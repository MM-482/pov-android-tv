package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MdbListApiTest {
    private class Credentials(token: String = "test-key", revision: String = "revision") : TrackerCredentials {
        private val values = mutableMapOf("mdblist.token" to token, "mdblist.account_revision" to revision)
        @Synchronized override fun get(key: String, default: String) = values[key] ?: default
        @Synchronized override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
    }
    private val movie = PlaybackIdentity("tt1234567", "movie", "Movie")
    private val episode = PlaybackIdentity("tt7654321", "series", "Show", 2, 7)

    @Test fun `API key setup verifies user before atomic persistence and rejected key preserves account`() {
        val credentials = Credentials()
        val api = MdbListApi(credentials, { url, _, _ ->
            assertTrue(url.startsWith("https://api.mdblist.com/user?apikey="))
            if (url.contains("new-key")) NetReply("""{"user_id":4,"username":"example"}""") else throw ApiException(401, "Authorization failed.")
        })
        assertTrue(api.connectApiKey("new-key"))
        val revision = api.accountRevision()
        assertNotEquals("revision", revision)
        try { api.connectApiKey("bad-key"); fail("Expected rejected key") } catch (_: ApiException) { }
        assertEquals("new-key", credentials.get("mdblist.token"))
        assertEquals(revision, api.accountRevision())
    }

    @Test fun `late API key verification cannot undo disconnect`() {
        val credentials = Credentials()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val api = MdbListApi(credentials, { _, _, _ -> started.countDown(); check(finish.await(10, TimeUnit.SECONDS)); NetReply("""{"user_id":4,"username":"example"}""") })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { try { api.connectApiKey("candidate"); true } catch (_: IllegalStateException) { false } }
            check(started.await(10, TimeUnit.SECONDS)); api.disconnect(); finish.countDown()
            assertFalse(result.get(10, TimeUnit.SECONDS)); assertFalse(api.connected())
        } finally { finish.countDown(); worker.shutdownNow() }
    }

    @Test fun `raw array list pages use header cursor without losing later titles`() {
        val calls = mutableListOf<String>()
        val api = MdbListApi(Credentials("pagination-key"), { url, _, _ ->
            calls.add(url)
            if (url.contains("cursor=")) {
                assertTrue(url.contains("cursor=opaque%2Bnext"))
                NetReply("""[{"title":"Second","mediatype":"show","imdb_id":"tt7654321","release_year":2024}]""", mapOf("X-Has-More" to "false"))
            } else NetReply("""[{"title":"First","mediatype":"movie","ids":{"imdb":"tt1234567"}}]""",
                mapOf("X-Has-More" to "true", "X-Next-Cursor" to "opaque+next"))
        })
        val result = api.listItems("123", "own")
        assertEquals(listOf("First", "Second"), result.map { it.title })
        assertEquals("series", result[1].type)
        assertEquals(2, calls.size)
    }

    @Test fun `incomplete cursor never silently returns truncated watchlist`() {
        val api = MdbListApi(Credentials("missing-cursor"), { _, _, _ -> NetReply("""[{"title":"One","mediatype":"movie","imdb_id":"tt1234567"}]""", mapOf("x-has-more" to "true")) })
        try { api.watchlist("movie"); fail("Expected incomplete pagination failure") } catch (error: ApiException) {
            assertTrue(error.message.orEmpty().contains("pagination"))
        }
    }

    @Test fun `repeated cursor is rejected instead of repeatedly loading the same page`() {
        var count = 0
        val api = MdbListApi(Credentials("repeated-cursor"), { _, _, _ -> count++; NetReply("""{"items":[{"title":"One","mediatype":"movie","imdb_id":"tt1234567"}],"pagination":{"has_more":true,"next_cursor":"same"}}""") })
        try { api.listItems("123", "external"); fail("Expected repeated-cursor failure") } catch (_: ApiException) { }
        assertEquals(2, count)
    }

    @Test fun `own liked and external lists use their documented shapes and liked offset pages`() {
        val api = MdbListApi(Credentials("list-kinds"), { url, _, _ -> when {
            url.contains("/external/lists/user") -> NetReply("""[{"id":4,"name":"External"}]""")
            url.contains("/lists/user") -> NetReply("""[{"id":1,"name":"Owned"}]""")
            url.contains("offset=1") -> NetReply("""{"lists":[{"id":3,"name":"Liked second"}],"pagination":{"has_more":false}}""")
            else -> NetReply("""{"lists":[{"id":2,"name":"Liked first"}],"pagination":{"has_more":true}}""")
        } })
        val lists = api.lists()
        assertEquals(listOf("own", "liked", "liked", "external"), lists.map { it.kind })
        assertEquals(listOf("1", "2", "3", "4"), lists.map { it.id })
    }

    @Test fun `episode watched payload identifies only chosen episode with show IMDb ID`() {
        val api = MdbListApi(Credentials("watched-key"), { url, method, body ->
            assertTrue(url.contains("/sync/watched?")); assertEquals("POST", method)
            val show = body!!.getJSONArray("shows").getJSONObject(0)
            assertEquals("tt7654321", show.getJSONObject("ids").getString("imdb"))
            val season = show.getJSONArray("seasons").getJSONObject(0)
            assertEquals(2, season.getInt("number"))
            assertEquals(7, season.getJSONArray("episodes").getJSONObject(0).getInt("number"))
            NetReply("""{"updated":{"movies":0,"episodes":1},"not_found":{"movies":0,"shows":0}}""")
        })
        api.markWatched(episode)
    }

    @Test fun `watchlist root series payload and scalar counts follow current schema`() {
        val api = MdbListApi(Credentials("watchlist-write"), { url, _, body ->
            assertTrue(url.contains("/watchlist/items/add?"))
            val show = body!!.getJSONArray("shows").getJSONObject(0)
            assertEquals("tt7654321", show.getJSONObject("ids").getString("imdb"))
            assertFalse(show.has("seasons"))
            NetReply("""{"added":1,"existing":0,"not_found":0}""")
        })
        api.setWatchlist(episode.copy(season = 0, episode = 0), true)
    }

    @Test fun `stale revision skips request and exact episode scrobble uses nested server schema`() {
        var calls = 0
        val api = MdbListApi(Credentials("scrobble-key"), { url, _, body ->
            calls++; assertTrue(url.contains("/scrobble/pause?"))
            val show = body!!.getJSONObject("show")
            assertEquals("tt7654321", show.getJSONObject("ids").getString("imdb"))
            assertEquals(2, show.getJSONObject("season").getInt("number"))
            assertEquals(7, show.getJSONObject("season").getJSONObject("episode").getInt("number"))
            NetReply("""{"id":1,"action":"pause","progress":34.5}""")
        })
        assertFalse(api.progressForAccount(episode, 34.5, "pause", "old"))
        assertEquals(0, calls)
        assertTrue(api.progressForAccount(episode, 34.5, "pause", "revision"))
        assertEquals(1, calls)
    }

    @Test fun `server rate limit blocks immediate retry and remains scoped to the key`() {
        var calls = 0
        val api = MdbListApi(Credentials("rate-key"), { _, _, _ -> calls++; throw ApiException(429, "Rate limited.", retryAfterSeconds = 40) }, { 1000L })
        repeat(2) { try { api.progressForAccount(movie, 20.0, "pause", "revision"); fail("Expected rate limit") } catch (error: ApiException) { assertEquals(429, error.code) } }
        assertEquals(1, calls)
        val other = MdbListApi(Credentials("different-key"), { _, _, _ -> NetReply("""{"id":1,"action":"pause","progress":20}""") }, { 1000L })
        assertTrue(other.progressForAccount(movie, 20.0, "pause", "revision"))
    }
}
