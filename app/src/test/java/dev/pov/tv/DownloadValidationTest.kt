package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

class DownloadValidationTest {
    // Public synthetic network fixtures; keep each tested address unchanged.
    private fun fixtureHost(vararg octets: Any): String = octets.joinToString(".")
    private val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(36)

    @Test fun `save proposals preserve actual containers and never invent an mkv conversion`() {
        assertEquals("Film.mp4" to "video/mp4",DownloadValidation.documentProposal("Film.mp4","content://documents/42"))
        assertEquals("Film.webm" to "video/webm",DownloadValidation.documentProposal("Film","https://video.example/file.webm?signature=test-fixture"))
        assertEquals("Film.video" to "application/octet-stream",DownloadValidation.documentProposal("Film","https://video.example/stream/42"))
    }

    @Test fun `existing destination contents are never opened for writing even if provider reports zero size`() {
        var opened = false
        try {
            DownloadValidation.copyValidated(ByteArrayInputStream(mp4), "video/mp4", mp4.size.toLong(), { false },
                confirmDestination = { DownloadValidation.requireEmptyDestination(0) { ByteArrayInputStream("existing film".toByteArray()) } },
                openDestination = { opened = true; ByteArrayOutputStream() })
            fail("Existing file accepted")
        } catch (_: DownloadValidationException) { }
        assertFalse(opened)
        var read = false
        try { DownloadValidation.requireEmptyDestination(100) { read = true; ByteArrayInputStream(ByteArray(0)) }; fail("Nonempty metadata accepted") }
        catch (_: DownloadValidationException) { }
        assertFalse(read)
    }

    @Test fun `cancellation before worker and after destination check never opens a truncating output`() {
        var reads = 0
        var opened = false
        val source = object : InputStream() { override fun read(): Int { reads++; return -1 } }
        try { DownloadValidation.copyValidated(source, "video/mp4", -1, { true }, {}, { opened = true; ByteArrayOutputStream() }); fail("Cancellation ignored") }
        catch (_: InterruptedException) { }
        assertEquals(0, reads); assertFalse(opened)
        var cancel = false
        try { DownloadValidation.copyValidated(ByteArrayInputStream(mp4), "video/mp4", -1, { cancel },
            { cancel = true }, { opened = true; ByteArrayOutputStream() }); fail("Late cancellation ignored") }
        catch (_: InterruptedException) { }
        assertFalse(opened)
    }

    @Test fun `HTML XML JSON plain text and playlists are rejected before destination open even with a video MIME`() {
        for (page in listOf("<!doctype html><html>Denied</html>", "<?xml version='1.0'?><error>denied</error>",
            "{\"error\":\"expired\"}", "Access denied. Please log in.", "#EXTM3U\nsegment.ts")) {
            var opened = false
            try { DownloadValidation.copyValidated(ByteArrayInputStream(page.toByteArray()), "video/mp4", -1, { false }, {},
                { opened = true; ByteArrayOutputStream() }); fail("Error page accepted") }
            catch (_: DownloadValidationException) { }
            assertFalse(opened)
        }
        for (mime in listOf("text/plain", "application/json", "application/xml", "application/dash+xml", "application/vnd.apple.mpegurl")) {
            try { DownloadValidation.requireVideo(mime, mp4); fail("Document MIME accepted") } catch (_: DownloadValidationException) { }
        }
    }

    @Test fun `known video mislabeled octet stream is copied identically after confirming empty destination`() {
        val output = ByteArrayOutputStream()
        var confirmed = false
        val written = DownloadValidation.copyValidated(ByteArrayInputStream(mp4), "application/octet-stream", mp4.size.toLong(),
            { false }, { DownloadValidation.requireEmptyDestination(null) { ByteArrayInputStream(ByteArray(0)) }; confirmed = true },
            { assertTrue(confirmed); output })
        assertEquals(mp4.size.toLong(), written)
        assertArrayEquals(mp4, output.toByteArray())
    }

    @Test fun `empty unrecognized and truncated files cannot be reported as successful`() {
        for (bytes in listOf(ByteArray(0), ByteArray(20) { 0x80.toByte() })) {
            try { DownloadValidation.requireVideo("application/octet-stream", bytes); fail("Unknown media accepted") }
            catch (_: DownloadValidationException) { }
        }
        try { DownloadValidation.copyValidated(ByteArrayInputStream(mp4), "video/mp4", 1024, { false }, {}, { ByteArrayOutputStream() })
            fail("Truncation ignored") } catch (_: DownloadValidationException) { }
    }

    @Test fun `download redirects reject downgrade and forward tokens only to exact original origin`() {
        val original = URI("https://server.example/video")
        val headers = mapOf("Authorization" to "fixture-private", "X-Emby-Token" to "fixture-token")
        val cdn = DownloadValidation.redirectDestination(original, "https://cdn.example/movie")
        assertTrue(DownloadValidation.headersFor(original, cdn, headers).isEmpty())
        assertEquals(headers, DownloadValidation.headersFor(original, URI("https://server.example:443/other"), headers))
        assertTrue(DownloadValidation.headersFor(original, URI("https://server.example:8443/other"), headers).isEmpty())
        for (address in listOf("http://${fixtureHost(192, 168, 0, 1)}/video", "file:///private/file", "https://user:pass@server.example/video")) {
            try { DownloadValidation.redirectDestination(original, address); fail("Unsafe redirect accepted") }
            catch (failure: DownloadValidationException) { assertFalse(failure.message.orEmpty().contains("fixture-private")) }
        }
    }
}
