package dev.pov.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.TextView
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.Locale

/** Historical internal layout regressions; these legacy compositions are no longer TV choices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConceptThemeRenderTest {
    private val concepts = listOf(ThemeSpec.GLASS, ThemeSpec.ESSENTIAL, ThemeSpec.PREMIERE, ThemeSpec.ATELIER, ThemeSpec.HORIZON, ThemeSpec.STUDIO)
    private val nav = linkedMapOf("home" to "Home", "movie" to "Movies", "series" to "TV Shows", "anime" to "Anime",
        "library" to "Library", "search" to "Search", "settings" to "Settings")
    private val nextEpisode = PlaybackIdentity("tt0903747", "series", "Series Fixture", 3, 7)
    private val otherEpisode = PlaybackIdentity("tt0944947", "series", "Other Series Fixture", 2, 4)

    @Test fun `all six native home compositions keep feature actions title cards and footer inside viewport`() {
        assertTrue("Legacy layout definitions must not be offered as current TV choices", concepts.none { it in ThemeSpec.selectableThemes })
        concepts.forEach { theme -> fixture(theme).use { screen -> assertVisibleComposition(screen) } }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `all six legacy Cameo wordmarks show the full name on one line without navigation overlap`() {
        assertWordmarksFit()
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `smaller televisions keep all six legacy wordmarks complete and unclipped`() {
        assertWordmarksFit()
    }

    private fun assertWordmarksFit() {
        concepts.forEach { theme -> fixture(theme).use { screen ->
            val wordmark = screen.target("concept:wordmark") as TextView
            assertInside(screen, wordmark)
            assertEquals("${theme.id} full brand", "CAMEO", wordmark.text.toString())
            assertEquals("${theme.id} single brand line", 1, wordmark.lineCount)
            val layout = requireNotNull(wordmark.layout)
            assertEquals("${theme.id} final letter included", 5, layout.getLineEnd(0))
            assertEquals("${theme.id} no omitted brand letters", 0, layout.getEllipsisCount(0))
            assertTrue("${theme.id} no horizontal brand clipping", layout.getLineWidth(0) <= wordmark.width - wordmark.compoundPaddingLeft - wordmark.compoundPaddingRight + 1f)
            assertTrue("${theme.id} no vertical brand clipping: layout=${layout.height}, view=${wordmark.height}, paddingTop=${wordmark.compoundPaddingTop}, paddingBottom=${wordmark.compoundPaddingBottom}, textSize=${wordmark.textSize}, fontMetrics=${wordmark.paint.fontMetricsInt}", layout.height <= wordmark.height - wordmark.compoundPaddingTop - wordmark.compoundPaddingBottom)
            val brandBounds = bounds(screen, wordmark)
            screen.shell.navigationViews.values.forEach { cell ->
                assertFalse("${theme.id} brand must not overlap ${cell.contentDescription}", Rect.intersects(brandBounds, bounds(screen, cell)))
            }
            val profile = requireNotNull(theme.conceptProfile)
            if (profile.railWidth > 0) {
                val scale = screen.shell.root.width / 960f
                val railBounds = Rect((profile.railX * scale).toInt(), (profile.railY * scale).toInt(),
                    ((profile.railX + profile.railWidth) * scale).toInt() + 1, ((profile.railY + profile.railHeight) * scale).toInt() + 1)
                assertTrue("${theme.id} brand stays inside its rail", railBounds.contains(brandBounds))
            }
        } }
    }

    @Test
    @Config(qualifiers = "w800dp-h450dp-land-mdpi")
    fun `smaller sixteen by nine TVs retain usable native geometry for all six concepts`() {
        concepts.forEach { theme -> fixture(theme).use { screen ->
            assertEquals(800, screen.shell.root.width)
            assertEquals(450, screen.shell.root.height)
            assertVisibleComposition(screen)
        } }
    }

    @Test fun `all seven navigation controls are visible focusable traversable and invoke their own destinations`() {
        concepts.forEach { theme -> fixture(theme).use { screen ->
            assertEquals(nav.keys.toList(), screen.shell.navigationViews.keys.toList())
            val cells = screen.shell.navigationViews.values.toList()
            val direction = if (theme in setOf(ThemeSpec.ATELIER, ThemeSpec.HORIZON)) View.FOCUS_RIGHT else View.FOCUS_DOWN
            cells.forEachIndexed { index, cell ->
                assertInside(screen, cell)
                assertTrue("${theme.id} ${cell.contentDescription} focusable", cell.isFocusable && cell.isClickable)
                assertTrue("${theme.id} native focus request", cell.requestFocus())
                assertSame(cell, screen.shell.root.findFocus())
                assertNotNull("${theme.id} visible focus treatment", cell.foreground)
                if (index < cells.lastIndex) assertSame("${theme.id} adjacent navigation", cells[index + 1],
                    FocusFinder.getInstance().findNextFocus(screen.shell.root, cell, direction))
                assertTrue(cell.performClick())
            }
            assertEquals(nav.keys.toList(), screen.navigationCalls)
        } }
    }

    @Test fun `watching viewport contains four complete cards and retains later focusable entries`() {
        concepts.filter { it != ThemeSpec.STUDIO }.forEach { theme -> fixture(theme, movieCount = 6).use { screen ->
            val scroll = descendants(screen.shell.root).filterIsInstance<HorizontalScrollView>().single()
            val cards = descendants(scroll).filter { (it.tag as? String)?.startsWith("concept:resume:") == true }
            assertEquals(6, cards.size)
            assertEquals(Rect(0, 0, scroll.width, scroll.height), scroll.clipBounds)
            val viewport = bounds(screen, scroll)
            cards.take(4).forEach { assertTrue("${theme.id} complete initial card", viewport.contains(bounds(screen, it))) }
            assertTrue("${theme.id} fifth card must start beyond the viewport", bounds(screen, cards[4]).left >= viewport.right)
            assertTrue(cards[4].isFocusable && cards[4].isClickable)
            assertTrue(cards[4].requestFocus())
            assertTrue(cards[4].performClick())
            assertSame(screen.data.recent[4], screen.resumeCalls.single())
        } }
    }

    @Test fun `native play details and resume controls preserve the supplied movie and exact saved episode`() {
        concepts.forEach { theme ->
            fixture(theme, recent = emptyList()).use { screen ->
                screen.target("concept:hero:play").performClick()
                screen.target("concept:hero:details").performClick()
                assertSame(screen.data.movies.first(), screen.playCalls.single())
                assertSame(screen.data.movies.first(), screen.detailCalls.single())
                assertTrue(screen.resumeCalls.isEmpty())
            }
            val saved = PlaybackStore.Recent("owned-fixture://episode-key", nextEpisode.title, nextEpisode, 120_000, 600_000)
            fixture(theme, recent = listOf(saved)).use { screen ->
                val resume = screen.target("concept:hero:play")
                resume.performClick()
                assertSame(saved, screen.resumeCalls.single())
                assertEquals(nextEpisode, screen.resumeCalls.single().identity)
                assertTrue(screen.playCalls.isEmpty())
                assertTrue(resume.performLongClick())
                assertSame(saved, screen.longPressCalls.single())
            }
        }
    }

    @Test fun `Studio up next and browse tiles preserve exact episode and destination callbacks`() {
        fixture(ThemeSpec.STUDIO, recent = emptyList()).use { screen ->
            val tile = screen.target("concept:episode:${nextEpisode.imdbId}:${nextEpisode.season}:${nextEpisode.episode}")
            assertInside(screen, tile)
            assertTrue(tile.isFocusable)
            tile.performClick()
            assertSame(nextEpisode, screen.episodeCalls.single())
            assertTrue(screen.playCalls.isEmpty())
            listOf("movie", "series", "anime", "library").forEach { key ->
                val browse = screen.target("concept:browse:$key")
                assertInside(screen, browse)
                assertTrue(browse.requestFocus())
                assertNotNull(browse.foreground)
                browse.performClick()
            }
            assertEquals(listOf("movie", "series", "anime", "library"), screen.browseCalls)
        }
    }

    @Test fun `card artwork stays inside its focus bounds and captions follow each concept placement`() {
        concepts.filter { it != ThemeSpec.STUDIO }.forEach { theme -> fixture(theme).use { screen ->
            val card = descendants(screen.shell.root).filterIsInstance<FrameLayout>().first {
                (it.tag as? String)?.startsWith("concept:resume:") == true }
            val artwork = card.getChildAt(0)
            assertTrue("${theme.id} artwork must not spill below its focus bounds", artwork.bottom <= card.height)
            assertTrue("${theme.id} artwork stays within the card width", artwork.left >= 0 && artwork.right <= card.width)
            val caption = descendants(card).filterIsInstance<TextView>().first {
                it.text.toString().equals(screen.data.movies.first().title, true) }
            if (theme in setOf(ThemeSpec.GLASS, ThemeSpec.ATELIER)) {
                assertTrue("${theme.id} external caption", caption.top >= artwork.bottom)
            } else {
                assertTrue("${theme.id} internal caption", caption.top < artwork.bottom && caption.bottom <= artwork.bottom)
            }
        } }
    }

    @Test fun `long real titles fit their native text slot without obscuring primary action or next section`() {
        val title = "The Remarkably Long Feature Title That Must Never Cover the Resume Button or the Watching Cards"
        concepts.forEach { theme -> fixture(theme, featureTitle = title).use { screen ->
            val primary = screen.target("concept:hero:play")
            val heading = screen.featureTitle()
            assertInside(screen, heading); assertInside(screen, primary)
            assertTrue("${theme.id} primary action text is legible", (primary as TextView).text.toString().contains("Resume"))
            assertTrue("${theme.id} title stays above action", bounds(screen, heading).bottom <= bounds(screen, primary).top)
            assertNotNull(heading.layout)
            assertTrue("${theme.id} title layout not vertically clipped", heading.layout.height <= heading.height - heading.paddingTop - heading.paddingBottom)
            assertTrue(heading.lineCount in 1..2)
            assertTrue(primary.performClick())
            assertSame(screen.data.recent.first(), screen.resumeCalls.single())
        } }
    }

    @Test fun `home uses live native controls and supplied artwork rather than concept screenshot backgrounds`() {
        concepts.forEach { theme -> fixture(theme, recent = emptyList()).use { screen ->
            val allowedArt = (screen.data.movies + screen.data.shows).flatMap { listOf(it.poster, it.background) }.filter(String::isNotBlank).toSet()
            assertTrue(screen.imageRequests.isNotEmpty())
            assertTrue(screen.imageRequests.values.all { it in allowedArt })
            assertFalse(screen.imageRequests.values.any { it.contains("theme_previews") || it.contains("concepts-") })
            assertFalse(screen.shell.root.background is BitmapDrawable)
            val images = descendants(screen.shell.root).filterIsInstance<ImageView>().filter { it.isShown && it.contentDescription != "POV" }
            assertTrue("${theme.id} no untracked screenshot ImageView", images.all { it in screen.imageRequests })
            val texts = descendants(screen.shell.root).filterIsInstance<TextView>().map { it.text.toString() }
            assertFalse(texts.any { it.contains("AFTERLIGHT") })
            assertFalse(texts.any { it.contains("m left") })
            assertTrue(texts.any { it == screen.data.movies.first().title.uppercase(Locale.getDefault()) })
            assertTrue(screen.target("concept:hero:play").isClickable)
        } }
    }

    private fun assertVisibleComposition(screen: Screen) {
        assertInside(screen, screen.target("concept:hero:play"))
        assertInside(screen, screen.target("concept:hero:details"))
        assertInside(screen, screen.featureTitle())
        val footer = descendants(screen.shell.root).filterIsInstance<TextView>().single { it.text.toString() == "Standalone app made by Mark Mateo" }
        assertInside(screen, footer)
        val cards = if (screen.theme == ThemeSpec.STUDIO) descendants(screen.shell.root).filter {
            (it.tag as? String)?.startsWith("concept:browse:") == true
        } else descendants(screen.shell.root).filter { (it.tag as? String)?.startsWith("concept:resume:") == true }.take(4)
        assertEquals("${screen.theme.id} four live home cards", 4, cards.size)
        cards.forEach { assertInside(screen, it); assertTrue(it.isFocusable && it.isClickable) }
    }

    private fun fixture(theme: ThemeSpec, recent: List<PlaybackStore.Recent>? = null, featureTitle: String = "Luminous Fixture", movieCount: Int = 4): Screen {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val owner = controller.get().apply { setTheme(R.style.AppTheme) }
        owner.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        val navigation = mutableListOf<String>()
        val shell = ConceptShellRenderer(owner, theme).render(nav.map { (key, label) -> ConceptDestination(key, label) { navigation += key } }) { "home" }
        val movies = (0 until movieCount).map { index -> MediaItemData("tt${1_000_001 + index}", "movie", if (index == 0) featureTitle else "Movie Fixture $index",
            "2026", background = "https://fixture.invalid/art/movie-$index.jpg", description = "An owned offline description for a real native text slot.", genres = listOf("Drama"), runtime = "108m") }
        val shows = listOf(nextEpisode, otherEpisode).mapIndexed { index, identity -> MediaItemData(identity.imdbId, "series", identity.title,
            background = "https://fixture.invalid/art/show-$index.jpg") }
        val saved = recent ?: movies.mapIndexed { index, movie -> PlaybackStore.Recent("owned-fixture://resume-key-$index", movie.title,
            PlaybackIdentity(movie.imdbId, "movie", movie.title), 120_000 + index * 1000L, 600_000) }
        val data = ConceptHomeData(movies, shows, saved, listOf(nextEpisode, otherEpisode))
        val screen = Screen(controller, theme, shell, data, navigation)
        val callbacks = ConceptHomeCallbacks(
            loadImage = { view, address -> screen.imageRequests[view] = address; view.setImageDrawable(ColorDrawable(Color.DKGRAY)) },
            details = { screen.detailCalls += it }, play = { screen.playCalls += it }, resume = { screen.resumeCalls += it },
            browse = { screen.browseCalls += it }, episode = { screen.episodeCalls += it },
            bindContinue = { view, value -> view.setOnLongClickListener { screen.longPressCalls += value; true } })
        ConceptHomeRenderer(owner, theme).render(shell.body, shell.heroBackdrop, shell.backdropArt, data, callbacks)
        owner.setContentView(shell.root)
        shadowOf(Looper.getMainLooper()).idle()
        val width = owner.resources.displayMetrics.widthPixels
        val height = owner.resources.displayMetrics.heightPixels
        repeat(2) {
            shell.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            shell.root.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return screen
    }

    private inner class Screen(val controller: ActivityController<Activity>, val theme: ThemeSpec, val shell: ConceptShellViews,
        val data: ConceptHomeData, val navigationCalls: MutableList<String>) : AutoCloseable {
        val imageRequests = linkedMapOf<ImageView, String>()
        val playCalls = mutableListOf<MediaItemData>()
        val detailCalls = mutableListOf<MediaItemData>()
        val resumeCalls = mutableListOf<PlaybackStore.Recent>()
        val longPressCalls = mutableListOf<PlaybackStore.Recent>()
        val episodeCalls = mutableListOf<PlaybackIdentity>()
        val browseCalls = mutableListOf<String>()
        fun target(tag: String): View = descendants(shell.root).single { it.tag == tag }
        fun featureTitle(): TextView {
            val title = data.recent.firstOrNull()?.identity?.title ?: data.movies.first().title
            return descendants(shell.root).filterIsInstance<TextView>().first { it.text.toString() == title.uppercase(Locale.getDefault()) }
        }
        override fun close() { controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun bounds(screen: Screen, view: View): Rect = Rect(0, 0, view.width, view.height).also { screen.shell.root.offsetDescendantRectToMyCoords(view, it) }
    private fun assertInside(screen: Screen, view: View) {
        val rect = bounds(screen, view)
        assertTrue("${screen.theme.id} ${view.tag ?: view.contentDescription} has dimensions", view.width > 0 && view.height > 0)
        assertTrue("${screen.theme.id} ${view.tag ?: view.contentDescription} $rect inside ${screen.shell.root.width}x${screen.shell.root.height}",
            rect.left >= -1 && rect.top >= -1 && rect.right <= screen.shell.root.width + 1 && rect.bottom <= screen.shell.root.height + 1)
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
