package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameoManualTrackersTest {
    private class Memory : ProviderCredentials {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key].orEmpty()
        override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
    }
    private data class Request(val url: String, val method: String, val headers: Map<String, String>, val body: String?)
    private class Http(private val answer: (Request) -> String) : CameoServiceTransport {
        val calls = mutableListOf<Request>()
        override fun request(url: String, method: String, headers: Map<String, String>, body: String?, contentType: String): String {
            val request = Request(url, method, headers, body); calls += request; return answer(request)
        }
    }
    private val movie = PlaybackIdentity("tt1234567", "movie", "Movie")
    @Test fun `all desktop connection kinds are named independent saved account specifications`() {
        val kinds = CameoConnections.SPECS.map { it.kind }
        assertEquals(22, kinds.size); assertEquals(kinds.size, kinds.distinct().size)
        assertTrue(kinds.containsAll(listOf("trakt", "simkl", "mdblist", "opensubtitles", "subdl", "subsource")))
    }
    @Test fun `manual Trakt accounts route each explicit update to its selected account without changing legacy auth`() {
        val memory = Memory().apply { values["trakt.access_token"] = "legacy-oauth-token"; values["trakt.account_revision"] = "legacy-revision" }
        val http = Http { request -> if (URI(request.url).path == "/users/settings") """{"user":{"username":"Viewer"}}"""
            else """{"added":{"movies":1},"not_found":{"movies":[]}}""" }
        val facade = CameoConnections(memory, http)
        val first = facade.save(CameoConnection(kind = "trakt", name = "First", token = "first-token", clientId = "app-one"))
        val second = facade.save(CameoConnection(kind = "trakt", name = "Second", token = "second-token", clientId = "app-two"))
        assertNotEquals(first.id, second.id)
        facade.updateWatchlist(first.id, movie, true)
        assertEquals("Bearer first-token", http.calls.last().headers["Authorization"])
        assertEquals("app-one", http.calls.last().headers["trakt-api-key"])
        facade.updateWatchlist(second.id, movie, true)
        assertEquals("Bearer second-token", http.calls.last().headers["Authorization"])
        assertEquals("app-two", http.calls.last().headers["trakt-api-key"])
        assertEquals("legacy-oauth-token", memory.values["trakt.access_token"])
        assertEquals("legacy-revision", memory.values["trakt.account_revision"])
    }
    @Test fun `explicit watched removal carries only the exact selected show episode`() {
        val http = Http { request -> if (request.url.endsWith("/users/settings")) """{"user":{"username":"Viewer"}}"""
            else """{"deleted":{"episodes":1},"not_found":{"shows":[]}}""" }
        val facade = CameoConnections(Memory(), http)
        val c = facade.save(CameoConnection(kind = "trakt", token = "token", clientId = "app"))
        facade.updateWatched(c.id, PlaybackIdentity("tt7654321", "series", "Show", 2, 7), false)
        val call = http.calls.last()
        assertEquals("/sync/history/remove", URI(call.url).path)
        val show = JSONObject(call.body!!).getJSONArray("shows").getJSONObject(0)
        assertEquals("tt7654321", show.getJSONObject("ids").getString("imdb"))
        val season = show.getJSONArray("seasons").getJSONObject(0)
        assertEquals(2, season.getInt("number")); assertEquals(7, season.getJSONArray("episodes").getJSONObject(0).getInt("number"))
        val before = http.calls.size
        try { facade.updateWatched(c.id, PlaybackIdentity("tt7654321", "series", "Show"), true); fail("Guessed episode") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, http.calls.size)
    }
    @Test fun `Simkl watchlist removal cannot silently remove watched history and exact anime ID is retained`() {
        val http = Http { request -> if (URI(request.url).path == "/users/settings") """{"user":{"name":"Viewer"}}"""
            else """{"added":{"anime":1}}""" }
        val facade = CameoConnections(Memory(), http)
        val c = facade.save(CameoConnection(kind = "simkl", token = "token", clientId = "app"))
        val before = http.calls.size
        try { facade.updateWatchlist(c.id, movie, false); fail("Destructive watchlist removal") } catch (_: IllegalArgumentException) { }
        assertEquals(before, http.calls.size)
        facade.updateWatchlist(c.id, PlaybackIdentity("kitsu:42", "series", "Anime"), true)
        val payload = JSONObject(http.calls.last().body!!).getJSONArray("anime").getJSONObject(0)
        assertEquals(42L, payload.getJSONObject("ids").getLong("kitsu")); assertFalse(payload.getJSONObject("ids").has("imdb"))
        assertEquals("plantowatch", payload.getString("to"))
    }
    @Test fun `MDBList API key and OAuth token remain distinct authentication modes`() {
        val http = Http { request -> if (URI(request.url).path == "/user") """{"username":"Viewer","user_id":1}"""
            else """{"added":{"movies":1}}""" }
        val facade = CameoConnections(Memory(), http)
        val key = facade.save(CameoConnection(kind = "mdblist", apiKey = "personal-key"))
        val token = facade.save(CameoConnection(kind = "mdblist", token = "oauth-token"))
        facade.updateWatchlist(key.id, movie, true)
        assertTrue(http.calls.last().url.contains("apikey=personal-key")); assertFalse(http.calls.last().headers.containsKey("Authorization"))
        facade.updateWatchlist(token.id, movie, true)
        assertFalse(http.calls.last().url.contains("apikey=")); assertEquals("Bearer oauth-token", http.calls.last().headers["Authorization"])
        val before = http.calls.size
        try { facade.save(CameoConnection(kind = "mdblist", token = "token", apiKey = "key")); fail("Ambiguous auth accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, http.calls.size)
    }
    @Test fun `tracker browse returns metadata under the original account and cannot resolve playback`() {
        val http = Http { request -> if (URI(request.url).path == "/users/settings") """{"user":{"username":"Viewer"}}"""
            else """[{"movie":{"title":"Movie","ids":{"imdb":"tt1234567"},"year":2020}}]""" }
        val facade = CameoConnections(Memory(), http)
        val c = facade.save(CameoConnection(kind = "trakt", token = "token", clientId = "app"))
        assertEquals(listOf("watchlist", "watched"), facade.browse(c.id).map { it.id })
        val item = facade.browse(c.id, "watchlist").single()
        assertEquals(c.id, item.connectionId); assertEquals(movie, item.identity)
        try { facade.resolve(c.id, item); fail("Tracker metadata treated as a video") } catch (_: IllegalStateException) { }
    }
    @Test fun `disconnect during explicit tracker response prevents stale confirmation without recreating an account`() {
        lateinit var facade: CameoConnections
        var id = ""
        val http = Http { request -> if (URI(request.url).path == "/users/settings") """{"user":{"username":"Viewer"}}"""
            else { facade.remove(id); """{"added":{"movies":1}}""" } }
        facade = CameoConnections(Memory(), http)
        id = facade.save(CameoConnection(kind = "trakt", token = "original", clientId = "app")).id
        try { facade.updateWatchlist(id, movie, true); fail("Stale response accepted") } catch (_: IllegalStateException) { }
        assertTrue(facade.list().isEmpty())
        assertEquals("Bearer original", http.calls.last().headers["Authorization"])
    }
}
