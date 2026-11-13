package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CameoAddonSubtitlesTest {
    private val srt = "1\n00:00:01,000 --> 00:00:03,500\nCaption.\n"

    @Test fun `raw UTF16 SRT VTT and ASS normalize with the correct decoder MIME`() {
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + srt.toByteArray(Charsets.UTF_16LE)
        val file = CameoAddonSubtitles.readFiles(utf16, "English.srt").single()
        assertEquals("application/x-subrip", file.mimeType)
        assertEquals(srt, file.bytes.toString(Charsets.UTF_8))
        assertEquals("text/vtt", CameoAddonSubtitles.readFiles("WEBVTT\n\n00:01.000 --> 00:03.500\nCaption.\n".toByteArray(), "English.vtt").single().mimeType)
        assertEquals("text/x-ssa", CameoAddonSubtitles.readFiles("[Events]\nDialogue: 0,0:00:01.00,0:00:03.50,Caption\n".toByteArray(), "English.ass").single().mimeType)
    }

    @Test fun `multiple archive episodes require exact selection instead of picking the first file`() {
        val bytes = zip("Series.S01E01.en.srt" to srt.toByteArray(), "Series.S01E02.en.srt" to srt.toByteArray())
        val files = CameoAddonSubtitles.readFiles(bytes, "English.zip")
        assertEquals(2, files.size)
        assertNull(CameoAddonSubtitles.chooseFile(files, "English.zip"))
        assertEquals("Series.S01E02.en.srt", CameoAddonSubtitles.chooseFile(files, "Series.S01E02.en.srt")?.entryName)
    }

    @Test fun `gzip subtitles are bounded and retain exact text`() {
        val bytes = ByteArrayOutputStream().also { target -> GZIPOutputStream(target).use { it.write(srt.toByteArray()) } }.toByteArray()
        assertEquals(srt, CameoAddonSubtitles.readFiles(bytes, "English.srt.gz").single().bytes.toString(Charsets.UTF_8))
    }

    @Test fun `unsafe paths error documents and decompression bombs never become subtitle files`() {
        val invalid = listOf(
            zip("../English.srt" to srt.toByteArray()),
            zip("C:/private/English.srt" to srt.toByteArray()),
            "<html>Access denied</html>".toByteArray(),
            zip("English.srt" to ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() }),
        )
        invalid.forEach { bytes ->
            try { CameoAddonSubtitles.readFiles(bytes, "English.srt"); fail("Invalid subtitle accepted") }
            catch (_: AddonSubtitleException) { }
        }
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { target ->
        ZipOutputStream(target).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
}
