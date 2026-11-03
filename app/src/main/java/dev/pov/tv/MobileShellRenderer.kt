package dev.pov.tv

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.ceil

/** Native touch shell with the same bottom destinations on phone, Fold, and tablet windows. */
class MobileShellRenderer(private val context: Context, private val theme: ThemeSpec) {
    private val ui = MobileUi(context, theme)
    private val profile = ui.profile
    private val window = AdaptiveLayout.snapshot(context)
    private val headerHeight = ceil(30 * context.resources.configuration.fontScale + 16).toInt().coerceAtLeast(52)
    private val categoriesHeight = ceil(20 * context.resources.configuration.fontScale + 20).toInt().coerceAtLeast(48)
    private val navigationHeight = ceil(46 + 16.5f * context.resources.configuration.fontScale).toInt().coerceAtLeast(68)

    @Suppress("DEPRECATION")
    fun render(destinations: List<ConceptDestination>, selectedDestination: () -> String): ConceptShellViews {
        (context as? Activity)?.window?.let { activityWindow ->
            activityWindow.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                (if (theme == ThemeSpec.ATELIER) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            activityWindow.statusBarColor = ui.palette.background
            activityWindow.navigationBarColor = ui.palette.background
        }
        val root = FrameLayout(context).apply {
            tag = "mobile-shell:bottom"
            background = if (theme == ThemeSpec.HORIZON) ui.auroraCanvas() else ui.rounded(ui.palette.background, 0)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        val backdrop = FrameLayout(context).apply { visibility = View.GONE }
        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        backdrop.addView(art, FrameLayout.LayoutParams(-1, -1))
        root.addView(backdrop, FrameLayout.LayoutParams(-1, 0))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipToPadding = false
            setPadding(0, 0, 0, ui.dp(24))
        }
        val scroller = ScrollView(context).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false; isFocusable = false
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        val navigation = linkedMapOf<String, View>()
        val header = LinearLayout(context).apply {
            tag = "mobile:header"; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(profile.insetDp), 0, ui.dp(8), 0)
        }
        header.addView(CameoBrandView(context, theme).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
        val wordmark = ui.text("CAMEO", if (theme == ThemeSpec.ATELIER) 25 else 23, ui.palette.text, true).apply {
            tag = "cameo:wordmark"
            typeface = ui.font(if (theme == ThemeSpec.ESSENTIAL) 750 else 600, serif = theme == ThemeSpec.ATELIER)
            letterSpacing = if (theme == ThemeSpec.ESSENTIAL) .015f else .10f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; contentDescription = "Cameo"
        }
        header.addView(wordmark, (if (theme == ThemeSpec.GLASS) LinearLayout.LayoutParams(-2, -2)
            else LinearLayout.LayoutParams(0, -2, 1f)).apply { leftMargin = ui.dp(8) })
        val themeLabel = ui.text(theme.displayName.uppercase(Locale.ROOT), 10, ui.palette.text, theme == ThemeSpec.ESSENTIAL).apply {
            tag = "mobile:theme-label"
            letterSpacing = if (theme == ThemeSpec.ESSENTIAL) .02f else .19f
            gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); maxLines = 1
            when (theme) {
                ThemeSpec.GLASS -> background = ui.rounded(ui.alpha(ui.palette.panel, 130), 10, ui.alpha(ui.palette.accent, 75))
                ThemeSpec.ESSENTIAL -> background = ui.rounded(ui.palette.panel, 9)
                ThemeSpec.PREMIERE -> setTextColor(ui.palette.accent)
                else -> Unit
            }
            if (context.resources.configuration.fontScale >= 1.5f && window.widthDp < 600) visibility = View.GONE
        }
        header.addView(themeLabel, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = if (theme == ThemeSpec.GLASS) ui.dp(12) else 0; rightMargin = ui.dp(4)
        })
        if (theme == ThemeSpec.GLASS) header.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        destinations.firstOrNull { it.key == "settings" }?.let { target ->
            val settings = iconAction(target)
            navigation[target.key] = settings
            header.addView(settings, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        }
        val standard = setOf("home", "movie", "series", "anime", "library", "search", "settings")
        val extras = destinations.filter { it.key !in standard }
        if (extras.isNotEmpty()) {
            // The brand remains an accessible menu anchor for account/player destinations.
            header.isClickable = true
            header.setOnClickListener { anchor ->
                PopupMenu(context, anchor).apply {
                    extras.forEachIndexed { index, target -> menu.add(0, index, index, target.label) }
                    setOnMenuItemClickListener { item -> extras.getOrNull(item.itemId)?.action?.invoke(); true }
                }.show()
            }
            header.contentDescription = "Cameo ${theme.displayName}. More destinations."
        }
        root.addView(header, FrameLayout.LayoutParams(-1, ui.dp(headerHeight), Gravity.TOP))
        val categories = LinearLayout(context).apply {
            tag = "mobile:categories"; gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL
            if (profile.tabs == MobileTabStyle.CAPSULE) background = ui.rounded(ui.alpha(ui.palette.background, 145), 26, ui.palette.border)
        }
        root.addView(categories, FrameLayout.LayoutParams(-1, ui.dp(categoriesHeight), Gravity.TOP).apply {
            leftMargin = ui.dp(profile.insetDp); rightMargin = ui.dp(profile.insetDp); topMargin = ui.dp(headerHeight)
        })
        var homePage = false
        var glassScrimRaised = false
        fun paintPinnedChrome() {
            header.background = when {
                profile.headerOverArtwork && homePage -> ui.rounded(ui.alpha(ui.palette.panel, if (glassScrimRaised) 245 else 85), 15)
                theme == ThemeSpec.HORIZON -> ui.rounded(Color.TRANSPARENT, 0)
                else -> ui.rounded(ui.palette.background, 0)
            }
            if (profile.headerOverArtwork) categories.background =
                if (glassScrimRaised) ui.rounded(ui.alpha(ui.palette.background, 240), 24) else null
        }
        fun refreshLayout() {
            val categoriesVisible = homePage || selectedDestination() in setOf("movie", "series")
            categories.visibility = if (categoriesVisible) View.VISIBLE else View.GONE
            glassScrimRaised = homePage && profile.headerOverArtwork && scroller.scrollY > ui.dp(32)
            paintPinnedChrome()
            val lp = scroller.layoutParams as FrameLayout.LayoutParams
            val inset = if (homePage) { if (profile.fullBleedHero) 0 else profile.insetDp } else window.horizontalInsetDp
            lp.leftMargin = ui.dp(inset); lp.rightMargin = ui.dp(inset)
            lp.topMargin = when {
                homePage && profile.headerOverArtwork -> 0
                categoriesVisible -> ui.dp(headerHeight + categoriesHeight + if (theme == ThemeSpec.ESSENTIAL) 14 else if (profile.fullBleedHero) 0 else 4)
                else -> ui.dp(headerHeight + 12)
            }
            lp.bottomMargin = ui.dp(navigationHeight + if (profile.floatingNavigation) 8 else 4)
            scroller.layoutParams = lp
        }
        scroller.tag = MobileShellState { home -> homePage = home; refreshLayout() }
        listOf("home" to "For you", "movie" to "Movies", "series" to "Shows").forEachIndexed { index, (key, label) ->
            destinations.firstOrNull { it.key == key }?.let { target ->
                val tab = category(target, label, selectedDestination) { refreshLayout() }
                categories.addView(tab, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (profile.tabs in setOf(MobileTabStyle.GLASS_PILLS, MobileTabStyle.SOLID_PILLS)) leftMargin = if (index == 0) 0 else ui.dp(6)
                })
                navigation["category:$key"] = tab
            }
        }
        val primary = listOf("home", "movie", "anime", "library", "search")
            .mapNotNull { key -> destinations.firstOrNull { it.key == key } }
        val nav = LinearLayout(context).apply {
            tag = "mobile:navigation"; orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER; setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = when {
                theme == ThemeSpec.GLASS -> ui.glassSurface(24)
                theme == ThemeSpec.HORIZON -> ui.rounded(ui.alpha(ui.palette.background, 225), 34, ui.palette.border)
                theme == ThemeSpec.ESSENTIAL -> ui.rounded(ui.palette.panel, 0)
                else -> ui.rounded(ui.palette.background, 0)
            }
        }
        primary.forEach { target ->
            val browse = target.key == "movie"
            val cell = navigation(target, selectedDestination, if (browse) "Browse" else target.label, if (browse) "browse" else target.key) { refreshLayout() }
            navigation[target.key] = cell
            if (browse) navigation["series"] = cell
            nav.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val navigationFrame = FrameLayout(context).apply {
            tag = "mobile:navigation-frame"
            addView(nav, FrameLayout.LayoutParams(-1, -1))
            if (!profile.floatingNavigation && theme != ThemeSpec.ESSENTIAL) addView(View(context).apply { setBackgroundColor(ui.palette.border) },
                FrameLayout.LayoutParams(-1, ui.dp(1), Gravity.TOP))
        }
        root.addView(navigationFrame, FrameLayout.LayoutParams(-1, ui.dp(navigationHeight), Gravity.BOTTOM).apply {
            leftMargin = if (theme == ThemeSpec.HORIZON) ui.dp(8) else 0; rightMargin = leftMargin
            bottomMargin = if (profile.floatingNavigation) ui.dp(4) else 0
        })
        val badge = ui.text("", 11, ui.palette.muted).apply { visibility = View.GONE }
        if (profile.headerOverArtwork) scroller.viewTreeObserver.addOnScrollChangedListener {
            val raised = homePage && scroller.scrollY > ui.dp(32)
            if (raised != glassScrimRaised) {
                glassScrimRaised = raised
                // Main owns OnScrollChangeListener. This observer changes only paint at the threshold.
                paintPinnedChrome()
            }
        }
        refreshLayout(); root.requestApplyInsets()
        return ConceptShellViews(root, body, scroller, backdrop, art, badge, navigation)
    }

    private fun iconAction(target: ConceptDestination): View = FrameLayout(context).apply {
        id = View.generateViewId(); tag = "nav:${target.key}"; contentDescription = target.label
        isFocusable = true; isClickable = true; background = ui.ripple(ui.rounded(Color.TRANSPARENT, 24))
        addView(NavigationIconView(context, target.key, ui.palette.text, theme), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
        setOnClickListener { target.action() }
    }

    private fun category(target: ConceptDestination, title: String, selectedDestination: () -> String, updateLayout: () -> Unit): View {
        val cell = FrameLayout(context).apply {
            id = View.generateViewId(); tag = "mobile:category:${target.key}"; contentDescription = title
            minimumHeight = ui.dp(48); isFocusable = true; isClickable = true
        }
        val label = ui.text(title, 16, ui.palette.text).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        cell.addView(label, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER).apply { topMargin = ui.dp(5); bottomMargin = ui.dp(5) })
        val underline = View(context)
        cell.addView(underline, FrameLayout.LayoutParams(-1, ui.dp(2), Gravity.BOTTOM).apply {
            leftMargin = ui.dp(14); rightMargin = ui.dp(14); bottomMargin = if (profile.tabs == MobileTabStyle.CAPSULE) ui.dp(1) else ui.dp(2)
        })
        fun paint(focused: Boolean) {
            val selected = selectedDestination() == target.key
            cell.isSelected = selected; label.typeface = ui.font(if (selected) 700 else 450)
            val pills = profile.tabs in setOf(MobileTabStyle.SOLID_PILLS, MobileTabStyle.GLASS_PILLS)
            label.setTextColor(if (selected && profile.tabs == MobileTabStyle.SOLID_PILLS) ui.palette.buttonText
                else if (selected && theme == ThemeSpec.ATELIER) ui.palette.accent else ui.palette.text)
            label.background = when (profile.tabs) {
                MobileTabStyle.SOLID_PILLS -> ui.rounded(if (selected) ui.palette.accent else ui.palette.panel, 24)
                MobileTabStyle.GLASS_PILLS -> ui.rounded(ui.alpha(if (selected) ui.palette.accent else ui.palette.panel, if (selected) 150 else 175), 24,
                    ui.alpha(ui.palette.accent, if (selected) 220 else 95))
                else -> null
            }
            underline.visibility = if (!pills && selected) View.VISIBLE else View.INVISIBLE
            underline.setBackgroundColor(ui.palette.accent)
            cell.background = ui.ripple(ui.rounded(if (focused) ui.alpha(ui.palette.accent, 35) else Color.TRANSPARENT, if (pills) 24 else 8))
            updateLayout()
        }
        cell.setOnFocusChangeListener { _, focused -> paint(focused) }
        cell.setOnClickListener { target.action(); paint(cell.hasFocus()) }
        paint(false)
        return cell
    }

    private fun navigation(target: ConceptDestination, selectedDestination: () -> String, title: String, iconKey: String, updateLayout: () -> Unit): View {
        val cell = LinearLayout(context).apply {
            id = View.generateViewId(); tag = "nav:${target.key}"; contentDescription = title
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            minimumHeight = ui.dp(56); setPadding(ui.dp(2), ui.dp(5), ui.dp(2), ui.dp(5))
        }
        val icon = NavigationIconView(context, iconKey, ui.palette.text, theme)
        val label = ui.text(if (target.key == "series") "Shows" else title.replaceFirstChar { it.titlecase(Locale.ROOT) }, 11, ui.palette.text).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        cell.addView(icon, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        cell.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        fun paint(focused: Boolean) {
            val selected = if (iconKey == "browse") selectedDestination() in setOf("movie", "series") else selectedDestination() == target.key
            cell.isSelected = selected
            val color = if (selected || focused) ui.palette.accent else ui.palette.text
            icon.tint = color; label.setTextColor(color); label.typeface = ui.font(if (selected) 650 else 450)
            cell.background = ui.ripple(ui.rounded(if (focused) ui.palette.navSelected else Color.TRANSPARENT, 18))
            updateLayout()
        }
        cell.setOnFocusChangeListener { _, focused -> paint(focused) }
        cell.setOnClickListener { target.action(); paint(cell.hasFocus()) }
        paint(false)
        return cell
    }

    companion object {
        /** The Activity reuses the shell between Home, catalog and detail routes. */
        @Suppress("UNUSED_PARAMETER")
        fun applyContentInsets(scroller: ScrollView, theme: ThemeSpec, home: Boolean) {
            (scroller.tag as? MobileShellState)?.apply?.invoke(home)
        }
    }
}

private class MobileShellState(val apply: (Boolean) -> Unit)

/** Mobile controls use sans text. Editorial and condensed faces are display roles only. */
internal class MobileUi(val context: Context, val theme: ThemeSpec) {
    val palette: ThemePalette = theme.palette
    val profile = MobileThemeProfile.forTheme(theme)
    fun dp(value: Int) = AdaptiveLayout.dp(context, value)
    fun alpha(color: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    fun font(weight: Int = 400, serif: Boolean = false, condensed: Boolean = false): Typeface {
        if (condensed) return Typeface.create("sans-serif-condensed", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        val key = "${if (serif) "serif" else "sans"}:$weight"
        return fonts.getOrPut(key) {
            runCatching {
                Typeface.Builder(context.assets, if (serif) "fonts/CormorantGaramond-Variable.ttf" else "fonts/Inter-Variable.ttf")
                    .setFontVariationSettings("'wght' ${weight.coerceIn(if (serif) 300 else 100, if (serif) 700 else 900)}")
                    .setWeight(weight).build()
            }.getOrElse { Typeface.create(if (serif) "serif" else "sans-serif", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL) }
        }
    }
    fun text(value: String, size: Int, color: Int = palette.text, bold: Boolean = false) = TextView(context).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat()); setTextColor(color)
        typeface = font(if (bold) 650 else 400); includeFontPadding = false
    }
    fun display(value: String, size: Int, color: Int = palette.text) = text(value, size, color, true).apply {
        typeface = font(if (theme == ThemeSpec.ATELIER) 600 else if (theme == ThemeSpec.PREMIERE) 400 else 750,
            serif = theme == ThemeSpec.ATELIER, condensed = theme == ThemeSpec.PREMIERE)
        if (theme == ThemeSpec.PREMIERE) { textScaleX = .75f; letterSpacing = .04f }
    }
    fun section(value: String) = text(value, if (theme == ThemeSpec.ATELIER) 25 else 22, bold = true).apply {
        typeface = font(if (theme == ThemeSpec.ATELIER) 650 else 700, serif = theme == ThemeSpec.ATELIER)
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1).coerceAtLeast(1), stroke)
    }
    fun glassSurface(radius: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(alpha(palette.accent, 110), alpha(palette.panel, 225), alpha(palette.background, 205))).apply {
        cornerRadius = dp(radius).toFloat(); setStroke(dp(1).coerceAtLeast(1), alpha(palette.accent, 75))
    }
    fun auroraCanvas() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF08363F.toInt(), palette.background, 0xFF201135.toInt(), palette.background))
    fun ripple(content: Drawable) = RippleDrawable(ColorStateList.valueOf(alpha(palette.accent, 45)), content, rounded(Color.WHITE, 18))
    fun action(label: String, primary: Boolean, callback: () -> Unit) = text(label, 16,
        if (primary) palette.buttonText else palette.text, true).apply {
        gravity = Gravity.CENTER; minimumHeight = dp(48); maxLines = 2
        setPadding(dp(10), dp(10), dp(10), dp(10)); isFocusable = true; isClickable = true; contentDescription = label
        val surface = when {
            !primary && theme == ThemeSpec.GLASS -> glassSurface(profile.buttonRadiusDp)
            else -> rounded(if (primary) palette.primaryButton else Color.TRANSPARENT, profile.buttonRadiusDp,
                if (primary) Color.TRANSPARENT else if (theme == ThemeSpec.ATELIER) palette.muted else palette.text)
        }
        background = ripple(surface); setOnClickListener { callback() }
    }
    private companion object { val fonts = mutableMapOf<String, Typeface>() }
}
