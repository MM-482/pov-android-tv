package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SimklApiTest {
    private class Keys(initial: Map<String, String>) : TrackerCredentials {
        val values = initial.toMutableMap()
        override fun get(key: String, default: String) = synchronized(values) { values[key] ?: default }
        override fun putAll(values: Map<String, String>) = synchronized(this.values) { this.values.putAll(values) }
    }
    private val epoch = 1_800_000_000L
    private val movie = PlaybackIdentity("tt0133093", "movie", "The Matrix")
    private val episode = PlaybackIdentity("tt0903747", "series", "Breaking Bad", 1, 2)
    private val device = """{"device_code":"device-secret","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin","verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}"""
    private val tokens = """{"access_token":"new-access","refresh_token":"same-refresh","token_type":"Bearer","expires_in":604800,"scope":"media:read media:write"}"""
    private fun connectedKeys(expiry: Long = epoch + 600_000) = Keys(mapOf(
        "simkl.client_id" to "v2-client", "simkl.access_token" to "old-access", "simkl.refresh_token" to "same-refresh",
        "simkl.expires_at" to "$expiry", "simkl.refresh_expires_at" to "${epoch + 12_000_000}",
        "simkl.scope" to "media:read media:write", "simkl.account_revision" to UUID.randomUUID().toString()))

    @Test fun deviceV2UsesReadWriteScopeAndHonorsPollingInterval() {
        val keys = Keys(mapOf("simkl.client_id" to "v2-client"))
        var now = 0L
        var polls = 0
        val api = SimklApi(keys, { url, method, headers, body ->
            assertEquals("POST", method)
            assertTrue(url.contains("client_id=v2-client"))
            assertTrue(headers["User-Agent"]!!.contains("POV"))
            if (url.contains("/oauth2/device")) { assertEquals("media:read media:write", body!!.getString("scope")); device }
            else { polls++; assertEquals("urn:ietf:params:oauth:grant-type:device_code", body!!.getString("grant_type")); tokens }
        }, { now }, { epoch })
        val auth = api.startDeviceAuth()
        assertEquals("BDWP-HQPK", auth.userCode)
        assertFalse(api.pollDeviceAuth(auth))
        assertEquals(0, polls)
        now = 5000
        assertTrue(api.pollDeviceAuth(auth))
        assertTrue(api.connected())
        assertEquals("same-refresh", keys.get("simkl.refresh_token"))
        assertTrue(api.accountRevision().isNotBlank())
    }

    @Test fun slowDownAddsFiveSecondsAndActuallyWaits() {
        var now = 0L
        var polls = 0
        val api = SimklApi(Keys(mapOf("simkl.client_id" to "client")), { url, _, _, _ ->
            if (url.contains("/oauth2/device")) device else { polls++; throw ApiException(400, "Slow down.", "slow_down") }
        }, { now }, { epoch })
        val auth = api.startDeviceAuth()
        now = 5000; assertFalse(api.pollDeviceAuth(auth)); assertEquals(1, polls)
        now = 10000; assertFalse(api.pollDeviceAuth(auth)); assertEquals(1, polls)
        now = 15000; assertFalse(api.pollDeviceAuth(auth)); assertEquals(2, polls)
    }

    @Test fun cancelledDeviceResponseCannotSaveLateTokens() {
        val keys = Keys(mapOf("simkl.client_id" to "client"))
        var now = 0L
        lateinit var api: SimklApi
        lateinit var auth: DeviceAuth
        api = SimklApi(keys, { url, _, _, _ -> if (url.contains("/oauth2/device")) device else { api.cancelDeviceAuth(auth); tokens } }, { now }, { epoch })
        auth = api.startDeviceAuth(); now = 5000
        expectApiError { api.pollDeviceAuth(auth) }
        assertFalse(api.connected())
    }

    @Test fun expiredAndUntrustedDeviceCodesAreRejected() {
        var now = 0L
        val api = SimklApi(Keys(mapOf("simkl.client_id" to "client")), { _, _, _, _ -> device }, { now }, { epoch })
        val auth = api.startDeviceAuth(); now = 900_000
        assertEquals(410, expectApiError { api.pollDeviceAuth(auth) }.code)
        expectApiError { api.parseDeviceAuth(JSONObject(device).put("verification_uri_complete", "https://simkl.com.evil.example/pin")) }
        expectApiError { api.parseDeviceAuth(JSONObject(device).put("verification_uri_complete", "https://simkl.com/pin?device_code=device-secret")) }
    }

    @Test fun refreshKeepsStableRevisionAndSupportsNonRotatingRefreshToken() {
        val keys = connectedKeys(epoch + 100)
        val revision = keys.get("simkl.account_revision")
        var refreshes = 0
        val api = SimklApi(keys, { url, _, headers, body ->
            if (url.contains("/oauth2/token")) { refreshes++; assertEquals("same-refresh", body!!.getString("refresh_token")); tokens }
            else { assertEquals("Bearer new-access", headers["Authorization"]); "[]" }
        }, { 0 }, { epoch })
        assertTrue(api.paused().isEmpty())
        assertEquals(1, refreshes)
        assertEquals(revision, api.accountRevision())
        assertEquals("same-refresh", keys.get("simkl.refresh_token"))
        assertEquals(epoch + 180 * 86400L, keys.get("simkl.refresh_expires_at").toLong())
    }

    @Test fun clientChangeDuringRefreshCannotOverwriteNewAccountState() {
        val keys = connectedKeys(epoch + 100)
        lateinit var api: SimklApi
        api = SimklApi(keys, { url, _, _, _ ->
            if (url.contains("/oauth2/token")) { api.setClientId("new-v2-client"); tokens } else "[]"
        }, { 0 }, { epoch })
        expectApiError { api.paused() }
        assertFalse(api.connected())
        assertEquals("new-v2-client", keys.get("simkl.client_id"))
        assertEquals("", keys.get("simkl.access_token"))
    }

    @Test fun oldQueuedEventCannotReachANewAccount() {
        val keys = connectedKeys()
        val oldRevision = keys.get("simkl.account_revision")
        keys.putAll(mapOf("simkl.account_revision" to "different-account"))
        var calls = 0
        val api = SimklApi(keys, { _, _, _, _ -> calls++; "{}" }, { 0 }, { epoch })
        assertFalse(api.progressForAccount(movie, 10.0, "start", oldRevision))
        assertEquals(0, calls)
    }

    @Test fun scrobbleUsesExactEpisodeAndEnforcesTwentySecondWindow() {
        var now = 0L
        val bodies = mutableListOf<JSONObject>()
        val api = SimklApi(connectedKeys(), { url, _, _, body ->
            assertTrue(url.contains("/scrobble/")); assertFalse(url.contains("allow_rewatch"))
            bodies += body!!; "{\"action\":\"start\"}"
        }, { now }, { epoch })
        api.progress(episode, 10.125, "start")
        assertEquals(1, bodies.single().getJSONObject("episode").getInt("season"))
        assertEquals(2, bodies.single().getJSONObject("episode").getInt("number"))
        assertEquals("tt0903747", bodies.single().getJSONObject("show").getJSONObject("ids").getString("imdb"))
        assertEquals(429, expectApiError { api.progress(episode, 11.0, "pause") }.code)
        now = 20_000; api.progress(episode, 11.0, "pause")
        assertEquals(2, bodies.size)
    }

    @Test fun serverRateLimit400IsTranslatedToRetryableWindow() {
        val api = SimklApi(connectedKeys(), { _, _, _, _ -> throw ApiException(400, "Rate limit.", "RATE_LIMIT") }, { 0 }, { epoch })
        val error = expectApiError { api.progress(movie, 12.0, "start") }
        assertEquals(429, error.code)
        assertEquals(20, error.retryAfterSeconds)
    }

    @Test fun readOnlyGrantNeverAttemptsAWrite() {
        val keys = connectedKeys(); keys.putAll(mapOf("simkl.scope" to "media:read"))
        var calls = 0
        val api = SimklApi(keys, { _, _, _, _ -> calls++; "{}" }, { 0 }, { epoch })
        assertEquals(403, expectApiError { api.progress(movie, 1.0, "start") }.code)
        assertEquals(0, calls)
    }

    @Test fun unchangedActivitiesAvoidASecondLibraryPullAndDeltaMovesItemsOutOfWatchlist() {
        var phase = 0
        val calls = mutableListOf<String>()
        val api = SimklApi(connectedKeys(), { url, _, _, _ ->
            calls += url
            when {
                url.contains("/sync/activities") -> if (phase < 2) activity("2026-10-20T10:00:00Z") else activity("2026-10-20T11:00:00Z")
                url.contains("date_from=") -> """{"movies":[{"status":"completed","movie":{"title":"The Matrix","ids":{"simkl":1,"imdb":"tt0133093"}}}]}"""
                else -> """{"movies":[{"status":"plantowatch","movie":{"title":"The Matrix","ids":{"simkl":1,"imdb":"tt0133093"}}}]}"""
            }
        }, { 0 }, { epoch })
        assertEquals(1, api.watchlist("movie").size)
        phase = 1; assertEquals(1, api.watchlist("movie").size)
        assertEquals(1, calls.count { it.contains("all-items") })
        phase = 2; assertTrue(api.watchlist("movie").isEmpty())
        assertTrue(calls.last().contains("date_from=2026-10-20T10%3A00%3A00Z"))
    }

    @Test fun libraryRemovalUsesMinimalIdReconciliation() {
        var removed = false
        val calls = mutableListOf<String>()
        val api = SimklApi(connectedKeys(), { url, _, _, _ ->
            calls += url
            when {
                url.contains("activities") -> if (!removed) activity("one", "old") else activity("two", "new", listStamp = "one")
                url.contains("simkl_ids_only") -> "{}"
                else -> """{"movies":[{"status":"plantowatch","movie":{"title":"The Matrix","ids":{"simkl":1,"imdb":"tt0133093"}}}]}"""
            }
        }, { 0 }, { epoch })
        assertEquals(1, api.watchlist("movie").size)
        removed = true; assertTrue(api.watchlist("movie").isEmpty())
        assertTrue(calls.last().contains("extended=simkl_ids_only"))
    }

    @Test fun failedLibraryFetchDoesNotAdvanceTheActivitiesSnapshot() {
        val keys = connectedKeys()
        val api = SimklApi(keys, { url, _, _, _ ->
            if (url.contains("activities")) activity("snapshot") else throw ApiException(500, "Service unavailable.")
        }, { 0 }, { epoch })
        expectApiError { api.watchlist("movies") }
        assertEquals("", keys.get("simkl.library.movies"))
    }

    @Test fun premiumOnly200IsAnActionableLimitation() {
        val api = SimklApi(connectedKeys(), { _, _, _, _ -> "{\"error\":\"premium_only\"}" }, { 0 }, { epoch })
        val error = expectApiError { api.listItems("123") }
        assertTrue(error.message!!.contains("PRO or VIP"))
    }

    @Test fun pausedEpisodePrefersOriginalTvdbNumbersForCorrectResume() {
        val api = SimklApi(connectedKeys(), { _, _, _, _ -> """[{"id":12,"progress":45.5,"paused_at":"2026-10-20T10:00:00Z","show":{"title":"Show","ids":{"imdb":"tt0903747"}},"episode":{"season":1,"number":35,"tvdb_season":2,"tvdb_number":2}}]""" }, { 0 }, { epoch })
        val item = api.paused().single()
        assertEquals(2, item.identity.season)
        assertEquals(2, item.identity.episode)
        assertEquals(45.5, item.progress, 0.01)
    }

    private fun activity(all: String, removed: String = "old", listStamp: String = all) = JSONObject().put("all", all)
        .put("movies", JSONObject().put("plantowatch", listStamp).put("completed", listStamp).put("removed_from_list", removed)).toString()
    private fun expectApiError(action: () -> Unit): ApiException = try { action(); throw AssertionError("Expected API failure") } catch (error: ApiException) { error }
}
