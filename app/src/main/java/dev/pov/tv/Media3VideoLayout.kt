package dev.pov.tv

import android.view.SurfaceView
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Set the incoming video geometry before PlayerView attaches its codec output. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object Media3VideoLayout {
    fun prime(view: PlayerView, size: VideoSize) {
        if (view.videoSurfaceView !is SurfaceView) return
        val ratio = if (size.width > 0 && size.height > 0)
            size.width * size.pixelWidthHeightRatio / size.height else 0f
        // Identical to PlayerView.updateAspectRatio for an ordinary SurfaceView.
        // Its setPlayer() attaches output BEFORE doing this, which otherwise
        // resizes a newly attached codec surface on a fullscreen/mini handoff.
        view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            ?.setAspectRatio(ratio.takeIf { it.isFinite() && it > 0 } ?: 0f)
    }

    fun geometryMatchesSurface(view: PlayerView): Boolean {
        val surface = view.videoSurfaceView as? SurfaceView ?: return false
        val content = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return false
        return view.isLaidOut && !view.isLayoutRequested && view.width > 0 && view.height > 0 &&
            content.isLaidOut && !content.isLayoutRequested && content.width > 0 && content.height > 0 &&
            surface.isLaidOut && !surface.isLayoutRequested && surface.width > 0 && surface.height > 0 &&
            surface.holder.surfaceFrame.width() == surface.width && surface.holder.surfaceFrame.height() == surface.height
    }
}

/** Primitive-only diagnostics; the session holds view keys weakly. */
internal data class VideoBindingGeometry(
    val surfaceWidthBefore: Int,
    val surfaceHeightBefore: Int,
    val bufferWidthBefore: Int,
    val bufferHeightBefore: Int,
    val contentWidthBefore: Int,
    val contentHeightBefore: Int,
    var layoutRequestedAfterBinding: Boolean = false,
    var surfaceResizeCount: Int = 0,
    var bufferResizeCount: Int = 0,
    var surfaceWidthAfter: Int = surfaceWidthBefore,
    var surfaceHeightAfter: Int = surfaceHeightBefore,
    var bufferWidthAfter: Int = bufferWidthBefore,
    var bufferHeightAfter: Int = bufferHeightBefore,
)
