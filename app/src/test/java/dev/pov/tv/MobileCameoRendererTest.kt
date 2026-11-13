package dev.pov.tv

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-port-mdpi")
class MobileCameoRendererTest {
    private fun descendants(view:View):List<View> = listOf(view) + if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
    private fun layout(view:View,width:Int,height:Int){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)}
    private fun fixture(widthDp:Int,heightDp:Int) {
        MobileCameoTheme.entries.forEach { theme ->
            val controller=Robolectric.buildActivity(Activity::class.java).setup()
            try {
                val activity=controller.get();val calls=mutableListOf<String>();var selected="home"
                val destinations=listOf("home","movie","series","anime","library","search","settings").map{key->ConceptDestination(key,key){calls+=key;selected=key}}
                val renderer=MobileCameoRenderer(activity,theme);val shell=renderer.shell(destinations){selected}
                val movie=MediaItemData("tt1234567","movie","The Last Horizon",year="2026",genres=listOf("Sci-Fi"),runtime="2h 08m",
                    poster="https://public.example/poster.jpg",background="https://public.example/backdrop.jpg")
                val movies=listOf(movie,movie.copy(imdbId="tt1234568",title="Second"),movie.copy(imdbId="tt1234569",title="Third"))
                val episode=PlaybackIdentity("tt7654321","series","Show",2,3)
                val recent=PlaybackStore.Recent("fixture",episode.title,episode,60_000,600_000)
                val details=mutableListOf<MediaItemData>();val saves=mutableListOf<MediaItemData>();val images=mutableListOf<String>();val resumes=mutableListOf<PlaybackStore.Recent>()
                val callbacks=ConceptHomeCallbacks({_,address->images+=address},{details+=it},{},{resumes+=it},{},{_,_->},{})
                val saved=listOf(recent,recent.copy(key="fixture-two",title="Second saved",identity=episode.copy(imdbId="tt7654322")))
                renderer.home(shell.body,ConceptHomeData(movies,emptyList(),saved,listOf(episode)),callbacks){saves+=it}
                activity.setContentView(shell.root)
                val density=activity.resources.displayMetrics.density;val width=(widthDp*density).roundToInt();val height=(heightDp*density).roundToInt()
                layout(shell.root,width,height)
                assertEquals(listOf("home","movie","anime","library","search"),shell.navigationViews.keys.toList())
                shell.navigationViews.forEach{(key,cell)->
                    assertTrue("${theme.title} $key width",cell.width>=48*density)
                    assertTrue("${theme.title} $key height",cell.height>=48*density)
                    val bounds=Rect();cell.getDrawingRect(bounds);shell.root.offsetDescendantRectToMyCoords(cell,bounds)
                    assertTrue("${theme.title} $key on screen",Rect(0,0,width,height).contains(bounds))
                    assertTrue(cell.performClick())
                }
                assertEquals(listOf("home","movie","anime","library","search"),calls)
                assertEquals(listOf("search"),shell.navigationViews.filterValues{it.isSelected}.keys.toList())
                val views=descendants(shell.root).filterIsInstance<TextView>()
                listOf("For you","Movies","Shows").forEach{name->
                    val category=views.single{it.text.toString()==name}
                    assertTrue("${theme.title} $name touch height must be at least48dp",category.height>=48*density)
                    val rect=Rect();category.getDrawingRect(rect);shell.root.offsetDescendantRectToMyCoords(category,rect)
                    assertTrue("${theme.title} $name complete category",rect.left>=0&&rect.right<=width)
                }
                views.single{it.text.toString()=="Explore"}.performClick()
                views.single{it.text.toString()=="+ Library"}.performClick()
                assertEquals(listOf(movie),details);assertEquals(listOf(movie),saves)
                assertTrue(shell.scroller.top>=0&&shell.scroller.bottom<=height)
                val immersive=theme in setOf(MobileCameoTheme.GLASS,MobileCameoTheme.CINEMA,MobileCameoTheme.AURORA)
                assertEquals(if(immersive)View.VISIBLE else View.GONE,shell.heroBackdrop.visibility)
                assertSame(shell.root,shell.heroBackdrop.parent)
                assertSame(shell.heroBackdrop,shell.backdropArt.parent)
                assertNotNull(shell.root.findViewWithTag<View>(if(immersive)"mobile:hero:overlay"else"mobile:hero:framed"))
                assertTrue(images.contains(movie.background));assertFalse(images.any{it.contains("theme_previews")||it.contains("concepts-2026")})
                listOf("mobile:poster:","mobile:resume:").forEach {prefix->
                    val cards=descendants(shell.root).filter{(it.tag as? String)?.startsWith(prefix)==true}.take(2)
                    assertEquals(2,cards.size)
                    cards.forEach{card->val rect=Rect();card.getDrawingRect(rect);shell.root.offsetDescendantRectToMyCoords(card,rect)
                        assertTrue("${theme.title} two complete $prefix cards",rect.left>=0&&rect.right<=width)}
                }
                descendants(shell.root).first{(it.tag as? String)?.startsWith("mobile:resume:")==true}.performClick()
                assertEquals(listOf(recent),resumes)
                views.single{it.text.toString()=="Movies"}.performClick()
                assertTrue(views.single{it.text.toString()=="Movies"}.isSelected)
                assertFalse(views.single{it.text.toString()=="For you"}.isSelected)
                assertEquals(listOf("movie"),shell.navigationViews.filterValues{it.isSelected}.keys.toList())
                shell.root.findViewWithTag<View>("mobile:settings").performClick();assertEquals("settings",calls.last())
            } finally {controller.pause().stop().destroy()}
        }
    }
    @Test fun `all five mobile themes have usable portrait actions and bottom navigation`() {fixture(390,844)}
    @Test @Config(qualifiers="w320dp-h640dp-port-mdpi") fun `compact phones retain full touch controls`() {fixture(320,640)}
    @Test @Config(qualifiers="w800dp-h1280dp-port-mdpi") fun `tablet portrait keeps navigation in viewport`() {fixture(800,1280)}
    @Test @Config(qualifiers="w390dp-h891dp-port-mdpi") fun `tall phone reference keeps native theme geometry and controls`() {fixture(390,891)}
    @Test @Config(qualifiers="w360dp-h820dp-port-mdpi") fun `narrow foldable cover viewport keeps complete touch controls`() {fixture(360,820)}
    @Test @Config(qualifiers="w720dp-h780dp-port-mdpi") fun `unfolded foldable viewport keeps live content and touch navigation`() {fixture(720,780)}
    @Test fun `edge to edge insets clear bars and cutouts without double padding legacy content`() {
        val window=Rect(0,0,390,891);val bars=Rect(0,28,0,24)
        assertEquals(bars,MobileCameoInsets.overlap(window,window,bars))
        assertEquals(Rect(),MobileCameoInsets.overlap(Rect(0,28,390,867),window,bars))
        assertEquals(Rect(8,28,12,24),MobileCameoInsets.overlap(window,window,Rect(8,28,12,24)))
        assertEquals(Rect(0,12,0,0),MobileCameoInsets.overlap(Rect(8,16,378,867),window,Rect(8,28,12,24)))
    }
}
