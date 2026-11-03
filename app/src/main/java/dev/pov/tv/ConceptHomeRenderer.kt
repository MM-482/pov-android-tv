package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
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
import dev.pov.tv.data.PlaybackIdentity
import java.security.MessageDigest
import java.util.Locale

data class ConceptHomeData(
    val movies: List<MediaItemData>, val shows: List<MediaItemData>,
    val recent: List<PlaybackStore.Recent>, val upNext: List<PlaybackIdentity> = emptyList(),
)
data class ConceptHomeCallbacks(
    val loadImage: (ImageView, String) -> Unit,
    val details: (MediaItemData) -> Unit,
    val play: (MediaItemData) -> Unit,
    val resume: (PlaybackStore.Recent) -> Unit,
    val browse: (String) -> Unit,
    val bindContinue: (View, PlaybackStore.Recent) -> Unit,
    val episode: (PlaybackIdentity) -> Unit,
    val saveToLibrary: (MediaItemData) -> Unit = {},
)

/** November 2 native TV compositions. Artwork, labels, progress and actions remain live data. */
class ConceptHomeRenderer(private val context: Context, private val theme: ThemeSpec) {
    private val profile = requireNotNull(theme.conceptProfile)
    private val ui = ConceptUi(context, theme)
    private val palette = theme.palette
    private data class Entry(val item: MediaItemData, val saved: PlaybackStore.Recent? = null, val episode: PlaybackIdentity? = null)

    fun render(body: LinearLayout, heroBackdrop: FrameLayout, backdropArt: ImageView, data: ConceptHomeData, callbacks: ConceptHomeCallbacks) {
        (body.parent as? ScrollView)?.let { ConceptShellRenderer.applyContentInsets(it, theme, home = true) }
        body.removeAllViews()
        // MainActivity owns the extra scroll clearance while its mini-player is active.
        body.setPadding(0, 0, 0, body.paddingBottom)
        val canvas = FrameLayout(context).apply { tag = "concept-home:${theme.id}"; clipChildren = false; clipToPadding = false }
        body.addView(canvas, LinearLayout.LayoutParams(ui.px(960), ui.px(540)))
        val saved = data.recent.filter { it.duration > 0 && it.position >= 0 && it.position < it.duration }
        if (theme == ThemeSpec.STUDIO) {
            val first = saved.firstOrNull()?.let { entry(it, data) }
                ?: (data.movies.firstOrNull() ?: data.shows.firstOrNull())?.let { Entry(it) }
            heroBackdrop.visibility = View.GONE
            studio(canvas, first, saved, data, callbacks)
            return
        }
        val featured = data.movies.firstOrNull() ?: data.shows.firstOrNull()
        heroBackdrop.visibility = View.GONE
        backdropArt.setImageDrawable(null)
        if (featured != null) {
            if (theme == ThemeSpec.GLASS || theme == ThemeSpec.PREMIERE) {
                heroBackdrop.layoutParams = heroBackdrop.layoutParams.apply {
                    height = ui.px(if (theme == ThemeSpec.PREMIERE) 285 else 540)
                }
                heroBackdrop.visibility = View.VISIBLE
                callbacks.loadImage(backdropArt, address(featured))
            } else {
                val art = artFrame(featured, callbacks, if (theme == ThemeSpec.HORIZON) 19 else profile.radius)
                art.tag = "concept:hero:art"
                if (theme != ThemeSpec.ATELIER) art.addView(View(context).apply {
                    background = ui.gradient(intArrayOf(ui.alpha(palette.background, 250), ui.alpha(palette.background, 120), Color.TRANSPARENT), GradientDrawable.Orientation.LEFT_RIGHT)
                }, FrameLayout.LayoutParams(-1, -1))
                ui.place(canvas, art, profile.heroFrameX, profile.heroFrameY, profile.heroFrameWidth, profile.heroFrameHeight)
            }
            feature(canvas, featured, callbacks)
        } else {
            ui.place(canvas, ui.text("Find your next story", 30, palette.text, true), profile.heroCopyX, profile.heroCopyY, profile.heroCopyWidth, 70)
            ui.place(canvas, action("Browse movies", true) { callbacks.browse("movie") }, profile.heroCopyX, profile.actionsY, 150, 36)
        }
        if (saved.isNotEmpty()) {
            heading(canvas, "Continue Watching", "continue", profile.sectionY, profile.watchingWidth)
            shelf(canvas, saved.take(24).map { entry(it, data) }, callbacks, discovery = false,
                y = profile.cardsY, width = profile.cardWidth, height = profile.cardHeight, gap = profile.cardGap, visible = 3)
        }
        val discover = (data.movies + data.shows).distinctBy { it.type to it.imdbId }.filterNot { item ->
            featured != null && item.type == featured.type && item.imdbId == featured.imdbId
        }
        if (discover.isNotEmpty()) {
            heading(canvas, "Discover", "discover", if (saved.isEmpty()) profile.sectionY else profile.discoverSectionY, profile.discoverWidth)
            shelf(canvas, discover.take(40).map { Entry(it) }, callbacks, discovery = true,
                y = if (saved.isEmpty()) profile.cardsY else profile.discoverCardsY,
                width = profile.discoverCardWidth, height = profile.discoverCardHeight, gap = profile.discoverCardGap, visible = 5)
        }
    }

    private fun entry(saved: PlaybackStore.Recent, data: ConceptHomeData): Entry {
        val identity = saved.identity
        val catalog = (data.movies + data.shows).firstOrNull { it.imdbId == identity?.imdbId && it.type == identity?.type }
        val item = catalog ?: MediaItemData(identity?.imdbId.orEmpty(), identity?.type ?: "movie", identity?.title ?: saved.title,
            background = identity?.imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }?.let { "https://images.metahub.space/background/medium/$it/img" }.orEmpty())
        return Entry(item, saved)
    }

    private fun feature(canvas: FrameLayout, item: MediaItemData, callbacks: ConceptHomeCallbacks) {
        val title = ui.text(item.title.uppercase(Locale.getDefault()), profile.titleSize, palette.text,
            theme == ThemeSpec.ESSENTIAL || theme == ThemeSpec.HORIZON).apply {
            tag = "concept:hero:title"
            typeface = ui.font(if (theme == ThemeSpec.ESSENTIAL || theme == ThemeSpec.HORIZON) 750 else if (theme == ThemeSpec.ATELIER) 550 else if (theme == ThemeSpec.GLASS) 500 else 400,
                serif = theme == ThemeSpec.ATELIER, condensed = theme == ThemeSpec.PREMIERE)
            letterSpacing = profile.titleTracking
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            // The reference title stacks use tight leading; retain room for real long titles.
            setLineSpacing(0f, when (theme) {
                ThemeSpec.GLASS -> .78f
                ThemeSpec.ESSENTIAL -> .84f
                ThemeSpec.ATELIER -> .74f
                ThemeSpec.HORIZON -> .80f
                else -> 1f
            })
            setAutoSizeTextTypeUniformWithConfiguration(ui.px(20).coerceAtLeast(1), ui.px(profile.titleSize).coerceAtLeast(2), ui.px(1).coerceAtLeast(1), TypedValue.COMPLEX_UNIT_PX)
            contentDescription = item.title
        }
        ui.place(canvas, title, profile.heroCopyX, profile.heroCopyY, profile.heroCopyWidth, profile.titleHeight)
        val metadata = listOf(item.year, item.genres.firstOrNull().orEmpty(), item.runtime).filter(String::isNotBlank).joinToString("  ·  ")
        ui.place(canvas, ui.text(metadata, 13, if (theme == ThemeSpec.ATELIER) palette.text else palette.muted).apply {
            maxLines = 1
            if (theme == ThemeSpec.ATELIER) typeface = ui.font(500, serif = true)
        }, profile.heroCopyX, profile.metadataY, profile.heroCopyWidth, 20)
        ui.place(canvas, action("Explore", true) { callbacks.details(item) }.apply { tag = "concept:hero:explore" },
            profile.heroCopyX, profile.actionsY, profile.primaryWidth, 36)
        ui.place(canvas, action("+ Library", false) { callbacks.saveToLibrary(item) }.apply { tag = "concept:hero:library" },
            profile.heroCopyX + profile.primaryWidth + profile.actionGap, profile.actionsY, profile.secondaryWidth, 36)
    }

    private fun heading(canvas: FrameLayout, value: String, key: String, y: Int, width: Int) {
        val ruled = theme == ThemeSpec.PREMIERE || theme == ThemeSpec.ATELIER
        val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; tag = "concept:heading:$key" }
        val label = ui.text(if (theme == ThemeSpec.PREMIERE) value.uppercase(Locale.getDefault()) else value,
            if (theme == ThemeSpec.PREMIERE) 13 else 17, palette.text, theme != ThemeSpec.PREMIERE).apply {
            maxLines = 1
            typeface = ui.font(if (theme == ThemeSpec.PREMIERE) 500 else 650, serif = theme == ThemeSpec.ATELIER)
            if (theme == ThemeSpec.PREMIERE) letterSpacing = .18f
        }
        line.addView(label, LinearLayout.LayoutParams(-2, -2))
        if (ruled) line.addView(View(context).apply { setBackgroundColor(ui.alpha(palette.border, 200)) },
            LinearLayout.LayoutParams(0, ui.px(1).coerceAtLeast(1), 1f).apply { leftMargin = ui.px(14) })
        ui.place(canvas, line, profile.sectionX, y, width, 23)
    }

    private fun shelf(canvas: FrameLayout, entries: List<Entry>, callbacks: ConceptHomeCallbacks, discovery: Boolean,
        y: Int, width: Int, height: Int, gap: Int, visible: Int) {
        val viewportWidth = visible * width + (visible - 1) * gap
        val scroll = HorizontalScrollView(context).apply {
            tag = "concept:row:${if (discovery) "discover" else "continue"}"
            isHorizontalScrollBarEnabled = false; isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; clipChildren = true; clipToPadding = false
            addOnLayoutChangeListener { target, left, top, right, bottom, _, _, _, _ -> target.clipBounds = Rect(0, 0, right - left, bottom - top) }
        }
        val row = LinearLayout(context).apply {
            clipChildren = false; clipToPadding = false
            setPadding(ui.px(3), ui.px(3), ui.px(5), ui.px(5))
        }
        scroll.addView(row)
        entries.forEach { entry ->
            row.addView(shelfCard(entry, width, height, discovery, callbacks),
                LinearLayout.LayoutParams(ui.px(width), ui.px(height)).apply { rightMargin = ui.px(gap) })
        }
        ui.place(canvas, scroll, profile.cardsX - 3, y - 3, viewportWidth + 6, height + 9)
    }

    private fun shelfCard(entry: Entry, width: Int, height: Int, discovery: Boolean, callbacks: ConceptHomeCallbacks): FrameLayout {
        val frame = FrameLayout(context).apply {
            tag = stableTag(entry)
            contentDescription = listOf(entry.item.title, if (discovery) "Explore" else "Resume", remaining(entry.saved)).filter(String::isNotBlank).joinToString(". ")
            clipChildren = false
            setOnClickListener { if (discovery) callbacks.details(entry.item) else activate(entry, callbacks) }
        }
        val art = artFrame(entry.item, callbacks, profile.radius, poster = discovery)
        ui.place(frame, art, 0, 0, width, height)
        val lightResume = theme == ThemeSpec.ATELIER && !discovery
        art.addView(View(context).apply {
            background = if (lightResume) ui.gradient(intArrayOf(ui.alpha(palette.background, 240), ui.alpha(palette.background, 125), Color.TRANSPARENT), GradientDrawable.Orientation.LEFT_RIGHT)
            else ui.gradient(intArrayOf(ui.alpha(Color.BLACK, if (discovery) 125 else 35), Color.TRANSPARENT, ui.alpha(Color.BLACK, if (discovery) 15 else 190)), GradientDrawable.Orientation.TOP_BOTTOM)
        }, FrameLayout.LayoutParams(-1, -1))
        val title = ui.text(entry.item.title.uppercase(Locale.getDefault()),
            if (theme == ThemeSpec.ATELIER) 19 else if (theme == ThemeSpec.PREMIERE) 17 else if (theme == ThemeSpec.ESSENTIAL && !discovery) 12 else 15,
            if (lightResume) palette.text else Color.WHITE, theme == ThemeSpec.ESSENTIAL && !discovery).apply {
            maxLines = if (theme == ThemeSpec.ESSENTIAL && !discovery) 1 else 2
            typeface = ui.font(if (theme == ThemeSpec.ESSENTIAL && !discovery) 700 else 450,
                serif = theme == ThemeSpec.ATELIER, condensed = theme == ThemeSpec.PREMIERE)
            letterSpacing = if (theme == ThemeSpec.ESSENTIAL && !discovery || theme == ThemeSpec.ATELIER) 0f else .13f
            gravity = if (discovery && theme != ThemeSpec.ATELIER) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.TOP
            if (theme == ThemeSpec.HORIZON && !discovery) visibility = View.INVISIBLE
        }
        val titleY = if (theme == ThemeSpec.ESSENTIAL && !discovery || theme == ThemeSpec.HORIZON && !discovery) height - 34 else if (discovery) 10 else 15
        val titleHeight = if (theme == ThemeSpec.ESSENTIAL && !discovery || theme == ThemeSpec.HORIZON && !discovery) 19 else 43
        ui.place(frame, title, if (lightResume) 18 else 12, titleY, if (lightResume) minOf(width - 36, 168) else width - 24, titleHeight)
        if (entry.saved != null) {
            val ratio = (entry.saved.position.toDouble() / entry.saved.duration.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            progress(frame, ratio, if (lightResume) 18 else 12, height - 13, width - if (lightResume) 36 else 24, 4)
            if (lightResume) ui.place(frame, ui.text(remaining(entry.saved), 11, palette.text).apply {
                typeface = ui.font(400, serif = true); maxLines = 1
            }, 18, height - 32, width - 36, 16)
        }
        frame.isFocusable = true; frame.isFocusableInTouchMode = true; frame.isClickable = true
        frame.setOnFocusChangeListener { _, focused ->
            frame.foreground = if (focused) FocusRingDrawable(palette.accent, ui.px(profile.radius).toFloat(), true, ui.scale) else null
            frame.scaleX = if (focused) theme.layout.focusScale else 1f; frame.scaleY = frame.scaleX
            frame.elevation = if (focused) ui.px(8).toFloat() else 0f
            if (theme == ThemeSpec.HORIZON && !discovery) title.visibility = if (focused) View.VISIBLE else View.INVISIBLE
        }
        entry.saved?.let { callbacks.bindContinue(frame, it) }
        return frame
    }

    private fun legacyCard(entry:Entry,width:Int,height:Int,imageHeight:Int,outside:Boolean,callbacks:ConceptHomeCallbacks):FrameLayout {
        val frame=FrameLayout(context).apply {
            tag=stableTag(entry);contentDescription=listOf(entry.item.title,entry.episode?.let {"Season ${it.season}, episode ${it.episode}"}.orEmpty(),remaining(entry.saved)).filter(String::isNotBlank).joinToString(". ")
            clipChildren=false
            setOnClickListener { activate(entry,callbacks) }
        }
        val image=artFrame(entry.item,callbacks,profile.radius)
        ui.place(frame,image,0,0,width,imageHeight)
        if(!outside) image.addView(View(context).apply {background=ui.gradient(intArrayOf(0xEF03060B.toInt(),Color.TRANSPARENT),GradientDrawable.Orientation.BOTTOM_TOP)},FrameLayout.LayoutParams(-1,-1))
        val titleColor=if(outside)palette.text else Color.WHITE
        val title=ui.text(if(outside)entry.item.title else entry.item.title.uppercase(Locale.getDefault()),if(outside)12 else 12,titleColor,true).apply {
            maxLines=1
            if(!outside) { typeface=ui.font(400,serif=theme==ThemeSpec.PREMIERE);letterSpacing=if(theme==ThemeSpec.PREMIERE).19f else .15f }
        }
        ui.place(frame,title,if(outside)4 else 12,if(outside)imageHeight+6 else if(entry.episode!=null)height-58 else height-40,width-(if(outside)8 else 24),20)
        val progress=entry.saved?.let { (it.position.toDouble()/it.duration.coerceAtLeast(1)).coerceIn(0.0,1.0) }
        if(progress!=null) {
            val barWidth=if(outside&&theme==ThemeSpec.ATELIER)width-20 else width-66
            progress(frame,progress,if(outside&&theme==ThemeSpec.ATELIER)10 else if(outside)5 else 12,
                if(outside&&theme==ThemeSpec.ATELIER)imageHeight-9 else if(outside)imageHeight+29 else height-15,barWidth,3)
            ui.place(frame,ui.text(remaining(entry.saved),if(theme==ThemeSpec.ATELIER)10 else 8,
                if(outside)palette.muted else 0xFFCCD3DF.toInt()).apply {gravity=if(theme==ThemeSpec.ATELIER)Gravity.START else Gravity.END},
                if(theme==ThemeSpec.ATELIER)4 else width-61,if(theme==ThemeSpec.ATELIER)imageHeight+24 else if(outside)imageHeight+24 else height-20,
                if(theme==ThemeSpec.ATELIER)width-8 else 52,17)
        } else if(outside&&entry.item.year.isNotBlank()) {
            ui.place(frame,ui.text(entry.item.year,9,palette.muted),4,imageHeight+24,width-8,17)
        }
        val ringTarget=if(outside)image else frame
        frame.isFocusable=true;frame.isFocusableInTouchMode=true;frame.isClickable=true
        frame.setOnFocusChangeListener { _,focused ->
            ringTarget.foreground=if(focused)FocusRingDrawable(if(theme==ThemeSpec.PREMIERE)0xFFFFFFD8.toInt()else palette.accent,ui.px(profile.radius).toFloat(),true,ui.scale)else null
            frame.scaleX=if(focused)theme.layout.focusScale else 1f;frame.scaleY=frame.scaleX
            frame.elevation=if(focused)ui.px(8).toFloat() else 0f
        }
        entry.saved?.let {callbacks.bindContinue(frame,it)}
        return frame
    }

    private fun studio(canvas:FrameLayout,first:Entry?,saved:List<PlaybackStore.Recent>,data:ConceptHomeData,callbacks:ConceptHomeCallbacks) {
        ui.place(canvas,ui.text(if(saved.isEmpty())"Discover" else "Continue Watching",20,palette.text,true),178,33,490,28)
        if(first!=null) {
            val feature=artFrame(first.item,callbacks,10).apply {tag="concept:studio:feature"}
            feature.addView(View(context).apply { background=ui.gradient(intArrayOf(0xE5030A10.toInt(),Color.TRANSPARENT),GradientDrawable.Orientation.BOTTOM_TOP) },FrameLayout.LayoutParams(-1,-1))
            ui.place(canvas,feature,178,63,496,276)
            val title=ui.text(first.item.title.uppercase(Locale.getDefault()),30,Color.WHITE).apply {
                letterSpacing=.22f;maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(ui.px(18),ui.px(30),ui.px(1).coerceAtLeast(1),TypedValue.COMPLEX_UNIT_PX)
            }
            ui.place(feature,title,21,147,454,38)
            val meta=listOf(first.item.year,first.item.genres.firstOrNull().orEmpty(),first.item.runtime).filter(String::isNotBlank).joinToString(" · ")
            ui.place(feature,ui.text(meta,12,0xFFD7DCE7.toInt()),21,185,440,22)
            first.saved?.let {
                progress(feature,(it.position.toDouble()/it.duration.coerceAtLeast(1)).coerceIn(0.0,1.0),21,211,218,4)
                ui.place(feature,ui.text("${it.position/60000}m / ${it.duration/60000}m",8,palette.muted),249,206,205,18)
            }
            val resume=action(if(first.saved==null)"▶  Play" else "▶  Resume",true){activate(first,callbacks)}.apply {tag="concept:hero:play"}
            first.saved?.let {callbacks.bindContinue(resume,it)}
            ui.place(feature,resume,20,229,122,35)
            if(first.item.imdbId.isNotBlank())ui.place(feature,action("ⓘ  More info",false){callbacks.details(first.item)}.apply {tag="concept:hero:details"},150,229,108,35)
        } else ui.place(canvas,action("Browse movies",true){callbacks.browse("movie")},198,180,160,44)

        val next=if(data.upNext.isNotEmpty())data.upNext.take(2).map { identity ->
            val item=(data.shows+data.movies).firstOrNull {it.imdbId==identity.imdbId}
                ?:MediaItemData(identity.imdbId,identity.type,identity.title,background=if(identity.imdbId.matches(Regex("tt[0-9]+")))"https://images.metahub.space/background/medium/${identity.imdbId}/img" else "")
            Entry(item,episode=identity)
        } else if(saved.size>1)saved.drop(1).take(2).map {entry(it,data)} else data.shows.take(2).map {Entry(it)}
        val nextHeading=if(data.upNext.isNotEmpty())"Up Next" else if(saved.size>1)"Keep Watching" else "Discover Shows"
        ui.place(canvas,ui.text(nextHeading,16,palette.muted,true),689,38,250,22)
        next.forEachIndexed {index,entry ->
            val cardHeight=if(index==0)127 else 136
            val tile=legacyCard(entry,252,cardHeight,cardHeight,false,callbacks)
            if(entry.episode!=null) {
                ui.place(tile,ui.text("S${entry.episode.season} E${entry.episode.episode}",10,0xFFE0E8F3.toInt()),14,cardHeight-35,218,20)
            }
            ui.place(canvas,tile,689,63+index*139,252,cardHeight)
        }
        ui.place(canvas,ui.text("Browse",16,palette.muted,true),178,358,750,24)
        val browse=listOf("movie" to "Movies", "series" to "TV Shows", "anime" to "Anime", "library" to "My Library")
        browse.forEachIndexed {index,(key,label) ->
            val item=when(key){"series"->data.shows.firstOrNull();"anime"->data.shows.getOrNull(1);"library"->first?.item;else->data.movies.getOrNull(1)?:data.movies.firstOrNull()}
            val frame=if(item!=null)artFrame(item,callbacks,10)else FrameLayout(context).apply {background=ui.rounded(palette.panel,10)}
            frame.addView(View(context).apply {background=ui.gradient(intArrayOf(0xE6040A10.toInt(),Color.TRANSPARENT),GradientDrawable.Orientation.BOTTOM_TOP)},FrameLayout.LayoutParams(-1,-1))
            ui.place(frame,ui.text("$label  ›",16,Color.WHITE,true),14,89,155,27)
            frame.tag="concept:browse:$key";frame.contentDescription=label;ui.focus(frame,10)
            frame.setOnClickListener{callbacks.browse(key)}
            val lefts=intArrayOf(178,363,566,757);val widths=intArrayOf(176,192,183,183)
            ui.place(canvas,frame,lefts[index],380,widths[index],120)
        }
    }

    private fun action(label: String, primary: Boolean, callback: () -> Unit): TextView =
        ui.text(label, if (theme == ThemeSpec.ATELIER) 18 else 15, if (primary) palette.buttonText else palette.text, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            contentDescription = label.removePrefix("▶  ").removePrefix("ⓘ  ")
            if (theme == ThemeSpec.ATELIER) typeface = ui.font(650, serif = true)
            background = when {
                primary && theme == ThemeSpec.PREMIERE -> ui.gradient(intArrayOf(palette.secondaryAccent, palette.primaryButton), GradientDrawable.Orientation.TOP_BOTTOM, profile.buttonRadius, palette.text)
                primary -> ui.rounded(palette.primaryButton, profile.buttonRadius, if (theme == ThemeSpec.ESSENTIAL) palette.text else Color.TRANSPARENT)
                else -> ui.rounded(if (theme == ThemeSpec.GLASS) ui.alpha(palette.panel, 195) else Color.TRANSPARENT,
                    profile.buttonRadius, if (theme == ThemeSpec.ATELIER) palette.muted else ui.alpha(palette.text, 190))
            }
            ui.focus(this, profile.buttonRadius)
            setOnClickListener { callback() }
        }

    private fun artFrame(item:MediaItemData,callbacks:ConceptHomeCallbacks,radius:Int,poster:Boolean=false):FrameLayout =
        (if (theme == ThemeSpec.STUDIO) FrameLayout(context) else RoundedArtworkFrame(context, ui.px(radius).toFloat())).apply {
        background=ui.rounded(palette.panel,radius,palette.border);clipToOutline=true
        val image=ImageView(context).apply {scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        addView(image,FrameLayout.LayoutParams(-1,-1));callbacks.loadImage(image,if(poster)item.poster.ifBlank { item.background } else address(item))
    }
    private fun address(item:MediaItemData)=item.background.ifBlank {item.poster}
    private fun activate(entry:Entry,callbacks:ConceptHomeCallbacks) {
        when {entry.saved!=null->callbacks.resume(entry.saved);entry.episode!=null->callbacks.episode(entry.episode);else->callbacks.play(entry.item)}
    }
    private fun remaining(saved:PlaybackStore.Recent?)=saved?.let {"${((it.duration-it.position).coerceAtLeast(0)/60000)}m left"}.orEmpty()
    private fun stableTag(entry:Entry):String=when {
        entry.saved!=null -> "concept:resume:"+MessageDigest.getInstance("SHA-256").digest(entry.saved.key.toByteArray()).take(10).joinToString(""){"%02x".format(it.toInt()and 255)}
        entry.episode!=null -> "concept:episode:${entry.episode.imdbId}:${entry.episode.season}:${entry.episode.episode}"
        else -> "concept:media:${entry.item.type}:${entry.item.imdbId}"
    }
    private fun progress(parent:FrameLayout,value:Double,x:Int,y:Int,width:Int,height:Int) {
        ui.place(parent,View(context).apply {background=ui.rounded(when(theme){ThemeSpec.ATELIER->0xFF9F9690.toInt();ThemeSpec.GLASS->0xFF292E40.toInt();ThemeSpec.PREMIERE->0xFF262423.toInt();ThemeSpec.HORIZON->0xFF222F41.toInt();else->0xFF394557.toInt()},3)},x,y,width,height)
        ui.place(parent,View(context).apply {background=ui.rounded(palette.progress,3)},x,y,(width*value).toInt().coerceIn(0,width),height)
    }
}
