package dev.pov.tv.data

import dev.pov.tv.ScopedPlaybackRequests
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DesktopServiceHubTest {
    // Public synthetic network fixtures; keep each tested address unchanged.
    private fun fixtureHost(vararg octets: Any): String = octets.joinToString(".")
    private class Memory : DesktopConnectionStore { var value = ""; override fun get() = value; override fun put(value: String) { this.value = value } }
    private data class Request(val url: String, val method: String, val headers: Map<String, String>, val body: String?, val contentType: String)
    private class Fake(private val answer: (Request) -> String) : DesktopTransport {
        val requests = mutableListOf<Request>()
        override fun request(url: String, method: String, headers: Map<String, String>, body: String?, contentType: String): String =
            Request(url, method, headers, body, contentType).let { requests += it; answer(it) }
    }
    private val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/media/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/media/film.mkv</d:href><d:propstat><d:prop><d:displayname>Film</d:displayname><d:getcontentlength>1234</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""

    @Test fun `new desktop services cover media servers and exact anime trackers`() {
        assertEquals(setOf("jellyfin", "emby", "plex", "webdav", "seedr", "anilist", "myanimelist", "kitsu"), DesktopServiceHub.SPECS.map { it.id }.toSet())
    }

    @Test fun `local HTTP is confined to private hosts and HTTPS supports remote servers`() {
        listOf("http://${fixtureHost(192, 168, 0, 10)}:8096/", "http://${fixtureHost(10, 1, 2, 3)}/", "http://${fixtureHost(172, 16, 5, 2)}/", "http://[fd12::1]/", "http://media.local/", "https://media.example/").forEach { DesktopUrlPolicy.address(it) }
        listOf("http://example.com/", "http://${fixtureHost(8, 8, 8, 8)}/", "http://${fixtureHost("010", 0, 0, 1)}/", "http://${fixtureHost(192, 168, "001", 1)}/", "http://127.1/", "http://2130706433/", "https://user:secret@example.com/", "https://media.example/a/../b", "https://media.example/a/%2e%2e/b", "https://media.example/a/%252e%252e/b", "https://media.example/a%2fb").forEach { address ->
            try { DesktopUrlPolicy.address(address); fail(address) } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `server paths cannot escape origin or configured root`() {
        val root = DesktopUrlPolicy.endpoint("https://media.example/base")
        assertEquals("https://media.example/base/Items", DesktopUrlPolicy.within(root, "Items").toString())
        listOf("https://other.example/base/file", "/sibling/file", "../file", "//other.example/file").forEach { path ->
            try { DesktopUrlPolicy.within(root, path); fail(path) } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `jellyfin login replaces password with verified token and user ID`() {
        val memory = Memory()
        val http = Fake { request ->
            assertEquals("https://media.example/Users/AuthenticateByName", request.url)
            assertEquals("POST", request.method)
            assertEquals("owner", JSONObject(request.body!!).getString("Username"))
            """{"AccessToken":"verified-test-token","User":{"Id":"user1","Name":"Owner"}}"""
        }
        val hub = DesktopServiceHub(memory, http)
        val result = hub.connect(DesktopConnectionInput("jellyfin", url = "https://media.example", username = "owner", password = "password-fixture"))
        assertEquals("Owner", result.accountName)
        val stored = org.json.JSONArray(memory.value).getJSONObject(0)
        assertEquals("", stored.getString("password"))
        assertEquals("user1", stored.getString("userId"))
        assertFalse(result.toString().contains("verified-test-token"))
    }

    @Test fun `server edition selection preserves exact media source and authorization in headers`() {
        val http = Fake { request -> when {
            request.url.endsWith("Users/Me") -> """{"Id":"user1","Name":"Owner"}"""
            request.url.contains("/Items?") -> """{"Items":[{"Id":"film1","Name":"Film","Type":"Movie","MediaSources":[{"Id":"edition1","Name":"Cinema"},{"Id":"edition2","Name":"Extended"}]}]}"""
            request.url.contains("/PlaybackInfo?") -> """{"MediaSources":[{"Id":"edition1"},{"Id":"edition2"}]}"""
            else -> error("Unexpected request")
        } }
        val hub = DesktopServiceHub(Memory(), http)
        val connection = hub.connect(DesktopConnectionInput("jellyfin", url = "https://media.example/", token = "test-token"))
        val files = hub.browse(connection.id, "folder1", "abc")
        assertEquals(2, files.size)
        assertTrue(http.requests.last().url.contains("ParentId=folder1"))
        assertTrue(http.requests.last().url.contains("SearchTerm=abc"))
        val playback = hub.playback(connection.id, files[1])
        assertTrue(playback.url.endsWith("Static=true&MediaSourceId=edition2"))
        assertFalse(playback.url.contains("test-token"))
        assertEquals("test-token", playback.headers["X-Emby-Token"])
        assertEquals(playback.headers, ScopedPlaybackRequests.normalizedHeaders(playback.headers))
        assertEquals(setOf("X-Emby-Token"), playback.headers.keys)
        try { hub.playback(connection.id, DesktopMediaEntry("film1", "Film")); fail("Expected exact edition requirement") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `disconnect while library request runs prevents stale results`() {
        lateinit var hub: DesktopServiceHub
        val http = Fake { request -> if (request.url.endsWith("Users/Me")) """{"Id":"u","Name":"Owner"}""" else {
            hub.connections().single().let { hub.disconnect(it.id) }; """{"Items":[]}"""
        } }
        hub = DesktopServiceHub(Memory(), http)
        val connection = hub.connect(DesktopConnectionInput("jellyfin", url = "https://media.example/", token = "fixture"))
        try { hub.browse(connection.id); fail("Expected stale session rejection") } catch (_: IllegalStateException) { }
        assertTrue(hub.connections().isEmpty())
    }

    @Test fun `failed credential replacement preserves saved account`() {
        var reject = false
        val hub = DesktopServiceHub(Memory(), Fake { if (reject) throw ApiException(401, "Unauthorized") else """{"Id":"u","Name":"Owner"}""" })
        val original = hub.connect(DesktopConnectionInput("jellyfin", url = "https://media.example", token = "working"))
        reject = true
        try { hub.connect(DesktopConnectionInput("jellyfin", url = "https://media.example", token = "bad", id = original.id)); fail("Expected failure") } catch (_: ApiException) { }
        assertEquals(listOf(original), hub.connections())
    }

    @Test fun `WebDAV folders parse XML and exclude the current collection`() {
        val hub = DesktopServiceHub(Memory(), Fake { xml })
        val connection = hub.connect(DesktopConnectionInput("webdav", url = "https://media.example/media", username = "owner", password = "fixture"))
        val file = hub.browse(connection.id).single()
        assertEquals("Film", file.name)
        assertEquals(1234L, file.size)
        assertFalse(file.directory)
        assertEquals("https://media.example/media/film.mkv", hub.playback(connection.id, file).url)
        assertEquals(hub.playback(connection.id, file).headers, ScopedPlaybackRequests.normalizedHeaders(hub.playback(connection.id, file).headers))
    }

    @Test fun `WebDAV ignores rejected properties foreign hosts and outside-root files`() {
        val bad = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/media/film.mkv</d:href><d:propstat><d:prop><d:displayname>Rejected</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat><d:propstat><d:prop><d:displayname>Accepted</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>https://other.example/media/file</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/media/temporary.mkv?signature=fixture</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val hub = DesktopServiceHub(Memory(), Fake { bad })
        val files = hub.parseDav(bad, URI("https://media.example/media/"), URI("https://media.example/media/"))
        assertEquals(1, files.size)
        assertEquals("Accepted", files[0].name)
        assertFalse(files[0].directory)
        try { hub.parseDav("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x/>", URI("https://media.example/"), URI("https://media.example/")); fail("Expected XML rejection") } catch (_: IllegalArgumentException) { }
        try { hub.parseDav("<html/>", URI("https://media.example/"), URI("https://media.example/")); fail("Expected DAV rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `Seedr uses the official WebDAV origin`() {
        val http = Fake { assertTrue(it.url.startsWith("https://dav.seedr.cc/")); xml.replace("/media/", "/") }
        val hub = DesktopServiceHub(Memory(), http)
        val connection = hub.connect(DesktopConnectionInput("seedr", url = "http://ignored.example/", username = "fixture", password = "fixture"))
        assertEquals("https://dav.seedr.cc/", connection.url)
    }

    @Test fun `AniList graph errors cannot be saved as verified connections`() {
        val hub = DesktopServiceHub(Memory(), Fake { """{"errors":[{"message":"private upstream body"}]}""" })
        try { hub.connect(DesktopConnectionInput("anilist", token = "fixture")); fail("Expected GraphQL rejection") } catch (error: ApiException) {
            assertFalse(error.message!!.contains("private upstream body"))
        }
        assertTrue(hub.connections().isEmpty())
    }

    @Test fun `MAL progress uses PATCH form with exact service identity and confirms response`() {
        val http = Fake { request -> if (request.url.endsWith("/@me")) """{"name":"Owner"}""" else {
            assertEquals("PATCH", request.method)
            assertTrue(request.body!!.contains("num_watched_episodes=12"))
            assertTrue(request.body.contains("status=completed"))
            assertTrue(request.body.contains("score=8"))
            """{"num_episodes_watched":12}"""
        } }
        val hub = DesktopServiceHub(Memory(), http)
        val connection = hub.connect(DesktopConnectionInput("myanimelist", token = "fixture"))
        hub.setAnimeProgress(connection.id, DesktopMediaEntry("mal:123", "Anime"), 12, "completed", 8)
        val count = http.requests.size
        try { hub.setAnimeProgress(connection.id, DesktopMediaEntry("anilist:123", "Anime"), 12, "completed"); fail("Expected mismatched service rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(count, http.requests.size)
    }

    @Test fun `Plex validates selected server without querying a different account host`() {
        val http = Fake { request -> assertEquals("https://media.example/library/sections", request.url); """{"MediaContainer":{"Directory":[]}}""" }
        val hub = DesktopServiceHub(Memory(), http)
        val connection = hub.connect(DesktopConnectionInput("plex", url = "https://media.example", token = "fixture"))
        assertEquals("Plex account", connection.accountName)
        assertEquals("fixture", http.requests.single().headers["X-Plex-Token"])
        assertEquals("Cameo", http.requests.single().headers["X-Plex-Product"])
        val playback = hub.playback(connection.id, DesktopMediaEntry("part", "Film", remotePath = "library/parts/1/file.mkv"))
        assertEquals(mapOf("X-Plex-Token" to "fixture"), playback.headers)
        assertEquals(playback.headers, ScopedPlaybackRequests.normalizedHeaders(playback.headers))
    }

    @Test fun `verification and label edit preserve revision but account replacement invalidates saved files`() {
        val http = Fake { """{"MediaContainer":{"Directory":[]}}""" }
        val hub = DesktopServiceHub(Memory(), http)
        val connection = hub.connect(DesktopConnectionInput("plex", url = "https://media.example", token = "old-fixture"))
        val originalRevision = hub.accountRevision(connection.id)
        hub.verify(connection.id)
        assertEquals(originalRevision, hub.accountRevision(connection.id))
        hub.connect(DesktopConnectionInput("plex", label = "Renamed", id = connection.id))
        assertEquals(originalRevision, hub.accountRevision(connection.id))
        hub.connect(DesktopConnectionInput("plex", label = "Renamed", token = "replacement-fixture", id = connection.id))
        assertNotEquals(originalRevision, hub.accountRevision(connection.id))
        val count = http.requests.size
        try { hub.playback(connection.id, DesktopMediaEntry("file", "Film", remotePath = "library/parts/1/file.mkv"), originalRevision); fail("Expected account revision rejection") } catch (_: IllegalStateException) { }
        assertEquals(count, http.requests.size)
    }

    @Test fun `malformed stored connection rows cannot be silently dropped or overwritten`() {
        val memory = Memory()
        val hub = DesktopServiceHub(memory, Fake { """{"MediaContainer":{"Directory":[]}}""" })
        hub.connect(DesktopConnectionInput("plex", url = "https://media.example", token = "fixture"))
        memory.value = org.json.JSONArray(memory.value).put("damaged-row").toString()
        val saved = memory.value
        try { hub.connections(); fail("Expected damaged storage rejection") } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("preserved")) }
        try { hub.disconnect("missing"); fail("Expected no rewrite of damaged storage") } catch (_: IllegalStateException) { }
        assertEquals(saved, memory.value)
    }

    @Test fun `anime list entries retain account revision before explicit progress update`() {
        var progressWrites = 0
        val http = Fake { request -> when {
            request.url.endsWith("/@me") -> """{"name":"Owner"}"""
            request.url.contains("/animelist?") -> """{"data":[{"node":{"id":123,"title":"Anime"}}]}"""
            else -> { progressWrites++; """{"num_episodes_watched":12}""" }
        } }
        val hub = DesktopServiceHub(Memory(), http)
        val connection = hub.connect(DesktopConnectionInput("myanimelist", token = "first-fixture"))
        val entry = hub.browse(connection.id).single()
        assertEquals(hub.accountRevision(connection.id), entry.accountRevision)
        hub.connect(DesktopConnectionInput("myanimelist", token = "replacement-fixture", id = connection.id))
        try { hub.setAnimeProgress(connection.id, entry, 12, "completed"); fail("Expected stale anime entry rejection") } catch (_: IllegalStateException) { }
        assertEquals(0, progressWrites)
    }
}
