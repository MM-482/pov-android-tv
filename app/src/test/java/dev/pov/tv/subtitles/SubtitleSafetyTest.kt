package dev.pov.tv.subtitles

import dev.pov.tv.data.PlaybackIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitleSafetyTest {
    private val episode = PlaybackIdentity("tt0903747", "series", "Show", 1, 2)
    private val subtitle = "1\n00:00:00,000 --> 00:00:01,000\nHello\n".toByteArray()
    private fun result(file: String = "") = SubtitleResult(SubtitleProvider.SUBSOURCE, "1", "Show", "en", episode, file)

    @Test fun archiveSelectsTheRequestedEpisodeRatherThanTheFirstFile() {
        val file = SubtitleArchive.read(zip("Show.S01E01.srt" to subtitle, "Show.S01E02.srt" to subtitle), result())
        assertEquals("Show.S01E02.srt", file.displayName)
    }
    @Test fun archiveRejectsAmbiguousAndWrongEpisodeFiles() {
        expectFailure { SubtitleArchive.read(zip("First.srt" to subtitle, "Second.srt" to subtitle), result()) }
        expectFailure { SubtitleArchive.read(zip("Show.S01E01.srt" to subtitle), result()) }
    }
    @Test fun combinedEpisodeTagsMatchEveryExplicitEpisodeAndDoNotMatchTheNextOne() {
        assertFalse(SubtitleArchive.explicitMismatch("Show.S01E01E02.srt", episode))
        assertTrue(SubtitleArchive.explicitMismatch("Show.S01E01E02.srt", episode.copy(episode = 3)))
        val file = SubtitleArchive.read(zip("Show.S01E01E02.srt" to subtitle, "Show.S01E03.srt" to subtitle), result())
        assertEquals("Show.S01E01E02.srt", file.displayName)
    }
    @Test fun combinedTagsAndRangesRemainSeasonSpecific() {
        assertFalse(SubtitleArchive.explicitMismatch("Show.S01E01E02-E04.srt", episode.copy(episode = 3)))
        assertTrue(SubtitleArchive.explicitMismatch("Show.S01E01E02-E04.srt", episode.copy(season = 2, episode = 3)))
        assertFalse(SubtitleArchive.explicitMismatch("Show.1x01-03.srt", episode))
    }
    @Test fun archiveRejectsTraversalAndOversizedInflation() {
        expectFailure { SubtitleArchive.read(zip("../../outside.srt" to subtitle), result()) }
        expectFailure { SubtitleArchive.read(zip("Huge.srt" to ByteArray(4 * 1024 * 1024 + 1)), result()) }
    }
    @Test fun htmlOrJsonErrorsCannotBeAttachedAsSubtitles() {
        expectFailure { SubtitleArchive.read("<html>Access denied</html>".toByteArray(), result("denied.srt")) }
        expectFailure { SubtitleArchive.read("{\"error\":\"quota reached\"}".toByteArray(), result("error.srt")) }
    }
    @Test fun utf16IsNormalizedToUtf8() {
        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + String(subtitle).toByteArray(Charsets.UTF_16LE)
        val file = SubtitleArchive.read(bytes, result("Show.S01E02.srt"))
        assertEquals(String(subtitle), String(file.bytes))
        assertEquals("application/x-subrip", file.mimeType)
    }
    @Test fun credentialHeadersAreStrippedOnDownloadRedirectEvenBetweenOfficialHosts() {
        val opened = mutableListOf<FakeConnection>()
        val http = SubtitleHttp { url -> FakeConnection(url, if (opened.isEmpty()) 302 else 200,
            if (opened.isEmpty()) "https://cdn.subsource.net/file.zip" else null).also { opened += it } }
        http.execute(SubtitleRequest("https://api.subsource.net/api/v1/subtitles/1/download", headers = mapOf("X-API-Key" to "secret"),
            allowedHosts = setOf("subsource.net"), allowSubdomains = true, download = true))
        assertEquals("secret", opened[0].supplied["X-API-Key"])
        assertFalse(opened[1].supplied.containsKey("X-API-Key"))
    }
    @Test fun authenticatedApiRequestsCannotRedirectToAnotherOrigin() {
        var calls = 0
        val http = SubtitleHttp { url -> calls++; FakeConnection(url, 302, "https://vip-api.opensubtitles.com/api/v1/subtitles") }
        expectFailure { http.execute(SubtitleRequest("https://api.opensubtitles.com/api/v1/subtitles", headers = mapOf("Api-Key" to "secret"),
            allowedHosts = setOf("api.opensubtitles.com", "vip-api.opensubtitles.com"))) }
        assertEquals(1, calls)
    }
    @Test fun unrelatedAndDowngradedDownloadTargetsAreRejectedBeforeRequest() {
        for (address in listOf("http://dl.subdl.com/file", "https://dl.subdl.com.evil.example/file", "https://secret@dl.subdl.com/file", "https://dl.subdl.com:8080/file")) {
            expectFailure { SubtitleHttp.checkedUrl(address, setOf("subdl.com"), true) }
        }
    }
    @Test fun rateLimitHasAnActionableSanitizedError() {
        val http = SubtitleHttp { url -> FakeConnection(url, 429, null) }
        try {
            http.execute(SubtitleRequest("https://api.subdl.com/api/v1/subtitles?api_key=top-secret", allowedHosts = setOf("api.subdl.com")))
            fail("Should reject quota error")
        } catch (error: SubtitleException) {
            assertEquals(429, error.status)
            assertEquals(120, error.retryAfterSeconds)
            assertFalse(error.message!!.contains("top-secret"))
        }
    }

    private class FakeConnection(url: URL, private val status: Int, private val location: String?) : HttpURLConnection(url) {
        val supplied = mutableMapOf<String, String>()
        override fun setRequestProperty(key: String, value: String) { supplied[key] = value }
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = when (name) { "Location" -> location; "Retry-After" -> "120"; else -> null }
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        override fun getContentType() = "application/octet-stream"
        override fun disconnect() { }
        override fun usingProxy() = false
        override fun connect() { }
    }
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        out.toByteArray()
    }
    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("Expected unsafe or ambiguous subtitle input to be rejected") } catch (_: SubtitleException) { }
    }
}
