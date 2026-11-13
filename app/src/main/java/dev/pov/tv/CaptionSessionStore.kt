package dev.pov.tv

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class CaptionSessionSelection(
    val configuration: MediaItem.SubtitleConfiguration?,
    val offsetMicros: Long,
    val enabled: Boolean,
)

internal interface CaptionSessionMetadata {
    fun read(): String
    fun write(value: String)
}

private class EncryptedCaptionSessionMetadata(context: Context) : CaptionSessionMetadata {
    private val store = SecureStore(context)
    override fun read() = store.get("cameo.caption_sessions.v1", "{}")
    override fun write(value: String) = store.put("cameo.caption_sessions.v1", value)
}

/**
 * Captions belong to a stable title/exact episode, never a temporary resolved stream URL.
 * Metadata is encrypted. The selected text is copied from app-owned cache into app-private files
 * so normal subtitle-cache eviction cannot silently replace it with a different episode.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class CaptionSessionStore internal constructor(
    private val cacheRoot: File,
    private val filesRoot: File,
    private val metadata: CaptionSessionMetadata,
) {
    constructor(context: Context) : this(context.applicationContext.cacheDir, context.applicationContext.filesDir,
        EncryptedCaptionSessionMetadata(context.applicationContext))

    private val directory get() = File(filesRoot, "cameo-caption-selections")

    fun restore(identity: PlaybackIdentity?, fallbackKey: String = ""): CaptionSessionSelection? = synchronized(lock) {
        val key = key(identity, fallbackKey) ?: return@synchronized null
        val index = readIndex()
        val item = index.optJSONObject(key) ?: return@synchronized null
        val enabled = item.optBoolean("enabled", true)
        val offset = item.optLong("offset").takeIf { it in -60_000_000L..60_000_000L } ?: 0L
        val fileName = item.optString("file")
        if (fileName.isBlank()) return@synchronized CaptionSessionSelection(null, offset, enabled)
        val file = retainedFile(fileName)
        val mime = item.optString("mime")
        if (file == null || !file.isFile || !file.canRead() || file.length() !in 1..ExternalSubtitleFiles.MAX_BYTES.toLong() || mime !in MIME_EXTENSIONS) {
            index.remove(key)
            metadata.write(index.toString())
            return@synchronized null
        }
        val language = item.optString("language").takeIf { LANGUAGE.matches(it) }
        val label = safeLabel(item.optString("label"))
        val configuration = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(mime)
            .setLanguage(language).setLabel(label).setId("cameo-external-subtitle")
            .setSelectionFlags(if (enabled) C.SELECTION_FLAG_DEFAULT else 0).build()
        CaptionSessionSelection(configuration, offset, enabled)
    }

    fun remember(identity: PlaybackIdentity?, fallbackKey: String, uri: Uri, displayName: String,
        language: String?, mimeType: String, offsetMicros: Long = 0L): Boolean = synchronized(lock) {
        val key = key(identity, fallbackKey) ?: return@synchronized false
        require(offsetMicros in -60_000_000L..60_000_000L) { "Unsupported subtitle timing offset." }
        val extension = MIME_EXTENSIONS[mimeType] ?: throw IllegalArgumentException("Unsupported subtitle format.")
        val original = ownedSource(uri) ?: throw IllegalArgumentException("Retain only a subtitle imported into Cameo's private storage.")
        val text = original.inputStream().use { ExternalSubtitleFiles.readSubtitle(it, extension) }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= ExternalSubtitleFiles.MAX_BYTES) { "The selected subtitle exceeds the 4 MB limit." }
        val index = readIndex()
        check(directory.isDirectory || directory.mkdirs()) { "The caption selection directory could not be created." }
        val filename = "$key-${UUID.randomUUID()}.$extension"
        val target = File(directory, filename)
        check(target.createNewFile()) { "The caption selection could not be saved." }
        val old = index.optJSONObject(key)?.optString("file")
        try {
            target.outputStream().use { it.write(bytes) }
            index.put(key, JSONObject().put("file", filename).put("mime", mimeType).put("label", safeLabel(displayName))
                .put("language", language?.lowercase(Locale.ROOT)?.takeIf { LANGUAGE.matches(it) }.orEmpty())
                .put("offset", offsetMicros).put("enabled", true).put("updated", System.currentTimeMillis()))
            val pruned = prune(index)
            metadata.write(index.toString())
            (pruned + listOfNotNull(old)).filter { it != filename }.forEach { retainedFile(it)?.delete() }
        } catch (failure: Exception) { target.delete(); throw failure }
        true
    }

    fun updateOffset(identity: PlaybackIdentity?, fallbackKey: String, offsetMicros: Long): Boolean = synchronized(lock) {
        require(offsetMicros in -60_000_000L..60_000_000L) { "Unsupported subtitle timing offset." }
        update(identity, fallbackKey) { it.put("offset", offsetMicros) }
    }

    fun setEnabled(identity: PlaybackIdentity?, fallbackKey: String, enabled: Boolean): Boolean = synchronized(lock) {
        update(identity, fallbackKey) { it.put("enabled", enabled) }
    }

    private fun update(identity: PlaybackIdentity?, fallbackKey: String, apply: (JSONObject) -> Unit): Boolean {
        val key = key(identity, fallbackKey) ?: return false
        val index = readIndex()
        val item = index.optJSONObject(key) ?: JSONObject()
        apply(item)
        item.put("updated", System.currentTimeMillis())
        index.put(key, item)
        val pruned = prune(index)
        metadata.write(index.toString())
        pruned.forEach { retainedFile(it)?.delete() }
        return true
    }

    private fun readIndex(): JSONObject {
        // Decryption failures propagate; never replace unreadable encrypted data with empty state.
        val raw = metadata.read()
        val index = try {
            val parser = JSONTokener(raw)
            val value = parser.nextValue()
            check(value is JSONObject && parser.nextClean() == '\u0000')
            value
        } catch (_: Exception) {
            throw IllegalStateException("Saved caption selections could not be read. Existing selections were preserved.")
        }
        check(index.keys().asSequence().all { key -> INDEX_KEY.matches(key) && index.opt(key) is JSONObject }) {
            "Saved caption selections could not be read. Existing selections were preserved."
        }
        return index
    }

    private fun prune(index: JSONObject): List<String> {
        val expired = index.keys().asSequence().toList().sortedByDescending { index.optJSONObject(it)?.optLong("updated") ?: 0L }.drop(40)
        return expired.mapNotNull { (index.remove(it) as? JSONObject)?.optString("file")?.takeIf(String::isNotBlank) }
    }

    private fun retainedFile(name: String): File? {
        if (!RETAINED_NAME.matches(name)) return null
        val file = runCatching { File(directory, name).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { inside(it, directory) }
    }

    private fun ownedSource(uri: Uri): File? {
        if (uri.scheme != "file") return null
        val file = runCatching { File(requireNotNull(uri.path)).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.isFile && it.canRead() && it.length() in 1..ExternalSubtitleFiles.MAX_BYTES.toLong() &&
            (inside(it, cacheRoot) || inside(it, filesRoot)) }
    }

    private fun inside(file: File, root: File): Boolean = runCatching {
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)

    companion object {
        private val lock = Any()
        private val LANGUAGE = Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")
        private val INDEX_KEY = Regex("[a-f0-9]{64}")
        private val MIME_EXTENSIONS = mapOf("application/x-subrip" to "srt", "text/vtt" to "vtt", "text/x-ssa" to "ass", "application/x-microdvd" to "sub")
        private val RETAINED_NAME = Regex("[a-f0-9]{64}-[a-f0-9-]{36}\\.(?:srt|vtt|ass|ssa|sub)")
        private fun safeLabel(value: String) = value.replace(Regex("[\\p{Cntrl}]"), "").substringBefore('?').substringBefore('#')
            .take(160).takeUnless { it.contains("://") }.orEmpty().ifBlank { "External subtitles" }

        internal fun key(identity: PlaybackIdentity?, fallbackKey: String): String? {
            val stable = identity?.takeIf { it.imdbId.isNotBlank() && it.imdbId.length <= 300 &&
                it.catalogNamespace.length <= 300 && it.type in setOf("movie", "series") &&
                (it.type == "movie" || it.season >= 0 && it.episode > 0) }
            val marker = if (stable != null) {
                // Length-prefix every component so delimiters in custom namespaces cannot collide.
                listOf(stable.catalogNamespace, stable.type, stable.imdbId,
                    (if (stable.type == "series") stable.season else 0).toString(),
                    (if (stable.type == "series") stable.episode else 0).toString()).joinToString("") { "${it.length}:$it" }
            } else fallbackKey.takeIf { it.isNotBlank() && it.length <= 512 && !it.contains("://") && it.none(Char::isISOControl) }
                ?.let { "fallback:$it" } ?: return null
            return MessageDigest.getInstance("SHA-256").digest(marker.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
