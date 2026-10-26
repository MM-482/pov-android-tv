package dev.pov.tv.subtitles

import dev.pov.tv.data.PlaybackIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

internal class SubtitleProviders(private val credentials: SubtitleCredentials, private val http: SubtitleTransport) {
    private data class OpenSession(val base: String, val token: String, val account: OpenSubtitlesSnapshot)
    fun configured(provider: SubtitleProvider): Boolean = credentials.get(keyFor(provider)).isNotBlank()

    fun search(provider: SubtitleProvider, identity: PlaybackIdentity, language: String): List<SubtitleResult> = when (provider) {
        SubtitleProvider.OPEN_SUBTITLES -> openSearch(identity, language)
        SubtitleProvider.SUBDL -> subdlSearch(identity, language)
        SubtitleProvider.SUBSOURCE -> subsourceSearch(identity, language)
    }

    fun download(result: SubtitleResult): SubtitleResponse = when (result.provider) {
        SubtitleProvider.OPEN_SUBTITLES -> {
            val fileId = result.id.toLongOrNull()?.takeIf { it > 0 } ?: invalid()
            val session = openSession()
            val value = openJson(session.base + "/download", openHeaders(session.account) + ("Authorization" to "Bearer ${session.token}"),
                method = "POST", body = JSONObject().put("file_id", fileId).put("sub_format", "srt"), account = session.account)
            fetch(value.optString("link"), setOf("opensubtitles.com", "osdb.link"), subdomains = true)
        }
        SubtitleProvider.SUBDL -> fetch(result.downloadUrl, setOf("dl.subdl.com"),
            headers = mapOf("x-api-key" to key(SubtitleProvider.SUBDL)))
        SubtitleProvider.SUBSOURCE -> {
            val id = result.id.toLongOrNull()?.takeIf { it > 0 } ?: invalid()
            fetch("$SUBSOURCE/subtitles/$id/download", setOf("subsource.net"), subdomains = true,
                headers = mapOf("X-API-Key" to key(SubtitleProvider.SUBSOURCE)))
        }
    }

    private fun openSearch(identity: PlaybackIdentity, language: String): List<SubtitleResult> {
        val id = identity.imdbId.removePrefix("tt").trimStart('0').ifEmpty { "0" }
        val params = mutableMapOf("languages" to language)
        if (identity.type == "series") {
            params["parent_imdb_id"] = id
            params["season_number"] = identity.season.toString()
            params["episode_number"] = identity.episode.toString()
        } else params["imdb_id"] = id
        val account = credentials.openSnapshot()
        val fingerprint = account.fingerprint
        val validToken = credentials.get(OPEN_FINGERPRINT) == fingerprint &&
            (credentials.get(OPEN_EXPIRES).toLongOrNull() ?: 0) > System.currentTimeMillis()
        val token = credentials.get(OPEN_TOKEN).takeIf { validToken && it.isNotBlank() }
        val base = if (token != null) openBase(credentials.get(OPEN_BASE)) else OPEN_BASE_DEFAULT
        val value = openJson(base + "/subtitles?" + query(params), openHeaders(account) + if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap(), account = account)
        return objects(value.optJSONArray("data")).flatMap { row ->
            val attr = row.optJSONObject("attributes") ?: return@flatMap emptyList()
            val lang = attr.optString("language").ifBlank { language }
            if (!lang.equals(language, true)) return@flatMap emptyList()
            objects(attr.optJSONArray("files")).mapNotNull { file ->
                val fileId = file.optLong("file_id", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val name = file.optString("file_name")
                SubtitleResult(SubtitleProvider.OPEN_SUBTITLES, fileId.toString(),
                    attr.optString("release").ifBlank { name }.ifBlank { "Subtitle $fileId" }, lang, identity,
                    fileName = name, hearingImpaired = attr.optBoolean("hearing_impaired"))
            }
        }.take(100)
    }

    private fun subdlSearch(identity: PlaybackIdentity, language: String): List<SubtitleResult> {
        val params = mutableMapOf("api_key" to key(SubtitleProvider.SUBDL), "imdb_id" to identity.imdbId,
            "type" to if (identity.type == "series") "tv" else "movie", "languages" to language.uppercase(Locale.ROOT),
            "subs_per_page" to "30", "unpack" to "1", "releases" to "1", "client" to "custom_integration")
        if (identity.type == "series") {
            params["season_number"] = identity.season.toString(); params["episode_number"] = identity.episode.toString()
        }
        val value = json("$SUBDL/subtitles?${query(params)}", hosts = setOf("api.subdl.com"))
        if (!value.optBoolean("status")) throw SubtitleException("SubDL could not complete this search. Check the API key and request quota in your SubDL account.")
        val matches = objects(value.optJSONArray("results"))
        if (matches.isNotEmpty() && matches.none { it.optString("imdb_id") == identity.imdbId }) return emptyList()
        return objects(value.optJSONArray("subtitles")).flatMap { subtitle ->
            val unpacked = objects(subtitle.optJSONArray("unpack_files"))
            val files = if (unpacked.isNotEmpty()) unpacked.filter {
                identity.type != "series" || (it.optInt("season", -1) == identity.season && it.optInt("episode", -1) == identity.episode)
            } else listOf(subtitle)
            files.mapNotNull { file ->
                if (identity.type == "series" && !subtitle.optBoolean("full_season") &&
                    ((subtitle.has("season") && subtitle.optInt("season", -1) != identity.season) ||
                        (subtitle.has("episode") && subtitle.optInt("episode", -1) != identity.episode))) return@mapNotNull null
                val relative = file.optString("url")
                if (relative.isBlank()) return@mapNotNull null
                val link = URL(URL("https://dl.subdl.com"), relative).toExternalForm()
                SubtitleHttp.checkedUrl(link, setOf("dl.subdl.com"))
                val name = file.optString("name")
                SubtitleResult(SubtitleProvider.SUBDL, relative, file.optString("release_name")
                    .ifBlank { subtitle.optString("release_name") }.ifBlank { name }.ifBlank { "SubDL subtitle" },
                    language, identity, name, file.optBoolean("hi", subtitle.optBoolean("hi")), link)
            }
        }.take(100)
    }

    private fun subsourceSearch(identity: PlaybackIdentity, language: String): List<SubtitleResult> {
        val languageName = subsourceLanguage(language)
        val headers = mapOf("X-API-Key" to key(SubtitleProvider.SUBSOURCE))
        val params = mutableMapOf("searchType" to "imdb", "imdb" to identity.imdbId, "type" to identity.type)
        if (identity.type == "series") params["season"] = identity.season.toString()
        val found = json("$SUBSOURCE/movies/search?${query(params)}", headers, hosts = setOf("api.subsource.net"))
        if (!found.optBoolean("success")) throw SubtitleException("SubSource could not complete this search. Check your API key and account quota.")
        val matching = objects(found.optJSONArray("data")).filter {
            it.optString("imdbId") == identity.imdbId && (identity.type != "series" || it.optInt("season", -1) == identity.season)
        }.take(3)
        return matching.flatMap { movie ->
            val id = movie.optLong("movieId", -1).takeIf { it > 0 } ?: return@flatMap emptyList()
            val results = mutableListOf<SubtitleResult>()
            val seenIds = mutableSetOf<Long>()
            for (page in 1..20) {
                val value = json("$SUBSOURCE/subtitles?${query(mapOf("movieId" to "$id", "language" to languageName, "limit" to "50", "page" to "$page"))}",
                    headers, hosts = setOf("api.subsource.net"))
                if (!value.optBoolean("success")) throw SubtitleException("SubSource could not load subtitles for this title.")
                val rows = objects(value.optJSONArray("data"))
                var newIds = 0
                rows.forEach { row ->
                    val subId = row.optLong("subtitleId", -1).takeIf { it > 0 } ?: return@forEach
                    if (!seenIds.add(subId)) return@forEach
                    newIds++
                    if (row.optString("language").isNotBlank() && row.optString("language") != languageName) return@forEach
                    val release = row.optJSONArray("releaseInfo")?.let { array ->
                        (0 until array.length()).joinToString(" · ") { array.optString(it) }
                    }.orEmpty().ifBlank { "Subtitle $subId" }
                    // The documented API filters by season, not episode. Filter after each page,
                    // so unrelated episodes cannot fill the result cap before later pages are read.
                    if (SubtitleArchive.explicitMismatch(release, identity)) return@forEach
                    results += SubtitleResult(SubtitleProvider.SUBSOURCE, "$subId", release, language, identity,
                        hearingImpaired = row.optBoolean("hearingImpaired"))
                }
                val pagination = value.optJSONObject("pagination")
                val returnedPage = pagination?.optInt("page", page) ?: page
                if (returnedPage != page || (rows.isNotEmpty() && newIds == 0)) {
                    if (results.isEmpty()) throw SubtitleException("SubSource repeated a subtitle result page. Try searching again later.")
                    break
                }
                val pages = pagination?.optInt("pages", 0) ?: 0
                val hasMore = rows.isNotEmpty() && if (pages > 0) page < pages else rows.size >= 50
                if (!hasMore || results.size >= 100) break
                if (page == 20 && results.isEmpty()) throw SubtitleException("SubSource has too many subtitle pages to search safely. Try another provider for this episode.")
            }
            results
        }.take(100)
    }

    private fun openSession(): OpenSession {
        val account = credentials.openSnapshot()
        val fingerprint = account.fingerprint
        if (credentials.get(OPEN_FINGERPRINT) == fingerprint && credentials.get(OPEN_TOKEN).isNotBlank() &&
            (credentials.get(OPEN_EXPIRES).toLongOrNull() ?: 0) > System.currentTimeMillis()) {
            return OpenSession(openBase(credentials.get(OPEN_BASE)), credentials.get(OPEN_TOKEN), account)
        }
        val username = account.username.trim()
        val password = account.password
        if (username.isBlank() || password.isBlank()) throw SubtitleException("OpenSubtitles downloads require your OpenSubtitles.com username and password as well as your API key. Add them in Settings.")
        if (credentials.get(OPEN_REJECTED) == fingerprint) throw SubtitleException("OpenSubtitles rejected these login details. Correct the username, password, or API key in Settings before trying again.")
        val response = try {
            json("$OPEN_BASE_DEFAULT/login", openHeaders(account), "POST", JSONObject().put("username", username).put("password", password), OPEN_API_HOSTS)
        } catch (error: SubtitleException) {
            if (error.status == 401) credentials.putIfOpenUnchanged(account, mapOf(OPEN_REJECTED to fingerprint))
            throw error
        }
        val token = response.optString("token").takeIf { it.isNotBlank() } ?: invalid()
        val base = openBase(response.optString("base_url"))
        val saved = credentials.putIfOpenUnchanged(account, mapOf(OPEN_TOKEN to token, OPEN_BASE to base, OPEN_FINGERPRINT to fingerprint,
            OPEN_EXPIRES to (System.currentTimeMillis() + 23 * 60 * 60 * 1000L).toString(), OPEN_REJECTED to ""))
        if (!saved) throw SubtitleException("The OpenSubtitles connection changed while signing in. Search again before downloading.")
        return OpenSession(base, token, account)
    }

    private fun openBase(value: String): String {
        val host = value.removePrefix("https://").removeSuffix("/api/v1").trimEnd('/')
        if (host !in OPEN_API_HOSTS) throw SubtitleException("OpenSubtitles returned an unsupported API host.")
        return "https://$host/api/v1"
    }
    private fun openHeaders(account: OpenSubtitlesSnapshot) = mapOf("Api-Key" to validKey(account.apiKey, SubtitleProvider.OPEN_SUBTITLES))
    private fun openJson(url: String, headers: Map<String, String>, method: String = "GET", body: JSONObject? = null, account: OpenSubtitlesSnapshot): JSONObject = try {
        json(url, headers, method, body, OPEN_API_HOSTS)
    } catch (error: SubtitleException) {
        if (error.status == 401 && headers.containsKey("Authorization")) {
            credentials.putIfOpenUnchanged(account, mapOf(OPEN_TOKEN to "", OPEN_EXPIRES to "0"))
            throw SubtitleException("Your OpenSubtitles session expired. Retry to sign in again, or check the account details in Settings.", 401)
        }
        throw error
    }
    private fun key(provider: SubtitleProvider): String = validKey(credentials.get(keyFor(provider)), provider)
    private fun validKey(value: String, provider: SubtitleProvider): String = value.trim().takeIf {
        it.isNotBlank() && it.length <= 4096 && it.none { char -> char.isWhitespace() || char.code < 32 }
    } ?: throw SubtitleException("Add a valid ${provider.label} API key in Settings first.")

    private fun json(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET", body: JSONObject? = null, hosts: Set<String>): JSONObject =
        http.execute(SubtitleRequest(url, method, headers, body, allowedHosts = hosts)).json()
    private fun fetch(url: String, hosts: Set<String>, subdomains: Boolean = false, headers: Map<String, String> = emptyMap()): SubtitleResponse =
        http.execute(SubtitleRequest(url, headers = headers, allowedHosts = hosts, allowSubdomains = subdomains,
            maxBytes = 8 * 1024 * 1024, download = true))

    private fun keyFor(provider: SubtitleProvider) = when (provider) {
        SubtitleProvider.OPEN_SUBTITLES -> SubtitleSettings.OPEN_SUBTITLES_API_KEY
        SubtitleProvider.SUBDL -> SubtitleSettings.SUBDL_API_KEY
        SubtitleProvider.SUBSOURCE -> SubtitleSettings.SUBSOURCE_API_KEY
    }
    private fun query(params: Map<String, String>) = params.toSortedMap().entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8").replace("+", "%20")}" }
    private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else
        (0 until minOf(array.length(), 200)).mapNotNull { array.optJSONObject(it) }
    private fun invalid(): Nothing = throw SubtitleException("The subtitle provider returned incomplete subtitle details. Search again.")

    private fun subsourceLanguage(language: String): String {
        if (language == "pt-br") return "brazilian_portuguese"
        if (language == "fa") return "farsi_persian"
        if (language == "zh-cn" || language == "zh-hans") return "chinese_simplified"
        if (language == "zh-tw" || language == "zh-hant") return "chinese_traditional"
        if (language == "zh") return "chinese"
        if (language == "nb" || language == "nn") return "norwegian"
        if (language == "fr-ca") return "french_canada"
        val name = Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT)
        if (name.isBlank() || name == language) throw SubtitleException("SubSource does not have a verified language mapping for $language. Try another subtitle provider.")
        return name.replace(' ', '_')
    }

    companion object {
        private const val OPEN_BASE_DEFAULT = "https://api.opensubtitles.com/api/v1"
        private val OPEN_API_HOSTS = setOf("api.opensubtitles.com", "vip-api.opensubtitles.com")
        private const val SUBDL = "https://api.subdl.com/api/v1"
        private const val SUBSOURCE = "https://api.subsource.net/api/v1"
        private const val OPEN_TOKEN = "subtitles.opensubtitles.token"
        private const val OPEN_BASE = "subtitles.opensubtitles.base_url"
        private const val OPEN_FINGERPRINT = "subtitles.opensubtitles.auth_fingerprint"
        private const val OPEN_EXPIRES = "subtitles.opensubtitles.expires_ms"
        private const val OPEN_REJECTED = "subtitles.opensubtitles.rejected_fingerprint"
    }
}
