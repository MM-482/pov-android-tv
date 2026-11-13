package dev.pov.tv

import dev.pov.tv.data.EpisodeData
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CameoUpNextTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
    private val episode = PlaybackIdentity("tt1234567", "series", "Series", 1, 10)

    @Test fun `exact next episode crosses seasons without renumbering or guessing an identity`() {
        val details = MediaItemData(episode.imdbId, "series", "Series", episodes = listOf(
            EpisodeData(2, 2, "Later"), EpisodeData(2, 1, "Return", "2020-01-01"),
            EpisodeData(1, 10, "Finale"), EpisodeData(2, 1, "Duplicate")))
        val next = CameoUpNext.select(episode, details, emptyList(), nowMillis = now)
        assertEquals(2, next?.season); assertEquals(1, next?.episode)
        assertEquals(episode.imdbId, next?.imdbId)
    }

    @Test fun `future exact next episode blocks a later episode even if later metadata says aired`() {
        val current = episode.copy(episode = 1)
        val details = MediaItemData(episode.imdbId, "series", "Series", episodes = listOf(
            EpisodeData(1, 1, "Current"), EpisodeData(1, 2, "Future", "2099-01-01"),
            EpisodeData(1, 3, "Incorrect later date", "2020-01-01")))
        assertNull(CameoUpNext.select(current, details, emptyList(), nowMillis = now))
        assertNull(CameoUpNext.select(current.copy(episode = 0), details, emptyList(), nowMillis = now))
    }

    @Test fun `full UTC and date only release times are respected`() {
        val details = MediaItemData(episode.imdbId, "series", "Series", episodes = listOf(
            EpisodeData(2, 1, "Later today", "2026-11-02T13:00:00.000Z")))
        assertNull(CameoUpNext.select(episode, details, emptyList(), nowMillis = now))
        assertEquals(1, CameoUpNext.select(episode, details.copy(episodes = listOf(EpisodeData(2, 1, "Today", "2026-11-02"))),
            emptyList(), nowMillis = now)?.episode)
    }

    @Test fun `film choice ranks matching genres and excludes recent current duplicate and series entries`() {
        val current = PlaybackIdentity("tt0000001", "movie", "Current")
        val details = MediaItemData(current.imdbId, "movie", "Current", genres = listOf("Horror", "Mystery"))
        val comedy = MediaItemData("tt0000002", "movie", "Comedy", genres = listOf("Comedy"))
        val recent = MediaItemData("tt0000003", "movie", "Recent", genres = listOf("Horror", "Mystery"))
        val match = MediaItemData("tt0000004", "movie", "Match", genres = listOf(" horror "))
        val series = MediaItemData("tt0000005", "series", "Series", genres = listOf("Horror", "Mystery"))
        assertEquals(match.imdbId, CameoUpNext.select(current, details, listOf(details, comedy, recent, series, match, match),
            setOf(recent.imdbId), now)?.imdbId)
    }

    @Test fun `movie score ties retain stable first catalog order`() {
        val current = PlaybackIdentity("tt0000001", "movie", "Current")
        val details = MediaItemData(current.imdbId, "movie", "Current", genres = listOf("Mystery"))
        val first = MediaItemData("tt0000002", "movie", "First", genres = listOf("Mystery"))
        val second = first.copy(imdbId = "tt0000003", title = "Second")
        assertEquals(first.imdbId, CameoUpNext.select(current, details, listOf(first, second), nowMillis = now)?.imdbId)
    }

    @Test fun `visibility uses desktop thresholds and never guesses custom catalog or unknown episode identity`() {
        val movie = PlaybackIdentity("tt0000001", "movie", "Current")
        assertFalse(CameoUpNext.eligible(movie, 4_000, 5_000))
        assertFalse(CameoUpNext.eligible(movie, 509_000, 600_000))
        assertTrue(CameoUpNext.eligible(movie, 510_000, 600_000))
        assertFalse(CameoUpNext.eligible(episode, 554_000, 600_000))
        assertTrue(CameoUpNext.eligible(episode, 555_000, 600_000))
        assertFalse(CameoUpNext.eligible(movie, 999_000, 600_000))
        assertFalse(CameoUpNext.eligible(movie.copy(catalogNamespace = "custom:addon"), 590_000, 600_000))
        assertFalse(CameoUpNext.eligible(episode.copy(episode = 0), 590_000, 600_000))
    }
}
