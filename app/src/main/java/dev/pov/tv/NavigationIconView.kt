package dev.pov.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small, font-independent navigation symbols drawn on a 24-unit canvas. */
class NavigationIconView(context: Context, private val key: String, color: Int,
    private val theme: ThemeSpec? = null) : View(context) {
    var tint: Int = color
        set(value) { field = value; invalidate() }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (24 * resources.displayMetrics.density + 0.5f).toInt()
        setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return
        val saved = canvas.save()
        canvas.translate(paddingLeft + (width - paddingLeft - paddingRight - size) / 2f,
            paddingTop + (height - paddingTop - paddingBottom - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        ink.color = tint
        ink.style = Paint.Style.STROKE
        when (key) {
            "home" -> home(canvas)
            "movie" -> if (theme == ThemeSpec.ESSENTIAL) clapper(canvas) else movie(canvas)
            "browse" -> if (theme in setOf(ThemeSpec.ESSENTIAL, ThemeSpec.ATELIER)) filmstrip(canvas) else clapper(canvas)
            "series" -> television(canvas)
            "anime" -> anime(canvas)
            "library" -> if (theme in setOf(ThemeSpec.GLASS, ThemeSpec.HORIZON)) bookmark(canvas) else library(canvas)
            "search" -> search(canvas)
            "trakt" -> trakt(canvas)
            "settings" -> settings(canvas)
            "nowplaying" -> {
                canvas.drawRoundRect(2f, 4f, 22f, 20f, 3f, 3f, ink)
                ink.style = Paint.Style.FILL
                canvas.drawPath(Path().apply { moveTo(10f, 8f); lineTo(16f, 12f); lineTo(10f, 16f); close() }, ink)
            }
            else -> canvas.drawCircle(12f, 12f, 8f, ink)
        }
        canvas.restoreToCount(saved)
    }

    private fun home(canvas: Canvas) {
        ink.style = if (theme == ThemeSpec.ESSENTIAL) Paint.Style.STROKE else Paint.Style.FILL
        canvas.drawPath(Path().apply {
            moveTo(2f, 10f); lineTo(12f, 2f); lineTo(22f, 10f)
            lineTo(19f, 10f); lineTo(19f, 21f); lineTo(14.5f, 21f)
            lineTo(14.5f, 14f); lineTo(9.5f, 14f); lineTo(9.5f, 21f)
            lineTo(5f, 21f); lineTo(5f, 10f); close()
        }, ink)
    }

    private fun movie(canvas: Canvas) {
        canvas.drawCircle(12f, 12f, 9f, ink)
        canvas.drawCircle(8.2f, 7.8f, 2f, ink)
        canvas.drawCircle(15.8f, 7.8f, 2f, ink)
        canvas.drawCircle(7.4f, 14.8f, 2f, ink)
        canvas.drawCircle(16.6f, 14.8f, 2f, ink)
        ink.style = Paint.Style.FILL
        canvas.drawCircle(12f, 12f, 1.2f, ink)
    }

    private fun television(canvas: Canvas) {
        canvas.drawRoundRect(3f, 7f, 21f, 20f, 1.8f, 1.8f, ink)
        canvas.drawLine(8f, 2.5f, 12f, 6.5f, ink)
        canvas.drawLine(16f, 2.5f, 12f, 6.5f, ink)
        canvas.drawLine(8f, 22f, 16f, 22f, ink)
    }

    private fun anime(canvas: Canvas) {
        if (theme == ThemeSpec.HORIZON) {
            canvas.drawPath(Path().apply {
                moveTo(2f, 4f); quadTo(12f, 7.5f, 22f, 4f)
                moveTo(3f, 8f); lineTo(21f, 8f)
                moveTo(7f, 8f); lineTo(6f, 22f)
                moveTo(17f, 8f); lineTo(18f, 22f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(12f, 6f); lineTo(12f, 12f)
            }, ink)
            return
        }
        canvas.drawPath(Path().apply {
            moveTo(3f, 10f); lineTo(4f, 2.5f); lineTo(9f, 6f)
            quadTo(12f, 5f, 15f, 6f); lineTo(20f, 2.5f); lineTo(21f, 10f)
            cubicTo(24f, 20f, 17f, 22f, 12f, 22f)
            cubicTo(7f, 22f, 0f, 20f, 3f, 10f); close()
        }, ink)
        ink.style = Paint.Style.FILL
        canvas.drawCircle(8f, 13f, 1.2f, ink)
        canvas.drawCircle(16f, 13f, 1.2f, ink)
        ink.style = Paint.Style.STROKE
        canvas.drawPath(Path().apply {
            moveTo(10f, 16f); quadTo(12f, 18.5f, 14f, 16f)
        }, ink)
    }

    private fun library(canvas: Canvas) {
        canvas.drawRoundRect(3f, 3f, 6.4f, 21f, 0.5f, 0.5f, ink)
        canvas.drawRoundRect(8.4f, 3f, 11.8f, 21f, 0.5f, 0.5f, ink)
        canvas.drawPath(Path().apply {
            moveTo(13.2f, 4.2f); lineTo(16.4f, 3.4f)
            lineTo(21f, 20.3f); lineTo(17.7f, 21.1f); close()
        }, ink)
    }

    private fun bookmark(canvas: Canvas) {
        canvas.drawPath(Path().apply {
            moveTo(5f, 3f); quadTo(5f, 2f, 6f, 2f); lineTo(18f, 2f)
            quadTo(19f, 2f, 19f, 3f); lineTo(19f, 22f)
            lineTo(12f, 17f); lineTo(5f, 22f); close()
        }, ink)
    }

    private fun clapper(canvas: Canvas) {
        canvas.drawRoundRect(2.5f, 9f, 21.5f, 22f, 1.5f, 1.5f, ink)
        canvas.drawPath(Path().apply {
            moveTo(2.5f, 9f); lineTo(1.5f, 4f); lineTo(20f, 1f)
            lineTo(21f, 6f); close()
        }, ink)
        canvas.drawLine(6f, 3.3f, 9.5f, 7.9f, ink)
        canvas.drawLine(12f, 2.3f, 15.5f, 6.9f, ink)
        canvas.drawLine(2.5f, 13f, 21.5f, 13f, ink)
    }

    private fun filmstrip(canvas: Canvas) {
        canvas.drawRoundRect(2.5f, 2f, 21.5f, 22f, 1f, 1f, ink)
        canvas.drawLine(7f, 2f, 7f, 22f, ink)
        canvas.drawLine(17f, 2f, 17f, 22f, ink)
        for (y in listOf(6f, 11f, 16f)) {
            canvas.drawLine(3f, y, 6.5f, y, ink)
            canvas.drawLine(17.5f, y, 21f, y, ink)
        }
    }

    private fun search(canvas: Canvas) {
        canvas.drawCircle(10f, 10f, 6.7f, ink)
        canvas.drawLine(15f, 15f, 21.3f, 21.3f, ink)
    }

    private fun trakt(canvas: Canvas) {
        canvas.drawCircle(12f, 12f, 9f, ink)
        canvas.drawPath(Path().apply {
            moveTo(6.7f, 11.9f); lineTo(10.3f, 15.5f); lineTo(17.4f, 8.4f)
        }, ink)
    }

    private fun settings(canvas: Canvas) {
        val gear = Path()
        for (index in 0 until 32) {
            val angle = index * Math.PI / 16.0 - Math.PI / 2.0
            val radius = if (index % 4 == 1 || index % 4 == 2) 9.5 else 7.7
            val x = (12.0 + cos(angle) * radius).toFloat()
            val y = (12.0 + sin(angle) * radius).toFloat()
            if (index == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
        }
        gear.close()
        canvas.drawPath(gear, ink)
        canvas.drawCircle(12f, 12f, 3f, ink)
    }
}
