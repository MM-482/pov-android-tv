package dev.pov.tv

import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Public-metadata recommendations only. Selecting a card must still open explicit sources. */
internal object CameoUpNext {
    private val imdb = Regex("tt[0-9]{5,12}")

    fun eligible(identity: PlaybackIdentity, positionMillis: Long, durationMillis: Long): Boolean =
        identity.catalogNamespace.isBlank() && imdb.matches(identity.imdbId) && identity.type in setOf("movie", "series") &&
            (identity.type == "movie" || (identity.season >= 0 && identity.episode > 0)) &&
            durationMillis >= 180_000L && positionMillis >= 0 && positionMillis <= durationMillis + 1_000L &&
            durationMillis - positionMillis <= if (identity.type == "series") 45_000L else 90_000L

    fun select(current: PlaybackIdentity, details: MediaItemData, candidates: List<MediaItemData>,
        recentlyPlayedIds: Set<String> = emptySet(), nowMillis: Long = System.currentTimeMillis()): PlaybackIdentity? {
        if (current.catalogNamespace.isNotBlank() || !imdb.matches(current.imdbId) ||
            details.imdbId != current.imdbId || details.type != current.type) return null
        if (current.type == "series") {
            if (current.season < 0 || current.episode <= 0) return null
            val next = details.episodes.filter { it.season >= 0 && it.episode > 0 }
                .distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
                .firstOrNull { it.season > current.season || it.season == current.season && it.episode > current.episode }
                ?: return null
            // Evaluate the exact next metadata entry before release filtering. Do not leap over it.
            val released = releaseTime(next.released)
            if (released != null && released > nowMillis) return null
            return PlaybackIdentity(details.imdbId, "series", details.title, next.season, next.episode)
        }
        if (current.type != "movie") return null
        val genres = details.genres.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        val next = candidates.filter { it.type == "movie" && imdb.matches(it.imdbId) &&
            it.imdbId != current.imdbId && it.imdbId !in recentlyPlayedIds }.distinctBy { it.imdbId }
            .withIndex().maxWithOrNull(compareBy<IndexedValue<MediaItemData>> {
                it.value.genres.map { value -> value.trim().lowercase(Locale.ROOT) }.toSet().intersect(genres).size
            }.thenBy { -it.index })?.value ?: return null
        return PlaybackIdentity(next.imdbId, "movie", next.title)
    }

    private fun releaseTime(raw: String): Long? {
        if (raw.isBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() ?:
            runCatching { LocalDate.parse(raw.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
}
