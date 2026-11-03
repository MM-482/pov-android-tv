package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import dev.pov.tv.subtitles.CameoSavedSubtitle
import dev.pov.tv.subtitles.CameoSavedSubtitles
import dev.pov.tv.subtitles.DownloadedSubtitle

data class CameoConnectionSpec(val kind: String, val label: String, val category: String,
    val fields: List<String>, val supportsSources: Boolean = false, val helpUrl: String = "") {
    val id: String get() = kind
}

/** Credentials are stored only inside SecureStore, never in a saved media descriptor. */
data class CameoConnection(val id: String = "", val kind: String, val name: String = "", val url: String = "",
    val token: String = "", val username: String = "", val password: String = "", val clientId: String = "",
    val userId: String = "", val revision: String = "", val apiKey: String = "") {
    override fun toString(): String = "CameoConnection($id, $kind, $name)"
    internal fun storedJson() = JSONObject().put("id", id).put("kind", kind).put("name", name).put("url", url)
        .put("token", token).put("username", username).put("password", password).put("clientId", clientId)
        .put("userId", userId).put("revision", revision).put("apiKey", apiKey)
    companion object {
        internal fun fromStoredJson(j: JSONObject) = CameoConnection(j.optString("id"), j.optString("kind"), j.optString("name"),
            j.optString("url"), j.optString("token"), j.optString("username"), j.optString("password"),
            j.optString("clientId"), j.optString("userId"), j.optString("revision"), j.optString("apiKey"))
    }
}

/** Exact server/account/file/edition identity. Contains neither credentials nor a resolved playback URL. */
data class CameoServiceItem(val id: String, val connectionId: String, val title: String, val folder: Boolean = false,
    val type: String = "video", val poster: String = "", val remoteId: String = id, val mediaSourceId: String = "",
    val size: Long = 0, val parentId: String = "", val identity: PlaybackIdentity? = null, val detail: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("connectionId", connectionId).put("title", title)
        .put("folder", folder).put("type", type).put("poster", poster).put("remoteId", remoteId)
        .put("mediaSourceId", mediaSourceId).put("size", size).put("parentId", parentId)
        .put("identity", identity?.toJson()).put("detail", detail)
    companion object {
        fun fromJson(j: JSONObject) = CameoServiceItem(j.getString("id"), j.getString("connectionId"), j.getString("title"),
            j.optBoolean("folder"), j.optString("type", "video"), j.optString("poster"), j.optString("remoteId", j.getString("id")),
            j.optString("mediaSourceId"), j.optLong("size"), j.optString("parentId"),
            j.optJSONObject("identity")?.let(PlaybackIdentity::fromJson), j.optString("detail"))
    }
}

/** Blocking worker-thread facade for extra desktop services and independent saved playback accounts. */
class CameoConnections internal constructor(private val credentials: ProviderCredentials,
    private val http: CameoServiceTransport = LiveCameoServiceTransport,
    private val providerHttp: ProviderTransport = LiveProviderTransport) {
    constructor(store: SecureStore) : this(EncryptedProviderCredentials(store))
    val specs: List<CameoConnectionSpec> get() = SPECS
    fun list(): List<CameoConnection> = synchronized(LOCK) { read() }
    fun get(id: String): CameoConnection = list().singleOrNull { it.id == id }
        ?: throw IllegalStateException("This connection is no longer saved. Connect the account again.")

    fun save(draft: CameoConnection): CameoConnection {
        require(SPECS.any { it.kind == draft.kind }) { "Choose a supported connection." }
        require(draft.name.length <= 100 && draft.name.none(Char::isISOControl)) { "Enter a shorter account name." }
        listOf(draft.token, draft.password, draft.username, draft.userId, draft.clientId, draft.apiKey).forEach {
            require(it.length <= 4096 && it.none { c -> c == '\r' || c == '\n' || c.code == 0 }) { "Enter valid account fields." }
        }
        (listOf(draft.apiKey, draft.clientId) + if (draft.kind == "easynews") emptyList() else listOf(draft.token)).forEach {
            require(it.isBlank() || it.trim().all { character -> character.code in 33..126 }) { "Enter a valid API key, access token and client ID." }
        }
        val before = synchronized(LOCK) { credentials.get(KEY) }
        val existing = if (draft.id.isBlank()) null else (readRaw(before).singleOrNull { it.id == draft.id }
            ?: throw IllegalStateException("This connection was removed. Add a new connection."))
        if (existing != null) require(existing.kind == draft.kind) { "Add a new connection to change service type." }
        val passwordLogin = draft.kind in setOf("jellyfin", "emby", "kitsu", "opensubtitles") && draft.password.isNotBlank()
        val preservedSubtitleBase = if (existing != null && draft.kind == "opensubtitles" && draft.url.isBlank() && draft.token == existing.token && draft.apiKey == existing.apiKey)
            existing.url else draft.url
        val clean = draft.copy(token = if (passwordLogin) "" else draft.token.trim(), username = draft.username.trim(), url = preservedSubtitleBase.trim(),
            userId = draft.userId.trim(), apiKey = draft.apiKey.trim(), clientId = draft.clientId.trim())
        val checked = when (clean.kind) {
            "easynews" -> {
                EasynewsNzb.validate(clean.username, clean.password.ifBlank { clean.token })
                clean.copy(name = clean.name.ifBlank { "Easynews · ${clean.username}" })
            }
            in PROVIDER_KINDS -> {
                val account = providerHub(clean).connect(clean.kind, if (clean.kind == "easynews") clean.password.ifBlank { clean.token } else clean.token, clean.username)
                clean.copy(name = clean.name.ifBlank { account.label })
            }
            in SERVER_KINDS -> CameoMediaServers(http).validate(clean)
            in ANIME_KINDS -> CameoAnimeTrackers(http).validate(clean)
            in MANUAL_TRACKER_KINDS -> CameoManualTrackers(http).validate(clean)
            in SUBTITLE_KINDS -> CameoSavedSubtitles(http).validate(clean)
            else -> error("Unsupported connection.")
        }
        val changedAccount = existing != null && existing.copy(id = "", name = "", revision = "") != checked.copy(id = "", name = "", revision = "")
        val saved = checked.copy(id = if (existing == null || changedAccount) UUID.randomUUID().toString() else existing.id,
            revision = if (existing != null && !changedAccount) existing.revision else UUID.randomUUID().toString())
        synchronized(LOCK) {
            check(credentials.get(KEY) == before) { "Your connections changed during verification. Please try again." }
            val all = readRaw(before).filterNot { it.id == existing?.id }.toMutableList()
            check(all.size < 100) { "Remove an unused account before adding another." }
            all += saved
            write(all)
        }
        return saved
    }
    fun remove(id: String) = synchronized(LOCK) { write(read().filterNot { it.id == id }) }

    fun browse(id: String, parentId: String = ""): List<CameoServiceItem> = withAccount(id) { c ->
        when (c.kind) {
            in SERVER_KINDS -> CameoMediaServers(guarded(c)).browse(c, parentId)
            in ANIME_KINDS -> CameoAnimeTrackers(guarded(c)).browse(c)
            in MANUAL_TRACKER_KINDS -> CameoManualTrackers(guarded(c)).browse(c, parentId)
            in SUBTITLE_KINDS -> throw IllegalStateException("Choose an identified movie or episode, then search subtitles with this account.")
            in PROVIDER_KINDS -> {
                val hub = providerHub(c, guardedProvider(c))
                if (parentId.startsWith("files:") || c.kind == "offcloud" && parentId.isNotBlank()) {
                    val folderId = parentId.removePrefix("files:")
                    hub.files(c.kind, folderId).map { file -> CameoServiceItem("video:${durableFileId(file)}", c.id, file.name,
                        remoteId = durableFileId(file), size = file.size, parentId = folderId) }
                } else hub.cloud(c.kind, parentId.removePrefix("dir:")).map { folder ->
                    CameoServiceItem((if (folder.directory) "dir:" else "files:") + folder.id, c.id, folder.name,
                        folder = true, remoteId = folder.id, detail = if (folder.ready) "" else "Processing")
                }
            }
            else -> error("This service has no browse operation.")
        }
    }
    fun resolve(id: String, item: CameoServiceItem, cancelled: () -> Boolean = { false }): ProviderPlayback = withAccount(id) { c ->
        checkCancelled(cancelled)
        require(item.connectionId == id && !item.folder) { "Choose a file from this account." }
        when (c.kind) {
            in SERVER_KINDS -> CameoMediaServers(guarded(c, cancelled)).resolve(c, item)
            "easynews" -> {
                val query = try { PlaybackIdentity.fromJson(JSONObject(item.parentId)) } catch (_: Exception) {
                    throw IllegalStateException("Search this Easynews title again before playing the saved result.")
                }
                val hub = providerHub(c, guardedProvider(c, cancelled))
                val file = hub.searchEasynews(query).singleOrNull { it.id == item.remoteId && it.name == item.title }
                    ?: throw IllegalStateException("This exact Easynews file is no longer available. Search the title again.")
                hub.playback(c.kind, file)
            }
            in PROVIDER_KINDS -> {
                val hub = providerHub(c, guardedProvider(c, cancelled))
                val file = hub.files(c.kind, item.parentId).singleOrNull { durableFileId(it) == item.remoteId && it.name == item.title }
                    ?: throw IllegalStateException("This exact service file is no longer available. Browse the folder again.")
                hub.playback(c.kind, file)
            }
            else -> throw IllegalStateException("This tracker item is metadata. Select a playable catalog title or local file.")
        }
    }
    fun prepare(id: String, source: SourceItem, identity: PlaybackIdentity, cancelled: () -> Boolean = { false }): ProviderPreparedSource = withAccount(id) { c ->
        checkCancelled(cancelled)
        require(c.kind in PROVIDER_KINDS && SPECS.first { it.kind == c.kind }.supportsSources) { "Choose a playback service that supports this source." }
        providerHub(c, guardedProvider(c, cancelled)).prepare(c.kind, source, identity, persistTransfer = true)
    }
    fun playback(id: String, file: ProviderFile, cancelled: () -> Boolean = { false }): ProviderPlayback = withAccount(id) { c ->
        checkCancelled(cancelled)
        require(c.kind in PROVIDER_KINDS) { "Choose the original playback account." }
        providerHub(c, guardedProvider(c, cancelled)).playback(c.kind, file)
    }
    fun fileItem(id: String, file: ProviderFile, folderId: String, identity: PlaybackIdentity? = null): CameoServiceItem {
        get(id)
        require(folderId.isNotBlank() && !file.direct) { "This is a temporary direct source. Save its catalog title instead of a private playback link." }
        return CameoServiceItem("video:${durableFileId(file)}", id, file.name, type = identity?.type ?: "video",
            remoteId = durableFileId(file), size = file.size, parentId = folderId, identity = identity)
    }
    fun searchEasynews(id: String, identity: PlaybackIdentity): List<CameoServiceItem> = withAccount(id) { c ->
        require(c.kind == "easynews") { "Choose an Easynews account." }
        val knownIdentity = identity.takeIf { it.imdbId.matches(Regex("tt[0-9]{5,12}")) }
        providerHub(c, guardedProvider(c)).searchEasynews(identity).map { file ->
            CameoServiceItem("easynews:${file.id}", c.id, file.name, type = knownIdentity?.type ?: "video",
                remoteId = file.id, size = file.size, parentId = identity.toJson().toString(), identity = knownIdentity)
        }
    }
    fun updateAnime(id: String, item: CameoServiceItem, progress: Int, status: String, score: Int = -1) = withAccount(id) { c ->
        require(item.connectionId == id) { "Choose a title from this account's own list." }
        CameoAnimeTrackers(guarded(c)).update(c, item, progress, status, score)
    }
    fun updateWatchlist(id: String, identity: PlaybackIdentity, added: Boolean) = withAccount(id) { c ->
        require(c.kind in MANUAL_TRACKER_KINDS) { "Choose Trakt, Simkl or MDBList for this action." }
        CameoManualTrackers(guarded(c)).watchlist(c, identity, added)
    }
    fun updateWatched(id: String, identity: PlaybackIdentity, watched: Boolean) = withAccount(id) { c ->
        require(c.kind in MANUAL_TRACKER_KINDS) { "Choose Trakt, Simkl or MDBList for this action." }
        CameoManualTrackers(guarded(c)).watched(c, identity, watched)
    }
    fun searchSubtitles(id: String, identity: PlaybackIdentity, language: String): List<CameoSavedSubtitle> = withAccount(id) { c ->
        require(c.kind in SUBTITLE_KINDS) { "Choose a saved subtitle account." }
        CameoSavedSubtitles(guarded(c)).search(c, identity, language)
    }
    fun downloadSubtitle(id: String, result: CameoSavedSubtitle, context: android.content.Context): DownloadedSubtitle = withAccount(id) { c ->
        require(c.id == result.connectionId && c.revision == result.revision && c.kind in SUBTITLE_KINDS) {
            "This subtitle belongs to a different or changed account. Search again."
        }
        CameoSavedSubtitles(guarded(c), { current(c) }).download(c, result, context)
    }
    private fun read() = readRaw(credentials.get(KEY))
    private fun durableFileId(file: ProviderFile): String = if (file.id.startsWith("https://", true) || file.id.startsWith("http://", true))
        "name:${file.name}" else file.id
    private fun readRaw(raw: String): List<CameoConnection> = if (raw.isBlank()) emptyList() else try {
        JSONArray(raw).providerObjects().map(CameoConnection::fromStoredJson)
    } catch (_: Exception) { throw IllegalStateException("Saved connections could not be read. Existing account data was preserved.") }
    private fun write(values: List<CameoConnection>) { credentials.putAll(mapOf(KEY to JSONArray().also { a -> values.forEach { a.put(it.storedJson()) } }.toString())) }
    private fun current(c: CameoConnection) { check(list().any { it.id == c.id && it.revision == c.revision }) { "The account changed. Choose this action again." } }
    private fun <T> withAccount(id: String, work: (CameoConnection) -> T): T { val c = get(id); val result = work(c); current(c); return result }
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw InterruptedException("This service action was canceled.")
    }
    private fun guarded(c: CameoConnection, cancelled: () -> Boolean = { false }) = object : CameoServiceTransport {
        override fun request(url: String, method: String, headers: Map<String, String>, body: String?, contentType: String): String {
            checkCancelled(cancelled); current(c); val value = http.request(url, method, headers, body, contentType); checkCancelled(cancelled); current(c); return value
        }
    }
    private fun guardedProvider(c: CameoConnection, cancelled: () -> Boolean = { false }) = object : ProviderTransport {
        override fun request(url: String, method: String, headers: Map<String, String>, fields: List<Pair<String, String>>?, json: JSONObject?): String {
            checkCancelled(cancelled); current(c); val value = providerHttp.request(url, method, headers, fields, json); checkCancelled(cancelled); current(c); return value
        }
    }
    private fun providerHub(c: CameoConnection, transport: ProviderTransport = providerHttp): ProviderHub {
        val values = mutableMapOf("${c.kind}.token" to if (c.kind == "easynews") c.password.ifBlank { c.token } else c.token,
            "${c.kind}.username" to c.username, "${c.kind}.account_revision" to c.revision)
        return ProviderHub(object : ProviderCredentials {
            override fun get(key: String) = values[key].orEmpty()
            override fun putAll(next: Map<String, String>) { values.putAll(next) }
        }, transport)
    }
    companion object {
        private const val KEY = "cameo.connections.v1"
        private val LOCK = Any()
        internal val PROVIDER_KINDS = ProviderHub.SPECS.map { it.id }.toSet()
        internal val SERVER_KINDS = setOf("jellyfin", "emby", "plex", "webdav", "seedr")
        internal val ANIME_KINDS = setOf("anilist", "myanimelist", "kitsu")
        val MANUAL_TRACKER_KINDS = setOf("trakt", "simkl", "mdblist")
        val SUBTITLE_KINDS = setOf("opensubtitles", "subdl", "subsource")
        val SPECS = ProviderHub.SPECS.map { CameoConnectionSpec(it.id, it.label, "playback",
            if (it.id == "easynews") listOf("name", "username", "password") else listOf("name", "token"), it.magnet, it.credentialUrl) } + listOf(
            CameoConnectionSpec("seedr", "Seedr WebDAV", "server", listOf("name", "username", "password"), helpUrl = "https://www.seedr.cc/"),
            CameoConnectionSpec("jellyfin", "Jellyfin", "server", listOf("name", "url", "username", "password", "token"), helpUrl = "https://jellyfin.org/docs/"),
            CameoConnectionSpec("emby", "Emby", "server", listOf("name", "url", "username", "password", "token", "userId"), helpUrl = "https://emby.media/support/articles/"),
            CameoConnectionSpec("plex", "Plex", "server", listOf("name", "url", "token"), helpUrl = "https://support.plex.tv/"),
            CameoConnectionSpec("webdav", "WebDAV", "server", listOf("name", "url", "username", "password")),
            CameoConnectionSpec("anilist", "AniList", "anime", listOf("name", "token"), helpUrl = "https://anilist.co/settings/developer"),
            CameoConnectionSpec("myanimelist", "MyAnimeList", "anime", listOf("name", "token"), helpUrl = "https://myanimelist.net/apiconfig"),
            CameoConnectionSpec("kitsu", "Kitsu", "anime", listOf("name", "username", "password", "token"), helpUrl = "https://kitsu.app/"),
            CameoConnectionSpec("trakt", "Trakt", "tracker", listOf("name", "token", "clientId"), helpUrl = "https://trakt.tv/oauth/applications"),
            CameoConnectionSpec("simkl", "Simkl", "tracker", listOf("name", "token", "clientId"), helpUrl = "https://simkl.com/settings/developer/"),
            CameoConnectionSpec("mdblist", "MDBList", "tracker", listOf("name", "apiKey", "token"), helpUrl = "https://mdblist.com/preferences/"),
            CameoConnectionSpec("opensubtitles", "OpenSubtitles", "subtitles", listOf("name", "apiKey", "username", "password", "token"), helpUrl = "https://www.opensubtitles.com/consumers"),
            CameoConnectionSpec("subdl", "SubDL", "subtitles", listOf("name", "apiKey"), helpUrl = "https://subdl.com/panel/api"),
            CameoConnectionSpec("subsource", "SubSource", "subtitles", listOf("name", "apiKey"), helpUrl = "https://subsource.net/dashboard"))
    }
}
