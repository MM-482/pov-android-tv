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
import org.robolectric.annotation.LooperMode
import java.util.Locale

/** Exercises real native shell/home trees; image loading and all navigation/playback calls are fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConceptThemeRenderTest {
    private val selectable = listOf(ThemeSpec.GLASS, ThemeSpec.PREMIERE, ThemeSpec.ESSENTIAL, ThemeSpec.ATELIER, ThemeSpec.HORIZON)
    private val concepts = selectable + ThemeSpec.STUDIO
    private val nav = linkedMapOf("home" to "Home", "movie" to "Movies", "series" to "TV Shows", "anime" to "Anime",
        "library" to "Library", "search" to "Search", "settings" to "Settings")
    private val nextEpisode = PlaybackIdentity("tt0903747", "series", "Series Fixture", 3, 7)
    private val otherEpisode = PlaybackIdentity("tt0944947", "series", "Other Series Fixture", 2, 4)

    @Test fun `all six native home compositions keep feature actions title cards and footer inside viewport`() {
        assertEquals(selectable, ThemeSpec.selectableThemes)
        concepts.forEach { theme -> fixture(theme).use { screen -> assertVisibleComposition(screen) } }
    }

    @Test
    @Config(qualifiers = "w800dp-h450dp-land-television-mdpi")
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
            val direction = if (theme.conceptProfile!!.railWidth == 0) View.FOCUS_RIGHT else View.FOCUS_DOWN
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

    @Test fun `separate shelves show three saved cards and five discoveries while later entries retain native focus`() {
        selectable.forEach { theme -> fixture(theme, movieCount = 8).use { screen ->
            val watching = screen.target("concept:row:continue") as HorizontalScrollView
            val watchingCards = descendants(watching).filter { (it.tag as? String)?.startsWith("concept:resume:") == true }
            assertShelf(screen, watching, watchingCards, expectedCount = 8, visibleCount = 3)
            assertTrue(watchingCards[3].requestFocus())
            assertTrue(watchingCards[3].performClick())
            assertSame(screen.data.recent[3], screen.resumeCalls.single())

            val discover = screen.target("concept:row:discover") as HorizontalScrollView
            val discoveryCards = descendants(discover).filter { (it.tag as? String)?.startsWith("concept:media:") == true }
            assertShelf(screen, discover, discoveryCards, expectedCount = 9, visibleCount = 5)
            assertFalse(discoveryCards.any { it.tag == "concept:media:movie:${screen.data.movies.first().imdbId}" })
            assertTrue(discoveryCards[5].requestFocus())
            assertTrue(discoveryCards[5].performClick())
            assertSame(screen.data.movies[6], screen.detailCalls.single())
            assertTrue(screen.playCalls.isEmpty())
        } }
    }

    @Test fun `catalog hero explores details and saves to library without replacing exact saved resume entries`() {
        val saved = PlaybackStore.Recent("owned-fixture://episode-key", nextEpisode.title, nextEpisode, 120_000, 600_000)
        selectable.forEach { theme -> fixture(theme, recent = listOf(saved)).use { screen ->
            val feature = screen.data.movies.first()
            assertEquals(feature.title.uppercase(Locale.getDefault()), screen.featureTitle().text.toString())
            screen.target("concept:hero:explore").performClick()
            screen.target("concept:hero:library").performClick()
            assertSame(feature, screen.detailCalls.single())
            assertSame(feature, screen.libraryCalls.single())
            assertTrue(screen.resumeCalls.isEmpty())
            assertTrue(screen.playCalls.isEmpty())
            val resume = descendants(screen.target("concept:row:continue")).single {
                (it.tag as? String)?.startsWith("concept:resume:") == true }
            assertTrue(resume.performClick())
            assertSame(saved, screen.resumeCalls.single())
            assertEquals(nextEpisode, screen.resumeCalls.single().identity)
            assertTrue(resume.performLongClick())
            assertSame(saved, screen.longPressCalls.single())
        } }
    }

    @Test fun `fresh catalog homes retain explore and library actions with no artificial saved progress`() {
        selectable.forEach { theme -> fixture(theme, recent = emptyList()).use { screen ->
            assertFalse(descendants(screen.shell.root).any { it.tag == "concept:row:continue" })
            screen.target("concept:hero:explore").performClick()
            screen.target("concept:hero:library").performClick()
            assertSame(screen.data.movies.first(), screen.detailCalls.single())
            assertSame(screen.data.movies.first(), screen.libraryCalls.single())
            assertTrue(screen.resumeCalls.isEmpty())
            assertTrue(screen.playCalls.isEmpty())
            assertInside(screen, screen.target("concept:row:discover"))
        } }
    }

    @Test fun `saved only homes preserve resume and offer real catalog navigation`() {
        val saved = PlaybackStore.Recent("owned-fixture://saved-only", "A saved title", null, 120_000, 600_000)
        selectable.forEach { theme -> fixture(theme, recent = listOf(saved), movieCount = 0, includeShows = false).use { screen ->
            assertFalse(descendants(screen.shell.root).any { it.tag == "concept:hero:explore" || it.tag == "concept:row:discover" })
            val resume = descendants(screen.target("concept:row:continue")).single {
                (it.tag as? String)?.startsWith("concept:resume:") == true }
            assertTrue(resume.contentDescription.toString().contains(saved.title))
            resume.performClick()
            assertSame(saved, screen.resumeCalls.single())
            val browse = descendants(screen.shell.root).filterIsInstance<TextView>().single { it.text.toString() == "Browse movies" }
            assertInside(screen, browse)
            browse.performClick()
            assertEquals(listOf("movie"), screen.browseCalls)
        } }
    }

    @Test fun `unknown duration completed and invalid progress never become resume cards`() {
        val invalid = listOf(
            PlaybackStore.Recent("owned-fixture://unknown-duration", "Unknown", null, 0, 0),
            PlaybackStore.Recent("owned-fixture://completed", "Completed", nextEpisode, 600_000, 600_000),
            PlaybackStore.Recent("owned-fixture://negative", "Negative", nextEpisode, -1, 600_000))
        selectable.forEach { theme -> fixture(theme, recent = invalid).use { screen ->
            assertFalse(descendants(screen.shell.root).any { it.tag == "concept:row:continue" })
            assertInside(screen, screen.target("concept:row:discover"))
            assertEquals(screen.data.movies.first().title.uppercase(Locale.getDefault()), screen.featureTitle().text.toString())
        } }
    }

    @Test fun `legacy Studio retains play details and exact saved episode resume`() {
        fixture(ThemeSpec.STUDIO, recent = emptyList()).use { screen ->
            screen.target("concept:hero:play").performClick()
            screen.target("concept:hero:details").performClick()
            assertSame(screen.data.movies.first(), screen.playCalls.single())
            assertSame(screen.data.movies.first(), screen.detailCalls.single())
        }
        val saved = PlaybackStore.Recent("owned-fixture://studio-episode-key", nextEpisode.title, nextEpisode, 120_000, 600_000)
        fixture(ThemeSpec.STUDIO, recent = listOf(saved)).use { screen ->
            val resume = screen.target("concept:hero:play")
            resume.performClick()
            assertSame(saved, screen.resumeCalls.single())
            assertTrue(resume.performLongClick())
            assertSame(saved, screen.longPressCalls.single())
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

    @Test fun `card artwork and captions stay within native focus bounds across both shelves`() {
        selectable.forEach { theme -> fixture(theme).use { screen ->
            listOf("continue", "discover").forEach { key ->
                val card = descendants(screen.target("concept:row:$key")).filterIsInstance<FrameLayout>().first {
                    (it.tag as? String)?.let { tag -> tag.startsWith("concept:resume:") || tag.startsWith("concept:media:") } == true }
                val artwork = card.getChildAt(0)
                assertEquals(card.width, artwork.width)
                assertEquals(card.height, artwork.height)
                descendants(card).filterIsInstance<TextView>().forEach { caption ->
                    assertTrue("${theme.id} $key caption stays within art", caption.top >= 0 && caption.bottom <= artwork.bottom)
                    assertTrue("${theme.id} $key caption has bounded width", caption.left >= 0 && caption.right <= card.width)
                }
                assertTrue(card.contentDescription.toString().contains(if (key == "continue") "Resume" else "Explore"))
                assertTrue(card.requestFocus())
                assertNotNull(card.foreground)
            }
        } }
    }

    @Test fun `long real titles fit their native slot above primary actions and shelves`() {
        val title = "The Remarkably Long Feature Title That Must Never Cover the Explore Button or the Watching Cards"
        concepts.forEach { theme -> fixture(theme, featureTitle = title).use { screen ->
            val primary = screen.primaryAction() as TextView
            val heading = screen.featureTitle()
            assertInside(screen, heading); assertInside(screen, primary)
            assertEquals(if (theme == ThemeSpec.STUDIO) "▶  Resume" else "Explore", primary.text.toString())
            assertTrue("${theme.id} title stays above action", bounds(screen, heading).bottom <= bounds(screen, primary).top)
            assertNotNull(heading.layout)
            assertTrue("${theme.id} title layout not vertically clipped", heading.layout.height <= heading.height - heading.paddingTop - heading.paddingBottom)
            assertTrue(heading.lineCount in 1..2)
            assertTrue(primary.performClick())
            if (theme == ThemeSpec.STUDIO) assertSame(screen.data.recent.first(), screen.resumeCalls.single())
            else assertSame(screen.data.movies.first(), screen.detailCalls.single())
        } }
    }

    @Test fun `home preserves caller supplied mini player clearance`() {
        selectable.forEach { theme -> fixture(theme, bottomClearance = 184).use { screen ->
            assertEquals(184, screen.shell.body.paddingBottom)
            assertTrue(screen.shell.body.height >= screen.shell.root.height + 184)
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
            assertTrue(screen.primaryAction().isClickable)
        } }
    }

    private fun assertVisibleComposition(screen: Screen) {
        assertInside(screen, screen.primaryAction())
        assertInside(screen, screen.target(if (screen.theme == ThemeSpec.STUDIO) "concept:hero:details" else "concept:hero:library"))
        assertInside(screen, screen.featureTitle())
        assertInside(screen, screen.target("concept:credit"))
        if (screen.theme != ThemeSpec.STUDIO) {
            val wordmark = screen.target("concept:wordmark") as TextView
            assertInside(screen, wordmark)
            assertNotNull(wordmark.layout)
            assertEquals("${screen.theme.id} complete Cameo wordmark", 0, wordmark.layout.getEllipsisCount(0))
        }
        if (screen.theme == ThemeSpec.STUDIO) {
            val cards = descendants(screen.shell.root).filter { (it.tag as? String)?.startsWith("concept:browse:") == true }
            assertEquals(4, cards.size)
            cards.forEach { assertInside(screen, it); assertTrue(it.isFocusable && it.isClickable) }
        } else {
            listOf("continue" to 3, "discover" to 5).forEach { (key, count) ->
                assertInside(screen, screen.target("concept:heading:$key"))
                val row = screen.target("concept:row:$key")
                assertInside(screen, row)
                val cards = descendants(row).filter {
                    (it.tag as? String)?.let { tag -> tag.startsWith("concept:resume:") || tag.startsWith("concept:media:") } == true
                }.take(count)
                assertEquals("${screen.theme.id} $key visible count", count, cards.size)
                cards.forEach { assertInside(screen, it); assertTrue(it.isFocusable && it.isClickable) }
            }
        }
    }

    private fun assertShelf(screen: Screen, scroll: HorizontalScrollView, cards: List<View>, expectedCount: Int, visibleCount: Int) {
        assertEquals(expectedCount, cards.size)
        assertEquals(Rect(0, 0, scroll.width, scroll.height), scroll.clipBounds)
        val viewport = bounds(screen, scroll)
        cards.take(visibleCount).forEach { assertTrue("${screen.theme.id} complete initial card", viewport.contains(bounds(screen, it))) }
        assertTrue("${screen.theme.id} later cards start beyond viewport", bounds(screen, cards[visibleCount]).left >= viewport.right)
        assertTrue(cards[visibleCount].isFocusable && cards[visibleCount].isClickable)
        assertSame("${screen.theme.id} remote can reach next offscreen card", cards[visibleCount],
            FocusFinder.getInstance().findNextFocus(screen.shell.root, cards[visibleCount - 1], View.FOCUS_RIGHT))
    }

    private fun fixture(theme: ThemeSpec, recent: List<PlaybackStore.Recent>? = null, featureTitle: String = "Luminous Fixture", movieCount: Int = 6,
        bottomClearance: Int = 0, includeShows: Boolean = true): Screen {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val owner = controller.get().apply { setTheme(R.style.AppTheme) }
        owner.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        val navigation = mutableListOf<String>()
        val shell = ConceptShellRenderer(owner, theme).render(nav.map { (key, label) -> ConceptDestination(key, label) { navigation += key } }) { "home" }
        val movies = (0 until movieCount).map { index -> MediaItemData("tt${1_000_001 + index}", "movie", if (index == 0) featureTitle else "Movie Fixture $index",
            "2026", background = "https://fixture.invalid/art/movie-$index.jpg", description = "An owned offline description for a real native text slot.", genres = listOf("Drama"), runtime = "108m") }
        val shows = (if (includeShows) listOf(nextEpisode, otherEpisode) else emptyList()).mapIndexed { index, identity -> MediaItemData(identity.imdbId, "series", identity.title,
            background = "https://fixture.invalid/art/show-$index.jpg") }
        val saved = recent ?: movies.mapIndexed { index, movie -> PlaybackStore.Recent("owned-fixture://resume-key-$index", movie.title,
            PlaybackIdentity(movie.imdbId, "movie", movie.title), 120_000 + index * 1000L, 600_000) }
        val data = ConceptHomeData(movies, shows, saved, listOf(nextEpisode, otherEpisode))
        val screen = Screen(controller, theme, shell, data, navigation)
        shell.body.setPadding(0, 0, 0, bottomClearance)
        val callbacks = ConceptHomeCallbacks(
            loadImage = { view, address -> screen.imageRequests[view] = address; view.setImageDrawable(ColorDrawable(Color.DKGRAY)) },
            details = { screen.detailCalls += it }, play = { screen.playCalls += it }, resume = { screen.resumeCalls += it },
            browse = { screen.browseCalls += it }, episode = { screen.episodeCalls += it }, saveToLibrary = { screen.libraryCalls += it },
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
        val libraryCalls = mutableListOf<MediaItemData>()
        val resumeCalls = mutableListOf<PlaybackStore.Recent>()
        val longPressCalls = mutableListOf<PlaybackStore.Recent>()
        val episodeCalls = mutableListOf<PlaybackIdentity>()
        val browseCalls = mutableListOf<String>()
        fun target(tag: String): View = descendants(shell.root).single { it.tag == tag }
        fun primaryAction(): View = target(if (theme == ThemeSpec.STUDIO) "concept:hero:play" else "concept:hero:explore")
        fun featureTitle(): TextView {
            if (theme != ThemeSpec.STUDIO) return target("concept:hero:title") as TextView
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
