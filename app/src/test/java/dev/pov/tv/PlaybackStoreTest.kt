package dev.pov.tv

import android.content.Context
import dev.pov.tv.data.PlaybackIdentity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val movie = PlaybackIdentity("tt1234567", "movie", "A Movie")

    @Before fun clearPreferences() {
        check(context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit())
    }

    @Test fun `successful older pause never acknowledges a newer completed event`() {
        val history = PlaybackStore(context)
        val pauseRevision = history.queue(movie, 25.0, "pause")
        val stopRevision = history.queue(movie, 100.0, "stop")
        assertNotEquals(pauseRevision, stopRevision)
        history.acknowledge(movie, pauseRevision)
        assertEquals(stopRevision, history.pending().single().revision)
        assertEquals("stop", history.pending().single().action)
        assertEquals(100.0, history.pending().single().percent, 0.001)
        history.acknowledge(movie, stopRevision)
        assertTrue(history.pending().isEmpty())
    }

    @Test fun `queue revision and acknowledgment work across separate activity stores`() {
        val main = PlaybackStore(context)
        val player = PlaybackStore(context)
        val old = main.queue(movie, 20.0, "pause")
        val recent = player.queue(movie.copy(title = "Updated title"), 80.0, "pause")
        main.acknowledge(movie, old)
        assertEquals(recent, player.pending().single().revision)
        main.acknowledge(movie, recent)
        assertTrue(player.pending().isEmpty())
    }

    @Test fun `concurrent stores preserve independent title updates`() {
        val first = PlaybackStore(context)
        val second = PlaybackStore(context)
        val executor = Executors.newFixedThreadPool(2)
        val begin = CountDownLatch(1)
        try {
            val writes = (1..16).map { index -> executor.submit {
                check(begin.await(10, TimeUnit.SECONDS))
                (if (index % 2 == 0) first else second).queue(movie.copy(imdbId = "tt${1000000 + index}"), index.toDouble(), "pause")
            } }
            begin.countDown()
            writes.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals(16, first.pending().size)
            assertEquals(16, first.pending().map { it.identity.imdbId }.distinct().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `legacy pending entries receive a stable persisted revision`() {
        val legacy = JSONArray().put(JSONObject().put("identity", movie.toJson()).put("percent", 44.0).put("action", "pause"))
        check(context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().putString("pending", legacy.toString()).commit())
        val first = PlaybackStore(context)
        val revision = first.pending().single().revision
        assertTrue(revision.isNotBlank())
        val reopened = PlaybackStore(context)
        assertEquals(revision, reopened.pending().single().revision)
        reopened.acknowledge(movie, revision)
        assertTrue(first.pending().isEmpty())
    }

    @Test fun `disconnect cleanup removes unsent progress but preserves local resume`() {
        val history = PlaybackStore(context)
        history.save("movie", movie.title, movie, 90_000, 900_000)
        history.queue(movie, 10.0, "pause")
        history.clearPending()
        assertTrue(history.pending().isEmpty())
        assertEquals(90_000L, history.resume("movie"))
    }

    @Test fun `dismissed title stays hidden when active playback saves another position`() {
        val history = PlaybackStore(context)
        history.save("movie", movie.title, movie, 90_000, 900_000)
        history.hideFromContinueWatching("movie", movie)
        PlaybackStore(context).save("movie", movie.title, movie, 120_000, 900_000)
        assertTrue(history.continueWatching().isEmpty())
        assertEquals(120_000L, history.resume("movie"))
    }

    @Test fun `hiding a title preserves Trakt queue and local resume`() {
        val history = PlaybackStore(context)
        history.save("movie", movie.title, movie, 90_000, 900_000)
        history.queue(movie, 10.0, "pause")
        history.hideFromContinueWatching("movie", movie)
        assertEquals(1, history.pending().size)
        assertEquals(90_000L, history.resume("movie"))
        assertTrue(history.isHidden(identity = movie.copy(title = "New title")))
    }

    @Test fun `new intentional play restores dismissed title across store instances`() {
        val history = PlaybackStore(context)
        history.save("movie", movie.title, movie, 90_000, 900_000)
        history.hideFromContinueWatching("movie", movie)
        PlaybackStore(context).beginPlayback("movie", movie)
        assertFalse(history.isHidden("movie", movie))
        assertEquals(1, history.continueWatching().size)
    }

    @Test fun `episode dismissal is scoped to one episode and works for remote entries`() {
        val history = PlaybackStore(context)
        val episode = movie.copy(type = "series", season = 1, episode = 2)
        history.hideFromContinueWatching("", episode)
        assertTrue(PlaybackStore(context).isHidden(identity = episode))
        assertFalse(history.isHidden(identity = episode.copy(episode = 3)))
        assertFalse(history.isHidden(identity = movie))
    }

    @Test fun `completed titles never enter Continue Watching`() {
        val history = PlaybackStore(context)
        history.save("done", movie.title, movie, 899_000, 900_000)
        history.save("invalid", movie.title, movie, -1, 900_000)
        assertTrue(history.continueWatching().isEmpty())
    }
}
