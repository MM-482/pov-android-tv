package dev.pov.tv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
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

/** Touch navigation with a bottom bar on narrow windows and a scrollable rail on wider ones. */
class MobileShellRenderer(private val context: Context, private val theme: ThemeSpec) {
    private val ui = MobileUi(context, theme)
    private val window = AdaptiveLayout.snapshot(context)

    @Suppress("DEPRECATION")
    fun render(destinations: List<ConceptDestination>, selectedDestination: () -> String): ConceptShellViews {
        (context as? Activity)?.window?.let { activityWindow ->
            activityWindow.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                (if (theme == ThemeSpec.ATELIER) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            activityWindow.statusBarColor = ui.palette.background
            activityWindow.navigationBarColor = ui.palette.background
        }
        val root = FrameLayout(context).apply {
            tag = "mobile-shell:${if (window.useNavigationRail) "rail" else "bottom"}"
            setBackgroundColor(ui.palette.background)
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
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            setPadding(0, 0, 0, ui.dp(24))
        }
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            isFocusable = false
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = ui.dp(window.railWidthDp + window.horizontalInsetDp)
            rightMargin = ui.dp(window.horizontalInsetDp)
            topMargin = ui.dp(64)
            bottomMargin = if (window.useNavigationRail) 0 else ui.dp(76)
        })

        val navigation = linkedMapOf<String, View>()
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), 0, ui.dp(8), 0)
        }
        val wordmark = ui.text("Cameo", 26, ui.palette.text, true).apply {
            tag = "cameo:wordmark"
            letterSpacing = -.025f
            maxLines = 1
            contentDescription = "Cameo"
        }
        header.addView(wordmark, LinearLayout.LayoutParams(0, -2, 1f))
        listOf("search", "settings").forEach { key ->
            destinations.firstOrNull { it.key == key }?.let { target ->
                val button = iconAction(target)
                navigation[target.key] = button
                header.addView(button, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            }
        }
        val standard = setOf("home", "movie", "series", "anime", "library", "search", "settings")
        val extras = destinations.filter { it.key !in standard }
        if (extras.isNotEmpty() && !window.useNavigationRail) {
            val more = ui.action("⋯", false) {}.apply {
                contentDescription = "More destinations"
                setOnClickListener { anchor ->
                    PopupMenu(context, anchor).apply {
                        extras.forEachIndexed { index, target -> menu.add(0, index, index, target.label) }
                        setOnMenuItemClickListener { item -> extras.getOrNull(item.itemId)?.action?.invoke(); true }
                    }.show()
                }
            }
            header.addView(more, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        }
        root.addView(header, FrameLayout.LayoutParams(-1, ui.dp(60), Gravity.TOP).apply { leftMargin = ui.dp(window.railWidthDp) })

        val primary = if (window.useNavigationRail) destinations.filter { it.key !in setOf("search", "settings") }
            else listOf("home", "movie", "series", "anime", "library").mapNotNull { key -> destinations.firstOrNull { it.key == key } }
        val nav = LinearLayout(context).apply {
            orientation = if (window.useNavigationRail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = ui.rounded(ui.palette.panel, if (window.useNavigationRail) 0 else 24)
        }
        primary.forEach { target ->
            val cell = navigation(target, selectedDestination)
            navigation[target.key] = cell
            nav.addView(cell, if (window.useNavigationRail) LinearLayout.LayoutParams(-1, ui.dp(76))
                else LinearLayout.LayoutParams(0, -1, 1f))
        }
        if (window.useNavigationRail) {
            val rail = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = false
                addView(nav, FrameLayout.LayoutParams(-1, -2))
            }
            root.addView(rail, FrameLayout.LayoutParams(ui.dp(88), -1, Gravity.START).apply { topMargin = ui.dp(8) })
        } else root.addView(nav, FrameLayout.LayoutParams(-1, ui.dp(68), Gravity.BOTTOM).apply {
            leftMargin = ui.dp(8); rightMargin = ui.dp(8); bottomMargin = ui.dp(4)
        })
        val badge = ui.text("", 11, ui.palette.muted).apply { visibility = View.GONE }
        root.requestApplyInsets()
        return ConceptShellViews(root, body, scroller, backdrop, art, badge, navigation)
    }

    private fun iconAction(target: ConceptDestination): View = FrameLayout(context).apply {
        id = View.generateViewId(); tag = "nav:${target.key}"; contentDescription = target.label
        isFocusable = true; isClickable = true
        background = ui.ripple(ui.rounded(Color.TRANSPARENT, 24))
        addView(NavigationIconView(context, target.key, ui.palette.text), FrameLayout.LayoutParams(ui.dp(23), ui.dp(23), Gravity.CENTER))
        setOnClickListener { target.action() }
    }

    private fun navigation(target: ConceptDestination, selectedDestination: () -> String): View {
        val cell = LinearLayout(context).apply {
            id = View.generateViewId(); tag = "nav:${target.key}"
            contentDescription = target.label
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(2), ui.dp(6), ui.dp(2), ui.dp(6))
        }
        val icon = NavigationIconView(context, target.key, ui.palette.muted)
        val label = ui.text(if (target.key == "series") "Shows" else target.label, 11, ui.palette.muted, true).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        cell.addView(icon, LinearLayout.LayoutParams(ui.dp(23), ui.dp(23)))
        cell.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        fun paint(focused: Boolean) {
            val selected = selectedDestination() == target.key
            cell.isSelected = selected
            val color = if (selected || focused) ui.palette.accent else ui.palette.muted
            icon.tint = color; label.setTextColor(color)
            cell.background = ui.ripple(ui.rounded(if (selected || focused) ui.palette.navSelected else Color.TRANSPARENT, 18))
        }
        cell.setOnFocusChangeListener { _, focused -> paint(focused) }
        cell.setOnClickListener { target.action(); paint(cell.hasFocus()) }
        paint(false)
        return cell
    }
}

/** Mobile sizes use density/sp and respect the system font scale. */
internal class MobileUi(val context: Context, val theme: ThemeSpec) {
    val palette: ThemePalette = when (theme) {
        ThemeSpec.ESSENTIAL -> theme.palette.copy(accent = 0xFF6FE1B4.toInt(), primaryButton = 0xFF6FE1B4.toInt(),
            progress = 0xFF6FE1B4.toInt(), background = 0xFF11171C.toInt(), panel = 0xFF1D252B.toInt(), navSelected = 0xFF243B33.toInt())
        ThemeSpec.ATELIER -> theme.palette.copy(accent = 0xFF365A89.toInt(), primaryButton = 0xFF365A89.toInt(),
            progress = 0xFF365A89.toInt(), navSelected = 0xFFE0E7EF.toInt())
        ThemeSpec.HORIZON -> theme.palette.copy(background = 0xFF100C21.toInt(), panel = 0xFF1B1531.toInt(), navSelected = 0xFF302348.toInt())
        else -> theme.palette
    }
    fun dp(value: Int) = AdaptiveLayout.dp(context, value)
    fun text(value: String, size: Int, color: Int = palette.text, bold: Boolean = false) = TextView(context).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat()); setTextColor(color)
        typeface = Typeface.create(if (theme == ThemeSpec.PREMIERE || theme == ThemeSpec.ATELIER) "serif" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = true
    }
    fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1).coerceAtLeast(1), stroke)
    }
    fun ripple(content: android.graphics.drawable.Drawable) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(45, Color.red(palette.accent), Color.green(palette.accent), Color.blue(palette.accent))),
        content, rounded(Color.WHITE, 18))
    fun action(label: String, primary: Boolean, callback: () -> Unit) = text(label, 15,
        if (primary) palette.buttonText else palette.text, true).apply {
        gravity = Gravity.CENTER; minimumHeight = dp(48); maxLines = 2
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isFocusable = true; isClickable = true; contentDescription = label
        background = ripple(rounded(if (primary) palette.primaryButton else palette.panel, 14, if (primary) Color.TRANSPARENT else palette.border))
        setOnClickListener { callback() }
    }
}
