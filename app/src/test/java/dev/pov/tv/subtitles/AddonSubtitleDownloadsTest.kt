package dev.pov.tv.subtitles

import dev.pov.tv.data.CameoAddon
import dev.pov.tv.data.CameoAddonSubtitle
import dev.pov.tv.data.PlaybackIdentity
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AddonSubtitleDownloadsTest {
    private val addon = CameoAddon("fixture-addon", "LAN", "http://192.168.1.10:8080/config")
    private val identity = PlaybackIdentity("custom:show", "series", "Show", 2, 3, "exact:episode", addon.id)
    private val text = "1\n00:00:01,000 --> 00:00:02,000\nFixture caption\n"

    @Test fun `LAN subtitle uses configured origin and bounded bytes then preserves archive episode validation`() {
        val context = RuntimeEnvironment.getApplication()
        var requests = 0
        val downloader = AddonSubtitleDownloads(context, { listOf(addon) }) { url, origin, limit ->
            requests++; assertTrue(url.startsWith("http://192.168.1.10:8080/"))
            assertEquals("http://192.168.1.10:8080", origin); assertEquals(8 * 1024 * 1024, limit)
            text.toByteArray()
        }
        val result = CameoAddonSubtitle("English", "eng", "http://192.168.1.10:8080/Show.S02E03.srt", addon.id)
        val downloaded = downloader.download(result, identity)
        val file = File(downloaded.uri.path!!)
        try { assertEquals(text, file.readText()); assertTrue(file.name.startsWith("cameo-addon-sub-")) }
        finally { file.delete() }
        try { downloader.download(result.copy(url = "http://192.168.1.10:8080/Show.S02E04.srt"), identity); fail("Wrong episode accepted") }
        catch (_: SubtitleException) { }
        try { downloader.download(result.copy(url = "http://192.168.1.11:8080/Show.S02E03.srt"), identity); fail("Foreign LAN subtitle requested") }
        catch (_: IllegalArgumentException) { }
        assertEquals(2, requests)
    }
}
