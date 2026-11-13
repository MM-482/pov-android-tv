package dev.pov.tv.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class PublicCatalogCacheReply(val json: JSONObject, val source: String, val savedAtMillis: Long) {
    val notice: String get() = when (source) {
        "stale-cache" -> "Showing saved catalog metadata because the public catalog is unavailable. Availability and release details may have changed."
        "cache" -> "Showing recently saved public catalog metadata."
        else -> ""
    }
}
data class PublicCatalogCacheInfo(val entries: Int, val bytes: Long, val maxEntries: Int, val maxBytes: Long)

/** Bounded, public-metadata-only disk cache. No caller can supply credentials or headers. */
class PublicCatalogCache internal constructor(private val directory: File,
    private val fetch: (String) -> JSONObject = ::fetchPublic,
    private val now: () -> Long = System::currentTimeMillis,
    private val freshMillis: Long = 6 * 60 * 60 * 1000L,
    private val staleMillis: Long = 7 * 24 * 60 * 60 * 1000L,
    private val maxEntries: Int = 200, private val maxBytes: Long = 24 * 1024 * 1024L,
    private val maxEntryBytes: Int = 2 * 1024 * 1024) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "cameo-public-catalog-cache"))
    private class State(var generation: Long = 0)
    private val state = synchronized(STATES) { STATES.getOrPut(directory.absolutePath) { State() } }
    @Volatile var lastRead: PublicCatalogCacheReply? = null
        private set
    init { require(maxEntries > 0 && maxBytes > 0 && maxEntryBytes > 0 && freshMillis >= 0 && staleMillis >= freshMillis) }

    fun json(url: String): JSONObject = read(url).json

    fun read(url: String): PublicCatalogCacheReply {
        validateAddress(url)
        val timestamp = now()
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val cached: Pair<PublicCatalogCacheReply?, Long> = synchronized(state) {
            val file = File(directory, "$key.json")
            val reply = readFile(file, url)
            if (reply != null) file.setLastModified(timestamp)
            reply to state.generation
        }
        val previous = cached.first
        if (previous != null && timestamp - previous.savedAtMillis in 0..freshMillis) return record(previous.copy(source = "cache"))
        try {
            val json = fetch(url)
            val raw = json.toString()
            require(!PRIVATE_RESPONSE_KEYS.containsMatchIn(raw)) { "This response is not public catalog metadata." }
            val reply = PublicCatalogCacheReply(json, "network", timestamp)
            val body = JSONObject().put("url", url).put("savedAt", timestamp).put("body", json).toString().toByteArray(Charsets.UTF_8)
            if (body.size <= maxEntryBytes) synchronized(state) {
                if (state.generation == cached.second) {
                    try {
                        check(directory.exists() || directory.mkdirs())
                        val temp = File.createTempFile("pending-", ".tmp", directory)
                        try {
                            temp.writeBytes(body)
                            Files.move(temp.toPath(), File(directory, "$key.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
                        } finally { temp.delete() }
                        trim()
                    } catch (_: IOException) { /* A cache write failure must not discard successful catalog data. */ }
                }
            }
            return record(reply)
        } catch (error: Exception) {
            val temporary = error is IOException && (error !is ApiException || error.code == 0 || error.code in setOf(408, 429) || error.code in 500..599)
            if (temporary && previous != null && timestamp - previous.savedAtMillis in 0..staleMillis && synchronized(state) { state.generation == cached.second })
                return record(previous.copy(source = "stale-cache"))
            throw error
        }
    }

    fun info(): PublicCatalogCacheInfo = synchronized(state) {
        val files = files()
        PublicCatalogCacheInfo(files.size, files.sumOf { it.length() }, maxEntries, maxBytes)
    }

    fun clear() = synchronized(state) {
        state.generation++
        files().forEach { it.delete() }
        lastRead = null
    }

    private fun readFile(file: File, url: String): PublicCatalogCacheReply? {
        if (!file.isFile || file.length() !in 1..maxEntryBytes.toLong()) return null
        return try {
            val record = JSONObject(file.readText(Charsets.UTF_8))
            val savedAt = record.optLong("savedAt", -1)
            require(record.getString("url") == url && savedAt > 0 && savedAt <= now() + 300_000)
            val body = record.getJSONObject("body")
            require(!PRIVATE_RESPONSE_KEYS.containsMatchIn(body.toString()))
            PublicCatalogCacheReply(body, "cache", savedAt)
        } catch (_: Exception) { file.delete(); null }
    }
    private fun record(reply: PublicCatalogCacheReply): PublicCatalogCacheReply { lastRead = reply; return reply }
    private fun files(): List<File> = directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.json")) }.orEmpty()
    private fun trim() {
        val files = files().sortedBy { it.lastModified() }.toMutableList()
        var bytes = files.sumOf { it.length() }
        while (files.isNotEmpty() && (files.size > maxEntries || bytes > maxBytes)) {
            val oldest = files.removeAt(0)
            val size = oldest.length()
            if (oldest.delete()) bytes -= size
        }
    }

    companion object {
        private val STATES = mutableMapOf<String, State>()
        private val PRIVATE_RESPONSE_KEYS = Regex("\"(?:access_token|refresh_token|password|authorization|api_key|apikey|streams|playbackUrl)\"\\s*:", RegexOption.IGNORE_CASE)
        internal fun validateAddress(url: String): URI {
            val uri = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("Unsupported public catalog address.") }
            require(uri.scheme == "https" && uri.port in setOf(-1, 443) && uri.userInfo == null && uri.fragment == null &&
                uri.path.orEmpty().split('/').none { it == ".." || it == "." }) { "Unsupported public catalog address." }
            val host = uri.host.orEmpty().lowercase()
            when (host) {
                "v3-cinemeta.strem.io", "cinemeta-catalogs.strem.io" -> {
                    require(uri.rawQuery == null && (uri.rawPath.matches(Regex("/catalog/(movie|series)/top/(?:skip=[0-9]+|search=[^/]+)(?:&genre=[^/]+)?\\.json")) ||
                        uri.rawPath.matches(Regex("/meta/(movie|series)/tt[0-9]{5,12}\\.json")))) { "Unsupported public Cinemeta metadata address." }
                    if (uri.rawPath.startsWith("/catalog/")) {
                        val extras = uri.rawPath.substringAfter("/top/").removeSuffix(".json").split('&')
                        require(extras.all { it.substringBefore('=') in setOf("search", "skip", "genre") }) { "This request is not public catalog metadata." }
                    }
                }
                "kitsu.app" -> {
                    require(uri.path.matches(Regex("/api/edge/anime(?:/[1-9][0-9]*(?:/media-relationships)?)?"))) { "Only public anime metadata can be cached." }
                    val keys = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map { URLDecoder.decode(it.substringBefore('='), "UTF-8") }
                    require(keys.all { it in setOf("page[limit]", "page[offset]", "filter[text]", "filter[subtype]", "filter[categories]", "sort", "include", "fields[anime]") }) {
                        "This request is not public anime metadata."
                    }
                }
                "anime-kitsu.strem.fun" -> require(uri.rawQuery == null && uri.path.matches(Regex("/meta/(movie|series)/kitsu:[1-9][0-9]*\\.json"))) {
                    "Unsupported public anime metadata address."
                }
                else -> error("This service is not a public catalog cache origin.")
            }
            return uri
        }
        private fun fetchPublic(url: String): JSONObject {
            val uri = validateAddress(url)
            return if (uri.host in setOf("v3-cinemeta.strem.io", "cinemeta-catalogs.strem.io")) Net.publicJson(url)
            else Net.json(url, headers = if (uri.host == "kitsu.app") mapOf("Accept" to "application/vnd.api+json") else emptyMap())
        }
    }
}
