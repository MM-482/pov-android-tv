package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

data class CameoAddon(val id: String, val name: String, val url: String, val enabled: Boolean = true,
    val manifestId: String = "", val resources: List<String> = emptyList()) {
    override fun toString() = "CameoAddon($id, $name, enabled=$enabled)"
}
data class CameoAddonCatalog(val id: String, val type: String, val name: String, val searchable: Boolean = false,
    val genres: List<String> = emptyList())
data class CameoAddonSubtitle(val name: String, val language: String, val url: String, val addonId: String)

/** Stremio-compatible manifest/catalog/meta/stream/subtitle access, retaining exact add-on and video identity. */
class AddonRepository internal constructor(private val credentials: ProviderCredentials, private val json: (String) -> JSONObject,
    private val publicCatalog: CatalogApi) {
    internal constructor(credentials: ProviderCredentials, json: (String) -> JSONObject) : this(credentials, json, CatalogApi())
    constructor(store: SecureStore) : this(EncryptedProviderCredentials(store), { address ->
        val bytes = LiveCameoServiceTransport.publicBytes(address, CameoServiceUrls.origin(address))
        try { JSONObject(bytes.toString(Charsets.UTF_8)) }
        catch (_: Exception) { throw ApiException(0, "The add-on returned an unreadable response.") }
    })
    fun list(): List<CameoAddon> = synchronized(LOCK) {
        val raw = credentials.get(KEY)
        if (raw.isBlank()) emptyList() else try { JSONArray(raw).providerObjects().map { j ->
            CameoAddon(j.getString("id"), j.getString("name"), j.getString("url"), j.optBoolean("enabled", true),
                j.optString("manifestId"), (j.optJSONArray("resources") ?: JSONArray()).strings())
        } } catch (_: Exception) { throw IllegalStateException("Saved add-ons could not be read. Their configuration was preserved.") }
    }
    fun install(address: String): CameoAddon {
        val root = base(address)
        val manifest = json("$root/manifest.json")
        val manifestId = manifest.providerString("id")
        val name = manifest.providerString("name")
        require(manifestId.isNotBlank() && name.isNotBlank()) { "This address did not return a valid add-on manifest." }
        val resources = (manifest.optJSONArray("resources") ?: JSONArray()).let { values ->
            (0 until values.length()).mapNotNull { i -> values.optJSONObject(i)?.providerString("name") ?: values.optString(i).takeIf { it.isNotBlank() } }
        }.filter { it in setOf("catalog", "meta", "stream", "subtitles") }.distinct()
        require(resources.isNotEmpty()) { "This add-on offers no supported catalogs, details, sources, or subtitles." }
        val id = MessageDigest.getInstance("SHA-256").digest(root.toByteArray()).joinToString("") { "%02x".format(it) }
        val addon = CameoAddon(id, name.take(200), root, manifestId = manifestId, resources = resources)
        synchronized(LOCK) {
            val current = list().filterNot { it.id == id }
            require(current.size < 50) { "Remove an unused add-on before installing another." }
            write(current + addon)
        }
        return addon
    }
    fun remove(id: String) = synchronized(LOCK) { write(list().filterNot { it.id == id }) }
    fun setEnabled(id: String, enabled: Boolean) = synchronized(LOCK) {
        require(list().any { it.id == id }) { "This add-on is no longer installed." }
        write(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }
    fun enabled(id: String, enabled: Boolean) = setEnabled(id, enabled)
    private fun addon(id: String) = list().singleOrNull { it.id == id && it.enabled }
        ?: throw IllegalStateException("This add-on was removed or disabled. Enable it before opening the saved title.")
    fun catalogs(id: String): List<CameoAddonCatalog> {
        val a = addon(id)
        return (json("${a.url}/manifest.json").optJSONArray("catalogs") ?: JSONArray()).providerObjects().mapNotNull { c ->
            val catalogId = c.providerString("id"); val type = c.providerString("type")
            if (!resourceId(catalogId) || !type.matches(Regex("[A-Za-z0-9_-]{1,40}"))) return@mapNotNull null
            val extras = (c.optJSONArray("extra") ?: JSONArray()).providerObjects()
            CameoAddonCatalog(catalogId, type, c.providerString("name").ifBlank { catalogId },
                extras.any { it.providerString("name") == "search" } || (c.optJSONArray("extraSupported") ?: JSONArray()).strings().contains("search"),
                extras.firstOrNull { it.providerString("name") == "genre" }?.optJSONArray("options")?.strings().orEmpty())
        }
    }
    fun catalog(id: String, catalogId: String, type: String, query: String = "", skip: Int = 0, genre: String = ""): List<MediaItemData> {
        val a = addon(id)
        require(resourceId(catalogId) && type.matches(Regex("[A-Za-z0-9_-]{1,40}")) && skip >= 0) { "Choose an actual add-on catalog." }
        val extras = mutableListOf<String>()
        if (query.isNotBlank()) extras += "search=${Net.encode(query.trim().take(200))}"
        if (genre.isNotBlank()) extras += "genre=${Net.encode(genre.take(100))}"
        if (skip > 0) extras += "skip=$skip"
        val suffix = if (extras.isEmpty()) "" else "/${extras.joinToString("&")}"
        val value = json("${a.url}/catalog/${Net.encode(type)}/${Net.encode(catalogId)}$suffix.json")
        return (value.optJSONArray("metas") ?: JSONArray()).providerObjects().mapNotNull { parse(it, type, a.id, catalogId) }.distinctBy { it.imdbId }
    }
    fun details(item: MediaItemData): MediaItemData {
        val a = addon(item.addonId)
        require(resourceId(item.imdbId)) { "This add-on title has no supported identity." }
        if ("meta" !in a.resources) {
            require("catalog" in a.resources && item.type in setOf("movie", "series") &&
                item.imdbId.matches(Regex("tt[0-9]{5,12}"))) {
                "This add-on does not provide details for this title. Public details require an exact IMDb movie or series."
            }
            // CatalogApi validates the returned IMDb identity and type. Existing add-on episode
            // identities remain authoritative; public metadata cannot replace them with guessed IDs.
            val details = publicCatalog.details(item)
            val existingNumbers = item.episodes.map { it.season to it.episode }.toSet()
            val episodes = (item.episodes + details.episodes.filterNot { (it.season to it.episode) in existingNumbers })
                .sortedWith(compareBy({ it.season }, { it.episode }))
            return details.copy(addonId = a.id, catalogId = item.catalogId, episodes = episodes)
        }
        val meta = json("${a.url}/meta/${Net.encode(item.type)}/${Net.encode(item.imdbId)}.json").optJSONObject("meta")
            ?: throw IllegalStateException("This add-on has no details for this title.")
        check(meta.providerString("id") == item.imdbId && meta.providerString("type").let { it.isBlank() || it == item.type }) {
            "The add-on returned a different title or type. No source was selected."
        }
        return parse(meta, item.type, a.id, item.catalogId)?.let { parsed -> parsed.copy(title = parsed.title.ifBlank { item.title },
            poster = parsed.poster.ifBlank { item.poster }, background = parsed.background.ifBlank { item.background }) }
            ?: throw IllegalStateException("The add-on returned incomplete title details.")
    }
    fun sources(item: MediaItemData, episode: EpisodeData? = null): List<SourceItem> = sources(identity(item, episode))
    fun sources(identity: PlaybackIdentity): List<SourceItem> {
        val id = videoId(identity)
        if (identity.addonId.isNotBlank()) addon(identity.addonId)
        val addons = list().filter { it.enabled && "stream" in it.resources }
        val result = mutableListOf<SourceItem>(); var failure: Exception? = null
        for (a in addons) try {
            result += readSources(a.url, a.id, a.name, identity.type, id)
        } catch (error: Exception) { failure = error }
        if (result.isEmpty() && failure != null) throw IllegalStateException("An add-on could not return sources. Check its configuration and try again.", failure)
        return result.distinctBy { listOf(it.addonId, it.directUrl, it.infoHash, it.fileIndex, it.title) }
    }
    /** Uses the existing configured endpoint without installing an add-on or changing preferences. */
    fun sourcesFromEndpoint(identity: PlaybackIdentity, address: String): List<SourceItem> {
        val root = base(address.ifBlank { "https://torrentio.strem.fun" })
        val endpointId = "endpoint:" + MessageDigest.getInstance("SHA-256").digest(root.toByteArray()).joinToString("") { "%02x".format(it) }
        return readSources(root, endpointId, "Configured source", identity.type, videoId(identity))
    }
    private fun readSources(root: String, addonId: String, name: String, type: String, id: String): List<SourceItem> {
            val streams = json("$root/stream/${Net.encode(type)}/${Net.encode(id)}.json").optJSONArray("streams") ?: JSONArray()
            return streams.providerObjects().mapNotNull { stream ->
                val hash = stream.providerString("infoHash").lowercase().takeIf { it.matches(Regex("[a-f0-9]{40}")) }.orEmpty()
                val url = safeMedia(stream.providerString("url"), root)
                if (hash.isBlank() && url.isBlank()) return@mapNotNull null
                val requested = stream.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
                val headers = SourceResolver.requestHeaders(requested)
                SourceItem(stream.providerString("title").ifBlank { stream.providerString("name") }.ifBlank { "Video" }, name,
                    infoHash = hash, fileIndex = stream.optInt("fileIdx", -1).takeIf { it >= 0 }, directUrl = url,
                    detail = stream.providerString("description"), headers = headers, addonId = addonId, sourceId = id,
                    origin = when {
                        url.isBlank() -> ""
                        url.startsWith("http://", true) -> CameoServiceUrls.origin(root)
                        else -> CameoServiceUrls.origin(url)
                    })
            }
    }
    fun subtitles(item: MediaItemData, episode: EpisodeData? = null): List<CameoAddonSubtitle> = subtitles(identity(item, episode))
    fun subtitles(identity: PlaybackIdentity): List<CameoAddonSubtitle> {
        val id = videoId(identity)
        if (identity.addonId.isNotBlank()) addon(identity.addonId)
        val addons = list().filter { it.enabled && "subtitles" in it.resources }
        val results = mutableListOf<CameoAddonSubtitle>(); var failure: Exception? = null
        for (a in addons) try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Subtitle search canceled.")
            val response = json("${a.url}/subtitles/${Net.encode(identity.type)}/${Net.encode(id)}.json")
            addon(a.id) // A removed or disabled add-on cannot contribute a stale result.
            results += (response.optJSONArray("subtitles") ?: JSONArray()).providerObjects().mapNotNull { row ->
                val url = safeMedia(row.providerString("url"), a.url)
                if (url.isBlank()) null else CameoAddonSubtitle(row.providerString("name").ifBlank { a.name }, row.providerString("lang"), url, a.id)
            }
        } catch (error: InterruptedException) { throw error
        } catch (error: Exception) { failure = error }
        if (results.isEmpty() && failure != null) throw IllegalStateException("An installed add-on could not return subtitles.", failure)
        return results.distinctBy { listOf(it.addonId, it.url, it.language) }
    }
    fun identity(item: MediaItemData, episode: EpisodeData?): PlaybackIdentity = PlaybackIdentity(item.imdbId, item.type, item.title,
        episode?.season ?: 0, episode?.episode ?: 0, episode?.sourceId.orEmpty(), item.addonId)
    private fun videoId(identity: PlaybackIdentity): String {
        val value = if (identity.sourceId.isNotBlank()) identity.sourceId else if (identity.type == "movie") identity.imdbId else {
            require(identity.imdbId.matches(Regex("tt[0-9]{5,12}")) && identity.season >= 0 && identity.episode > 0) { "Select the exact add-on episode before searching for a source." }
            "${identity.imdbId}:${identity.season}:${identity.episode}"
        }
        require(resourceId(value)) { "This title has no supported source identity." }; return value
    }
    private fun parse(j: JSONObject, type: String, addonId: String, catalogId: String): MediaItemData? {
        val id = j.providerString("id"); val name = j.providerString("name")
        if (!resourceId(id) || name.isBlank()) return null
        val episodes = (j.optJSONArray("videos") ?: JSONArray()).providerObjects().mapNotNull { video ->
            val season = video.optInt("season", -1); val number = video.optInt("episode", -1); val sourceId = video.providerString("id")
            if (season < 0 || number < 1 || !resourceId(sourceId)) null else EpisodeData(season, number,
                video.providerString("title").ifBlank { video.providerString("name") }.ifBlank { "Episode $number" },
                video.providerString("released"), safeMedia(video.providerString("thumbnail")), sourceId, listOf(sourceId))
        }.distinctBy { it.sourceId }.sortedWith(compareBy({ it.season }, { it.episode }))
        return MediaItemData(id, type, name, j.providerString("releaseInfo"), safeMedia(j.providerString("poster")),
            safeMedia(j.providerString("background")), j.providerString("description"), (j.optJSONArray("genres") ?: JSONArray()).strings(),
            j.providerString("runtime"), j.providerString("imdbRating"), episodes, addonId, catalogId)
    }
    private fun base(raw: String): String {
        var value = raw.trim().trimEnd('/')
        if (value.startsWith("stremio://", true)) value = "https://" + value.substring(10)
        if (value.endsWith("/manifest.json", true)) value = value.dropLast(14)
        val uri = CameoServiceUrls.checked(value)
        require(uri.query == null && uri.fragment == null) { "Enter an HTTPS or private local HTTP add-on manifest address without a query or fragment." }
        return value
    }
    private fun safeMedia(value: String, origin: String = ""): String = try {
        val uri = CameoServiceUrls.addonResource(value, origin)
        uri.scheme.lowercase() + value.substring(value.indexOf(':'))
    } catch (_: Exception) { "" }
    private fun resourceId(value: String) = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9:_.-]{0,499}"))
    private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }.filter { it.isNotBlank() && it != "null" }
    private fun write(values: List<CameoAddon>) { credentials.putAll(mapOf(KEY to JSONArray().also { array -> values.forEach { a ->
        array.put(JSONObject().put("id", a.id).put("name", a.name).put("url", a.url).put("enabled", a.enabled)
            .put("manifestId", a.manifestId).put("resources", JSONArray(a.resources)))
    } }.toString())) }
    companion object { private const val KEY = "cameo.addons.v1"; private val LOCK = Any() }
}
