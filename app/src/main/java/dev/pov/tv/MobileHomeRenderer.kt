package dev.pov.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import dev.pov.tv.data.MediaItemData
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/** Content-driven native phone/foldable Home. Every control remains an Android view. */
class MobileHomeRenderer(private val context: Context, private val theme: ThemeSpec) {
    private val ui = MobileUi(context, theme)
    private val profile = ui.profile
    private val window = AdaptiveLayout.snapshot(context)
    private val content = MobileContentRenderer(context, theme)
    private val shelfWidth = (window.widthDp - window.railWidthDp - profile.insetDp * 2).coerceAtLeast(1)
    private val shelfColumns = if (window.useNavigationRail) 3 else 2
    private val tileWidth = (shelfWidth - 10 * (shelfColumns - 1)) / shelfColumns

    fun render(body: LinearLayout, heroBackdrop: FrameLayout, backdropArt: ImageView,
        data: ConceptHomeData, callbacks: ConceptHomeCallbacks) {
        (body.parent as? ScrollView)?.let { MobileShellRenderer.applyContentInsets(it, theme, home = true) }
        heroBackdrop.visibility = View.GONE
        backdropArt.setImageDrawable(null)
        body.removeAllViews()
        body.tag = "mobile-home:${theme.id}"
        body.setPadding(0, 0, 0, body.paddingBottom.coerceAtLeast(ui.dp(24)))
        val saved = data.recent.filter { it.duration > 0 && it.position >= 0 && it.position < it.duration }
        // The feature is discovery. Saved playback remains bound to its exact Continue Watching entry.
        val featured = data.movies.firstOrNull() ?: data.shows.firstOrNull()
            ?: saved.firstOrNull { !it.identity?.imdbId.isNullOrBlank() }?.let { item(it, data) }
        if (featured != null) {
            body.addView(content.homeHero(featured, callbacks), LinearLayout.LayoutParams(-1, -2))
        } else {
            val empty = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(profile.insetDp), ui.dp(if (profile.headerOverArtwork) 116 else 20), ui.dp(profile.insetDp), ui.dp(20))
            }
            empty.addView(ui.display("Your next great watch", 27), LinearLayout.LayoutParams(-1, -2))
            empty.addView(ui.text("Explore movies, shows, and anime, or open your library.", 15, ui.palette.muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(16) })
            empty.addView(ui.action("Browse movies", true) { callbacks.browse("movie") }, LinearLayout.LayoutParams(-1, -2))
            body.addView(empty, LinearLayout.LayoutParams(-1, -2))
        }
        if (saved.isNotEmpty()) {
            section(body, "Continue Watching")
            val row = row(body, "mobile:continue-row")
            saved.take(20).forEach { recent ->
                val caption = "${((recent.duration - recent.position).coerceAtLeast(0) / 60000)} min left"
                val tile = content.artCard(item(recent, data), callbacks.loadImage, tileWidth,
                    caption = caption, progress = recent.position.toDouble() / recent.duration.coerceAtLeast(1),
                    onClick = { callbacks.resume(recent) }).apply {
                    tag = "mobile:resume:${digest(recent.key)}"
                    callbacks.bindContinue(this, recent)
                }
                row.addView(tile, shelfCardParams())
            }
        }
        val catalog = (data.movies + data.shows).distinctBy { "${it.type}:${it.imdbId}" }
        val watchedIds = saved.mapNotNull { it.identity?.imdbId }.toSet()
        val discovery = catalog.filter { it.imdbId != featured?.imdbId && it.imdbId !in watchedIds }
            .ifEmpty { catalog.filter { it.imdbId != featured?.imdbId } }
        if (discovery.isNotEmpty()) {
            section(body, "Discover")
            val row = row(body, "mobile:discover-row")
            discovery.take(24).forEach { item ->
                row.addView(content.artCard(item, callbacks.loadImage, tileWidth, poster = true,
                    onClick = { callbacks.details(item) }).apply { tag = "mobile:media:${item.type}:${item.imdbId}" }, shelfCardParams())
            }
        }
        if (data.upNext.isNotEmpty()) {
            section(body, "Up Next")
            val row = row(body, "mobile:up-next-row")
            data.upNext.take(20).forEach { identity ->
                val title = catalog.firstOrNull { it.imdbId == identity.imdbId }
                    ?: MediaItemData(identity.imdbId, identity.type, identity.title, background = artwork(identity.imdbId))
                row.addView(content.card(title, callbacks.loadImage, caption = "Season ${identity.season} · Episode ${identity.episode}",
                    onClick = { callbacks.episode(identity) }).apply {
                    tag = "mobile:episode:${identity.imdbId}:${identity.season}:${identity.episode}"
                }, LinearLayout.LayoutParams(ui.dp(content.cardWidthDp()), -2).apply { rightMargin = ui.dp(10) })
            }
        }
        catalogRow(body, "Popular Movies", data.movies, callbacks)
        catalogRow(body, "Popular Shows", data.shows, callbacks)
        if (data.movies.isEmpty() && data.shows.isEmpty() && saved.isNotEmpty()) {
            body.addView(ui.text("Your saved progress is available. Browse again when the catalog is reachable.", 14, ui.palette.muted),
                shelfParams().apply { topMargin = ui.dp(20) })
        }
    }

    private fun catalogRow(body: LinearLayout, title: String, items: List<MediaItemData>, callbacks: ConceptHomeCallbacks) {
        if (items.isEmpty()) return
        section(body, title)
        val row = row(body, "mobile:catalog:${items.first().type}")
        items.take(24).forEach { item ->
            row.addView(content.card(item, callbacks.loadImage, caption = item.year, onClick = { callbacks.details(item) })
                .apply { tag = "mobile:catalog-item:${item.type}:${item.imdbId}" },
                LinearLayout.LayoutParams(ui.dp(content.cardWidthDp()), -2).apply { rightMargin = ui.dp(10) })
        }
    }
    private fun section(body: LinearLayout, title: String) {
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (theme == ThemeSpec.ATELIER) section.addView(View(context).apply { setBackgroundColor(ui.palette.border) },
            LinearLayout.LayoutParams(-1, ui.dp(1)).apply { bottomMargin = ui.dp(6) })
        section.addView(ui.section(title), LinearLayout.LayoutParams(-1, -2))
        body.addView(section, shelfParams().apply { topMargin = ui.dp(16); bottomMargin = ui.dp(10) })
    }
    private fun row(body: LinearLayout, rowTag: String): LinearLayout {
        val row = LinearLayout(context).apply { tag = "$rowTag:cards" }
        body.addView(HorizontalScrollView(context).apply {
            tag = rowTag; isHorizontalScrollBarEnabled = false; isFocusable = false; clipToPadding = false
            addView(row, FrameLayout.LayoutParams(-2, -2))
        }, shelfParams().apply { bottomMargin = ui.dp(4) })
        return row
    }
    private fun shelfParams() = LinearLayout.LayoutParams(-1, -2).apply {
        if (profile.fullBleedHero) { leftMargin = ui.dp(profile.insetDp); rightMargin = ui.dp(profile.insetDp) }
    }
    private fun shelfCardParams() = LinearLayout.LayoutParams(ui.dp(tileWidth), -2).apply { rightMargin = ui.dp(10) }
    private fun item(saved: PlaybackStore.Recent, data: ConceptHomeData): MediaItemData =
        (data.movies + data.shows).firstOrNull { it.imdbId == saved.identity?.imdbId }
            ?: MediaItemData(saved.identity?.imdbId.orEmpty(), saved.identity?.type ?: "movie", saved.identity?.title ?: saved.title,
                background = artwork(saved.identity?.imdbId.orEmpty()))
    private fun artwork(id: String) = if (id.matches(Regex("tt[0-9]+"))) "https://images.metahub.space/background/medium/$id/img" else ""
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(10).joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Reusable mobile content. Home has themed compositions; detail/catalog APIs remain generic. */
class MobileContentRenderer(private val context: Context, private val theme: ThemeSpec) {
    private val ui = MobileUi(context, theme)
    private val profile = ui.profile
    private val window = AdaptiveLayout.snapshot(context)
    private val homeWidth = window.widthDp - window.railWidthDp - if (profile.fullBleedHero) 0 else profile.insetDp * 2

    fun cardWidthDp(poster: Boolean = false): Int =
        if (poster) ((window.contentWidthDp - 10) / 2).coerceIn(132, 200)
        else (window.contentWidthDp * .66f).roundToInt().coerceIn(180, 248)
    fun cardHeightDp(): Int = (cardWidthDp() * 9f / 16f).roundToInt() + 84

    fun homeHero(item: MediaItemData, callbacks: ConceptHomeCallbacks): FrameLayout {
        val frame = MobileArtworkFrame(context, ui.dp(if (profile.hero == MobileHeroComposition.SLATE) 12 else 0).toFloat()).apply {
            tag = "featured-hero"
            if (profile.hero == MobileHeroComposition.SLATE) background = ui.rounded(ui.palette.panel, 12)
            clipToOutline = profile.hero == MobileHeroComposition.SLATE
        }
        val composition = "mobile:hero:composition:${profile.hero.name.lowercase(Locale.ROOT)}:${if (window.splitFeature) "wide" else "narrow"}"
        val copy = homeCopy(item, callbacks)
        if (window.splitFeature && !profile.fullBleedHero) {
            val layout = LinearLayout(context).apply { tag = composition; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            copy.minimumHeight = ui.dp(240)
            layout.addView(copy, LinearLayout.LayoutParams(0, -2, .48f))
            val artwork = artwork(item, callbacks.loadImage, rounded = true)
            artwork.minimumHeight = ui.dp(240)
            layout.addView(artwork, LinearLayout.LayoutParams(0, -1, .52f))
            frame.addView(layout, FrameLayout.LayoutParams(-1, -2))
        } else if (profile.fullBleedHero) {
            frame.addView(artwork(item, callbacks.loadImage), FrameLayout.LayoutParams(-1, -1))
            frame.addView(View(context).apply {
                background = GradientDrawable(if (window.splitFeature) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.TOP_BOTTOM,
                    if (window.splitFeature) intArrayOf(ui.palette.background, ui.alpha(ui.palette.background, 210), Color.TRANSPARENT)
                    else intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, ui.alpha(ui.palette.background, 65), ui.palette.background, ui.palette.background))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, -1))
            val layout = LinearLayout(context).apply { tag = composition; orientation = LinearLayout.VERTICAL }
            if (window.splitFeature) {
                copy.minimumHeight = ui.dp(if (profile.headerOverArtwork) 340 else 245)
                copy.gravity = Gravity.CENTER_VERTICAL
                if (profile.headerOverArtwork) copy.setPadding(copy.paddingLeft, ui.dp(110), copy.paddingRight, ui.dp(20))
                layout.addView(copy, LinearLayout.LayoutParams(ui.dp((homeWidth * .58f).roundToInt()), -2))
            } else {
                val artworkSpace = when (profile.hero) {
                    MobileHeroComposition.GLASS -> (homeWidth * .51f).roundToInt()
                        .coerceAtLeast((50 * context.resources.configuration.fontScale + 72).roundToInt().coerceAtLeast(174))
                    MobileHeroComposition.CINEMA -> (homeWidth * .40f).roundToInt()
                    else -> (homeWidth * .44f).roundToInt()
                }
                layout.addView(View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-1, ui.dp(artworkSpace)))
                layout.addView(copy, LinearLayout.LayoutParams(-1, -2))
            }
            frame.addView(layout, FrameLayout.LayoutParams(-1, -2))
        } else {
            val layout = LinearLayout(context).apply { tag = composition; orientation = LinearLayout.VERTICAL }
            val artHeight = (homeWidth * if (profile.hero == MobileHeroComposition.PEARL) .50f else .40f).roundToInt()
            layout.addView(artwork(item, callbacks.loadImage, rounded = profile.hero == MobileHeroComposition.PEARL), LinearLayout.LayoutParams(-1, ui.dp(artHeight)))
            layout.addView(copy, LinearLayout.LayoutParams(-1, -2))
            frame.addView(layout, FrameLayout.LayoutParams(-1, -2))
        }
        return frame
    }

    private fun homeCopy(item: MediaItemData, callbacks: ConceptHomeCallbacks): LinearLayout {
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inset = if (profile.hero == MobileHeroComposition.PEARL) 0 else if (profile.fullBleedHero) profile.insetDp + (if (theme == ThemeSpec.GLASS) 4 else 0) else 16
            setPadding(ui.dp(inset), ui.dp(if (profile.hero == MobileHeroComposition.PEARL || profile.hero == MobileHeroComposition.SLATE) 8 else 0), ui.dp(inset), ui.dp(8))
        }
        val title = ui.display(item.title.uppercase(Locale.ROOT), if (window.splitFeature) profile.titleSizeSp.coerceAtMost(38) else profile.titleSizeSp).apply {
            tag = "mobile:hero:title"; maxLines = 4; ellipsize = TextUtils.TruncateAt.END
            contentDescription = item.title
            if (profile.hero == MobileHeroComposition.GLASS && !window.splitFeature) maxWidth = ui.dp((homeWidth * .68f).roundToInt())
        }
        copy.addView(title, LinearLayout.LayoutParams(if (profile.hero == MobileHeroComposition.GLASS && !window.splitFeature) -2 else -1, -2))
        val metadata = metadata(item)
        if (metadata.isNotBlank()) copy.addView(ui.text(metadata, if (theme == ThemeSpec.GLASS) 14 else 15,
            if (theme == ThemeSpec.ATELIER || theme == ThemeSpec.GLASS) ui.palette.muted else ui.palette.text).apply {
            tag = "mobile:hero:metadata"
            if (theme == ThemeSpec.PREMIERE) letterSpacing = .08f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(ui.action("Explore", true) { callbacks.details(item) }.apply {
            tag = "mobile:hero:primary"; contentDescription = "Explore ${item.title}"
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(12) })
        actions.addView(ui.action("+ Library", false) { callbacks.saveToLibrary(item) }.apply {
            tag = "mobile:hero:library"; contentDescription = "Add ${item.title} to library"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        copy.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        return copy
    }

    /** Two complete native artwork tiles fit the phone shelf; larger type expands their height. */
    fun artCard(item: MediaItemData, loadImage: (ImageView, String) -> Unit, widthDp: Int, poster: Boolean = false,
        caption: String? = null, progress: Double? = null, onClick: () -> Unit): FrameLayout {
        val frame = MobileArtworkFrame(context, ui.dp(profile.cardRadiusDp).toFloat()).apply {
            contentDescription = listOf(item.title, caption.orEmpty()).filter(String::isNotBlank).joinToString(". ")
            isFocusable = true; isClickable = true
            val baseHeight = (widthDp * if (poster) profile.posterHeightRatio else if (theme == ThemeSpec.PREMIERE) .63f else .57f).roundToInt()
            minimumHeight = ui.dp(baseHeight + ((context.resources.configuration.fontScale - 1f).coerceAtLeast(0f) * 44).roundToInt())
            background = ui.rounded(ui.palette.panel, profile.cardRadiusDp,
                if (theme == ThemeSpec.HORIZON) ui.palette.border else Color.TRANSPARENT)
            foreground = ui.ripple(ui.rounded(Color.TRANSPARENT, profile.cardRadiusDp))
            clipToOutline = true
            if (theme == ThemeSpec.ATELIER) elevation = ui.dp(2).toFloat()
            setOnClickListener { onClick() }
        }
        val image = ImageView(context).apply {
            tag = "mobile:tile:art"; scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(ui.palette.navSelected)
        }
        loadImage(image, if (poster) item.poster.ifBlank { item.background } else item.background.ifBlank { item.poster })
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(context).apply {
            background = GradientDrawable(if (poster) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(if (poster) 0x8006111C.toInt() else 0xE607111A.toInt(), Color.TRANSPARENT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, if (poster) ui.dp((widthDp * .65f).roundToInt()) else -1, if (poster) Gravity.TOP else Gravity.BOTTOM))
        val overlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(if (poster) 14 else 10), ui.dp(if (poster) 14 else 8), ui.dp(if (poster) 14 else 10), ui.dp(8))
        }
        val title = if (poster) ui.display(item.title.uppercase(Locale.ROOT), if (theme == ThemeSpec.PREMIERE) 28 else 24, Color.WHITE)
            else if (theme == ThemeSpec.ATELIER || theme == ThemeSpec.PREMIERE) ui.display(item.title.uppercase(Locale.ROOT), if (theme == ThemeSpec.PREMIERE) 23 else 18, Color.WHITE)
            else ui.text(item.title.uppercase(Locale.ROOT), 16, Color.WHITE, true)
        title.apply {
            tag = "mobile:tile:title"; maxLines = if (poster) 3 else 2; ellipsize = TextUtils.TruncateAt.END
            if (poster && theme != ThemeSpec.PREMIERE) { gravity = Gravity.CENTER; letterSpacing = if (theme == ThemeSpec.GLASS || theme == ThemeSpec.ATELIER) .12f else 0f }
        }
        overlay.addView(title, LinearLayout.LayoutParams(if (poster) ui.dp((widthDp * if (theme == ThemeSpec.PREMIERE) .60f else .78f).roundToInt()) else -1, -2).apply {
            if (poster && theme != ThemeSpec.PREMIERE) gravity = Gravity.CENTER_HORIZONTAL
        })
        if (!poster && theme == ThemeSpec.ATELIER && !caption.isNullOrBlank()) overlay.addView(ui.text(caption, 11, Color.WHITE),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
        if (progress != null) overlay.addView(progressBar(progress), LinearLayout.LayoutParams(-1, ui.dp(4)).apply { topMargin = ui.dp(6) })
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -2, if (poster) Gravity.TOP else Gravity.BOTTOM))
        if (theme == ThemeSpec.HORIZON) frame.addView(View(context).apply {
            background = ui.rounded(Color.TRANSPARENT, profile.cardRadiusDp, ui.palette.border)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    fun hero(item: MediaItemData, loadImage: (ImageView, String) -> Unit, primaryLabel: String? = null,
        onPrimary: (() -> Unit)? = null, onDetails: (() -> Unit)? = null): FrameLayout {
        val frame = RoundedArtworkFrame(context, ui.dp(profile.cardRadiusDp).toFloat()).apply {
            tag = "featured-hero"; background = ui.rounded(ui.palette.panel, profile.cardRadiusDp, ui.palette.border); clipToOutline = true
        }
        val layout = LinearLayout(context).apply { orientation = if (window.splitFeature) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val artwork = artwork(item, loadImage)
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
        }
        copy.addView(ui.text(if (primaryLabel == "Resume") "PICK UP WHERE YOU LEFT OFF" else "FEATURED", 11, ui.palette.accent, true).apply { letterSpacing = .12f }, LinearLayout.LayoutParams(-1, -2))
        copy.addView(ui.display(item.title, if (window.splitFeature) 32 else 28).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        val metadata = metadata(item)
        if (metadata.isNotBlank()) copy.addView(ui.text(metadata, 13, ui.palette.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        if (item.description.isNotBlank()) copy.addView(ui.text(item.description, 14, ui.palette.muted).apply {
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(ui.dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        if (onPrimary != null || onDetails != null) {
            val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            if (onPrimary != null) actions.addView(ui.action(primaryLabel ?: "Watch now", true, onPrimary).apply { tag = "mobile:hero:primary" },
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = if (onDetails != null) ui.dp(10) else 0 })
            if (onDetails != null) actions.addView(ui.action("More info", false, onDetails), LinearLayout.LayoutParams(0, -2, 1f))
            copy.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        }
        if (window.splitFeature) {
            copy.minimumHeight = ui.dp(260)
            artwork.minimumHeight = ui.dp(260)
            layout.addView(artwork, LinearLayout.LayoutParams(0, -1, .48f))
            layout.addView(copy, LinearLayout.LayoutParams(0, -2, .52f))
        } else {
            layout.addView(artwork, LinearLayout.LayoutParams(-1, ui.dp((window.contentWidthDp * 9f / 16f).roundToInt().coerceIn(150, 280))))
            layout.addView(copy, LinearLayout.LayoutParams(-1, -2))
        }
        frame.addView(layout, FrameLayout.LayoutParams(-1, -2))
        return frame
    }

    fun card(item: MediaItemData, loadImage: (ImageView, String) -> Unit, caption: String? = null,
        progress: Double? = null, onClick: () -> Unit): FrameLayout {
        val frame = RoundedArtworkFrame(context, ui.dp(profile.cardRadiusDp).toFloat()).apply {
            contentDescription = listOf(item.title, caption.orEmpty()).filter(String::isNotBlank).joinToString(". ")
            minimumHeight = ui.dp(48); isFocusable = true; isClickable = true
            background = ui.ripple(ui.rounded(ui.palette.panel, profile.cardRadiusDp, ui.palette.border)); clipToOutline = true
            setOnClickListener { onClick() }
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val artwork = AspectArtwork(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(ui.palette.navSelected)
        }
        loadImage(artwork, item.background.ifBlank { item.poster })
        column.addView(artwork, LinearLayout.LayoutParams(-1, -2))
        if (progress != null) column.addView(progressBar(progress), LinearLayout.LayoutParams(-1, ui.dp(3)))
        column.addView(ui.text(item.title, 15, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = ui.dp(12); rightMargin = ui.dp(12); topMargin = ui.dp(10); bottomMargin = if (caption.isNullOrBlank()) ui.dp(12) else 0
            })
        if (!caption.isNullOrBlank()) column.addView(ui.text(caption, 12, ui.palette.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ui.dp(12); rightMargin = ui.dp(12); topMargin = ui.dp(4); bottomMargin = ui.dp(12) })
        frame.addView(column, FrameLayout.LayoutParams(-1, -2))
        return frame
    }

    private fun artwork(item: MediaItemData, loadImage: (ImageView, String) -> Unit, rounded: Boolean = false): FrameLayout {
        val frame = RoundedArtworkFrame(context, ui.dp(if (rounded) 11 else 0).toFloat()).apply {
            tag = "mobile:hero:art"
            background = ui.rounded(ui.palette.navSelected, if (rounded) 11 else 0)
            clipToOutline = rounded
            if (rounded && theme == ThemeSpec.ATELIER) elevation = ui.dp(2).toFloat()
        }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        loadImage(image, item.background.ifBlank { item.poster })
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        return frame
    }
    private fun metadata(item: MediaItemData) = listOf(item.year, item.genres.firstOrNull().orEmpty(), item.runtime).filter(String::isNotBlank).joinToString(" · ")
    private fun progressBar(value: Double) = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progress = (value.coerceIn(0.0, 1.0) * 1000).roundToInt()
        progressTintList = ColorStateList.valueOf(ui.palette.progress)
        progressBackgroundTintList = ColorStateList.valueOf(0x806E818D.toInt())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    /** Image aspect follows the measured card width, including resized foldable windows. */
    private class AspectArtwork(context: Context) : ImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val desiredHeight = (MeasureSpec.getSize(widthMeasureSpec) * 9f / 16f).roundToInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.EXACTLY))
        }
    }

    /** Artwork intrinsic pixels must not set a shelf or feature's native layout height. */
    private class MobileArtworkFrame(context: Context, radiusPx: Float) : RoundedArtworkFrame(context, radiusPx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            var contentHeight = minimumHeight
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == View.GONE) continue
                val params = child.layoutParams as LayoutParams
                if (params.height == LayoutParams.MATCH_PARENT) continue
                measureChildWithMargins(child, widthMeasureSpec, 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0)
                contentHeight = contentHeight.coerceAtLeast(child.measuredHeight + params.topMargin + params.bottomMargin + paddingTop + paddingBottom)
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(resolveSize(contentHeight, heightMeasureSpec), MeasureSpec.EXACTLY))
        }
    }
}
