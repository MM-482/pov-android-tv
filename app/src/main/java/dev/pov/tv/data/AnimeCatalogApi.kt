package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Anime discovery identity is kept separate from playback/Trakt's IMDb identity. */
data class AnimeTitle(
    val kitsuId: String,
    val title: String,
    val type: String,
    val year: String = "",
    val poster: String = "",
    val background: String = "",
    val description: String = "",
    val subtype: String = "",
    val genres: List<String> = emptyList(),
    internal val traktId: String = "",
    internal val tvdbId: String = "",
    val startDate: String = "",
    val relationRole: String = ""
) {
    /** Discovery format is separate from the movie/series identity required for playback. */
    val category: String get() = when (subtype.lowercase(Locale.ROOT)) {
        "movie" -> "movie"
        "ova", "special" -> "special"
        "tv", "ona" -> if (relationRole == "summary") "special" else "series"
        else -> if (type == "movie") "movie" else "series"
    }
    val mappingNotice: String get() = if (type == "series")
        "Only this anime season or part's verified episodes are shown, using their original show episode numbers."
    else "Playback availability depends on your source provider and TorBox."
}

data class AnimePage(val items: List<AnimeTitle>, val hasMore: Boolean, val nextOffset: Int)

/** Only catalog-declared relationships are included. Main-story special parts remain in seasons. */
data class AnimeRelated(
    val seasons: List<AnimeTitle>,
    val movies: List<AnimeTitle>,
    val specials: List<AnimeTitle>,
    val otherSeries: List<AnimeTitle> = emptyList(),
    val truncated: Boolean = false,
    val notice: String = "Catalog-linked seasons and story parts, movies, and extras are listed separately."
)

/**
 * Credential-free, paged Kitsu discovery. Call on a worker. Browse/search never use account tokens.
 * Explicit Kitsu -> Trakt/TVDB relationships are preferred for matching. The community Anime Kitsu
 * metadata endpoint can supply a same-Kitsu IMDb mapping when those relationships are absent.
 * No title-similarity matching, guessed IMDb IDs, or guessed season/absolute episode numbers.
 */
class AnimeCatalogApi internal constructor(
    private val publicJson: (String) -> JSONObject,
    private val lookupId: (String, String, String) -> MediaItemData?,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val canonicalDetails: (MediaItemData) -> MediaItemData = CatalogApi()::details
) {
    constructor(trakt: TraktApi) : this(
        { url -> Net.json(url, headers = mapOf("Accept" to "application/vnd.api+json")) },
        trakt::lookupCatalogId
    )
    constructor(trakt: TraktApi, context: android.content.Context) : this(
        PublicMetadataCache(context)::json, trakt::lookupCatalogId,
        canonicalDetails = CatalogApi(context)::details
    )

    private data class Cached(val at: Long, val page: AnimePage)
    private val pages = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > 12
    }
    private val resolved = object : LinkedHashMap<String, MediaItemData>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItemData>?): Boolean = size > 128
    }
    private var unavailableUntil = 0L
    private data class Relation(val role: String, val title: AnimeTitle)
    private data class RelatedPage(val entries: List<Relation>, val hasMore: Boolean)
    private val relationshipPages = object : LinkedHashMap<String, Pair<Long, RelatedPage>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, RelatedPage>>?): Boolean = size > 24
    }

    fun popular(type: String = "series", skip: Int = 0): AnimePage = browse(type, skip, "")

    fun search(query: String, type: String = "series", skip: Int = 0): AnimePage {
        validateType(type)
        require(skip in 0..100_000) { "The anime catalog offset is invalid." }
        val term = query.trim().take(200)
        if (term.isBlank()) return AnimePage(emptyList(), false, skip)
        return browse(type, skip, term)
    }

    private fun browse(type: String, skip: Int, query: String): AnimePage {
        validateType(type)
        require(skip in 0..100_000) { "The anime catalog offset is invalid." }
        val queryParts = linkedMapOf(
            "page[limit]" to PAGE_SIZE.toString(), "page[offset]" to skip.toString(),
            "include" to "mappings,categories"
        )
        if (query.isBlank()) queryParts["sort"] = "popularityRank" else queryParts["filter[text]"] = query
        when (type) {
            "movie" -> queryParts["filter[subtype]"] = "movie"
            "series" -> queryParts["filter[subtype]"] = "TV,ONA"
            "special" -> queryParts["filter[subtype]"] = "OVA,special"
        }
        val address = "$BASE/anime?" + queryParts.entries.joinToString("&") { (key, value) ->
            "${Net.encode(key)}=${Net.encode(value)}"
        }
        synchronized(pages) {
            pages[address]?.let { if (monotonicMillis() - it.at < CACHE_MILLIS) return it.page }
        }
        val page = parsePage(fetch(address), skip, type)
        synchronized(pages) { pages[address] = Cached(monotonicMillis(), page) }
        return page
    }

    /**
     * Returns a verified IMDb-backed title stub. Pass it through CatalogApi.details for the
     * canonical movie/show and its episode selector before building any PlaybackIdentity.
     * In particular, this method never assigns a Kitsu season's episodes to IMDb season 1.
     */
    fun resolve(title: AnimeTitle): MediaItemData {
        require(NUMERIC_ID.matches(title.kitsuId)) { "This anime has no supported catalog identifier." }
        require(title.type == "movie" || title.type == "series") { "This anime format is not supported for playback." }
        val key = "${title.type}:${title.kitsuId}"
        synchronized(resolved) { resolved[key]?.let { return it } }
        for ((site, id) in listOf("trakt" to title.traktId, "tvdb" to title.tvdbId)) {
            if (!NUMERIC_ID.matches(id)) continue
            val mapped = try { lookupId(site, id, title.type) } catch (error: ApiException) {
                // Discovery still works without a configured/available Trakt app. The separate,
                // public metadata service is allowed to supply only explicit same-Kitsu IDs.
                if (error.code == 429) throw error
                null
            }
            if (mapped != null && mapped.type == title.type && IMDB_ID.matches(mapped.imdbId)) {
                val result = mapped.copy(
                    poster = title.poster, background = title.background,
                    genres = (listOf("Anime") + title.genres + mapped.genres).distinct()
                )
                synchronized(resolved) { resolved[key] = result }
                return result
            }
        }
        val response = fetch("$MAPPING_BASE/meta/${title.type}/kitsu:${title.kitsuId}.json")
        val result = parseImdbMapping(response, title)
        synchronized(resolved) { resolved[key] = result }
        return result
    }

    /** Selected-entry episode picker. Do not replace its scoped episodes with the full parent show. */
    fun details(title: AnimeTitle): AnimeEpisodeCatalog {
        require(NUMERIC_ID.matches(title.kitsuId)) { "This anime has no supported catalog identifier." }
        require(title.type == "movie" || title.type == "series") { "This anime format is not supported for playback." }
        val payload = fetch("$MAPPING_BASE/meta/${title.type}/kitsu:${title.kitsuId}.json")
        val stub = parseImdbMapping(payload, title)
        val canonical = canonicalDetails(stub)
        check(canonical.imdbId == stub.imdbId && canonical.type == stub.type) {
            "The anime details returned a different movie or show. No episode has been selected."
        }
        return AnimeEpisodeCatalogParser.parse(payload, title, canonical)
    }

    /** Bounded walk of explicit sequel/prequel links; films and extras never become TV seasons. */
    fun related(title: AnimeTitle): AnimeRelated {
        require(NUMERIC_ID.matches(title.kitsuId)) { "This anime has no supported catalog identifier." }
        val queue = ArrayDeque<AnimeTitle>().apply { add(title) }
        val visited = mutableSetOf<String>()
        val story = linkedMapOf<String, AnimeTitle>()
        val movies = linkedMapOf<String, AnimeTitle>()
        val specials = linkedMapOf<String, AnimeTitle>()
        val otherSeries = linkedMapOf<String, AnimeTitle>()
        when (title.category) {
            "movie" -> movies[title.kitsuId] = title
            "special" -> specials[title.kitsuId] = title
            else -> story[title.kitsuId] = title
        }
        var truncated = false
        while (queue.isNotEmpty() && visited.size < 12) {
            val current = queue.removeFirst()
            if (!visited.add(current.kitsuId)) continue
            val relationships = relationshipPage(current.kitsuId)
            truncated = truncated || relationships.hasMore
            relationships.entries.forEach { entry ->
                val destination = entry.title.copy(relationRole = entry.role)
                when {
                    destination.category == "movie" -> movies[destination.kitsuId] = destination
                    entry.role in setOf("prequel", "sequel") && destination.type == "series" -> {
                        story[destination.kitsuId] = destination
                        if (destination.kitsuId !in visited && queue.none { it.kitsuId == destination.kitsuId }) queue.add(destination)
                    }
                    destination.category == "special" -> specials[destination.kitsuId] = destination
                    else -> otherSeries[destination.kitsuId] = destination
                }
            }
        }
        truncated = truncated || queue.isNotEmpty()
        val order = compareBy<AnimeTitle>({ it.startDate.ifBlank { it.year.ifBlank { "9999" } } }, { it.kitsuId.toLongOrNull() ?: Long.MAX_VALUE })
        val seasons = story.values.sortedWith(order)
        val mainIds = story.keys
        return AnimeRelated(seasons, movies.values.sortedWith(order), specials.values.filter { it.kitsuId !in mainIds }.sortedWith(order),
            otherSeries.values.filter { it.kitsuId !in mainIds }.sortedWith(order), truncated,
            if (truncated) "Catalog-linked titles are shown. This large relationship chain has been limited; additional related titles may be available in search."
            else "Catalog-linked seasons and story parts, movies, and extras are listed separately.")
    }

    private fun relationshipPage(id: String): RelatedPage {
        synchronized(relationshipPages) {
            relationshipPages[id]?.let { if (monotonicMillis() - it.first < CACHE_MILLIS) return it.second }
        }
        val payload = fetch("$BASE/anime/$id/media-relationships?include=destination&page%5Blimit%5D=20")
        val data = payload.optJSONArray("data") ?: throw ApiException(0, "Related anime titles are currently unavailable.")
        val included = payload.optJSONArray("included") ?: JSONArray()
        val resources = mutableMapOf<Pair<String, String>, JSONObject>()
        for (index in 0 until included.length()) {
            val resource = included.optJSONObject(index) ?: continue
            val key = resource.string("type") to resource.string("id")
            if (resources.put(key, resource) != null) throw ApiException(0, "Related anime metadata is ambiguous.")
        }
        val entries = (0 until minOf(data.length(), 20)).mapNotNull { index ->
            val relationship = data.optJSONObject(index) ?: return@mapNotNull null
            if (relationship.string("type") != "mediaRelationships") return@mapNotNull null
            val role = relationship.optJSONObject("attributes")?.string("role").orEmpty()
            if (role !in RELATION_ROLES) return@mapNotNull null
            val destination = relationship.optJSONObject("relationships")?.optJSONObject("destination")?.optJSONObject("data")
                ?: return@mapNotNull null
            if (destination.string("type") != "anime") return@mapNotNull null
            val record = resources["anime" to destination.string("id")] ?: return@mapNotNull null
            parseTitle(record, emptyMap())?.let { Relation(role, it) }
        }
        val next = payload.optJSONObject("links")?.opt("next")
        val page = RelatedPage(entries, next is String && next.isNotBlank())
        synchronized(relationshipPages) { relationshipPages[id] = monotonicMillis() to page }
        return page
    }

    private fun fetch(address: String): JSONObject {
        val now = monotonicMillis()
        val wait = synchronized(pages) { unavailableUntil - now }
        if (wait > 0) throw ApiException(429, "The anime catalog is busy. Please wait before trying again.",
            retryAfterSeconds = ((wait + 999) / 1000).coerceAtMost(86400).toInt())
        return try { publicJson(address) } catch (error: ApiException) {
            when (error.code) {
                429 -> {
                    val retry = error.retryAfterSeconds.coerceAtLeast(30).coerceAtMost(86400)
                    synchronized(pages) { unavailableUntil = now + retry * 1000L }
                    throw ApiException(429, "The anime catalog is busy. Please wait before trying again.",
                        retryAfterSeconds = retry)
                }
                404 -> throw ApiException(404, "This anime's metadata is currently unavailable. Try searching for the title in Movies or TV shows.")
                401, 403 -> throw ApiException(error.code, "The anime catalog could not be accessed. Please try again later.")
                in 500..599 -> throw ApiException(error.code, "The anime catalog is temporarily unavailable. Please try again later.")
                else -> throw error
            }
        }
    }

    internal fun parsePage(payload: JSONObject, skip: Int = 0, type: String = "all"): AnimePage {
        validateType(type)
        val data = payload.optJSONArray("data") ?: throw ApiException(0, "The anime catalog returned an unreadable response.")
        val included = payload.optJSONArray("included") ?: JSONArray()
        val references = mutableMapOf<Pair<String, String>, JSONObject>()
        for (i in 0 until included.length()) {
            val item = included.optJSONObject(i) ?: continue
            val identity = item.optString("type") to item.optString("id")
            // Duplicate relationship keys are ambiguous and must never cross-link identities.
            if (identity in references) throw ApiException(0, "The anime catalog returned ambiguous metadata.")
            references[identity] = item
        }
        val items = (0 until minOf(data.length(), PAGE_SIZE)).mapNotNull { index ->
            val record = data.optJSONObject(index) ?: return@mapNotNull null
            parseTitle(record, references)?.takeIf { type == "all" || it.category == type }
        }.distinctBy { it.kitsuId }
        // The count of raw records, not visible records, advances the server cursor. Explicit
        // NSFW/invalid records may be omitted without repeating a page forever.
        val consumed = data.length().coerceAtMost(PAGE_SIZE)
        val next = payload.optJSONObject("links")?.opt("next")
        val hasMore = consumed > 0 && next is String && next.isNotBlank() && skip + consumed <= 100_000
        return AnimePage(items, hasMore, skip + consumed)
    }

    private fun parseTitle(record: JSONObject, included: Map<Pair<String, String>, JSONObject>): AnimeTitle? {
        if (record.optString("type") != "anime") return null
        val kitsuId = record.string("id").takeIf(NUMERIC_ID::matches) ?: return null
        val attributes = record.optJSONObject("attributes") ?: return null
        if (attributes.optBoolean("nsfw") || attributes.string("ageRating").equals("R18", true)) return null
        val subtype = attributes.string("subtype")
        val type = when (subtype.lowercase(Locale.ROOT)) {
            "movie" -> "movie"
            "tv", "ona", "ova", "special" -> "series"
            else -> return null // Music videos and an unknown future format have no safe playback identity.
        }
        val titles = attributes.optJSONObject("titles") ?: JSONObject()
        val name = listOf(titles.string("en"), titles.string("en_us"), attributes.string("canonicalTitle"),
            titles.string("en_jp"), titles.string("ja_jp")).firstOrNull(String::isNotBlank)?.take(300) ?: return null
        val mappings = relationship(record, "mappings", included).mapNotNull { it.optJSONObject("attributes") }
        fun uniqueId(site: String): String = mappings.filter { it.string("externalSite") == site }
            .map { it.string("externalId") }.filter(NUMERIC_ID::matches).distinct().singleOrNull().orEmpty()
        val directTvdb = uniqueId("thetvdb/series").takeIf { type == "series" }.orEmpty()
        // Legacy Kitsu TVDB mappings are "show-id/season-number". Only use the show-id; the
        // displayed episode selector is authoritative and no season numbers are carried over.
        val legacyTvdb = if (type == "series") mappings.filter { it.string("externalSite") == "thetvdb" }
            .map { it.string("externalId") }.filter { it.matches(Regex("[1-9][0-9]{0,14}/[0-9]{1,5}")) }
            .map { it.substringBefore('/') }.distinct().singleOrNull().orEmpty() else ""
        val tvdb = if (directTvdb.isNotBlank() && legacyTvdb.isNotBlank() && directTvdb != legacyTvdb) ""
            else directTvdb.ifBlank { legacyTvdb }
        val genres = relationship(record, "categories", included).mapNotNull { it.optJSONObject("attributes") }
            .map { it.string("title") }.filter(String::isNotBlank).distinct().take(8)
        return AnimeTitle(
            kitsuId, name, type, attributes.string("startDate").take(4).takeIf { it.matches(Regex("[12][0-9]{3}")) }.orEmpty(),
            image(attributes.optJSONObject("posterImage")), image(attributes.optJSONObject("coverImage")),
            attributes.string("description").ifBlank { attributes.string("synopsis") }.take(12_000), subtype,
            listOf("Anime") + genres, uniqueId("trakt"), tvdb,
            startDate = attributes.string("startDate").takeIf { it.matches(Regex("[12][0-9]{3}-[0-9]{2}-[0-9]{2}")) }.orEmpty()
        )
    }

    internal fun parseImdbMapping(payload: JSONObject, title: AnimeTitle): MediaItemData {
        val meta = payload.optJSONObject("meta") ?: unmapped()
        if (meta.string("id") != "kitsu:${title.kitsuId}" || meta.string("kitsu_id") != title.kitsuId ||
            meta.string("type") != title.type) unmapped()
        if (meta.string("animeType").isNotBlank() && title.subtype.isNotBlank() &&
            !meta.string("animeType").equals(title.subtype, true)) unmapped()
        val ids = mutableSetOf<String>()
        fun collect(value: String) {
            if (value.isBlank()) return
            if (!IMDB_ID.matches(value)) unmapped()
            ids += value
        }
        collect(meta.string("imdb_id"))
        val videos = meta.optJSONArray("videos") ?: JSONArray()
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            val videoId = video.string("id")
            if (videoId != "kitsu:${title.kitsuId}" && !videoId.startsWith("kitsu:${title.kitsuId}:")) unmapped()
            collect(video.string("imdb_id"))
        }
        // An anthology or OVA collection may map to multiple IMDb titles; the user must choose.
        val id = ids.singleOrNull() ?: unmapped()
        return MediaItemData(id, title.type, title.title, title.year, title.poster, title.background,
            title.description, title.genres)
    }

    private fun relationship(record: JSONObject, name: String, included: Map<Pair<String, String>, JSONObject>): List<JSONObject> {
        val data = record.optJSONObject("relationships")?.optJSONObject(name)?.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val ref = data.optJSONObject(index) ?: return@mapNotNull null
            if (ref.string("type") != name) return@mapNotNull null
            included[ref.string("type") to ref.string("id")]
        }
    }

    private fun image(images: JSONObject?): String = listOf("large", "medium", "original", "small")
        .map { images?.string(it).orEmpty() }.firstOrNull { value ->
            try { val uri = URI(value); uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null }
            catch (_: Exception) { false }
        }.orEmpty()

    private fun JSONObject.string(key: String): String = (opt(key) as? String)?.trim().orEmpty()
    private fun validateType(type: String) = require(type in setOf("all", "movie", "series", "special")) { "Choose anime series, movies, or specials and OVAs." }
    private fun unmapped(): Nothing = throw ApiException(0,
        "This anime does not have one unambiguous IMDb match. Search for its title in Movies or TV shows and choose the matching title. No episode has been guessed.")

    private companion object {
        const val BASE = "https://kitsu.app/api/edge"
        const val MAPPING_BASE = "https://anime-kitsu.strem.fun"
        const val PAGE_SIZE = 20
        const val CACHE_MILLIS = 10 * 60 * 1000L
        val NUMERIC_ID = Regex("[1-9][0-9]{0,14}")
        val IMDB_ID = Regex("tt[0-9]{5,12}")
        val RELATION_ROLES = setOf("prequel", "sequel", "summary", "side_story", "alternative_version", "spinoff", "other", "character", "full_story", "parent_story")
    }
}
