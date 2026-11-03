package dev.pov.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/** Native theme mark. The launcher icon remains an independent user preference. */
class CameoBrandView @JvmOverloads constructor(context: Context, private val theme: ThemeSpec = ThemeSpec.GLASS) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontMetrics = Paint.FontMetrics()
    private val editorialFont = if (theme == ThemeSpec.ATELIER || theme == ThemeSpec.PREMIERE) editorialTypeface(context) else null
    private val cinemaArc = RectF(12f, 10f, 85f, 89f)
    private val mark = Path().apply {
        arcTo(RectF(5f, 5f, 95f, 95f), 41f, 278f)
        lineTo(69.6f, 33f)
        arcTo(RectF(24f, 24f, 76f, 76f), 319f, -278f)
        close()
    }
    private val lowerFacet = Path().apply {
        moveTo(10f, 73f); lineTo(47f, 47f); lineTo(86f, 85f); lineTo(32f, 100f); close()
    }
    private val upperFacet = Path().apply {
        moveTo(9f, 38f); lineTo(49f, 1f); lineTo(38f, 44f); lineTo(4f, 70f); close()
    }
    private val fillShader: Shader? = when (theme) {
        ThemeSpec.GLASS -> LinearGradient(15f, 10f, 80f, 85f,
            intArrayOf(0xFFD8F5FF.toInt(), 0xFF81D9FA.toInt(), 0xFF4299E3.toInt()), null, Shader.TileMode.CLAMP)
        ThemeSpec.HORIZON -> LinearGradient(7f, 12f, 90f, 86f,
            intArrayOf(0xFF0DE3E9.toInt(), 0xFF46B8FF.toInt(), 0xFF9264FF.toInt()), null, Shader.TileMode.CLAMP)
        else -> null
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = AdaptiveLayout.dp(context, 36)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return
        val checkpoint = canvas.save()
        canvas.translate(paddingLeft + (width - paddingLeft - paddingRight - size) / 2f,
            paddingTop + (height - paddingTop - paddingBottom - size) / 2f)
        canvas.scale(size / 100f, size / 100f)
        ink.reset(); ink.isAntiAlias = true
        if (theme == ThemeSpec.ATELIER || theme == ThemeSpec.PREMIERE) {
            ink.color = theme.palette.accent
            ink.typeface = editorialFont
            ink.textSize = 114f
            ink.textAlign = Paint.Align.CENTER
            ink.getFontMetrics(fontMetrics)
            val baseline = 50f - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText("C", 49f, baseline, ink)
            if (theme == ThemeSpec.PREMIERE) {
                ink.style = Paint.Style.STROKE; ink.strokeWidth = 1.5f
                canvas.drawArc(cinemaArc, 54f, 260f, false, ink)
            }
        } else {
            ink.style = Paint.Style.FILL
            ink.shader = fillShader
            ink.color = theme.palette.accent
            canvas.drawPath(mark, ink)
            if (theme == ThemeSpec.HORIZON) {
                val clipped = canvas.save()
                canvas.clipPath(mark)
                ink.shader = null; ink.color = Color.argb(70, 167, 116, 255)
                canvas.drawPath(lowerFacet, ink)
                ink.color = Color.argb(70, 203, 250, 255)
                canvas.drawPath(upperFacet, ink)
                canvas.restoreToCount(clipped)
            }
        }
        canvas.restoreToCount(checkpoint)
    }

    private companion object {
        @Volatile private var editorial: Typeface? = null
        fun editorialTypeface(context: Context): Typeface = editorial ?: synchronized(this) {
            editorial ?: runCatching { Typeface.Builder(context.assets, "fonts/CormorantGaramond-Variable.ttf")
                .setFontVariationSettings("'wght' 600").setWeight(600).build() }
                .getOrDefault(Typeface.create("serif", Typeface.NORMAL)).also { editorial = it }
        }
    }
}
