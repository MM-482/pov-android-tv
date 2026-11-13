package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pov.tv.data.MediaItemData
import java.util.Locale
import kotlin.math.roundToInt

/** Five live native TV compositions. Preview PNGs are used only by the theme picker. */
internal class CameoTvRenderer(private val context: Context, private val theme: ThemeSpec) {
    init { require(theme.isCameoTv) }
    private val p = theme.palette
    private val scale = context.resources.displayMetrics.widthPixels / 960f
    private val glass get() = theme == ThemeSpec.CAMEO_GLASS
    private val cinema get() = theme == ThemeSpec.CAMEO_CINEMA
    private val slate get() = theme == ThemeSpec.CAMEO_SLATE
    private val pearl get() = theme == ThemeSpec.CAMEO_PEARL
    private val aurora get() = theme == ThemeSpec.CAMEO_AURORA
    private val rail get() = glass || slate
    private fun px(value: Int) = (value * scale).roundToInt()
    private fun font(weight: Int = 400, serif: Boolean = false): Typeface {
        val path = if (serif) "fonts/InstrumentSerif-Regular.ttf" else "fonts/Inter-Variable.ttf"
        return synchronized(fonts) { fonts.getOrPut("$path:$weight") {
            runCatching { Typeface.Builder(context.assets, path).setWeight(if (serif) 400 else weight).build() }
                .getOrElse { Typeface.create(if (serif) "serif" else "sans-serif", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL) }
        } }
    }
    private fun text(value: String, size: Int, color: Int = p.text, weight: Int = 400, serif: Boolean = false) = TextView(context).apply {
        text = value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
        typeface = font(weight, serif); includeFontPadding = false; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private fun shape(color: Int, radius: Int = 0, border: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radius).toFloat()
        if (border != Color.TRANSPARENT) setStroke(px(1).coerceAtLeast(1), border)
    }
    private fun fade(colors: IntArray, direction: GradientDrawable.Orientation = GradientDrawable.Orientation.TOP_BOTTOM, radius: Int = 0) = GradientDrawable(direction, colors).apply { cornerRadius = px(radius).toFloat() }
    private fun place(parent: FrameLayout, view: View, x: Int, y: Int, width: Int, height: Int) {
        parent.addView(view, FrameLayout.LayoutParams(if (width < 0) width else px(width), if (height < 0) height else px(height)).apply {
            leftMargin = px(x); topMargin = px(y)
        })
    }
    private fun focus(view: View, radius: Int = 8) {
        view.isFocusable = true; view.isFocusableInTouchMode = true; view.isClickable = true; view.id = View.generateViewId()
        view.setOnFocusChangeListener { target, active ->
            target.foreground = if (active) FocusRingDrawable(p.accent, px(radius).toFloat(), true, scale) else null
            target.elevation = if (active) px(5).toFloat() else 0f
        }
    }

    fun shell(destinations: List<ConceptDestination>, selected: () -> String): ConceptShellViews {
        val root = FrameLayout(context).apply { tag = "cameo-tv:root:${theme.id}"; setBackgroundColor(p.background); clipChildren = false }
        if (aurora) root.background = fade(intArrayOf(0xFF07313D.toInt(), p.background, 0xFF281044.toInt()), GradientDrawable.Orientation.TL_BR)
        val backdrop = FrameLayout(context).apply { visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "cameo-tv:backdrop-art" }
        backdrop.addView(art, FrameLayout.LayoutParams(-1, -1))
        backdrop.addView(View(context).apply { background = fade(intArrayOf(if (glass) 0x5509192D else p.background, Color.TRANSPARENT), GradientDrawable.Orientation.LEFT_RIGHT) }, FrameLayout.LayoutParams(-1, -1))
        backdrop.addView(View(context).apply { background = fade(intArrayOf(Color.TRANSPARENT, 0x22000000, p.background)) }, FrameLayout.LayoutParams(-1, -1))
        root.addView(backdrop, FrameLayout.LayoutParams(-1, px(if (glass) 540 else 278)))
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val scroller = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; isFocusable = false; clipChildren = false; clipToPadding = false }
        scroller.addView(body); root.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        ConceptShellRenderer.applyContentInsets(scroller, theme, false)
        scroller.setOnScrollChangeListener { _, _, y, _, _ -> backdrop.translationY = -y.toFloat() }
        if (rail) {
            val panel = View(context).apply { background = if (glass) fade(intArrayOf(0xCC283D59.toInt(), 0xDD10263B.toInt()), GradientDrawable.Orientation.TL_BR, 16) else shape(0xFF181D22.toInt()) }
            if (glass) { panel.background = shape(0xC5284159.toInt(), 16, 0xFF58718B.toInt()); place(root, panel, 11, 15, 142, 510) }
            else place(root, panel, 0, 0, 150, 540)
        }
        if (aurora) place(root, View(context).apply { background = shape(0x99101024.toInt(), 18, 0xFF53516F.toInt()) }, 253, 11, 472, 30)
        if (pearl) place(root, View(context).apply { setBackgroundColor(0xFFC5C3C0.toInt()) }, 23, 40, 914, 1)
        brand(root)
        val navigation = linkedMapOf<String, View>()
        val targets = destinations.filter { it.key !in setOf("trakt", "nowplaying") }
        targets.forEachIndexed { index, target ->
            val cell = FrameLayout(context).apply { tag = "nav:${target.key}"; contentDescription = target.label }
            focus(cell, if (glass) 12 else 6)
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(if (rail) 15 else 4), 0, px(3), 0) }
            val icon = NavigationIconView(context, target.key, p.text)
            if (rail || !aurora && target.key in setOf("search", "settings")) line.addView(icon, LinearLayout.LayoutParams(px(if (rail) 19 else 15), px(if (rail) 19 else 15)).apply { marginEnd = px(if (rail) 13 else 8) })
            if (!rail) line.gravity = Gravity.CENTER
            val caption = text(if (target.key == "series") "Shows" else target.label, if (rail) 14 else 12, p.text, 450, pearl)
            caption.maxLines = 1; line.addView(caption, LinearLayout.LayoutParams(-2, -2)); cell.addView(line, FrameLayout.LayoutParams(-1, -1))
            val marker = View(context).apply { background = shape(p.accent, 2) }
            if (slate) place(cell, marker, 0, 10, 3, 23) else if (!rail) cell.addView(marker, FrameLayout.LayoutParams(-1, px(2), Gravity.BOTTOM).apply { leftMargin = px(10); rightMargin = px(10) })
            fun paint(focused: Boolean) {
                val active = selected() == target.key; caption.typeface = font(if (active) 650 else 450, pearl)
                caption.setTextColor(if (active && !glass && !slate && !pearl) p.accent else p.text)
                marker.visibility = if (active) View.VISIBLE else View.INVISIBLE
                cell.background = when {
                    glass && active -> shape(0xB977ACD0.toInt(), 12, 0xFFA6D4EF.toInt())
                    focused -> shape(if (pearl) 0xFFE6DDD2.toInt() else 0x442B5672, 6)
                    else -> shape(Color.TRANSPARENT)
                }
                cell.foreground = if (focused) FocusRingDrawable(p.accent, px(if (glass) 12 else 6).toFloat(), true, scale) else null
            }
            cell.setOnFocusChangeListener { _, active -> paint(active) }
            cell.setOnClickListener { target.action(); navigation.values.forEach { it.onFocusChangeListener?.onFocusChange(it, it.hasFocus()) } }
            paint(false)
            when {
                glass -> place(root, cell, 18, 87 + index * 43, 128, 37)
                slate -> place(root, cell, 16, 79 + index * 44, 125, 43)
                cinema -> place(root, cell, listOf(195, 265, 342, 420, 495, 581, 679).getOrElse(index) { 679 }, 8, if (index >= 5) 89 else 67, 29)
                pearl -> place(root, cell, 244 + index * 78, 4, 74, 29)
                else -> place(root, cell, 263 + index * 65, 13, 66, 27)
            }
            navigation[target.key] = cell
        }
        targets.forEachIndexed { index, target -> navigation[target.key]?.let { cell ->
            val before = targets.getOrNull(index - 1)?.let { navigation[it.key] } ?: cell
            val after = targets.getOrNull(index + 1)?.let { navigation[it.key] } ?: cell
            if (rail) { cell.nextFocusUpId = before.id; cell.nextFocusDownId = after.id }
            else { cell.nextFocusLeftId = before.id; cell.nextFocusRightId = after.id }
        } }
        val badge = text("", 10, p.muted).apply { visibility = View.GONE }
        place(root, badge, 680, 525, 260, 12)
        val name = text("Made by Mark Mateo", if (glass) 9 else 10, p.muted, serif = pearl).apply { gravity = if (glass) Gravity.CENTER else Gravity.END }
        if (glass) {
            place(root, View(context).apply { setBackgroundColor(0xFF63798E.toInt()) }, 30, 486, 105, 1)
            place(root, name, 25, 495, 120, 15)
        } else place(root, name, if (pearl) 385 else 650, 519, if (pearl) 190 else 292, 15)
        return ConceptShellViews(root, body, scroller, backdrop, art, badge, navigation)
    }

    private fun brand(root: FrameLayout) {
        val group = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; contentDescription = "Cameo" }
        val mark = text("C", if (pearl || cinema) 36 else 33, p.accent, 700, pearl || cinema)
        if (aurora) mark.setTextColor(0xFF12E6EC.toInt())
        group.addView(mark, LinearLayout.LayoutParams(px(32), px(40)))
        val name = text("CAMEO", if (slate) 20 else if (pearl) 23 else 16, p.text, if (pearl) 400 else 650, pearl).apply { letterSpacing = if (slate || pearl) 0f else .18f }
        group.addView(name, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(if (pearl) 10 else 6) })
        when { glass -> place(root, group, 28, 32, 115, 41); slate -> place(root, group, 23, 23, 118, 40); pearl -> place(root, group, 46, 0, 170, 39); else -> place(root, group, 28, 4, 142, 40) }
        val label = text("${listOf(ThemeSpec.CAMEO_GLASS, ThemeSpec.CAMEO_CINEMA, ThemeSpec.CAMEO_SLATE, ThemeSpec.CAMEO_PEARL, ThemeSpec.CAMEO_AURORA).indexOf(theme) + 1}".padStart(2, '0') + " / " + theme.displayName.removePrefix("Cameo ").uppercase(Locale.ROOT), 9, if (pearl) p.accent else p.text, 500).apply {
            gravity = Gravity.CENTER; background = shape(if (pearl) 0xFFD5DEE6.toInt() else Color.TRANSPARENT, if (cinema) 4 else 10, if (pearl) Color.TRANSPARENT else 0xFF71839A.toInt())
        }
        place(root, label, if (glass) 872 else 854, if (glass) 14 else 9, if (glass) 74 else 89, 19)
    }

    fun home(body: LinearLayout, heroBackdrop: FrameLayout, backdropArt: ImageView, data: ConceptHomeData, callbacks: ConceptHomeCallbacks, save: (MediaItemData) -> Unit) {
        (body.parent as? ScrollView)?.let { ConceptShellRenderer.applyContentInsets(it, theme, true) }
        body.removeAllViews(); body.setPadding(0, 0, 0, body.paddingBottom)
        val canvas = FrameLayout(context).apply { tag = "cameo-tv:home:${theme.id}"; clipChildren = false }
        body.addView(canvas, LinearLayout.LayoutParams(px(960), px(540)))
        val featured = data.movies.firstOrNull() ?: data.shows.firstOrNull()
        heroBackdrop.visibility = if (featured != null && (glass || cinema)) View.VISIBLE else View.GONE
        if (featured != null) {
            if (glass || cinema) callbacks.loadImage(backdropArt, featured.background.ifBlank { featured.poster })
            feature(canvas, featured, callbacks, save)
        } else place(canvas, text("Find your next story", 30, weight = 650), if (rail) 184 else 45, 110, 500, 70)
        val left = if (rail) 174 else if (cinema) 34 else 25
        val right = if (glass) 16 else if (slate) 14 else if (cinema) 32 else 25
        val width = 960 - left - right
        val headingY = when { glass -> 245; cinema -> 256; slate -> 236; pearl -> 246; else -> 248 }
        val cardY = when { glass -> 270; cinema -> 278; slate -> 258; pearl -> 269; else -> 276 }
        val recent = data.recent.filter { it.duration > 0 && it.position >= 0 && it.position < it.duration }
        if (recent.isNotEmpty()) {
            heading(canvas, "Continue Watching", left, headingY, width)
            val items = recent.take(12)
            horizontal(canvas, left, cardY, width, if (slate) 97 else if (pearl) 93 else 88, 3, 12, items.map { saved ->
                val item = (data.movies + data.shows).firstOrNull { it.imdbId == saved.identity?.imdbId && it.type == saved.identity?.type }
                    ?: MediaItemData(saved.identity?.imdbId.orEmpty(), saved.identity?.type ?: "movie", saved.title,
                        background = saved.identity?.imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }?.let { "https://images.metahub.space/background/medium/$it/img" }.orEmpty())
                card(item, false, saved, callbacks) { callbacks.resume(saved) }.also { callbacks.bindContinue(it, saved) }
            })
        }
        val discoveryY = if (recent.isEmpty()) headingY else when { glass -> 377; cinema -> 374; slate -> 367; pearl -> 377; else -> 374 }
        heading(canvas, "Discover", left, discoveryY, width)
        val posterY = discoveryY + 24
        horizontal(canvas, left, posterY, width, if (glass || slate) 119 else 108, 5, if (glass) 7 else 9,
            (data.movies.ifEmpty { data.shows }).take(20).map { item -> card(item, true, null, callbacks) { callbacks.details(item) } })
        // Keep the approved first viewport intact; account-specific next episodes continue below it.
        if (data.upNext.isNotEmpty()) {
            val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(px(left), px(20), px(right), px(24)); tag = "cameo-tv:up-next" }
            group.addView(text("Up Next", 18, weight = 650, serif = pearl), LinearLayout.LayoutParams(-1, px(30)))
            data.upNext.take(12).forEach { episode ->
                group.addView(action("${episode.title}  ·  Season ${episode.season}, episode ${episode.episode}", false) { callbacks.episode(episode) }.apply {
                    tag = "cameo-tv:next:${episode.imdbId}:${episode.season}:${episode.episode}"; contentDescription = "${episode.title}, season ${episode.season}, episode ${episode.episode}"
                }, LinearLayout.LayoutParams(-1, px(42)).apply { bottomMargin = px(8) })
            }
            body.addView(group, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun feature(canvas: FrameLayout, item: MediaItemData, callbacks: ConceptHomeCallbacks, save: (MediaItemData) -> Unit) {
        if (slate || pearl || aurora) {
            val frame = FrameLayout(context).apply { tag = "cameo-tv:hero-art"; background = shape(p.panel, if (aurora) 19 else 12); clipToOutline = true }
            val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            callbacks.loadImage(art, item.background.ifBlank { item.poster }); frame.addView(art, FrameLayout.LayoutParams(-1, -1))
            if (!pearl) frame.addView(View(context).apply { background = fade(intArrayOf(0xDD131C23.toInt(), 0x44131C23, Color.TRANSPARENT), GradientDrawable.Orientation.LEFT_RIGHT) }, FrameLayout.LayoutParams(-1, -1))
            when { slate -> place(canvas, frame, 168, 36, 779, 187); pearl -> place(canvas, frame, 317, 52, 622, 181); else -> place(canvas, frame, 25, 54, 912, 184) }
        }
        val x = when { glass -> 184; cinema -> 47; slate -> 194; pearl -> 44; else -> 60 }
        val y = when { glass -> 64; cinema -> 61; slate -> 53; pearl -> 66; else -> 76 }
        val width = when { glass -> 330; cinema -> 355; pearl -> 260; else -> 390 }
        val title = text(item.title.uppercase(Locale.ROOT), if (pearl) 43 else if (cinema) 43 else 37, weight = if (slate || aurora) 700 else 500, serif = pearl).apply {
            tag = "cameo-tv:hero-title"; gravity = Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(px(22).coerceAtLeast(1), px(if (pearl || cinema) 43 else 37).coerceAtLeast(2), px(1).coerceAtLeast(1), TypedValue.COMPLEX_UNIT_PX)
            if (glass) letterSpacing = .05f
            if (cinema) { typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); textScaleX = .72f; letterSpacing = .12f }
        }
        place(canvas, title, x, y, width, if (cinema) 95 else 77)
        val metaY = when { glass -> 146; cinema -> 166; slate -> 139; pearl -> 147; else -> 150 }
        place(canvas, text(listOf(item.year, item.genres.firstOrNull().orEmpty(), item.runtime).filter(String::isNotBlank).joinToString("  •  "), 13, p.muted, serif = pearl).apply { maxLines = 1 }, x, metaY, width, 20)
        val buttonY = when { glass -> 178; cinema -> 192; slate -> 166; pearl -> 181; else -> 180 }
        val buttonWidth = if (glass) 133 else if (pearl) 126 else 117
        place(canvas, action("Explore", true) { callbacks.details(item) }.apply { tag = "cameo-tv:hero:explore" }, x, buttonY, buttonWidth, 37)
        place(canvas, action("+ Library", false) { save(item) }.apply { tag = "cameo-tv:hero:save" }, x + buttonWidth + 12, buttonY, if (glass) 125 else 105, 35)
    }

    private fun action(value: String, primary: Boolean, run: () -> Unit) = text(value, 15, if (primary) p.buttonText else p.text, if (primary) 650 else 450, pearl).apply {
        gravity = Gravity.CENTER; maxLines = 1; background = shape(if (primary) p.primaryButton else if (glass) 0x99527596.toInt() else Color.TRANSPARENT, if (cinema) 5 else 9,
            if (primary) Color.TRANSPARENT else if (pearl) 0xFF838889.toInt() else 0xFFE1E5EC.toInt())
        focus(this, if (cinema) 5 else 9); setOnClickListener { run() }
    }
    private fun heading(canvas: FrameLayout, title: String, x: Int, y: Int, width: Int) {
        val label = text(if (cinema) title.uppercase(Locale.ROOT) else title, if (pearl) 18 else if (cinema) 13 else 17, weight = if (pearl || cinema) 450 else 650, serif = pearl).apply {
            tag = "cameo-tv:section:$title"; maxLines = 1; if (cinema) letterSpacing = .18f
        }
        place(canvas, label, x, y, width, 24)
        if (pearl || cinema) {
            val offset = if (title == "Discover") if (pearl) 78 else 94 else if (pearl) 164 else 192
            place(canvas, View(context).apply { setBackgroundColor(if (pearl) 0xFFAAA9A5.toInt() else 0xFF8B7D5B.toInt()) }, x + offset, y + 12, (width - offset).coerceAtLeast(1), 1)
        }
    }
    private fun horizontal(canvas: FrameLayout, x: Int, y: Int, width: Int, height: Int, columns: Int, gap: Int, cards: List<View>) {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; clipToPadding = false
            addOnLayoutChangeListener { target, left, top, right, bottom, _, _, _, _ -> target.clipBounds = android.graphics.Rect(0, 0, right - left, bottom - top) }
        }
        val row = LinearLayout(context).apply { clipChildren = false; setPadding(px(2), px(2), px(2), px(2)) }
        scroll.addView(row)
        val cardWidth = (width - gap * (columns - 1)) / columns
        cards.forEachIndexed { index, card -> row.addView(card, LinearLayout.LayoutParams(px(cardWidth), px(height)).apply { marginEnd = px(if (index == cards.lastIndex) 0 else gap) }) }
        place(canvas, scroll, x - 2, y - 2, width + 4, height + 4)
    }
    private fun card(item: MediaItemData, poster: Boolean, saved: PlaybackStore.Recent?, callbacks: ConceptHomeCallbacks, run: () -> Unit): View {
        val card = FrameLayout(context).apply {
            tag = if (poster) "cameo-tv:poster:${item.type}:${item.imdbId}" else "cameo-tv:resume:${saved?.key?.hashCode()}"
            contentDescription = item.title; background = shape(p.panel, if (cinema) 3 else 9, if (aurora || glass) 0xFF60758D.toInt() else Color.TRANSPARENT); clipToOutline = true
            focus(this, if (cinema) 3 else 9); setOnClickListener { run() }
        }
        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        callbacks.loadImage(art, (if (poster) item.poster else item.background).ifBlank { item.poster }); card.addView(art, FrameLayout.LayoutParams(-1, -1))
        card.addView(View(context).apply { background = fade(intArrayOf(if (pearl) 0xDDF4F1EB.toInt() else if (poster) 0x77000000 else 0xCC000000.toInt(), Color.TRANSPARENT), if (pearl && !poster) GradientDrawable.Orientation.LEFT_RIGHT else if (poster) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP) }, FrameLayout.LayoutParams(-1, -1))
        val title = text(item.title.uppercase(Locale.ROOT), if (poster) 16 else 12, if (pearl) p.text else Color.WHITE, if (slate) 650 else 400, pearl).apply {
            maxLines = 2; if (poster || cinema) letterSpacing = if (cinema) .16f else .1f
            if (cinema) { typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); textScaleX = .82f }
            if (poster) gravity = Gravity.CENTER
        }
        card.addView(title, FrameLayout.LayoutParams(-1, -2, if (poster || pearl || cinema || glass) Gravity.TOP else Gravity.BOTTOM).apply {
            leftMargin = px(if (poster) 9 else 12); rightMargin = px(9)
            if (poster || pearl || cinema || glass) topMargin = px(if (poster) 10 else 17) else bottomMargin = px(20)
        })
        if (saved != null) {
            if (pearl) card.addView(text(remaining(saved), 10, p.text, serif = true), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = px(18); bottomMargin = px(19) })
            val progress = CameoTvProgress(context, saved.position.toDouble() / saved.duration.coerceAtLeast(1), p.accent)
            card.addView(progress, FrameLayout.LayoutParams(-1, px(4), Gravity.BOTTOM).apply { leftMargin = px(12); rightMargin = px(12); bottomMargin = px(9) })
        }
        return card
    }
    private fun remaining(saved: PlaybackStore.Recent): String { val minutes = ((saved.duration - saved.position).coerceAtLeast(0) / 60_000).toInt(); return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left" }
    private companion object { val fonts = mutableMapOf<String, Typeface>() }
}

private class CameoTvProgress(context: Context, private val progress: Double, private val accent: Int) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: android.graphics.Canvas) {
        paint.color = 0x997F8E9D.toInt(); canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        paint.color = accent; canvas.drawRoundRect(0f, 0f, (width * (progress.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0)).toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
    }
}

/** Construction helpers avoid enum initialization cycles. */
internal fun cameoTvPalette(id: String): ThemePalette {
    val pearl = id == "pearl"
    val accent = when (id) { "glass" -> 0xFF87D9FF; "cinema" -> 0xFFE9BF7C; "slate" -> 0xFF00CE83; "pearl" -> 0xFF315F7D; else -> 0xFF0DE7DE }.toInt()
    val background = when (id) { "glass" -> 0xFF091A2D; "cinema" -> 0xFF080807; "slate" -> 0xFF1B2025; "pearl" -> 0xFFFAF6F0; else -> 0xFF101024 }.toInt()
    return ThemePalette(background, if (pearl) 0xFFEDE8E1.toInt() else 0xFF222C37.toInt(), if (pearl) 0xFF24292D.toInt() else if (id == "cinema") 0xFFFFF4E0.toInt() else 0xFFF4F7FC.toInt(),
        if (pearl) 0xFF545B62.toInt() else 0xFFD1DAE6.toInt(), accent, 0xFF9E69EE.toInt(), if (pearl) Color.WHITE else 0xFF071722.toInt(),
        if (pearl) 0xFF929394.toInt() else 0xFF52637B.toInt(), if (pearl) 0xFFE1DAD2.toInt() else 0xFF21445B.toInt(), accent,
        if (id == "glass") 0xFFF0F5FB.toInt() else accent)
}
internal fun cameoTvLayout(id: String) = ThemeLayout(if (id == "glass" || id == "slate") NavigationPlacement.LEFT_RAIL else NavigationPlacement.TOP_BAR,
    if (id == "pearl") HeroComposition.SPLIT else if (id == "slate" || id == "aurora") HeroComposition.FRAMED else HeroComposition.FULL_BLEED,
    CardFormat.POSTER, 18, if (id == "glass" || id == "slate") 153 else 0, if (id == "glass" || id == "slate") 0 else 54,
    240, 150, 114, 9, 9, 38, if (id == "pearl") "serif" else "sans-serif", .05f, 18, 1.012f)
