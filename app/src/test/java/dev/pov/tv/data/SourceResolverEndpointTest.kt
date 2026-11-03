package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SourceResolverEndpointTest {
    private class Cloud : SourceTorBox {
        var cacheCalls = 0
        var mutations = 0
        var cachedHashes = emptySet<String>()
        override fun cached(hashes: List<String>): Set<String> { cacheCalls++; return cachedHashes.intersect(hashes.toSet()) }
        override fun addMagnet(hash: String): Long { mutations++; error("No transfer expected") }
        override fun folder(kind: String, id: Long): CloudFolder = error("No folder request expected")
        override fun streamUrl(kind: String, folderId: Long, fileId: Long): String = error("No stream request expected")
    }
    private val movie = PlaybackIdentity("tt1234567", "movie", "Movie")

    @Test fun `legacy source lookup preserves exact video ID and safe proxy headers through direct playback`() {
        val requests = mutableListOf<String>()
        val cloud = Cloud()
        val headers = JSONObject().put("Authorization", "Bearer fixture").put("Cookie", "session=fixture")
            .put("Referer", "https://source.example/").put("X-Api-Key", "fixture-key")
            .put("Host", "evil.example").put("Range", "bytes=20-").put("Proxy-Authorization", "unsafe")
            .put("Bad Header", "invalid").put("X-Control", "value\r\nInjected: bad")
        val stream = JSONObject().put("title", "Show.S02E03.mkv").put("url", "https://media.example/video")
            .put("behaviorHints", JSONObject().put("proxyHeaders", JSONObject().put("request", headers)))
        val resolver = SourceResolver({ " StReMiO://source.example/Config/MANIFEST.JSON/ " }, cloud, { address ->
            requests += address; JSONObject().put("streams", JSONArray().put(stream))
        })
        val identity = PlaybackIdentity("tt1234567", "series", "Show", 2, 3, "exact:episode:9988")
        val source = resolver.sourcesUncached(identity).single()
        assertEquals("https://source.example/Config/stream/series/exact%3Aepisode%3A9988.json", requests.single())
        assertEquals(identity.sourceId, source.sourceId)
        assertEquals(mapOf("Authorization" to "Bearer fixture", "Cookie" to "session=fixture",
            "Referer" to "https://source.example/", "X-Api-Key" to "fixture-key"), source.headers)
        assertEquals("https://media.example", source.origin)
        val prepared = resolver.prepare(source, identity) as PreparedSource.Direct
        assertEquals(source.headers, prepared.headers); assertEquals(source.origin, prepared.origin)
        assertEquals(identity, prepared.identity)
        assertEquals(0, cloud.cacheCalls); assertEquals(0, cloud.mutations)
        try { resolver.resolve(source, identity); fail("String-only caller silently lost required headers") }
        catch (_: IllegalStateException) { }
    }

    @Test fun `local source endpoint permits only its exact HTTP origin and carries the configured scope`() {
        val cloud = Cloud()
        val resolver = SourceResolver({ "http://192.168.1.20:8080/config/manifest.json" }, cloud, {
            JSONObject("""{"streams":[
                {"title":"Local","url":"http://192.168.1.20:8080/video.mkv","behaviorHints":{"proxyHeaders":{"request":{"X-Api-Key":"fixture"}}}},
                {"url":"http://192.168.1.20:8081/wrong-port.mkv"},
                {"url":"http://192.168.1.21:8080/wrong-host.mkv"},
                {"url":"http://public.example/unsafe.mkv"}
            ]}""")
        })
        val source = resolver.sources(movie).single()
        assertEquals("http://192.168.1.20:8080", source.origin)
        val prepared = resolver.prepare(source, movie) as PreparedSource.Direct
        assertEquals(source.directUrl, prepared.url); assertEquals(source.origin, prepared.origin)
        assertEquals(mapOf("X-Api-Key" to "fixture"), prepared.headers)
        assertEquals(0, cloud.cacheCalls); assertEquals(0, cloud.mutations)
        try { resolver.prepare(source.copy(origin = "http://192.168.1.21:8080"), movie); fail("Cross-origin HTTP request accepted") }
        catch (_: IllegalArgumentException) { }
        try { resolver.resolve(source, movie); fail("String-only caller lost configured local origin") }
        catch (_: IllegalStateException) { }
    }

    @Test fun `safe source header parsing retains legacy TorBox cache order without creating transfers`() {
        val ready = "a".repeat(40); val waiting = "b".repeat(40)
        val cloud = Cloud().apply { cachedHashes = setOf(ready) }
        val resolver = SourceResolver({ "https://source.example" }, cloud, {
            JSONObject().put("streams", JSONArray()
                .put(JSONObject().put("infoHash", waiting).put("title", "Waiting.mkv"))
                .put(JSONObject().put("infoHash", ready).put("title", "Ready.mkv")))
        })
        val sources = resolver.sources(movie)
        assertEquals(listOf(ready, waiting), sources.map { it.infoHash })
        assertTrue(sources[0].cached); assertFalse(sources[1].cached)
        assertTrue(sources.all { it.sourceId == movie.imdbId })
        assertEquals(1, cloud.cacheCalls); assertEquals(0, cloud.mutations)
    }

    @Test fun `source address normalization accepts explicit local hosts without making DNS requests`() {
        assertEquals("https://source.example/Config", SourceResolver.normalizeEndpoint("StReMiO://source.example/Config/MANIFEST.JSON/"))
        assertEquals("http://media.local:8080/config", SourceResolver.normalizeEndpoint("HTTP://media.local:8080/config/manifest.json"))
        assertEquals("https://torrentio.strem.fun", SourceResolver.normalizeEndpoint(""))
        listOf("http://public.example/manifest.json", "https://user:secret@source.example/", "https://source.example/?token=secret",
            "https://source.example/#fragment", "file:///video", "http://10.0.0.1:0/").forEach { address ->
            try { SourceResolver.normalizeEndpoint(address); fail("Unsafe source address accepted: $address") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `legacy source lookup never guesses a request for custom catalog IDs`() {
        val cloud = Cloud()
        val resolver = SourceResolver({ "https://source.example" }, cloud, { throw AssertionError("Guessed lookup") })
        val identity = PlaybackIdentity("custom:show:42", "series", "Show", 2, 3, "video:9988", "configured-addon")
        try { resolver.sourcesUncached(identity); fail("Legacy lookup guessed custom catalog mapping") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, cloud.cacheCalls); assertEquals(0, cloud.mutations)
    }
}
