package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublicCatalogCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://v3-cinemeta.strem.io/meta/movie/tt1234567.json"

    @Test fun `recent public metadata is reused without a network request and is labeled cached`() {
        var requests = 0
        var clock = 1_000_000L
        val cache = PublicCatalogCache(temporary.newFolder(), { requests++; JSONObject("""{"meta":{"id":"tt1234567","name":"Film"}}""") }, { clock }, 10, 50)
        assertEquals("network", cache.read(url).source)
        clock += 5
        val saved = cache.read(url)
        assertEquals("cache", saved.source)
        assertTrue(saved.notice.isNotBlank())
        assertEquals(1, requests)
        assertEquals(1, cache.info().entries)
    }

    @Test fun `temporary offline failure can use bounded stale metadata with explicit notice`() {
        var clock = 1_000_000L
        var offline = false
        val cache = PublicCatalogCache(temporary.newFolder(), { if (offline) throw IOException("offline") else JSONObject("""{"meta":{"name":"Film"}}""") }, { clock }, 10, 50)
        cache.read(url)
        clock += 20; offline = true
        assertEquals("stale-cache", cache.read(url).source)
        assertTrue(cache.lastRead!!.notice.contains("unavailable"))
        clock += 40
        try { cache.read(url); fail("Expected expired fallback") } catch (_: IOException) { }
    }

    @Test fun `authorization and missing-title errors are not hidden by stale data`() {
        var clock = 1_000_000L
        var code = 0
        val cache = PublicCatalogCache(temporary.newFolder(), { if (code != 0) throw ApiException(code, "Fixture") else JSONObject("""{"meta":{}}""") }, { clock }, 10, 50)
        cache.read(url)
        clock += 20
        for (status in listOf(401, 403, 404, 451)) {
            code = status
            try { cache.read(url); fail("Expected live rejection") } catch (error: ApiException) { assertEquals(status, error.code) }
        }
    }

    @Test fun `only public metadata paths and allowlisted queries reach the fetcher`() {
        var requests = 0
        val cache = PublicCatalogCache(temporary.newFolder(), { requests++; JSONObject() }, { 1_000_000 })
        val rejected = listOf("https://api.trakt.tv/users/settings", "https://kitsu.app/api/edge/users?filter[self]=true", "https://kitsu.app/api/edge/library-entries?filter[userId]=42",
            "https://v3-cinemeta.strem.io/stream/movie/tt1234567.json", "https://v3-cinemeta.strem.io/meta/movie/tt1234567.json?apikey=fixture",
            "https://kitsu.app/api/edge/anime?access_token=fixture", "https://v3-cinemeta.strem.io/catalog/movie/top/search=film&apikey=fixture.json",
            "http://v3-cinemeta.strem.io/meta/movie/tt1234567.json", "https://user:fixture@v3-cinemeta.strem.io/meta/movie/tt1234567.json")
        rejected.forEach { address -> try { cache.read(address); fail("Expected private/unsupported URL rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }
        assertEquals(0, requests)
        cache.read("https://v3-cinemeta.strem.io/catalog/movie/top/search=space%20film.json")
        cache.read("https://kitsu.app/api/edge/anime?page%5Blimit%5D=20&filter%5Btext%5D=anime")
        cache.read("https://anime-kitsu.strem.fun/meta/series/kitsu:123.json")
        assertEquals(3, requests)
    }

    @Test fun `unexpected credential or stream response data is never persisted`() {
        val cache = PublicCatalogCache(temporary.newFolder(), { JSONObject("""{"access_token":"fixture","streams":[{"url":"https://private.example"}]}""") }, { 1_000_000 })
        try { cache.read(url); fail("Expected non-metadata rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(0, cache.info().entries)
    }

    @Test fun `cache is bounded by entry count and clear preserves unrelated files`() {
        val folder = temporary.newFolder()
        val unrelated = java.io.File(folder, "unrelated.txt").apply { writeText("preserve") }
        var clock = 1_000_000L
        val cache = PublicCatalogCache(folder, { JSONObject("""{"metas":[]}""") }, { clock++ }, maxEntries = 3)
        repeat(8) { cache.read("https://v3-cinemeta.strem.io/catalog/movie/top/skip=${it * 100}.json") }
        assertEquals(3, cache.info().entries)
        assertTrue(cache.info().bytes <= cache.info().maxBytes)
        cache.clear()
        assertEquals(0, cache.info().entries)
        assertTrue(unrelated.isFile)
    }

    @Test fun `clearing while fetch runs cannot recreate deleted cache data`() {
        lateinit var cache: PublicCatalogCache
        cache = PublicCatalogCache(temporary.newFolder(), { cache.clear(); JSONObject("""{"meta":{}}""") }, { 1_000_000 })
        assertEquals("network", cache.read(url).source)
        assertEquals(0, cache.info().entries)
    }

    @Test fun `oversized metadata returns live but is not cached`() {
        val cache = PublicCatalogCache(temporary.newFolder(), { JSONObject().put("meta", "x".repeat(3000)) }, { 1_000_000 }, maxEntryBytes = 1000)
        assertEquals("network", cache.read(url).source)
        assertEquals(0, cache.info().entries)
    }
}
