package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.content.res.ColorStateList
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Native, content-driven phone/foldable Home. No TV canvas or baked-in screenshot. */
class MobileHomeRenderer(private val context: Context, private val theme: ThemeSpec) {
    private val ui = MobileUi(context, theme)
    private val content = MobileContentRenderer(context, theme)

    fun render(body: LinearLayout, heroBackdrop: FrameLayout, backdropArt: ImageView,
        data: ConceptHomeData, callbacks: ConceptHomeCallbacks) {
        heroBackdrop.visibility = View.GONE
        backdropArt.setImageDrawable(null)
        body.removeAllViews()
        body.tag = "mobile-home:${theme.id}"
        body.setPadding(0, ui.dp(4), 0, body.paddingBottom.coerceAtLeast(ui.dp(24)))
        val saved = data.recent.filter { it.duration > 0 && it.position >= 0 && it.position < it.duration }
        val firstSaved = saved.firstOrNull()
        val featured = firstSaved?.let { item(it, data) } ?: data.movies.firstOrNull() ?: data.shows.firstOrNull()
        if (featured != null) {
            val feature = content.hero(featured, callbacks.loadImage,
                primaryLabel = if (firstSaved != null) "Resume" else "Watch now",
                onPrimary = { if (firstSaved != null) callbacks.resume(firstSaved) else callbacks.play(featured) },
                onDetails = if (featured.imdbId.isNotBlank()) ({ callbacks.details(featured) }) else null)
            firstSaved?.let { recent ->
                feature.findViewWithTag<View>("mobile:hero:primary")?.let { callbacks.bindContinue(it, recent) }
            }
            body.addView(feature, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(24) })
        } else {
            body.addView(ui.text("Your next great watch", 27, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
            body.addView(ui.text("Explore movies, shows, and anime, or open your library.", 15, ui.palette.muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(16) })
            body.addView(ui.action("Browse movies", true) { callbacks.browse("movie") }, LinearLayout.LayoutParams(-1, -2))
        }
        if (saved.isNotEmpty()) {
            section(body, "Continue watching")
            val row = row(body)
            saved.take(20).forEach { recent ->
                val tile = content.card(item(recent, data), callbacks.loadImage,
                    caption = "${((recent.duration - recent.position).coerceAtLeast(0) / 60000)} min left",
                    progress = recent.position.toDouble() / recent.duration.coerceAtLeast(1),
                    onClick = { callbacks.resume(recent) }).apply {
                    tag = "mobile:resume:${digest(recent.key)}"
                    callbacks.bindContinue(this, recent)
                }
                row.addView(tile, cardParams())
            }
        }
        if (data.upNext.isNotEmpty()) {
            section(body, "Up next")
            val row = row(body)
            data.upNext.take(20).forEach { identity ->
                val title = (data.shows + data.movies).firstOrNull { it.imdbId == identity.imdbId }
                    ?: MediaItemData(identity.imdbId, identity.type, identity.title, background = artwork(identity.imdbId))
                row.addView(content.card(title, callbacks.loadImage, caption = "Season ${identity.season} · Episode ${identity.episode}",
                    onClick = { callbacks.episode(identity) }).apply {
                    tag = "mobile:episode:${identity.imdbId}:${identity.season}:${identity.episode}"
                }, cardParams())
            }
        }
        catalogRow(body, "Popular movies", data.movies, callbacks)
        catalogRow(body, "Popular shows", data.shows, callbacks)
        if (data.movies.isEmpty() && data.shows.isEmpty() && saved.isNotEmpty()) {
            body.addView(ui.text("Your saved progress is available. Browse again when the catalog is reachable.", 14, ui.palette.muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        }
    }

    private fun catalogRow(body: LinearLayout, title: String, items: List<MediaItemData>, callbacks: ConceptHomeCallbacks) {
        if (items.isEmpty()) return
        section(body, title)
        val row = row(body)
        items.take(24).forEach { item ->
            row.addView(content.card(item, callbacks.loadImage, caption = item.year, onClick = { callbacks.details(item) })
                .apply { tag = "mobile:media:${item.type}:${item.imdbId}" }, cardParams())
        }
    }

    private fun section(body: LinearLayout, title: String) {
        body.addView(ui.text(title, 21, bold = true), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = ui.dp(12); bottomMargin = ui.dp(12)
        })
    }
    private fun row(body: LinearLayout): LinearLayout {
        val row = LinearLayout(context).apply { setPadding(0, 0, ui.dp(8), ui.dp(8)) }
        body.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; isFocusable = false
            addView(row, FrameLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        return row
    }
    private fun cardParams() = LinearLayout.LayoutParams(ui.dp(content.cardWidthDp()), -2).apply { rightMargin = ui.dp(12) }
    private fun item(saved: PlaybackStore.Recent, data: ConceptHomeData): MediaItemData =
        (data.movies + data.shows).firstOrNull { it.imdbId == saved.identity?.imdbId }
            ?: MediaItemData(saved.identity?.imdbId.orEmpty(), saved.identity?.type ?: "movie", saved.identity?.title ?: saved.title,
                background = artwork(saved.identity?.imdbId.orEmpty()))
    private fun artwork(id: String) = if (id.matches(Regex("tt[0-9]+"))) "https://images.metahub.space/background/medium/$id/img" else ""
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(10).joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Reusable mobile feature/card layouts used by Home and the Activity's catalog/details pages. */
class MobileContentRenderer(private val context: Context, private val theme: ThemeSpec) {
    private val ui = MobileUi(context, theme)
    private val window = AdaptiveLayout.snapshot(context)

    fun cardWidthDp(poster: Boolean = false): Int =
        if (poster) (window.contentWidthDp * .43f).roundToInt().coerceIn(132, 188)
        else (window.contentWidthDp * .66f).roundToInt().coerceIn(180, 248)

    fun cardHeightDp(): Int = (cardWidthDp() * 9f / 16f).roundToInt() + 84

    fun hero(item: MediaItemData, loadImage: (ImageView, String) -> Unit, primaryLabel: String? = null,
        onPrimary: (() -> Unit)? = null, onDetails: (() -> Unit)? = null): FrameLayout {
        val frame = FrameLayout(context).apply {
            tag = "featured-hero"; background = ui.rounded(ui.palette.panel, 22, ui.palette.border)
            clipToOutline = true
        }
        val layout = LinearLayout(context).apply {
            orientation = if (window.splitFeature) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val artwork = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(ui.palette.navSelected)
        }
        loadImage(artwork, item.background.ifBlank { item.poster })
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
        }
        val eyebrow = ui.text(if (primaryLabel == "Resume") "PICK UP WHERE YOU LEFT OFF" else "FEATURED", 11, ui.palette.accent, true).apply {
            letterSpacing = .12f
        }
        copy.addView(eyebrow, LinearLayout.LayoutParams(-1, -2))
        copy.addView(ui.text(item.title, if (window.splitFeature) 32 else 28, bold = true).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        val metadata = listOf(item.year, item.genres.firstOrNull().orEmpty(), item.runtime).filter(String::isNotBlank).joinToString(" · ")
        if (metadata.isNotBlank()) copy.addView(ui.text(metadata, 13, ui.palette.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        if (item.description.isNotBlank()) copy.addView(ui.text(item.description, 14, ui.palette.muted).apply {
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(ui.dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        if (onPrimary != null || onDetails != null) {
            val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            if (onPrimary != null) actions.addView(ui.action(primaryLabel ?: "Watch now", true, onPrimary).apply {
                tag = "mobile:hero:primary"
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = if (onDetails != null) ui.dp(10) else 0 })
            if (onDetails != null) actions.addView(ui.action("More info", false, onDetails), LinearLayout.LayoutParams(0, -2, 1f))
            copy.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        }
        if (window.splitFeature) {
            artwork.minimumHeight = ui.dp(300)
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
        val frame = FrameLayout(context).apply {
            contentDescription = listOf(item.title, caption.orEmpty()).filter(String::isNotBlank).joinToString(". ")
            minimumHeight = ui.dp(48); isFocusable = true; isClickable = true
            background = ui.ripple(ui.rounded(ui.palette.panel, 16, ui.palette.border))
            clipToOutline = true
            setOnClickListener { onClick() }
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val artwork = AspectArtwork(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(ui.palette.navSelected)
        }
        loadImage(artwork, item.background.ifBlank { item.poster })
        column.addView(artwork, LinearLayout.LayoutParams(-1, -2))
        if (progress != null) column.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; this.progress = (progress.coerceIn(0.0, 1.0) * 1000).roundToInt()
            progressTintList = ColorStateList.valueOf(ui.palette.progress)
            progressBackgroundTintList = ColorStateList.valueOf(ui.palette.border)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, ui.dp(3)))
        column.addView(ui.text(item.title, 15, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = ui.dp(12); rightMargin = ui.dp(12); topMargin = ui.dp(10); bottomMargin = if (caption.isNullOrBlank()) ui.dp(12) else 0
            })
        if (!caption.isNullOrBlank()) column.addView(ui.text(caption, 12, ui.palette.muted).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ui.dp(12); rightMargin = ui.dp(12); topMargin = ui.dp(4); bottomMargin = ui.dp(12) })
        frame.addView(column, FrameLayout.LayoutParams(-1, -2))
        return frame
    }

    /** Actual measured card width determines the image's aspect ratio after a window resize. */
    private class AspectArtwork(context: Context) : ImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
            val desiredHeight = (measuredWidth * 9f / 16f).roundToInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.EXACTLY))
        }
    }
}
