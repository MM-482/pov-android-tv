package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.util.Locale
import java.security.MessageDigest

data class MdbList(val id: String, val name: String, val kind: String)

/** API-key MDBList integration. All account requests are pinned HTTPS requests without redirects. */
class MdbListApi internal constructor(
    private val store: TrackerCredentials,
    private val transport: (String, String, JSONObject?) -> NetReply = { url, method, body -> Net.reply(url, method, body = body) },
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    constructor(store: SecureStore) : this(object : TrackerCredentials {
        override fun get(key: String, default: String) = store.get(key, default)
        override fun putAll(values: Map<String, String>) = store.putAll(values)
    })
    private data class Account(val key: String, val revision: String)
    private class AccountChanged : IllegalStateException("The MDBList account changed. Choose this action again.")
    private data class Page(val items: List<JSONObject>, val pagination: JSONObject?, val next: String, val hasMore: Boolean?)

    fun configured(): Boolean = store.get("mdblist.token").isNotBlank()
    fun connected(): Boolean = configured()
    fun accountRevision(): String = store.get("mdblist.account_revision")

    /** Verify without replacing the current key, then persist key and revision together. */
    fun connectApiKey(value: String): Boolean {
        val key = value.trim()
        require(key.isNotBlank() && key.length <= 512 && key.all { it.code in 33..126 }) { "Enter a valid MDBList API key." }
        val before = snapshot(false)
        val result = request(Account(key, "candidate"), "/user", guarded = false)
        val user = objectValue(result.body)
        if (user.optLong("user_id", -1) < 0 || user.text("username").isBlank()) throw ApiException(401, "MDBList did not verify this API key.")
        synchronized(authLock) {
            if (snapshot(false) != before) throw AccountChanged()
            store.putAll(mapOf("mdblist.token" to key, "mdblist.account_revision" to UUID.randomUUID().toString()))
        }
        return true
    }
    fun disconnect() = synchronized(authLock) {
        store.putAll(mapOf("mdblist.token" to "", "mdblist.account_revision" to UUID.randomUUID().toString()))
        Unit
    }

    fun lists(): List<MdbList> = withAccount { account ->
        listOf("own" to "/lists/user", "liked" to "/lists/liked", "external" to "/external/lists/user").flatMap { (kind, path) ->
            readPages(account, path, if (kind == "liked") Mode.OFFSET else Mode.NONE).mapNotNull { item ->
                val id = item.text("id")
                val name = item.text("name")
                if (id.isBlank() || name.isBlank()) null else MdbList(id, name, kind)
            }
        }.distinctBy { it.kind to it.id }
    }
    fun listItems(id: String, kind: String): List<MediaItemData> = withAccount { account ->
        require(id.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Invalid MDBList list identifier." }
        require(kind in setOf("own", "liked", "external")) { "Invalid MDBList list type." }
        val path = if (kind == "external") "/external/lists/${Net.encode(id)}/items" else "/lists/${Net.encode(id)}/items"
        mediaPages(account, "$path?unified=true&append_to_response=poster,description,genres,ratings")
    }
    fun watchlist(type: String): List<MediaItemData> = withAccount { account ->
        val kind = when (type) { "movie", "movies" -> "movie"; "series", "show", "shows" -> "show"; else -> throw IllegalArgumentException("Invalid MDBList media type.") }
        mediaPages(account, "/watchlist/items?unified=true&mediatype=$kind&append_to_response=poster,description,genres,ratings")
    }
    private fun mediaPages(account: Account, path: String): List<MediaItemData> =
        readPages(account, path, Mode.CURSOR).mapNotNull(::parseMedia).distinctBy { it.type to it.imdbId }

    fun setWatchlist(identity: PlaybackIdentity, added: Boolean) = synchronized(writeLock) {
        withAccount { account ->
            validate(identity, episode = false)
            val media = JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId))
            val kind = if (movie(identity)) "movies" else "shows"
            val result = objectValue(request(account, "/watchlist/items/${if (added) "add" else "remove"}", "POST",
                JSONObject().put(kind, JSONArray().put(media))).body)
            confirmWrite(result, if (added) listOf("added", "existing") else listOf("removed", "deleted", "existing", "added"), kind)
        }
        Unit
    }
    fun markWatched(identity: PlaybackIdentity) = synchronized(writeLock) {
        withAccount { account ->
            validate(identity, episode = true)
            val item = JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId))
            val kind = if (movie(identity)) "movies" else "episodes"
            val body = if (movie(identity)) JSONObject().put("movies", JSONArray().put(item)) else {
                item.put("seasons", JSONArray().put(JSONObject().put("number", identity.season)
                    .put("episodes", JSONArray().put(JSONObject().put("number", identity.episode)))))
                JSONObject().put("shows", JSONArray().put(item))
            }
            confirmWrite(objectValue(request(account, "/sync/watched", "POST", body).body), listOf("updated", "added", "existing"), kind)
        }
        Unit
    }
    /** The immutable snapshot always sends to the account that queued this event. */
    fun progressForAccount(identity: PlaybackIdentity, percent: Double, action: String, revision: String): Boolean = synchronized(writeLock) {
        if (revision.isBlank() || !connected() || accountRevision() != revision) return@synchronized false
        validate(identity, episode = true)
        require(percent.isFinite()) { "Invalid playback progress." }
        require(action in setOf("start", "pause", "stop")) { "Invalid playback action." }
        val account = snapshot()
        if (account.revision != revision) return@synchronized false
        try {
            val body = progressBody(identity, percent)
            val result = objectValue(request(account, "/scrobble/$action", "POST", body).body)
            if (result.text("action").isBlank() || !result.has("progress")) throw ApiException(0, "MDBList did not confirm the playback update.")
            ensureCurrent(account)
            true
        } catch (_: AccountChanged) { false }
    }
    internal fun progressBody(identity: PlaybackIdentity, percent: Double): JSONObject {
        val target = JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId))
        if (!movie(identity)) target.put("season", JSONObject().put("number", identity.season)
            .put("episode", JSONObject().put("number", identity.episode)))
        return JSONObject().put(if (movie(identity)) "movie" else "show", target)
            .put("progress", percent.coerceIn(0.0, 100.0)).put("app_name", "POV Standalone")
    }

    private enum class Mode { NONE, CURSOR, OFFSET }
    private fun readPages(account: Account, path: String, mode: Mode): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var cursor = ""
        var offset = 0
        val limit = if (mode == Mode.OFFSET) 100 else 1000
        repeat(1000) {
            val query = if (mode == Mode.NONE && cursor.isBlank()) "" else "limit=$limit" +
                if (cursor.isNotBlank()) "&cursor=${Net.encode(cursor)}" else if (mode == Mode.OFFSET) "&offset=$offset" else ""
            val url = path + if (query.isBlank()) "" else (if ('?' in path) "&" else "?") + query
            val page = parsePage(request(account, url))
            result.addAll(page.items)
            if (result.size > 250_000) throw ApiException(0, "This MDBList library is too large to load at once.")
            if (page.hasMore == false || (page.hasMore == null && page.next.isBlank())) return result
            if (page.items.isEmpty()) throw ApiException(0, "MDBList pagination stopped unexpectedly. Refresh this list.")
            if (mode == Mode.OFFSET) offset += page.items.size else {
                if (page.next.isBlank() || !seen.add(page.next)) throw ApiException(0, "MDBList returned incomplete pagination. Refresh this list.")
                cursor = page.next
            }
        }
        throw ApiException(0, "MDBList returned too many pages. Narrow this list and try again.")
    }
    private fun parsePage(reply: NetReply): Page {
        val value = try { JSONTokener(reply.body).nextValue() } catch (_: Exception) { malformed() }
        val pagination = (value as? JSONObject)?.optJSONObject("pagination")
        val headerMore = reply.header("x-has-more")?.lowercase(Locale.ROOT)?.let { if (it == "true") true else if (it == "false") false else null }
        val more = if (pagination?.has("has_more") == true) pagination.optBoolean("has_more") else headerMore
        val next = pagination?.text("next_cursor").orEmpty().ifBlank { (value as? JSONObject)?.text("next_cursor").orEmpty() }
            .ifBlank { reply.header("x-next-cursor").orEmpty() }
        val rows = when (value) {
            is JSONArray -> value.providerObjects()
            is JSONObject -> {
                val unified = value.optJSONArray("items") ?: value.optJSONArray("lists")
                if (unified == null && !value.has("movies") && !value.has("shows")) malformed()
                if (unified != null) unified.providerObjects() else listOf("movies" to "movie", "shows" to "show").flatMap { (key, type) ->
                    (value.optJSONArray(key) ?: JSONArray()).providerObjects().map { JSONObject(it.toString()).put("_pov_type", type) }
                }
            }
            else -> malformed()
        }
        return Page(rows, pagination, next, more)
    }

    private fun snapshot(required: Boolean = true): Account = synchronized(authLock) {
        val account = Account(store.get("mdblist.token"), store.get("mdblist.account_revision"))
        if (required && account.key.isBlank()) throw ApiException(401, "Connect your MDBList API key in Settings first.")
        account
    }
    private fun ensureCurrent(account: Account) { if (snapshot(false) != account) throw AccountChanged() }
    private fun <T> withAccount(work: (Account) -> T): T {
        val account = snapshot()
        val value = work(account)
        ensureCurrent(account)
        return value
    }
    private fun request(account: Account, path: String, method: String = "GET", body: JSONObject? = null, guarded: Boolean = true): NetReply {
        require(path.startsWith('/') && !path.startsWith("//") && "apikey=" !in path && '#' !in path) { "Invalid MDBList API path." }
        if (guarded) ensureCurrent(account)
        val rateKey = MessageDigest.getInstance("SHA-256").digest(account.key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val remaining = synchronized(rateLock) { (rateUntil[rateKey] ?: 0) - epochSeconds() }
        if (remaining > 0) throw ApiException(429, "MDBList is rate limited. Please wait before trying again.", retryAfterSeconds = remaining.coerceAtMost(86400).toInt())
        val reply = try {
            transport(BASE + path + (if ('?' in path) '&' else '?') + "apikey=${Net.encode(account.key)}", method, body)
        } catch (error: ApiException) {
            if (error.code == 429) synchronized(rateLock) { rateUntil[rateKey] = maxOf(rateUntil[rateKey] ?: 0, epochSeconds() + maxOf(5, error.retryAfterSeconds)) }
            throw error
        }
        if (guarded) ensureCurrent(account)
        if (reply.header("x-ratelimit-remaining")?.toLongOrNull() == 0L) {
            reply.header("x-ratelimit-reset")?.toLongOrNull()?.let { reset -> synchronized(rateLock) { rateUntil[rateKey] = maxOf(rateUntil[rateKey] ?: 0, reset) } }
        }
        val error = try { (JSONTokener(reply.body).nextValue() as? JSONObject)?.let { it.has("error") || it.text("status") == "error" } == true } catch (_: Exception) { false }
        if (error) throw ApiException(0, "MDBList could not complete this request. Check your API key and account limits.")
        return reply
    }

    private fun confirmWrite(value: JSONObject, fields: List<String>, kind: String) {
        fun count(item: Any?): Long = when (item) {
            is Number -> item.toLong()
            is JSONArray -> item.length().toLong()
            is JSONObject -> item.optLong(kind, 0)
            else -> 0
        }
        fun failures(item: Any?): Long = when (item) {
            is Number -> item.toLong()
            is JSONArray -> item.length().toLong()
            is JSONObject -> item.keys().asSequence().sumOf { failures(item.opt(it)) }
            else -> 0
        }
        if (failures(value.opt("not_found")) > 0 || failures(value.opt("errors")) > 0) throw ApiException(404, "MDBList could not match this title. The update was not confirmed.")
        if (fields.none { value.has(it) } || fields.sumOf { count(value.opt(it)) } <= 0) {
            throw ApiException(0, "MDBList did not confirm this update. Refresh before trying again.")
        }
    }
    internal fun parseMedia(entry: JSONObject): MediaItemData? {
        val movie = entry.optJSONObject("movie")
        val show = entry.optJSONObject("show")
        val media = movie ?: show ?: entry
        val id = media.optJSONObject("ids")?.text("imdb").orEmpty().ifBlank { media.text("imdb_id") }.ifBlank { media.text("imdb") }
        if (!id.matches(IMDB_ID)) return null
        val type = if (movie != null) "movie" else if (show != null) "series" else when (entry.text("mediatype").ifBlank { entry.text("type") }.ifBlank { entry.text("_pov_type") }) {
            "movie", "movies" -> "movie"; "show", "shows", "series" -> "series"; else -> return null
        }
        val title = media.text("title").ifBlank { media.text("name") }
        if (title.isBlank()) return null
        fun secure(value: String) = try { providerHttps(value) } catch (_: Exception) { "" }
        val genres = media.optJSONArray("genres") ?: JSONArray()
        return MediaItemData(id, type, title, media.text("release_year").ifBlank { media.text("year") },
            secure(media.text("poster")), secure(media.text("backdrop")), media.text("description").ifBlank { media.text("overview") },
            (0 until genres.length()).mapNotNull { index -> genres.optString(index).takeIf { it.isNotBlank() } })
    }
    private fun movie(identity: PlaybackIdentity) = identity.type in setOf("movie", "movies")
    private fun validate(identity: PlaybackIdentity, episode: Boolean) {
        require(identity.imdbId.matches(IMDB_ID)) { "A matched IMDb title is required for MDBList tracking." }
        require(movie(identity) || identity.type in setOf("series", "show", "shows", "episode")) { "Invalid MDBList media type." }
        if (episode && !movie(identity)) require(identity.season >= 0 && identity.episode > 0) { "Choose a specific episode before updating MDBList." }
    }
    private fun objectValue(raw: String): JSONObject = try { JSONObject(raw) } catch (_: Exception) { malformed() }
    private fun malformed(): Nothing = throw ApiException(0, "MDBList returned an unreadable response.")
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it == "null" }.orEmpty()
    private fun NetReply.header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    companion object {
        private const val BASE = "https://api.mdblist.com"
        private val IMDB_ID = Regex("tt[0-9]{5,12}")
        private val authLock = Any()
        private val writeLock = Any()
        private val rateLock = Any()
        private val rateUntil = mutableMapOf<String, Long>()
    }
}
