package dev.pov.tv

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.floor
import kotlin.math.roundToInt

/** Compact native portrait previews; the whole card applies its theme with one touch. */
internal class MobileThemePickerRenderer(private val context: Context) {
    fun render(body: LinearLayout, selected: MobileCameoTheme, apply: (MobileCameoTheme) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val config = context.resources.configuration
        val screenWidth = config.screenWidthDp.takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels / density).roundToInt()
        val screenHeight = config.screenHeightDp.takeIf { it > 0 } ?: (context.resources.displayMetrics.heightPixels / density).roundToInt()
        val available = (body.width.takeIf { it > 0 } ?: dp(screenWidth)) - body.paddingLeft - body.paddingRight
        val geometry = geometry((available / density).toInt().coerceAtLeast(120), screenHeight)
        val palette = selected.palette
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; tag = "mobile-theme-grid"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val gridWidth = geometry.columns * geometry.cardWidth + (geometry.columns - 1) * GAP
        MobileCameoTheme.entries.chunked(geometry.columns).forEach { themes ->
            val row = LinearLayout(context).apply { gravity = Gravity.TOP; tag = "mobile-theme-row" }
            themes.forEachIndexed { index, theme ->
                val active = theme == selected
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; tag = "mobile-theme:${theme.name}"
                    contentDescription = "Apply ${theme.title} theme" + if (active) ", selected" else ""
                    minimumHeight = dp(48); setPadding(dp(8), dp(8), dp(8), dp(8)); isSelected = active
                    background = GradientDrawable().apply {
                        setColor(palette.panel); cornerRadius = dp(12).toFloat()
                        setStroke(dp(if (active) 2 else 1).coerceAtLeast(1), if (active) palette.accent else palette.border)
                    }
                    // Background assignment may replace padding; touch targets need the final inset.
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    isClickable = true; isFocusable = true; isFocusableInTouchMode = false
                    setOnClickListener { apply(theme) }
                    setOnFocusChangeListener { target, focused ->
                        target.foreground = if (focused) FocusRingDrawable(palette.accent, dp(12).toFloat(), true, density) else null
                    }
                }
                val imageFrame = FrameLayout(context)
                val image = ImageView(context).apply {
                    tag = "mobile-theme-image:${theme.name}"; scaleType = ImageView.ScaleType.FIT_CENTER
                    isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    context.assets.open("mobile_theme_previews/${String.format(java.util.Locale.ROOT, "%02d", theme.ordinal + 1)}-${theme.name.lowercase(java.util.Locale.ROOT)}.png").use { stream ->
                        setImageBitmap(BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 }))
                    }
                }
                imageFrame.addView(image, FrameLayout.LayoutParams(-1, -1))
                if (active) imageFrame.addView(TextView(context).apply {
                    text = "✓"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(if (selected == MobileCameoTheme.PEARL) Color.WHITE else 0xFF071924.toInt())
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.accent) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false; isFocusable = false
                    tag = "mobile-theme-selected"
                }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); rightMargin = dp(4) })
                card.addView(imageFrame, LinearLayout.LayoutParams(-1, dp(geometry.imageHeight)))
                card.addView(TextView(context).apply {
                    text = theme.title; textSize = 14f; setTextColor(palette.text); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
                    tag = "mobile-theme-name:${theme.name}"; isClickable = false; isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, dp(48)))
                row.addView(card, LinearLayout.LayoutParams(dp(geometry.cardWidth), dp(geometry.imageHeight + 64)).apply { if (index < themes.lastIndex) marginEnd = dp(GAP) })
            }
            grid.addView(row, LinearLayout.LayoutParams(dp(gridWidth), -2).apply { bottomMargin = dp(GAP) })
        }
        body.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }

    internal data class Geometry(val columns: Int, val cardWidth: Int, val imageHeight: Int)
    companion object {
    internal fun geometry(availableWidth: Int, screenHeight: Int): Geometry {
        val preferredHeight = (screenHeight - 300).coerceIn(100, 300)
        val preferredWidth = ((preferredHeight * MAX_ASPECT).roundToInt() + 16).coerceAtLeast(112)
        val minimumColumns = if (availableWidth >= 240) 2 else 1
        val columns = floor((availableWidth + GAP).toDouble() / (preferredWidth + GAP)).toInt().coerceAtLeast(minimumColumns).coerceAtMost(5)
        val width = minOf(preferredWidth, (availableWidth - GAP * (columns - 1)) / columns).coerceAtLeast(48)
        val height = minOf(preferredHeight, ((width - 16) / MAX_ASPECT).toInt()).coerceAtLeast(48)
        return Geometry(columns, width, height)
    }
    private const val GAP = 12
    private const val MAX_ASPECT = 852f / 1846f
    }
}
