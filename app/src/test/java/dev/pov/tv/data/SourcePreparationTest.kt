package dev.pov.tv.data

import org.junit.Assert.*
import org.junit.Test

/** Synthetic service adapters only: preparing choices must not silently pick an edition. */
class SourcePreparationTest {
    private val movie = PlaybackIdentity("tt1234567", "movie", "Example Movie")
    private val episode = PlaybackIdentity("tt7654321", "series", "Example Show", 1, 2)

    private class Cloud : SourceTorBox {
        var files = emptyList<CloudFile>()
        var ready = true
        var added = 0
        var lookedUp = 0
        var streamed = mutableListOf<Long>()
        var missingFirst = false
        var returnedId = 77L
        override fun cached(hashes: List<String>) = emptySet<String>()
        override fun addMagnet(hash: String): Long { added++; return 77 }
        override fun folder(kind: String, id: Long): CloudFolder {
            lookedUp++
            if (missingFirst && lookedUp == 1) throw ApiException(404, "The requested item is unavailable.")
            return CloudFolder(returnedId, "torrents", "Synthetic source", ready, files)
        }
        override fun streamUrl(kind: String, folderId: Long, fileId: Long): String {
            assertEquals("torrents", kind); assertEquals(77L, folderId)
            streamed.add(fileId)
            return "https://media.example.invalid/video/$fileId"
        }
    }

    private class Harness {
        val cloud = Cloud()
        var nanos = 0L
        val resolver = SourceResolver({ "" }, cloud, { error("No catalog network request should occur") },
            { nanos }, { delay -> nanos += delay * 1_000_000 })
    }

    @Test fun `three movie cuts remain separate choices and the exact middle filename only suggests focus`() {
        val h = Harness()
        h.cloud.files = listOf(file(11, "Movie.Theatrical.720p.mkv"), file(43, "Movie.Directors.Cut.1080p.mkv"), file(908, "Movie.Extended.2160p.mkv"))
        val prepared = h.resolver.prepare(source("Movie.Directors.Cut.1080p.mkv", fileIndex = 1), movie) as PreparedSource.Files
        assertEquals(listOf(11L, 43L, 908L), prepared.files.map { it.id })
        assertEquals(43L, prepared.preferredFileId)
        assertNull("An exact name must not hide other versions", prepared.automaticFile)
        assertEquals(movie, prepared.identity)
        assertEquals(1, h.cloud.added)
        assertTrue("Preparing choices must not request a playback link", h.cloud.streamed.isEmpty())
        val middle = prepared.files[1]
        h.cloud.streamUrl(prepared.folder.kind, prepared.folder.id, middle.id)
        assertEquals(listOf(43L), h.cloud.streamed)
    }

    @Test fun `all versions of the requested episode remain selectable with stable TorBox IDs`() {
        val h = Harness()
        h.cloud.files = listOf(file(100, "Show.S01E01.mkv"), file(204, "Show.S01E02.720p.mkv"),
            file(550, "Show.S01E02.1080p.mkv"), file(999, "Show.S01E02.2160p.mkv"), file(1000, "Show.S01E03.mkv"))
        val prepared = h.resolver.prepare(source("Show.S01E02.1080p.mkv", fileIndex = 1), episode) as PreparedSource.Files
        assertEquals(listOf(204L, 550L, 999L), prepared.files.map { it.id })
        assertEquals(550L, prepared.preferredFileId)
        assertNull(prepared.automaticFile)
        assertEquals(episode, prepared.identity)
        assertTrue(h.cloud.streamed.isEmpty())
    }

    @Test fun `samples trailers extras subtitles and metadata are excluded from file choices`() {
        val h = Harness()
        h.cloud.files = listOf(file(1, "sample.mkv"), file(2, "extras/interview.mp4"), file(3, "Movie.trailer.mp4"),
            file(4, "Movie.srt"), file(5, "Movie.nfo"), file(6, "Movie.720p.mkv"), file(7, "Movie.1080p.mkv"))
        val prepared = h.resolver.prepare(source("Movie"), movie) as PreparedSource.Files
        assertEquals(listOf(6L, 7L), prepared.files.map { it.id })
        assertNull(prepared.automaticFile)
    }

    @Test fun `a single matching episode can still play without an unnecessary file picker`() {
        val h = Harness()
        h.cloud.files = listOf(file(7, "Show.S01E01.mkv"), file(81, "Show.S01E02.mkv"), file(500, "Show.S01E03.mkv"))
        val prepared = h.resolver.prepare(source("Show Season 1"), episode) as PreparedSource.Files
        assertEquals(81L, prepared.automaticFile?.id)
        assertEquals(81L, prepared.preferredFileId)
        assertTrue(h.cloud.streamed.isEmpty())
        assertEquals("https://media.example.invalid/video/81", h.resolver.resolve(source("Show Season 1"), episode))
        assertEquals(listOf(81L), h.cloud.streamed)
    }

    @Test fun `legacy single-stream callers cannot silently pick an exact filename from multiple versions`() {
        val h = Harness()
        h.cloud.files = listOf(file(9, "Movie.720p.mkv"), file(30, "Movie.1080p.mkv"), file(70, "Movie.2160p.mkv"))
        val error = failure { h.resolver.resolve(source("Movie.1080p.mkv"), movie) }
        assertTrue(error.message.orEmpty().contains("Choose a video file"))
        assertTrue(h.cloud.streamed.isEmpty())
    }

    @Test fun `unmarked multi-episode packs cannot be assigned a guessed episode identity`() {
        val h = Harness()
        h.cloud.files = listOf(file(1, "part-one.mkv"), file(2, "part-two.mkv"), file(3, "part-three.mkv"))
        val error = failure { h.resolver.prepare(source("Example Show collection"), episode) }
        assertTrue(error.message.orEmpty().contains("could not be matched safely"))
        assertTrue(h.cloud.streamed.isEmpty())
    }

    @Test fun `an exact source filename can safely narrow an otherwise unmarked episode pack`() {
        val h = Harness()
        h.cloud.files = listOf(file(10, "part-one.mkv"), file(44, "part-two.mkv"), file(80, "part-three.mkv"))
        val prepared = h.resolver.prepare(source("part-two.mkv"), episode) as PreparedSource.Files
        assertEquals(listOf(44L), prepared.files.map { it.id })
        assertEquals(44L, prepared.automaticFile?.id)
    }

    @Test fun `explicit wrong episodes and sample-only sources produce useful failures`() {
        val h = Harness()
        h.cloud.files = listOf(file(1, "Show.S02E02.mkv"))
        assertTrue(failure { h.resolver.prepare(source("Show collection"), episode) }.message.orEmpty().contains("different episode"))
        h.cloud.files = listOf(file(9, "sample.mkv"))
        assertTrue(failure { h.resolver.prepare(source("Movie"), movie) }.message.orEmpty().contains("playable video"))
    }

    @Test fun `not-ready transfers stop polling after the bounded window without requesting a stream`() {
        val h = Harness()
        h.cloud.ready = false
        val error = failure { h.resolver.prepare(source("Movie"), movie) }
        assertTrue(error.message.orEmpty().contains("not ready"))
        assertEquals(20_000_000_000L, h.nanos)
        assertEquals(1, h.cloud.added)
        assertTrue(h.cloud.lookedUp in 2..15)
        assertTrue(h.cloud.streamed.isEmpty())
    }

    @Test fun `newly accepted transfers can appear after a temporary not-found response`() {
        val h = Harness()
        h.cloud.missingFirst = true
        h.cloud.files = listOf(file(65, "Movie.mkv"))
        val prepared = h.resolver.prepare(source("Movie"), movie) as PreparedSource.Files
        assertEquals(65L, prepared.automaticFile?.id)
        assertEquals(2, h.cloud.lookedUp)
    }

    @Test fun `direct HTTPS sources do not create or inspect TorBox transfers`() {
        val h = Harness()
        val prepared = h.resolver.prepare(source("Movie").copy(directUrl = "https://media.example.invalid/direct"), movie) as PreparedSource.Direct
        assertEquals("https://media.example.invalid/direct", prepared.url)
        assertEquals(movie, prepared.identity)
        assertEquals(0, h.cloud.added)
        assertEquals(0, h.cloud.lookedUp)
    }

    @Test fun `conflicting repeated file IDs and wrong transfer responses are rejected`() {
        val h = Harness()
        h.cloud.files = listOf(file(9, "Movie.720p.mkv"), file(9, "Movie.1080p.mkv"))
        assertTrue(failure { h.resolver.prepare(source("Movie"), movie) }.message.orEmpty().contains("conflicting"))
        h.cloud.files = listOf(file(9, "Movie.mkv")); h.cloud.returnedId = 78
        assertTrue(failure { h.resolver.prepare(source("Movie"), movie) }.message.orEmpty().contains("different transfer"))
        assertTrue(h.cloud.streamed.isEmpty())
    }

    @Test fun `namespaced custom addon title can prepare exact editions without an IMDb guess`() {
        val h = Harness()
        h.cloud.files = listOf(file(11, "Movie.720p.mkv"), file(43, "Movie.1080p.mkv"))
        val identity = movie.copy(imdbId = "custom:42", catalogNamespace = "original-catalog")
        val selected = source("Movie").copy(sourceAddonId = "source-provider", resourceType = "movie", resourceId = "custom:42",
            requestIdentity = SourceRequestIdentity("original-catalog", "custom:42", "movie", "custom:42"))
        val prepared = h.resolver.prepare(selected, identity) as PreparedSource.Files
        assertEquals(identity, prepared.identity)
        assertEquals(listOf(11L, 43L), prepared.files.map { it.id })
        assertNull(prepared.automaticFile)
    }

    @Test fun `custom title without explicit addon provenance cannot create a transfer`() {
        val h = Harness()
        val identity = movie.copy(imdbId = "custom:42", catalogNamespace = "original-catalog")
        try { h.resolver.prepare(source("Movie"), identity); fail("Expected provenance requirement") } catch (_: IllegalArgumentException) { }
        assertEquals(0, h.cloud.added)
        try { h.resolver.sourcesUncached(identity); fail("Legacy source lookup must retain IMDb contract") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `custom series source still needs the exact declared episode`() {
        val h = Harness()
        val identity = PlaybackIdentity("custom:42", "series", "Show", catalogNamespace = "original-catalog")
        val selected = source("Show").copy(sourceAddonId = "source-provider", resourceType = "series", resourceId = "episode:exact")
        try { h.resolver.prepare(selected, identity); fail("Expected episode requirement") } catch (_: IllegalArgumentException) { }
        assertEquals(0, h.cloud.added)
    }

    private fun source(title: String, fileIndex: Int? = null) = SourceItem(title, "synthetic-provider", infoHash = "a".repeat(40), fileIndex = fileIndex)
    private fun file(id: Long, name: String) = CloudFile(id, name, id * 1000)
    private fun failure(work: () -> Any?): IllegalStateException {
        try { work(); fail("Expected a safe preparation failure") } catch (error: IllegalStateException) { return error }
        error("Expected a safe preparation failure")
    }
}
