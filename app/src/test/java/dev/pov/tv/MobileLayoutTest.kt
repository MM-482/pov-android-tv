package dev.pov.tv

import android.app.Activity
import android.content.Context
import android.graphics.Insets
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
import org.robolectric.util.ReflectionHelpers

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

    @Test fun `five bottom controls stay anchored across touch widths themes insets and scrolling`() {
        val expectedKeys = listOf("home", "movie", "anime", "library", "search")
        val expectedLabels = listOf("Home", "Browse", "Anime", "Library", "Search")
        val safe = Rect(6, 24, 10, 28)
        try {
            for (width in listOf(360, 600, 720, 840, 1024)) {
                RuntimeEnvironment.setQualifiers("w${width}dp-h800dp-${if (width < 800) "port" else "land"}-mdpi")
                val context: Context = RuntimeEnvironment.getApplication()
                for (theme in ThemeSpec.selectableThemes) {
                    val case = "${theme.displayName} ${width}dp"
                    val clicked = mutableListOf<String>()
                    val destinations = linkedMapOf("home" to "Home", "movie" to "Movies", "series" to "Shows", "anime" to "Anime",
                        "library" to "Library", "search" to "Search", "settings" to "Settings", "trakt" to "Trakt")
                        .map { (key, label) -> ConceptDestination(key, label) { clicked += key } }
                    val shell = MobileShellRenderer(context, theme).render(destinations) { "home" }
                    shell.body.addView(View(context), LinearLayout.LayoutParams(-1, 1800))
                    shell.root.dispatchApplyWindowInsets(systemInsets(safe))
                    assertEquals("$case safe left inset", safe.left, shell.root.paddingLeft)
                    assertEquals("$case safe top inset", safe.top, shell.root.paddingTop)
                    assertEquals("$case safe right inset", safe.right, shell.root.paddingRight)
                    assertEquals("$case safe bottom inset", safe.bottom, shell.root.paddingBottom)
                    measureShell(shell.root, width, 800)
                    assertEquals("$case uses the same touch shell", "mobile-shell:bottom", shell.root.tag)
                    val nav = shell.root.findViewWithTag<LinearLayout>("mobile:navigation")
                    assertEquals("$case has one horizontal row", LinearLayout.HORIZONTAL, nav.orientation)
                    assertEquals("$case retains exactly five primary destinations", 5, nav.childCount)
                    assertEquals("$case destination order", expectedKeys.map { "nav:$it" }, (0 until nav.childCount).map { nav.getChildAt(it).tag })
                    assertEquals("$case labels remain unchanged", expectedLabels, (0 until nav.childCount).flatMap { labels(nav.getChildAt(it)) })
                    assertSame("$case Shows keeps the Browse destination", shell.navigationViews["movie"], shell.navigationViews["series"])
                    val navBounds = bounds(shell.root, nav)
                    assertTrue("$case bottom bar is below the content", navBounds.top >= 800 - safe.bottom - 90)
                    assertTrue("$case bar stays above the system navigation area", navBounds.bottom <= 800 - safe.bottom)
                    assertTrue("$case bar is anchored to the safe bottom", navBounds.bottom >= 800 - safe.bottom - 8)
                    assertTrue("$case bar clears side insets", navBounds.left >= safe.left && navBounds.right <= width - safe.right)
                    assertTrue("$case scrolling content clears the bar", bounds(shell.root, shell.scroller).bottom <= navBounds.top)
                    expectedKeys.forEach { key ->
                        val button = requireNotNull(shell.navigationViews[key])
                        assertTrue("$case $key has a 48dp touch width", button.width >= 48)
                        assertTrue("$case $key has a 48dp touch height", button.height >= 48)
                        assertTrue("$case $key stays inside the bottom bar", navBounds.contains(bounds(shell.root, button)))
                        assertTrue("$case $key activates", button.performClick())
                    }
                    assertEquals("$case callbacks retain their destinations", expectedKeys, clicked)
                    shell.scroller.scrollTo(0, 420)
                    assertTrue("$case content can scroll", shell.scroller.scrollY > 0)
                    assertEquals("$case scrolling keeps the menu fixed", navBounds, bounds(shell.root, nav))
                    MobileShellRenderer.applyContentInsets(shell.scroller, theme, true)
                    measureShell(shell.root, width, 800)
                    assertEquals("$case Home keeps the menu fixed", navBounds, bounds(shell.root, nav))
                    assertTrue("$case Home content clears the bar", bounds(shell.root, shell.scroller).bottom <= navBounds.top)
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers("w360dp-h800dp-port-mdpi")
        }
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
    fun `unfolded-sized window keeps bottom navigation and a wider feature composition`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val destinations = listOf(ConceptDestination("home", "Home") {})
        val shell = MobileShellRenderer(context, ThemeSpec.ATELIER).render(destinations) { "home" }
        assertEquals("mobile-shell:bottom", shell.root.tag)
        val margins = shell.scroller.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(16, margins.leftMargin)
        assertEquals(16, margins.rightMargin)
        assertEquals(72, margins.bottomMargin)
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

    private fun labels(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun bounds(root: ViewGroup, view: View) = Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
    private fun measureShell(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }
    @Suppress("DEPRECATION")
    private fun systemInsets(safe: Rect): WindowInsets = if (Build.VERSION.SDK_INT >= 30) {
        WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(safe.left, safe.top, safe.right, safe.bottom))
            .setVisible(WindowInsets.Type.systemBars(), true).build()
    } else ReflectionHelpers.callConstructor(WindowInsets::class.java,
        ReflectionHelpers.ClassParameter.from(Rect::class.java, safe))
}
