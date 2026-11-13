package dev.pov.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import dev.pov.tv.data.MediaItemData
import kotlin.math.roundToInt

internal object CameoDevice {
    fun isTelevision(context: Context) = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
        ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) &&
            context.resources.configuration.screenWidthDp >= 600)
}

internal enum class MobileCameoTheme(val title: String, val foundation: ThemeSpec) {
    GLASS("Glass", ThemeSpec.GLASS), CINEMA("Cinema", ThemeSpec.PREMIERE),
    SLATE("Slate", ThemeSpec.ESSENTIAL), PEARL("Pearl", ThemeSpec.ATELIER), AURORA("Aurora", ThemeSpec.HORIZON);
    val palette: ThemePalette get() = when (this) {
        GLASS -> foundation.palette.copy(background=0xFF091A2D.toInt(),panel=0xFF203B55.toInt(),accent=0xFF87D9FF.toInt(),primaryButton=0xFFF0F5FB.toInt(),buttonText=0xFF0C2949.toInt())
        CINEMA -> foundation.palette.copy(background=0xFF080807.toInt(),panel=0xFF1D1C19.toInt(),accent=0xFFE9BF7C.toInt(),primaryButton=0xFFE9BF7C.toInt(),buttonText=Color.BLACK)
        SLATE -> foundation.palette.copy(background=0xFF1B2025.toInt(),panel=0xFF2A3036.toInt(),accent=0xFF00CE83.toInt(),primaryButton=0xFF00CE83.toInt(),buttonText=0xFF071D14.toInt())
        PEARL -> foundation.palette.copy(background=0xFFFAF6F0.toInt(),panel=0xFFEDE8E1.toInt(),text=0xFF24292D.toInt(),muted=0xFF545B62.toInt(),accent=0xFF315F7D.toInt(),primaryButton=0xFF315F7D.toInt(),buttonText=Color.WHITE)
        AURORA -> foundation.palette.copy(background=0xFF101024.toInt(),panel=0xFF202139.toInt(),accent=0xFF0DE7DE.toInt(),primaryButton=0xFF0DE7DE.toInt(),buttonText=0xFF07232B.toInt())
    }
    companion object { fun from(theme: ThemeSpec) = entries.firstOrNull { it.foundation == theme } ?: GLASS }
}

/** Native live composition measured from the five mobile references; artwork and actions stay separate views. */
internal class MobileCameoRenderer(private val context: Context, private val theme: MobileCameoTheme) {
    private val p = theme.palette
    private val widthDp = context.resources.configuration.screenWidthDp.takeIf { it > 0 }
        ?: (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density).roundToInt()
    private val scale = (widthDp / 390f).coerceIn(.82f, 1.25f)
    private val inset = when (theme) { MobileCameoTheme.CINEMA -> 10; MobileCameoTheme.SLATE -> 11; MobileCameoTheme.AURORA -> 14; else -> 12 }
    private val immersive = theme in setOf(MobileCameoTheme.GLASS, MobileCameoTheme.CINEMA, MobileCameoTheme.AURORA)
    private val contentTop = if (theme == MobileCameoTheme.SLATE) 96 else 92
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun scaled(value: Int) = (value * scale).roundToInt()
    private fun shape(color: Int, radius: Int, border: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (border != Color.TRANSPARENT) setStroke(dp(1), border)
    }
    private fun gradient(colors: IntArray, radius: Int = 0, border: Int = Color.TRANSPARENT) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
        cornerRadius = dp(radius).toFloat(); if (border != Color.TRANSPARENT) setStroke(dp(1), border)
    }
    private fun font(weight: Int = 400, serif: Boolean = false): Typeface {
        val path = if (serif) "fonts/InstrumentSerif-Regular.ttf" else "fonts/Inter-Variable.ttf"
        return synchronized(fonts) { fonts.getOrPut("$path:$weight") {
            runCatching { Typeface.Builder(context.assets, path).setWeight(if (serif) 400 else weight).build() }
                .getOrElse { Typeface.create(if (serif) "serif" else "sans-serif", if (weight >= 600 && !serif) Typeface.BOLD else Typeface.NORMAL) }
        } }
    }
    private fun text(value: String, size: Int, color: Int = p.text, weight: Int = 400, serif: Boolean = false) = TextView(context).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat()); setTextColor(color)
        typeface = font(weight, serif); includeFontPadding = false
    }
    private fun action(value: String, primary: Boolean = false, run: () -> Unit): TextView = text(value, 15,
        if (primary) p.buttonText else p.text, if (primary) 650 else 500).apply {
        minHeight = dp(48); gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        setPadding(dp(10), 0, dp(10), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        val radius = when (theme) { MobileCameoTheme.CINEMA -> 7; MobileCameoTheme.SLATE -> 16; else -> 12 }
        val background = when {
            primary && theme == MobileCameoTheme.GLASS -> gradient(intArrayOf(0xFFF8FBFF.toInt(), 0xFFDDE9F5.toInt()), radius)
            primary && theme == MobileCameoTheme.CINEMA -> gradient(intArrayOf(0xFFF4CF92.toInt(), p.primaryButton), radius)
            primary && theme == MobileCameoTheme.AURORA -> GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF18EADD.toInt(), 0xFF00E8EA.toInt())).apply { cornerRadius = dp(radius).toFloat() }
            primary -> shape(p.primaryButton, radius)
            theme == MobileCameoTheme.GLASS -> gradient(intArrayOf(0x775C7999, 0x66445D7A), radius, 0x668EABC9)
            theme == MobileCameoTheme.PEARL -> shape(Color.TRANSPARENT, radius, 0xFF929394.toInt())
            else -> shape(Color.TRANSPARENT, radius, if (theme == MobileCameoTheme.CINEMA) 0xFFE9E0CF.toInt() else 0xFFD6D9E2.toInt())
        }
        // The visual reference uses a 36–40dp button; its native touch target is still 48dp.
        this.background = android.graphics.drawable.InsetDrawable(background, 0, dp(4), 0, dp(4))
        // Assigning a drawable background can replace View padding with its own empty padding.
        setPadding(dp(10), 0, dp(10), 0)
        if (primary && theme in setOf(MobileCameoTheme.GLASS, MobileCameoTheme.SLATE)) {
            setCompoundDrawablesRelative(MobilePlayGlyph(if (primary) p.buttonText else p.text).apply { setBounds(0, 0, dp(13), dp(15)) }, null, null, null)
            compoundDrawablePadding = dp(7)
        }
        setOnClickListener { run() }
    }

    fun shell(destinations: List<ConceptDestination>, selected: () -> String): ConceptShellViews {
        val root = FrameLayout(context).apply { setBackgroundColor(p.background); tag = "mobile:root" }
        if (theme == MobileCameoTheme.AURORA) root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF0D3543.toInt(), p.background, 0xFF24113F.toInt()))
        val backdrop = FrameLayout(context).apply {
            visibility = View.GONE; tag = "mobile:hero-backdrop"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "mobile:backdrop-art" }
        backdrop.addView(art, FrameLayout.LayoutParams(-1, -1))
        backdrop.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                Color.TRANSPARENT, 0x08000000, 0x55000000, p.background))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(backdrop, FrameLayout.LayoutParams(-1, dp(scaled(440))))
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false }
        scroller.addView(body)
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(contentTop); bottomMargin = dp(64) })
        header(root, destinations)
        categories(root, destinations, selected)
        val navigation = bottomNavigation(root, destinations, selected)
        installInsets(root)
        val badge = text("", 10).apply { visibility = View.GONE }
        return ConceptShellViews(root, body, scroller, backdrop, art, badge, navigation)
    }

    private fun header(root: FrameLayout, destinations: List<ConceptDestination>) {
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(5), 0); tag = "mobile:header"
            if (theme == MobileCameoTheme.GLASS) background = gradient(intArrayOf(0x33253F60, 0x335278A0), 14)
        }
        val brand = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; contentDescription = "Cameo" }
        brand.addView(MobileCameoGlyph(context, "brand", p.accent, theme), LinearLayout.LayoutParams(dp(32), dp(32)))
        brand.addView(text("CAMEO", if (theme == MobileCameoTheme.PEARL) 23 else 21, p.text,
            if (theme == MobileCameoTheme.CINEMA) 450 else 650, theme == MobileCameoTheme.PEARL).apply {
            letterSpacing = if (theme == MobileCameoTheme.CINEMA) .15f else .04f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(7) })
        header.addView(brand, LinearLayout.LayoutParams(if (theme == MobileCameoTheme.GLASS) -2 else 0, -1,
            if (theme == MobileCameoTheme.GLASS) 0f else 1f))
        val appearance = text(theme.title.uppercase(java.util.Locale.ROOT), if (theme == MobileCameoTheme.SLATE) 10 else 8,
            if (theme == MobileCameoTheme.CINEMA) p.accent else p.text, if (theme == MobileCameoTheme.SLATE) 650 else 450).apply {
            gravity = Gravity.CENTER; letterSpacing = if (theme == MobileCameoTheme.SLATE) 0f else .18f
            background = when (theme) {
                MobileCameoTheme.GLASS -> shape(0x22244365, 9, 0x555F87B4)
                MobileCameoTheme.SLATE -> shape(0xFF343A40.toInt(), 8)
                else -> android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            }
        }
        if (theme in setOf(MobileCameoTheme.CINEMA, MobileCameoTheme.AURORA)) header.addView(rule(0x668B86A1), LinearLayout.LayoutParams(dp(1), dp(18)).apply { marginEnd = dp(9) })
        header.addView(appearance, LinearLayout.LayoutParams(dp(if (theme == MobileCameoTheme.AURORA) 76 else 53), dp(if (theme == MobileCameoTheme.SLATE) 24 else 17)).apply {
            marginStart = dp(if (theme == MobileCameoTheme.GLASS) 12 else 0)
        })
        if (theme == MobileCameoTheme.GLASS) header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        if (theme in setOf(MobileCameoTheme.CINEMA, MobileCameoTheme.PEARL)) header.addView(rule(0x668B86A1), LinearLayout.LayoutParams(dp(1), dp(22)).apply { marginStart = dp(7) })
        val settings = FrameLayout(context).apply {
            isClickable = true; isFocusable = true; contentDescription = "Settings"; tag = "mobile:settings"
            setOnClickListener { destinations.firstOrNull { it.key == "settings" }?.action?.invoke() }
        }
        settings.addView(NavigationIconView(context, "settings", p.text), FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, FrameLayout.LayoutParams(-1, dp(48)))
    }

    private fun categories(root: FrameLayout, destinations: List<ConceptDestination>, selected: () -> String) {
        val categories = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; tag = "mobile:categories"
            setPadding(dp(if (theme == MobileCameoTheme.GLASS) 14 else 12), 0, dp(12), 0)
        }
        if (theme == MobileCameoTheme.AURORA) {
            categories.background = shape(0x660A1024, 30, 0xFF72769F.toInt())
            categories.setPadding(dp(3), 0, dp(3), 0)
        }
        listOf("For you" to "home", "Movies" to "movie", "Shows" to "series").forEachIndexed { index, (name, key) ->
            val active = selected() == key
            val label = text(name, 14, if (active && theme == MobileCameoTheme.SLATE) p.buttonText else if (active && theme == MobileCameoTheme.PEARL) p.accent else p.text,
                if (active || theme == MobileCameoTheme.SLATE) 650 else 400).apply {
                gravity = Gravity.CENTER; minHeight = dp(48); isClickable = true; isFocusable = true
                maxLines = 1; tag = "mobile:category:$key"; isSelected = active
                setOnClickListener {
                    destinations.firstOrNull { it.key == key }?.action?.invoke(); updateCategories(root, selected())
                    root.findViewWithTag<LinearLayout>("mobile:bottom-nav")?.let { nav ->
                        for (index in 0 until nav.childCount) nav.getChildAt(index).let { it.onFocusChangeListener?.onFocusChange(it, it.hasFocus()) }
                    }
                }
                background = categoryBackground(active)
            }
            val fixed = when (theme) { MobileCameoTheme.GLASS -> if (index == 0) 90 else 82; MobileCameoTheme.CINEMA -> if (index == 0) 92 else 104; else -> 0 }
            categories.addView(label, LinearLayout.LayoutParams(if (fixed == 0) 0 else dp(scaled(fixed)), dp(48), if (fixed == 0) 1f else 0f).apply {
                if (index < 2 && theme in setOf(MobileCameoTheme.GLASS, MobileCameoTheme.SLATE)) marginEnd = dp(8)
            })
        }
        if (theme in setOf(MobileCameoTheme.CINEMA, MobileCameoTheme.PEARL)) root.addView(rule(if (theme == MobileCameoTheme.PEARL) 0xFFC8C7C5.toInt() else 0xFF6D6252.toInt()),
            FrameLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(91) })
        root.addView(categories, FrameLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(if (theme == MobileCameoTheme.SLATE) 48 else 44)
            if (theme == MobileCameoTheme.AURORA) { leftMargin = dp(14); rightMargin = dp(14) }
        })
    }

    private fun categoryBackground(active: Boolean): android.graphics.drawable.Drawable = when (theme) {
        MobileCameoTheme.GLASS -> android.graphics.drawable.InsetDrawable(gradient(
            if (active) intArrayOf(0xDD7EB3D1.toInt(), 0xAA4B7FA6.toInt()) else intArrayOf(0x88547198.toInt(), 0x77425A7B), 20,
            if (active) 0xDDAADCF5.toInt() else 0x7788A6C7), 0, dp(9), 0, dp(9))
        MobileCameoTheme.SLATE -> android.graphics.drawable.InsetDrawable(shape(if (active) p.accent else p.panel, 24), 0, dp(7), 0, dp(7))
        else -> if (active) android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), android.graphics.drawable.ColorDrawable(p.accent))).apply {
            setLayerInset(1, dp(if (theme == MobileCameoTheme.CINEMA) 3 else 18), dp(45), dp(if (theme == MobileCameoTheme.CINEMA) 3 else 18), 0)
        } else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    }
    private fun updateCategories(root: FrameLayout, selected: String) {
        listOf("home", "movie", "series").forEach { key ->
            val label = root.findViewWithTag<TextView>("mobile:category:$key") ?: return@forEach
            val active = key == selected
            if (label.isSelected != active) {
                label.isSelected = active; label.background = categoryBackground(active)
                label.setTextColor(if (active && theme == MobileCameoTheme.SLATE) p.buttonText else if (active && theme == MobileCameoTheme.PEARL) p.accent else p.text)
                label.typeface = font(if (active || theme == MobileCameoTheme.SLATE) 650 else 400)
            }
        }
    }

    private fun bottomNavigation(root: FrameLayout, destinations: List<ConceptDestination>, selected: () -> String): Map<String, View> {
        val nav = LinearLayout(context).apply { gravity = Gravity.CENTER; tag = "mobile:bottom-nav"; setPadding(dp(4), dp(2), dp(4), dp(2)) }
        nav.background = when (theme) {
            MobileCameoTheme.GLASS -> gradient(intArrayOf(0xDD3B536B.toInt(), 0xED203C57.toInt()), 20, 0x886B89A5.toInt())
            MobileCameoTheme.AURORA -> shape(0xDD101126.toInt(), 30, 0xFF7A7DA7.toInt())
            MobileCameoTheme.PEARL -> shape(p.background, 0, 0xFFD5D0CC.toInt())
            MobileCameoTheme.SLATE -> shape(0xFF25292D.toInt(), 0)
            else -> shape(p.background, 0)
        }
        val navigation = linkedMapOf<String, View>()
        listOf("Home" to "home", "Browse" to "movie", "Anime" to "anime", "Library" to "library", "Search" to "search").forEach { (name, key) ->
            val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; tag = "nav:$key"; contentDescription = name }
            val glyph = MobileCameoGlyph(context, key, p.muted, theme)
            val caption = text(name, 11, p.muted, 500)
            cell.addView(glyph, LinearLayout.LayoutParams(dp(24), dp(24)))
            cell.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            fun paint() { val active = selected() == key || key == "movie" && selected() == "series"; val color = if (active) p.accent else p.text
                glyph.tint = color; caption.setTextColor(color); cell.isSelected = active; updateCategories(root, selected()) }
            cell.setOnFocusChangeListener { _, _ -> paint() }; cell.setOnClickListener {
                destinations.firstOrNull { it.key == key }?.action?.invoke()
                navigation.values.forEach { it.onFocusChangeListener?.onFocusChange(it, it.hasFocus()) }
            }; paint()
            nav.addView(cell, LinearLayout.LayoutParams(0, dp(58), 1f)); navigation[key] = cell
        }
        root.addView(nav, FrameLayout.LayoutParams(-1, dp(62), Gravity.BOTTOM).apply {
            if (theme == MobileCameoTheme.AURORA) { leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(3) }
        })
        return navigation
    }

    fun home(body: LinearLayout, data: ConceptHomeData, callbacks: ConceptHomeCallbacks, save: (MediaItemData) -> Unit) {
        val root = (body.parent as? ViewGroup)?.parent as? FrameLayout
        val backdrop = root?.findViewWithTag<FrameLayout>("mobile:hero-backdrop")
        val backdropArt = root?.findViewWithTag<ImageView>("mobile:backdrop-art")
        body.removeAllViews(); body.setPadding(dp(inset), dp(if (immersive) 0 else if (theme == MobileCameoTheme.PEARL) 6 else 12), dp(inset), dp(24))
        val featured = data.movies.firstOrNull() ?: data.shows.firstOrNull()
        backdrop?.visibility = if (immersive && featured != null) View.VISIBLE else View.GONE
        if (featured != null && immersive && backdrop != null && backdropArt != null) {
            val artHeight = if (theme == MobileCameoTheme.GLASS) 430 else 326
            (backdrop.layoutParams as FrameLayout.LayoutParams).apply {
                height = dp(scaled(artHeight)); topMargin = if (theme == MobileCameoTheme.GLASS) 0 else dp(contentTop)
            }.also { backdrop.layoutParams = it }
            callbacks.loadImage(backdropArt, featured.background.ifBlank { featured.poster })
        }
        if (featured != null) body.addView(feature(featured, callbacks, save), LinearLayout.LayoutParams(-1, -2))
        if (data.recent.isNotEmpty()) {
            section(body, "Continue Watching", first = true)
            val row = row(body); val width = cardWidth()
            data.recent.take(12).forEach { saved ->
                val item = (data.movies + data.shows).firstOrNull { it.imdbId == saved.identity?.imdbId && it.type == saved.identity?.type }
                    ?: MediaItemData(saved.identity?.imdbId.orEmpty(), saved.identity?.type ?: "movie", saved.title,
                        poster = saved.identity?.imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }?.let { "https://images.metahub.space/poster/medium/$it/img" }.orEmpty())
                val progress = if (saved.duration > 0) saved.position.toDouble() / saved.duration else null
                val card = card(item, callbacks, false, progress, if (theme == MobileCameoTheme.PEARL) "${time(saved.position)} / ${time(saved.duration)}" else null) { callbacks.resume(saved) }
                callbacks.bindContinue(card, saved)
                row.addView(card, LinearLayout.LayoutParams(dp(width), dp(scaled(if (theme == MobileCameoTheme.CINEMA) 114 else 102))).apply { marginEnd = dp(8) })
            }
        }
        if (data.movies.isNotEmpty()) {
            section(body, "Discover")
            posterRow(body, data.movies, callbacks)
        }
        if (data.upNext.isNotEmpty()) {
            section(body, "Up Next")
            data.upNext.take(6).forEach { episode -> body.addView(action("${episode.title} · S${episode.season} E${episode.episode}") { callbacks.episode(episode) },
                LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) }) }
        }
        if (data.shows.isNotEmpty()) { section(body, "Popular Shows"); posterRow(body, data.shows, callbacks) }
        body.addView(text("Standalone app made by Mark Mateo", 11, p.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(12)) })
    }

    private fun feature(item: MediaItemData, callbacks: ConceptHomeCallbacks, save: (MediaItemData) -> Unit): View {
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "mobile:hero:copy" }
        val title = text(item.title.uppercase(java.util.Locale.ROOT), when (theme) { MobileCameoTheme.CINEMA -> 42; MobileCameoTheme.PEARL -> 30; MobileCameoTheme.GLASS -> 28; else -> 24 }, p.text,
            if (theme == MobileCameoTheme.PEARL || theme == MobileCameoTheme.CINEMA) 400 else 700, theme == MobileCameoTheme.PEARL).apply {
            tag = "mobile:hero:title"; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            if (theme == MobileCameoTheme.CINEMA) { typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); textScaleX = .76f; letterSpacing = .025f }
            if (theme == MobileCameoTheme.GLASS) setLineSpacing(-dp(3).toFloat(), 1f)
        }
        copy.addView(title, LinearLayout.LayoutParams(if (theme == MobileCameoTheme.GLASS) dp(scaled(240)) else -1, -2))
        copy.addView(text(listOf(item.year, item.genres.firstOrNull().orEmpty(), item.runtime).filter(String::isNotBlank).joinToString(" • "), 13, p.muted).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; tag = "mobile:hero:metadata"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val controls = LinearLayout(context).apply { tag = "mobile:hero:controls" }
        controls.addView(action("Explore", true) { callbacks.details(item) }.apply { tag = "mobile:hero:explore" }, LinearLayout.LayoutParams(0, dp(48), if (theme == MobileCameoTheme.GLASS) 1.2f else 1f).apply { marginEnd = dp(10) })
        controls.addView(action("+ Library") { save(item) }.apply { tag = "mobile:hero:save" }, LinearLayout.LayoutParams(0, dp(48), 1f))
        copy.addView(controls, LinearLayout.LayoutParams(if (theme == MobileCameoTheme.GLASS) dp(scaled(252)) else -1, -2).apply { topMargin = dp(6) })
        if (immersive) {
            val frame = FrameLayout(context).apply { tag = "mobile:hero:overlay"; minimumHeight = dp(scaled(if (theme == MobileCameoTheme.GLASS) 255 else 286)) }
            frame.addView(copy, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(3); rightMargin = dp(3) })
            return frame
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; tag = "mobile:hero:framed"
            if (theme == MobileCameoTheme.SLATE) { background = shape(p.panel, 11); clipToOutline = true }
        }
        val artwork = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = item.title; tag = "mobile:hero:art"
            background = shape(p.panel, 10); clipToOutline = true
        }
        callbacks.loadImage(artwork, item.background.ifBlank { item.poster })
        panel.addView(artwork, LinearLayout.LayoutParams(-1, dp(scaled(if (theme == MobileCameoTheme.PEARL) 184 else 147))))
        copy.setPadding(dp(if (theme == MobileCameoTheme.PEARL) 4 else 13), dp(8), dp(if (theme == MobileCameoTheme.PEARL) 2 else 13), dp(5))
        panel.addView(copy, LinearLayout.LayoutParams(-1, -2))
        return panel
    }

    private fun cardWidth(): Int { val columns = if (widthDp >= 600) 3 else 2; return (widthDp - inset * 2 - 8 * (columns - 1)) / columns }
    private fun posterRow(body: LinearLayout, items: List<MediaItemData>, callbacks: ConceptHomeCallbacks) {
        val row = row(body); val height = when (theme) { MobileCameoTheme.GLASS -> 200; MobileCameoTheme.CINEMA -> 196; MobileCameoTheme.SLATE -> 204; MobileCameoTheme.PEARL -> 180; MobileCameoTheme.AURORA -> 194 }
        items.take(14).forEach { item -> row.addView(card(item, callbacks, true) { callbacks.details(item) },
            LinearLayout.LayoutParams(dp(cardWidth()), dp(scaled(height))).apply { marginEnd = dp(8) }) }
    }
    private fun section(body: LinearLayout, value: String, first: Boolean = false) {
        val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "mobile:section:$value" }
        if (theme == MobileCameoTheme.PEARL) group.addView(rule(0xFFC8C7C5.toInt()), LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(6) })
        group.addView(text(value, if (theme == MobileCameoTheme.PEARL) 24 else 19, p.text,
            if (theme == MobileCameoTheme.PEARL) 400 else 650, theme == MobileCameoTheme.PEARL))
        body.addView(group, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(if (first && theme == MobileCameoTheme.GLASS) 14 else if (theme == MobileCameoTheme.PEARL) 12 else 18)
            bottomMargin = dp(if (theme == MobileCameoTheme.GLASS && first) 6 else 10)
        })
    }
    private fun row(body: LinearLayout): LinearLayout {
        val scroller = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; clipToPadding = true }
        val row = LinearLayout(context); scroller.addView(row); body.addView(scroller, LinearLayout.LayoutParams(-1, -2)); return row
    }
    private fun card(item: MediaItemData, callbacks: ConceptHomeCallbacks, poster: Boolean, progress: Double? = null,
        timing: String? = null, run: () -> Unit): View {
        val frame = FrameLayout(context).apply {
            background = shape(p.panel, if (theme == MobileCameoTheme.CINEMA) 6 else 10,
                if (theme == MobileCameoTheme.AURORA) 0xFF586B88.toInt() else Color.TRANSPARENT)
            clipToOutline = true; isClickable = true; isFocusable = true; contentDescription = item.title
            tag = if (poster) "mobile:poster:${item.type}:${item.imdbId}" else "mobile:resume:${item.type}:${item.imdbId}"
            setOnClickListener { run() }
            if (theme == MobileCameoTheme.PEARL) elevation = dp(2).toFloat()
        }
        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        callbacks.loadImage(art, (if (poster) item.poster else item.background).ifBlank { item.poster })
        frame.addView(art, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(context).apply { background = GradientDrawable(if (poster) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(if (poster && theme == MobileCameoTheme.PEARL) 0xCCFAF6F0.toInt() else if (poster) 0x55000000 else 0xCC000000.toInt(), Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
        val caption = text(item.title.uppercase(java.util.Locale.ROOT), if (poster) 16 else 13, if (poster && theme == MobileCameoTheme.PEARL) p.text else Color.WHITE,
            if (theme in setOf(MobileCameoTheme.CINEMA, MobileCameoTheme.PEARL)) 400 else if (poster) 450 else 650, theme == MobileCameoTheme.PEARL).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            if (poster) { gravity = Gravity.CENTER; letterSpacing = if (theme == MobileCameoTheme.GLASS) .13f else .04f }
            if (theme == MobileCameoTheme.CINEMA) { typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); if (poster) textScaleX = .85f }
        }
        frame.addView(caption, FrameLayout.LayoutParams(-1, -2, if (poster) Gravity.TOP else Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(8)
            if (poster) topMargin = dp(14) else bottomMargin = dp(if (timing != null) 26 else 17)
        })
        if (timing != null) frame.addView(text(timing, 10, Color.WHITE), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(10); bottomMargin = dp(14) })
        if (progress != null && progress.isFinite()) frame.addView(MobileResumeProgress(context, progress, p.accent), FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(7)
        })
        return frame
    }
    private fun time(milliseconds: Long): String { val minutes = (milliseconds.coerceAtLeast(0) / 60_000).toInt(); return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m" }
    private fun rule(color: Int) = View(context).apply { setBackgroundColor(color) }

    /** Insets apply only where this root really intersects system bars, avoiding double padding on API26. */
    private fun installInsets(root: FrameLayout) {
        MobileCameoInsetBinding(root)
    }
    private companion object { val fonts = mutableMapOf<String, Typeface>() }
}

/** An initial child dispatch may be consumed by DecorView; attachment supplies the full window data. */
internal class MobileCameoInsetBinding(
    private val root: View,
    private val currentWindow: () -> MobileCameoWindowInsets? = { MobileCameoInsets.windowMetrics(root.context) },
    private val fullWindowInsets: () -> WindowInsets? = { root.rootWindowInsets ?: root.rootView.rootWindowInsets },
) {
    private var dispatched: WindowInsets? = null
    private var lastDebugState: String? = null
    private var pendingPadding: android.graphics.Rect? = null
    private var paddingCommitPosted = false
    private val commitPadding = Runnable {
        paddingCommitPosted = false
        val desired = pendingPadding
        pendingPadding = null
        if (desired != null && root.isAttachedToWindow && !matchesPadding(desired)) {
            // View.layout clears FORCE_LAYOUT after layout listeners. Mutate padding in the next UI task.
            root.setPadding(desired.left, desired.top, desired.right, desired.bottom)
            root.requestLayout()
            root.invalidate()
        }
    }

    init {
        root.setOnApplyWindowInsetsListener { _, insets ->
            dispatched = insets
            refresh(); root.post { refresh() }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.requestApplyInsets()
                refresh()
                // The first traversal can populate rootWindowInsets after the attach callback.
                view.post { if (view.isAttachedToWindow) { view.requestApplyInsets(); refresh() } }
            }
            override fun onViewDetachedFromWindow(view: View) {
                view.removeCallbacks(commitPadding); paddingCommitPosted = false; pendingPadding = null
            }
        })
        if (root.isAttachedToWindow) { root.requestApplyInsets(); refresh() }
    }

    private fun refresh() {
        if (!root.isAttachedToWindow || root.width <= 0 || root.height <= 0) return
        val window = root.rootView
        if (window.width <= 0 || window.height <= 0) return
        val complete = fullWindowInsets()?.let(MobileCameoInsets::bars)
        val child = dispatched?.let(MobileCameoInsets::bars)
        // WindowManager supplies current, unconsumed data even if a rebuilt view root has a zero placeholder.
        val metrics = if (!MobileCameoInsets.hasBars(complete)) currentWindow() else null
        val useMetrics = metrics != null && MobileCameoInsets.hasBars(metrics.bars) && metrics.frame.width() > 0 && metrics.frame.height() > 0
        val bars = when {
            MobileCameoInsets.hasBars(complete) -> requireNotNull(complete)
            useMetrics -> requireNotNull(metrics).bars
            MobileCameoInsets.hasBars(child) -> requireNotNull(child)
            complete != null -> complete
            child != null -> child
            else -> return
        }
        val here = IntArray(2); val origin = IntArray(2)
        root.getLocationOnScreen(here); window.getLocationOnScreen(origin)
        val rootFrame = android.graphics.Rect(here[0], here[1], here[0] + root.width, here[1] + root.height)
        val windowFrame = if (useMetrics) requireNotNull(metrics).frame
            else android.graphics.Rect(origin[0], origin[1], origin[0] + window.width, origin[1] + window.height)
        val overlap = MobileCameoInsets.overlap(
            rootFrame, windowFrame, bars)
        pendingPadding = if (matchesPadding(overlap)) null else android.graphics.Rect(overlap)
        if (pendingPadding != null && !paddingCommitPosted) {
            paddingCommitPosted = true
            root.post(commitPadding)
        }
        if (BuildConfig.DEBUG) {
            fun bounds(tag: String) = root.findViewWithTag<View>(tag)?.let { android.graphics.Rect(it.left, it.top, it.right, it.bottom) }
            val actual = android.graphics.Rect(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
            val state = "source=${if (useMetrics) "window-metrics" else if (MobileCameoInsets.hasBars(complete)) "view-root" else "child"} root=$rootFrame window=$windowFrame bars=$bars padding=$overlap actual=$actual header=${bounds("mobile:header")} nav=${bounds("mobile:bottom-nav") }"
            if (lastDebugState != state) { android.util.Log.d("CameoInsets", state); lastDebugState = state }
        }
    }

    private fun matchesPadding(value: android.graphics.Rect) = root.paddingLeft == value.left && root.paddingTop == value.top &&
        root.paddingRight == value.right && root.paddingBottom == value.bottom
}

internal data class MobileCameoWindowInsets(val frame: android.graphics.Rect, val bars: android.graphics.Rect)

internal object MobileCameoInsets {
    fun hasBars(insets: android.graphics.Rect?) = insets != null && (insets.left > 0 || insets.top > 0 || insets.right > 0 || insets.bottom > 0)

    fun windowMetrics(context: Context): MobileCameoWindowInsets? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val metrics = requireNotNull(context.getSystemService(WindowManager::class.java)).currentWindowMetrics
            MobileCameoWindowInsets(android.graphics.Rect(metrics.bounds), bars(metrics.windowInsets))
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    fun bars(insets: WindowInsets): android.graphics.Rect {
        if (Build.VERSION.SDK_INT >= 30) return insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            .let { android.graphics.Rect(it.left, it.top, it.right, it.bottom) }
        val bars = if (insets.hasStableInsets()) android.graphics.Rect(insets.stableInsetLeft, insets.stableInsetTop, insets.stableInsetRight, insets.stableInsetBottom)
            else android.graphics.Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.let { cutout ->
            bars.set(maxOf(bars.left, cutout.safeInsetLeft), maxOf(bars.top, cutout.safeInsetTop), maxOf(bars.right, cutout.safeInsetRight), maxOf(bars.bottom, cutout.safeInsetBottom))
        }
        return bars
    }
    fun overlap(root: android.graphics.Rect, window: android.graphics.Rect, bars: android.graphics.Rect) = android.graphics.Rect(
        (window.left + bars.left - root.left).coerceAtLeast(0),
        (window.top + bars.top - root.top).coerceAtLeast(0),
        (root.right - (window.right - bars.right)).coerceAtLeast(0),
        (root.bottom - (window.bottom - bars.bottom)).coerceAtLeast(0))
}

private class MobileResumeProgress(context: Context, private val value: Double, private val color: Int) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: android.graphics.Canvas) {
        paint.color = 0x888C99A7.toInt(); canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        paint.color = color; canvas.drawRoundRect(0f, 0f, (width * value.coerceIn(0.0, 1.0)).toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
    }
}

private class MobilePlayGlyph(private val tint: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    override fun draw(canvas: android.graphics.Canvas) {
        val path = android.graphics.Path().apply {
            moveTo(bounds.left.toFloat(), bounds.top.toFloat()); lineTo(bounds.right.toFloat(), bounds.exactCenterY()); lineTo(bounds.left.toFloat(), bounds.bottom.toFloat()); close()
        }
        canvas.drawPath(path, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Native geometry for the references' brand, clapperboard, anime, bookmark and search symbols. */
private class MobileCameoGlyph(context: Context, private val key: String, color: Int, private val theme: MobileCameoTheme) : View(context) {
    var tint = color; set(value) { field = value; invalidate() }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.7f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val size = minOf(width, height).toFloat(); if (size <= 0) return; val save = canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2); canvas.scale(size / 24, size / 24)
        paint.shader = null; paint.color = tint; paint.style = android.graphics.Paint.Style.STROKE
        val path = android.graphics.Path()
        when (key) {
            "brand" -> {
                paint.strokeWidth = if (theme == MobileCameoTheme.PEARL || theme == MobileCameoTheme.CINEMA) 1.8f else 5.2f
                paint.strokeCap = android.graphics.Paint.Cap.BUTT
                if (theme in setOf(MobileCameoTheme.GLASS, MobileCameoTheme.AURORA)) paint.shader = android.graphics.SweepGradient(12f, 12f,
                    if (theme == MobileCameoTheme.GLASS) intArrayOf(0xFF79C8FF.toInt(), 0xFFD7F5FF.toInt(), 0xFF4AA7E4.toInt(), 0xFF79C8FF.toInt())
                    else intArrayOf(0xFF6542E9.toInt(), 0xFF9859F5.toInt(), 0xFF09DCE9.toInt(), 0xFF6542E9.toInt()), null)
                canvas.drawArc(android.graphics.RectF(4f, 4f, 20f, 20f), 37f, 286f, false, paint)
                paint.shader = null; paint.strokeCap = android.graphics.Paint.Cap.ROUND; paint.strokeWidth = 1.7f
            }
            "home" -> {
                path.moveTo(3f, 10f); path.lineTo(12f, 3f); path.lineTo(21f, 10f); path.lineTo(21f, 21f); path.lineTo(15f, 21f); path.lineTo(15f, 14f); path.lineTo(9f, 14f); path.lineTo(9f, 21f); path.lineTo(3f, 21f); path.close()
                if (theme != MobileCameoTheme.SLATE) paint.style = android.graphics.Paint.Style.FILL; canvas.drawPath(path, paint)
            }
            "movie" -> {
                canvas.drawRoundRect(3f, 8f, 21f, 21f, 1.5f, 1.5f, paint)
                path.moveTo(3f, 8f); path.lineTo(2f, 3f); path.lineTo(20f, 1f); path.lineTo(21f, 6f); path.close(); canvas.drawPath(path, paint)
                canvas.drawLine(7f, 3f, 10f, 7f, paint); canvas.drawLine(14f, 2f, 17f, 6f, paint)
                if (theme != MobileCameoTheme.SLATE) { path.reset(); path.moveTo(10f, 12f); path.lineTo(16f, 15f); path.lineTo(10f, 18f); path.close(); canvas.drawPath(path, paint) }
            }
            "anime" -> if (theme == MobileCameoTheme.AURORA) {
                canvas.drawLine(2f, 6f, 22f, 6f, paint); canvas.drawLine(5f, 10f, 19f, 10f, paint)
                canvas.drawLine(7f, 6f, 6f, 22f, paint); canvas.drawLine(17f, 6f, 18f, 22f, paint)
            } else {
                path.moveTo(3f, 10f); path.lineTo(3f, 3f); path.lineTo(8f, 7f); path.quadTo(12f, 5f, 16f, 7f); path.lineTo(21f, 3f); path.lineTo(21f, 10f)
                path.cubicTo(25f, 24f, -1f, 24f, 3f, 10f); canvas.drawPath(path, paint)
                canvas.drawCircle(8f, 13f, .8f, paint); canvas.drawCircle(16f, 13f, .8f, paint)
            }
            "library" -> if (theme in setOf(MobileCameoTheme.GLASS, MobileCameoTheme.AURORA)) {
                path.moveTo(5f, 3f); path.lineTo(19f, 3f); path.lineTo(19f, 22f); path.lineTo(12f, 17f); path.lineTo(5f, 22f); path.close(); canvas.drawPath(path, paint)
            } else {
                canvas.drawRoundRect(2f, 3f, 6f, 22f, .5f, .5f, paint); canvas.drawRoundRect(8f, 3f, 12f, 22f, .5f, .5f, paint)
                path.moveTo(14f, 4f); path.lineTo(18f, 3f); path.lineTo(23f, 21f); path.lineTo(19f, 22f); path.close(); canvas.drawPath(path, paint)
            }
            "search" -> { canvas.drawCircle(10f, 10f, 7f, paint); canvas.drawLine(15f, 15f, 22f, 22f, paint) }
        }
        canvas.restoreToCount(save)
    }
}
