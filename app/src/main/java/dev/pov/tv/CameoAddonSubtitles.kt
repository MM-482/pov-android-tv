package dev.pov.tv

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSpec
import dev.pov.tv.subtitles.SubtitleSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

internal class AddonSubtitleChoiceRequired(val requestToken: String, val candidates: List<String>) : IOException(
    "This archive has several subtitle files. Choose the exact file to attach or save.")
internal class AddonSubtitleException(message: String) : IOException(message)
internal data class AddonSubtitleFile(val entryName: String, val bytes: ByteArray, val extension: String, val mimeType: String)

/** Explicit add-on result download. URLs never enter cache names or a persisted request queue. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object CameoAddonSubtitles {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private data class Pending(val files: List<AddonSubtitleFile>, val expiresAt: Long)
    private val pending = linkedMapOf<String, Pending>()
    private val extensions = setOf("srt", "vtt", "ass", "ssa")

    fun fetch(context: Context, url: String, language: String, name: String): ImportedSubtitle {
        SubtitleSettings.language(language)
        if (!ScopedPlaybackRequests.approved(url)) throw AddonSubtitleException("Choose an HTTPS subtitle address or an explicit local-network subtitle address.")
        val source = OriginBoundHttpDataSource(url, emptyMap())
        val downloaded = try {
            val length = source.open(DataSpec(Uri.parse(url)))
            if (length > MAX_BYTES) throw AddonSubtitleException("This subtitle download exceeds the 4 MB limit.")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw AddonSubtitleException("Subtitle download cancelled.")
                val count = source.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                if (count == -1) break
                if (count > 0) output.write(buffer, 0, count)
                if (output.size() > MAX_BYTES) throw AddonSubtitleException("This subtitle download exceeds the 4 MB limit.")
            }
            output.toByteArray()
        } catch (failure: AddonSubtitleException) { throw failure }
        catch (failure: OriginBoundMediaException) { throw AddonSubtitleException(failure.message ?: "The subtitle connection failed.") }
        catch (_: Exception) { throw AddonSubtitleException("This subtitle could not be downloaded. Choose another result.") }
        finally { source.close() }
        val files = readFiles(downloaded, name)
        val chosen = chooseFile(files, name)
        if (chosen != null) return save(context, chosen)
        val token = synchronized(pending) {
            val now = SystemClock.elapsedRealtime()
            pending.entries.removeAll { it.value.expiresAt < now }
            while (pending.size >= 2) pending.remove(pending.keys.first())
            UUID.randomUUID().toString().also { pending[it] = Pending(files, now + 300_000L) }
        }
        throw AddonSubtitleChoiceRequired(token, files.map { it.entryName })
    }

    fun fetchSelected(context: Context, requestToken: String, entryName: String): ImportedSubtitle {
        val choices = synchronized(pending) { pending.remove(requestToken) }
            ?.takeIf { it.expiresAt >= SystemClock.elapsedRealtime() }
            ?: throw AddonSubtitleException("This subtitle selection expired. Download the result again.")
        val file = choices.files.singleOrNull { it.entryName == entryName }
            ?: throw AddonSubtitleException("Choose one of the exact files offered by this archive.")
        return save(context, file)
    }

    internal fun readFiles(bytes: ByteArray, name: String): List<AddonSubtitleFile> {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw AddonSubtitleException("The subtitle download is empty or exceeds 4 MB.")
        val files = try {
            when {
                bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() -> {
                    val output = mutableListOf<AddonSubtitleFile>()
                    val seen = mutableSetOf<String>()
                    var total = 0
                    var count = 0
                    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (++count > 256) throw AddonSubtitleException("This subtitle archive contains too many entries.")
                            val path = entry.name.replace('\\', '/')
                            if (path.length > 300 || path.startsWith('/') || path.contains(':') || '\u0000' in path ||
                                path.split('/').any { it == ".." } || !seen.add(path))
                                throw AddonSubtitleException("This subtitle archive contains unsafe or duplicate paths.")
                            if (!entry.isDirectory) {
                                val raw = bounded(zip, MAX_BYTES - total)
                                total += raw.size
                                if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions)
                                    output += validate(path, raw)
                            }
                            zip.closeEntry()
                        }
                    }
                    output
                }
                bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() ->
                    listOf(validate(name.removeSuffix(".gz"), GZIPInputStream(ByteArrayInputStream(bytes)).use { bounded(it, MAX_BYTES) }))
                else -> listOf(validate(name, bytes))
            }
        } catch (failure: AddonSubtitleException) { throw failure }
        catch (_: Exception) { throw AddonSubtitleException("This subtitle archive could not be read. Choose another result.") }
        if (files.isEmpty()) throw AddonSubtitleException("This result contains no readable SRT, VTT, ASS, or SSA subtitles.")
        return files
    }

    internal fun chooseFile(files: List<AddonSubtitleFile>, preferredName: String): AddonSubtitleFile? =
        files.filter { basename(it.entryName).equals(basename(preferredName), true) }.singleOrNull() ?: files.singleOrNull()

    private fun validate(name: String, raw: ByteArray): AddonSubtitleFile {
        val preferred = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val order = listOf(preferred).filter { it in extensions } + extensions.filter { it != preferred }
        for (extension in order) {
            val text = runCatching { ExternalSubtitleFiles.readSubtitle(ByteArrayInputStream(raw), extension) }.getOrNull() ?: continue
            val normalized = text.toByteArray(Charsets.UTF_8)
            if (normalized.size > MAX_BYTES) throw AddonSubtitleException("The normalized subtitle exceeds the 4 MB limit.")
            return AddonSubtitleFile(name.take(300), normalized, extension, when (extension) {
                "vtt" -> "text/vtt"; "ass", "ssa" -> "text/x-ssa"; else -> "application/x-subrip"
            })
        }
        throw AddonSubtitleException("This file is not readable SRT, VTT, ASS, or SSA subtitles.")
    }

    private fun bounded(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw AddonSubtitleException("Subtitle download cancelled.")
            val count = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - output.size()))
            if (count == -1) break
            if (count > 0) output.write(buffer, 0, count)
            if (output.size() > maximum) throw AddonSubtitleException("The expanded subtitle archive exceeds the 4 MB limit.")
        }
        return output.toByteArray()
    }

    private fun save(context: Context, file: AddonSubtitleFile): ImportedSubtitle {
        val directory = File(context.applicationContext.cacheDir, "cameo-addon-subtitles")
        if (!directory.isDirectory && !directory.mkdirs()) throw AddonSubtitleException("The subtitle cache could not be created. Check free storage.")
        val destination = File(directory, "caption-${UUID.randomUUID()}.${file.extension}")
        try { destination.writeBytes(file.bytes) }
        catch (_: Exception) { destination.delete(); throw AddonSubtitleException("The subtitle could not be saved. Check free storage.") }
        val label = basename(file.entryName).replace(Regex("[\\p{Cntrl}]"), "").take(160)
            .takeUnless { it.contains("://") }.orEmpty().ifBlank { "Downloaded subtitle.${file.extension}" }
        return ImportedSubtitle(Uri.fromFile(destination), label, file.mimeType)
    }

    private fun basename(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
}
