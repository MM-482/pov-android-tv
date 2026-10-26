package dev.pov.tv

import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.Collections

/** Legacy Sony codec ordering based on reproduced output-handoff pauses. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object Media3DecoderPolicy {
    private const val STALLED_VP9_DECODER = "OMX.MTK.VIDEO.DECODER.VP9"
    private const val SOFTWARE_AVC_DECODER = "OMX.google.h264.decoder"

    fun affected(device: String = Build.DEVICE, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        device == "SVP-DTV15" && sdk == 26

    fun selector(delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
        device: String = Build.DEVICE, sdk: Int = Build.VERSION.SDK_INT): MediaCodecSelector {
        if (!affected(device, sdk)) return delegate
        return MediaCodecSelector { mimeType, secure, tunneling ->
            val decoders = delegate.getDecoderInfos(mimeType, secure, tunneling)
            when {
                mimeType == MimeTypes.VIDEO_VP9 -> Collections.unmodifiableList(decoders.filterNot { it.name == STALLED_VP9_DECODER })
                mimeType == MimeTypes.VIDEO_H264 && decoders.any { it.name == SOFTWARE_AVC_DECODER } ->
                    // Only promote a decoder returned for this exact secure /
                    // tunneling query. Preserve every other fallback's order.
                    // Media3 still sorts by actual format support before use.
                    Collections.unmodifiableList(decoders.sortedBy { if (it.name == SOFTWARE_AVC_DECODER) 0 else 1 })
                else -> decoders
            }
        }
    }

    fun trackParameters(parameters: TrackSelectionParameters,
        device: String = Build.DEVICE, sdk: Int = Build.VERSION.SDK_INT): TrackSelectionParameters {
        if (!affected(device, sdk) || parameters.preferredVideoMimeTypes.isNotEmpty()) return parameters
        // This is a preference, not a track-type exclusion: VP9-only media can
        // still select another advertised decoder. Preserve explicit preferences,
        // audio/subtitle choices, overrides and the selected playback engine.
        return parameters.buildUpon().setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_VP9).build()
    }
}
