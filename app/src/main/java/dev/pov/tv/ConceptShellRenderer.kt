package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

data class ConceptDestination(val key: String, val label: String, val action: () -> Unit)
data class ConceptShellViews(
    val root: FrameLayout, val body: LinearLayout, val scroller: ScrollView,
    val heroBackdrop: FrameLayout, val backdropArt: ImageView, val badge: TextView,
    val navigationViews: Map<String, View>,
)

/** Native TV navigation; independently loaded catalog art never contains interface controls. */
class ConceptShellRenderer(private val context: Context, private val theme: ThemeSpec) {
    private val profile = requireNotNull(theme.conceptProfile)
    private val ui = ConceptUi(context, theme)
    private val palette = theme.palette

    fun render(destinations: List<ConceptDestination>, selectedDestination: () -> String): ConceptShellViews {
        val root = FrameLayout(context).apply {
            tag = "concept-shell:${theme.id}"
            setBackgroundColor(palette.background)
            clipChildren = false
        }
        if (theme == ThemeSpec.HORIZON) {
            root.background = ui.gradient(intArrayOf(0xFF073640.toInt(), palette.background, palette.panel), GradientDrawable.Orientation.TL_BR)
        }
        val backdrop = FrameLayout(context).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        backdrop.addView(art, FrameLayout.LayoutParams(-1, -1))
        backdrop.addView(View(context).apply {
            background = ui.gradient(intArrayOf(palette.background, ui.alpha(palette.background, 205), Color.TRANSPARENT), GradientDrawable.Orientation.LEFT_RIGHT)
        }, FrameLayout.LayoutParams(-1, -1))
        backdrop.addView(View(context).apply {
            background = ui.gradient(intArrayOf(Color.TRANSPARENT, ui.alpha(palette.background, 30), palette.background), GradientDrawable.Orientation.TOP_BOTTOM)
        }, FrameLayout.LayoutParams(-1, -1))
        if (theme == ThemeSpec.PREMIERE) backdrop.addView(View(context).apply {
            tag = "concept:header-shade"
            background = ui.gradient(intArrayOf(ui.alpha(palette.background, 245), Color.TRANSPARENT), GradientDrawable.Orientation.TOP_BOTTOM)
        }, FrameLayout.LayoutParams(-1, ui.px(95)))
        root.addView(backdrop, FrameLayout.LayoutParams(-1, ui.px(540)))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            addView(body)
        }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        applyContentInsets(scroller, theme, home = false)
        scroller.setOnScrollChangeListener { _, _, y, _, _ -> backdrop.translationY = -y.toFloat() }

        if (profile.railWidth > 0) {
            val rail = View(context).apply {
                tag = "concept:rail"
                background = if (theme == ThemeSpec.GLASS) ui.gradient(
                    intArrayOf(ui.alpha(palette.panel, 205), ui.alpha(palette.background, 228), ui.alpha(palette.panel, 180)),
                    GradientDrawable.Orientation.TL_BR, 17, ui.alpha(palette.accent, 110))
                else ui.gradient(intArrayOf(0xFF171C21.toInt(), palette.background), GradientDrawable.Orientation.LEFT_RIGHT)
            }
            ui.place(root, rail, profile.railX, profile.railY, profile.railWidth, profile.railHeight)
        } else if (theme == ThemeSpec.HORIZON) {
            ui.place(root, View(context).apply {
                tag = "concept:navigation-capsule"
                background = ui.rounded(ui.alpha(palette.background, 216), 20, palette.border)
            }, 253, 10, 480, 31)
        } else if (theme == ThemeSpec.ATELIER) {
            ui.place(root, View(context).apply { setBackgroundColor(palette.border) }, 23, 40, 914, 1)
        }
        brand(root)

        val navigation = linkedMapOf<String, View>()
        val primary = destinations.filter { it.key !in setOf("trakt", "nowplaying") }
        primary.forEachIndexed { index, target ->
            val cell = navigation(target, selectedDestination)
            val top = profile.railWidth == 0
            val positions = when (theme) {
                ThemeSpec.PREMIERE -> intArrayOf(194, 264, 344, 418, 495, 581, 674)
                ThemeSpec.ATELIER -> intArrayOf(248, 321, 399, 478, 556, 638, 718)
                else -> null
            }
            val widths = when (theme) {
                ThemeSpec.PREMIERE -> intArrayOf(60, 70, 67, 66, 72, 92, 103)
                ThemeSpec.ATELIER -> intArrayOf(64, 70, 70, 66, 72, 70, 89)
                else -> null
            }
            val x = if (top) positions?.getOrNull(index) ?: (profile.navX + index * profile.navStep) else profile.navX
            val y = if (top) profile.navY else profile.navY + index * profile.navStep
            val width = if (top) widths?.getOrNull(index) ?: if (target.key == "settings") 74 else profile.navWidth else profile.navWidth
            ui.place(root, cell, x, y, width, profile.navHeight)
            navigation[target.key] = cell
        }
        primary.forEachIndexed { index, target ->
            val cell = requireNotNull(navigation[target.key])
            val previous = primary.getOrNull(index - 1)?.let { navigation[it.key] }
            val next = primary.getOrNull(index + 1)?.let { navigation[it.key] }
            if (profile.railWidth == 0) {
                cell.nextFocusLeftId = previous?.id ?: cell.id
                cell.nextFocusRightId = next?.id ?: cell.id
            } else {
                cell.nextFocusUpId = previous?.id ?: cell.id
                cell.nextFocusDownId = next?.id ?: cell.id
            }
        }
        val badge = ui.text("", 10, palette.muted).apply { visibility = View.GONE }
        ui.place(root, badge, 710, 5, 230, 22)
        footer(root)
        return ConceptShellViews(root, body, scroller, backdrop, art, badge, navigation)
    }

    private fun brand(root: FrameLayout) {
        if (theme == ThemeSpec.STUDIO) {
            ui.place(root, ui.text("Cameo", 24, palette.text, true).apply { maxLines = 1 },
                profile.logoX, profile.logoY, profile.logoWidth + 24, profile.logoHeight)
            return
        }
        val brand = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Cameo"
            tag = "concept:brand"
        }
        brand.addView(CameoBrandView(context, theme).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.px(profile.logoHeight), ui.px(profile.logoHeight)))
        if (theme == ThemeSpec.PREMIERE || theme == ThemeSpec.ATELIER) {
            brand.addView(View(context).apply { setBackgroundColor(palette.border) },
                LinearLayout.LayoutParams(ui.px(1).coerceAtLeast(1), ui.px(18)).apply { leftMargin = ui.px(8); rightMargin = ui.px(9) })
        }
        val wordmarkSize = if (theme == ThemeSpec.ATELIER) 20 else if (theme == ThemeSpec.ESSENTIAL) 19 else 16
        brand.addView(ui.text("CAMEO", wordmarkSize,
            palette.text, theme == ThemeSpec.ESSENTIAL).apply {
            tag = "concept:wordmark"
            maxLines = 1
            typeface = ui.font(if (theme == ThemeSpec.ESSENTIAL) 750 else 600, serif = theme == ThemeSpec.ATELIER)
            letterSpacing = if (theme == ThemeSpec.ATELIER || theme == ThemeSpec.ESSENTIAL) .01f else .19f
            setAutoSizeTextTypeUniformWithConfiguration(ui.px(13).coerceAtLeast(1), ui.px(wordmarkSize).coerceAtLeast(2),
                ui.px(1).coerceAtLeast(1), TypedValue.COMPLEX_UNIT_PX)
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = if (theme == ThemeSpec.PREMIERE || theme == ThemeSpec.ATELIER) 0 else ui.px(8) })
        ui.place(root, brand, profile.logoX, profile.logoY, profile.logoWidth, profile.logoHeight)
    }

    private fun footer(root: FrameLayout) {
        val footer = ui.text("Standalone app made by Mark Mateo", if (theme == ThemeSpec.GLASS) 8 else 9, palette.muted).apply {
            tag = "concept:credit"
            gravity = if (theme == ThemeSpec.GLASS || theme == ThemeSpec.ATELIER) Gravity.CENTER else Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = if (theme == ThemeSpec.GLASS) 2 else 1
            if (theme == ThemeSpec.ATELIER || theme == ThemeSpec.PREMIERE) typeface = Typeface.create(ui.font(400, serif = true), Typeface.ITALIC)
        }
        when (theme) {
            ThemeSpec.GLASS -> {
                ui.place(root, View(context).apply { setBackgroundColor(ui.alpha(palette.muted, 100)) }, 29, 486, 106, 1)
                ui.place(root, footer, 23, 495, 119, 25)
            }
            ThemeSpec.ATELIER -> {
                ui.place(root, View(context).apply { setBackgroundColor(palette.border) }, 29, 527, 356, 1)
                ui.place(root, View(context).apply { setBackgroundColor(palette.border) }, 559, 527, 371, 1)
                ui.place(root, footer, 386, 517, 173, 18)
            }
            else -> ui.place(root, footer, 640, profile.footerY, 298, 14)
        }
    }

    private fun navigation(target: ConceptDestination, selectedDestination: () -> String): View {
        val top = profile.railWidth == 0
        val cell = FrameLayout(context).apply {
            id = View.generateViewId()
            tag = "nav:${target.key}"
            contentDescription = target.label
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }
        val line = LinearLayout(context).apply {
            gravity = if (top) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(ui.px(if (top) 3 else profile.navIconInset), 0, ui.px(4), 0)
        }
        val icon = NavigationIconView(context, target.key, palette.text)
        val showIcon = !top || (theme == ThemeSpec.PREMIERE && target.key in setOf("search", "settings"))
        if (showIcon) line.addView(icon, LinearLayout.LayoutParams(ui.px(if (top) 15 else 19), ui.px(if (top) 15 else 19)).apply {
            rightMargin = ui.px(if (top) 7 else 12)
        })
        val label = ui.text(if (target.key == "series") "Shows" else target.label,
            if (theme == ThemeSpec.ATELIER) 15 else if (theme == ThemeSpec.HORIZON) 12 else 14, palette.text).apply {
            maxLines = 1
            typeface = ui.font(450, serif = theme == ThemeSpec.ATELIER)
        }
        line.addView(label, LinearLayout.LayoutParams(-2, -2))
        cell.addView(line, FrameLayout.LayoutParams(-1, -1))
        val marker = View(context).apply { background = ui.rounded(palette.accent, 2) }
        if (top) cell.addView(marker, FrameLayout.LayoutParams(-1, ui.px(2).coerceAtLeast(1), Gravity.BOTTOM).apply {
            leftMargin = ui.px(10); rightMargin = ui.px(10)
        })
        else if (theme == ThemeSpec.ESSENTIAL) ui.place(cell, marker, 3, 7, 3, 25)
        fun paint(focused: Boolean) {
            val selected = selectedDestination() == target.key
            marker.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            label.setTextColor(palette.text)
            label.typeface = ui.font(if (selected) 650 else 450, serif = theme == ThemeSpec.ATELIER)
            icon.tint = palette.text
            val radius = if (top) 8 else if (theme == ThemeSpec.GLASS) 11 else 6
            cell.background = when {
                theme == ThemeSpec.GLASS && (selected || focused) -> ui.gradient(
                    intArrayOf(ui.alpha(palette.accent, 115), ui.alpha(palette.panel, 235)),
                    GradientDrawable.Orientation.TL_BR, radius, ui.alpha(palette.accent, 170))
                focused -> ui.rounded(ui.alpha(palette.navSelected, if (theme == ThemeSpec.ATELIER) 235 else 180), radius)
                else -> ui.rounded(Color.TRANSPARENT, radius)
            }
            cell.foreground = if (focused) FocusRingDrawable(palette.accent, ui.px(radius).toFloat(), true, ui.scale) else null
        }
        cell.setOnFocusChangeListener { _, focused -> paint(focused) }
        cell.setOnClickListener { target.action() }
        paint(false)
        return cell
    }

    companion object {
        fun applyContentInsets(scroller: ScrollView, theme: ThemeSpec, home: Boolean) {
            val profile = theme.conceptProfile ?: return
            val scale = scroller.resources.displayMetrics.widthPixels / 960f
            val params = scroller.layoutParams as? FrameLayout.LayoutParams ?: return
            params.leftMargin = if (home) 0 else (profile.contentLeft * scale).roundToInt()
            params.rightMargin = if (home) 0 else (profile.contentRight * scale).roundToInt()
            params.topMargin = if (home) 0 else (profile.headerHeight * scale).roundToInt()
            scroller.layoutParams = params
        }
    }
}

/** TV primitives use the reference canvas; mobile renderers retain independent dp/sp measurements. */
internal class ConceptUi(val context: Context, val theme: ThemeSpec) {
    val scale = context.resources.displayMetrics.widthPixels / 960f
    fun px(value: Int) = (value * scale).roundToInt()
    fun font(weight: Int = 400, serif: Boolean = false, condensed: Boolean = false): Typeface {
        if (condensed) return Typeface.create("sans-serif-condensed", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        val path = if (serif) "fonts/CormorantGaramond-Variable.ttf" else "fonts/Inter-Variable.ttf"
        return synchronized(fonts) { fonts.getOrPut("$path:$weight") {
            try {
                val axisWeight = weight.coerceIn(if (serif) 300 else 100, if (serif) 700 else 900)
                Typeface.Builder(context.assets, path).setWeight(axisWeight)
                    .setFontVariationSettings("'wght' $axisWeight").build()
            }
            catch (_: Exception) { Typeface.create(if (serif) "serif" else "sans-serif", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL) }
        } }
    }
    fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
        setTextColor(color)
        typeface = font(if (bold) 650 else 400)
        includeFontPadding = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(px(1).coerceAtLeast(1), stroke)
    }
    fun gradient(colors: IntArray, orientation: GradientDrawable.Orientation, radius: Int = 0, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable(orientation, colors).apply {
            cornerRadius = px(radius).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(px(1).coerceAtLeast(1), stroke)
        }
    fun alpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    fun place(parent: FrameLayout, view: View, x: Int, y: Int, width: Int, height: Int) {
        parent.addView(view, FrameLayout.LayoutParams(if (width < 0) width else px(width), if (height < 0) height else px(height)).apply {
            leftMargin = px(x); topMargin = px(y)
        })
    }
    fun focus(view: View, radius: Int, color: Int = theme.palette.accent) {
        view.isFocusable = true; view.isFocusableInTouchMode = true; view.isClickable = true
        view.setOnFocusChangeListener { target, focused ->
            target.foreground = if (focused) FocusRingDrawable(color, px(radius).toFloat(), true, scale) else null
            target.scaleX = if (focused) theme.layout.focusScale else 1f; target.scaleY = target.scaleX
            target.elevation = if (focused) px(7).toFloat() else 0f
        }
    }
    private companion object { val fonts = mutableMapOf<String, Typeface>() }
}
