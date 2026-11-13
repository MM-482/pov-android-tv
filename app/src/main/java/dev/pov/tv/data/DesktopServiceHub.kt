package dev.pov.tv.data

import android.util.Base64
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.util.UUID

data class DesktopServiceSpec(val id: String, val label: String, val category: String,
    val requiresUrl: Boolean = false, val requiresToken: Boolean = false, val usernamePassword: Boolean = false)
data class DesktopConnectionInput(val kind: String, val label: String = "", val url: String = "", val token: String = "",
    val username: String = "", val password: String = "", val userId: String = "", val id: String = "")
data class DesktopConnectionSummary(val id: String, val kind: String, val label: String, val accountName: String, val url: String)
data class DesktopMediaEntry(val id: String, val name: String, val directory: Boolean = false,
    val media: MediaItemData? = null, val remotePath: String = "", val mediaSourceId: String = "", val size: Long = 0, val accountRevision: String = "")

internal interface DesktopConnectionStore { fun get(): String; fun put(value: String) }

/** Desktop media-server/anime adapters. Saved credentials remain inside Android Keystore storage. */
class DesktopServiceHub internal constructor(private val store: DesktopConnectionStore,
    private val http: DesktopTransport = LiveDesktopTransport) {
    constructor(store: SecureStore) : this(object : DesktopConnectionStore {
        override fun get() = store.get(STORE_KEY)
        override fun put(value: String) = store.put(STORE_KEY, value)
    })
    val specs get() = SPECS

    private data class Account(val input: DesktopConnectionInput, val accountName: String, val revision: String) {
        fun summary() = DesktopConnectionSummary(input.id, input.kind, input.label, accountName, input.url)
        fun json() = JSONObject().put("id", input.id).put("kind", input.kind).put("label", input.label).put("url", input.url)
            .put("token", input.token).put("username", input.username).put("password", input.password).put("userId", input.userId)
            .put("accountName", accountName).put("revision", revision)
    }

    fun connections(): List<DesktopConnectionSummary> = synchronized(LOCK) { accounts().map { it.summary() } }
    fun accountRevision(connectionId: String): String = synchronized(LOCK) {
        accounts().singleOrNull { it.input.id == connectionId }?.revision ?: error("This connection is no longer available.")
    }

    fun connect(input: DesktopConnectionInput): DesktopConnectionSummary {
        val spec = SPECS.firstOrNull { it.id == input.kind } ?: error("Choose a supported service.")
        require(listOf(input.token, input.username, input.password, input.userId, input.label).all { value ->
            value.length <= 4096 && value.none { it.code < 32 || it.code == 127 }
        } && input.url.length <= 8192) { "Enter valid service credentials and server address." }
        val id = input.id.ifBlank { UUID.randomUUID().toString() }
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid saved connection identity." }
        val before = synchronized(LOCK) { accounts().firstOrNull { it.input.id == id } }
        require(input.id.isBlank() || before?.input?.kind == input.kind) { "This saved connection changed. Reopen its settings." }
        val previous = before?.input
        val replaceLogin = input.password.isNotBlank() && input.token.isBlank() && input.kind in setOf("jellyfin", "emby", "kitsu")
        var clean = input.copy(id = id, label = input.label.trim().take(120).ifBlank { spec.label },
            token = if (replaceLogin) "" else input.token.trim().ifBlank { previous?.token.orEmpty() },
            username = input.username.trim().ifBlank { previous?.username.orEmpty() },
            password = input.password.ifBlank { previous?.password.orEmpty() },
            userId = input.userId.trim().ifBlank { previous?.userId.orEmpty() },
            url = if (input.kind == "seedr") "https://dav.seedr.cc/" else input.url.trim().ifBlank { previous?.url.orEmpty() })
        if (spec.requiresUrl || clean.kind == "seedr") clean = clean.copy(url = DesktopUrlPolicy.endpoint(clean.url).toASCIIString())
        if (spec.requiresToken) require(clean.token.isNotBlank()) { "Enter your service access token." }
        val checked = validate(clean)
        // A read-only verification or label edit does not replace an account session.
        val validated = if (before != null && before.input.copy(label = checked.input.label) == checked.input)
            checked.copy(revision = before.revision) else checked
        synchronized(LOCK) {
            val current = accounts()
            check(current.firstOrNull { it.input.id == id } == before) { "The connection changed while checking this account. Try again." }
            require(current.size < 100 || before != null) { "Remove a saved connection before adding another." }
            save(current.filterNot { it.input.id == id } + validated)
        }
        return validated.summary()
    }

    fun disconnect(connectionId: String) = synchronized(LOCK) { save(accounts().filterNot { it.input.id == connectionId }) }

    fun verify(connectionId: String): DesktopConnectionSummary {
        val existing = synchronized(LOCK) { accounts().singleOrNull { it.input.id == connectionId } }
            ?: error("This connection is no longer available.")
        return connect(existing.input)
    }

    fun browse(connectionId: String, parentId: String = "", search: String = ""): List<DesktopMediaEntry> = session(connectionId) { account ->
        (when (account.input.kind) {
            "webdav", "seedr" -> dav(account, parentId)
            "plex" -> plex(account, parentId)
            "jellyfin", "emby" -> mediaBrowser(account, parentId, search)
            "anilist" -> aniList(account)
            "myanimelist" -> myAnimeList(account)
            "kitsu" -> kitsuList(account)
            else -> error("This connection has no browsable library.")
        }).map { it.copy(accountRevision = account.revision) }
    }

    fun playback(connectionId: String, entry: DesktopMediaEntry, expectedRevision: String? = null): ProviderPlayback = session(connectionId, expectedRevision ?: entry.accountRevision.takeIf { it.isNotBlank() }) { account ->
        require(entry.accountRevision.isBlank() || entry.accountRevision == account.revision) { "The service account changed. Reopen this library." }
        require(!entry.directory) { "Choose a video file." }
        val c = account.input
        when (c.kind) {
            "webdav", "seedr" -> {
                val url = server(c, entry.remotePath.ifBlank { entry.id })
                require(URI(url).rawQuery == null) { "Choose the file's stable WebDAV path, not a temporary download link." }
                ProviderPlayback(url, playbackHeaders(c))
            }
            "plex" -> {
                require(entry.remotePath.trimStart('/').startsWith("library/parts/")) { "Choose an exact Plex media file." }
                ProviderPlayback(server(c, entry.remotePath.trimStart('/')), playbackHeaders(c))
            }
            "jellyfin", "emby" -> {
                validId(entry.id)
                val response = json(account, server(c, "Items/${Net.encode(entry.id)}/PlaybackInfo") + "?UserId=${Net.encode(c.userId)}")
                val sources = response.optJSONArray("MediaSources").objects()
                val source = if (entry.mediaSourceId.isBlank()) sources.singleOrNull() else sources.singleOrNull { it.text("Id") == entry.mediaSourceId }
                require(source != null) { "Choose the exact available edition from your server library." }
                require(!source.optBoolean("RequiresOpening")) { "This source requires a live server session. Choose a stored video." }
                val sourceId = source.text("Id").also(::validId)
                ProviderPlayback(server(c, "Videos/${Net.encode(entry.id)}/stream") +
                    "?Static=true&MediaSourceId=${Net.encode(sourceId)}", playbackHeaders(c))
            }
            else -> throw ApiException(0, "This is an anime tracking library. Choose a catalog source to play this title.")
        }
    }

    fun setAnimeProgress(connectionId: String, entry: DesktopMediaEntry, progress: Int, status: String, score: Int = -1) = session(connectionId, entry.accountRevision.takeIf { it.isNotBlank() }) { account ->
        require(progress in 0..1_000_000 && score in -1..10) { "Enter a valid episode count and score from 0 to 10." }
        val state = STATUSES.indexOf(status)
        require(state >= 0) { "Choose a supported anime list status." }
        val c = account.input
        val prefix = when (c.kind) { "anilist" -> "anilist:"; "myanimelist" -> "mal:"; "kitsu" -> "kitsu:"; else -> "" }
        require(prefix.isNotEmpty() && entry.id.startsWith(prefix)) { "Open the anime from this service's own list before updating progress." }
        val number = entry.id.removePrefix(prefix).toIntOrNull()
        require(number != null && number > 0) { "This anime has no valid service identity." }
        when (c.kind) {
            "anilist" -> {
                val states = listOf("PLANNING", "CURRENT", "COMPLETED", "PAUSED", "DROPPED", "REPEATING")
                val variables = JSONObject().put("mediaId", number).put("progress", progress).put("status", states[state])
                if (score >= 0) variables.put("scoreRaw", score * 10)
                val response = graph(account, "mutation(\$mediaId:Int!,\$progress:Int,\$status:MediaListStatus,\$scoreRaw:Int){SaveMediaListEntry(mediaId:\$mediaId,progress:\$progress,status:\$status,scoreRaw:\$scoreRaw){id mediaId progress status}}", variables)
                    .getJSONObject("SaveMediaListEntry")
                check(response.optInt("mediaId") == number && response.optInt("progress", -1) == progress) { "AniList did not confirm the requested progress." }
            }
            "myanimelist" -> {
                val states = listOf("plan_to_watch", "watching", "completed", "on_hold", "dropped", "watching")
                val fields = mutableListOf("num_watched_episodes" to progress.toString(), "status" to states[state], "is_rewatching" to (state == 5).toString())
                if (score >= 0) fields += "score" to score.toString()
                val raw = request(account, "https://api.myanimelist.net/v2/anime/$number/my_list_status", "PATCH", bearer(c),
                    fields.joinToString("&") { Net.encode(it.first) + "=" + Net.encode(it.second) }, "application/x-www-form-urlencoded; charset=utf-8")
                check(providerObject(raw).optInt("num_episodes_watched", -1) == progress) { "MyAnimeList did not confirm the requested progress." }
            }
            "kitsu" -> {
                val states = listOf("planned", "current", "completed", "on_hold", "dropped", "current")
                val prior = json(account, "https://kitsu.app/api/edge/library-entries?filter[userId]=${Net.encode(c.userId)}&filter[animeId]=$number&page[limit]=1", extra = kitsuHeaders(c))
                    .optJSONArray("data").objects().singleOrNull()
                val attributes = JSONObject().put("progress", progress).put("status", states[state]).put("reconsuming", state == 5)
                if (score >= 0) attributes.put("ratingTwenty", if (score == 0) JSONObject.NULL else score * 2)
                val data = JSONObject().put("type", "libraryEntries").put("attributes", attributes)
                val entryId = prior?.text("id").orEmpty()
                if (entryId.isNotBlank()) data.put("id", entryId.also(::validId)) else data.put("relationships", JSONObject()
                    .put("user", JSONObject().put("data", JSONObject().put("type", "users").put("id", c.userId)))
                    .put("anime", JSONObject().put("data", JSONObject().put("type", "anime").put("id", number.toString()))))
                val raw = request(account, "https://kitsu.app/api/edge/library-entries" + if (entryId.isBlank()) "" else "/${Net.encode(entryId)}",
                    if (entryId.isBlank()) "POST" else "PATCH", kitsuHeaders(c), JSONObject().put("data", data).toString(), "application/vnd.api+json")
                val updated = providerObject(raw).optJSONObject("data")?.optJSONObject("attributes")
                check(updated?.optInt("progress", -1) == progress) { "Kitsu did not confirm the requested progress." }
            }
        }
    }

    private fun validate(input: DesktopConnectionInput): Account {
        var c = input
        fun call(url: String, method: String = "GET", header: Map<String, String> = headers(c), body: JSONObject? = null) =
            providerObject(http.request(url, method, header, body?.toString()))
        val name = when (c.kind) {
            "webdav", "seedr" -> {
                require(c.username.isNotBlank() && c.password.isNotBlank() && ':' !in c.username) { "Enter the WebDAV username and password." }
                dav(Account(c, "", ""), "", checking = true)
                c.username
            }
            "plex" -> {
                // Validate against the configured server, not an unrelated public endpoint.
                val result = call(server(c, "library/sections"))
                require(result.optJSONObject("MediaContainer") != null) { "Plex did not confirm this account and server." }
                c.username.ifBlank { "Plex account" }
            }
            "jellyfin", "emby" -> {
                val result = if (c.token.isBlank()) {
                    require(c.username.isNotBlank() && c.password.isNotBlank()) { "Enter a server token or username and password." }
                    val login = call(server(c, "Users/AuthenticateByName"), "POST", body = JSONObject().put("Username", c.username).put("Pw", c.password))
                    val user = login.optJSONObject("User") ?: error("The server did not identify this account.")
                    c = c.copy(token = login.text("AccessToken"), userId = user.text("Id"), password = "")
                    require(c.token.isNotBlank() && c.userId.isNotBlank()) { "The server rejected the account." }
                    user
                } else {
                    if (c.kind == "emby" && c.userId.isBlank()) c = c.copy(userId = c.username)
                    if (c.kind == "emby") require(c.userId.isNotBlank()) { "Emby access tokens also need the user ID." }
                    call(server(c, if (c.kind == "emby") "Users/${Net.encode(c.userId)}" else "Users/Me"))
                }
                c = c.copy(userId = result.text("Id"))
                require(c.userId.isNotBlank()) { "The server did not identify this user." }
                result.text("Name")
            }
            "anilist" -> {
                val response = call("https://graphql.anilist.co", "POST", bearer(c), JSONObject().put("query", "query { Viewer { id name } }"))
                checkGraph(response)
                val viewer = response.optJSONObject("data")?.optJSONObject("Viewer") ?: error("AniList did not identify this account.")
                c = c.copy(userId = viewer.text("id"))
                viewer.text("name")
            }
            "myanimelist" -> call("https://api.myanimelist.net/v2/users/@me", header = bearer(c)).text("name")
            "kitsu" -> {
                if (c.token.isBlank()) {
                    require(c.username.isNotBlank() && c.password.isNotBlank()) { "Enter a Kitsu token or username and password." }
                    val form = listOf("grant_type" to "password", "username" to c.username, "password" to c.password)
                        .joinToString("&") { Net.encode(it.first) + "=" + Net.encode(it.second) }
                    val login = providerObject(http.request("https://kitsu.app/api/oauth/token", "POST", emptyMap(), form, "application/x-www-form-urlencoded"))
                    c = c.copy(token = login.text("access_token"), password = "")
                    require(c.token.isNotBlank()) { "Kitsu did not accept this account." }
                }
                val user = call("https://kitsu.app/api/edge/users?filter[self]=true", header = kitsuHeaders(c)).optJSONArray("data").objects().singleOrNull()
                    ?: error("Kitsu did not identify this account.")
                c = c.copy(userId = user.text("id"))
                user.optJSONObject("attributes")?.text("name").orEmpty()
            }
            else -> error("Choose a supported service.")
        }
        require(name.isNotBlank()) { "The service did not confirm an account." }
        return Account(c, name.take(160), UUID.randomUUID().toString())
    }

    private fun mediaBrowser(account: Account, parent: String, search: String): List<DesktopMediaEntry> {
        val c = account.input
        if (parent.isNotBlank()) validId(parent)
        val route = if (c.kind == "jellyfin") "Items" else "Users/${Net.encode(c.userId)}/Items"
        val response = json(account, server(c, route) + "?UserId=${Net.encode(c.userId)}&Fields=MediaSources,Overview,ProviderIds" +
            "&IncludeItemTypes=Movie,Episode,Series,Season,Folder,BoxSet&Limit=250&Recursive=${parent.isBlank()}" +
            (if (parent.isNotBlank()) "&ParentId=${Net.encode(parent)}" else "") +
            (if (search.isNotBlank()) "&SearchTerm=${Net.encode(search.take(200))}" else ""))
        return response.optJSONArray("Items").objects().flatMap { item ->
            val id = item.text("Id").also(::validId)
            val name = item.text("Name")
            val folder = item.optBoolean("IsFolder")
            val sources = item.optJSONArray("MediaSources").objects()
            if (folder || sources.isEmpty()) listOf(DesktopMediaEntry(id, name, folder, media = serverMedia(item, name, id)))
            else sources.map { source -> DesktopMediaEntry(id, name + if (sources.size > 1) " · ${source.text("Name")}" else "",
                media = serverMedia(item, name, id), mediaSourceId = source.text("Id"), size = source.optLong("Size")) }
        }
    }

    private fun serverMedia(item: JSONObject, name: String, id: String): MediaItemData {
        val imdb = item.optJSONObject("ProviderIds")?.text("Imdb").orEmpty().takeIf { it.matches(Regex("tt[0-9]{5,12}")) }
        val episode = if (item.text("Type") == "Episode" && item.optInt("IndexNumber") > 0)
            listOf(EpisodeData(item.optInt("ParentIndexNumber"), item.optInt("IndexNumber"), name)) else emptyList()
        return MediaItemData(imdb ?: "server:$id", if (item.text("Type") == "Movie") "movie" else "series", name,
            year = item.text("ProductionYear"), description = item.text("Overview"), episodes = episode)
    }

    private fun plex(account: Account, parent: String): List<DesktopMediaEntry> {
        val route = when { parent.isBlank() -> "library/sections"; parent.startsWith("/library/") -> parent.trimStart('/'); else -> {
            validId(parent); "library/sections/${Net.encode(parent)}/all"
        } }
        val container = json(account, server(account.input, route) + "?X-Plex-Container-Size=250").optJSONObject("MediaContainer")
            ?: throw ApiException(0, "Plex returned an unreadable library.")
        if (parent.isBlank()) return container.optJSONArray("Directory").objects().map { DesktopMediaEntry(it.text("key"), it.text("title"), true) }
        return container.optJSONArray("Metadata").objects().flatMap { item ->
            if (item.text("type") in setOf("show", "season", "collection")) listOf(DesktopMediaEntry(item.text("key"), item.text("title"), true))
            else item.optJSONArray("Media").objects().flatMap { media -> media.optJSONArray("Part").objects().map { part ->
                DesktopMediaEntry(item.text("ratingKey") + ":" + media.text("id") + ":" + part.text("id"), item.text("title"),
                    media = MediaItemData("plex:${item.text("ratingKey")}", "movie", item.text("title"), year = item.text("year"), description = item.text("summary")),
                    remotePath = part.text("key"), mediaSourceId = media.text("id"), size = part.optLong("size"))
            } }
        }
    }

    private fun dav(account: Account, parent: String, checking: Boolean = false): List<DesktopMediaEntry> {
        val c = account.input
        val root = DesktopUrlPolicy.endpoint(c.url)
        val target = DesktopUrlPolicy.within(root, parent.ifBlank { root.toASCIIString() })
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"
        val raw = if (checking) http.request(target.toASCIIString(), "PROPFIND", headers(c) + ("Depth" to "1"), body, "application/xml")
            else request(account, target.toASCIIString(), "PROPFIND", headers(c) + ("Depth" to "1"), body, "application/xml")
        return parseDav(raw, root, target)
    }

    internal fun parseDav(xml: String, root: URI, target: URI): List<DesktopMediaEntry> {
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "The WebDAV server returned unsupported XML declarations." }
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val result = mutableListOf<DesktopMediaEntry>()
        var href = ""; var name = ""; var size = 0L; var directory = false; var status = ""; var field = ""
        var pendingName = ""; var pendingSize = 0L; var pendingDirectory = false
        var inPropstat = false; var accepted = false; var multistatus = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.DOCDECL) error("WebDAV XML declarations are unsupported.")
            if (event == XmlPullParser.START_TAG) {
                field = if (parser.namespace == "DAV:") parser.name else ""
                if (parser.namespace == "DAV:") when (field) {
                    "multistatus" -> multistatus = true
                    "response" -> { href = ""; name = ""; size = 0; directory = false; accepted = false }
                    "propstat" -> { inPropstat = true; status = ""; pendingName = ""; pendingSize = 0; pendingDirectory = false }
                    "collection" -> if (inPropstat) pendingDirectory = true
                }
            } else if (event == XmlPullParser.TEXT || event == XmlPullParser.ENTITY_REF) {
                when (field) { "href" -> if (!inPropstat) href += parser.text; "displayname" -> if (inPropstat) pendingName += parser.text
                    "getcontentlength" -> if (inPropstat) pendingSize = parser.text.trim().toLongOrNull() ?: 0
                    "status" -> if (inPropstat) status += parser.text }
            } else if (event == XmlPullParser.END_TAG && parser.namespace == "DAV:") {
                when (parser.name) {
                    "propstat" -> {
                        if (Regex("\\s200(?:\\s|$)").containsMatchIn(status)) {
                            accepted = true
                            if (pendingName.isNotBlank()) name = pendingName
                            if (pendingSize > 0) size = pendingSize
                            directory = directory || pendingDirectory
                        }
                        inPropstat = false
                    }
                    "response" -> if (accepted && href.isNotBlank()) {
                        val uri = try { DesktopUrlPolicy.within(root, target.resolve(href.trim()).toASCIIString()).takeIf { it.rawQuery == null } } catch (_: Exception) { null }
                        if (uri != null && uri.path.trimEnd('/') != target.path.trimEnd('/')) {
                            require(result.size < 10_000) { "This WebDAV folder is too large. Choose a smaller folder." }
                            result += DesktopMediaEntry(uri.rawPath, name.trim().ifBlank { uri.path.trimEnd('/').substringAfterLast('/') }, directory,
                                remotePath = uri.toASCIIString(), size = size)
                        }
                    }
                }
                field = ""
            }
            event = parser.nextToken()
        }
        require(multistatus) { "The server did not return a WebDAV directory response." }
        return result.distinctBy { it.id }
    }

    private fun aniList(account: Account): List<DesktopMediaEntry> {
        val id = account.input.userId.toIntOrNull() ?: error("AniList did not identify your account.")
        val data = graph(account, "query(\$id:Int!){MediaListCollection(userId:\$id,type:ANIME){lists{entries{media{id format title{userPreferred}coverImage{large}description startDate{year}}}}}}", JSONObject().put("id", id))
        return data.optJSONObject("MediaListCollection")?.optJSONArray("lists").objects().flatMap { list ->
            list.optJSONArray("entries").objects().mapNotNull { entry -> entry.optJSONObject("media")?.let { item ->
                val mediaId = "anilist:${item.text("id")}"; val title = item.optJSONObject("title")?.text("userPreferred").orEmpty()
                DesktopMediaEntry(mediaId, title, media = MediaItemData(mediaId, if (item.text("format") == "MOVIE") "movie" else "series", title,
                    year = item.optJSONObject("startDate")?.text("year").orEmpty(), poster = item.optJSONObject("coverImage")?.text("large").orEmpty(),
                    description = item.text("description").replace(Regex("<[^>]*>"), "")))
            } }
        }.distinctBy { it.id }
    }

    private fun myAnimeList(account: Account): List<DesktopMediaEntry> {
        val result = mutableListOf<DesktopMediaEntry>()
        var offset = 0
        repeat(100) {
            val raw = json(account, "https://api.myanimelist.net/v2/users/@me/animelist?limit=100&offset=$offset&fields=main_picture,synopsis,mean,media_type,start_date", extra = bearer(account.input))
            val rows = raw.optJSONArray("data").objects()
            rows.forEach { row -> row.optJSONObject("node")?.let { item ->
                val id = "mal:${item.text("id")}"; val title = item.text("title")
                result += DesktopMediaEntry(id, title, media = MediaItemData(id, if (item.text("media_type") == "movie") "movie" else "series", title,
                    poster = item.optJSONObject("main_picture")?.text("medium").orEmpty(), description = item.text("synopsis"), rating = item.text("mean"), year = item.text("start_date").take(4)))
            } }
            if (rows.size < 100 || raw.optJSONObject("paging")?.text("next").isNullOrBlank()) return result.distinctBy { it.id }
            offset += 100
        }
        return result.distinctBy { it.id }
    }

    private fun kitsuList(account: Account): List<DesktopMediaEntry> {
        val result = mutableListOf<DesktopMediaEntry>()
        var offset = 0
        repeat(100) {
            val raw = json(account, "https://kitsu.app/api/edge/library-entries?filter[userId]=${Net.encode(account.input.userId)}&filter[kind]=anime&include=anime&page[limit]=500&page[offset]=$offset", extra = kitsuHeaders(account.input))
            raw.optJSONArray("included").objects().filter { it.text("type") == "anime" }.forEach { row ->
                val item = row.optJSONObject("attributes") ?: return@forEach
                val id = "kitsu:${row.text("id")}"; val title = item.text("canonicalTitle")
                result += DesktopMediaEntry(id, title, media = MediaItemData(id, if (item.text("subtype").equals("movie", true)) "movie" else "series", title,
                    poster = item.optJSONObject("posterImage")?.text("large").orEmpty(), background = item.optJSONObject("coverImage")?.text("large").orEmpty(),
                    year = item.text("startDate").take(4), description = item.text("synopsis"), rating = item.text("averageRating")))
            }
            if (raw.optJSONObject("links")?.text("next").isNullOrBlank()) return result.distinctBy { it.id }
            offset += 500
        }
        return result.distinctBy { it.id }
    }

    private fun graph(account: Account, query: String, variables: JSONObject = JSONObject()): JSONObject {
        val response = json(account, "https://graphql.anilist.co", "POST", JSONObject().put("query", query).put("variables", variables), bearer(account.input))
        checkGraph(response)
        return response.optJSONObject("data") ?: throw ApiException(0, "AniList returned an unreadable response.")
    }
    private fun checkGraph(raw: JSONObject) {
        if ((raw.optJSONArray("errors")?.length() ?: 0) > 0) throw ApiException(0, "AniList rejected this account or list update.")
    }

    private fun request(account: Account, url: String, method: String = "GET", headers: Map<String, String> = headers(account.input), body: String? = null,
        contentType: String = "application/json; charset=utf-8"): String {
        current(account)
        val result = http.request(url, method, headers, body, contentType)
        current(account)
        return result
    }
    private fun json(account: Account, url: String, method: String = "GET", body: JSONObject? = null, extra: Map<String, String> = headers(account.input)) =
        providerObject(request(account, url, method, extra, body?.toString()))
    private fun <T> session(id: String, expectedRevision: String? = null, work: (Account) -> T): T {
        val account = synchronized(LOCK) { accounts().singleOrNull { it.input.id == id } } ?: error("This connection is no longer available.")
        check(expectedRevision == null || account.revision == expectedRevision) { "The service account changed. Reopen this library." }
        val result = work(account)
        current(account)
        return result
    }
    private fun current(account: Account) {
        check(synchronized(LOCK) { accounts().any { it.input.id == account.input.id && it.revision == account.revision } }) {
            "The service account changed. Reopen this library."
        }
    }
    private fun accounts(): List<Account> {
        val raw = store.get()
        if (raw.isBlank()) return emptyList()
        return try {
            require(raw.length <= 2 * 1024 * 1024)
            val array = JSONArray(raw)
            require(array.length() <= 100)
            val values = (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                Account(DesktopConnectionInput(row.getString("kind"), row.getString("label"), row.getString("url"), row.getString("token"),
                    row.getString("username"), row.getString("password"), row.getString("userId"), row.getString("id")), row.getString("accountName"), row.getString("revision")).also { account ->
                    require(SPECS.any { it.id == account.input.kind } && account.input.id.matches(Regex("[a-fA-F0-9-]{36}")) &&
                        account.input.label.isNotBlank() && account.accountName.isNotBlank() && account.revision.isNotBlank())
                }
            }
            require(values.map { it.input.id }.toSet().size == values.size)
            values
        }
        catch (_: Exception) { throw IllegalStateException("Saved service connections could not be read. Account data was preserved.") }
    }
    private fun save(accounts: List<Account>) {
        val value = JSONArray().apply { accounts.forEach { put(it.json()) } }.toString()
        require(value.length <= 2 * 1024 * 1024) { "Saved service connections are too large. Remove a connection before adding another." }
        store.put(value)
    }
    private fun server(c: DesktopConnectionInput, path: String) = DesktopUrlPolicy.within(DesktopUrlPolicy.endpoint(c.url), path).toASCIIString()
    private fun bearer(c: DesktopConnectionInput) = mapOf("Authorization" to "Bearer ${c.token}")
    private fun kitsuHeaders(c: DesktopConnectionInput) = bearer(c) + ("Accept" to "application/vnd.api+json")
    // Client-identification headers belong to the server API. The media origin receives only
    // the authorization needed to stream, which also fits the player's explicit header policy.
    private fun playbackHeaders(c: DesktopConnectionInput): Map<String, String> = when (c.kind) {
        "plex" -> mapOf("X-Plex-Token" to c.token)
        "jellyfin", "emby" -> mapOf("X-Emby-Token" to c.token)
        else -> headers(c)
    }
    private fun headers(c: DesktopConnectionInput): Map<String, String> = when (c.kind) {
        "webdav", "seedr" -> mapOf("Authorization" to "Basic " + Base64.encodeToString("${c.username}:${c.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        "plex" -> mapOf("X-Plex-Token" to c.token, "X-Plex-Product" to "Cameo", "X-Plex-Client-Identifier" to "cameo-android", "Accept" to "application/json")
        "jellyfin", "emby" -> mapOf("X-Emby-Authorization" to "MediaBrowser Client=\"Cameo\", Device=\"Android\", DeviceId=\"cameo-android\", Version=\"0.4\"") +
            if (c.token.isBlank()) emptyMap() else mapOf("X-Emby-Token" to c.token, "X-MediaBrowser-Token" to c.token)
        "kitsu" -> kitsuHeaders(c)
        else -> bearer(c)
    }
    private fun validId(value: String) { require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}"))) { "The service returned an invalid item identity." } }
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it == "null" }.orEmpty()
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        private const val STORE_KEY = "cameo.desktop.connections.v1"
        private val LOCK = Any()
        val STATUSES = listOf("planned", "watching", "completed", "onHold", "dropped", "repeating")
        val SPECS = listOf(
            DesktopServiceSpec("jellyfin", "Jellyfin", "server", true, usernamePassword = true),
            DesktopServiceSpec("emby", "Emby", "server", true, usernamePassword = true),
            DesktopServiceSpec("plex", "Plex", "server", true, true),
            DesktopServiceSpec("webdav", "WebDAV", "server", true, usernamePassword = true),
            DesktopServiceSpec("seedr", "Seedr", "cloud", usernamePassword = true),
            DesktopServiceSpec("anilist", "AniList", "anime", requiresToken = true),
            DesktopServiceSpec("myanimelist", "MyAnimeList", "anime", requiresToken = true),
            DesktopServiceSpec("kitsu", "Kitsu", "anime", usernamePassword = true))
    }
}
