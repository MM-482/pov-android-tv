package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class AddonCatalog(val id: String, val type: String, val name: String,
    val extras: List<String> = emptyList(), val requiredExtras: List<String> = emptyList())
data class AddonResource(val name: String, val types: List<String> = emptyList(), val idPrefixes: List<String> = emptyList(), val inheritGlobalFilters: Boolean = true)
data class InstalledAddon(val id: String, val name: String, val manifestUrl: String, val enabled: Boolean,
    val manifestId: String, val catalogs: List<AddonCatalog>, val resources: List<AddonResource>,
    val types: List<String> = emptyList(), val idPrefixes: List<String> = emptyList(), val version: String = "", val description: String = "", val revision: String = "")
data class AddonEpisode(val id: String, val season: Int, val number: Int, val title: String, val released: String = "", val thumbnail: String = "")
data class AddonMedia(val item: MediaItemData, val addonId: String, val type: String, val id: String, val episodes: List<AddonEpisode> = emptyList())
data class AddonSubtitle(val id: String, val language: String, val url: String, val addonId: String, val provider: String)

internal interface AddonStore { fun get(): String; fun put(value: String) }

/** Stremio's documented manifest/catalog/meta/stream/subtitles protocol, without event reporting. */
class AddonCatalogApi internal constructor(private val store: AddonStore,
    private val http: DesktopTransport = LiveDesktopTransport) {
    constructor(store: SecureStore) : this(object : AddonStore {
        override fun get() = store.get(STORE_KEY)
        override fun put(value: String) = store.put(STORE_KEY, value)
    })

    fun installed(): List<InstalledAddon> = synchronized(LOCK) { records().map(::parseStored) }

    fun install(manifestUrl: String): InstalledAddon {
        val url = normalize(manifestUrl)
        val root = providerObject(http.request(url))
        val id = UUID.randomUUID().toString()
        val revision = UUID.randomUUID().toString()
        val validated = parse(root, id, url, true, revision)
        synchronized(LOCK) {
            val current = records()
            require(current.none { it.text("manifestUrl") == url }) { "This add-on is already installed." }
            require(current.size < 100) { "Remove an add-on before installing another." }
            save(current + JSONObject().put("id", id).put("manifestUrl", url).put("enabled", true).put("manifest", root).put("revision", revision))
        }
        return validated
    }

    fun remove(id: String) = synchronized(LOCK) { save(records().filterNot { it.text("id") == id }) }
    fun refresh(id: String): InstalledAddon {
        val before = installed().singleOrNull { it.id == id } ?: error("This add-on is no longer installed.")
        val raw = providerObject(http.request(before.manifestUrl))
        val updated = parse(raw, before.id, before.manifestUrl, before.enabled, UUID.randomUUID().toString())
        synchronized(LOCK) {
            val current = records()
            val record = current.singleOrNull { it.text("id") == id }
            check(record != null && parseStored(record) == before) { "This add-on changed while refreshing. Try again." }
            record.put("manifest", raw).put("revision", updated.revision)
            save(current)
        }
        return updated
    }
    fun setEnabled(id: String, enabled: Boolean) = synchronized(LOCK) {
        val rows = records()
        require(rows.any { it.text("id") == id }) { "This add-on is no longer installed." }
        rows.first { it.text("id") == id }.also { row ->
            if (row.optBoolean("enabled", true) != enabled) row.put("enabled", enabled).put("revision", UUID.randomUUID().toString())
        }
        save(rows)
    }

    fun catalog(addonId: String, catalog: AddonCatalog, search: String = "", skip: Int = 0, genre: String = ""): List<AddonMedia> = session(addonId) { addon ->
        require(addon.catalogs.any { it == catalog }) { "This catalog is not supplied by the add-on." }
        require(skip in 0..1_000_000) { "Invalid catalog page." }
        val extras = linkedMapOf<String, String>()
        if (search.isNotBlank()) { require("search" in catalog.extras) { "This catalog does not support search." }; extras["search"] = search.take(300) }
        if (skip > 0 || "skip" in catalog.requiredExtras) { require("skip" in catalog.extras) { "This catalog does not support paging." }; extras["skip"] = skip.toString() }
        if (genre.isNotBlank()) { require("genre" in catalog.extras) { "This catalog does not support genre filtering." }; extras["genre"] = genre.take(100) }
        require(catalog.requiredExtras.all { it in extras }) { "This catalog needs its required filters before it can be opened." }
        val suffix = if (extras.isEmpty()) "" else "/" + extras.entries.joinToString("&") { Net.encode(it.key) + "=" + Net.encode(it.value) }
        val response = get(addon, "catalog/${Net.encode(catalog.type)}/${Net.encode(catalog.id)}$suffix.json")
        response.optJSONArray("metas").objects().mapNotNull { parseMedia(it, addon.id, catalog.type) }.distinctBy { it.type to it.id }
    }

    fun detail(addonId: String, type: String, id: String): AddonMedia = session(addonId) { addon ->
        resource(addon, "meta", type, id)
        val raw = get(addon, "meta/${Net.encode(type)}/${Net.encode(id)}.json")
        val item = raw.optJSONObject("meta")?.let { parseMedia(it, addonId, type) }
        require(item != null && item.id == id && item.type == type) { "The add-on returned details for a different title." }
        item
    }

    fun streams(addonId: String, identity: PlaybackIdentity, videoId: String = ""): List<SourceItem> = session(addonId) { addon ->
        val id = videoId.ifBlank { if (identity.type == "series") "${identity.imdbId}:${identity.season}:${identity.episode}" else identity.imdbId }
        resource(addon, "stream", identity.type, id)
        get(addon, "stream/${Net.encode(identity.type)}/${Net.encode(id)}.json").optJSONArray("streams").objects().mapNotNull { stream ->
            val hash = stream.text("infoHash").lowercase().takeIf { it.matches(Regex("[a-f0-9]{40}")) }.orEmpty()
            val url = stream.text("url").takeIf(::safeStream).orEmpty()
            if (hash.isEmpty() && url.isEmpty()) return@mapNotNull null
            val hints = stream.optJSONObject("behaviorHints")
            val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { raw ->
                raw.keys().asSequence().associateWith { raw.text(it) }.also { values ->
                    val names = mutableSetOf<String>()
                    require(values.size <= 30 && values.all { (key, value) -> key.matches(Regex("[A-Za-z0-9-]{1,80}")) &&
                        names.add(key.lowercase(java.util.Locale.ROOT)) && key.lowercase(java.util.Locale.ROOT) !in setOf("host", "content-length", "connection", "transfer-encoding") &&
                        value.length <= 8192 && value.none { (it.code < 32 && it != '\t') || it.code == 127 } }) {
                        "This add-on returned unsupported playback headers."
                    }
                }
            }.orEmpty()
            val title = hints?.text("filename").orEmpty().ifBlank { stream.text("description").lineSequence().firstOrNull().orEmpty() }
                .ifBlank { stream.text("title") }.ifBlank { stream.text("name") }.ifBlank { "Video source" }.take(500)
            if (identity.type == "series" && EpisodeFilename.isExplicitMismatch(title, identity.season, identity.episode)) return@mapNotNull null
            SourceItem(title, addon.name, hash, if (stream.has("fileIdx")) stream.optInt("fileIdx", -1).takeIf { it >= 0 } else null,
                url, detail = stream.text("description").take(1000), requestHeaders = headers, sourceAddonId = addon.id, resourceType = identity.type, resourceId = id,
                requestIdentity = SourceRequestIdentity(identity.catalogNamespace, identity.imdbId, identity.type, id, identity.season, identity.episode))
        }.distinctBy { listOf(it.infoHash, it.fileIndex, it.directUrl, it.requestHeaders) }
    }

    fun subtitles(addonId: String, type: String, videoId: String): List<AddonSubtitle> = session(addonId) { addon ->
        resource(addon, "subtitles", type, videoId)
        get(addon, "subtitles/${Net.encode(type)}/${Net.encode(videoId)}.json").optJSONArray("subtitles").objects().mapNotNull { raw ->
            val url = raw.text("url")
            if (!safeStream(url)) return@mapNotNull null
            AddonSubtitle(raw.text("id"), raw.text("lang"), url, addon.id, addon.name)
        }
    }

    private fun <T> session(id: String, work: (InstalledAddon) -> T): T {
        val snapshot = installed().singleOrNull { it.id == id && it.enabled } ?: error("This add-on is disabled or was removed.")
        val result = work(snapshot)
        check(installed().any { it == snapshot && it.enabled }) { "This add-on changed. Reopen its catalog." }
        return result
    }
    private fun get(addon: InstalledAddon, path: String): JSONObject {
        check(installed().any { it == addon && it.enabled }) { "This add-on changed. Reopen its catalog." }
        val root = URI(addon.manifestUrl.removeSuffix("manifest.json"))
        return providerObject(http.request(DesktopUrlPolicy.within(root, path).toASCIIString()))
    }
    private fun resource(addon: InstalledAddon, name: String, type: String, id: String) {
        require(type.matches(Regex("[A-Za-z0-9_-]{1,80}")) && id.length in 1..1000 && id.none { it.code < 32 }) { "Choose a valid add-on title identity." }
        require(addon.resources.any { resource ->
            val types = if (resource.inheritGlobalFilters) addon.types else resource.types
            val prefixes = if (resource.inheritGlobalFilters) addon.idPrefixes else resource.idPrefixes
            resource.name == name && (types.isEmpty() || type in types) && (prefixes.isEmpty() || prefixes.any(id::startsWith))
        }) {
            "This add-on does not provide this resource for the selected title."
        }
    }
    private fun records(): List<JSONObject> {
        val raw = store.get()
        if (raw.isBlank()) return emptyList()
        return try {
            require(raw.length <= 16 * 1024 * 1024)
            val array = JSONArray(raw)
            require(array.length() <= 100)
            val values = (0 until array.length()).map { index -> array.getJSONObject(index).also { row ->
                require(row.getString("id").matches(Regex("[a-fA-F0-9-]{36}")) && row.get("enabled") is Boolean)
                row.getJSONObject("manifest")
                normalize(row.getString("manifestUrl"))
            } }
            require(values.map { it.text("id") }.toSet().size == values.size && values.map { it.text("manifestUrl") }.toSet().size == values.size)
            values
        } catch (_: Exception) { error("Saved add-ons could not be read. Their configuration was preserved.") }
    }
    private fun save(records: List<JSONObject>) {
        val value = JSONArray(records).toString()
        require(value.length <= 16 * 1024 * 1024) { "Saved add-on configuration is too large. Remove an add-on before adding or refreshing it." }
        store.put(value)
    }
    private fun parseStored(raw: JSONObject) = parse(raw.getJSONObject("manifest"), raw.text("id"), raw.text("manifestUrl"), raw.optBoolean("enabled", true), raw.text("revision"))
    private fun parse(raw: JSONObject, id: String, url: String, enabled: Boolean, revision: String): InstalledAddon {
        val name = raw.text("name").take(150)
        require(raw.text("id").isNotBlank() && name.isNotBlank() && raw.toString().length <= 2 * 1024 * 1024) { "This address did not return a valid add-on manifest." }
        require(raw.optJSONObject("behaviorHints")?.optBoolean("configurationRequired") != true) { "Configure this add-on with its provider first, then paste its configured manifest address." }
        val resources = raw.optJSONArray("resources")?.let { array -> (0 until array.length()).mapNotNull { index ->
            when (val value = array.opt(index)) {
                is String -> AddonResource(value)
                is JSONObject -> {
                    val types = value.optJSONArray("types").strings()
                    require(types.isNotEmpty()) { "This add-on declares a resource without supported media types." }
                    AddonResource(value.text("name"), types, value.optJSONArray("idPrefixes").strings(), false)
                }
                else -> throw IllegalArgumentException("This add-on declares an unsupported resource.")
            }
        } }.orEmpty().filter { it.name in setOf("catalog", "meta", "stream", "subtitles") }
        val catalogs = raw.optJSONArray("catalogs").objects().mapNotNull { catalog ->
            val type = catalog.text("type"); val key = catalog.text("id")
            if (key.isBlank() || type.isBlank()) return@mapNotNull null
            val extra = catalog.optJSONArray("extra").objects()
            AddonCatalog(key, type, catalog.text("name").ifBlank { key }, (extra.map { it.text("name") } + catalog.optJSONArray("extraSupported").strings() + catalog.optJSONArray("extraRequired").strings()).distinct(),
                (extra.filter { it.optBoolean("isRequired") }.map { it.text("name") } + catalog.optJSONArray("extraRequired").strings()).distinct())
        }
        require(resources.isNotEmpty() || catalogs.isNotEmpty()) { "This add-on has no supported catalogs, metadata, streams, or subtitles." }
        return InstalledAddon(id, name, normalize(url), enabled, raw.text("id"), catalogs, resources, raw.optJSONArray("types").strings(),
            raw.optJSONArray("idPrefixes").strings(), raw.text("version"), raw.text("description").take(2000), revision)
    }
    private fun parseMedia(raw: JSONObject, addonId: String, fallbackType: String): AddonMedia? {
        val id = raw.text("id"); val name = raw.text("name"); val type = raw.text("type").ifBlank { fallbackType }
        if (id.isBlank() || name.isBlank() || id.length > 1000) return null
        val episodes = raw.optJSONArray("videos").objects().mapNotNull { episode ->
            val episodeId = episode.text("id")
            if (episodeId.isBlank()) null else AddonEpisode(episodeId, episode.optInt("season"), episode.optInt("episode"),
                episode.text("title").ifBlank { episode.text("name") }, episode.text("released"), episode.text("thumbnail"))
        }.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number }))
        val item = MediaItemData(id, type, name, raw.text("releaseInfo").ifBlank { raw.text("year") }, raw.text("poster"),
            raw.text("background"), raw.text("description"), raw.optJSONArray("genres").strings(), raw.text("runtime"), raw.text("imdbRating"),
            episodes.map { EpisodeData(it.season, it.number, it.title, it.released, it.thumbnail) })
        return AddonMedia(item, addonId, type, id, episodes)
    }
    private fun normalize(raw: String): String {
        var url = raw.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://").trimEnd('/')
        val target = DesktopUrlPolicy.address(url)
        require(target.rawQuery == null) { "Use an add-on manifest address without a query string." }
        if (!url.endsWith("/manifest.json", true)) url += "/manifest.json"
        else url = url.dropLast("manifest.json".length) + "manifest.json"
        require(url.length <= 8192) { "The add-on address is too long." }
        return url
    }
    private fun safeStream(value: String) = try { DesktopUrlPolicy.address(value); true } catch (_: Exception) { false }
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it == "null" }.orEmpty()
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() && value != "null" } }
    companion object { private const val STORE_KEY = "cameo.addons.v1"; private val LOCK = Any() }
}
