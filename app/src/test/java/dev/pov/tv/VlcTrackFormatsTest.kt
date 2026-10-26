package dev.pov.tv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Label
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class VlcTrackFormatsTest {
    @Test fun `native display name replaces parsed description without stale Media3 labels`() {
        val parsed = Format.Builder().setLanguage("en").setLabel("English parsed description")
            .setSampleMimeType("audio/ac3").setCodecs("a52").setChannelCount(6).setAverageBitrate(384_000).build()
        // This is the actual invariant hit on the Sony with the old merge sequence.
        assertThrows(IllegalStateException::class.java) { parsed.buildUpon().setLabel("Track 1 - English").build() }
        val merged = VlcTrackFormats.named(parsed, "Track 1 - English", C.TRACK_TYPE_AUDIO)
        assertEquals("Track 1 - English", merged.label)
        assertEquals(listOf(Label("en", "Track 1 - English")), merged.labels)
        assertEquals("en", merged.language)
        assertEquals("audio/ac3", merged.sampleMimeType)
        assertEquals("a52", merged.codecs)
        assertEquals(6, merged.channelCount)
        assertEquals(384_000, merged.bitrate)
        // Subsequent player-side buildUpon calls must remain valid too.
        assertEquals("vlc:1:7", merged.buildUpon().setId("vlc:1:7").build().id)
    }

    @Test fun `missing partial metadata and blank names remain valid typed tracks`() {
        val audio = VlcTrackFormats.named(null, null, C.TRACK_TYPE_AUDIO)
        assertEquals("audio/x-vlc-unknown", audio.sampleMimeType)
        assertNull(audio.label)
        assertTrue(audio.labels.isEmpty())
        val text = VlcTrackFormats.named(null, "\u0000  \n", C.TRACK_TYPE_TEXT)
        assertEquals("text/x-vlc-subtitle", text.sampleMimeType)
        assertNull(text.label)
        val parsed = Format.Builder().setSampleMimeType("text/vtt").setLabel("Embedded subtitles").build()
        val fallback = VlcTrackFormats.named(parsed, "   ", C.TRACK_TYPE_TEXT)
        assertEquals("Embedded subtitles", fallback.label)
        assertEquals("text/vtt", fallback.sampleMimeType)
    }

    @Test fun `localized metadata label lists are replaced consistently and native control text is bounded`() {
        val parsed = Format.Builder().setLanguage("en").setLabels(listOf(Label("en", "English"), Label("fr", "Anglais")))
            .setSampleMimeType("audio/mp4a-latm").build()
        val merged = VlcTrackFormats.named(parsed, "  Native\ntrack\tname  ", C.TRACK_TYPE_AUDIO)
        assertEquals("Native track name", merged.label)
        assertEquals(listOf(Label("en", "Native track name")), merged.labels)
        assertEquals(160, VlcTrackFormats.named(parsed, "x".repeat(400), C.TRACK_TYPE_AUDIO).label!!.length)
    }
}
