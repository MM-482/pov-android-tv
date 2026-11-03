package dev.pov.tv.data

import org.json.JSONObject
import java.net.URI

/** Public Cinemeta metadata, with no embedded third-party account credentials. Call on a worker. */
class CatalogApi internal constructor(private val publicJson: (String) -> JSONObject) {
    constructor() : this(Net::publicJson)
    constructor(context: android.content.Context) : this(PublicMetadataCache(context)::json)
    fun popular(type: String, skip: Int = 0): List<MediaItemData> {
        requireType(type)
        require(skip >= 0) { "The catalog offset cannot be negative." }
        return catalog("$BASE/catalog/$type/top/skip=$skip.json", type)
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
    }
}
