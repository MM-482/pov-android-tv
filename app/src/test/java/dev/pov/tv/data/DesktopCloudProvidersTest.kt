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
class DesktopCloudProvidersTest {
    private data class Request(val url: String, val method: String, val headers: Map<String, String>,
        val fields: List<Pair<String, String>>?, val json: JSONObject?)
    private class Http(private val response: (Request) -> String) : ProviderTransport {
        val requests = mutableListOf<Request>()
        override fun request(url: String, method: String, headers: Map<String, String>, fields: List<Pair<String, String>>?, json: JSONObject?): String {
            val request = Request(url, method, headers, fields, json)
            requests += request
            assertEquals("Bearer owner-test-token", headers["Authorization"])
            assertFalse(url.contains("owner-test-token"))
            assertTrue(URI(url).host in setOf("debrid-link.com", "api.put.io"))
            return response(request)
        }
    }
    private val movie = PlaybackIdentity("tt1234567", "movie", "Movie")
    private val source = SourceItem("Movie", "Public indexer", infoHash = "a".repeat(40))
    private val token = "owner-test-token"
    private fun dl(value: String, extra: String = "") = """{"success":true,"value":$value$extra}"""
    private fun put(value: String) = """{"status":"OK",$value}"""
    private inline fun <reified T : Throwable> expectFailure(work: () -> Unit): T {
        try { work() } catch (failure: Throwable) { if (failure is T) return failure else throw failure }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun `Debrid-Link verifies an account only through authenticated read`() {
        val http = Http { request ->
            assertEquals("https://debrid-link.com/api/v2/account/infos", request.url)
            assertEquals("GET", request.method)
            dl("""{"username":"Sample","accountType":1,"premiumLeft":86400}""")
        }
        val account = DebridLinkProvider(http).account(token, "")
        assertEquals("Sample", account.label)
        assertTrue(account.premium)
        assertEquals(1, http.requests.size)
    }

    @Test fun `Debrid-Link failed or empty account responses are rejected without raw errors`() {
        val failure = expectFailure<ApiException> {
            DebridLinkProvider(Http { """{"success":false,"error":"bad_token_owner-test-token"}""" }).account(token, "")
        }
        assertEquals(401, failure.code)
        assertFalse(failure.message.orEmpty().contains(token))
        expectFailure<ApiException> { DebridLinkProvider(Http { dl("{}") }).account(token, "") }
    }

    @Test fun `Debrid-Link browsing categories cannot create transfers`() {
        val http = Http { throw AssertionError("Category display must not send a request") }
        val folders = DebridLinkProvider(http).cloud(token, "")
        assertEquals(listOf("category:seedbox", "category:downloader"), folders.map { it.id })
        assertTrue(folders.all { it.directory })
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `Debrid-Link library follows explicit next pages and stops when absent`() {
        val http = Http { request -> when {
            request.url.contains("page=0") -> dl("""[{"id":"one","name":"One","downloadPercent":100}]""", """, "pagination":{"next":2}""")
            request.url.contains("page=2") -> dl("""[{"id":"two","name":"Two","downloadPercent":20}]""")
            else -> throw AssertionError("Wrong page: ${request.url}")
        } }
        val folders = DebridLinkProvider(http).cloud(token, "category:seedbox")
        assertEquals(listOf("seedbox:one", "seedbox:two"), folders.map { it.id })
        assertTrue(folders[0].ready)
        assertFalse(folders[1].ready)
        assertTrue(http.requests.all { it.method == "GET" })
    }

    @Test fun `Debrid-Link rejects repeated page cursors`() {
        val http = Http { dl("[]", """, "pagination":{"next":0}""") }
        expectFailure<IllegalStateException> { DebridLinkProvider(http).cloud(token, "category:seedbox") }
        assertEquals(1, http.requests.size)
    }

    @Test fun `Debrid-Link file choice retains actual transfer and file IDs without saved URL`() {
        val http = Http { request ->
            assertTrue(request.url.contains("ids=transfer"))
            dl("""[{"id":"other","files":[{"id":"wrong","name":"Other.mkv"}]},{"id":"transfer","files":[{"id":"small","name":"Movie.1080p.mkv","size":200,"downloadUrl":"https://private.example/a"},{"id":"large","name":"Movie.2160p.mkv","size":400,"downloadUrl":"https://private.example/b"}]}]""")
        }
        val files = DebridLinkProvider(http).files(token, "seedbox:transfer")
        assertEquals(listOf("seedfile:transfer:small", "seedfile:transfer:large"), files.map { it.id })
        assertTrue(files.all { it.link.isBlank() })
    }

    @Test fun `Debrid-Link selected file is freshly resolved not replaced with the largest`() {
        val http = Http { dl("""[{"id":"transfer","files":[{"id":"small","name":"Movie.mkv","downloadPercent":100,"downloadUrl":"https://cdn.example/chosen"},{"id":"large","name":"Movie.4k.mkv","downloadPercent":100,"downloadUrl":"https://cdn.example/not-chosen"}]}]""") }
        val result = DebridLinkProvider(http).playback(token, ProviderFile("seedfile:transfer:small", "Movie.mkv", link="https://stale.example/old"))
        assertEquals("https://cdn.example/chosen", result.url)
        assertEquals(1, http.requests.size)
    }

    @Test fun `Debrid-Link missing readiness and wrong transfer cannot play`() {
        expectFailure<ApiException> {
            DebridLinkProvider(Http { dl("""[{"id":"transfer","files":[{"id":"small","downloadUrl":"https://cdn.example/unsafe"}]}]""") })
                .playback(token, ProviderFile("seedfile:transfer:small", "Movie.mkv"))
        }
        expectFailure<ApiException> {
            DebridLinkProvider(Http { dl("""[{"id":"different","files":[{"id":"small","downloadPercent":100,"downloadUrl":"https://cdn.example/unsafe"}]}]""") })
                .playback(token, ProviderFile("seedfile:transfer:small", "Movie.mkv"))
        }
    }

    @Test fun `Debrid-Link generated links recheck expiry and processing`() {
        for (flags in listOf("\"expired\":true", "\"isProcessing\":true")) {
            expectFailure<ApiException> {
                DebridLinkProvider(Http { dl("""[{"id":"link",$flags,"downloadUrl":"https://cdn.example/unsafe"}]""") })
                    .playback(token, ProviderFile("download:link", "Movie.mkv"))
            }
        }
        val result = DebridLinkProvider(Http { dl("""[{"id":"link","downloadUrl":"https://cdn.example/fresh"}]""") })
            .playback(token, ProviderFile("download:link", "Movie.mkv"))
        assertEquals("https://cdn.example/fresh", result.url)
    }

    @Test fun `Debrid-Link preparation creates one explicit transfer and keeps every edition`() {
        val http = Http { request -> if (request.url.endsWith("/seedbox/add")) {
            assertEquals("POST", request.method)
            assertEquals("magnet:?xt=urn:btih:${source.infoHash}", request.json!!.getString("url"))
            assertFalse(request.json.getBoolean("wait"))
            dl("""{"id":"transfer"}""")
        } else dl("""[{"id":"transfer","downloadPercent":100,"files":[{"id":"one","name":"Movie.1080p.mkv","size":200},{"id":"two","name":"Movie.2160p.mkv","size":400}]}]""") }
        val prepared = DebridLinkProvider(http, pause = {}).prepare(token, source, movie)
        assertEquals(2, prepared.files.size)
        assertNull(prepared.automaticFile)
        assertEquals("seedbox:transfer", prepared.folderId)
        assertEquals(1, http.requests.count { it.method == "POST" })
        assertFalse(http.requests.any { it.url.contains("downloader/add") })
    }

    @Test fun `Debrid-Link pending preparation is bounded and leaves transfer in account`() {
        val http = Http { request -> if (request.url.endsWith("/seedbox/add")) dl("""{"id":"transfer"}""")
            else dl("""[{"id":"transfer","downloadPercent":15,"files":[]}]""") }
        var pauses = 0
        val failure = expectFailure<ApiException> { DebridLinkProvider(http, { pauses++ }, 2).prepare(token, source, movie) }
        assertTrue(failure.message.orEmpty().contains("remains in your cloud"))
        assertEquals(1, pauses)
        assertEquals(3, http.requests.size)
        assertFalse(http.requests.any { it.method == "DELETE" || it.url.contains("remove") })
    }

    @Test fun `putio verifies account and root listing uses official cursor continuation`() {
        val http = Http { request -> when {
            request.url.endsWith("/account/info") -> put(""""info":{"user_id":8,"username":"Sample","account_active":true}""")
            request.url.contains("/files/list?") -> put(""""files":[{"id":1,"name":"Shows","file_type":"FOLDER"}],"cursor":"opaque cursor"""")
            request.url.endsWith("/files/list/continue") -> {
                assertEquals("POST", request.method)
                assertEquals("opaque cursor", request.json!!.getString("cursor"))
                put(""""files":[{"id":2,"name":"Movie.mkv","size":400}],"cursor":""""")
            }
            else -> throw AssertionError("Unexpected request")
        } }
        assertTrue(PutIoProvider(http).account(token, "").premium)
        val folders = PutIoProvider(http).cloud(token, "")
        assertEquals(listOf("1", "file:2", "category:transfers"), folders.map { it.id })
        assertTrue(folders[0].directory)
        assertFalse(folders[1].directory)
        assertEquals("2", folders[1].files.single().id)
        assertTrue(folders[1].files.single().link.isBlank())
    }

    @Test fun `putio repeated cursor is rejected without unbounded requests`() {
        val http = Http { put(""""files":[],"cursor":"repeat"""") }
        expectFailure<IllegalStateException> { PutIoProvider(http).cloud(token, "0") }
        assertEquals(2, http.requests.size)
    }

    @Test fun `putio active transfers are browseable without creating or resolving media`() {
        val http = Http { request ->
            assertTrue(request.url.endsWith("/transfers/list"))
            put(""""transfers":[{"id":90,"name":"Movie","status":"DOWNLOADING"}]""")
        }
        val transfers = PutIoProvider(http).cloud(token, "category:transfers")
        assertEquals("transfer:90", transfers.single().id)
        assertFalse(transfers.single().ready)
        assertEquals("GET", http.requests.single().method)
    }

    @Test fun `putio file playback requests fresh URL for selected actual file only`() {
        val http = Http { request ->
            assertEquals("https://api.put.io/v2/files/7/url?notunnel=0", request.url)
            put(""""url":"https://cdn.example/actual-seven"""")
        }
        assertEquals("https://cdn.example/actual-seven", PutIoProvider(http).playback(token,
            ProviderFile("7", "Movie.mkv", link="https://stale.example/wrong")).url)
        assertEquals(1, http.requests.size)
    }

    @Test fun `putio file and transfer identity substitutions are rejected`() {
        expectFailure<IllegalStateException> {
            PutIoProvider(Http { put(""""file":{"id":8,"name":"Other.mkv"}""") }).files(token, "file:7")
        }
        expectFailure<IllegalStateException> {
            PutIoProvider(Http { put(""""transfer":{"id":91,"file_id":7}""") }).files(token, "transfer:90")
        }
    }

    @Test fun `putio preparation follows exact transfer file tree and preserves editions`() {
        val http = Http { request -> when {
            request.url.endsWith("/transfers/add") -> {
                assertEquals("POST", request.method)
                assertEquals(listOf("url" to "magnet:?xt=urn:btih:${source.infoHash}"), request.fields)
                put(""""transfer":{"id":90}""")
            }
            request.url.endsWith("/transfers/90") -> put(""""transfer":{"id":90,"file_id":2,"status":"COMPLETED"}""")
            request.url.endsWith("/files/2") -> put(""""file":{"id":2,"name":"Versions","file_type":"FOLDER"}""")
            request.url.contains("parent_id=2") -> put(""""files":[{"id":7,"name":"Movie.1080p.mkv","size":200},{"id":8,"name":"Movie.2160p.mkv","size":400}]""")
            else -> throw AssertionError("Unexpected request ${request.url}")
        } }
        val prepared = PutIoProvider(http, pause = {}).prepare(token, source, movie)
        assertEquals(listOf("7", "8"), prepared.files.map { it.id })
        assertNull(prepared.automaticFile)
        assertEquals("transfer:90", prepared.folderId)
        assertEquals(1, http.requests.count { it.method == "POST" })
        assertFalse(http.requests.any { it.url.contains("/url") })
    }

    @Test fun `putio pending preparation is bounded and never cancels cloud transfer`() {
        val http = Http { request -> if (request.url.endsWith("/transfers/add")) put(""""transfer":{"id":90}""")
            else put(""""transfer":{"id":90,"file_id":0,"status":"DOWNLOADING"}""") }
        val failure = expectFailure<ApiException> { PutIoProvider(http, {}, 2).prepare(token, source, movie) }
        assertEquals("PUTIO_PROCESSING", failure.errorKey)
        assertEquals(3, http.requests.size)
        assertFalse(http.requests.any { it.url.contains("cancel") || it.method == "DELETE" })
    }

    @Test fun `putio nested source preparation preserves folder names for episode matching`() {
        val episode = PlaybackIdentity("tt1234567", "series", "Show", 2, 1)
        val http = Http { request -> when {
            request.url.endsWith("/transfers/add") -> put(""""transfer":{"id":90}""")
            request.url.endsWith("/transfers/90") -> put(""""transfer":{"id":90,"file_id":2,"status":"COMPLETED"}""")
            request.url.endsWith("/files/2") -> put(""""file":{"id":2,"name":"Show","file_type":"FOLDER"}""")
            request.url.contains("parent_id=2") -> put(""""files":[{"id":3,"name":"Season 2","file_type":"FOLDER"}]""")
            request.url.contains("parent_id=3") -> put(""""files":[{"id":7,"name":"Show.S02E01.mkv"},{"id":8,"name":"Show.S02E02.mkv"}]""")
            else -> throw AssertionError("Unexpected request")
        } }
        val prepared = PutIoProvider(http, {}).prepare(token, source.copy(title="Show S02E01"), episode)
        assertEquals("7", prepared.automaticFile!!.id)
        assertTrue(prepared.files.single().name.startsWith("Season 2/"))
    }

    @Test fun `custom addon episode prepares through DebridLink without guessed IMDb or adjacent file`() {
        val identity = PlaybackIdentity("custom:42", "series", "Show", 2, 4, "catalog-owner")
        val selected = source.copy(title = "Show.S02E04.mkv", sourceAddonId = "stream-provider", resourceType = "series", resourceId = "video:opaque",
            requestIdentity = SourceRequestIdentity("catalog-owner", "custom:42", "series", "video:opaque", 2, 4))
        val http = Http { request -> if (request.url.endsWith("/seedbox/add")) dl("""{"id":"transfer"}""")
            else dl("""[{"id":"transfer","downloadPercent":100,"files":[{"id":"chosen","name":"Show.S02E04.mkv","size":200},{"id":"adjacent","name":"Show.S02E05.mkv","size":400}]}]""") }
        val prepared = DebridLinkProvider(http, pause = {}).prepare(token, selected, identity)
        assertEquals("seedfile:transfer:chosen", prepared.automaticFile!!.id)
        assertEquals(1, http.requests.count { it.method == "POST" })
        assertFalse(http.requests.any { it.url.contains("custom:") || Regex("tt[0-9]+").containsMatchIn(it.url) })
    }

    @Test fun `custom addon episode prepares through putio using its exact requested identity`() {
        val identity = PlaybackIdentity("custom:42", "series", "Show", 2, 4, "catalog-owner")
        val selected = source.copy(title = "Show.S02E04.mkv", sourceAddonId = "stream-provider", resourceType = "series", resourceId = "video:opaque",
            requestIdentity = SourceRequestIdentity("catalog-owner", "custom:42", "series", "video:opaque", 2, 4))
        val http = Http { request -> when {
            request.url.endsWith("/transfers/add") -> put(""""transfer":{"id":90}""")
            request.url.endsWith("/transfers/90") -> put(""""transfer":{"id":90,"file_id":2,"status":"COMPLETED"}""")
            request.url.endsWith("/files/2") -> put(""""file":{"id":2,"name":"Show","file_type":"FOLDER"}""")
            request.url.contains("parent_id=2") -> put(""""files":[{"id":7,"name":"Show.S02E04.mkv"},{"id":8,"name":"Show.S02E05.mkv"}]""")
            else -> error("Unexpected request")
        } }
        val prepared = PutIoProvider(http, pause = {}).prepare(token, selected, identity)
        assertEquals("7", prepared.automaticFile!!.id)
        assertEquals(1, http.requests.count { it.method == "POST" })
    }

    @Test fun `putio recursive folder cycle is rejected`() {
        val http = Http { request -> when {
            request.url.endsWith("/transfers/90") -> put(""""transfer":{"id":90,"file_id":2}""")
            request.url.endsWith("/files/2") -> put(""""file":{"id":2,"name":"Loop","file_type":"FOLDER"}""")
            else -> put(""""files":[{"id":2,"name":"Loop","file_type":"FOLDER"}]""")
        } }
        expectFailure<IllegalStateException> { PutIoProvider(http).files(token, "transfer:90") }
        assertEquals(3, http.requests.size)
    }

    @Test fun `invalid file IDs and mismatched source episodes fail before provider writes`() {
        val http = Http { throw AssertionError("Invalid identity reached API") }
        expectFailure<IllegalArgumentException> { PutIoProvider(http).playback(token, ProviderFile("7/../../other", "Movie.mkv")) }
        expectFailure<IllegalArgumentException> { DebridLinkProvider(http).files(token, "seedbox:../other") }
        expectFailure<IllegalArgumentException> { PutIoProvider(http).prepare(token, source.copy(infoHash="invalid"), movie) }
        val wrongEpisode = source.copy(title="Show.S02E02.mkv")
        val identity = PlaybackIdentity("tt1234567", "series", "Show", 2, 1)
        expectFailure<IllegalArgumentException> { PutIoProvider(http).prepare(token, wrongEpisode, identity) }
        expectFailure<IllegalArgumentException> { DebridLinkProvider(http).prepare(token, wrongEpisode, identity) }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `cancelled thread cannot create a provider transfer`() {
        val http = Http { throw AssertionError("Cancelled action reached API") }
        try {
            Thread.currentThread().interrupt()
            expectFailure<InterruptedException> { PutIoProvider(http).prepare(token, source, movie) }
            expectFailure<InterruptedException> { DebridLinkProvider(http).prepare(token, source, movie) }
        } finally { Thread.interrupted() }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `provider file URLs must remain HTTPS without user info`() {
        for (url in listOf("http://cdn.example/video", "https://name:password@cdn.example/video", "file:///tmp/movie")) {
            expectFailure<ApiException> { PutIoProvider(Http { put(""""url":"$url"""") }).playback(token, ProviderFile("7", "Movie.mkv")) }
            expectFailure<ApiException> { DebridLinkProvider(Http { dl("""[{"id":"link","downloadUrl":"$url"}]""") }).playback(token, ProviderFile("download:link", "Movie.mkv")) }
        }
    }

    private fun directHub(): ProviderHub = ProviderHub(object : ProviderCredentials {
        override fun get(key: String) = when (key) { "putio.token" -> "separate-provider-account-key"; "putio.account_revision" -> "fixture"; else -> "" }
        override fun putAll(values: Map<String, String>) { throw AssertionError("Read-only direct source must not save credentials") }
    }, Http { throw AssertionError("Direct source must not call provider API") })

    @Test fun `direct source preserves its explicit headers without borrowing provider credentials`() {
        val direct = source.copy(directUrl="https://media.example/chosen", requestHeaders=mapOf(
            "Authorization" to "Bearer source-test-key", "X-Media-Variant" to "edition-two", "Cookie" to "fixture=one"))
        val playback = directHub().prepare("putio", direct, movie).playback!!
        assertEquals(direct.directUrl, playback.url)
        assertEquals(direct.requestHeaders, playback.headers)
        assertFalse(playback.headers.values.any { it.contains("separate-provider-account-key") })
        assertEquals(playback, directHub().resolve("putio", direct, movie))
    }

    @Test fun `direct source rejects unsafe headers explicitly instead of silently dropping them`() {
        val invalid = listOf(mapOf("Host" to "different.example"), mapOf("Content-Length" to "42"),
            mapOf("Connection" to "keep-alive"), mapOf("Transfer-Encoding" to "chunked"),
            mapOf("X-Test" to "value\r\nInjected: yes"), mapOf("X-Test" to "x\u0000y"),
            mapOf("Bad Key" to "value"), mapOf("Authorization" to "one", "authorization" to "two"))
        invalid.forEach { headers ->
            expectFailure<IllegalArgumentException> { directHub().prepare("putio", source.copy(directUrl="https://media.example/chosen", requestHeaders=headers), movie) }
        }
    }
}
