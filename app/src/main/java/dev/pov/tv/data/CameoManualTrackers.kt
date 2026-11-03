package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Explicit per-connection operations. These tokens never enter the legacy playback reporter. */
internal class CameoManualTrackers(private val http: CameoServiceTransport) {
    private fun request(c: CameoConnection, path: String, body: JSONObject? = null): Any {
        require(path.startsWith('/') && !path.startsWith("//")) { "Choose a valid tracker operation." }
        var address = when (c.kind) {
            "trakt" -> "https://api.trakt.tv$path"
            "simkl" -> "https://api.simkl.com$path"
            "mdblist" -> "https://api.mdblist.com$path"
            else -> error("Choose a supported tracker.")
        }
        val headers = when (c.kind) {
            "trakt" -> mapOf("Authorization" to "Bearer ${c.token}", "trakt-api-key" to c.clientId, "trakt-api-version" to "2")
            "simkl" -> {
                address += (if ('?' in address) "&" else "?") + "client_id=${Net.encode(c.clientId)}&app-name=Cameo&app-version=0.4"
                mapOf("Authorization" to "Bearer ${c.token}", "simkl-api-key" to c.clientId)
            }
            else -> if (c.apiKey.isNotBlank()) {
                address += (if ('?' in address) "&" else "?") + "apikey=${Net.encode(c.apiKey)}"; emptyMap()
            } else mapOf("Authorization" to "Bearer ${c.token}")
        }
        val text = http.request(address, if (body == null) "GET" else "POST", headers, body?.toString())
        val value = try { JSONTokener(text).nextValue() } catch (_: Exception) { throw ApiException(0, "The tracker returned an unreadable response.") }
        if (value is JSONObject) {
            check((!value.has("error") || value.isNull("error")) && (value.optJSONArray("errors")?.length() ?: 0) == 0) {
                "The tracker rejected this operation. Check the selected account and its permissions."
            }
            val missing = value.optJSONObject("not_found")
            check(missing == null || missing.keys().asSequence().none { (missing.optJSONArray(it)?.length() ?: 0) > 0 }) {
                "The tracker could not match this exact title or episode. No update was confirmed."
            }
        }
        return value
    }
    fun validate(c: CameoConnection): CameoConnection {
        if (c.kind == "mdblist") require((c.apiKey.isNotBlank()) xor (c.token.isNotBlank())) { "Use one MDBList API key or OAuth access token." }
        else require(c.token.isNotBlank() && c.clientId.isNotBlank()) { "Enter the user access token and the registered application's client ID." }
        val raw = request(c, if (c.kind == "mdblist") "/user" else "/users/settings") as? JSONObject
            ?: throw ApiException(401, "The tracker did not verify this account.")
        val user = if (c.kind == "mdblist") raw else raw.optJSONObject("user") ?: raw
        val label = user.providerString("username").ifBlank { user.providerString("name") }
        check(label.isNotBlank()) { "The tracker did not identify the selected account." }
        return c.copy(name = c.name.ifBlank { "${CameoConnections.SPECS.first { it.kind == c.kind }.label} · $label" })
    }
    fun browse(c: CameoConnection, parent: String): List<CameoServiceItem> {
        if (parent.isBlank() && c.kind != "simkl") return listOf(
            CameoServiceItem("watchlist", c.id, "Watchlist", folder = true),
            CameoServiceItem("watched", c.id, if (c.kind == "trakt") "Watched movies" else "Watched", folder = true))
        val items = mutableListOf<CameoServiceItem>()
        if (c.kind == "simkl") {
            for (bucket in listOf("movies", "shows", "anime")) {
                val raw = request(c, "/sync/all-items/$bucket?extended=full&include_all_episodes=original") as? JSONObject
                    ?: throw ApiException(0, "Simkl did not return its library.")
                (raw.optJSONArray(bucket) ?: JSONArray()).providerObjects().mapNotNullTo(items) { parse(c, it, if (bucket == "movies") "movie" else "series") }
            }
        } else {
            require(parent in setOf("watchlist", "watched")) { "Choose a saved tracker list." }
            val limit = if (c.kind == "mdblist") 1000 else 100
            val route = when (c.kind) {
                "trakt" -> if (parent == "watched") "/sync/watched/movies" else "/sync/watchlist"
                else -> if (parent == "watched") "/sync/watched" else "/watchlist/items"
            }
            val seen = mutableSetOf<String>()
            for (page in 0 until (10000 / limit)) {
                val suffix = if (c.kind == "mdblist") "unified=true&limit=$limit&offset=${page * limit}" else "limit=$limit&page=${page + 1}"
                val raw = request(c, "$route?$suffix")
                val rows = (raw as? JSONArray ?: (raw as? JSONObject)?.optJSONArray("items") ?: JSONArray()).providerObjects()
                val before = seen.size
                rows.mapNotNull { parse(c, it, if (it.providerString("mediatype") == "movie") "movie" else "series") }
                    .filter { seen.add(it.id) }.forEach(items::add)
                if (rows.size < limit || before == seen.size) break
            }
        }
        return items.distinctBy { it.id }
    }
    private fun parse(c: CameoConnection, row: JSONObject, fallback: String): CameoServiceItem? {
        val media = row.optJSONObject("movie") ?: row.optJSONObject("show") ?: row.optJSONObject("anime") ?: row
        val ids = media.optJSONObject("ids") ?: JSONObject()
        val imdb = ids.providerString("imdb")
        val kitsu = ids.providerString("kitsu")
        val id = when {
            imdb.matches(Regex("tt[0-9]{5,12}")) -> imdb
            c.kind == "simkl" && kitsu.matches(Regex("[1-9][0-9]{0,11}")) -> "kitsu:$kitsu"
            else -> return null
        }
        val type = if (row.has("movie")) "movie" else fallback
        val title = media.providerString("title").ifBlank { media.providerString("name") }
        if (title.isBlank()) return null
        val identity = PlaybackIdentity(id, type, title)
        return CameoServiceItem("${c.kind}:$type:$id", c.id, title, type = type, remoteId = id,
            identity = identity, detail = media.providerString("year"))
    }
    private fun entry(c: CameoConnection, identity: PlaybackIdentity, episode: Boolean): Pair<String, JSONObject> {
        require(identity.type in setOf("movie", "series")) { "Choose a catalog movie or series." }
        val ids = JSONObject()
        if (identity.imdbId.matches(Regex("tt[0-9]{5,12}"))) ids.put("imdb", identity.imdbId)
        else {
            val match = Regex("(kitsu|anilist|mal):([1-9][0-9]{0,11})").matchEntire(identity.imdbId)
            require(c.kind == "simkl" && match != null) { "This tracker needs a verified IMDb title identity." }
            ids.put(match.groupValues[1], match.groupValues[2].toLong())
        }
        val bucket = if (identity.type == "movie") "movies" else if (ids.has("imdb")) "shows" else "anime"
        val item = JSONObject().put("ids", ids)
        if (episode && identity.type == "series") {
            require(identity.season >= 0 && identity.episode > 0) { "Select an exact episode before updating watched history." }
            item.put("seasons", JSONArray().put(JSONObject().put("number", identity.season)
                .put("episodes", JSONArray().put(JSONObject().put("number", identity.episode)))))
        }
        return bucket to item
    }
    fun watchlist(c: CameoConnection, identity: PlaybackIdentity, added: Boolean) {
        val (bucket, item) = entry(c, identity, false)
        val path = when (c.kind) {
            "trakt" -> if (added) "/sync/watchlist" else "/sync/watchlist/remove"
            "mdblist" -> "/watchlist/items/${if (added) "add" else "remove"}"
            else -> {
                require(added) { "Simkl removes its watchlist and watched record together. Use an explicit watched-history action to remove its shared record." }
                item.put("to", "plantowatch"); "/sync/add-to-list"
            }
        }
        confirm(request(c, path, JSONObject().put(bucket, JSONArray().put(item))))
    }
    fun watched(c: CameoConnection, identity: PlaybackIdentity, watched: Boolean) {
        val (bucket, item) = entry(c, identity, true)
        val base = if (c.kind == "mdblist") "/sync/watched" else "/sync/history"
        confirm(request(c, base + if (watched) "" else "/remove", JSONObject().put(bucket, JSONArray().put(item))))
    }
    private fun confirm(value: Any) {
        val raw = value as? JSONObject ?: throw ApiException(0, "The tracker did not confirm this update.")
        check(listOf("added", "existing", "deleted", "removed", "updated").any(raw::has)) { "The tracker did not confirm this update. Refresh its list before retrying." }
    }
}
