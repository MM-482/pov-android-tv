package dev.pov.tv

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.TraktApi
import dev.pov.tv.data.TraktCredentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises Main's actual async loader and native renderer without onCreate, accounts, or HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class StudioUpNextIntegrationTest {
    private val expected = PlaybackIdentity("tt0903747", "series", "Series Fixture", 3, 7)
    private val movie = MediaItemData("tt1234567", "movie", "Movie Fixture")
    // Matching catalog data deliberately has no artwork URL, so Main cannot start an image request.
    private val show = MediaItemData(expected.imdbId, expected.type, expected.title)

    @Test fun `account replaced while Studio request waits in worker queue causes no HTTP or stale UI`() {
        fixture().use { screen ->
            screen.load()
            assertEquals(1, screen.worker.pending)
            screen.credentials.replaceAccount()
            screen.worker.runNextOffMain()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, screen.requests.size)
            screen.assertUnchanged()
        }
    }

    @Test fun `completed Up Next result cannot update a replacement account or a later page`() {
        for (change in listOf("account", "page")) fixture().use { screen ->
            screen.load()
            screen.worker.runNextOffMain()
            // The fake HTTP work completed, but the real Activity UI runnable is still queued.
            assertEquals(2, screen.requests.size)
            screen.assertUnchanged()
            if (change == "account") screen.credentials.replaceAccount()
            else {
                set(screen.activity, "generation", 8)
                set(screen.activity, "isHome", false)
            }
            shadowOf(Looper.getMainLooper()).idle()

            screen.assertUnchanged()
            assertEquals(2, screen.requests.size)
            assertTrue(descendants(screen.body).none { (it.tag as? String)?.startsWith("concept:episode:") == true })
        }
    }

    @Test fun `current account result renders exact Trakt episode in actual Studio views`() {
        fixture().use { screen ->
            screen.load()
            screen.worker.runNextOffMain()
            screen.assertUnchanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf(expected), field(screen.activity, "studioNextEpisodes"))
            assertTrue((field(screen.activity, "studioNextLoadedAt") as Long) > 0L)
            val tiles = descendants(screen.body).filter { it.tag == "concept:episode:${expected.imdbId}:3:7" }
            assertEquals(1, tiles.size)
            assertTrue(tiles.single().isFocusable && tiles.single().isClickable)
            assertEquals("${expected.title}. Season 3, episode 7", tiles.single().contentDescription.toString())
            val labels = descendants(screen.body).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.contains("Up Next"))
            assertTrue(labels.contains("S3 E7"))
            assertTrue(labels.any { it.equals(expected.title, ignoreCase = true) })
            assertEquals(listOf("/sync/watched/shows?extended=full", "/shows/37/progress/watched?hidden=false&specials=false"), screen.requests)
            assertEquals(0, screen.imageRequests)
        }
    }

    private fun fixture(): Fixture {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.AppTheme)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val marker = TextView(activity).apply { text = "Existing discovery remains usable" }
        body.addView(marker)
        val scroll = ScrollView(activity).apply { addView(body) }
        val root = FrameLayout(activity).apply { addView(scroll) }
        val backdrop = FrameLayout(activity).apply { addView(ImageView(activity)) }
        root.addView(backdrop)
        activity.setContentView(root)
        set(activity, "theme", ThemeSpec.STUDIO)
        set(activity, "body", body); set(activity, "scroller", scroll)
        set(activity, "heroBackdrop", backdrop); set(activity, "backdropArt", backdrop.getChildAt(0))
        set(activity, "focusController", TvFocusController(body, scroll))
        set(activity, "generation", 7); set(activity, "isHome", true)
        set(activity, "studioNextRevision", "synthetic-account-original")

        val fixture = Fixture(activity, body, marker)
        val api = TraktApi(fixture.credentials,
            authTransport = { _, _, _ -> throw AssertionError("Offline Studio test attempted authentication") },
            apiTransport = { address, method, _, json ->
                assertEquals("GET", method); assertNull(json)
                val path = address.removePrefix("https://api.trakt.tv")
                fixture.requests += path
                when (path) {
                    "/sync/watched/shows?extended=full" -> """[{"last_watched_at":"2026-10-25T12:00:00Z","show":{"title":"Series Fixture","ids":{"trakt":37,"imdb":"tt0903747"}}}]"""
                    "/shows/37/progress/watched?hidden=false&specials=false" -> """{"next_episode":{"season":3,"number":7}}"""
                    else -> throw AssertionError("Unexpected offline Trakt request")
                }
            })
        set(activity, "trakt", api)
        (field(activity, "worker") as ExecutorService).shutdownNow()
        set(activity, "worker", fixture.worker)
        (field(activity, "images") as ExecutorService).shutdownNow()
        set(activity, "images", object : QueuedWorker() {
            override fun execute(command: Runnable) {
                fixture.imageRequests++
                throw AssertionError("Offline Studio fixture must not request artwork")
            }
        })
        return fixture
    }

    private inner class Fixture(val activity: MainActivity, val body: LinearLayout, val marker: View) : AutoCloseable {
        val credentials = FakeTraktCredentials()
        val worker = QueuedWorker()
        val requests = mutableListOf<String>()
        var imageRequests = 0
        fun load() {
            MainActivity::class.java.getDeclaredMethod("loadStudioNext", Int::class.javaPrimitiveType,
                String::class.java, List::class.java, List::class.java, List::class.java)
                .apply { isAccessible = true }.invoke(activity, 7, "synthetic-account-original", listOf(movie), listOf(show), emptyList<PlaybackStore.Recent>())
        }
        fun assertUnchanged() {
            assertEquals(1, body.childCount)
            assertSame(marker, body.getChildAt(0))
            assertEquals(emptyList<PlaybackIdentity>(), field(activity, "studioNextEpisodes"))
            assertEquals(0L, field(activity, "studioNextLoadedAt"))
            assertEquals(0, imageRequests)
        }
        override fun close() {
            for (name in listOf("worker", "images", "authWorker")) (field(activity, name) as ExecutorService).shutdownNow()
            activity.finish()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class FakeTraktCredentials : TraktCredentials {
        private val values = ConcurrentHashMap(mapOf(
            "trakt.client_id" to "synthetic-studio-client", "trakt.access_token" to "synthetic-studio-access",
            "trakt.refresh_token" to "synthetic-studio-refresh", "trakt.expires_at" to "9999999999",
            "trakt.account_revision" to "synthetic-account-original"))
        override fun get(key: String, default: String): String = values[key] ?: default
        override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
        override fun remove(key: String) { values.remove(key) }
        fun replaceAccount() { putAll(mapOf("trakt.access_token" to "synthetic-replacement-access", "trakt.account_revision" to "synthetic-account-replacement")) }
    }

    /** Runs the production worker closure on a real non-UI thread, leaving UI posts controllable. */
    private open class QueuedWorker : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        val pending get() = tasks.size
        override fun execute(command: Runnable) { check(!stopped); tasks.addLast(command) }
        fun runNextOffMain() {
            val task = tasks.removeFirst()
            val failure = AtomicReference<Throwable?>()
            val thread = Thread({ try { task.run() } catch (error: Throwable) { failure.set(error) } }, "studio-offline-test")
            thread.start(); thread.join(3_000)
            check(!thread.isAlive) { "Offline Studio worker did not finish" }
            failure.get()?.let { throw AssertionError("Offline Studio worker failed", it) }
        }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun set(activity: MainActivity, name: String, value: Any) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
