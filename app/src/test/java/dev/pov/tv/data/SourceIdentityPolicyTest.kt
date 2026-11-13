package dev.pov.tv.data

import org.junit.Assert.*
import org.junit.Test

class SourceIdentityPolicyTest {
    private val identity = PlaybackIdentity("custom:42", "series", "Show", 2, 4, "catalog-owner")
    private val source = SourceItem("Show.S02E04.mkv", "Source provider", infoHash = "a".repeat(40),
        sourceAddonId = "different-stream-provider", resourceType = "series", resourceId = "opaque:episode-video",
        requestIdentity = SourceRequestIdentity("catalog-owner", "custom:42", "series", "opaque:episode-video", 2, 4))

    @Test fun `source provider may differ from exact retained catalog owner`() {
        SourceIdentityPolicy.selected(source, identity)
        assertNotEquals(source.sourceAddonId, identity.catalogNamespace)
    }

    @Test fun `missing or mismatched owner media video type and episode provenance is rejected`() {
        val request = source.requestIdentity!!
        val variants = listOf(source.copy(requestIdentity = null), source.copy(sourceAddonId = ""), source.copy(resourceType = "movie"),
            source.copy(resourceId = "different-video"), source.copy(requestIdentity = request.copy(catalogNamespace = "another-owner")),
            source.copy(requestIdentity = request.copy(mediaId = "custom:99")), source.copy(requestIdentity = request.copy(type = "movie")),
            source.copy(requestIdentity = request.copy(season = 1)), source.copy(requestIdentity = request.copy(episode = 5)))
        variants.forEach { selected -> rejected { SourceIdentityPolicy.selected(selected, identity) } }
    }

    @Test fun `plain catalog search and unnamespaced custom calls stay blocked`() {
        rejected { SourceIdentityPolicy.catalog(identity) }
        rejected { SourceIdentityPolicy.selected(source, identity.copy(catalogNamespace = "")) }
        rejected { SourceIdentityPolicy.selected(source, identity.copy(episode = 0)) }
        rejected { SourceIdentityPolicy.selected(source.copy(title = "Show.S02E05.mkv"), identity) }
    }

    @Test fun `movie source must retain exact title resource ID`() {
        val movie = PlaybackIdentity("custom:42", "movie", "Film", catalogNamespace = "catalog-owner")
        val selected = source.copy(title = "Film", resourceType = "movie", resourceId = movie.imdbId,
            requestIdentity = SourceRequestIdentity("catalog-owner", movie.imdbId, "movie", movie.imdbId))
        SourceIdentityPolicy.selected(selected, movie)
        rejected { SourceIdentityPolicy.selected(selected.copy(resourceId = "custom:99",
            requestIdentity = selected.requestIdentity!!.copy(videoId = "custom:99")), movie) }
    }

    @Test fun `every cloud transfer backend rejects bad provenance before network`() {
        var calls = 0
        val http = object : ProviderTransport {
            override fun request(url: String, method: String, headers: Map<String, String>, fields: List<Pair<String, String>>?, json: org.json.JSONObject?): String {
                calls++
                error("Invalid selection reached provider API")
            }
        }
        val backends = listOf(RealDebridProvider(http), AllDebridProvider(http), PremiumizeProvider(http), OffcloudProvider(http),
            DebridLinkProvider(http, pause = {}), PutIoProvider(http, pause = {}))
        backends.forEach { backend ->
            rejected { backend.prepare("unused-fixture", source.copy(requestIdentity = null), identity) }
            rejected { backend.prepare("unused-fixture", source.copy(requestIdentity = source.requestIdentity!!.copy(episode = 5)), identity) }
        }
        assertEquals(0, calls)
    }

    private fun rejected(work: () -> Unit) {
        try { work(); fail("Expected invalid source selection to be rejected") } catch (_: IllegalArgumentException) { }
    }
}
