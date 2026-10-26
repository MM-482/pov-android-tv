package dev.pov.tv.subtitles

import android.content.Context
import android.net.Uri
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Blocking service. Search and download must be dispatched on a worker thread. */
class SubtitleRepository internal constructor(
    private val cacheDirectory: File, credentials: SubtitleCredentials, http: SubtitleTransport
) {
    constructor(context: Context, store: SecureStore) : this(File(context.applicationContext.cacheDir, "downloaded-subtitles"), StoredSubtitleCredentials(store), SubtitleHttp())
    private val providers = SubtitleProviders(credentials, http)

    fun search(identity: PlaybackIdentity, language: String = "en"): SubtitleSearchReport {
        require(identity.imdbId.matches(Regex("tt[0-9]{5,12}")) && identity.type in setOf("movie", "series")) { "This video has no supported movie or series identity for subtitle search." }
        require(identity.type != "series" || (identity.season >= 0 && identity.episode > 0)) { "Choose an episode before searching for subtitles." }
        val preferred = SubtitleSettings.language(language)
        val issues = mutableListOf<SubtitleProviderIssue>()
        val results = mutableListOf<SubtitleResult>()
        SubtitleProvider.entries.forEach { provider ->
            if (Thread.currentThread().isInterrupted) throw SubtitleException("Subtitle request cancelled.")
            if (!providers.configured(provider)) {
                issues += SubtitleProviderIssue(provider, "Add your ${provider.label} API key in Settings to search this provider.")
            } else try {
                results += providers.search(provider, identity, preferred).filter {
                    identity.type != "series" || !SubtitleArchive.explicitMismatch(it.fileName.ifBlank { it.displayName }, identity)
                }
            } catch (error: SubtitleException) {
                issues += SubtitleProviderIssue(provider, error.message ?: "This provider is unavailable.")
            } catch (_: Exception) {
                issues += SubtitleProviderIssue(provider, "This provider returned unexpected subtitle data. Try again later.")
            }
        }
        return SubtitleSearchReport(results.distinctBy { it.provider to it.id }, issues)
    }

    fun download(result: SubtitleResult): DownloadedSubtitle {
        val response = providers.download(result)
        val file = SubtitleArchive.read(response.bytes, result)
        synchronized(cacheLock) {
            if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) throw SubtitleException("Subtitle cache could not be created. Check the TV's free storage.")
            // The private cache uses generated names only; archive paths never become disk paths.
            val existing = cacheDirectory.listFiles()?.filter { it.isFile && it.name.startsWith("pov-sub-") }.orEmpty().sortedByDescending { it.lastModified() }
            existing.drop(39).forEach { it.delete() }
            val target = File(cacheDirectory, "pov-sub-${UUID.randomUUID()}.${file.extension}")
            try { target.outputStream().use { it.write(file.bytes) } }
            catch (_: Exception) { target.delete(); throw SubtitleException("Subtitle could not be saved. Check the TV's free storage.") }
            return DownloadedSubtitle(Uri.fromFile(target), file.displayName, result.language, file.mimeType)
        }
    }

    private companion object { val cacheLock = Any() }
}

internal data class SubtitleFile(val bytes: ByteArray, val extension: String, val displayName: String, val mimeType: String)

/** Bounded ZIP/gzip handling with no disk extraction and no guessing between episode files. */
internal object SubtitleArchive {
    private const val MAX_FILE = 4 * 1024 * 1024
    private const val MAX_TOTAL = 20 * 1024 * 1024
    private val subtitleExtension = Regex("(?i)\\.(srt|vtt|ass|ssa)$")
    private val episodeTag = Regex("(?i)(?:^|[^a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})((?:(?:[ ._-]*(?:-|to)[ ._-]*e?\\d{1,3})|(?:[ ._-]*e\\d{1,3}))*)")
    private val episodeTail = Regex("(?i)(?:[ ._-]*(?:-|to)[ ._-]*e?(\\d{1,3}))|(?:[ ._-]*e(\\d{1,3}))")
    private val crossEpisodeTag = Regex("(?i)(?:^|[^a-z0-9])(\\d{1,2})x(\\d{1,3})(?:[ ._-]*-[ ._-]*(\\d{1,3}))?(?=$|[^0-9])")

    fun read(bytes: ByteArray, result: SubtitleResult): SubtitleFile {
        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) throw SubtitleException("The subtitle download is empty or too large.")
        val entries = if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) unzip(bytes) else {
            val raw = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { SubtitleHttp.boundedRead(it, MAX_FILE) }
            } else bytes
            listOf(result.fileName.ifBlank { "subtitle.srt" } to raw)
        }
        val eligible = entries.filter { subtitleExtension.containsMatchIn(it.first) }
            .ifEmpty { if (entries.size == 1) entries else emptyList() }
        val exact = eligible.filter { basename(it.first).equals(basename(result.fileName), true) && result.fileName.isNotBlank() }
        val matching = if (result.identity.type == "series") eligible.filter { tags(it.first).contains(result.identity.season to result.identity.episode) } else emptyList()
        val chosen = when {
            exact.size == 1 -> exact[0]
            matching.size == 1 -> matching[0]
            eligible.size == 1 -> eligible[0]
            eligible.isEmpty() -> throw SubtitleException("This download does not contain a supported SRT, VTT, or ASS subtitle.")
            else -> throw SubtitleException("This archive contains several subtitle files and the right file could not be identified. Choose another result or import a specific subtitle file.")
        }
        if (explicitMismatch(chosen.first, result.identity)) throw SubtitleException("This subtitle archive contains a different episode. Choose another result.")
        val text = decode(chosen.second).trimStart('\uFEFF')
        val extension = when {
            text.trimStart().startsWith("WEBVTT") -> "vtt"
            text.contains("[Events]", true) && text.contains("Dialogue:", true) -> "ass"
            Regex("(?m)\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}").containsMatchIn(text) -> "srt"
            else -> throw SubtitleException("This file is not a readable SRT, VTT, or ASS subtitle. Choose another result.")
        }
        val display = basename(chosen.first).replace(Regex("[\\p{Cntrl}]"), "").take(180).ifBlank { "Downloaded subtitle.$extension" }
        val mime = when (extension) { "vtt" -> "text/vtt"; "ass" -> "text/x-ssa"; else -> "application/x-subrip" }
        return SubtitleFile(text.toByteArray(Charsets.UTF_8), extension, display, mime)
    }

    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        var total = 0
        var count = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > 256) throw SubtitleException("This subtitle archive contains too many files.")
                    val path = entry.name.replace('\\', '/')
                    if (path.startsWith('/') || path.split('/').any { it == ".." } || path.contains(':')) throw SubtitleException("This subtitle archive contains an unsafe file path.")
                    if (!entry.isDirectory) {
                        val data = SubtitleHttp.boundedRead(zip, minOf(MAX_FILE, MAX_TOTAL - total))
                        total += data.size
                        if (subtitleExtension.containsMatchIn(path)) result += path to data
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: SubtitleException) { throw error
        } catch (_: Exception) { throw SubtitleException("This subtitle archive could not be opened. Choose another result.") }
        return result
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_FILE) throw SubtitleException("This subtitle file is too large.")
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return try { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw SubtitleException("This subtitle uses an unsupported text encoding. Choose a UTF-8 subtitle or import a converted file.") }
    }

    fun explicitMismatch(name: String, identity: PlaybackIdentity): Boolean {
        if (identity.type != "series") return false
        val found = tags(name)
        return found.isNotEmpty() && (identity.season to identity.episode) !in found
    }
    private fun tags(name: String): Set<Pair<Int, Int>> {
        val found = mutableSetOf<Pair<Int, Int>>()
        episodeTag.findAll(name).forEach { tag ->
            val season = tag.groupValues[1].toInt()
            var previous = tag.groupValues[2].toInt()
            found += season to previous
            episodeTail.findAll(tag.groupValues[3]).forEach { tail ->
                val end = tail.groupValues[1].toIntOrNull()
                if (end != null) {
                    if (end >= previous && end - previous <= 100) for (episode in previous..end) found += season to episode
                    previous = end
                } else {
                    previous = tail.groupValues[2].toInt()
                    found += season to previous
                }
            }
        }
        crossEpisodeTag.findAll(name).forEach { tag ->
            val season = tag.groupValues[1].toInt()
            val start = tag.groupValues[2].toInt()
            val end = tag.groupValues[3].toIntOrNull() ?: start
            if (end >= start && end - start <= 100) for (episode in start..end) found += season to episode
        }
        return found
    }
    private fun basename(name: String) = name.substringAfterLast('/').substringAfterLast('\\')
}
