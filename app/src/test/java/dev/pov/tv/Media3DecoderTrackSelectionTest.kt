package dev.pov.tv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Media3 selection proves the Sony policy is a preference, not a VP9 track exclusion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class Media3DecoderTrackSelectionTest {
    private val avc = video("avc", MimeTypes.VIDEO_H264, 640, 360, 500_000)
    private val vp9 = video("vp9", MimeTypes.VIDEO_VP9, 1280, 720, 1_500_000)

    @Test fun `Sony preference selects supported AVC over the otherwise preferred higher resolution VP9`() {
        assertEquals("vp9", select(listOf(avc, vp9), applyPolicy = false).id)
        assertEquals("avc", select(listOf(avc, vp9), applyPolicy = true).id)
    }

    @Test fun `VP9 only media retains a playable selected video track`() {
        val selected = select(listOf(vp9), applyPolicy = true)
        assertEquals("vp9", selected.id)
        assertEquals(MimeTypes.VIDEO_VP9, selected.sampleMimeType)
    }

    @Test fun `unsupported AVC cannot displace supported VP9 despite the Sony MIME preference`() {
        val selected = select(listOf(avc, vp9), applyPolicy = true, avcSupported = false)
        assertEquals("vp9", selected.id)
        assertEquals(MimeTypes.VIDEO_VP9, selected.sampleMimeType)
    }

    private fun select(formats: List<Format>, applyPolicy: Boolean, avcSupported: Boolean = true): Format {
        val context = RuntimeEnvironment.getApplication()
        val defaults = TrackSelectionParameters.Builder(context)
            .clearVideoSizeConstraints().clearViewportSizeConstraints().build()
        val parameters = if (applyPolicy) Media3DecoderPolicy.trackParameters(defaults, "SVP-DTV15", 26) else defaults
        val selector = DefaultTrackSelector(context, parameters)
        selector.init({}, DefaultBandwidthMeter.getSingletonInstance(context))
        val renderer = object : RendererCapabilities {
            override fun getName() = "Offline video capabilities"
            override fun getTrackType() = C.TRACK_TYPE_VIDEO
            override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
            override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
                if (format.sampleMimeType == MimeTypes.VIDEO_H264 && !avcSupported)
                    C.FORMAT_UNSUPPORTED_SUBTYPE else C.FORMAT_HANDLED)
        }
        try {
            val groups = TrackGroupArray(*formats.map { TrackGroup(it.id.orEmpty(), it) }.toTypedArray())
            val result = selector.selectTracks(arrayOf(renderer), groups, MediaSource.MediaPeriodId("offline-period"), Timeline.EMPTY)
            assertTrue("Video renderer remains enabled", result.isRendererEnabled(0))
            val selected = result.selections[0]
            assertNotNull("A supported video is selected", selected)
            assertEquals(1, selected!!.length())
            return selected.selectedFormat
        } finally { selector.release() }
    }

    private fun video(id: String, mime: String, width: Int, height: Int, bitrate: Int) = Format.Builder()
        .setId(id).setSampleMimeType(mime).setWidth(width).setHeight(height)
        .setFrameRate(25f).setAverageBitrate(bitrate).build()
}
