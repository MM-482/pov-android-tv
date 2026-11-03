package dev.pov.tv.subtitles

import android.content.Context
import android.net.Uri
import dev.pov.tv.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL
import java.util.UUID

/** In-memory search result bound to the exact saved account that returned it. */
data class CameoSavedSubtitle(val connectionId: String, val revision: String, val accountName: String, val result: SubtitleResult) {
    override fun toString() = "CameoSavedSubtitle($connectionId, ${result.provider}, ${result.id})"
}

/** Independent account adapter; it never reads or writes the legacy subtitle credential keys. */
internal class CameoSavedSubtitles(private val api: CameoServiceTransport, private val checkCurrent: () -> Unit = {},
    private val downloads: SubtitleTransport = SubtitleHttp()) {
    private fun key(c: CameoConnection) = c.apiKey.ifBlank { if (c.kind != "opensubtitles") c.token else "" }.also {
        require(it.isNotBlank() && it.length <= 4096 && it.all { char -> char.code in 33..126 }) { "Enter a valid ${c.kind} application API key." }
    }
    private fun openBase(value: String): String {
        if (value.isBlank()) return OPEN_BASE
        val url = URI(if ("://" in value) value else "https://$value")
        require(url.scheme == "https" && url.host in OPEN_HOSTS && url.port in setOf(-1, 443) && url.userInfo == null && url.fragment == null && url.query == null) {
            "OpenSubtitles must use its official secure API host."
        }
        return "https://${url.host}/api/v1"
    }
    private fun openHeaders(c: CameoConnection) = mapOf("Api-Key" to key(c)) +
        if (c.token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${c.token}")
    fun validate(draft: CameoConnection): CameoConnection {
        var c = draft
        key(c)
        when (c.kind) {
            "opensubtitles" -> {
                if (c.token.isBlank() && (c.username.isNotBlank() || c.password.isNotBlank())) {
                    require(c.username.isNotBlank() && c.password.isNotBlank()) { "Enter both OpenSubtitles username and password, or an account access token." }
                    val login = providerObject(api.request("$OPEN_BASE/login", "POST", openHeaders(c),
                        JSONObject().put("username", c.username).put("password", c.password).toString()))
                    val token = login.providerString("token")
                    check(token.isNotBlank()) { "OpenSubtitles did not accept this account." }
                    c = c.copy(token = token, password = "", url = openBase(login.providerString("base_url")))
                }
                val base = openBase(c.url)
                val info = providerObject(api.request("$base/infos/${if (c.token.isBlank()) "languages" else "user"}", headers = openHeaders(c)))
                check(if (c.token.isBlank()) (info.optJSONArray("data")?.length() ?: 0) > 0 else info.optJSONObject("data") != null) {
                    "OpenSubtitles did not confirm this configuration."
                }
                val label = if (c.token.isBlank()) "OpenSubtitles search" else "OpenSubtitles account"
                return c.copy(name = c.name.ifBlank { label }, url = base, password = "")
            }
            "subdl" -> {
                val info = providerObject(api.request("https://api.subdl.com/api/v2/me", headers = mapOf("Authorization" to "Bearer ${key(c)}")))
                check(info.has("plan") || info.has("user")) { "SubDL did not verify this API key." }
            }
            "subsource" -> {
                val info = providerObject(api.request("https://api.subsource.net/api/v1/movies/search?searchType=imdb&imdb=tt0133093&type=movie",
                    headers = mapOf("X-API-Key" to key(c))))
                check(info.optBoolean("success")) { "SubSource did not verify this API key." }
            }
            else -> error("Choose a subtitle account.")
        }
        return c.copy(name = c.name.ifBlank { if (c.kind == "subdl") "SubDL" else "SubSource" })
    }
    fun search(c: CameoConnection, identity: PlaybackIdentity, language: String): List<CameoSavedSubtitle> {
        validateIdentity(identity)
        val lang = SubtitleSettings.language(language)
        val results = if (c.kind == "subdl") subdlSearch(c, identity, lang)
            else SubtitleProviders(credentials(c), transport()).search(provider(c.kind), identity, lang)
        checkCurrent()
        return results.filter { !SubtitleArchive.explicitMismatch(it.fileName, identity) &&
            !SubtitleArchive.explicitMismatch(it.displayName, identity) }
            .distinctBy { it.id }.take(100).map { CameoSavedSubtitle(c.id, c.revision, c.name.ifBlank { c.kind }, it) }
    }
    fun download(c: CameoConnection, bound: CameoSavedSubtitle, context: Context): DownloadedSubtitle {
        require(c.id == bound.connectionId && c.revision == bound.revision && provider(c.kind) == bound.result.provider) {
            "This subtitle belongs to another account. Search with the intended account again."
        }
        checkCurrent()
        val result = bound.result
        val response = if (c.kind == "subdl") {
            val url = if (result.downloadUrl.isNotBlank()) result.downloadUrl else {
                require(result.id.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "Choose an exact SubDL subtitle file." }
                "https://api.subdl.com/api/v2/subtitles/${Net.encode(result.id)}/download?format=zip"
            }
            val headers = if (result.downloadUrl.isNotBlank()) mapOf("X-API-Key" to key(c)) else mapOf("Authorization" to "Bearer ${key(c)}")
            transport().execute(SubtitleRequest(url, headers = headers, allowedHosts = setOf("subdl.com"), allowSubdomains = true,
                download = true, maxBytes = 8 * 1024 * 1024))
        } else SubtitleProviders(credentials(c), transport()).download(result)
        checkCurrent()
        val file = SubtitleArchive.read(response.bytes, result)
        val directory = File(context.applicationContext.cacheDir, "downloaded-subtitles")
        synchronized(CACHE_LOCK) {
            check(directory.isDirectory || directory.mkdirs()) { "Subtitle cache could not be created." }
            directory.listFiles()?.filter { it.name.startsWith("cameo-account-sub-") }?.sortedByDescending { it.lastModified() }
                ?.drop(39)?.forEach { it.delete() }
            val destination = File(directory, "cameo-account-sub-${UUID.randomUUID()}.${file.extension}")
            try { destination.writeBytes(file.bytes) } catch (error: Exception) { destination.delete(); throw error }
            return DownloadedSubtitle(Uri.fromFile(destination), file.displayName, result.language, file.mimeType)
        }
    }
    private fun subdlSearch(c: CameoConnection, identity: PlaybackIdentity, language: String): List<SubtitleResult> {
        val params = linkedMapOf("imdb_id" to identity.imdbId, "type" to if (identity.type == "series") "tv" else "movie",
            "languages" to language, "unpack" to "1", "subs_per_page" to "30")
        if (identity.type == "series") { params["season"] = identity.season.toString(); params["episode"] = identity.episode.toString() }
        val query = params.entries.joinToString("&") { Net.encode(it.key) + "=" + Net.encode(it.value) }
        val value = providerObject(api.request("https://api.subdl.com/api/v2/subtitles/search?$query", headers = mapOf("Authorization" to "Bearer ${key(c)}")))
        check(!value.has("error") && value.optBoolean("status", true)) { "SubDL could not complete this search. Check the selected account and quota." }
        val matches = (value.optJSONArray("results") ?: JSONArray()).providerObjects()
        if (matches.isNotEmpty() && matches.none { it.providerString("imdb_id") == identity.imdbId }) return emptyList()
        return (value.optJSONArray("subtitles") ?: JSONArray()).providerObjects().flatMap { subtitle ->
            val unpacked = (subtitle.optJSONArray("unpack_files") ?: JSONArray()).providerObjects()
            (unpacked.ifEmpty { listOf(subtitle) }).mapNotNull { file ->
                if (identity.type == "series" && ((file.has("season") && file.optInt("season") != identity.season) ||
                    (file.optInt("episode") > 0 && file.optInt("episode") != identity.episode))) return@mapNotNull null
                val name = file.providerString("release_name").ifBlank { subtitle.providerString("release_name") }.ifBlank { file.providerString("name") }
                val url = file.providerString("url").let { raw -> if (raw.isBlank()) "" else URL(URL("https://dl.subdl.com/"), raw).toString() }
                if (url.isNotBlank()) SubtitleHttp.checkedUrl(url, setOf("subdl.com"), true)
                val id = listOf("file_n_id", "n_id", "nId", "id").firstNotNullOfOrNull { field -> file.providerString(field).takeIf { it.isNotBlank() } }
                    ?: if (url.isNotBlank()) URI(url).path.substringAfterLast('/') else ""
                if (id.isBlank()) null else SubtitleResult(SubtitleProvider.SUBDL, id, name.ifBlank { "SubDL subtitle" }, language,
                    identity, file.providerString("name"), downloadUrl = url)
            }
        }
    }
    private fun credentials(c: CameoConnection): SubtitleCredentials {
        val accountKey = key(c)
        val values = mutableMapOf(SubtitleSettings.OPEN_SUBTITLES_API_KEY to accountKey,
            SubtitleSettings.OPEN_SUBTITLES_USERNAME to c.username, SubtitleSettings.OPEN_SUBTITLES_PASSWORD to c.password,
            SubtitleSettings.OPEN_SUBTITLES_REVISION to c.revision,
            SubtitleSettings.SUBSOURCE_API_KEY to accountKey, SubtitleSettings.SUBDL_API_KEY to accountKey)
        val snapshot = OpenSubtitlesSnapshot.read { values[it].orEmpty() }
        if (c.kind == "opensubtitles" && c.token.isNotBlank()) values.putAll(mapOf(
            "subtitles.opensubtitles.token" to c.token, "subtitles.opensubtitles.base_url" to openBase(c.url),
            "subtitles.opensubtitles.auth_fingerprint" to snapshot.fingerprint,
            "subtitles.opensubtitles.expires_ms" to (System.currentTimeMillis() + 15 * 60 * 1000).toString()))
        return object : SubtitleCredentials {
            override fun get(key: String): String { checkCurrent(); return values[key].orEmpty() }
            override fun openSnapshot() = OpenSubtitlesSnapshot.read(::get)
            override fun putIfOpenUnchanged(expected: OpenSubtitlesSnapshot, updates: Map<String, String>): Boolean {
                checkCurrent()
                if (openSnapshot() != expected) return false
                values.putAll(updates); return true
            }
        }
    }
    private fun transport() = SubtitleTransport { request ->
        checkCurrent()
        SubtitleHttp.checkedUrl(request.url, request.allowedHosts, request.allowSubdomains)
        val response = if (request.download) downloads.execute(request) else SubtitleResponse(
            api.request(request.url, request.method, request.headers, request.body?.toString()).toByteArray(Charsets.UTF_8), "application/json")
        checkCurrent(); response
    }
    private fun provider(kind: String) = when (kind) {
        "opensubtitles" -> SubtitleProvider.OPEN_SUBTITLES
        "subdl" -> SubtitleProvider.SUBDL
        "subsource" -> SubtitleProvider.SUBSOURCE
        else -> error("Choose a subtitle account.")
    }
    private fun validateIdentity(identity: PlaybackIdentity) {
        require(identity.imdbId.matches(Regex("tt[0-9]{5,12}")) && identity.type in setOf("movie", "series")) { "Choose a verified movie or series for this subtitle account." }
        require(identity.type != "series" || identity.season >= 0 && identity.episode > 0) { "Select the exact episode before searching subtitles." }
    }
    companion object {
        private const val OPEN_BASE = "https://api.opensubtitles.com/api/v1"
        private val OPEN_HOSTS = setOf("api.opensubtitles.com", "vip-api.opensubtitles.com")
        private val CACHE_LOCK = Any()
    }
}
