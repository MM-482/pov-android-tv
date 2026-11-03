package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** Explicit anime-list edits; does not invent cross-service IDs or enable playback reporting. */
internal class CameoAnimeTrackers(private val http: CameoServiceTransport) {
    private fun headers(c: CameoConnection) = mapOf("Authorization" to "Bearer ${c.token}")
    private fun get(c: CameoConnection, url: String) = providerObject(http.request(url, headers = headers(c)))
    private fun graph(c: CameoConnection, query: String, variables: JSONObject = JSONObject()): JSONObject {
        val response = providerObject(http.request("https://graphql.anilist.co", "POST", headers(c),
            JSONObject().put("query", query).put("variables", variables).toString()))
        check((response.optJSONArray("errors")?.length() ?: 0) == 0) { "AniList rejected this request. Check the token and exact title identity." }
        return response.optJSONObject("data") ?: throw ApiException(0, "AniList returned an incomplete response.")
    }
    fun validate(draft: CameoConnection): CameoConnection {
        var c = draft
        val label: String
        when (c.kind) {
            "anilist" -> {
                require(c.token.isNotBlank()) { "Enter your AniList access token." }
                val user = graph(c, "query { Viewer { id name } }").optJSONObject("Viewer")
                    ?: throw ApiException(401, "AniList did not verify this account.")
                label = user.providerString("name"); c = c.copy(userId = user.providerString("id"))
            }
            "myanimelist" -> {
                require(c.token.isNotBlank()) { "Enter your MyAnimeList access token." }
                val user = get(c, "https://api.myanimelist.net/v2/users/@me")
                label = user.providerString("name"); c = c.copy(userId = user.providerString("id"))
            }
            "kitsu" -> {
                if (c.token.isBlank()) {
                    require(c.username.isNotBlank() && c.password.isNotBlank()) { "Enter your Kitsu username/password or access token." }
                    val form = listOf("grant_type" to "password", "username" to c.username, "password" to c.password)
                        .joinToString("&") { Net.encode(it.first) + "=" + Net.encode(it.second) }
                    val auth = providerObject(http.request("https://kitsu.app/api/oauth/token", "POST", body = form, contentType = "application/x-www-form-urlencoded"))
                    c = c.copy(token = auth.providerString("access_token"), password = "")
                }
                require(c.token.isNotBlank()) { "Kitsu did not return an access token." }
                val user = get(c, "https://kitsu.app/api/edge/users?filter[self]=true").optJSONArray("data")?.optJSONObject(0)
                    ?: throw ApiException(401, "Kitsu did not verify this account.")
                label = user.optJSONObject("attributes")?.providerString("name").orEmpty()
                c = c.copy(userId = user.providerString("id"), password = "")
            }
            else -> error("Choose an anime tracking service.")
        }
        check(label.isNotBlank() && c.userId.isNotBlank()) { "The anime service did not identify an account." }
        return c.copy(name = c.name.ifBlank { "${CameoConnections.SPECS.first { it.kind == c.kind }.label} · $label" })
    }
    fun browse(c: CameoConnection): List<CameoServiceItem> = when (c.kind) {
        "anilist" -> {
            val userId = c.userId.toIntOrNull() ?: throw IllegalStateException("Reconnect AniList to identify its user.")
            val data = graph(c, "query (\$id: Int) { MediaListCollection(userId: \$id, type: ANIME) { lists { entries { progress status media { id title { userPreferred } coverImage { large } description } } } } }", JSONObject().put("id", userId))
            (data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: JSONArray()).providerObjects().flatMap { list ->
                (list.optJSONArray("entries") ?: JSONArray()).providerObjects().mapNotNull { entry ->
                    val media = entry.optJSONObject("media") ?: return@mapNotNull null
                    val id = media.providerString("id")
                    if (id.isBlank()) return@mapNotNull null
                    CameoServiceItem("anilist:$id", c.id, media.optJSONObject("title")?.providerString("userPreferred").orEmpty(),
                        type = "series", poster = safeArt(media.optJSONObject("coverImage")?.providerString("large").orEmpty()), remoteId = id,
                        detail = "${entry.optInt("progress")} episodes · ${entry.providerString("status")}")
                }
            }.distinctBy { it.id }
        }
        "myanimelist" -> {
            val all = mutableListOf<CameoServiceItem>()
            var offset = 0
            while (offset < 10000) {
                val data = get(c, "https://api.myanimelist.net/v2/users/@me/animelist?limit=100&offset=$offset&fields=main_picture,synopsis,list_status")
                val rows = (data.optJSONArray("data") ?: JSONArray()).providerObjects()
                rows.forEach { row -> row.optJSONObject("node")?.let { node ->
                    val id = node.providerString("id")
                    if (id.isNotBlank()) all += CameoServiceItem("mal:$id", c.id, node.providerString("title"), type = "series",
                        poster = safeArt(node.optJSONObject("main_picture")?.providerString("medium").orEmpty()), remoteId = id,
                        detail = "${row.optJSONObject("list_status")?.optInt("num_episodes_watched") ?: 0} episodes")
                } }
                offset += rows.size
                if (rows.isEmpty() || data.optJSONObject("paging")?.providerString("next").isNullOrBlank()) break
            }
            all.distinctBy { it.id }
        }
        "kitsu" -> {
            require(c.userId.isNotBlank()) { "Reconnect Kitsu to identify its user." }
            val all = mutableListOf<CameoServiceItem>()
            var offset = 0
            while (offset < 10000) {
                val data = get(c, "https://kitsu.app/api/edge/library-entries?filter[userId]=${Net.encode(c.userId)}&filter[kind]=anime&include=anime&page[limit]=20&page[offset]=$offset")
                val included = (data.optJSONArray("included") ?: JSONArray()).providerObjects().filter { it.providerString("type") == "anime" }
                included.forEach { row ->
                    val attr = row.optJSONObject("attributes") ?: return@forEach
                    val id = row.providerString("id")
                    if (id.isNotBlank()) all += CameoServiceItem("kitsu:$id", c.id, attr.providerString("canonicalTitle"), type = "series",
                        poster = safeArt(attr.optJSONObject("posterImage")?.providerString("medium").orEmpty()), remoteId = id,
                        detail = attr.providerString("synopsis"))
                }
                val count = data.optJSONArray("data")?.length() ?: 0
                offset += count
                if (count == 0 || data.optJSONObject("links")?.providerString("next").isNullOrBlank()) break
            }
            all.distinctBy { it.id }
        }
        else -> error("Choose an anime tracking service.")
    }
    private fun safeArt(value: String): String = try { providerHttps(value) } catch (_: Exception) { "" }
    fun update(c: CameoConnection, item: CameoServiceItem, progress: Int, status: String, score: Int) {
        require(progress in 0..1_000_000 && score in -1..10) { "Enter a whole episode count and a score from 0 to 10." }
        val index = listOf("planned", "watching", "completed", "onHold", "dropped", "repeating").indexOf(status)
        require(index >= 0) { "Choose an available anime status." }
        val prefix = when (c.kind) { "anilist" -> "anilist:"; "myanimelist" -> "mal:"; "kitsu" -> "kitsu:"; else -> error("Choose an anime service.") }
        require(item.connectionId == c.id && item.id.startsWith(prefix)) { "Open this title from this anime service's own list." }
        val id = item.id.removePrefix(prefix).toIntOrNull()
        require(id != null && id > 0 && item.remoteId == id.toString()) { "This anime has no exact service identity." }
        when (c.kind) {
            "anilist" -> {
                val variables = JSONObject().put("mediaId", id).put("progress", progress)
                    .put("status", listOf("PLANNING", "CURRENT", "COMPLETED", "PAUSED", "DROPPED", "REPEATING")[index])
                if (score >= 0) variables.put("scoreRaw", score * 10)
                val result = graph(c, "mutation(\$mediaId:Int!,\$progress:Int,\$status:MediaListStatus,\$scoreRaw:Int){SaveMediaListEntry(mediaId:\$mediaId,progress:\$progress,status:\$status,scoreRaw:\$scoreRaw){id mediaId progress status}}", variables).optJSONObject("SaveMediaListEntry")
                check(result != null && result.optInt("mediaId") == id && result.optInt("progress") == progress) { "AniList did not confirm the requested progress." }
            }
            "myanimelist" -> {
                val fields = mutableListOf("num_watched_episodes" to progress.toString(),
                    "status" to listOf("plan_to_watch", "watching", "completed", "on_hold", "dropped", "watching")[index],
                    "is_rewatching" to (index == 5).toString())
                if (score >= 0) fields += "score" to score.toString()
                val response = providerObject(http.request("https://api.myanimelist.net/v2/anime/$id/my_list_status", "PATCH", headers(c),
                    fields.joinToString("&") { Net.encode(it.first) + "=" + Net.encode(it.second) }, "application/x-www-form-urlencoded"))
                check(response.optInt("num_episodes_watched", -1) == progress) { "MyAnimeList did not confirm the requested progress." }
            }
            else -> {
                val base = "https://kitsu.app/api/edge/library-entries"
                val previous = get(c, "$base?filter[userId]=${Net.encode(c.userId)}&filter[animeId]=$id&page[limit]=1")
                    .optJSONArray("data")?.optJSONObject(0)?.providerString("id").orEmpty()
                val attrs = JSONObject().put("progress", progress).put("status", listOf("planned", "current", "completed", "on_hold", "dropped", "current")[index]).put("reconsuming", index == 5)
                if (score >= 0) attrs.put("ratingTwenty", if (score == 0) JSONObject.NULL else score * 2)
                val data = JSONObject().put("type", "libraryEntries").put("attributes", attrs)
                if (previous.isNotBlank()) data.put("id", previous) else data.put("relationships", JSONObject()
                    .put("user", JSONObject().put("data", JSONObject().put("type", "users").put("id", c.userId)))
                    .put("anime", JSONObject().put("data", JSONObject().put("type", "anime").put("id", id.toString()))))
                val response = providerObject(http.request(base + if (previous.isBlank()) "" else "/${Net.encode(previous)}",
                    if (previous.isBlank()) "POST" else "PATCH", headers(c), JSONObject().put("data", data).toString(), "application/vnd.api+json"))
                check(response.optJSONObject("data")?.optJSONObject("attributes")?.optInt("progress", -1) == progress) { "Kitsu did not confirm the requested progress." }
            }
        }
    }
}
