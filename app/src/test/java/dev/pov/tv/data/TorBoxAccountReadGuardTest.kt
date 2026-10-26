package dev.pov.tv.data

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TorBoxAccountReadGuardTest {
    private class Credentials(var token: String = "account-A") : TorBoxCredentials {
        var afterNextRead: (() -> Unit)? = null
        override fun get(key: String): String {
            val current = if (key == "torbox.token") token else ""
            afterNextRead?.let { afterNextRead = null; it() }
            return current
        }
        override fun put(key: String, value: String) { if (key == "torbox.token") token = value }
        override fun remove(key: String) { if (key == "torbox.token") token = "" }
    }
    private fun success(value: Any): JSONObject = JSONObject().put("success", true).put("data", value)

    @Test fun `requestdl query and Authorization always use one token and stale result is discarded`() {
        val credentials = Credentials()
        var requests = 0
        val api = TorBoxApi(credentials, { url, _, headers, _ ->
            requests++
            assertTrue(url.contains("token=account-A&"))
            assertEquals("Bearer account-A", headers["Authorization"])
            credentials.token = "account-B"
            success("https://cdn.example/old-account-video.mkv")
        })
        val revision = api.accountRevision()
        try { api.streamUrl("torrents", 42, 9, revision); fail("Expected changed account rejection") } catch (error: ApiException) {
            assertEquals(401, error.code)
            assertFalse(error.message.orEmpty().contains("account-A"))
        }
        assertEquals(1, requests)
    }

    @Test fun `token switch between query snapshot and network prevents mixed credential request`() {
        val credentials = Credentials()
        var requests = 0
        val api = TorBoxApi(credentials, { _, _, _, _ -> requests++; success("https://cdn.example/video.mkv") })
        val revision = api.accountRevision()
        credentials.afterNextRead = { credentials.token = "account-B" }
        try { api.streamUrl("torrents", 42, 9, revision); fail("Expected pre-request rejection") } catch (_: ApiException) { }
        assertEquals(0, requests)
    }

    @Test fun `queued old account cloud file and transfer operations cannot use replacement token`() {
        val credentials = Credentials()
        var requests = 0
        val api = TorBoxApi(credentials, { _, _, _, _ -> requests++; success(JSONArray()) }, multipartTransport = { _, _, _ -> requests++; success(JSONObject().put("torrent_id", 42)) })
        val revision = api.accountRevision()
        credentials.token = "account-B"
        val actions = listOf<() -> Any>(
            { api.cloud("torrents", revision) },
            { api.folder("torrents", 42, revision) },
            { api.streamUrl("torrents", 42, 9, revision) },
            { api.cached(listOf("a".repeat(40)), revision) },
            { api.addMagnet("a".repeat(40), revision) }
        )
        actions.forEach { action -> try { action(); fail("Expected stale account rejection") } catch (error: ApiException) { assertEquals(401, error.code) } }
        assertEquals(0, requests)
    }

    @Test fun `disconnect stays responsive during cloud request and rejects late private data`() {
        val credentials = Credentials()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = TorBoxApi(credentials, { _, _, _, _ -> entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); success(JSONArray()) })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val cloud = workers.submit<Boolean> { try { api.cloud("torrents"); true } catch (_: ApiException) { false } }
            check(entered.await(10, TimeUnit.SECONDS))
            val disconnect = workers.submit<String> { api.disconnect(); api.accountRevision() }
            assertEquals("", disconnect.get(2, TimeUnit.SECONDS))
            release.countDown(); assertFalse(cloud.get(10, TimeUnit.SECONDS))
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun `failed key verification does not invalidate existing account revision or immutable session`() {
        val credentials = Credentials()
        val reader = TorBoxApi(credentials, { _, _, _, _ -> success(JSONArray()) })
        val revision = reader.accountRevision()
        val authorizer = TorBoxApi(credentials, { _, _, _, _ -> throw ApiException(401, "Invalid candidate credential.") })
        try { authorizer.setApiKey("invalid-candidate"); fail("Expected verification error") } catch (_: ApiException) { }
        assertEquals(revision, reader.accountRevision())
        assertTrue(reader.cloud("torrents", revision).isEmpty())
    }

    @Test fun `multipart transfer uses snapshotted Authorization and rejects changed account response`() {
        val credentials = Credentials()
        val api = TorBoxApi(credentials, multipartTransport = { _, fields, headers ->
            assertEquals("Bearer account-A", headers["Authorization"])
            assertEquals("magnet:?xt=urn:btih:${"a".repeat(40)}", fields["magnet"])
            credentials.token = "account-B"
            success(JSONObject().put("torrent_id", 42))
        })
        try { api.addMagnet("a".repeat(40)); fail("Expected stale transfer response rejection") } catch (error: ApiException) { assertEquals(401, error.code) }
    }

    @Test fun `source preparation expected account is checked before creating any transfer`() {
        val noCalls = object : SourceTorBox {
            override fun accountRevision() = "replacement-account"
            override fun cached(hashes: List<String>): Set<String> = throw AssertionError("Unexpected cache request")
            override fun addMagnet(hash: String): Long = throw AssertionError("Unexpected transfer")
            override fun folder(kind: String, id: Long): CloudFolder = throw AssertionError("Unexpected folder request")
            override fun streamUrl(kind: String, folderId: Long, fileId: Long): String = throw AssertionError("Unexpected playback request")
        }
        val resolver = SourceResolver({ "https://provider.example" }, noCalls)
        try { resolver.prepare(SourceItem("Movie", "test", infoHash = "a".repeat(40)), PlaybackIdentity("tt1234567", "movie", "Movie"), "old-account"); fail("Expected stale account failure") }
        catch (error: ApiException) { assertEquals(401, error.code) }
    }
}
