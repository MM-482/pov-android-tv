package dev.pov.tv.data

import org.json.JSONObject
import java.net.URI
import java.util.Base64

/** Experimental port of POV's existing authenticated Easynews web-search interface. */
internal class EasynewsProvider(private val http: ProviderTransport) {
    private fun auth(username: String, password: String): Map<String, String> = mapOf("Authorization" to
        "Basic ${Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))}")
    fun account(password: String, username: String): ProviderAccount {
        val html = http.request("https://account.easynews.com/editinfo.php", headers = auth(username, password))
        if (!Regex("(?is)<form\\b[^>]*\\bid\\s*=\\s*['\"]accountForm['\"]").containsMatchIn(html)) {
            throw ApiException(401, "Easynews did not verify this account. Check your username, password, and web-access subscription.")
        }
        return ProviderAccount("easynews", username, true, "Experimental web interface; account playback still needs device verification")
    }
    fun search(password: String, username: String, identity: PlaybackIdentity): List<ProviderFile> {
        val query = if (identity.type == "series") {
            require(identity.season >= 0 && identity.episode > 0) { "Choose an episode before searching Easynews." }
            "${identity.title} S${identity.season.toString().padStart(2, '0')}E${identity.episode.toString().padStart(2, '0')}"
        } else identity.title
        require(query.isNotBlank()) { "Choose a title before searching Easynews." }
        val params = listOf("gps" to query.take(300), "pby" to "100", "pno" to "1", "spamf" to "1", "u" to "1", "sb" to "1",
            "gx" to "1", "st" to "adv", "sS" to "3", "s1" to "relevance", "s1d" to "-", "s2" to "dsize", "s2d" to "-",
            "fty[]" to "VIDEO", "safeO" to "1")
        val url = "$BASE/2.0/search/solr-search/advanced?" + params.joinToString("&") { Net.encode(it.first) + "=" + Net.encode(it.second) }
        return parseSearch(providerObject(http.request(url, headers = auth(username, password))))
            .filterNot { identity.type == "series" && EpisodeFilename.isExplicitMismatch(it.name, identity.season, identity.episode) }
    }
    internal fun parseSearch(json: JSONObject): List<ProviderFile> {
        val data = json.optJSONArray("data") ?: throw ApiException(0, "Easynews returned an unsupported search response. Its web interface may have changed.")
        val down = json.providerString("downURL")
        if (data.length() == 0) return emptyList()
        trusted(down)
        val farm = json.providerString("dlFarm")
        val port = json.providerString("dlPort")
        return data.providerObjects().mapNotNull { item ->
            val virus = when (val value = item.opt("virus")) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value.lowercase() !in setOf("", "0", "false", "null")
                else -> false
            }
            if (virus) return@mapNotNull null
            if (item.has("type") && item.providerString("type").uppercase() != "VIDEO") return@mapNotNull null
            val duration = item.providerString("14")
            if (Regex("^\\d+s|^[0-5]m").containsMatchIn(duration)) return@mapNotNull null
            val hash = item.providerString("0")
            val name = item.providerString("10")
            val ext = item.providerString("11")
            if (hash.isBlank() || name.isBlank() || ext.isBlank() || '/' in farm || '/' in port) return@mapNotNull null
            val path = listOf(farm, port, hash + ext, name + ext).joinToString("/") { Net.encode(it) }
            val link = down.trimEnd('/') + "/" + path
            trusted(link)
            ProviderFile(hash, name + ext, item.optLong("rawSize", 0), link)
        }
    }
    fun playback(password: String, username: String, file: ProviderFile): ProviderPlayback =
        ProviderPlayback(trusted(file.link), auth(username, password))
    internal fun trusted(link: String): String {
        providerHttps(link)
        val uri = URI(link)
        val host = uri.host.lowercase()
        if (host != "easynews.com" && !host.endsWith(".easynews.com")) throw ApiException(0, "Easynews returned a playback address outside its service.")
        if (uri.port !in setOf(-1, 443)) throw ApiException(0, "Easynews returned an unsupported playback address.")
        return link
    }
    companion object { private const val BASE = "https://members.easynews.com" }
}
