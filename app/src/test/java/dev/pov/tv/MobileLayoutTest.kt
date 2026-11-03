package dev.pov.tv

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.pov.tv.data.MediaItemData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w360dp-h800dp-port-mdpi")
class MobileLayoutTest {
    @Test fun `narrow home has five reachable touch destinations and vertically scrolls real content`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val clicked = mutableListOf<String>()
        val destinations = listOf("home", "movie", "series", "anime", "library", "search", "settings").map {
            ConceptDestination(it, it) { clicked += it }
        }
        val shell = MobileShellRenderer(activity, ThemeSpec.GLASS).render(destinations) { "home" }
        activity.setContentView(shell.root)
        val movies = (1..12).map { MediaItemData("tt${1000000 + it}", "movie", "Movie $it", description = "A short feature description.") }
        MobileHomeRenderer(activity, ThemeSpec.GLASS).render(shell.body, shell.heroBackdrop, shell.backdropArt,
            ConceptHomeData(movies, emptyList(), emptyList()), ConceptHomeCallbacks(
                loadImage = { _, _ -> }, details = {}, play = {}, resume = {}, browse = {}, bindContinue = { _, _ -> }, episode = {}))
        shell.root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        shell.root.layout(0, 0, 360, 800)
        assertEquals("mobile-shell:bottom", shell.root.tag)
        assertTrue(shell.body.measuredHeight > shell.scroller.measuredHeight)
        val primary = shell.body.findViewWithTag<View>("mobile:hero:primary")
        assertNotNull(primary)
        assertTrue(primary.measuredHeight >= 48)
        listOf("home", "movie", "series", "anime", "library").forEach { key ->
            val button = requireNotNull(shell.navigationViews[key])
            assertTrue(button.measuredHeight >= 48)
            assertTrue(button.measuredWidth >= 48)
            button.performClick()
        }
        assertEquals(listOf("home", "movie", "series", "anime", "library"), clicked)
        activity.finish()
    }

    @Test @Config(qualifiers = "w720dp-h690dp-land-mdpi")
    fun `unfolded-sized window uses rail without expanding content to a TV canvas`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val destinations = listOf(ConceptDestination("home", "Home") {})
        val shell = MobileShellRenderer(context, ThemeSpec.ATELIER).render(destinations) { "home" }
        assertEquals("mobile-shell:rail", shell.root.tag)
        val margins = shell.scroller.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(104, margins.leftMargin)
        assertEquals(16, margins.rightMargin)
        assertEquals(0, margins.bottomMargin)
    }

    @Test fun `source choices retain readable text and activate on the first touch-sized target`() {
        val context: Context = RuntimeEnvironment.getApplication()
        var chosen = false
        val list = TvChoiceList(context)
        val row = TvChoiceRow(context, "source:fixture", "A long movie filename with quality and release details",
            "2160p · 12 GB · Synthetic provider", "Available source") { chosen = true }
        list.addChoice(row)
        list.measure(View.MeasureSpec.makeMeasureSpec(328, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        list.layout(0, 0, 328, list.measuredHeight)
        assertTrue(row.measuredHeight >= 88)
        assertFalse(row.isFocusableInTouchMode)
        fun labels(view: View): List<TextView> = if (view is TextView) listOf(view)
            else if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()
        assertTrue(labels(row).all { it.textSize >= 12f })
        row.performClick()
        assertTrue(chosen)
    }
}
