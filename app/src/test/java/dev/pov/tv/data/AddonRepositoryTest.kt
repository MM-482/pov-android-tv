package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AddonRepositoryTest {
    private class Memory : ProviderCredentials {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key].orEmpty()
        override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
    }
    @Test fun `catalog only IMDb fallback keeps provenance and all existing exact episode identities`() {
        val addonRequests = mutableListOf<String>()
        val publicRequests = mutableListOf<String>()
        val catalog = CatalogApi { address ->
            publicRequests += address
            JSONObject(if ("/series/" in address)
                """{"meta":{"id":"tt0903747","type":"series","name":"Public show","description":"Verified metadata","videos":[{"season":1,"episode":1,"title":"Public one"},{"season":1,"episode":2,"title":"Public two"}]}}"""
            else """{"meta":{"id":"tt0133093","type":"movie","name":"Public movie","description":"Verified movie metadata"}}""")
        }
        val repo = AddonRepository(Memory(), { address ->
            addonRequests += address
            check(address.endsWith("/manifest.json")) { "Catalog-only add-on must not receive a meta request" }
            JSONObject("""{"id":"catalog-only","name":"Catalog only","resources":["catalog"]}""")
        }, catalog)
        val addon = repo.install("https://catalog.example/config")
        val exact = EpisodeData(1, 1, "Add-on one", sourceId = "custom:episode:91",
            sourceIds = listOf("custom:episode:91", "verified:alias:17"))
        val extra = EpisodeData(0, 1, "Special", sourceId = "special:1", sourceIds = listOf("special:1"))
        val item = MediaItemData("tt0903747", "series", "Catalog title", episodes = listOf(exact, extra),
            addonId = addon.id, catalogId = "personal-list")
        val result = repo.details(item)
        assertEquals(addon.id, result.addonId); assertEquals("personal-list", result.catalogId)
        assertEquals("Verified metadata", result.description)
        assertEquals(exact, result.episodes.single { it.season == 1 && it.episode == 1 })
        assertEquals(extra, result.episodes.single { it.season == 0 })
        assertTrue(result.episodes.single { it.episode == 2 }.sourceId.isBlank())
        assertEquals("custom:episode:91", repo.identity(result, exact).sourceId)
        val movie = repo.details(MediaItemData("tt0133093", "movie", "Catalog movie", addonId = addon.id, catalogId = "movies"))
        assertEquals(addon.id, movie.addonId); assertEquals("movies", movie.catalogId)
        assertEquals("Verified movie metadata", movie.description)
        assertEquals(listOf("https://v3-cinemeta.strem.io/meta/series/tt0903747.json",
            "https://v3-cinemeta.strem.io/meta/movie/tt0133093.json"), publicRequests)
        assertEquals(1, addonRequests.size)
    }
    @Test fun `catalog only custom identifiers and unsupported types cannot trigger guessed public details`() {
        var requests = 0
        val repo = AddonRepository(Memory(), { address ->
            requests++; check(address.endsWith("/manifest.json"))
            JSONObject("""{"id":"catalog-only","name":"Catalog only","resources":["catalog"]}""")
        }, CatalogApi { throw AssertionError("A custom identity reached Cinemeta") })
        val addon = repo.install("https://catalog.example")
        listOf(MediaItemData("custom:77", "series", "Custom", addonId = addon.id),
            MediaItemData("tt0903747", "channel", "Channel", addonId = addon.id)).forEach { item ->
            try { repo.details(item); fail("Unsupported fallback identity accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, requests)
    }
    @Test fun `declared add-on metadata failures or mismatches never fall back to Cinemeta`() {
        var reply = """{"meta":{"id":"tt9999999","type":"movie","name":"Wrong title"}}"""
        val requests = mutableListOf<String>()
        val repo = AddonRepository(Memory(), { address ->
            requests += address
            JSONObject(if (address.endsWith("/manifest.json"))
                """{"id":"metadata","name":"Metadata","resources":["catalog","meta"]}""" else reply)
        }, CatalogApi { throw AssertionError("Declared metadata failure triggered public fallback") })
        val addon = repo.install("https://metadata.example")
        val item = MediaItemData("tt0133093", "movie", "Saved title", addonId = addon.id)
        try { repo.details(item); fail("Mismatched metadata accepted") } catch (_: IllegalStateException) { }
        reply = "{}"
        try { repo.details(item); fail("Missing metadata accepted") } catch (_: IllegalStateException) { }
        assertEquals(3, requests.size)
        assertTrue(requests.drop(1).all { it == "https://metadata.example/meta/movie/tt0133093.json" })
    }
    @Test fun `stremio install scheme and case insensitive manifest suffix normalize without changing configuration path`() {
        val requests = mutableListOf<String>()
        val repo = AddonRepository(Memory()) { address ->
            requests += address
            JSONObject("""{"id":"sample","name":"Sample","resources":["stream"]}""")
        }
        val addon = repo.install("  StReMiO://addon.example/CaseSensitiveConfig/MANIFEST.JSON/  ")
        assertEquals("https://addon.example/CaseSensitiveConfig", addon.url)
        assertEquals("https://addon.example/CaseSensitiveConfig/manifest.json", requests.single())
        assertEquals(addon.id, repo.install("https://addon.example/CaseSensitiveConfig/manifest.json").id)
        assertEquals(1, repo.list().size)
    }
    @Test fun `LAN addon retains exact catalog episode streams headers and same origin subtitles`() {
        val requests = mutableListOf<String>()
        val root = "http://192.168.1.20:8080/config"
        val repo = AddonRepository(Memory()) { address ->
            requests += address
            JSONObject(when {
                address.endsWith("manifest.json") -> """{"id":"lan","name":"LAN","resources":["catalog","meta","stream","subtitles"],"catalogs":[{"id":"shows","type":"series"}]}"""
                "/catalog/" in address -> """{"metas":[{"id":"local:show","name":"Show"}]}"""
                "/meta/" in address -> """{"meta":{"id":"local:show","type":"series","name":"Show","videos":[{"id":"local:exact:39","season":2,"episode":3}]}}"""
                "/stream/" in address -> """{"streams":[{"url":"http://192.168.1.20:8080/video.mkv","behaviorHints":{"proxyHeaders":{"request":{"X-Api-Key":"fixture","Cookie":"session=fixture"}}}},{"url":"http://192.168.1.21:8080/other.mkv"},{"url":"http://public.example/video"}]}"""
                else -> """{"subtitles":[{"url":"http://192.168.1.20:8080/Show.S02E03.srt","lang":"eng"},{"url":"http://192.168.1.21:8080/other.srt","lang":"eng"},{"url":"https://cdn.example/exact.srt","lang":"eng"}]}"""
            })
        }
        val addon = repo.install("$root/manifest.json")
        assertEquals("shows", repo.catalogs(addon.id).single().id)
        val item = repo.details(repo.catalog(addon.id, "shows", "series").single())
        val identity = repo.identity(item, item.episodes.single())
        val source = repo.sources(identity).single()
        assertEquals("local:exact:39", source.sourceId)
        assertEquals("http://192.168.1.20:8080", source.origin)
        assertEquals(mapOf("X-Api-Key" to "fixture", "Cookie" to "session=fixture"), source.headers)
        assertTrue(requests.last().endsWith("/stream/series/local%3Aexact%3A39.json"))
        assertEquals(2, repo.subtitles(identity).size)
        assertTrue(requests.last().endsWith("/subtitles/series/local%3Aexact%3A39.json"))
        try { repo.install("http://public.example/manifest.json"); fail("Public HTTP addon accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun `catalog and exact episode identities survive details and source lookup`() {
        val requests = mutableListOf<String>()
        val repo = AddonRepository(Memory()) { url ->
            requests += url
            JSONObject(when {
                url.endsWith("manifest.json") -> """{"id":"sample","name":"Sample","resources":["catalog","meta","stream"],"catalogs":[{"id":"collection","type":"series"}]}"""
                "/catalog/" in url -> """{"metas":[{"id":"custom:77","name":"Show"}]}"""
                "/meta/" in url -> """{"meta":{"id":"custom:77","type":"series","name":"Show","videos":[{"id":"episode:9988","season":2,"episode":3,"title":"Three"}]}}"""
                else -> """{"streams":[{"title":"1080p","url":"https://cdn.example/video","behaviorHints":{"proxyHeaders":{"request":{"Authorization":"Bearer private","Host":"evil.example"}}}}]}"""
            })
        }
        val addon = repo.install("https://addon.example/manifest.json")
        val title = repo.catalog(addon.id, "collection", "series").single()
        assertEquals(addon.id, title.addonId); assertEquals("collection", title.catalogId)
        val detailed = repo.details(title)
        val episode = detailed.episodes.single()
        assertEquals("episode:9988", episode.sourceId)
        val identity = repo.identity(detailed, episode)
        assertEquals(identity, PlaybackIdentity.fromJson(identity.toJson()))
        val source = repo.sources(detailed, episode).single()
        assertTrue(requests.last().endsWith("/stream/series/episode%3A9988.json"))
        assertEquals("Bearer private", source.headers["Authorization"])
        assertFalse(source.headers.containsKey("Host"))
        assertEquals("https://cdn.example", source.origin)
        assertEquals("episode:9988", source.sourceId)
    }
    @Test fun `configured copies of same manifest retain separate identity`() {
        val repo = AddonRepository(Memory()) { JSONObject("""{"id":"same","name":"Same","resources":["stream"]}""") }
        val a = repo.install("https://addon.example/config-a/manifest.json")
        val b = repo.install("https://addon.example/config-b/manifest.json")
        assertNotEquals(a.id, b.id); assertEquals(2, repo.list().size)
        repo.setEnabled(a.id, false)
        assertFalse(repo.list().single { it.id == a.id }.enabled)
        repo.remove(b.id); assertEquals(1, repo.list().size)
    }
    @Test fun `mismatched add-on metadata cannot silently retarget a saved title`() {
        val repo = AddonRepository(Memory()) { url -> JSONObject(if (url.endsWith("manifest.json"))
            """{"id":"sample","name":"Sample","resources":["meta"]}""" else """{"meta":{"id":"different","name":"Other"}}""") }
        val addon = repo.install("https://addon.example")
        try { repo.details(MediaItemData("saved", "movie", "Saved", addonId = addon.id)); fail("Retargeted title") }
        catch (_: IllegalStateException) { }
    }
    @Test fun `custom series identity requires an exact video ID rather than guessed episode numbering`() {
        val repo = AddonRepository(Memory()) { error("No request is allowed") }
        try { repo.sources(PlaybackIdentity("custom:77", "series", "Show", 1, 2)); fail("Guessed identity") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun `configured source endpoint is stateless and preserves supplied episode and headers`() {
        val memory = Memory(); val requests = mutableListOf<String>()
        val repo = AddonRepository(memory) { url -> requests += url; JSONObject("""{"streams":[{"url":"https://media.example/v","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://source.example/"}}}}]}""") }
        val identity = PlaybackIdentity("tt1234567", "series", "Show", 3, 2, sourceId = "exact:episode:777")
        val stream = repo.sourcesFromEndpoint(identity, "https://source.example/config/manifest.json").single()
        assertEquals("https://source.example/config/stream/series/exact%3Aepisode%3A777.json", requests.single())
        assertEquals("https://source.example/", stream.headers["Referer"])
        assertEquals("https://media.example", stream.origin)
        assertEquals("exact:episode:777", stream.sourceId)
        assertTrue(memory.values.isEmpty()); assertTrue(repo.list().isEmpty())
    }
    @Test fun `failed subtitle add-on does not discard exact identity results from another add-on`() {
        val requests = mutableListOf<String>()
        val repo = AddonRepository(Memory()) { url ->
            requests += url
            if (url.endsWith("manifest.json")) JSONObject("""{"id":"subtitles","name":"Subtitles","resources":["subtitles"]}""")
            else if (url.startsWith("https://broken.example")) throw ApiException(503, "Unavailable")
            else JSONObject("""{"subtitles":[{"lang":"eng","url":"https://subtitles.example/exact.srt"}]}""")
        }
        repo.install("https://broken.example"); val working = repo.install("https://working.example")
        val identity = PlaybackIdentity("custom:show", "series", "Show", 2, 1, "episode:exact", working.id)
        assertEquals(working.id, repo.subtitles(identity).single().addonId)
        assertTrue(requests.last().endsWith("/subtitles/series/episode%3Aexact.json"))
        repo.setEnabled(working.id, false)
        try { repo.subtitles(identity); fail("Disabled source accepted") } catch (_: IllegalStateException) { }
    }
}
