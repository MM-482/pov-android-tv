package dev.pov.tv

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class ImportedSubtitle(val uri: Uri, val displayName: String)

/** Copy a user-selected SAF document once; both native and Media3 engines can read the same file. */
internal object ExternalSubtitleFiles {
    const val MAX_BYTES = 4 * 1024 * 1024
    private val timing = Regex("(?m)^\\s*\\d{1,3}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{1,3}:\\d{2}:\\d{2}[,.]\\d{3}[^\\r\\n]*$")

    fun import(context: Context, uri: Uri): ImportedSubtitle {
        require(uri.scheme == "content") { "Choose an SRT document from the Android file picker." }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.take(160)?.replace(Regex("[\\p{Cntrl}]"), "") ?: "External subtitles.srt"
        val text = context.contentResolver.openInputStream(uri)?.use(::readSrt)
            ?: throw IllegalArgumentException("The selected provider could not open this file.")
        val directory = File(context.cacheDir, "subtitles/imported").apply { check(mkdirs() || isDirectory) }
        // No provider path or filename becomes a filesystem path.
        val file = File(directory, "${UUID.randomUUID()}.srt")
        file.writeText(text, Charsets.UTF_8)
        return ImportedSubtitle(Uri.fromFile(file), name)
    }

    internal fun readSrt(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_BYTES) { "This subtitle file is too large (maximum 4 MB)." }
        require(bytes.isNotEmpty()) { "The selected subtitle file is empty." }
        val encoding = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> StandardCharsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> StandardCharsets.UTF_16BE
            else -> StandardCharsets.UTF_8
        }
        val text = try {
            encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) { throw IllegalArgumentException("Save this SRT as UTF-8 or UTF-16 and choose it again.") }
        require('\u0000' !in text && timing.containsMatchIn(text.replace("\r\n", "\n"))) {
            "The selected file does not contain readable SRT subtitle timings."
        }
        return text
    }
}
