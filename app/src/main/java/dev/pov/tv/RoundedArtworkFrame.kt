package dev.pov.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.widget.FrameLayout

/** Clips artwork consistently on hardware and software canvases, without clipping the focus ring. */
internal open class RoundedArtworkFrame @JvmOverloads constructor(context: Context, private val radiusPx: Float = 0f) : FrameLayout(context) {
    private val artworkClip = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        artworkClip.reset()
        val radius = radiusPx.coerceAtMost(minOf(w, h) / 2f).coerceAtLeast(0f)
        artworkClip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.clipPath(artworkClip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
