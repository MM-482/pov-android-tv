package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublicMetadataCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `cache never accepts account server or configured add-on endpoints`() {
        listOf("https://kitsu.app/api/edge/users?filter[self]=true", "https://kitsu.app/api/edge/library-entries",
            "https://api.trakt.tv/users/settings", "https://private-addon.example/key/meta/movie/id.json",
            "https://kitsu.app/api/edge/anime?access_token=secret").forEach { assertFalse(it, PublicMetadataCache.publicAddress(it)) }
    }
    @Test fun `fresh public metadata is reused and a bounded stale copy survives temporary outage`() {
        val directory = temporary.newFolder()
        var calls = 0
        var now = System.currentTimeMillis()
        var offline = false
        val cache = PublicMetadataCache(directory, { calls++; if (offline) error("Offline") else JSONObject("""{"meta":{"id":"tt1234567"}}""") }, { now })
        val url = "https://v3-cinemeta.strem.io/meta/movie/tt1234567.json"
        cache.json(url); now = maxOf(now, directory.listFiles()!!.single().lastModified()); cache.json(url)
        assertEquals(1, calls)
        now += 7 * 60 * 60 * 1000L; offline = true
        assertEquals("tt1234567", cache.json(url).getJSONObject("meta").getString("id"))
        assertEquals(2, calls)
        now += 8 * 24 * 60 * 60 * 1000L
        try { cache.json(url); fail("Ancient stale metadata accepted") } catch (_: IllegalStateException) { }
    }
}
