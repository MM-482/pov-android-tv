package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.media3.datasource.DataSpec
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProtectedPlaybackRequestsTest {
    private class Connection(url: URL, private val code: Int, private val location: String? = null) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?): String? = when (name) {
            "Location" -> location
            "Content-Length" -> "3"
            else -> null
        }
        override fun getInputStream() = ByteArrayInputStream("abc".toByteArray())
    }
    @Test fun `credential scope accepts service HTTPS subdomains and rejects lookalike redirect destinations`() {
        assertTrue(ProtectedPlaybackRequests.approved("https://members.easynews.com/media"))
        assertTrue(ProtectedPlaybackRequests.approved("https://easynews.com:443/media"))
        listOf("http://members.easynews.com/media", "https://easynews.com.evil.example/media",
            "https://evil-easynews.com/media", "https://user:pass@easynews.com/media", "https://easynews.com:8443/media",
            "file:///easynews.com/media").forEach { assertFalse(it, ProtectedPlaybackRequests.approved(it)) }
    }
    @Test fun `handoff token is opaque single use and holds header only in memory`() {
        val secret = "Basic fixture-only"
        val token = ProtectedPlaybackRequests.add("https://members.easynews.com/media", mapOf("Authorization" to secret))
        assertFalse(token.contains(secret)); assertFalse(token.contains("easynews"))
        assertEquals(secret, ProtectedPlaybackRequests.take(token)?.headers?.get("Authorization"))
        assertNull(ProtectedPlaybackRequests.take(token))
    }
    @Test fun `reject unsupported headers and newline injection`() {
        for (headers in listOf(mapOf("Cookie" to "x"), mapOf("Authorization" to "Basic bad\r\nInjected: value"))) {
            try { ProtectedPlaybackRequests.add("https://easynews.com/media", headers); fail("Headers accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `redirect guard never opens or sends authorization to an outside host`() {
        val connections = mutableListOf<Connection>()
        val source = ProtectedHttpDataSource(mapOf("Authorization" to "Basic fixture")) { uri ->
            Connection(uri.toURL(), 302, "https://attacker.example/media").also(connections::add)
        }
        try {
            source.open(DataSpec(Uri.parse("https://members.easynews.com/media")))
            fail("Unsafe redirect accepted")
        } catch (_: IOException) { }
        assertEquals(1, connections.size)
        assertEquals("Basic fixture", connections.single().getRequestProperty("Authorization"))
    }

    @Test fun `safe service redirect retains authorization and exposes real media bytes`() {
        val connections = mutableListOf<Connection>()
        val source = ProtectedHttpDataSource(mapOf("Authorization" to "Basic fixture")) { uri ->
            Connection(uri.toURL(), if (connections.isEmpty()) 302 else 200,
                "https://media.easynews.com/video").also(connections::add)
        }
        assertEquals(3L, source.open(DataSpec(Uri.parse("https://members.easynews.com/media"))))
        val buffer = ByteArray(4)
        assertEquals(3, source.read(buffer, 0, 4))
        assertEquals("abc", buffer.copyOf(3).toString(Charsets.UTF_8))
        assertEquals(-1, source.read(buffer, 0, 4))
        assertEquals(2, connections.size)
        connections.forEach { assertEquals("Basic fixture", it.getRequestProperty("Authorization")) }
        source.close()
    }
}
