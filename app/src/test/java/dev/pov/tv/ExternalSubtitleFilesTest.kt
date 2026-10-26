package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ExternalSubtitleFilesTest {
    private val valid = "1\n00:00:01,000 --> 00:00:03,500\nA subtitle.\n"
    @Test fun `UTF8 and BOM UTF16 SRT normalize without losing text`() {
        assertEquals(valid, ExternalSubtitleFiles.readSrt(ByteArrayInputStream(valid.toByteArray())))
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + valid.toByteArray(Charsets.UTF_16LE)
        assertEquals(valid, ExternalSubtitleFiles.readSrt(ByteArrayInputStream(utf16)))
    }
    @Test fun `reject empty malformed and oversized documents before caching`() {
        for (bytes in listOf(byteArrayOf(), "not subtitles".toByteArray(), ByteArray(ExternalSubtitleFiles.MAX_BYTES + 1))) {
            try { ExternalSubtitleFiles.readSrt(ByteArrayInputStream(bytes)); fail("Invalid document accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
