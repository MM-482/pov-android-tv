package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameoConnectionsTest {
    private class Memory : ProviderCredentials {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key].orEmpty()
        override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
    }
    private fun provider(answer: (String, Map<String, String>) -> String) = object : ProviderTransport {
        override fun request(url: String, method: String, headers: Map<String, String>, fields: List<Pair<String, String>>?, json: JSONObject?) = answer(url, headers)
    }
    private fun service(answer: (String, String, Map<String, String>, String?) -> String) = object : CameoServiceTransport {
        override fun request(url: String, method: String, headers: Map<String, String>, body: String?, contentType: String) = answer(url, method, headers, body)
    }
    private val never = service { _, _, _, _ -> error("Unexpected service request") }

    @Test fun `multiple saved provider accounts use separate credentials and durable identities`() {
        val seen = mutableListOf<String>()
        val facade = CameoConnections(Memory(), never, provider { url, headers ->
            val token = headers.getValue("Authorization"); seen += token
            if (url.endsWith("/user")) """{"username":"${token.substringAfterLast(' ')}","type":"premium","id":1}""" else "[]"
        })
        val first = facade.save(CameoConnection(kind = "realdebrid", token = "first"))
        val second = facade.save(CameoConnection(kind = "realdebrid", token = "second"))
        assertNotEquals(first.id, second.id)
        assertEquals(2, facade.list().size)
        facade.browse(first.id); assertEquals("Bearer first", seen.last())
        facade.browse(second.id); assertEquals("Bearer second", seen.last())
        assertFalse(first.toString().contains("Bearer"))
    }

    @Test fun `credential replacement changes identity but name edits do not`() {
        val facade = CameoConnections(Memory(), never, provider { _, _ -> """{"username":"user","type":"premium","id":1}""" })
        val first = facade.save(CameoConnection(kind = "realdebrid", token = "one"))
        val renamed = facade.save(first.copy(name = "Personal account"))
        assertEquals(first.id, renamed.id); assertEquals(first.revision, renamed.revision)
        val replacement = facade.save(renamed.copy(token = "two"))
        assertNotEquals(first.id, replacement.id)
        assertFalse(facade.list().any { it.id == first.id })
    }

    @Test fun `failed account verification preserves existing connection`() {
        var reject = false
        val facade = CameoConnections(Memory(), never, provider { _, _ ->
            if (reject) throw ApiException(401, "Expired")
            """{"username":"user","type":"premium","id":1}"""
        })
        val first = facade.save(CameoConnection(kind = "realdebrid", token = "one"))
        reject = true
        try { facade.save(first.copy(token = "bad")); fail("Should reject") } catch (_: ApiException) { }
        assertEquals(first, facade.list().single())
    }

    @Test fun `disconnect during browse rejects the returned stale account data`() {
        lateinit var facade: CameoConnections
        var savedId = ""
        facade = CameoConnections(Memory(), never, provider { url, _ ->
            if (url.endsWith("/user")) """{"username":"user","type":"premium","id":1}"""
            else { facade.remove(savedId); "[]" }
        })
        savedId = facade.save(CameoConnection(kind = "realdebrid", token = "one")).id
        try { facade.browse(savedId); fail("Stale account response accepted") } catch (_: IllegalStateException) { }
        assertTrue(facade.list().isEmpty())
    }

    @Test fun `saved AllDebrid file descriptors contain names not temporary private links`() {
        val facade = CameoConnections(Memory(), never, provider { url, _ ->
            if (url.contains("/user")) """{"status":"success","data":{"user":{"username":"u","isPremium":true}}}"""
            else """{"status":"success","data":{"magnets":[{"id":"transfer7","files":[{"n":"movie.mkv","s":123,"l":"https://provider.example/private?token=secret"}]}]}}"""
        })
        val c = facade.save(CameoConnection(kind = "alldebrid", token = "one"))
        val item = facade.browse(c.id, "files:transfer7").single()
        assertEquals("name:movie.mkv", item.remoteId)
        assertFalse(item.toJson().toString().contains("secret"))
        assertFalse(item.toJson().toString().contains("https://"))
        assertEquals(item, CameoServiceItem.fromJson(item.toJson()))
    }

    @Test fun `Jellyfin preserves exact edition and keeps token in scoped headers`() {
        val http = service { url, _, _, _ -> when {
            url.endsWith("Users/Me") -> """{"Id":"user1","Name":"Viewer"}"""
            url.contains("PlaybackInfo") -> """{"MediaSources":[{"Id":"edition-A"},{"Id":"edition-B"}]}"""
            else -> """{"Items":[{"Id":"film1","Name":"Film","Type":"Movie","MediaSources":[{"Id":"edition-A","Name":"1080p"},{"Id":"edition-B","Name":"2160p"}]}],"TotalRecordCount":1}"""
        } }
        val facade = CameoConnections(Memory(), http)
        val c = facade.save(CameoConnection(kind = "jellyfin", url = "http://192.168.1.5:8096", token = "private-token"))
        val items = facade.browse(c.id)
        assertEquals(2, items.size)
        val result = facade.resolve(c.id, items[1])
        assertTrue(result.url.contains("MediaSourceId=edition-B"))
        assertEquals("http://192.168.1.5:8096", result.origin)
        assertEquals("private-token", result.headers["X-Emby-Token"])
        assertFalse(result.url.contains("private-token"))
        assertFalse(items[1].toJson().toString().contains("private-token"))
    }

    @Test fun `WebDAV rejects cross-root and unsuccessful rows and accepts empty valid authentication`() {
        val xml = """<d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/videos/</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/videos/one.mkv</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:displayname>One</d:displayname><d:getcontentlength>55</d:getcontentlength></d:prop></d:propstat></d:response>
            <d:response><d:href>/videos/%2e%2e/secret.mkv</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop/></d:propstat></d:response>
            <d:response><d:href>/videos/unavailable.mkv</d:href><d:propstat><d:status>HTTP/1.1 404 Not Found</d:status><d:prop/></d:propstat></d:response>
            </d:multistatus>"""
        val facade = CameoConnections(Memory(), service { _, method, headers, _ ->
            assertEquals("PROPFIND", method); assertEquals("1", headers["Depth"]); xml
        })
        val c = facade.save(CameoConnection(kind = "webdav", url = "https://nas.example/videos/", username = "me", password = "secret"))
        val items = facade.browse(c.id)
        assertEquals(1, items.size); assertEquals("/videos/one.mkv", items.single().remoteId)
        assertEquals(55L, items.single().size)
    }

    @Test fun `public HTTP and userinfo are rejected while private local server URLs are accepted`() {
        listOf("http://example.com", "http://192.168.1.1.evil.example", "http://10.bad.0.0.1", "http://[fc0::1]", "https://user:pass@example.com").forEach {
            try { CameoServiceUrls.checked(it); fail("Accepted $it") } catch (_: IllegalArgumentException) { }
        }
        listOf("http://192.168.1.3:8096", "http://10.0.0.2", "http://[fd00::1]", "http://[fe9f::1]", "http://localhost", "https://server.example").forEach {
            assertNotNull(CameoServiceUrls.checked(it))
        }
        assertFalse(CameoServiceUrls.isLocalHost("172.32.0.1"))
    }
    @Test fun `canceled saved-account preparation cannot create a transfer`() {
        var requests = 0
        val facade = CameoConnections(Memory(), never, provider { _, _ -> requests++; """{"username":"user","type":"premium","id":1}""" })
        val connection = facade.save(CameoConnection(kind = "realdebrid", token = "one"))
        try {
            facade.prepare(connection.id, SourceItem("Example", "Test", infoHash = "a".repeat(40)),
                PlaybackIdentity("tt1234567", "movie", "Example"), cancelled = { true })
            fail("Canceled transfer was created")
        } catch (_: InterruptedException) { }
        assertEquals(1, requests)
    }
    @Test fun `Real-Debrid generated downloads remain durable and refresh links only for explicit playback`() {
        var downloadReads = 0; var unlocks = 0
        val facade = CameoConnections(Memory(), never, provider { url, _ -> when {
            url.endsWith("/user") -> """{"username":"user","type":"premium","id":1}"""
            url.contains("/torrents?") -> "[]"
            url.contains("/downloads?") -> { downloadReads++; """[{"id":"download7","filename":"Example.mkv","filesize":123,"link":"https://rd.example/restricted?private=secret","download":"https://cdn.example/expired?token=secret"}]""" }
            url.endsWith("/unrestrict/link") -> { unlocks++; """{"download":"https://cdn.example/fresh"}""" }
            else -> error("Unexpected API operation")
        } })
        val c = facade.save(CameoConnection(kind = "realdebrid", token = "account-token"))
        val category = facade.browse(c.id).single { it.title == "Generated downloads" }
        val entry = facade.browse(c.id, category.id).single()
        val file = facade.browse(c.id, entry.id).single()
        assertEquals("download:download7", file.remoteId)
        assertFalse(file.toJson().toString().contains("secret")); assertFalse(file.toJson().toString().contains("https://"))
        assertEquals(0, unlocks)
        assertEquals("https://cdn.example/fresh", facade.resolve(c.id, CameoServiceItem.fromJson(file.toJson())).url)
        assertEquals(1, unlocks); assertTrue(downloadReads >= 3)
    }
    @Test fun `Plex collection navigation uses the actual collection route`() {
        val facade = CameoConnections(Memory(), service { url, _, _, _ -> when {
            url.startsWith("https://plex.tv/") -> """{"username":"Viewer"}"""
            url.contains("/library/sections/1/all") -> """{"MediaContainer":{"Metadata":[{"type":"collection","ratingKey":"7","key":"/library/collections/7/children","title":"Collection"}]}}"""
            url.contains("/library/collections/7/children") -> """{"MediaContainer":{"Metadata":[{"type":"movie","ratingKey":"film9","title":"Film","Media":[{"Part":[{"id":"part10","size":200}]}]}]}}"""
            else -> """{"MediaContainer":{"Directory":[]}}"""
        } })
        val c = facade.save(CameoConnection(kind = "plex", url = "https://plex.example", token = "private"))
        val collection = facade.browse(c.id, "/library/sections/1/all").single()
        assertTrue(collection.folder); assertEquals("/library/collections/7/children", collection.id)
        val file = facade.browse(c.id, collection.id).single()
        assertEquals("film9", file.remoteId); assertEquals("part10", file.mediaSourceId)
    }
}
