package dev.pov.tv

import android.app.Activity
import android.graphics.Rect
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
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
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class CameoTvRendererTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun bounds(root: ViewGroup, view: View) = Rect().also { view.getDrawingRect(it); root.offsetDescendantRectToMyCoords(view, it) }
    private fun fixture(width: Int, height: Int) {
        ThemeSpec.cameoTvThemes.forEach { theme ->
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                val activity = controller.get(); var selected = "home"; val navigated = mutableListOf<String>()
                val targets = listOf("home", "movie", "series", "anime", "library", "search", "settings").map { key -> ConceptDestination(key, key) { selected = key; navigated += key } }
                val renderer = CameoTvRenderer(activity, theme); val shell = renderer.shell(targets) { selected }
                val movies = (1..7).map { MediaItemData("tt123456$it", "movie", if (it == 1) "The Last Horizon" else "Movie $it", "2026",
                    "https://public-fixture.example/$it.jpg", "https://public-fixture.example/$it-backdrop.jpg", genres = listOf("Sci-Fi"), runtime = "2h 08m") }
                val recent = movies.take(4).mapIndexed { index, item -> PlaybackStore.Recent("fixture:$index", item.title, PlaybackIdentity(item.imdbId, item.type, item.title), 120_000, 600_000) }
                val details = mutableListOf<MediaItemData>(); val saves = mutableListOf<MediaItemData>(); val resumed = mutableListOf<PlaybackStore.Recent>(); val images = mutableListOf<String>()
                renderer.home(shell.body, shell.heroBackdrop, shell.backdropArt, ConceptHomeData(movies, emptyList(), recent),
                    ConceptHomeCallbacks({ _, address -> images += address }, { details += it }, {}, { resumed += it }, {}, { _, _ -> }, {})) { saves += it }
                activity.setContentView(shell.root)
                val density = activity.resources.displayMetrics.density
                val screenWidth = (width * density).roundToInt(); val screenHeight = (height * density).roundToInt()
                shell.root.measure(View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.EXACTLY))
                shell.root.layout(0, 0, screenWidth, screenHeight)
                val viewport = Rect(0, 0, screenWidth, screenHeight)
                listOf("cameo-tv:hero-title", "cameo-tv:hero:explore", "cameo-tv:hero:save").forEach { tag ->
                    val view = shell.root.findViewWithTag<View>(tag); assertNotNull(tag, view); assertTrue("${theme.id} $tag fits", viewport.contains(bounds(shell.root, view)))
                }
                shell.root.findViewWithTag<View>("cameo-tv:hero:explore").performClick()
                shell.root.findViewWithTag<View>("cameo-tv:hero:save").performClick()
                assertEquals(listOf(movies.first()), details); assertEquals(listOf(movies.first()), saves)
                shell.navigationViews.values.forEachIndexed { index, view ->
                    assertTrue("${theme.id} navigation stays inside screen", viewport.contains(bounds(shell.root, view)))
                    assertTrue(view.isFocusable && view.isClickable); assertTrue(view.requestFocus()); assertNotNull(view.foreground)
                    val cells = shell.navigationViews.values.toList()
                    if (index < cells.lastIndex) assertSame("${theme.id} adjacent navigation", cells[index + 1], FocusFinder.getInstance().findNextFocus(shell.root, view,
                        if (theme.layout.navigation == NavigationPlacement.LEFT_RAIL) View.FOCUS_DOWN else View.FOCUS_RIGHT))
                    view.performClick()
                }
                assertEquals(targets.map { it.key }, navigated)
                val rows = descendants(shell.root).filterIsInstance<HorizontalScrollView>(); assertEquals(2, rows.size)
                listOf("cameo-tv:resume:" to 3, "cameo-tv:poster:" to 5).forEach { (prefix, visible) ->
                    val cards = descendants(shell.root).filter { (it.tag as? String)?.startsWith(prefix) == true }
                    assertEquals(if (visible == 3) 4 else 7, cards.size)
                    cards.take(visible).forEach { assertTrue("${theme.id} $visible complete initial cards", viewport.contains(bounds(shell.root, it))) }
                    val later = cards[visible]; assertTrue(later.isFocusable && later.isClickable)
                    val rowViewport = rows[if (visible == 3) 0 else 1]
                    assertTrue("${theme.id} later cards do not peek into the reference viewport", bounds(shell.root, later).left >= bounds(shell.root, rowViewport).right)
                }
                descendants(shell.root).first { (it.tag as? String)?.startsWith("cameo-tv:resume:") == true }.performClick()
                assertEquals(listOf(recent.first()), resumed)
                assertTrue(images.isNotEmpty()); assertFalse(images.any { it.contains("theme_previews") || it.contains("concepts-2026") })
                assertEquals(if (theme in listOf(ThemeSpec.CAMEO_GLASS, ThemeSpec.CAMEO_CINEMA)) View.VISIBLE else View.GONE, shell.heroBackdrop.visibility)
                ConceptShellRenderer.applyContentInsets(shell.scroller, theme, false)
                val margins = shell.scroller.layoutParams as android.widget.FrameLayout.LayoutParams
                assertEquals((requireNotNull(theme.conceptProfile).contentLeft * screenWidth / 960f).roundToInt(), margins.leftMargin)
            } finally { controller.pause().stop().destroy() }
        }
    }
    @Test fun `five Cameo TV themes keep live actions navigation and both reference rows usable`() { fixture(960, 540) }
    @Test @Config(qualifiers = "w800dp-h450dp-land-mdpi") fun `smaller TV viewports retain five complete posters and three resume cards`() { fixture(800, 450) }
    @Test fun `TV choices contain only the five Cameo themes and update only appearance preferences`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("pov_appearance", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().putString("unrelated", "keep").commit()
        ThemeSpec.cameoTvThemes.forEach { theme ->
            ThemePreferences(context).select(theme)
            assertEquals(theme, ThemePreferences(context).selectedConcept())
            context.assets.open(theme.previewAsset).use { assertTrue(it.available() > 0) }
        }
        assertEquals("keep", prefs.getString("unrelated", null))
        assertEquals(5, ThemeSpec.allSelectableThemes.distinct().size)
        assertEquals(ThemeSpec.cameoTvThemes, ThemeSpec.selectableThemes)
        assertEquals(ThemeSpec.cameoTvThemes, ThemeSpec.allSelectableThemes)
        assertTrue(ThemeSpec.cameoTvThemes.all { it.isConcept && it.isCameoTv })
    }
    @Test fun `fresh TV installs and removed dashboard selections use Cameo Glass`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("pov_appearance", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(ThemeSpec.CAMEO_GLASS, ThemePreferences(context).selectedCameoTv())
        assertEquals("cameo-glass", prefs.getString("theme", null))
        ThemePreferences(context).select(ThemeSpec.STUDIO)
        assertEquals(ThemeSpec.CAMEO_GLASS, ThemePreferences(context).selectedCameoTv())
        ThemePreferences(context).select(ThemeSpec.NEON)
        assertEquals(ThemeSpec.CAMEO_AURORA, ThemePreferences(context).selectedCameoTv())
    }
    @Test fun `Up Next sits below reference viewport and opens the exact supplied episode`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get(); val renderer = CameoTvRenderer(activity, ThemeSpec.CAMEO_GLASS)
            val shell = renderer.shell(emptyList()) { "home" }
            val episode = PlaybackIdentity("tt7654321", "series", "Exact Show", 4, 7)
            val selected = mutableListOf<PlaybackIdentity>()
            renderer.home(shell.body, shell.heroBackdrop, shell.backdropArt, ConceptHomeData(emptyList(), emptyList(), emptyList(), listOf(episode)),
                ConceptHomeCallbacks({ _, _ -> }, {}, {}, {}, {}, { _, _ -> }, { selected += it })) {}
            activity.setContentView(shell.root)
            shell.root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY)); shell.root.layout(0, 0, 960, 540)
            val group = shell.root.findViewWithTag<View>("cameo-tv:up-next")
            assertTrue(bounds(shell.root, group).top >= 540)
            val action = shell.root.findViewWithTag<View>("cameo-tv:next:tt7654321:4:7")
            assertTrue(action.isFocusable && action.isClickable); action.performClick()
            assertEquals(listOf(episode), selected)
        } finally { controller.pause().stop().destroy() }
    }
}
