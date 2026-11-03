package dev.pov.tv.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID

/** Only public metadata is cached; account, server and configured add-on requests cannot enter here. */
class PublicMetadataCache internal constructor(private val directory: File, private val fetch: (String) -> JSONObject,
    private val clock: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(File(context.applicationContext.cacheDir, "cameo-public-metadata"), ::fetchPublic)
    fun json(address: String): JSONObject {
        require(publicAddress(address)) { "Only public catalog metadata can be cached." }
        val key = MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$key.json")
        val cached = read(file)
        val age = clock() - file.lastModified()
        if (cached != null && age in 0..FRESH_MILLIS) return cached
        try {
            val result = fetch(address)
            val bytes = result.toString().toByteArray()
            if (bytes.size <= MAX_ENTRY_BYTES) synchronized(LOCK) {
                try {
                    directory.mkdirs()
                    val temp = File(directory, "$key.${UUID.randomUUID()}.tmp")
                    try { temp.writeBytes(bytes); if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) } }
                    finally { temp.delete() }
                    directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
                        ?.drop(60)?.forEach { it.delete() }
                } catch (_: Exception) { /* An unavailable cache never blocks real metadata. */ }
            }
            return result
        } catch (error: Exception) {
            if (cached != null && age in 0..STALE_MILLIS) return cached
            throw error
        }
    }
    fun clear() = synchronized(LOCK) { directory.listFiles()?.forEach { it.delete() }; Unit }
    private fun read(file: File): JSONObject? = try {
        if (!file.isFile || file.length() > MAX_ENTRY_BYTES) null else JSONObject(file.readText())
    } catch (_: Exception) { null }
    companion object {
        private val LOCK = Any()
        private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        private const val FRESH_MILLIS = 6 * 60 * 60 * 1000L
        private const val STALE_MILLIS = 7 * 24 * 60 * 60 * 1000L
        internal fun publicAddress(address: String): Boolean = try {
            val u = URI(address)
            u.scheme == "https" && u.userInfo == null && u.fragment == null && u.port in setOf(-1, 443) && when (u.host) {
                "v3-cinemeta.strem.io", "cinemeta-catalogs.strem.io" -> u.query == null && (u.path.startsWith("/catalog/") || u.path.startsWith("/meta/"))
                "anime-kitsu.strem.fun" -> u.query == null && u.path.startsWith("/meta/")
                "kitsu.app" -> u.path.matches(Regex("/api/edge/(anime|categories|mappings|media-relationships)(/.*)?")) &&
                    (u.rawQuery ?: "").split('&').filter { it.isNotBlank() }.all { part ->
                        URLDecoder.decode(part.substringBefore('='), "UTF-8") in setOf("include", "sort", "page[limit]", "page[offset]", "filter[text]", "filter[subtype]")
                    }
                else -> false
            }
        } catch (_: Exception) { false }
        private fun fetchPublic(url: String): JSONObject {
            require(publicAddress(url)) { "Unsupported public metadata address." }
            return if (URI(url).host in setOf("v3-cinemeta.strem.io", "cinemeta-catalogs.strem.io")) Net.publicJson(url)
                else Net.json(url, headers = mapOf("Accept" to "application/vnd.api+json"))
        }
    }
}
