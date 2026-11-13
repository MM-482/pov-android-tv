package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderAccountProfilesTest {
    private class Keys : ProviderCredentials {
        val values = mutableMapOf<String, String>()
        var beforeCompare: (() -> Unit)? = null
        @Synchronized override fun get(key: String) = values[key].orEmpty()
        @Synchronized override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
        @Synchronized override fun snapshot(keys: Collection<String>) = keys.associateWith { values[it].orEmpty() }
        @Synchronized override fun putIfUnchanged(expected: Map<String, String>, values: Map<String, String>): Boolean {
            beforeCompare?.let { beforeCompare = null; it() }
            if (expected.any { (key, value) -> this.values[key].orEmpty() != value }) return false
            this.values.putAll(values)
            return true
        }
    }
    private data class Request(val url: String, val token: String)
    private class Http(var answer: (Request) -> String = { account(it) }) : ProviderTransport {
        val calls = mutableListOf<Request>()
        override fun request(url: String, method: String, headers: Map<String, String>, fields: List<Pair<String, String>>?, json: JSONObject?): String =
            Request(url, headers["Authorization"].orEmpty()).let { calls += it; answer(it) }
        companion object {
            fun account(request: Request): String = if (request.url.contains("api.torbox.app"))
                """{"success":true,"data":{"id":1,"email":"fixture@example.invalid","plan":1}}"""
            else """{"id":1,"username":"fixture","type":"premium"}"""
        }
    }

    @Test fun `named accounts preserve both credentials and stable per-account revisions`() {
        val keys = Keys(); val http = Http(); val hub = ProviderHub(keys, http)
        hub.connect("realdebrid", "private-token-alpha", profileLabel = "First")
        val first = hub.accounts("realdebrid").single()
        val revision = hub.accountRevision("realdebrid")
        hub.connect("realdebrid", "private-token-beta", profileLabel = "Second")
        val accounts = hub.accounts("realdebrid")
        assertEquals(setOf("First", "Second"), accounts.map { it.label }.toSet())
        assertEquals("Second", accounts.single { it.active }.label)
        assertFalse(accounts.toString().contains("private-token"))
        hub.selectAccount("realdebrid", first.id)
        assertEquals("private-token-alpha", keys.get("realdebrid.token"))
        assertEquals(revision, hub.accountRevision("realdebrid"))
        assertEquals(2, http.calls.size)
    }

    @Test fun `legacy singleton account imports without network or revision changes`() {
        val keys = Keys().apply { putAll(mapOf("realdebrid.token" to "legacy-private-fixture", "realdebrid.account_revision" to "legacy-revision")) }
        val hub = ProviderHub(keys, Http { error("No network request expected") })
        val first = hub.accounts("realdebrid").single()
        assertTrue(first.active)
        assertEquals("legacy-revision", hub.accountRevision("realdebrid"))
        assertEquals(first, hub.accounts("realdebrid").single())
    }

    @Test fun `unnamed connect replaces active profile and rejected named account preserves all saved profiles`() {
        val keys = Keys(); val http = Http(); val hub = ProviderHub(keys, http)
        hub.connect("realdebrid", "fixture-alpha", profileLabel = "First")
        val first = hub.accounts("realdebrid").single()
        val revision = hub.accountRevision("realdebrid")
        hub.connect("realdebrid", "replacement-fixture")
        assertEquals(first.id, hub.accounts("realdebrid").single().id)
        assertNotEquals(revision, hub.accountRevision("realdebrid"))
        val before = hub.accounts("realdebrid")
        http.answer = { throw ApiException(401, "Fixture rejection") }
        try { hub.connect("realdebrid", "rejected-fixture", profileLabel = "Rejected"); fail("Expected account rejection") } catch (_: ApiException) { }
        assertEquals(before, hub.accounts("realdebrid"))
        assertEquals("replacement-fixture", keys.get("realdebrid.token"))
    }

    @Test fun `removing an inactive profile preserves active account while removing active clears only it`() {
        val keys = Keys(); val hub = ProviderHub(keys, Http())
        hub.connect("realdebrid", "fixture-alpha", profileLabel = "First")
        val first = hub.accounts("realdebrid").single()
        hub.connect("realdebrid", "fixture-beta", profileLabel = "Second")
        val second = hub.accounts("realdebrid").single { it.active }
        val revision = hub.accountRevision("realdebrid")
        hub.removeAccount("realdebrid", first.id)
        assertEquals(revision, hub.accountRevision("realdebrid"))
        assertEquals("fixture-beta", keys.get("realdebrid.token"))
        hub.removeAccount("realdebrid", second.id)
        assertFalse(hub.connected("realdebrid"))
        assertTrue(hub.accounts("realdebrid").isEmpty())
    }

    @Test fun `disconnect active profile preserves explicitly saved alternative accounts`() {
        val keys = Keys(); val hub = ProviderHub(keys, Http())
        hub.connect("realdebrid", "fixture-alpha", profileLabel = "First")
        val first = hub.accounts("realdebrid").single()
        hub.connect("realdebrid", "fixture-beta", profileLabel = "Second")
        hub.disconnect("realdebrid")
        assertFalse(hub.connected("realdebrid"))
        assertEquals(listOf(first.copy(active = false)), hub.accounts("realdebrid"))
        hub.selectAccount("realdebrid", first.id)
        assertTrue(hub.connected("realdebrid"))
        assertEquals("fixture-alpha", keys.get("realdebrid.token"))
    }

    @Test fun `switch away and back cannot revive an old in-flight response`() {
        val keys = Keys(); val http = Http(); val hub = ProviderHub(keys, http)
        hub.connect("realdebrid", "fixture-alpha", profileLabel = "First")
        val first = hub.accounts("realdebrid").single()
        val revision = hub.accountRevision("realdebrid")
        hub.connect("realdebrid", "fixture-beta", profileLabel = "Second")
        val second = hub.accounts("realdebrid").single { it.active }
        hub.selectAccount("realdebrid", first.id)
        http.answer = { request ->
            assertTrue(request.url.contains("/torrents"))
            assertEquals("Bearer fixture-alpha", request.token)
            hub.selectAccount("realdebrid", second.id); hub.selectAccount("realdebrid", first.id)
            "[]"
        }
        try { hub.cloud("realdebrid", expectedRevision = revision); fail("Expected stale activation rejection") } catch (_: IllegalStateException) { }
        assertEquals(revision, hub.accountRevision("realdebrid"))
    }

    @Test fun `direct TorBox auth updates active profile before any account can be reselected`() {
        val keys = Keys(); val http = Http(); val hub = ProviderHub(keys, http)
        hub.connect("torbox", "fixture-alpha", profileLabel = "First")
        val first = hub.accounts("torbox").single()
        hub.connect("torbox", "fixture-beta", profileLabel = "Second")
        val second = hub.accounts("torbox").single { it.active }
        val oldRevision = hub.accountRevision("torbox")
        keys.putAll(mapOf("torbox.token" to "device-auth-fixture"))
        val newRevision = hub.accountRevision("torbox")
        assertNotEquals(oldRevision, newRevision)
        hub.selectAccount("torbox", first.id)
        hub.selectAccount("torbox", second.id)
        assertEquals("device-auth-fixture", keys.get("torbox.token"))
        assertEquals(newRevision, hub.accountRevision("torbox"))
        val count = http.calls.size
        try { hub.playback("torbox", ProviderFile("torrents:1:1", "Film"), oldRevision); fail("Expected old account rejection") } catch (_: IllegalStateException) { }
        assertEquals(count, http.calls.size)
    }

    @Test fun `direct TorBox disconnect cannot leave a reselectable stale token`() {
        val keys = Keys(); val hub = ProviderHub(keys, Http())
        hub.connect("torbox", "fixture-alpha", profileLabel = "First")
        val first = hub.accounts("torbox").single()
        keys.putAll(mapOf("torbox.token" to ""))
        assertTrue(hub.accounts("torbox").isEmpty())
        try { hub.selectAccount("torbox", first.id); fail("Expected disconnected profile removal") } catch (_: IllegalStateException) { }
        keys.putAll(mapOf("torbox.token" to "new-device-fixture"))
        assertNotEquals(first.id, hub.accounts("torbox").single().id)
    }

    @Test fun `simultaneous direct authentication defeats stale profile selection through atomic compare`() {
        val keys = Keys(); val hub = ProviderHub(keys, Http())
        hub.connect("torbox", "fixture-alpha", profileLabel = "First")
        val first = hub.accounts("torbox").single()
        hub.connect("torbox", "fixture-beta", profileLabel = "Second")
        keys.beforeCompare = { keys.putAll(mapOf("torbox.token" to "newer-device-fixture")) }
        try { hub.selectAccount("torbox", first.id); fail("Expected concurrent credential rejection") } catch (_: IllegalStateException) { }
        assertEquals("newer-device-fixture", keys.get("torbox.token"))
    }

    @Test fun `TorBox profile switch invalidates legacy API requests even when switched back`() {
        val keys = Keys(); val hub = ProviderHub(keys, Http())
        hub.connect("torbox", "fixture-alpha", profileLabel = "First")
        val first = hub.accounts("torbox").single()
        hub.connect("torbox", "fixture-beta", profileLabel = "Second")
        val second = hub.accounts("torbox").single { it.active }
        hub.selectAccount("torbox", first.id)
        val api = TorBoxApi(object : TorBoxCredentials {
            override fun get(key: String) = keys.get(key)
            override fun put(key: String, value: String) = keys.putAll(mapOf(key to value))
        }, { _, _, _, _ ->
            hub.selectAccount("torbox", second.id); hub.selectAccount("torbox", first.id)
            JSONObject("""{"success":true,"data":{"id":1,"email":"fixture@example.invalid"}}""")
        })
        try { api.account(); fail("Expected stale legacy request rejection") } catch (error: ApiException) { assertEquals(401, error.code) }
    }
}
