package dev.pov.tv.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AddonCatalogApiTest {
    private class Memory : AddonStore { var value = ""; override fun get() = value; override fun put(value: String) { this.value = value } }
    private data class Request(val url: String, val headers: Map<String, String>)
    private class Fake(private val answer: (Request) -> String) : DesktopTransport {
        val requests = mutableListOf<Request>()
        override fun request(url: String, method: String, headers: Map<String, String>, body: String?, contentType: String): String =
            Request(url, headers).let { requests += it; answer(it) }
    }
    private val manifest = """{"id":"fixture.addon","name":"Fixture","version":"1","types":["series","movie"],"resources":["catalog","meta",{"name":"stream","types":["series"],"idPrefixes":["custom:"]},"subtitles"],"catalogs":[{"id":"featured","type":"series","name":"Featured","extra":[{"name":"search"},{"name":"skip"}]}]}"""

    @Test fun `install normalizes Stremio link and preserves actual manifest catalogs`() {
        val http = Fake { assertEquals("https://addon.example/private-config/manifest.json", it.url); manifest }
        val api = AddonCatalogApi(Memory(), http)
        val addon = api.install("stremio://addon.example/private-config/manifest.json")
        assertEquals("fixture.addon", addon.manifestId)
        assertEquals(listOf("search", "skip"), addon.catalogs.single().extras)
        assertEquals(addon, api.installed().single())
        assertTrue(http.requests.single().headers.isEmpty())
    }

    @Test fun `catalog follows declared extras and retains custom title and addon identities`() {
        val http = Fake { if (it.url.endsWith("manifest.json")) manifest else {
            assertTrue(it.url.endsWith("/catalog/series/featured/search=an%20anime&skip=100.json"))
            """{"metas":[{"id":"custom:42","type":"series","name":"Anime"}]}"""
        } }
        val api = AddonCatalogApi(Memory(), http)
        val addon = api.install("https://addon.example/manifest.json")
        val item = api.catalog(addon.id, addon.catalogs.single(), "an anime", 100).single()
        assertEquals("custom:42", item.id)
        assertEquals("custom:42", item.item.imdbId)
        assertEquals(addon.id, item.addonId)
        val count = http.requests.size
        try { api.catalog(addon.id, addon.catalogs.single(), genre = "Drama"); fail("Expected unsupported extra") } catch (_: IllegalArgumentException) { }
        assertEquals(count, http.requests.size)
    }

    @Test fun `metadata preserves custom episode IDs and rejects different title details`() {
        var mismatch = false
        val api = AddonCatalogApi(Memory(), Fake { if (it.url.endsWith("manifest.json")) manifest else
            if (mismatch) """{"meta":{"id":"custom:other","name":"Other","type":"series"}}""" else
            """{"meta":{"id":"custom:42","name":"Anime","type":"series","videos":[{"id":"episode:unique","season":2,"episode":4,"title":"Part"}]}}"""
        })
        val addon = api.install("https://addon.example")
        val item = api.detail(addon.id, "series", "custom:42")
        assertEquals("episode:unique", item.episodes.single().id)
        assertEquals(4, item.item.episodes.single().episode)
        mismatch = true
        try { api.detail(addon.id, "series", "custom:42"); fail("Expected identity mismatch") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `streams preserve proxy headers custom video ID and provenance`() {
        val http = Fake { if (it.url.endsWith("manifest.json")) manifest else {
            assertTrue(it.url.endsWith("/stream/series/custom%3A42%3Aspecial.json"))
            """{"streams":[{"name":"Direct","url":"https://video.example/movie.mkv","behaviorHints":{"filename":"Example.S01E02.mkv","proxyHeaders":{"request":{"Authorization":"Bearer fixture","Referer":"https://origin.example/"}}}}]}"""
        } }
        val api = AddonCatalogApi(Memory(), http)
        val addon = api.install("https://addon.example")
        val source = api.streams(addon.id, PlaybackIdentity("custom:42", "series", "Example", 1, 2), "custom:42:special").single()
        assertEquals("Bearer fixture", source.requestHeaders["Authorization"])
        assertEquals("custom:42:special", source.resourceId)
        assertEquals("series", source.resourceType)
        assertEquals(addon.id, source.sourceAddonId)
        assertEquals(SourceRequestIdentity("", "custom:42", "series", "custom:42:special", 1, 2), source.requestIdentity)
        assertTrue(http.requests.all { it.headers.isEmpty() })
    }

    @Test fun `unsupported resource types and prefixes never produce requests`() {
        val http = Fake { manifest }
        val api = AddonCatalogApi(Memory(), http)
        val addon = api.install("https://addon.example")
        try { api.streams(addon.id, PlaybackIdentity("tt1234567", "movie", "Film")); fail("Expected type rejection") } catch (_: IllegalArgumentException) { }
        try { api.streams(addon.id, PlaybackIdentity("tt1234567", "series", "Show", 1, 1)); fail("Expected prefix rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(1, http.requests.size)
    }

    @Test fun `disabled addons cannot request resources and stale removed response is rejected`() {
        lateinit var api: AddonCatalogApi
        var removeOnResponse = false
        val http = Fake { if (it.url.endsWith("manifest.json")) manifest else {
            if (removeOnResponse) api.remove(api.installed().single().id)
            """{"metas":[]}"""
        } }
        api = AddonCatalogApi(Memory(), http)
        val addon = api.install("https://addon.example")
        api.setEnabled(addon.id, false)
        try { api.catalog(addon.id, addon.catalogs.single()); fail("Expected disabled rejection") } catch (_: IllegalStateException) { }
        assertEquals(1, http.requests.size)
        api.setEnabled(addon.id, true)
        removeOnResponse = true
        try { api.catalog(addon.id, addon.catalogs.single()); fail("Expected removed-session rejection") } catch (_: IllegalStateException) { }
        assertTrue(api.installed().isEmpty())
    }

    @Test fun `malformed manifest or credential-bearing URL is never installed`() {
        val memory = Memory()
        val api = AddonCatalogApi(memory, Fake { """{"id":"bad","name":"Bad"}""" })
        try { api.install("https://addon.example"); fail("Expected no-resource rejection") } catch (_: IllegalArgumentException) { }
        try { api.install("https://user:password@addon.example"); fail("Expected URL credentials rejection") } catch (_: IllegalArgumentException) { }
        assertTrue(api.installed().isEmpty())
    }

    @Test fun `add-on subtitle resource retains provider and language identity`() {
        val api = AddonCatalogApi(Memory(), Fake { if (it.url.endsWith("manifest.json")) manifest else
            """{"subtitles":[{"id":"s1","lang":"spa","url":"https://subtitles.example/a.srt"},{"id":"bad","lang":"eng","url":"http://public.example/a.srt"}]}"""
        })
        val addon = api.install("https://addon.example")
        val subtitle = api.subtitles(addon.id, "series", "custom:42").single()
        assertEquals("spa", subtitle.language)
        assertEquals(addon.id, subtitle.addonId)
        assertEquals("Fixture", subtitle.provider)
    }

    @Test fun `proxy header injection and transport header overrides are rejected`() {
        val api = AddonCatalogApi(Memory(), Fake { if (it.url.endsWith("manifest.json")) manifest else
            """{"streams":[{"url":"https://video.example/movie.mkv","behaviorHints":{"proxyHeaders":{"request":{"Host":"other.example"}}}}]}"""
        })
        val addon = api.install("https://addon.example")
        try { api.streams(addon.id, PlaybackIdentity("custom:42", "series", "Example", 1, 2)); fail("Expected header rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `object resource with no prefixes matches all IDs while string resource inherits manifest prefixes`() {
        val http = Fake { if (it.url.endsWith("manifest.json"))
            """{"id":"fixture","name":"Fixture","types":["movie"],"idPrefixes":["tt"],"resources":["meta",{"name":"stream","types":["movie"]}]}"""
            else """{"streams":[{"name":"Edition","url":"https://video.example/file.mkv"}]}"""
        }
        val api = AddonCatalogApi(Memory(), http)
        val addon = api.install("https://addon.example")
        assertEquals(1, api.streams(addon.id, PlaybackIdentity("custom:42", "movie", "Film")).size)
        assertFalse(addon.resources.single { it.name == "stream" }.inheritGlobalFilters)
        try { api.detail(addon.id, "movie", "custom:42"); fail("Expected inherited metadata prefix rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(2, http.requests.size)
    }

    @Test fun `disable and enable during an in-flight catalog request rejects old response`() {
        lateinit var api: AddonCatalogApi
        val http = Fake { if (it.url.endsWith("manifest.json")) manifest else {
            val id = api.installed().single().id
            api.setEnabled(id, false)
            api.setEnabled(id, true)
            """{"metas":[{"id":"custom:42","type":"series","name":"Stale"}]}"""
        } }
        api = AddonCatalogApi(Memory(), http)
        val addon = api.install("https://addon.example")
        try { api.catalog(addon.id, addon.catalogs.single()); fail("Expected changed-generation rejection") } catch (_: IllegalStateException) { }
        assertTrue(api.installed().single().enabled)
        assertNotEquals(addon.revision, api.installed().single().revision)
    }

    @Test fun `malformed stored rows cannot be silently dropped or replaced`() {
        val memory = Memory()
        val api = AddonCatalogApi(memory, Fake { manifest })
        val addon = api.install("https://addon.example")
        memory.value = org.json.JSONArray(memory.value).put(17).toString()
        val saved = memory.value
        try { api.installed(); fail("Expected damaged storage rejection") } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("preserved")) }
        try { api.remove(addon.id); fail("Expected no rewrite of damaged storage") } catch (_: IllegalStateException) { }
        assertEquals(saved, memory.value)
    }

    @Test fun `configuration required manifest cannot masquerade as a working installation`() {
        val memory = Memory()
        val raw = org.json.JSONObject(manifest).put("behaviorHints", org.json.JSONObject().put("configurationRequired", true)).toString()
        val api = AddonCatalogApi(memory, Fake { raw })
        try { api.install("https://addon.example"); fail("Expected provider configuration instruction") } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("Configure this add-on"))
        }
        assertEquals("", memory.value)
    }

    @Test fun `case-insensitive duplicate or control-bearing playback headers reject resource`() {
        listOf("""{"Authorization":"Bearer first","authorization":"Bearer second"}""", """{"Referer":"unsafe\u0001value"}""").forEach { headers ->
            val api = AddonCatalogApi(Memory(), Fake { if (it.url.endsWith("manifest.json")) manifest else
                """{"streams":[{"url":"https://video.example/file.mkv","behaviorHints":{"proxyHeaders":{"request":$headers}}}]}"""
            })
            val addon = api.install("https://addon.example")
            try { api.streams(addon.id, PlaybackIdentity("custom:42", "series", "Anime", 1, 2)); fail("Expected invalid header rejection") } catch (_: IllegalArgumentException) { }
        }
    }
}
