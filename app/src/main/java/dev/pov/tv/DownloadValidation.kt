package dev.pov.tv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.net.URI

internal class DownloadValidationException(message: String) : IOException(message)

/** No destination is opened for writing until source bytes and destination emptiness are checked. */
internal object DownloadValidation {
    fun documentProposal(name:String,address:String):Pair<String,String> {
        val extensions=mapOf("mp4" to "video/mp4","m4v" to "video/mp4","mkv" to "video/x-matroska",
            "webm" to "video/webm","avi" to "video/x-msvideo","mov" to "video/quicktime","ts" to "video/mp2t",
            "m2ts" to "video/mp2t","mpg" to "video/mpeg","mpeg" to "video/mpeg","wmv" to "video/x-ms-wmv",
            "flv" to "video/x-flv","ogv" to "video/ogg","3gp" to "video/3gpp")
        val label=name.trim().replace(Regex("[\\p{Cntrl}]"),"").take(180).takeUnless{it.contains("://")}.orEmpty().ifBlank{"Video download"}
        val supplied=label.substringAfterLast('.',"").lowercase(Locale.ROOT)
        if(supplied in extensions)return label to extensions.getValue(supplied)
        val path=runCatching{URI(address).path.orEmpty()}.getOrDefault("")
        val extension=path.substringAfterLast('/',"").substringAfterLast('.',"").lowercase(Locale.ROOT)
        return if(extension in extensions) "$label.$extension" to extensions.getValue(extension)
            else "$label.video" to "application/octet-stream"
    }

    fun redirectDestination(current: URI, location: String): URI {
        val next = try { current.resolve(location) } catch (_: Exception) {
            throw DownloadValidationException("The source sent an invalid redirect.")
        }
        if (!ScopedPlaybackRequests.approved(next.toString()) ||
            current.scheme.equals("https", true) && !next.scheme.equals("https", true))
            throw DownloadValidationException("The source redirected to an unsupported or insecure connection.")
        return next
    }

    fun headersFor(initial: URI, target: URI, headers: Map<String, String>): Map<String, String> =
        if (MediaAddressPolicy.sameOrigin(initial, target)) headers else emptyMap()

    fun requireEmptyDestination(reportedSize: Long?, openForRead: () -> InputStream?) {
        if (reportedSize != null && reportedSize > 0L) throw DownloadValidationException("Choose a new or empty file. Cameo will not overwrite an existing download.")
        val input = try { openForRead() } catch (_: Exception) { null }
            ?: throw DownloadValidationException("The save provider could not confirm that this file is empty. Choose a new file or another save provider.")
        input.use {
            if (it.read() != -1) throw DownloadValidationException("Choose a new or empty file. Cameo will not overwrite existing contents.")
        }
    }

    fun requireVideo(contentType: String, prefix: ByteArray) {
        val type = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (prefix.isEmpty()) throw DownloadValidationException("The source returned an empty file.")
        if (type.startsWith("text/") || listOf("html", "xml", "json", "mpegurl", "dash").any(type::contains))
            throw DownloadValidationException("The source returned a page, document, or adaptive manifest instead of a video file.")
        val leading = prefix.take(128).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        if (leading.startsWith('<') || leading.startsWith('{') || leading.startsWith('[') || leading.startsWith("#EXTM3U", true))
            throw DownloadValidationException("The source returned a page, document, or adaptive manifest instead of a video file.")
        fun ascii(start: Int, length: Int) = if (prefix.size >= start + length) prefix.copyOfRange(start, start + length).toString(Charsets.US_ASCII) else ""
        fun bytes(vararg values: Int) = prefix.size >= values.size && values.indices.all { (prefix[it].toInt() and 255) == values[it] }
        val magic = ascii(4, 4) in setOf("ftyp", "moov", "mdat", "wide", "free", "skip") ||
            bytes(0x1a, 0x45, 0xdf, 0xa3) || (ascii(0, 4) == "RIFF" && ascii(8, 4) == "AVI ") ||
            ascii(0, 4) in setOf("OggS", ".RMF") || ascii(0, 3) == "FLV" ||
            bytes(0, 0, 1, 0xba) || bytes(0, 0, 1, 0xb3) ||
            bytes(0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11) ||
            (prefix.size > 188 && prefix[0] == 0x47.toByte() && prefix[188] == 0x47.toByte()) ||
            (prefix.size > 196 && prefix[4] == 0x47.toByte() && prefix[196] == 0x47.toByte())
        if (looksLikeText(prefix) || (!magic && !type.startsWith("video/")))
            throw DownloadValidationException("The source did not return a recognizable video file. A web error page will not be saved as a movie.")
    }

    private fun looksLikeText(prefix: ByteArray): Boolean {
        val charset = when {
            prefix.size >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            prefix.size >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text = runCatching { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(prefix)).toString().removePrefix("\uFEFF") }.getOrNull() ?: return false
        return text.isNotBlank() && text.all { !it.isISOControl() || it in "\r\n\t" }
    }

    fun copyValidated(source: InputStream, contentType: String, expectedBytes: Long,
        cancelled: () -> Boolean, confirmDestination: () -> Unit, openDestination: () -> OutputStream,
        progress: (Long) -> Unit = {}): Long {
        fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException() }
        checkCancelled()
        val prefix = ByteArrayOutputStream(512)
        val scratch = ByteArray(512)
        while (prefix.size() < 512) {
            checkCancelled()
            val count = source.read(scratch, 0, 512 - prefix.size())
            if (count == -1) break
            if (count > 0) prefix.write(scratch, 0, count)
        }
        val first = prefix.toByteArray()
        requireVideo(contentType, first)
        if (expectedBytes >= 0 && first.size.toLong() > expectedBytes)
            throw DownloadValidationException("The video source returned more bytes than its advertised file length.")
        checkCancelled()
        confirmDestination()
        checkCancelled()
        var written = 0L
        openDestination().use { output ->
            checkCancelled()
            output.write(first)
            written = first.size.toLong()
            progress(written)
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled()
                val count = source.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                written += count
                progress(written)
            }
            checkCancelled()
            if (expectedBytes >= 0L && written != expectedBytes)
                throw DownloadValidationException("The file ended before the transfer finished, or its advertised size changed. Choose a fresh source.")
            output.flush()
        }
        return written
    }
}
