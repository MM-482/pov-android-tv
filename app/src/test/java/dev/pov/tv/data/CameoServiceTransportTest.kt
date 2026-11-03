package dev.pov.tv.data

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.IOException
import okhttp3.Dns

class CameoServiceTransportTest {
    @Test fun `LAN DNS rejects any public result and retains only exact private addresses for the connection`() {
        val privateAddress = InetAddress.getByName("192.168.1.10")
        val mixed = CameoServiceUrls.privateDns(object : Dns {
            override fun lookup(hostname: String) = listOf(privateAddress, InetAddress.getByName("8.8.8.8"))
        })
        try { mixed.lookup("addon.local"); fail("Mixed DNS accepted") } catch (_: IOException) { }
        val local = CameoServiceUrls.privateDns(object : Dns {
            override fun lookup(hostname: String) = listOf(privateAddress)
        })
        assertEquals(listOf(privateAddress), local.lookup("addon.local"))
        assertEquals("cdn.example", CameoServiceUrls.addonResource("https://cdn.example/sub.srt", "http://addon.local:8080").host)
        listOf("http://other.local:8080/sub.srt", "http://addon.local:8081/sub.srt", "http://public.example/sub.srt").forEach { url ->
            try { CameoServiceUrls.addonResource(url, "http://addon.local:8080"); fail("Untrusted HTTP accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `public LAN subtitle bytes follow same origin redirect without cookies and enforce size cap`() {
        val worker = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        try {
            val served = worker.submit {
                repeat(3) { index -> server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val requestLine = reader.readLine()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isBlank()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    assertNull(headers["authorization"]); assertNull(headers["cookie"])
                    val response = when (index) {
                        0 -> "HTTP/1.1 302 Found\r\nLocation: /subtitle.srt\r\nSet-Cookie: secret=fixture\r\nContent-Length: 0\r\n"
                        1 -> { assertTrue(requestLine.startsWith("GET /subtitle.srt ")); "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n" }
                        else -> "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n"
                    }
                    socket.getOutputStream().write((response + "Connection: close\r\n\r\n" + if (index == 0) "" else "abc").toByteArray())
                } }
            }
            val origin = "http://127.0.0.1:${server.localPort}"
            assertEquals("abc", String(LiveCameoServiceTransport.publicBytes("$origin/start", origin, 3)))
            try { LiveCameoServiceTransport.publicBytes("$origin/too-large", origin, 2); fail("Oversized subtitle accepted") }
            catch (error: ApiException) { assertTrue(error.message.orEmpty().contains("too large")) }
            served.get(5, TimeUnit.SECONDS)
        } finally { server.close(); worker.shutdownNow() }
    }
    @Test fun `public LAN redirect cannot contact a different local origin`() {
        val worker = Executors.newSingleThreadExecutor()
        val source = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val other = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        source.soTimeout = 5000
        try {
            val served = worker.submit {
                source.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${other.localPort}/subtitle\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                }
            }
            val origin = "http://127.0.0.1:${source.localPort}"
            try { LiveCameoServiceTransport.publicBytes("$origin/start", origin, 100); fail("Foreign LAN redirect followed") }
            catch (_: IllegalArgumentException) { }
            served.get(5, TimeUnit.SECONDS)
            other.soTimeout = 150
            try { other.accept().close(); fail("Foreign LAN received request") } catch (_: SocketTimeoutException) { }
        } finally { source.close(); other.close(); worker.shutdownNow() }
    }
    @Test fun `real transport sends PROPFIND and XML body to a configured local server`() {
        val worker = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            val body = "<d:propfind xmlns:d=\"DAV:\"/>"
            val response = "<d:multistatus xmlns:d=\"DAV:\"/>"
            val received = worker.submit<String> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val line = reader.readLine()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isBlank()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    assertEquals("1", headers["depth"]); assertEquals("Basic fixture", headers["authorization"])
                    val content = CharArray(headers.getValue("content-length").toInt())
                    var read = 0
                    while (read < content.size) { val count = reader.read(content, read, content.size - read); check(count > 0); read += count }
                    assertEquals(body, String(content))
                    socket.getOutputStream().write(("HTTP/1.1 207 Multi-Status\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response").toByteArray())
                    line
                }
            }
            val value = LiveCameoServiceTransport.request("http://127.0.0.1:${server.localPort}/videos/", "PROPFIND",
                mapOf("Depth" to "1", "Authorization" to "Basic fixture"), body, "application/xml")
            assertEquals(response, value)
            assertEquals("PROPFIND /videos/ HTTP/1.1", received.get(5, TimeUnit.SECONDS))
        } finally { server.close(); worker.shutdownNow() }
    }
    @Test fun `authenticated service redirect is rejected before opening a new origin`() {
        val worker = Executors.newSingleThreadExecutor()
        val source = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val other = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            val served = worker.submit {
                source.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${other.localPort}/private\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                }
            }
            try { LiveCameoServiceTransport.request("http://127.0.0.1:${source.localPort}/", headers = mapOf("Authorization" to "Bearer fixture")); fail("Redirect followed") }
            catch (error: ApiException) { assertEquals(302, error.code) }
            served.get(5, TimeUnit.SECONDS)
            other.soTimeout = 150
            try { other.accept().close(); fail("Credential request reached new origin") } catch (_: SocketTimeoutException) { }
        } finally { source.close(); other.close(); worker.shutdownNow() }
    }
}
