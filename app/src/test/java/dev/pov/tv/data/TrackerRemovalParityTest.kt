package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackerRemovalParityTest {
    private class TraktKeys : TraktCredentials {
        private val values = mutableMapOf("trakt.client_id" to "fixture-client", "trakt.access_token" to "fixture-access", "trakt.refresh_token" to "fixture-refresh",
            "trakt.expires_at" to "9999999999", "trakt.account_revision" to UUID.randomUUID().toString())
        override fun get(key: String, default: String) = values[key] ?: default
        override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
        override fun remove(key: String) { values.remove(key) }
    }
    private class Keys(initial: Map<String, String>) : TrackerCredentials {
        private val values = initial.toMutableMap()
        override fun get(key: String, default: String) = values[key] ?: default
        override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
    }
    private val movie = PlaybackIdentity("tt1234567", "movie", "Film")
    private val episode = PlaybackIdentity("tt7654321", "series", "Show", 2, 4)

    @Test fun `Trakt unwatched removes only requested episode and accepts confirmed already-absent state`() {
        val api = TraktApi(TraktKeys(), apiTransport = { url, method, _, body ->
            assertEquals("https://api.trakt.tv/sync/history/remove", url)
            assertEquals("POST", method)
            val season = body!!.getJSONArray("shows").getJSONObject(0).getJSONArray("seasons").getJSONObject(0)
            assertEquals(2, season.getInt("number"))
            assertEquals(4, season.getJSONArray("episodes").getJSONObject(0).getInt("number"))
            """{"deleted":{"episodes":0},"not_found":{"shows":[],"episodes":[]}}"""
        })
        api.setWatched(episode, false)
    }

    @Test fun `Trakt removal rejects unconfirmed unmatched and namespaced titles`() {
        var requests = 0
        val api = TraktApi(TraktKeys(), apiTransport = { _, _, _, _ -> requests++; """{"deleted":{"movies":0},"not_found":{"movies":[{"ids":{"imdb":"tt1234567"}}]}}""" })
        try { api.setWatched(movie, false); fail("Expected unmatched rejection") } catch (_: ApiException) { }
        try { api.setWatched(movie.copy(catalogNamespace = "private-addon"), false); fail("Expected namespace rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(1, requests)
    }

    @Test fun `MDBList unwatched preserves exact show episode and confirms deletion`() {
        val api = MdbListApi(Keys(mapOf("mdblist.token" to "fixture-key", "mdblist.account_revision" to UUID.randomUUID().toString())), { url, method, body ->
            assertTrue(url.contains("/sync/watched/remove?apikey="))
            assertEquals("POST", method)
            val season = body!!.getJSONArray("shows").getJSONObject(0).getJSONArray("seasons").getJSONObject(0)
            assertEquals(2, season.getInt("number"))
            assertEquals(4, season.getJSONArray("episodes").getJSONObject(0).getInt("number"))
            NetReply("""{"deleted":{"episodes":1}}""")
        })
        api.setWatched(episode, false)
    }

    @Test fun `MDBList empty response cannot claim watched removal succeeded`() {
        val api = MdbListApi(Keys(mapOf("mdblist.token" to "fixture-key", "mdblist.account_revision" to UUID.randomUUID().toString())), { _, _, _ -> NetReply("{}") })
        try { api.setWatched(movie, false); fail("Expected confirmation requirement") } catch (_: ApiException) { }
    }

    @Test fun `Simkl watched removal uses exact identity and confirmed deletion without changing scope`() {
        val api = SimklApi(simklKeys(), { url, method, headers, body ->
            assertTrue(url.contains("/sync/history/remove?"))
            assertEquals("POST", method)
            assertEquals("Bearer fixture-access", headers["Authorization"])
            assertEquals("tt1234567", body!!.getJSONArray("movies").getJSONObject(0).getJSONObject("ids").getString("imdb"))
            """{"deleted":{"movies":1},"not_found":{"movies":[]}}"""
        })
        api.setWatched(movie, false)
    }

    @Test fun `Simkl isolated watchlist removal cannot silently delete history`() {
        var requests = 0
        val api = SimklApi(simklKeys(), { _, _, _, _ -> requests++; "{}" })
        try { api.setWatchlist(MediaItemData(movie.imdbId, movie.type, movie.title), false); fail("Expected shared-history guard") } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("watched history"))
        }
        assertEquals(0, requests)
    }

    private fun simklKeys() = Keys(mapOf("simkl.client_id" to "fixture-client", "simkl.access_token" to "fixture-access", "simkl.refresh_token" to "fixture-refresh",
        "simkl.expires_at" to "9999999999", "simkl.refresh_expires_at" to "9999999999", "simkl.scope" to "media:read media:write", "simkl.account_revision" to UUID.randomUUID().toString()))
}
