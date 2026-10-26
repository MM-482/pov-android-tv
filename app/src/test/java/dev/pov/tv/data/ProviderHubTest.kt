package dev.pov.tv.data

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderHubTest {
    private class MemoryCredentials : ProviderCredentials {
        private val values = mutableMapOf<String, String>()
        @Synchronized override fun get(key: String) = values[key].orEmpty()
        @Synchronized override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
    }
    private data class Request(val url: String, val method: String, val headers: Map<String, String>, val fields: List<Pair<String, String>>?)
    private class FakeTransport(private val answer: (Request) -> String) : ProviderTransport {
        val requests = mutableListOf<Request>()
        override fun request(url: String, method: String, headers: Map<String, String>, fields: List<Pair<String, String>>?, json: JSONObject?): String {
            val request = Request(url, method, headers, fields)
            requests.add(request)
            return answer(request)
        }
    }
    private val movie = PlaybackIdentity("tt1234567", "movie", "Example")
    private val source = SourceItem("Example", "Test", infoHash = "a".repeat(40))

    @Test fun `provider list exactly matches original POV playback services`() {
        assertEquals(setOf("torbox", "realdebrid", "alldebrid", "premiumize", "offcloud", "easynews"), ProviderHub.SPECS.map { it.id }.toSet())
        assertTrue(ProviderHub.SPECS.single { it.id == "easynews" }.experimental)
    }

    @Test fun `manual Real-Debrid key is verified without forwarding it to source providers`() {
        val credentials = MemoryCredentials()
        val http = FakeTransport { request ->
            assertEquals("https://api.real-debrid.com/rest/1.0/user", request.url)
            assertEquals("Bearer user-supplied-token", request.headers["Authorization"])
            assertNull(URI(request.url).query)
            """{"id":1,"username":"example","type":"premium"}"""
        }
        val hub = ProviderHub(credentials, http)
        assertFalse(hub.connected("realdebrid"))
        assertTrue(hub.connect("realdebrid", "user-supplied-token").premium)
        assertTrue(hub.connected("realdebrid"))
        assertTrue(hub.accountRevision("realdebrid").isNotBlank())
    }

    @Test fun `failed key verification never overwrites a connected account`() {
        val credentials = MemoryCredentials().apply { putAll(mapOf("realdebrid.token" to "working", "realdebrid.account_revision" to "old")) }
        val hub = ProviderHub(credentials, FakeTransport { throw ApiException(401, "Authorization failed.") })
        try { hub.connect("realdebrid", "bad"); fail("Expected failed verification") } catch (_: ApiException) { }
        assertEquals("working", credentials.get("realdebrid.token"))
        assertEquals("old", hub.accountRevision("realdebrid"))
    }

    @Test fun `disconnect during account verification cannot resurrect credentials`() {
        val credentials = MemoryCredentials()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val hub = ProviderHub(credentials, FakeTransport {
            started.countDown(); check(finish.await(10, TimeUnit.SECONDS))
            """{"id":1,"username":"example","type":"premium"}"""
        })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { try { hub.connect("realdebrid", "new-token"); true } catch (_: IllegalStateException) { false } }
            check(started.await(10, TimeUnit.SECONDS))
            hub.disconnect("realdebrid"); finish.countDown()
            assertFalse(result.get(10, TimeUnit.SECONDS))
            assertFalse(hub.connected("realdebrid"))
        } finally { finish.countDown(); worker.shutdownNow() }
    }

    @Test fun `UI account status and disconnect remain responsive while cloud request waits`() {
        val credentials = MemoryCredentials().apply { putAll(mapOf("realdebrid.token" to "token", "realdebrid.account_revision" to "old")) }
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val hub = ProviderHub(credentials, FakeTransport {
            started.countDown(); check(finish.await(10, TimeUnit.SECONDS)); "[]"
        })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val cloud = workers.submit<Boolean> { try { hub.cloud("realdebrid"); true } catch (_: IllegalStateException) { false } }
            check(started.await(10, TimeUnit.SECONDS))
            val status = workers.submit<Boolean> { val linked = hub.connected("realdebrid"); hub.disconnect("realdebrid"); linked }
            assertTrue(status.get(2, TimeUnit.SECONDS))
            finish.countDown()
            assertFalse(cloud.get(10, TimeUnit.SECONDS))
            assertFalse(hub.connected("realdebrid"))
        } finally { finish.countDown(); workers.shutdownNow() }
    }

    @Test fun `queued file and source requests cannot adopt account switched before worker starts`() {
        val credentials = MemoryCredentials().apply { putAll(mapOf("realdebrid.token" to "new-account-key", "realdebrid.account_revision" to "new-revision",
            "easynews.token" to "new-password", "easynews.username" to "new-user", "easynews.account_revision" to "new-revision")) }
        val hub = ProviderHub(credentials, FakeTransport { throw AssertionError("Stale account request reached transport") })
        val actions = listOf<() -> Any>(
            { hub.cloud("realdebrid", expectedRevision = "old-revision") },
            { hub.files("realdebrid", "old-folder", expectedRevision = "old-revision") },
            { hub.prepare("realdebrid", source, movie, expectedRevision = "old-revision") },
            { hub.playback("realdebrid", ProviderFile("old-folder:1", "Movie.mkv"), expectedRevision = "old-revision") },
            { hub.searchEasynews(movie, expectedRevision = "old-revision") }
        )
        actions.forEach { action ->
            try { action(); fail("Expected stale account rejection") } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("account changed"))
            }
        }
    }

    @Test fun `Real-Debrid selected-file links preserve API selection ordering`() {
        val provider = RealDebridProvider(FakeTransport { fail("Parser must not call network"); "" })
        val files = provider.parseFiles(JSONObject("""{"files":[{"id":2,"path":"unselected.mkv","selected":0},{"id":8,"path":"readme.txt","selected":1},{"id":19,"path":"movie.mkv","selected":1}],"links":["https://rd.example/readme","https://rd.example/movie"]}"""), "transfer")
        assertEquals("transfer:19", files[1].id)
        assertEquals("https://rd.example/movie", files[1].link)
    }

    @Test fun `Real-Debrid preparation preserves versions without selecting or downloading files`() {
        val http = FakeTransport { request -> when {
            request.url.endsWith("/addMagnet") -> """{"id":"transfer"}"""
            request.url.contains("/info/") -> """{"status":"waiting_files_selection","files":[{"id":3,"path":"Example.1080p.mkv","bytes":200},{"id":9,"path":"Example.2160p.mkv","bytes":400}]}"""
            else -> throw AssertionError("Unexpected API operation")
        } }
        val prepared = RealDebridProvider(http).prepare("token", source, movie)
        assertEquals(listOf("transfer:3", "transfer:9"), prepared.files.map { it.id })
        assertNull(prepared.automaticFile)
        assertFalse(http.requests.any { it.url.contains("selectFiles") || it.url.contains("unrestrict") })
    }

    @Test fun `Real-Debrid playback selects exactly the user chosen file`() {
        val http = FakeTransport { request -> when {
            request.url.contains("/selectFiles/") -> { assertEquals(listOf("files" to "9"), request.fields); "" }
            request.url.contains("/info/") -> """{"status":"downloaded","files":[{"id":3,"path":"Example.1080p.mkv","selected":0},{"id":9,"path":"Example.2160p.mkv","selected":1}],"links":["https://rd.example/chosen"]}"""
            request.url.endsWith("/unrestrict/link") -> { assertEquals(listOf("link" to "https://rd.example/chosen"), request.fields); """{"download":"https://cdn.example/chosen"}""" }
            else -> throw AssertionError("Unexpected API operation")
        } }
        val result = RealDebridProvider(http).playback("token", ProviderFile("transfer:9", "Example.2160p.mkv"))
        assertEquals("https://cdn.example/chosen", result.url)
        assertEquals(3, http.requests.size)
    }

    @Test fun `AllDebrid nested files retain folder names and service links`() {
        val files = AllDebridProvider(FakeTransport { fail("No network expected"); "" }).flatten(JSONArray("""[{"n":"Season 2","e":[{"n":"Show.S02E01.mkv","s":400,"l":"https://alldebrid.com/f/one"},{"n":"Show.S02E02.mkv","s":500,"l":"https://alldebrid.com/f/two"}]}]"""))
        assertEquals("Season 2/Show.S02E02.mkv", files[1].name)
        assertEquals("https://alldebrid.com/f/two", files[1].link)
    }

    @Test fun `AllDebrid preparation uses current files endpoint and leaves both versions locked until chosen`() {
        val http = FakeTransport { request -> when {
            request.url.endsWith("/v4/magnet/upload") -> """{"status":"success","data":{"magnets":[{"id":42}]}}"""
            request.url.endsWith("/v4.1/magnet/status") -> """{"status":"success","data":{"magnets":{"id":42,"statusCode":4}}}"""
            request.url.endsWith("/v4/magnet/files") -> """{"status":"success","data":{"magnets":[{"id":42,"files":[{"n":"Example.1080p.mkv","s":200,"l":"https://alldebrid.com/f/one"},{"n":"Example.2160p.mkv","s":400,"l":"https://alldebrid.com/f/two"}]}]}}"""
            else -> throw AssertionError("Unexpected API operation")
        } }
        val prepared = AllDebridProvider(http).prepare("token", source, movie)
        assertEquals(2, prepared.files.size)
        assertNull(prepared.automaticFile)
        assertFalse(http.requests.any { it.url.contains("/link/unlock") })
    }

    @Test fun `Premiumize instant files retain all versions and do not use retired transcode fields`() {
        val http = FakeTransport { request ->
            assertTrue(request.url.endsWith("/transfer/directdl"))
            """{"status":"success","content":[{"path":"Example.1080p.mkv","size":200,"link":"https://cdn.example/one"},{"path":"Example.2160p.mkv","size":400,"link":"https://cdn.example/two"}]}"""
        }
        val prepared = PremiumizeProvider(http).prepare("token", source, movie)
        assertEquals(2, prepared.files.size)
        assertNull(prepared.automaticFile)
        assertTrue(prepared.files.all { it.direct })
        assertEquals("https://cdn.example/two", PremiumizeProvider(http).playback("token", prepared.files[1]).url)
        assertEquals(1, http.requests.size)
    }

    @Test fun `Easynews account login HTML is never treated as verified account`() {
        val credentials = MemoryCredentials()
        val hub = ProviderHub(credentials, FakeTransport { "<html><form id='login'>Sign in</form></html>" })
        try { hub.connect("easynews", "password", "username"); fail("Expected verification failure") } catch (_: ApiException) { }
        assertFalse(hub.connected("easynews"))
    }

    @Test fun `Offcloud selected cached source returns all playable versions without creating a cloud transfer`() {
        val http = FakeTransport { request ->
            assertEquals("offcloud.com", URI(request.url).host)
            assertTrue(request.url.contains("/api/cache/download?key="))
            """[{"filename":"Example.1080p.mkv","size":200,"url":"https://cdn.example/one"},{"filename":"Example.2160p.mkv","size":400,"url":"https://cdn.example/two"}]"""
        }
        val prepared = OffcloudProvider(http).prepare("token", source, movie)
        assertEquals(2, prepared.files.size)
        assertNull(prepared.automaticFile)
        assertEquals("https://cdn.example/two", OffcloudProvider(http).playback("token", prepared.files[1]).url)
        assertEquals(1, http.requests.size)
    }

    @Test fun `Easynews playback credential stays in scoped headers and cannot escape provider domain`() {
        val provider = EasynewsProvider(FakeTransport { fail("No network expected"); "" })
        val result = provider.playback("secret-password", "user", ProviderFile("id", "Movie.mkv", link = "https://members.easynews.com/dl/movie.mkv"))
        assertFalse(result.url.contains("secret-password"))
        assertTrue(result.headers.getValue("Authorization").startsWith("Basic "))
        listOf("https://easynews.com.evil.example/video", "https://evil.example/video", "http://members.easynews.com/video").forEach { link ->
            try { provider.playback("secret-password", "user", ProviderFile("id", "Movie", link = link)); fail("Expected host rejection") } catch (error: ApiException) {
                assertFalse(error.message.orEmpty().contains("secret-password"))
            }
        }
    }

    @Test fun `Easynews search filters virus results and constructs scoped encoded playback paths`() {
        val provider = EasynewsProvider(FakeTransport { fail("No network expected"); "" })
        val files = provider.parseSearch(JSONObject("""{"downURL":"https://members.easynews.com/dl","dlFarm":"farm","dlPort":"443","data":[{"0":"hash","10":"Example 1080p","11":".mkv","type":"VIDEO","rawSize":400},{"0":"bad","10":"Virus","11":".mkv","virus":true}]}"""))
        assertEquals(1, files.size)
        assertEquals("Example 1080p.mkv", files.single().name)
        assertEquals("https://members.easynews.com/dl/farm/443/hash.mkv/Example%201080p.mkv", files.single().link)
        assertEquals(400L, files.single().size)
    }

    @Test fun `non TorBox source listing never requests TorBox account or claims TorBox cache status`() {
        val noTorBox = object : SourceTorBox {
            override fun cached(hashes: List<String>): Set<String> = throw AssertionError("Unexpected TorBox cache call")
            override fun addMagnet(hash: String): Long = throw AssertionError("Unexpected transfer")
            override fun folder(kind: String, id: Long): CloudFolder = throw AssertionError("Unexpected cloud request")
            override fun streamUrl(kind: String, folderId: Long, fileId: Long): String = throw AssertionError("Unexpected link request")
        }
        val resolver = SourceResolver({ "https://provider.example" }, noTorBox,
            { JSONObject().put("streams", JSONArray().put(JSONObject().put("infoHash", "a".repeat(40)).put("title", "Example.mkv"))) })
        val choices = resolver.sourcesUncached(movie)
        assertEquals(1, choices.size)
        assertFalse(choices.single().cached)
    }
}
