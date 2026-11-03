package dev.pov.tv

import dev.pov.tv.data.ProviderPlayback
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class DownloadTransferTest {
    private class Response(address: URI, private val status: Int, private val fields: Map<String, String> = emptyMap(), private val bytes: ByteArray = byteArrayOf()) : HttpURLConnection(address.toURL()) {
        val sent = linkedMapOf<String, String>()
        override fun setRequestProperty(key: String, value: String) { sent[key] = value }
        override fun getResponseCode() = status
        override fun getHeaderField(name: String) = fields[name]
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }
    @Test fun accountHeadersNeverFollowACrossOriginRedirect() {
        val first = URI("https://media.example.test/video")
        val second = URI("https://cdn.example.test/file")
        val a = Response(first, 302, mapOf("Location" to second.toString()))
        val b = Response(second, 200, mapOf("Content-Length" to "3"), "abc".toByteArray())
        val file = File.createTempFile("download-test", ".tmp")
        try {
            DownloadTransfer.copy(ProviderPlayback(first.toString(), mapOf("Authorization" to "Bearer fixture")), file, { false }, { _, _ -> },
                connect = { if (it == first) a else b })
            assertEquals("Bearer fixture", a.sent["Authorization"])
            assertFalse(b.sent.containsKey("Authorization"))
            assertEquals("abc", file.readText())
        } finally { file.delete() }
    }
    @Test fun truncatedResponseDoesNotReportSuccess() {
        val address = URI("https://media.example.test/video")
        val file = File.createTempFile("download-test", ".tmp")
        try { assertThrows(IOException::class.java) {
            DownloadTransfer.copy(ProviderPlayback(address.toString()), file, { false }, { _, _ -> },
                connect = { Response(address, 200, mapOf("Content-Length" to "9"), "abc".toByteArray()) })
        } } finally { file.delete() }
    }
    @Test fun explicitAccountOriginDoesNotAuthorizeAnUnrelatedDownloadHost() {
        val address = URI("https://cdn.example.test/video")
        val response = Response(address, 200, mapOf("Content-Length" to "3"), "abc".toByteArray())
        val file = File.createTempFile("download-test", ".tmp")
        try {
            DownloadTransfer.copy(ProviderPlayback(address.toString(), mapOf("Authorization" to "private"), "https://server.example.test"),
                file, { false }, { _, _ -> }, connect = { response })
            assertFalse(response.sent.containsKey("Authorization"))
            assertEquals("abc", file.readText())
        } finally { file.delete() }
    }
    @Test fun publicCleartextIsRejectedBeforeConnecting() {
        val file = File.createTempFile("download-test", ".tmp")
        try { assertThrows(IllegalArgumentException::class.java) {
            DownloadTransfer.copy(ProviderPlayback("http://203.0.113.1/video"), file, { false }, { _, _ -> },
                connect = { error("Must not connect") })
        } } finally { file.delete() }
    }
    @Test fun localServerConnectsToPinnedAddressAndPreservesEncodedPath() {
        val address = URI("http://localhost:8096/video%20folder/file.mkv?token=a%2Fb")
        val file = File.createTempFile("download-test", ".tmp")
        var response: Response? = null
        try {
            DownloadTransfer.copy(ProviderPlayback(address.toString(), mapOf("Authorization" to "fixture"), "http://localhost:8096"), file, { false }, { _, _ -> },
                connect = { pinned ->
                    assertNotEquals("localhost", pinned.host)
                    assertTrue(java.net.InetAddress.getByName(pinned.host).isLoopbackAddress)
                    assertEquals(address.rawPath, pinned.rawPath)
                    assertEquals(address.rawQuery, pinned.rawQuery)
                    Response(pinned, 200, mapOf("Content-Length" to "3"), "abc".toByteArray()).also { response = it }
                })
            assertEquals("localhost:8096", response!!.sent["Host"])
            assertEquals("fixture", response!!.sent["Authorization"])
            assertEquals("abc", file.readText())
        } finally { file.delete() }
    }
    @Test fun cancelBeforeNetworkAccessDoesNotConnect() {
        val file = File.createTempFile("download-test", ".tmp")
        try { assertThrows(IOException::class.java) {
            DownloadTransfer.copy(ProviderPlayback("https://media.example.test/video"), file, { true }, { _, _ -> },
                connect = { error("Must not connect") })
        } } finally { file.delete() }
    }
    @Test fun adaptiveManifestUrlIsNotSavedAsAnOfflineMovie() {
        val file = File.createTempFile("download-test", ".tmp")
        try { assertThrows(IllegalArgumentException::class.java) {
            DownloadTransfer.copy(ProviderPlayback("https://media.example.test/master.m3u8?token=fixture"), file, { false }, { _, _ -> },
                connect = { error("Must not connect") })
        } } finally { file.delete() }
    }
    @Test fun extensionlessAdaptiveResponseIsRejectedBeforeWriting() {
        val address = URI("https://media.example.test/stream")
        val file = File.createTempFile("download-test", ".tmp")
        try {
            assertThrows(IOException::class.java) {
                DownloadTransfer.copy(ProviderPlayback(address.toString()), file, { false }, { _, _ -> },
                    connect = { Response(address, 200, mapOf("Content-Type" to "application/dash+xml; charset=utf-8"), "manifest".toByteArray()) })
            }
            assertEquals(0L, file.length())
        } finally { file.delete() }
    }
}
