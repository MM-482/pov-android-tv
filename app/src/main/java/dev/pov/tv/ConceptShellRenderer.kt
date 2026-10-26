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

/** Real TV navigation over independently loaded artwork; concept PNGs are never a screen background. */
class ConceptShellRenderer(private val context: Context, private val theme: ThemeSpec) {
    private val profile = requireNotNull(theme.conceptProfile)
    private val ui = ConceptUi(context, theme)

    fun render(destinations: List<ConceptDestination>, selectedDestination: () -> String): ConceptShellViews {
        val root = FrameLayout(context).apply { setBackgroundColor(theme.palette.background); clipChildren=false }
        val backdrop = FrameLayout(context).apply { visibility=View.GONE; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val art = ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
        backdrop.addView(art, FrameLayout.LayoutParams(-1,-1))
        backdrop.addView(View(context).apply { background=ui.gradient(intArrayOf(theme.palette.background,
            ui.alpha(theme.palette.background,145),Color.TRANSPARENT),GradientDrawable.Orientation.LEFT_RIGHT) },FrameLayout.LayoutParams(-1,-1))
        backdrop.addView(View(context).apply { background=ui.gradient(intArrayOf(theme.palette.background,
            ui.alpha(theme.palette.background,200),Color.TRANSPARENT),GradientDrawable.Orientation.BOTTOM_TOP) },FrameLayout.LayoutParams(-1,-1))
        if(theme==ThemeSpec.HORIZON) backdrop.addView(View(context).apply {
            // Catalog artwork can be bright behind the top navigation; preserve its contrast.
            tag="concept:header-shade"
            background=ui.gradient(intArrayOf(0xED010308.toInt(),0xED010308.toInt(),Color.TRANSPARENT),GradientDrawable.Orientation.TOP_BOTTOM)
        },FrameLayout.LayoutParams(-1,ui.px(144)))
        root.addView(backdrop,FrameLayout.LayoutParams(-1,ui.px(540)))

        val body = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false }
        val scroller = ScrollView(context).apply { isFillViewport=true;isVerticalScrollBarEnabled=false;clipChildren=false;clipToPadding=false;isFocusable=false }
        scroller.addView(body)
        root.addView(scroller,FrameLayout.LayoutParams(-1,-1))
        applyContentInsets(scroller,theme,home=false)
        scroller.setOnScrollChangeListener { _,_,y,_,_ -> backdrop.translationY=-y.toFloat() }

        if (profile.railWidth>0) {
            val rail = View(context).apply {
                background=if(theme==ThemeSpec.GLASS) ui.gradient(intArrayOf(0xF00C1625.toInt(),0xED0E0D19.toInt(),0xEF142438.toInt()),
                    GradientDrawable.Orientation.TL_BR,17,0xFF50637A.toInt())
                else ui.gradient(intArrayOf(ui.alpha(theme.palette.background,248),ui.alpha(theme.palette.background,230)),GradientDrawable.Orientation.LEFT_RIGHT)
            }
            ui.place(root,rail,profile.railX,profile.railY,profile.railWidth,profile.railHeight)
        }
        val logo=ImageView(context).apply { setImageResource(R.drawable.pov_wordmark);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="POV" }
        ui.place(root,logo,profile.logoX,profile.logoY,profile.logoWidth,profile.logoHeight)

        val navigation=linkedMapOf<String,View>()
        val primary=destinations.filter { it.key !in setOf("trakt","nowplaying") }
        primary.forEachIndexed { index,target ->
            val top=profile.railWidth==0
            val x=if(top) when(target.key) {
                "movie" -> if(theme==ThemeSpec.HORIZON)209 else 210
                "series" -> if(theme==ThemeSpec.HORIZON)285 else 295
                "anime" -> if(theme==ThemeSpec.HORIZON)376 else 380
                "library" -> if(theme==ThemeSpec.HORIZON)447 else 465
                "search" -> if(theme==ThemeSpec.HORIZON)767 else 538
                "settings" -> if(theme==ThemeSpec.HORIZON)852 else 635
                else -> profile.navX
            } else profile.navX
            val y=if(top) profile.navY else profile.navY+index*profile.navStep
            val width=if(top&&target.key in setOf("search","settings")) 91 else profile.navWidth
            val cell=navigation(target,selectedDestination)
            ui.place(root,cell,x,y,width,profile.navHeight)
            navigation[target.key]=cell
        }
        primary.forEachIndexed { index,target ->
            val cell=requireNotNull(navigation[target.key])
            val previous=primary.getOrNull(index-1)?.let {navigation[it.key]}
            val next=primary.getOrNull(index+1)?.let {navigation[it.key]}
            if(profile.railWidth==0) {
                cell.nextFocusLeftId=previous?.id?:cell.id;cell.nextFocusRightId=next?.id?:cell.id
            } else {
                cell.nextFocusUpId=previous?.id?:cell.id;cell.nextFocusDownId=next?.id?:cell.id
            }
        }
        val badge=ui.text("",10,theme.palette.muted).apply { visibility=View.GONE }
        ui.place(root,badge,710,5,230,22)
        val footer=ui.text("Standalone app made by Mark Mateo",7,if(theme==ThemeSpec.ATELIER) 0xFF96918C.toInt() else 0xFF7A8291.toInt()).apply {
            gravity=Gravity.END or Gravity.CENTER_VERTICAL;letterSpacing=if(theme==ThemeSpec.PREMIERE) .18f else 0f
        }
        ui.place(root,footer,620,profile.footerY,320,13)
        return ConceptShellViews(root,body,scroller,backdrop,art,badge,navigation)
    }

    private fun navigation(target: ConceptDestination, selectedDestination: () -> String): View {
        val cell=FrameLayout(context).apply { id=View.generateViewId();tag="nav:${target.key}";contentDescription=target.label;isFocusable=true;isFocusableInTouchMode=true;isClickable=true }
        val top=profile.railWidth==0
        val line=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(ui.px(if(top) 12 else profile.navIconInset),0,ui.px(5),0) }
        val icon=NavigationIconView(context,target.key,theme.palette.muted)
        val showIcon=!top || target.key in setOf("search","settings")
        if(top&&!showIcon) {line.gravity=Gravity.CENTER;line.setPadding(0,0,0,0)}
        if(showIcon) line.addView(icon,LinearLayout.LayoutParams(ui.px(if(top)14 else 18),ui.px(if(top)14 else 18)).apply { rightMargin=ui.px(if(top)9 else 12) })
        val label=ui.text(if(target.key=="series") "TV Shows" else target.label,if(top)12 else if(theme==ThemeSpec.GLASS)11 else 12,theme.palette.muted)
        line.addView(label,LinearLayout.LayoutParams(-2,-2))
        cell.addView(line,FrameLayout.LayoutParams(-1,-1))
        val marker=View(context)
        if(theme==ThemeSpec.PREMIERE) { marker.background=ui.rounded(theme.palette.accent,2);ui.place(cell,marker,0,8,2,17) }
        fun paint(focused:Boolean) {
            val selected=selectedDestination()==target.key
            val active=focused||selected
            val color=if(active) theme.palette.text else theme.palette.muted
            label.setTextColor(if(selected&&theme==ThemeSpec.PREMIERE) theme.palette.accent else color)
            label.typeface=ui.font(if(selected) 650 else 430);icon.tint=if(selected&&theme!=ThemeSpec.GLASS&&theme!=ThemeSpec.ATELIER) theme.palette.accent else color
            marker.visibility=if(selected) View.VISIBLE else View.INVISIBLE
            val radius=if(theme==ThemeSpec.HORIZON) 22 else if(theme==ThemeSpec.ATELIER) 20 else profile.radius
            cell.background=when {
                theme==ThemeSpec.GLASS&&active -> ui.gradient(intArrayOf(0xAA174052.toInt(),0xAA44314E.toInt()),GradientDrawable.Orientation.LEFT_RIGHT,radius,0xFF5796B6.toInt())
                theme==ThemeSpec.ATELIER&&active -> ui.rounded(theme.palette.navSelected,radius)
                theme==ThemeSpec.PREMIERE -> ui.rounded(if(focused)theme.palette.navSelected else Color.TRANSPARENT,radius)
                active -> ui.rounded(ui.alpha(theme.palette.navSelected,160),radius,theme.palette.accent)
                else -> ui.rounded(Color.TRANSPARENT,radius)
            }
            cell.foreground=if(focused) FocusRingDrawable(if(theme==ThemeSpec.GLASS) 0xFF6DEBFF.toInt() else theme.palette.accent,ui.px(radius).toFloat(),true,ui.scale) else null
        }
        cell.setOnFocusChangeListener { _,focused -> paint(focused) }
        cell.setOnClickListener { target.action() };paint(false)
        return cell
    }

    companion object {
        fun applyContentInsets(scroller: ScrollView, theme: ThemeSpec, home: Boolean) {
            val profile=theme.conceptProfile?:return
            val scale=scroller.resources.displayMetrics.widthPixels/960f
            val params=scroller.layoutParams as? FrameLayout.LayoutParams ?: return
            params.leftMargin=if(home)0 else (profile.contentLeft*scale).roundToInt()
            params.rightMargin=if(home)0 else (profile.contentRight*scale).roundToInt()
            params.topMargin=if(home)0 else (profile.headerHeight*scale).roundToInt()
            scroller.layoutParams=params
        }
    }
}

/** Shared native drawing primitives, measured in the same logical pixels as MainActivity. */
internal class ConceptUi(val context:Context,val theme:ThemeSpec) {
    val scale=context.resources.displayMetrics.widthPixels/960f
    fun px(value:Int)=(value*scale).roundToInt()
    fun font(weight:Int=400,serif:Boolean=false):Typeface {
        val path=if(serif)"fonts/CormorantGaramond-Variable.ttf" else "fonts/Inter-Variable.ttf"
        return synchronized(fonts) { fonts.getOrPut("$path:$weight") {
            try { Typeface.Builder(context.assets,path).setWeight(weight).build() }
            catch(_:Exception){ Typeface.create(if(serif)"serif" else "sans-serif",if(weight>=600)Typeface.BOLD else Typeface.NORMAL) }
        } }
    }
    fun text(value:String,size:Int,color:Int,bold:Boolean=false)=TextView(context).apply {
        text=value;setTextSize(TypedValue.COMPLEX_UNIT_PX,px(size).toFloat());setTextColor(color)
        typeface=font(if(bold)650 else 400);includeFontPadding=false
        maxLines=2;ellipsize=TextUtils.TruncateAt.END
    }
    fun rounded(color:Int,radius:Int,stroke:Int=Color.TRANSPARENT)=GradientDrawable().apply {
        setColor(color);cornerRadius=px(radius).toFloat();if(stroke!=Color.TRANSPARENT)setStroke(px(1).coerceAtLeast(1),stroke)
    }
    fun gradient(colors:IntArray,orientation:GradientDrawable.Orientation,radius:Int=0,stroke:Int=Color.TRANSPARENT)=GradientDrawable(orientation,colors).apply {
        cornerRadius=px(radius).toFloat();if(stroke!=Color.TRANSPARENT)setStroke(px(1).coerceAtLeast(1),stroke)
    }
    fun alpha(color:Int,alpha:Int)=Color.argb(alpha,Color.red(color),Color.green(color),Color.blue(color))
    fun place(parent:FrameLayout,view:View,x:Int,y:Int,width:Int,height:Int) {
        parent.addView(view,FrameLayout.LayoutParams(if(width<0)width else px(width),if(height<0)height else px(height)).apply { leftMargin=px(x);topMargin=px(y) })
    }
    fun focus(view:View,radius:Int,color:Int=theme.palette.accent) {
        view.isFocusable=true;view.isFocusableInTouchMode=true;view.isClickable=true
        view.setOnFocusChangeListener { target,focused ->
            target.foreground=if(focused)FocusRingDrawable(color,px(radius).toFloat(),true,scale)else null
            target.scaleX=if(focused)theme.layout.focusScale else 1f;target.scaleY=target.scaleX
            target.elevation=if(focused)px(7).toFloat() else 0f
        }
    }
    private companion object { val fonts=mutableMapOf<String,Typeface>() }
}
