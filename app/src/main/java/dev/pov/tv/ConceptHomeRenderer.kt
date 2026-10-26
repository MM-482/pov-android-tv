package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
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
import dev.pov.tv.data.PlaybackIdentity
import java.security.MessageDigest
import java.util.Locale

data class ConceptHomeData(
    val movies: List<MediaItemData>, val shows: List<MediaItemData>,
    val recent: List<PlaybackStore.Recent>, val upNext: List<PlaybackIdentity> = emptyList(),
)
data class ConceptHomeCallbacks(
    val loadImage: (ImageView,String) -> Unit,
    val details: (MediaItemData) -> Unit,
    val play: (MediaItemData) -> Unit,
    val resume: (PlaybackStore.Recent) -> Unit,
    val browse: (String) -> Unit,
    val bindContinue: (View,PlaybackStore.Recent) -> Unit,
    val episode: (PlaybackIdentity) -> Unit,
)

/** Six native compositions. All titles, art, progress and actions come from real input data. */
class ConceptHomeRenderer(private val context:Context,private val theme:ThemeSpec) {
    private val profile=requireNotNull(theme.conceptProfile)
    private val ui=ConceptUi(context,theme)
    private val palette=theme.palette
    private data class Entry(val item:MediaItemData,val saved:PlaybackStore.Recent?=null,val episode:PlaybackIdentity?=null)

    fun render(body:LinearLayout,heroBackdrop:FrameLayout,backdropArt:ImageView,data:ConceptHomeData,callbacks:ConceptHomeCallbacks) {
        (body.parent as? ScrollView)?.let { ConceptShellRenderer.applyContentInsets(it,theme,home=true) }
        body.removeAllViews()
        // The Activity owns bottom clearance for its active mini-player.
        body.setPadding(0,0,0,body.paddingBottom)
        val canvas=FrameLayout(context).apply { tag="concept-home:${theme.id}";clipChildren=false;clipToPadding=false }
        body.addView(canvas,LinearLayout.LayoutParams(ui.px(960),ui.px(540)))
        val saved=data.recent.filter { it.duration>0 && it.position>=0 && it.position<it.duration }
        val first=saved.firstOrNull()?.let { entry(it,data) } ?: (data.movies.firstOrNull()?:data.shows.firstOrNull())?.let { Entry(it) }
        if(theme==ThemeSpec.STUDIO) {
            heroBackdrop.visibility=View.GONE
            studio(canvas,first,saved,data,callbacks)
            return
        }
        if(first!=null) {
            if(theme==ThemeSpec.ATELIER) {
                heroBackdrop.visibility=View.GONE
                val art=artFrame(first.item,callbacks,9)
                ui.place(canvas,art,392,66,538,259)
            } else {
                heroBackdrop.layoutParams=heroBackdrop.layoutParams.apply { height=ui.px(540) }
                heroBackdrop.visibility=View.VISIBLE
                callbacks.loadImage(backdropArt,address(first.item))
            }
            feature(canvas,first,callbacks)
        } else {
            heroBackdrop.visibility=View.GONE
            ui.place(canvas,ui.text("Find your next story",30,palette.text,true),profile.heroCopyX,130,500,50)
            ui.place(canvas,action("Browse movies",true){callbacks.browse("movie")},profile.heroCopyX,205,155,42)
        }
        val entries=if(saved.isNotEmpty())saved.take(12).map { entry(it,data) }
            else (data.movies.ifEmpty { data.shows }).take(12).map { Entry(it) }
        val heading=if(saved.isNotEmpty())"Continue Watching" else "Discover"
        ui.place(canvas,ui.text(heading,if(theme==ThemeSpec.PREMIERE)16 else 18,palette.text,true),profile.sectionX,profile.sectionY,700,27)
        watchingRow(canvas,entries,callbacks)
    }

    private fun entry(saved:PlaybackStore.Recent,data:ConceptHomeData):Entry {
        val identity=saved.identity
        val catalog=(data.movies+data.shows).firstOrNull { it.imdbId==identity?.imdbId }
        val item=catalog?:MediaItemData(identity?.imdbId.orEmpty(),identity?.type?:"movie",identity?.title?:saved.title,
            background=identity?.imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }?.let { "https://images.metahub.space/background/medium/$it/img" }.orEmpty())
        return Entry(item,saved)
    }

    private fun feature(canvas:FrameLayout,entry:Entry,callbacks:ConceptHomeCallbacks) {
        val item=entry.item
        val x=profile.heroCopyX
        val y=profile.heroCopyY
        val width=profile.heroCopyWidth
        val titleY=when(theme) { ThemeSpec.ATELIER->132;ThemeSpec.HORIZON->117;else->y }
        if(theme==ThemeSpec.ATELIER||theme==ThemeSpec.HORIZON) {
            val eyebrow=ui.text(if(theme==ThemeSpec.ATELIER)"FEATURED TONIGHT" else "YOUR NEXT GREAT STORY",8,
                if(theme==ThemeSpec.ATELIER)0xFFAC7D59.toInt() else palette.muted,true).apply { letterSpacing=.40f;maxLines=1 }
            ui.place(canvas,eyebrow,x,if(theme==ThemeSpec.ATELIER)110 else 94,width,15)
        }
        val title=ui.text(item.title.uppercase(Locale.getDefault()),profile.titleSize,
            if(theme==ThemeSpec.ATELIER)Color.BLACK else palette.text).apply {
            typeface=ui.font(if(theme==ThemeSpec.GLASS)350 else 400,serif=theme==ThemeSpec.PREMIERE)
            letterSpacing=profile.titleTracking
            gravity=Gravity.CENTER_VERTICAL;maxLines=2;ellipsize=TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(ui.px(22).coerceAtLeast(1),ui.px(profile.titleSize).coerceAtLeast(2),ui.px(1).coerceAtLeast(1),TypedValue.COMPLEX_UNIT_PX)
            contentDescription=item.title
        }
        ui.place(canvas,title,x,titleY,width,if(theme==ThemeSpec.PREMIERE)59 else 55)
        val metadata=listOf(item.year,item.genres.firstOrNull().orEmpty(),item.runtime).filter(String::isNotBlank).joinToString("  ·  ")
        val metaY=when(theme) { ThemeSpec.GLASS->157;ThemeSpec.ESSENTIAL->148;ThemeSpec.PREMIERE->188;ThemeSpec.ATELIER->186;else->166 }
        val meta=ui.text(metadata,12,if(theme==ThemeSpec.ATELIER)palette.muted else 0xFFCDD3DF.toInt()).apply {
            maxLines=1;letterSpacing=if(theme==ThemeSpec.GLASS||theme==ThemeSpec.PREMIERE).16f else 0f
        }
        ui.place(canvas,meta,x,metaY,width,22)
        val descY=when(theme) { ThemeSpec.GLASS->187;ThemeSpec.ESSENTIAL->176;ThemeSpec.PREMIERE->212;ThemeSpec.ATELIER->216;else->193 }
        if(item.description.isNotBlank()) {
            val description=ui.text(item.description,12,palette.muted).apply { maxLines=2;setLineSpacing(ui.px(3).toFloat(),1f) }
            ui.place(canvas,description,x,descY,if(theme==ThemeSpec.ATELIER)300 else minOf(width,380),43)
        }
        val buttonY=when(theme) { ThemeSpec.GLASS->235;ThemeSpec.ESSENTIAL->225;ThemeSpec.PREMIERE->260;ThemeSpec.ATELIER->267;else->241 }
        val primary=action(if(entry.saved!=null)"▶  Resume" else "▶  Play",true){ activate(entry,callbacks) }.apply { tag="concept:hero:play" }
        entry.saved?.let { callbacks.bindContinue(primary,it) }
        ui.place(canvas,primary,x,buttonY,if(theme==ThemeSpec.ATELIER)132 else if(theme==ThemeSpec.HORIZON)153 else 143,42)
        if(item.imdbId.isNotBlank()) {
            ui.place(canvas,action("ⓘ  More info",false){callbacks.details(item)}.apply { tag="concept:hero:details" },
                x+(if(theme==ThemeSpec.ATELIER)141 else if(theme==ThemeSpec.HORIZON)165 else 153),buttonY,
                if(theme==ThemeSpec.ESSENTIAL)111 else if(theme==ThemeSpec.PREMIERE)146 else 124,42)
        }
    }

    private fun watchingRow(canvas:FrameLayout,entries:List<Entry>,callbacks:ConceptHomeCallbacks) {
        val width=4*profile.cardWidth+3*profile.cardGap
        val scroll=HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled=false;isFocusable=false;isFocusableInTouchMode=false
            descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS;clipChildren=true;clipToPadding=false
            // A long row is one child; clipChildren alone clips that child's own
            // bounds, rather than this viewport, on the Android 8 TV renderer.
            addOnLayoutChangeListener { target,left,top,right,bottom,_,_,_,_ ->
                target.clipBounds=Rect(0,0,right-left,bottom-top)
            }
        }
        val row=LinearLayout(context).apply {clipChildren=false;clipToPadding=false;setPadding(ui.px(3),ui.px(3),ui.px(5),ui.px(5))}
        scroll.addView(row)
        entries.forEach { entry ->
            row.addView(card(entry,profile.cardWidth,profile.cardHeight,profile.cardImageHeight,profile.labelsOutside,callbacks),
                LinearLayout.LayoutParams(ui.px(profile.cardWidth),ui.px(profile.cardHeight)).apply { rightMargin=ui.px(profile.cardGap) })
        }
        ui.place(canvas,scroll,profile.cardsX-3,profile.cardsY-3,width+6,profile.cardHeight+10)
    }

    private fun card(entry:Entry,width:Int,height:Int,imageHeight:Int,outside:Boolean,callbacks:ConceptHomeCallbacks):FrameLayout {
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
            val tile=card(entry,252,cardHeight,cardHeight,false,callbacks)
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

    private fun action(label:String,primary:Boolean,callback:()->Unit):TextView {
        val foreground=if(primary)palette.buttonText else palette.text
        return ui.text(label,13,foreground,true).apply {
            gravity=Gravity.CENTER;maxLines=1;contentDescription=label.removePrefix("▶  ").removePrefix("ⓘ  ")
            background=when {
                primary&&theme==ThemeSpec.HORIZON -> ui.gradient(intArrayOf(0xFF64E8FD.toInt(),0xFFB773FC.toInt()),GradientDrawable.Orientation.LEFT_RIGHT,profile.buttonRadius,palette.accent)
                primary&&theme==ThemeSpec.PREMIERE -> ui.gradient(intArrayOf(0xFFFCDB96.toInt(),0xFFF7C47E.toInt()),GradientDrawable.Orientation.TOP_BOTTOM,profile.buttonRadius,0xFFFFE3A0.toInt())
                else -> ui.rounded(if(primary)palette.primaryButton else if(theme==ThemeSpec.ATELIER)palette.panel else ui.alpha(palette.panel,185),profile.buttonRadius,
                    if(primary&&theme==ThemeSpec.GLASS)Color.WHITE else if(!primary&&theme!=ThemeSpec.ATELIER)palette.border else Color.TRANSPARENT)
            }
            ui.focus(this,profile.buttonRadius,if(primary&&theme==ThemeSpec.ATELIER)Color.WHITE else palette.accent);setOnClickListener {callback()}
        }
    }

    private fun artFrame(item:MediaItemData,callbacks:ConceptHomeCallbacks,radius:Int)=FrameLayout(context).apply {
        background=ui.rounded(palette.panel,radius,palette.border);clipToOutline=true
        val image=ImageView(context).apply {scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        addView(image,FrameLayout.LayoutParams(-1,-1));callbacks.loadImage(image,address(item))
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
