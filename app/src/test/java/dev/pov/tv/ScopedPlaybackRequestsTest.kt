package dev.pov.tv

import android.net.Uri
import android.os.Looper
import androidx.media3.datasource.DataSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ScopedPlaybackRequestsTest {
    // Public synthetic network fixtures; keep each tested address unchanged.
    private fun fixtureHost(vararg octets: Any): String = octets.joinToString(".")
    private class Connection(url: URL, private val code: Int, private val fields: Map<String, String> = emptyMap(),
        private val body: String = "abc") : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?): String? = fields.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }

    @Test fun `tokens are opaque one use bounded lifetime and keep scoped headers in memory`() {
        val address = "http://${fixtureHost(192, 168, 1, 10)}:8096/Videos/fixture/stream"
        val token = ScopedPlaybackRequests.add(address, mapOf("x-emby-token" to "fixture-private-token"))
        assertFalse(token.contains("fixture"))
        assertEquals("fixture-private-token", ScopedPlaybackRequests.take(token)?.headers?.get("X-Emby-Token"))
        assertNull(ScopedPlaybackRequests.take(token))
        val expired = ScopedPlaybackRequests.add("https://media.example/video", emptyMap())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6))
        assertNull(ScopedPlaybackRequests.take(expired))
    }

    @Test fun `unsafe addresses protocol headers ambiguous names and injection are rejected before handoff`() {
        for (address in listOf("http://public.example/video", "content://provider/video", "file:///private/video",
            "https://user:pass@media.example/video", "https://media.example:0/video", "https://media.example/video#fragment")) {
            try { ScopedPlaybackRequests.add(address, emptyMap()); fail("Unsafe address accepted") } catch (_: IllegalArgumentException) { }
        }
        for (headers in listOf(mapOf("Host" to "attacker.example"), mapOf("Range" to "bytes=0-1"),
            mapOf("Authorization" to "fixture\r\nHost: attacker.example"), mapOf("Authorization" to "a", "authorization" to "b"))) {
            try { ScopedPlaybackRequests.add("https://media.example/video", headers); fail("Unsafe headers accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `redirect and manifest requests send credentials only to exact initial origin`() {
        val connections = mutableListOf<Connection>()
        val source = OriginBoundHttpDataSource("https://server.example/video", mapOf("Authorization" to "Bearer fixture", "X-Plex-Token" to "fixture-plex")) { uri ->
            Connection(uri.toURL(), if (connections.isEmpty()) 302 else 200,
                if (connections.isEmpty()) mapOf("Location" to "https://cdn.example/video") else mapOf("Content-Length" to "3")).also(connections::add)
        }
        assertEquals(3L, source.open(DataSpec(Uri.parse("https://server.example/video"))))
        assertEquals("Bearer fixture", connections[0].getRequestProperty("Authorization"))
        assertEquals("fixture-plex", connections[0].getRequestProperty("X-Plex-Token"))
        assertNull(connections[1].getRequestProperty("Authorization"))
        assertNull(connections[1].getRequestProperty("X-Plex-Token"))
        source.close()
        source.open(DataSpec(Uri.parse("https://server.example:8443/segment")))
        assertNull(connections.last().getRequestProperty("Authorization"))
        source.close()
        source.open(DataSpec(Uri.parse("https://server.example:443/segment")))
        assertEquals("Bearer fixture", connections.last().getRequestProperty("Authorization"))
        source.close()
    }

    @Test fun `HTTPS downgrade is rejected before connecting to plaintext redirect destination`() {
        val connections = mutableListOf<Connection>()
        val source = OriginBoundHttpDataSource("https://server.example/video", mapOf("Authorization" to "fixture")) { uri ->
            Connection(uri.toURL(), 302, mapOf("Location" to "http://${fixtureHost(192, 168, 1, 10)}/video")).also(connections::add)
        }
        try { source.open(DataSpec(Uri.parse("https://server.example/video"))); fail("Downgrade accepted") } catch (expected: IOException) {
            assertFalse(expected.message.orEmpty().contains("fixture"))
        }
        assertEquals(1, connections.size)
        assertTrue(connections.single().disconnected)
    }

    @Test fun `partial ranges match byte offsets and expose only the requested media bytes`() {
        val connection = Connection(URL("https://media.example/video"), 206,
            mapOf("Content-Range" to "bytes 3-5/8", "Content-Length" to "3"), "def")
        val source = OriginBoundHttpDataSource("https://media.example/video", emptyMap()) { connection }
        assertEquals(3L, source.open(DataSpec.Builder().setUri("https://media.example/video").setPosition(3).setLength(3).build()))
        assertEquals("bytes=3-5", connection.getRequestProperty("Range"))
        val bytes = ByteArray(8)
        assertEquals(3, source.read(bytes, 0, bytes.size))
        assertEquals("def", bytes.copyOf(3).toString(Charsets.UTF_8))
        assertEquals(-1, source.read(bytes, 0, bytes.size))
        source.close()
    }

    @Test fun `exact EOF 416 is valid but mismatched or undocumented EOF is an error`() {
        val exact = OriginBoundHttpDataSource("https://media.example/video", emptyMap()) {
            Connection(it.toURL(), 416, mapOf("Content-Range" to "bytes */8"))
        }
        assertEquals(0L, exact.open(DataSpec.Builder().setUri("https://media.example/video").setPosition(8).build()))
        assertEquals(-1, exact.read(ByteArray(1), 0, 1))
        exact.close()
        for (fields in listOf(mapOf("Content-Range" to "bytes */8"), emptyMap())) {
            val invalid = OriginBoundHttpDataSource("https://media.example/video", emptyMap()) { Connection(it.toURL(), 416, fields) }
            try { invalid.open(DataSpec.Builder().setUri("https://media.example/video").setPosition(9).build()); fail("Wrong EOF accepted") }
            catch (_: IOException) { }
        }
    }

    @Test fun `range mismatches inconsistent lengths and truncated bodies fail without exposing private addresses`() {
        for (fields in listOf(mapOf("Content-Range" to "bytes 0-2/8", "Content-Length" to "3"),
            mapOf("Content-Range" to "bytes 3-5/8", "Content-Length" to "4"), emptyMap())) {
            val source = OriginBoundHttpDataSource("https://media.example/video?token=fixture-private", emptyMap()) { Connection(it.toURL(), 206, fields) }
            try { source.open(DataSpec.Builder().setUri("https://media.example/video?token=fixture-private").setPosition(3).build()); fail("Bad range accepted") }
            catch (failure: IOException) { assertFalse(failure.message.orEmpty().contains("fixture-private")) }
        }
        val truncated = OriginBoundHttpDataSource("https://media.example/video", emptyMap()) {
            Connection(it.toURL(), 200, mapOf("Content-Length" to "8"), "abc")
        }
        truncated.open(DataSpec(Uri.parse("https://media.example/video")))
        val buffer = ByteArray(8)
        assertEquals(3, truncated.read(buffer, 0, 8))
        try { truncated.read(buffer, 0, 8); fail("Truncation hidden") } catch (_: IOException) { }
        truncated.close()
    }

    @Test fun `legal refusal error is useful and does not suggest lowering quality`() {
        val source = OriginBoundHttpDataSource("https://media.example/video", emptyMap()) { Connection(it.toURL(), 451) }
        try { source.open(DataSpec(Uri.parse("https://media.example/video"))); fail("HTTP451 accepted") }
        catch (failure: IOException) {
            assertTrue(failure.message.orEmpty().contains("451"))
            assertTrue(failure.message.orEmpty().contains("does not remove"))
        }
    }
}
