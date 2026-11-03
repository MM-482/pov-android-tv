package dev.pov.tv.subtitles

import dev.pov.tv.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameoSavedSubtitlesTest {
    private data class Request(val url: String, val method: String, val headers: Map<String, String>, val body: String?)
    private class Api(private val answer: (Request) -> String) : CameoServiceTransport {
        val requests = mutableListOf<Request>()
        override fun request(url: String, method: String, headers: Map<String, String>, body: String?, contentType: String): String {
            return answer(Request(url, method, headers, body).also(requests::add))
        }
    }
    private class SavedAccounts : ProviderCredentials {
        val legacy = mapOf(SubtitleSettings.OPEN_SUBTITLES_API_KEY to "legacy-app-key",
            "subtitles.opensubtitles.token" to "legacy-user-token", SubtitleSettings.SUBDL_API_KEY to "legacy-subdl-key")
        val values = legacy.toMutableMap()
        override fun get(key: String): String { assertEquals("cameo.connections.v1", key); return values[key].orEmpty() }
        override fun putAll(values: Map<String, String>) {
            assertEquals(setOf("cameo.connections.v1"), values.keys); this.values.putAll(values)
        }
    }
    private val episode = PlaybackIdentity("tt0903747", "series", "Show", 2, 3)
    private val caption = "1\n00:00:01,000 --> 00:00:02,000\nFixture caption\n"
    private val openResults = """{"data":[{"attributes":{"language":"en","release":"Show.S02E03","files":[{"file_id":101,"file_name":"Show.S02E03.srt"}]}}]}"""
    private fun query(url: String) = URI(url).rawQuery.orEmpty().split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    @Test fun `saved OpenSubtitles accounts keep API keys access tokens and official hosts separate from legacy state`() {
        val stored = SavedAccounts()
        val api = Api { request -> when {
            request.url.endsWith("/infos/user") -> """{"data":{"allowed_downloads":10}}"""
            request.url.endsWith("/infos/languages") -> """{"data":[{"language_code":"en"}]}"""
            "/subtitles?" in request.url -> openResults
            else -> error("Unexpected API operation")
        } }
        val facade = CameoConnections(stored, api)
        val first = facade.save(CameoConnection(kind = "opensubtitles", name = "First",
            apiKey = "app-first", token = "token-first"))
        val second = facade.save(CameoConnection(kind = "opensubtitles", name = "Second",
            apiKey = "app-second", token = "token-second", url = "https://vip-api.opensubtitles.com"))
        val searchOnly = facade.save(CameoConnection(kind = "opensubtitles", apiKey = "app-search-only"))
        listOf(first, second, searchOnly).forEach { account ->
            val bound = facade.searchSubtitles(account.id, episode, "en").single()
            val request = api.requests.last()
            assertEquals(account.apiKey, request.headers["Api-Key"])
            assertEquals(account.token.takeIf(String::isNotBlank)?.let { "Bearer $it" }, request.headers["Authorization"])
            assertEquals(if (account.id == second.id) "vip-api.opensubtitles.com" else "api.opensubtitles.com", URI(request.url).host)
            assertEquals("2", query(request.url)["season_number"]); assertEquals("3", query(request.url)["episode_number"])
            assertEquals(account.id, bound.connectionId); assertEquals(account.revision, bound.revision)
            assertEquals(episode, bound.result.identity)
            assertFalse(request.url.contains(account.apiKey)); assertFalse(bound.toString().contains(account.apiKey))
        }
        stored.legacy.forEach { (key, value) -> assertEquals(value, stored.values[key]) }
    }

    @Test fun `OpenSubtitles account download uses scoped metadata credentials then credential free file download`() {
        val account = CameoConnection(id = "first", revision = "revision-first", kind = "opensubtitles",
            apiKey = "app-first", token = "token-first")
        val api = Api { request ->
            assertEquals("app-first", request.headers["Api-Key"])
            assertEquals("Bearer token-first", request.headers["Authorization"])
            if ("/subtitles?" in request.url) openResults else {
                assertTrue(request.url.endsWith("/download")); assertEquals("POST", request.method)
                assertEquals(101, org.json.JSONObject(request.body!!).getInt("file_id"))
                """{"link":"https://dl.opensubtitles.com/fixture.srt"}"""
            }
        }
        var downloads = 0
        val adapter = CameoSavedSubtitles(api, downloads = SubtitleTransport { request ->
            downloads++; assertTrue(request.headers.isEmpty()); assertTrue(request.download)
            SubtitleResponse(caption.toByteArray(), "application/x-subrip")
        })
        val bound = adapter.search(account, episode, "en").single()
        val downloaded = adapter.download(account, bound, RuntimeEnvironment.getApplication())
        val file = File(downloaded.uri.path!!)
        try { assertEquals(caption, file.readText()); assertEquals(1, downloads) } finally { file.delete() }
    }

    @Test fun `SubDL v2 uses account bearer search exact episode files and each documented download route`() {
        val account = CameoConnection(id = "subdl-account", revision = "revision", kind = "subdl", apiKey = "subdl-key")
        val api = Api { request ->
            assertEquals("https://api.subdl.com/api/v2/subtitles/search", request.url.substringBefore('?'))
            assertEquals(mapOf("Authorization" to "Bearer subdl-key"), request.headers)
            val params = query(request.url)
            assertEquals("tt0903747", params["imdb_id"]); assertEquals("2", params["season"]); assertEquals("3", params["episode"])
            assertFalse(params.containsKey("api_key")); assertFalse(request.url.contains("subdl-key"))
            """{"status":true,"results":[{"imdb_id":"tt0903747"}],"subtitles":[
              {"full_season":true,"release_name":"Season 2","unpack_files":[
                {"file_n_id":"exact-file","season":2,"episode":3,"name":"Show.S02E03.srt","url":"/subtitle/exact.srt"},
                {"file_n_id":"wrong-episode","season":2,"episode":4,"name":"Show.S02E04.srt"},
                {"file_n_id":"wrong-season","season":1,"episode":3,"name":"Show.S01E03.srt"},
                {"file_n_id":"contradictory-release","season":2,"episode":3,"release_name":"Show.S02E04","name":"captions.srt"}]},
              {"n_id":"api-only","season":2,"episode":3,"name":"Show.S02E03.srt","release_name":"Show.S02E03"}]}"""
        }
        val requests = mutableListOf<SubtitleRequest>()
        val archive = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("Show.S02E03.srt")); zip.write(caption.toByteArray()); zip.closeEntry()
        } }.toByteArray()
        val adapter = CameoSavedSubtitles(api, downloads = SubtitleTransport { request ->
            requests += request
            SubtitleResponse(if (request.url.contains("/api/v2/")) archive else caption.toByteArray(), "application/octet-stream")
        })
        val results = adapter.search(account, episode, "en")
        assertEquals(listOf("exact-file", "api-only"), results.map { it.result.id })
        results.forEach { result ->
            val download = adapter.download(account, result, RuntimeEnvironment.getApplication())
            File(download.uri.path!!).let { file -> try { assertEquals(caption, file.readText()) } finally { file.delete() } }
        }
        assertEquals("https://dl.subdl.com/subtitle/exact.srt", requests[0].url)
        assertEquals(mapOf("X-API-Key" to "subdl-key"), requests[0].headers)
        assertEquals("https://api.subdl.com/api/v2/subtitles/api-only/download?format=zip", requests[1].url)
        assertEquals(mapOf("Authorization" to "Bearer subdl-key"), requests[1].headers)
    }

    @Test fun `SubSource token-as-key account fallback is used by the scoped provider adapter`() {
        val account = CameoConnection(id = "subsource", revision = "revision", kind = "subsource", token = "fallback-key")
        val api = Api { request ->
            assertEquals("fallback-key", request.headers["X-API-Key"])
            """{"success":true,"data":[]}"""
        }
        assertTrue(CameoSavedSubtitles(api).search(account, episode, "en").isEmpty())
        assertEquals(1, api.requests.size)
    }

    @Test fun `bound subtitle is rejected before any request after account replacement removal or revision change`() {
        val api = Api { request -> when {
            request.url.endsWith("/infos/user") -> """{"data":{}}"""
            "/subtitles?" in request.url -> openResults
            else -> throw AssertionError("A stale result reached the network adapter")
        } }
        val facade = CameoConnections(SavedAccounts(), api)
        val first = facade.save(CameoConnection(kind = "opensubtitles", apiKey = "first-key", token = "first-token"))
        val bound = facade.searchSubtitles(first.id, episode, "en").single()
        val replacement = facade.save(first.copy(token = "replacement-token"))
        val before = api.requests.size
        val context = RuntimeEnvironment.getApplication()
        try { facade.downloadSubtitle(first.id, bound, context); fail("Removed identity downloaded") } catch (_: IllegalStateException) { }
        try { facade.downloadSubtitle(replacement.id, bound, context); fail("Replacement account adopted result") } catch (_: IllegalArgumentException) { }
        val adapter = CameoSavedSubtitles(api, downloads = SubtitleTransport { throw AssertionError("Stale file download") })
        try { adapter.download(first.copy(revision = "changed"), bound, context); fail("Changed revision downloaded") } catch (_: IllegalArgumentException) { }
        val current = facade.searchSubtitles(replacement.id, episode, "en").single()
        facade.remove(replacement.id)
        try { facade.downloadSubtitle(replacement.id, current, context); fail("Removed account downloaded") } catch (_: IllegalStateException) { }
        assertEquals(before + 1, api.requests.size)
    }

    @Test fun `account changing during file response prevents caching its stale subtitle`() {
        val account = CameoConnection(id = "subdl", revision = "current", kind = "subdl", apiKey = "key")
        val bound = CameoSavedSubtitle(account.id, account.revision, "Account", SubtitleResult(SubtitleProvider.SUBDL,
            "file-id", "Show.S02E03", "en", episode, fileName = "Show.S02E03.srt"))
        var current = true
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "downloaded-subtitles")
        val before = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        val adapter = CameoSavedSubtitles(Api { error("No metadata request") }, { check(current) { "Account changed" } },
            SubtitleTransport { current = false; SubtitleResponse(caption.toByteArray(), "application/x-subrip") })
        try { adapter.download(account, bound, context); fail("Stale response was cached") } catch (_: IllegalStateException) { }
        assertEquals(before, directory.listFiles()?.map { it.name }?.toSet().orEmpty())
    }
}
