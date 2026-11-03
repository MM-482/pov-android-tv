package dev.pov.tv

import android.net.Uri
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SameOriginMediaRequestsTest {
    private class Connection(url: URL, private val code: Int = 200, private val location: String? = null,
        private val contentRange: String? = null) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?): String? = when (name) {
            "Location" -> location
            "Content-Length" -> "3"
            "Content-Range" -> contentRange
            else -> null
        }
        override fun getInputStream() = ByteArrayInputStream("abc".toByteArray())
    }
    private fun request(url: String, origin: String = "https://server.example", headers: Map<String, String> = mapOf("Authorization" to "Bearer fixture")) =
        SameOriginMediaRequests.take(SameOriginMediaRequests.add(url, headers, origin))!!

    @Test fun `opaque handoff is single use and does not reveal credentials or source URL`() {
        val token = SameOriginMediaRequests.add("https://server.example/video?fixture=signed", mapOf("X-Plex-Token" to "private-fixture"), "https://server.example")
        assertFalse(token.contains("private-fixture")); assertFalse(token.contains("server.example"))
        assertEquals("private-fixture", SameOriginMediaRequests.take(token)?.headers?.get("X-Plex-Token"))
        assertNull(SameOriginMediaRequests.take(token))
    }

    @Test fun `HTTPS redirect can reach CDN but never forwards origin credentials or injected data spec authorization`() {
        val connections = mutableListOf<Connection>()
        val source = SameOriginMediaDataSource(request("https://server.example/video"), connect = { uri ->
            Connection(uri.toURL(), if (connections.isEmpty()) 302 else 200, "https://cdn.example/video").also(connections::add)
        })
        val spec = DataSpec.Builder().setUri("https://server.example/video")
            .setHttpRequestHeaders(mapOf("Authorization" to "injected", "Cookie" to "private-fixture")).build()
        assertEquals(3L, source.open(spec))
        assertEquals("Bearer fixture", connections.first().getRequestProperty("Authorization"))
        assertNull(connections.last().getRequestProperty("Authorization"))
        assertTrue(connections.all { it.getRequestProperty("Cookie") == null })
        val bytes = ByteArray(3); assertEquals(3, source.read(bytes, 0, 3)); assertEquals("abc", String(bytes))
        source.close()
    }

    @Test fun `Plex and addon request headers work on original server and are stripped together at CDN redirect`() {
        val headers = mapOf("X-Plex-Token" to "private-plex-fixture", "X-Plex-Product" to "Cameo",
            "X-Plex-Client-Identifier" to "fixture-client", "Accept" to "video/*",
            "X-Api-Key" to "private-addon-fixture", "Cookie" to "session=private-cookie-fixture")
        val connections = mutableListOf<Connection>()
        val source = SameOriginMediaDataSource(request("https://server.example/video", headers = headers), connect = { uri ->
            Connection(uri.toURL(), if (connections.isEmpty()) 302 else 200, "https://cdn.example/video").also(connections::add)
        })
        assertEquals(3L, source.open(DataSpec(Uri.parse("https://server.example/video"))))
        headers.forEach { (key, value) ->
            assertEquals(key, value, connections.first().getRequestProperty(key))
            assertNull(key, connections.last().getRequestProperty(key))
        }
        source.close()
    }

    @Test fun `HTTP request only opens exact configured local server after all resolved addresses are private`() {
        var opened = 0
        val request = request("http://nas.example:8096/video", "http://nas.example:8096")
        val source = SameOriginMediaDataSource(request, connect = { uri -> opened++; Connection(uri.toURL()) },
            resolve = { arrayOf(InetAddress.getByName("192.168.1.10"), InetAddress.getByName("8.8.8.8")) })
        try { source.open(DataSpec(Uri.parse(request.url))); fail("Public address accepted") } catch (_: IOException) {}
        assertEquals(0, opened)
        var pinned: Connection? = null
        val local = SameOriginMediaDataSource(request, connect = { uri ->
            opened++; assertEquals("192.168.1.10", uri.host)
            Connection(uri.toURL()).also { pinned = it }
        },
            resolve = { arrayOf(InetAddress.getByName("192.168.1.10")) })
        assertEquals(3L, local.open(DataSpec(Uri.parse(request.url))))
        assertEquals("nas.example:8096", pinned!!.getRequestProperty("Host"))
        assertEquals(1, opened); local.close()
    }

    @Test fun `HTTP redirect to another LAN host is rejected before sending headers`() {
        var opened = 0
        val request = request("http://192.168.1.10/video", "http://192.168.1.10")
        val source = SameOriginMediaDataSource(request, connect = { uri ->
            opened++; Connection(uri.toURL(), 302, "http://192.168.1.11/video")
        }, resolve = { arrayOf(InetAddress.getByName("192.168.1.10")) })
        try { source.open(DataSpec(Uri.parse(request.url))); fail("Foreign LAN origin accepted") } catch (_: IOException) {}
        assertEquals(1, opened)
    }

    @Test fun `exact end of file range response remains a legitimate EOF rather than playback failure`() {
        val source = SameOriginMediaDataSource(request("https://server.example/video"), connect = { uri ->
            Connection(uri.toURL(), 416, contentRange = "bytes */1234")
        })
        assertEquals(0L, source.open(DataSpec.Builder().setUri("https://server.example/video").setPosition(1234).build()))
        assertEquals(-1, source.read(ByteArray(2), 0, 2)); source.close()
    }

    @Test fun `malformed headers or embedded credentials cannot enter request handoff`() {
        listOf(mapOf("Host" to "other.example"), mapOf("Connection" to "keep-alive"),
            mapOf("Content-Length" to "100"), mapOf("Transfer-Encoding" to "chunked"),
            mapOf("Bad Header" to "value"), mapOf("X-Api-Key" to "bad\u0001value"),
            mapOf("Authorization" to "bad\r\nInjected: yes")).forEach { headers ->
            try { SameOriginMediaRequests.add("https://server.example/video", headers, "https://server.example"); fail("Invalid headers accepted") }
            catch (_: IllegalArgumentException) {}
        }
        try { SameOriginMediaRequests.add("https://user:pass@server.example/video", emptyMap(), "https://server.example"); fail("Embedded credentials accepted") }
        catch (_: IllegalArgumentException) {}
    }
}
