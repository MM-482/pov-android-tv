package dev.pov.tv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class Media3DecoderPolicyTest {
    private fun codec(name: String, mime: String = MimeTypes.VIDEO_VP9) = MediaCodecInfo.newInstance(
        name, mime, mime, null, !name.startsWith("OMX.google"), name.startsWith("OMX.google"),
        !name.startsWith("OMX.google"), false, false)

    @Test fun `advertised software AVC becomes primary while hardware and other fallbacks keep their relative order`() {
        val hardware = codec("OMX.MTK.VIDEO.DECODER.AVC", MimeTypes.VIDEO_H264)
        val other = codec("OMX.other.AVC", MimeTypes.VIDEO_H264)
        val software = codec("OMX.google.h264.decoder", MimeTypes.VIDEO_H264)
        val last = codec("OMX.last.AVC", MimeTypes.VIDEO_H264)
        val upstream = listOf(hardware, other, software, last)
        val queries = mutableListOf<Triple<String, Boolean, Boolean>>()
        val selector = Media3DecoderPolicy.selector(MediaCodecSelector { mime, secure, tunneling ->
            queries += Triple(mime, secure, tunneling); upstream
        }, "SVP-DTV15", 26)
        val result = selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
        assertEquals(listOf(software, hardware, other, last), result)
        assertSame(software, result.first())
        assertSame("MTK remains the next advertised initialization fallback", hardware, result[1])
        assertEquals(listOf(Triple(MimeTypes.VIDEO_H264, false, false)), queries)
        assertEquals(listOf(hardware, other, software, last), upstream)
    }

    @Test fun `secure or tunneled queries never acquire a software decoder that upstream did not advertise`() {
        val secureHardware = codec("OMX.MTK.VIDEO.DECODER.AVC.secure", MimeTypes.VIDEO_H264)
        val upstream = listOf(secureHardware)
        val queries = mutableListOf<Triple<String, Boolean, Boolean>>()
        val selector = Media3DecoderPolicy.selector(MediaCodecSelector { mime, secure, tunneling ->
            queries += Triple(mime, secure, tunneling); upstream
        }, "SVP-DTV15", 26)
        assertSame(upstream, selector.getDecoderInfos(MimeTypes.VIDEO_H264, true, false))
        assertSame(upstream, selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, true))
        assertEquals(listOf(Triple(MimeTypes.VIDEO_H264, true, false), Triple(MimeTypes.VIDEO_H264, false, true)), queries)
    }

    @Test fun `AVC without advertised Google software and other MIME types keep the identical fallback list`() {
        val upstream = listOf(codec("OMX.MTK.VIDEO.DECODER.AVC", MimeTypes.VIDEO_H264))
        val selector = Media3DecoderPolicy.selector(MediaCodecSelector { _, _, _ -> upstream }, "SVP-DTV15", 26)
        assertSame(upstream, selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false))
        assertSame(upstream, selector.getDecoderInfos(MimeTypes.VIDEO_H265, false, false))
        assertSame(upstream, selector.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false))
    }

    @Test fun `only proven MTK VP9 is excluded with remaining decoder order and query flags preserved`() {
        val stalled = codec("OMX.MTK.VIDEO.DECODER.VP9")
        val software = codec("OMX.google.vp9.decoder")
        val other = codec("OMX.other.VP9")
        val secureVariant = codec("OMX.MTK.VIDEO.DECODER.VP9.secure")
        val queries = mutableListOf<Triple<String, Boolean, Boolean>>()
        val delegate = MediaCodecSelector { mime, secure, tunneling ->
            queries += Triple(mime, secure, tunneling)
            listOf(stalled, software, other, secureVariant)
        }
        val selector = Media3DecoderPolicy.selector(delegate, "SVP-DTV15", 26)
        assertEquals(listOf(software, other, secureVariant), selector.getDecoderInfos(MimeTypes.VIDEO_VP9, true, true))
        assertEquals(listOf(Triple(MimeTypes.VIDEO_VP9, true, true)), queries)
        assertEquals(listOf(stalled, software, other, secureVariant), selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false))
        assertEquals(Triple(MimeTypes.VIDEO_H264, false, false), queries.last())
    }

    @Test fun `other devices and Android releases retain the identical upstream selector`() {
        val delegate = MediaCodecSelector { _, _, _ -> emptyList() }
        assertSame(delegate, Media3DecoderPolicy.selector(delegate, "SVP-DTV15", 27))
        assertSame(delegate, Media3DecoderPolicy.selector(delegate, "BRAVIA_ATV2", 26))
        assertSame(delegate, Media3DecoderPolicy.selector(delegate, "other-device", 26))
    }

    @Test fun `decoder query failure is not hidden or changed into a fabricated supported decoder`() {
        val failure = IllegalStateException("synthetic decoder query failure")
        val selector = Media3DecoderPolicy.selector(MediaCodecSelector { _, _, _ -> throw failure }, "SVP-DTV15", 26)
        try { selector.getDecoderInfos(MimeTypes.VIDEO_VP9, false, false); fail("Expected query failure") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }

    @Test fun `no remaining VP9 decoder stays unavailable with existing actionable compatibility error`() {
        val selector = Media3DecoderPolicy.selector(MediaCodecSelector { _, _, _ -> listOf(codec("OMX.MTK.VIDEO.DECODER.VP9")) }, "SVP-DTV15", 26)
        assertTrue(selector.getDecoderInfos(MimeTypes.VIDEO_VP9, false, false).isEmpty())
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(720).setHeight(576).build()
        val support = RendererCapabilities.getFormatSupport(MediaCodecVideoRenderer.supportsFormat(
            RuntimeEnvironment.getApplication(), selector, format))
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support)
        val tracks = Tracks(listOf(Tracks.Group(TrackGroup(format), false,
            intArrayOf(support), booleanArrayOf(false))))
        val issue = requireNotNull(VideoPlaybackCompatibility.issue(tracks, Player.STATE_BUFFERING))
        assertEquals(VideoCompatibilityReason.UNSUPPORTED, issue.reason)
        assertTrue(issue.message.contains("VP9"))
        assertTrue(issue.message.contains("Choose a compatible copy"))
    }

    @Test fun `soft AVC preference preserves audio subtitles disabled types overrides and explicit video preference`() {
        val audioGroup = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("fr").build())
        val original = TrackSelectionParameters.Builder().setPreferredAudioLanguage("fr")
            .setPreferredTextLanguage("es").setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .addOverride(TrackSelectionOverride(audioGroup, 0)).build()
        val changed = Media3DecoderPolicy.trackParameters(original, "SVP-DTV15", 26)
        assertEquals(listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_VP9), changed.preferredVideoMimeTypes)
        assertEquals(original.preferredAudioLanguages, changed.preferredAudioLanguages)
        assertEquals(original.preferredTextLanguages, changed.preferredTextLanguages)
        assertEquals(original.disabledTrackTypes, changed.disabledTrackTypes)
        assertEquals(original.overrides, changed.overrides)
        assertSame(original, Media3DecoderPolicy.trackParameters(original, "other-device", 26))
        val explicit = original.buildUpon().setPreferredVideoMimeType(MimeTypes.VIDEO_VP9).build()
        assertSame(explicit, Media3DecoderPolicy.trackParameters(explicit, "SVP-DTV15", 26))
    }
}
