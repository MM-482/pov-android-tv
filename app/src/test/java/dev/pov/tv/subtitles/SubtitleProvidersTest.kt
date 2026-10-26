package dev.pov.tv.subtitles

import dev.pov.tv.data.PlaybackIdentity
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SubtitleProvidersTest {
    private val movie = PlaybackIdentity("tt0133093", "movie", "The Matrix")
    private val episode = PlaybackIdentity("tt0903747", "series", "Breaking Bad", 1, 2)
    private class Keys(val values: MutableMap<String, String>) : SubtitleCredentials {
        override fun get(key: String) = synchronized(values) { values[key].orEmpty() }
        override fun openSnapshot() = synchronized(values) { OpenSubtitlesSnapshot.read(::get) }
        override fun putIfOpenUnchanged(expected: OpenSubtitlesSnapshot, values: Map<String, String>): Boolean = synchronized(this.values) {
            if (OpenSubtitlesSnapshot.read(::get) != expected) false else { this.values.putAll(values); true }
        }
        fun replaceOpen(values: Map<String, String>) = synchronized(this.values) {
            this.values.putAll(values + (SubtitleSettings.OPEN_SUBTITLES_REVISION to java.util.UUID.randomUUID().toString()))
        }
    }
    private class MockHttp(vararg responses: String) : SubtitleTransport {
        val requests = mutableListOf<SubtitleRequest>()
        val replies = ArrayDeque(responses.toList())
        override fun execute(request: SubtitleRequest): SubtitleResponse {
            requests += request
            check(replies.isNotEmpty()) { "Unexpected HTTP request" }
            return SubtitleResponse(replies.removeFirst().toByteArray(), "application/json")
        }
    }

    @Test fun openSubtitlesUsesParentImdbAndEpisodeWithoutLoggingInDuringSearch() {
        val http = MockHttp("""{"data":[{"attributes":{"language":"en","release":"Show.S01E02","files":[{"file_id":77,"file_name":"Show.S01E02.srt"}]}}]}""")
        val service = SubtitleProviders(Keys(mutableMapOf(SubtitleSettings.OPEN_SUBTITLES_API_KEY to "os-key")), http)
        val found = service.search(SubtitleProvider.OPEN_SUBTITLES, episode, "en")
        assertEquals("77", found.single().id)
        assertTrue(http.requests.single().url.contains("parent_imdb_id=903747"))
        assertTrue(http.requests.single().url.contains("episode_number=2"))
        assertFalse(http.requests.single().url.contains("os-key"))
        assertEquals(mapOf("Api-Key" to "os-key"), http.requests.single().headers)
    }

    @Test fun openSubtitlesDownloadUsesBothCredentialsAndDoesNotSendThemToFileHost() {
        val http = MockHttp(
            """{"token":"session-token","base_url":"vip-api.opensubtitles.com"}""",
            """{"link":"https://dl.opensubtitles.com/download/file.srt","file_name":"file.srt","remaining":9}""",
            "1\n00:00:00,000 --> 00:00:01,000\nHello")
        val service = SubtitleProviders(Keys(mutableMapOf(SubtitleSettings.OPEN_SUBTITLES_API_KEY to "os-key",
            SubtitleSettings.OPEN_SUBTITLES_USERNAME to "username", SubtitleSettings.OPEN_SUBTITLES_PASSWORD to "password")), http)
        service.download(SubtitleResult(SubtitleProvider.OPEN_SUBTITLES, "77", "The Matrix", "en", movie, "movie.srt"))
        assertTrue(http.requests[0].url.endsWith("/login"))
        assertEquals("username", http.requests[0].body!!.getString("username"))
        assertEquals("https://vip-api.opensubtitles.com/api/v1/download", http.requests[1].url)
        assertEquals("Bearer session-token", http.requests[1].headers["Authorization"])
        assertEquals("os-key", http.requests[1].headers["Api-Key"])
        assertEquals(77, http.requests[1].body!!.getInt("file_id"))
        assertTrue(http.requests[2].headers.isEmpty())
    }

    @Test fun rejectedLoginIsNotRetriedWithTheSameCredentials() {
        val keys = Keys(mutableMapOf(SubtitleSettings.OPEN_SUBTITLES_API_KEY to "os-key", SubtitleSettings.OPEN_SUBTITLES_USERNAME to "bad-user", SubtitleSettings.OPEN_SUBTITLES_PASSWORD to "bad-pass"))
        var calls = 0
        val service = SubtitleProviders(keys, SubtitleTransport { calls++; throw SubtitleException("Authorization failed.", 401) })
        val result = SubtitleResult(SubtitleProvider.OPEN_SUBTITLES, "77", "Movie", "en", movie)
        repeat(2) { try { service.download(result); fail("Should reject login") } catch (_: SubtitleException) { } }
        assertEquals(1, calls)
    }

    @Test fun subdlUnpacksOnlyTheRequestedEpisode() {
        val http = MockHttp("""{"status":true,"results":[{"imdb_id":"tt0903747"}],"subtitles":[{"full_season":true,"release_name":"Season 1","unpack_files":[{"season":1,"episode":1,"url":"/subtitle/1/file1","name":"Show.S01E01.srt"},{"season":1,"episode":2,"url":"/subtitle/1/file2","name":"Show.S01E02.srt"}]}]}""")
        val service = SubtitleProviders(Keys(mutableMapOf(SubtitleSettings.SUBDL_API_KEY to "subdl-key")), http)
        val result = service.search(SubtitleProvider.SUBDL, episode, "en").single()
        assertEquals("Show.S01E02.srt", result.fileName)
        assertEquals("https://dl.subdl.com/subtitle/1/file2", result.downloadUrl)
        assertTrue(http.requests.single().url.contains("unpack=1"))
    }

    @Test fun subsourceUsesDocumentedImdbSeasonAndSubtitleEndpoints() {
        val http = MockHttp("""{"success":true,"data":[{"movieId":5,"imdbId":"tt0903747","season":1}]}""",
            """{"success":true,"data":[{"subtitleId":15,"language":"english","releaseInfo":["Show.S01E02","WEB-DL"],"hearingImpaired":true}]}""",
            "zip bytes")
        val service = SubtitleProviders(Keys(mutableMapOf(SubtitleSettings.SUBSOURCE_API_KEY to "source-key")), http)
        val result = service.search(SubtitleProvider.SUBSOURCE, episode, "en").single()
        service.download(result)
        assertTrue(http.requests[0].url.contains("searchType=imdb"))
        assertTrue(http.requests[0].url.contains("season=1"))
        assertTrue(http.requests[1].url.contains("language=english"))
        assertEquals("https://api.subsource.net/api/v1/subtitles/15/download", http.requests[2].url)
        assertEquals("source-key", http.requests[2].headers["X-API-Key"])
    }

    @Test fun missingProviderKeysAreReportedWithoutAnyNetworkCalls() {
        val http = MockHttp()
        val report = SubtitleRepository(File("unused-subtitle-test-cache"), Keys(mutableMapOf()), http).search(movie)
        assertTrue(report.items.isEmpty())
        assertEquals(3, report.issues.size)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun oneProviderQuotaFailureDoesNotHideOtherProviderResults() {
        val keys = Keys(mutableMapOf(SubtitleSettings.OPEN_SUBTITLES_API_KEY to "os", SubtitleSettings.SUBDL_API_KEY to "dl"))
        val http = SubtitleTransport { request ->
            if (request.url.contains("opensubtitles")) throw SubtitleException("Quota reached.", 429, 60)
            SubtitleResponse("""{"status":true,"results":[{"imdb_id":"tt0133093"}],"subtitles":[{"name":"Matrix.srt","url":"/subtitle/5.zip"}]}""".toByteArray(), "application/json")
        }
        val report = SubtitleRepository(File("unused-subtitle-test-cache"), keys, http).search(movie)
        assertEquals(SubtitleProvider.SUBDL, report.items.single().provider)
        assertTrue(report.issues.any { it.provider == SubtitleProvider.OPEN_SUBTITLES && it.message.contains("Quota") })
    }

    @Test fun subsourceSearchFindsEarlyAndLateEpisodesBeyondFirstHundredUnrelatedResults() {
        for (requested in listOf(1, 20)) {
            val target = episode.copy(episode = requested)
            val visited = mutableListOf<Int>()
            val service = SubtitleProviders(Keys(mutableMapOf(SubtitleSettings.SUBSOURCE_API_KEY to "source-key")), SubtitleTransport { request ->
                if (request.url.contains("/movies/search")) response("""{"success":true,"data":[{"movieId":5,"imdbId":"tt0903747","season":1}]}""")
                else {
                    val page = Regex("[?&]page=([0-9]+)").find(request.url)!!.groupValues[1].toInt()
                    visited += page
                    val otherEpisode = if (requested == 1) 20 else 1
                    val rows = if (page < 3) (1..50).map { subtitleRow(page * 1000 + it, otherEpisode) }
                        else listOf(subtitleRow(3001, requested))
                    response(subsourcePage(page, 3, rows).toString())
                }
            })
            val found = service.search(SubtitleProvider.SUBSOURCE, target, "en")
            assertEquals(listOf(1, 2, 3), visited)
            assertEquals("3001", found.single().id)
            assertEquals("en", found.single().language)
        }
    }

    @Test fun subsourceRepeatedPageStopsWithoutRunawayRequests() {
        var subtitleCalls = 0
        val service = SubtitleProviders(Keys(mutableMapOf(SubtitleSettings.SUBSOURCE_API_KEY to "source-key")), SubtitleTransport { request ->
            if (request.url.contains("/movies/search")) response("""{"success":true,"data":[{"movieId":5,"imdbId":"tt0903747","season":1}]}""")
            else { subtitleCalls++; response(subsourcePage(1, 100, listOf(subtitleRow(1, 1))).toString()) }
        })
        try { service.search(SubtitleProvider.SUBSOURCE, episode, "en"); fail("Repeated page should be rejected") }
        catch (error: SubtitleException) { assertTrue(error.message!!.contains("repeated")) }
        assertEquals(2, subtitleCalls)
    }

    @Test fun subsourceBoundsHugePaginationEvenWhenEveryPageHasUniqueUnrelatedResults() {
        var subtitleCalls = 0
        val service = SubtitleProviders(Keys(mutableMapOf(SubtitleSettings.SUBSOURCE_API_KEY to "source-key")), SubtitleTransport { request ->
            if (request.url.contains("/movies/search")) response("""{"success":true,"data":[{"movieId":5,"imdbId":"tt0903747","season":1}]}""")
            else { subtitleCalls++; response(subsourcePage(subtitleCalls, Int.MAX_VALUE, listOf(subtitleRow(subtitleCalls, 1))).toString()) }
        })
        try { service.search(SubtitleProvider.SUBSOURCE, episode, "en"); fail("Unbounded page count should be stopped") }
        catch (error: SubtitleException) { assertTrue(error.message!!.contains("too many")) }
        assertEquals(20, subtitleCalls)
    }

    @Test fun combinedEpisodeSubtitleSurvivesRepositoryEpisodeFiltering() {
        val http = MockHttp("""{"data":[{"attributes":{"language":"en","files":[{"file_id":77,"file_name":"Show.S01E01E02.srt"}]}}]}""")
        val report = SubtitleRepository(File("unused-subtitle-test-cache"), Keys(mutableMapOf(SubtitleSettings.OPEN_SUBTITLES_API_KEY to "os-key")), http).search(episode, "en")
        assertEquals("Show.S01E01E02.srt", report.items.single().fileName)
        assertEquals("en", report.items.single().language)
    }

    @Test fun lateOpenLoginCannotRestoreATokenAfterConnectionWasCleared() {
        val keys = openKeys()
        var calls = 0
        val service = SubtitleProviders(keys, SubtitleTransport { request ->
            calls++; assertTrue(request.url.endsWith("/login"))
            keys.replaceOpen(mapOf(SubtitleSettings.OPEN_SUBTITLES_API_KEY to "", SubtitleSettings.OPEN_SUBTITLES_USERNAME to "",
                SubtitleSettings.OPEN_SUBTITLES_PASSWORD to "", "subtitles.opensubtitles.token" to ""))
            response("""{"token":"old-account-token","base_url":"api.opensubtitles.com"}""")
        })
        expectChangedConnection { service.download(SubtitleResult(SubtitleProvider.OPEN_SUBTITLES, "77", "Movie", "en", movie)) }
        assertEquals(1, calls)
        assertEquals("", keys.get("subtitles.opensubtitles.token"))
        assertEquals("", keys.get(SubtitleSettings.OPEN_SUBTITLES_API_KEY))
    }

    @Test fun lateOpenLoginCannotOverwriteAReplacementAccountsToken() {
        val keys = openKeys()
        val service = SubtitleProviders(keys, SubtitleTransport {
            keys.replaceOpen(mapOf(SubtitleSettings.OPEN_SUBTITLES_API_KEY to "new-key", SubtitleSettings.OPEN_SUBTITLES_USERNAME to "new-user",
                SubtitleSettings.OPEN_SUBTITLES_PASSWORD to "new-password", "subtitles.opensubtitles.token" to "new-account-token"))
            response("""{"token":"old-account-token","base_url":"api.opensubtitles.com"}""")
        })
        expectChangedConnection { service.download(SubtitleResult(SubtitleProvider.OPEN_SUBTITLES, "77", "Movie", "en", movie)) }
        assertEquals("new-account-token", keys.get("subtitles.opensubtitles.token"))
        assertEquals("new-user", keys.get(SubtitleSettings.OPEN_SUBTITLES_USERNAME))
    }

    @Test fun clearingAndReaddingIdenticalCredentialsStillInvalidatesTheOldLoginResponse() {
        val keys = openKeys()
        val service = SubtitleProviders(keys, SubtitleTransport {
            keys.replaceOpen(mapOf("subtitles.opensubtitles.token" to ""))
            response("""{"token":"stale-same-account-token","base_url":"api.opensubtitles.com"}""")
        })
        expectChangedConnection { service.download(SubtitleResult(SubtitleProvider.OPEN_SUBTITLES, "77", "Movie", "en", movie)) }
        assertEquals("", keys.get("subtitles.opensubtitles.token"))
    }

    @Test fun lateRejectedLoginDoesNotPersistARejectionAfterAccountReplacement() {
        val keys = openKeys()
        val service = SubtitleProviders(keys, SubtitleTransport {
            keys.replaceOpen(mapOf(SubtitleSettings.OPEN_SUBTITLES_USERNAME to "replacement-user"))
            throw SubtitleException("Authorization failed.", 401)
        })
        try { service.download(SubtitleResult(SubtitleProvider.OPEN_SUBTITLES, "77", "Movie", "en", movie)); fail("Login should fail") }
        catch (_: SubtitleException) { }
        assertEquals("", keys.get("subtitles.opensubtitles.rejected_fingerprint"))
        assertEquals("replacement-user", keys.get(SubtitleSettings.OPEN_SUBTITLES_USERNAME))
    }

    private fun openKeys() = Keys(mutableMapOf(SubtitleSettings.OPEN_SUBTITLES_API_KEY to "os-key",
        SubtitleSettings.OPEN_SUBTITLES_USERNAME to "username", SubtitleSettings.OPEN_SUBTITLES_PASSWORD to "password"))
    private fun response(value: String) = SubtitleResponse(value.toByteArray(), "application/json")
    private fun subtitleRow(id: Int, episode: Int) = JSONObject().put("subtitleId", id).put("language", "english")
        .put("releaseInfo", JSONArray().put("Show.S01E${episode.toString().padStart(2, '0')}"))
    private fun subsourcePage(page: Int, pages: Int, rows: List<JSONObject>) = JSONObject().put("success", true)
        .put("data", JSONArray(rows)).put("pagination", JSONObject().put("page", page).put("pages", pages))
    private fun expectChangedConnection(action: () -> Unit) {
        try { action(); fail("A stale login must not be persisted") }
        catch (error: SubtitleException) { assertTrue(error.message!!.contains("connection changed")) }
    }
}
