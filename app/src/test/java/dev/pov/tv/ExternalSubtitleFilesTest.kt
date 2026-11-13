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

    @Test fun `desktop text subtitle formats are recognized by their own syntax`() {
        val examples = mapOf(
            "vtt" to "WEBVTT\n\n00:01.000 --> 00:03.500\nCaption.\n",
            "ass" to "[Script Info]\nTitle: Fixture\n[Events]\nFormat: Layer, Start, End, Text\nDialogue: 0,0:00:01.00,0:00:03.50,Caption\n",
            "ssa" to "[Events]\nDialogue: Marked=0,0:00:01.00,0:00:03.50,Caption\n",
            "sub" to "{25}{75}Caption\n",
        )
        examples.forEach { (extension, text) ->
            assertEquals(extension, text, ExternalSubtitleFiles.readSubtitle(ByteArrayInputStream(text.toByteArray()), extension))
        }
    }

    @Test fun `wrong extensions documents binary captions and invalid encodings cannot masquerade as subtitles`() {
        val invalid = listOf(
            "vtt" to valid.toByteArray(),
            "ass" to "[Events]\nnot a dialogue".toByteArray(),
            "sub" to byteArrayOf(0, 1, 2, 3),
            "srt" to byteArrayOf(0xc3.toByte(), 0x28),
            "html" to valid.toByteArray(),
        )
        invalid.forEach { (extension, bytes) ->
            try { ExternalSubtitleFiles.readSubtitle(ByteArrayInputStream(bytes), extension); fail("Invalid $extension accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
