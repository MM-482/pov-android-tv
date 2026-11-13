package dev.pov.tv.data

import android.content.Context
import org.json.JSONObject
import java.net.URI

/** Public Cinemeta metadata, with no embedded third-party account credentials. Call on a worker. */
class CatalogApi private constructor(private val diskCache: PublicCatalogCache?, private val publicJson: (String) -> JSONObject) {
    internal constructor(publicJson: (String) -> JSONObject) : this(null, publicJson)
    constructor() : this(Net::publicJson)
    constructor(context: Context) : this(PublicCatalogCache(context))
    private constructor(cache: PublicCatalogCache) : this(cache, cache::json)
    val lastCacheRead: PublicCatalogCacheReply? get() = diskCache?.lastRead
    fun cacheInfo(): PublicCatalogCacheInfo = diskCache?.info() ?: PublicCatalogCacheInfo(0, 0, 200, 24 * 1024 * 1024L)
    fun clearCache() { diskCache?.clear() }
    fun popular(type: String, skip: Int = 0, genre: String = ""): List<MediaItemData> {
        requireType(type)
        require(skip >= 0) { "The catalog offset cannot be negative." }
        val suffix = genre.trim().takeUnless { it.isBlank() || it == "All" || it == "All genres" }?.also {
            require(it in genres(type)) { "Choose a supported catalog genre." }
        }?.let { "&genre=${Net.encode(it)}" }.orEmpty()
        return catalog("$BASE/catalog/$type/top/skip=$skip$suffix.json", type)
    }

    /** Official Cinemeta top-catalog manifest genre options, verified November 2, 2026. */
    fun genres(type: String): List<String> {
        requireType(type)
        return MOVIE_GENRES + if (type == "series") listOf("Reality-TV", "Talk-Show", "Game-Show") else emptyList()
    }

    fun search(query: String, type: String): List<MediaItemData> {
        requireType(type)
        val term = query.trim().take(200)
        if (term.isEmpty()) return emptyList()
        return catalog("$BASE/catalog/$type/top/search=${Net.encode(term)}.json", type)
    }

    fun details(item: MediaItemData): MediaItemData {
        requireType(item.type)
        require(IMDB_ID.matches(item.imdbId)) { "This title has no supported IMDb identifier." }
        val meta = publicJson("$BASE/meta/${item.type}/${item.imdbId}.json")
            .optJSONObject("meta") ?: throw IllegalStateException("Title details are unavailable.")
        check(!meta.has("type") || meta.isNull("type") || meta.optString("type") == item.type) {
            "The catalog returned a different media type. Choose the matching movie or TV show."
        }
        val parsed = parseMeta(meta, item.type) ?: throw IllegalStateException("Title details are unavailable.")
        check(parsed.imdbId == item.imdbId) { "The catalog returned details for a different title. Please try again." }
        return parsed.copy(
            title = parsed.title.ifBlank { item.title },
            year = parsed.year.ifBlank { item.year },
            poster = parsed.poster.ifBlank { item.poster },
            background = parsed.background.ifBlank { item.background },
            description = parsed.description.ifBlank { item.description },
            genres = parsed.genres.ifEmpty { item.genres },
            runtime = parsed.runtime.ifBlank { item.runtime },
            rating = parsed.rating.ifBlank { item.rating }
        )
    }

    private fun catalog(url: String, type: String): List<MediaItemData> {
        val metas = publicJson(url).optJSONArray("metas")
            ?: throw IllegalStateException("The catalog is temporarily unavailable.")
        return (0 until metas.length()).mapNotNull { index ->
            metas.optJSONObject(index)?.let { parseMeta(it, type) }
        }.distinctBy { it.imdbId }
    }

    private fun parseMeta(meta: JSONObject, type: String): MediaItemData? {
        val id = meta.optString("imdb_id").takeIf(IMDB_ID::matches) ?: meta.optString("id")
        if (!IMDB_ID.matches(id)) return null
        val title = meta.optString("name").trim()
        if (title.isBlank()) return null
        val genres = meta.optJSONArray("genres")
        val videos = meta.optJSONArray("videos")
        val episodes = if (type == "series" && videos != null) {
            (0 until videos.length()).mapNotNull { index ->
                val video = videos.optJSONObject(index) ?: return@mapNotNull null
                val season = video.optInt("season", -1)
                val episode = video.optInt("episode", -1)
                if (season < 0 || episode < 1) return@mapNotNull null
                EpisodeData(
                    season = season,
                    episode = episode,
                    title = video.optString("title").ifBlank { video.optString("name") }.ifBlank { "Episode $episode" },
                    released = video.optString("released"),
                    thumbnail = artwork(video.optString("thumbnail"))
                )
            }.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
        } else emptyList()
        return MediaItemData(
            imdbId = id,
            type = type,
            title = title,
            year = meta.optString("releaseInfo").ifBlank { meta.optString("year") },
            poster = artwork(meta.optString("poster")),
            background = artwork(meta.optString("background")),
            description = meta.optString("description"),
            genres = if (genres == null) emptyList() else
                (0 until genres.length()).map { genres.optString(it) }.filter { it.isNotBlank() },
            runtime = meta.optString("runtime"),
            rating = meta.optString("imdbRating"),
            episodes = episodes
        )
    }

    private fun requireType(type: String) {
        require(type == "movie" || type == "series") { "Only movies and TV series are supported." }
    }

    private fun artwork(value: String): String = try {
        val uri = URI(value)
        if (uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) value else ""
    } catch (_: Exception) { "" }

    private companion object {
        const val BASE = "https://v3-cinemeta.strem.io"
        val IMDB_ID = Regex("tt[0-9]{5,12}")
        val MOVIE_GENRES = listOf("Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama",
            "Family", "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western")
    }
}
