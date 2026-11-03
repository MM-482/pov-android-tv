package dev.pov.tv

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
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
        assertSame(shell.navigationViews["movie"], shell.navigationViews["series"])
        val shows = requireNotNull(shell.navigationViews["category:series"])
        assertTrue(shows.measuredHeight >= 48)
        shows.performClick()
        listOf("home", "movie", "anime", "library", "search").forEach { key ->
            val button = requireNotNull(shell.navigationViews[key])
            assertTrue(button.measuredHeight >= 48)
            assertTrue(button.measuredWidth >= 48)
            button.performClick()
        }
        assertEquals(listOf("series", "home", "movie", "anime", "library", "search"), clicked)
        activity.finish()
    }

    @Test fun `all five themes explore the catalog feature and retain exact resume and hold identities`() {
        val featured = MediaItemData("tt100001", "movie", "The Last Horizon")
        val savedTitle = MediaItemData("tt100002", "movie", "Silent Tide")
        val discoveries = listOf(MediaItemData("tt100003", "movie", "Wild Atlas"), MediaItemData("tt100004", "movie", "City of Echoes"))
        val identity = PlaybackIdentity(savedTitle.imdbId, "movie", savedTitle.title, sourceId = "saved-source", addonId = "saved-addon")
        val recent = PlaybackStore.Recent("saved-playback-key", savedTitle.title, identity, 62000, 124000)
        for (theme in ThemeSpec.selectableThemes) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val shell = MobileShellRenderer(activity, theme).render(listOf(ConceptDestination("home", "Home") {})) { "home" }
            var explored: MediaItemData? = null
            var libraryItem: MediaItemData? = null
            var resumed: PlaybackStore.Recent? = null
            var held: PlaybackStore.Recent? = null
            MobileHomeRenderer(activity, theme).render(shell.body, shell.heroBackdrop, shell.backdropArt,
                ConceptHomeData(listOf(featured, savedTitle) + discoveries, emptyList(), listOf(recent)), ConceptHomeCallbacks(
                    loadImage = { _, _ -> }, details = { explored = it }, play = { fail("Home Explore must open details") },
                    resume = { resumed = it }, browse = {}, bindContinue = { view, saved ->
                        view.setOnLongClickListener { held = saved; true }
                    }, episode = {}, saveToLibrary = { libraryItem = it }))
            shell.body.findViewWithTag<View>("mobile:hero:primary").performClick()
            shell.body.findViewWithTag<View>("mobile:hero:library").performClick()
            assertSame(theme.displayName, featured, explored)
            assertSame(theme.displayName, featured, libraryItem)
            val row = shell.body.findViewWithTag<HorizontalScrollView>("mobile:continue-row").getChildAt(0) as ViewGroup
            row.getChildAt(0).performClick()
            row.getChildAt(0).performLongClick()
            assertSame(recent, resumed)
            assertSame(recent, held)
            val discover = shell.body.findViewWithTag<HorizontalScrollView>("mobile:discover-row").getChildAt(0) as ViewGroup
            assertEquals(2, discover.childCount)
            assertEquals("mobile:media:movie:tt100003", discover.getChildAt(0).tag)
            fun taggedActions(view: View): List<String> =
                (if (view.isClickable && view.tag is String) listOf(view.tag as String) else emptyList()) +
                    (if (view is ViewGroup) (0 until view.childCount).flatMap { taggedActions(view.getChildAt(it)) } else emptyList())
            val tags = taggedActions(shell.body)
            assertEquals("Home focus targets have unique identities", tags.size, tags.toSet().size)
            activity.finish()
        }
    }

    @Test fun `saved-only playback without catalog identity remains resumable without invalid catalog actions`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val recent = PlaybackStore.Recent("saved-url-key", "Saved local playback", null, 1000, 90000)
        val shell = MobileShellRenderer(context, ThemeSpec.ESSENTIAL).render(listOf(ConceptDestination("home", "Home") {})) { "home" }
        var resumed: PlaybackStore.Recent? = null
        MobileHomeRenderer(context, ThemeSpec.ESSENTIAL).render(shell.body, shell.heroBackdrop, shell.backdropArt,
            ConceptHomeData(emptyList(), emptyList(), listOf(recent)), ConceptHomeCallbacks(
                loadImage = { _, _ -> }, details = { fail("Unidentified playback has no details destination") }, play = {},
                resume = { resumed = it }, browse = {}, bindContinue = { _, _ -> }, episode = {},
                saveToLibrary = { fail("Unidentified playback has no catalog library identity") }))
        assertNull(shell.body.findViewWithTag<View>("mobile:hero:primary"))
        assertNull(shell.body.findViewWithTag<View>("mobile:hero:library"))
        val row = shell.body.findViewWithTag<HorizontalScrollView>("mobile:continue-row").getChildAt(0) as ViewGroup
        row.getChildAt(0).performClick()
        assertSame(recent, resumed)
    }

    @Test fun `browse selects Movies and Shows through one bottom destination and restores generic page insets`() {
        val context: Context = RuntimeEnvironment.getApplication()
        var selected = "home"
        val destinations = listOf("home", "movie", "series", "anime", "library", "search", "settings").map { key ->
            ConceptDestination(key, key) { selected = key }
        }
        val shell = MobileShellRenderer(context, ThemeSpec.GLASS).render(destinations) { selected }
        MobileShellRenderer.applyContentInsets(shell.scroller, ThemeSpec.GLASS, true)
        assertEquals(0, (shell.scroller.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
        shell.navigationViews.getValue("category:series").performClick()
        shell.navigationViews.values.forEach { it.onFocusChangeListener?.onFocusChange(it, false) }
        assertEquals("series", selected)
        assertTrue(shell.navigationViews.getValue("movie").isSelected)
        assertTrue(shell.navigationViews.getValue("category:series").isSelected)
        selected = "library"
        MobileShellRenderer.applyContentInsets(shell.scroller, ThemeSpec.GLASS, false)
        assertEquals(View.GONE, shell.root.findViewWithTag<View>("mobile:categories").visibility)
        assertEquals(64, (shell.scroller.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
        assertEquals(16, (shell.scroller.layoutParams as ViewGroup.MarginLayoutParams).leftMargin)
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
        val item = MediaItemData("tt100001", "movie", "A feature for a wider window")
        MobileHomeRenderer(context, ThemeSpec.ATELIER).render(shell.body, shell.heroBackdrop, shell.backdropArt,
            ConceptHomeData(listOf(item), emptyList(), emptyList()), ConceptHomeCallbacks(
                loadImage = { _, _ -> }, details = {}, play = {}, resume = {}, browse = {}, bindContinue = { _, _ -> }, episode = {}))
        shell.root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(690, View.MeasureSpec.EXACTLY))
        shell.root.layout(0, 0, 720, 690)
        assertNotNull(shell.body.findViewWithTag<View>("mobile:hero:composition:pearl:wide"))
        val artwork = shell.body.findViewWithTag<View>("mobile:hero:art")
        val title = shell.body.findViewWithTag<View>("mobile:hero:title")
        val artBounds = Rect(0, 0, artwork.width, artwork.height).also { shell.root.offsetDescendantRectToMyCoords(artwork, it) }
        val titleBounds = Rect(0, 0, title.width, title.height).also { shell.root.offsetDescendantRectToMyCoords(title, it) }
        assertTrue("Fold artwork sits beside feature copy", artBounds.left > titleBounds.left)
        assertTrue("Fold artwork has visible height", artwork.measuredHeight >= 200)
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
