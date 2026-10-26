package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/** The watched list and every follow-up/401 retry belong to one captured Trakt account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TraktNextEpisodesAccountTest {
    private class Credentials : TraktCredentials {
        val values = ConcurrentHashMap(mapOf(
            "trakt.client_id" to "synthetic-client-original", "trakt.access_token" to "synthetic-access-original",
            "trakt.refresh_token" to "synthetic-refresh-original", "trakt.expires_at" to "9999999999",
            "trakt.account_revision" to "synthetic-account-original"))
        override fun get(key: String, default: String) = values[key] ?: default
        override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
        override fun remove(key: String) { values.remove(key) }
        fun replace() { putAll(mapOf(
            "trakt.client_id" to "synthetic-client-replacement", "trakt.access_token" to "synthetic-access-replacement",
            "trakt.refresh_token" to "synthetic-refresh-replacement", "trakt.account_revision" to "synthetic-account-replacement")) }
    }

    @Test fun `replacement after watched response prevents old show request under new account`() {
        val credentials = Credentials()
        val requests = mutableListOf<String>()
        var authRequests = 0
        val api = TraktApi(credentials,
            authTransport = { _, _, _ -> authRequests++; throw AssertionError("Unexpected authentication") },
            apiTransport = { address, _, headers, _ ->
                requests += address
                assertEquals("Bearer synthetic-access-original", headers["Authorization"])
                credentials.replace()
                watched()
            })

        rejected { api.nextEpisodes("synthetic-account-original") }
        assertEquals(listOf("https://api.trakt.tv/sync/watched/shows?extended=full"), requests)
        assertEquals(0, authRequests)
        assertEquals("synthetic-access-replacement", credentials.get("trakt.access_token"))
    }

    @Test fun `401 arriving after replacement cannot refresh or retry with replacement credentials`() {
        val credentials = Credentials()
        var requests = 0
        var authRequests = 0
        val api = TraktApi(credentials,
            authTransport = { _, _, _ -> authRequests++; throw AssertionError("Replacement must not be refreshed") },
            apiTransport = { _, _, _, _ ->
                requests++
                credentials.replace()
                throw ApiException(401, "Synthetic rejected access")
            })

        rejected { api.nextEpisodes("synthetic-account-original") }
        assertEquals(1, requests)
        assertEquals(0, authRequests)
        assertEquals("synthetic-refresh-replacement", credentials.get("trakt.refresh_token"))
    }

    @Test fun `same account 401 refresh keeps client and revision and returns exact next episode`() {
        val credentials = Credentials()
        val requests = mutableListOf<Pair<String, String>>()
        var authRequests = 0
        val api = TraktApi(credentials,
            authTransport = { _, _, body ->
                authRequests++
                assertEquals("synthetic-client-original", body.getString("client_id"))
                assertEquals("synthetic-refresh-original", body.getString("refresh_token"))
                tokens()
            },
            apiTransport = { address, method, headers, body ->
                assertEquals("GET", method); assertNull(body)
                assertEquals("synthetic-client-original", headers["trakt-api-key"])
                requests += address to headers["Authorization"].orEmpty()
                when {
                    requests.size == 1 -> throw ApiException(401, "Synthetic expired access")
                    address.contains("/sync/watched/") -> watched()
                    else -> """{"next_episode":{"season":3,"number":7}}"""
                }
            })

        assertEquals(listOf(PlaybackIdentity("tt0903747", "series", "Series Fixture", 3, 7)), api.nextEpisodes())
        assertEquals(1, authRequests)
        assertEquals(listOf("Bearer synthetic-access-original", "Bearer synthetic-access-refreshed", "Bearer synthetic-access-refreshed"), requests.map { it.second })
        assertEquals("synthetic-account-original", credentials.get("trakt.account_revision"))
    }

    @Test fun `account change during refresh rejects late tokens and issues no retried read`() {
        val credentials = Credentials()
        var requests = 0
        val api = TraktApi(credentials,
            authTransport = { _, _, _ -> credentials.replace(); tokens() },
            apiTransport = { _, _, _, _ -> requests++; throw ApiException(401, "Synthetic expired access") })

        rejected { api.nextEpisodes("synthetic-account-original") }
        assertEquals(1, requests)
        assertEquals("synthetic-access-replacement", credentials.get("trakt.access_token"))
        assertEquals("synthetic-refresh-replacement", credentials.get("trakt.refresh_token"))
        assertEquals("synthetic-account-replacement", credentials.get("trakt.account_revision"))
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected changed-account rejection") }
        catch (error: ApiException) { assertEquals(401, error.code) }
    }
    private fun watched() = """[{"last_watched_at":"2026-10-25T12:00:00Z","show":{"title":"Series Fixture","ids":{"trakt":37,"imdb":"tt0903747"}}}]"""
    private fun tokens() = JSONObject().put("access_token", "synthetic-access-refreshed")
        .put("refresh_token", "synthetic-refresh-refreshed").put("created_at", System.currentTimeMillis() / 1000)
        .put("expires_in", 3600)
}
