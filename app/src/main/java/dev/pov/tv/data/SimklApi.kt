package dev.pov.tv.data

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

data class SimklList(val id: String, val name: String)
data class SimklPlayback(val identity: PlaybackIdentity, val progress: Double, val pausedAt: String, val id: Long)

/** AUTH V2 device flow. The client ID must be a separately registered public TV/device app. */
class SimklApi internal constructor(
    private val store: TrackerCredentials,
    private val transport: (String, String, Map<String, String>, JSONObject?) -> String = Net::raw,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    constructor(store: SecureStore) : this(object : TrackerCredentials {
        override fun get(key: String, default: String) = store.get(key, default)
        override fun putAll(values: Map<String, String>) = store.putAll(values)
    })

    private data class Pending(val started: Long, val client: String, val generation: Long, val session: Long,
        val expiry: Int, var interval: Int, var lastPoll: Long)
    private data class Account(val client: String, val revision: String, val generation: Long, val access: String,
        val refresh: String, val expires: Long)
    private val pending = ConcurrentHashMap<String, Pending>()
    private var sessionGeneration = 0L
    private val rateLock = Any()
    private var rateUntil = 0L

    fun configured() = validClientId(store.get("simkl.client_id").trim())
    fun connected() = configured() && store.get("simkl.access_token").isNotBlank()
    fun accountRevision(): String = store.get("simkl.account_revision")

    /** Clears local access immediately. A late auth/refresh response cannot restore it. */
    fun disconnect() {
        val revoke = synchronized(authLock) {
            val client = store.get("simkl.client_id")
            val token = store.get("simkl.refresh_token").ifBlank { store.get("simkl.access_token") }
            generation++; sessionGeneration++; pending.clear()
            store.putAll(emptyAccount())
            client to token
        }
        // Best effort revoke ends this grant only. Local disconnect remains effective if offline.
        if (validClientId(revoke.first) && revoke.second.isNotBlank()) Thread({ runCatching {
            oauth("/oauth2/revoke", JSONObject().put("client_id", revoke.first).put("token", revoke.second), revoke.first)
        } }, "pov-simkl-revoke").apply { isDaemon = true; start() }
    }

    fun setClientId(value: String): Boolean = synchronized(authLock) {
        val id = value.trim()
        require(validClientId(id)) { "Enter the Client ID from your registered Simkl AUTH V2 TV/device application." }
        val changed = store.get("simkl.client_id").trim() != id
        if (changed) {
            generation++; sessionGeneration++; pending.clear()
            store.putAll(emptyAccount() + ("simkl.client_id" to id))
        }
        changed
    }

    @Synchronized fun startDeviceAuth(): DeviceAuth {
        val state = synchronized(authLock) { pending.clear(); Triple(clientId(), generation, ++sessionGeneration) }
        val started = elapsedRealtime()
        val value = try { oauth("/oauth2/device", JSONObject().put("client_id", state.first)
            .put("scope", "media:read media:write"), state.first) } catch (error: ApiException) { throw authError(error) }
        val auth = parseDeviceAuth(value)
        synchronized(authLock) {
            if (state.second != generation || state.third != sessionGeneration || clientId() != state.first) ended()
            pending[auth.deviceCode] = Pending(started, state.first, state.second, state.third,
                auth.expiresSeconds, auth.intervalSeconds, started)
        }
        return auth
    }

    fun cancelDeviceAuth(auth: DeviceAuth? = null) = synchronized(authLock) {
        if (auth == null) { pending.clear(); sessionGeneration++ } else pending.remove(auth.deviceCode)
        Unit
    }

    @Synchronized fun pollDeviceAuth(auth: DeviceAuth): Boolean {
        val state = pending[auth.deviceCode] ?: ended()
        val now = elapsedRealtime()
        synchronized(authLock) { if (state.generation != generation || state.session != sessionGeneration || clientId() != state.client) ended() }
        if (now - state.started >= state.expiry * 1000L) {
            pending.remove(auth.deviceCode); throw ApiException(410, "The Simkl linking code expired. Start linking again.")
        }
        if (now - state.lastPoll < state.interval * 1000L) return false
        state.lastPoll = now
        val value = try {
            oauth("/oauth2/token", JSONObject().put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .put("client_id", state.client).put("device_code", auth.deviceCode), state.client)
        } catch (error: ApiException) {
            when {
                error.code == 400 && error.errorKey == "authorization_pending" -> return false
                error.errorKey == "slow_down" || error.code == 429 -> {
                    state.interval = maxOf(state.interval + 5, error.retryAfterSeconds); return false
                }
                error.errorKey == "expired_token" -> { pending.remove(auth.deviceCode); throw ApiException(410, "The Simkl linking code expired. Start linking again.") }
                else -> { pending.remove(auth.deviceCode); throw authError(error) }
            }
        }
        synchronized(authLock) {
            if (pending[auth.deviceCode] !== state || generation != state.generation || state.session != sessionGeneration || clientId() != state.client) ended()
            saveTokens(value, UUID.randomUUID().toString())
            generation++; pending.clear()
        }
        return true
    }

    /** Activities-gated, persisted incremental sync. No full-library polling on a timer. */
    fun watchlist(type: String): List<MediaItemData> = library(type)
        .filter { it.optString("status") == "plantowatch" }.mapNotNull(::parseMedia)

    fun libraryItems(type: String): List<MediaItemData> = library(type).mapNotNull(::parseMedia)

    private fun library(type: String): List<JSONObject> = synchronized(libraryLock) {
        val kind = when (type) { "movie", "movies" -> "movies"; "series", "show", "shows" -> "shows"; else -> throw IllegalArgumentException("Choose movies or TV series.") }
        val revision = accountRevision()
        val cacheKey = "simkl.library.$kind"
        val cache = runCatching { JSONObject(store.get(cacheKey)) }.getOrNull()?.takeIf { it.optString("revision") == revision }
        val before = cache?.optJSONObject("snapshot")
        val activities = objectRequest("/sync/activities", expectedRevision = revision)
        val current = objects(cache?.optJSONArray("items"))
        if (before != null && stamp(before, "all") == stamp(activities, "all")) return@synchronized current
        val domain = if (kind == "movies") "movies" else "tv_shows"
        val oldGroup = before?.optJSONObject(domain)
        val newGroup = activities.optJSONObject(domain)
        if (newGroup == null) malformed()
        val buckets = listOf("plantowatch", "watching", "completed", "hold", "dropped")
        val membershipChanged = oldGroup == null || buckets.any { stamp(oldGroup, it) != stamp(newGroup, it) }
        val removed = oldGroup != null && stamp(oldGroup, "removed_from_list") != stamp(newGroup, "removed_from_list")
        val merged = current.associateBy(::libraryId).toMutableMap()
        if (cache == null || membershipChanged) {
            val date = before?.let { stamp(it, "all") }.orEmpty()
            val delta = objectRequest("/sync/all-items/$kind/all?extended=full" + if (cache != null && date.isNotBlank()) "&date_from=${Net.encode(date)}" else "", expectedRevision = revision)
            if (cache == null) merged.clear()
            objects(delta.optJSONArray(kind)).forEach { entry -> merged[libraryId(entry)] = entry }
        }
        if (removed) {
            val ids = objectRequest("/sync/all-items/$kind/all?extended=simkl_ids_only", expectedRevision = revision)
            val present = objects(ids.optJSONArray(kind)).map(::libraryId).toSet()
            merged.keys.retainAll(present)
        }
        val result = merged.values.toList()
        synchronized(authLock) {
            checkRevision(revision)
            store.putAll(mapOf(cacheKey to JSONObject().put("revision", revision).put("snapshot", activities).put("items", JSONArray(result)).toString()))
        }
        result
    }

    /** Custom lists are read only and require Simkl PRO/VIP. Triggered by an explicit library action. */
    fun customLists(): List<SimklList> {
        val revision = accountRevision()
        val profile = objectRequest("/users/settings", expectedRevision = revision)
        val user = profile.optJSONObject("account")?.optLong("id", -1)?.takeIf { it > 0 } ?: malformed()
        val result = mutableListOf<SimklList>()
        for (page in 1..20) {
            val value = objectRequest("/lists/user/$user?limit=500&page=$page", expectedRevision = revision)
            objects(value.optJSONArray("lists")).forEach { row ->
                val id = row.optLong("id", -1)
                if (id > 0) result += SimklList(id.toString(), row.optString("name").ifBlank { "Simkl list" })
            }
            if (page >= (value.optJSONObject("pagination")?.optInt("total_pages", 1) ?: 1)) return result.distinctBy { it.id }
        }
        throw ApiException(0, "This Simkl list collection is too large to load at once.")
    }

    fun listItems(id: String): List<MediaItemData> {
        require(id.matches(Regex("[1-9][0-9]{0,15}"))) { "Invalid Simkl list." }
        val result = mutableListOf<MediaItemData>()
        val revision = accountRevision()
        for (page in 1..20) {
            val value = objectRequest("/lists/$id?limit=500&page=$page&extended=full", expectedRevision = revision)
            objects(value.optJSONArray("items")).mapNotNullTo(result) { row -> parseMedia(row) }
            if (page >= (value.optJSONObject("pagination")?.optInt("total_pages", 1) ?: 1)) return result.distinctBy { it.type to it.imdbId }
        }
        throw ApiException(0, "This Simkl list is too large to load at once.")
    }

    fun playback(): List<SimklPlayback> = paused()
    fun paused(): List<SimklPlayback> {
        val rows = arrayRequest("/sync/playback")
        return objects(rows).mapNotNull { row ->
            val media = row.optJSONObject("movie") ?: row.optJSONObject("show") ?: row.optJSONObject("anime") ?: return@mapNotNull null
            val id = imdb(media) ?: return@mapNotNull null
            val movie = row.has("movie")
            val episode = row.optJSONObject("episode")
            // Prefer original TVDB numbering where Simkl maps an anime's canonical episode numbers.
            val season = episode?.optInt("tvdb_season", episode.optInt("season", -1)) ?: -1
            val number = episode?.optInt("tvdb_number", episode.optInt("number", episode.optInt("episode", -1))) ?: -1
            if (!movie && (season < 0 || number <= 0)) return@mapNotNull null
            val progress = row.optDouble("progress", Double.NaN)
            if (!progress.isFinite() || progress !in 0.0..100.0) return@mapNotNull null
            SimklPlayback(PlaybackIdentity(id, if (movie) "movie" else "series", media.optString("title"), if (movie) 0 else season,
                if (movie) 0 else number), progress, row.optString("paused_at").ifBlank { row.optString("watched_at") }, row.optLong("id"))
        }
    }

    fun progressForAccount(identity: PlaybackIdentity, percent: Double, action: String, revision: String): Boolean = synchronized(writeLock) {
        if (revision.isBlank() || !connected() || accountRevision() != revision) false
        else { sendProgress(identity, percent, action, revision); true }
    }
    fun progress(identity: PlaybackIdentity, percent: Double, action: String) = synchronized(writeLock) {
        sendProgress(identity, percent, action, accountRevision())
    }
    fun scrobble(action: String, identity: PlaybackIdentity, progress: Double, expectedAccountRevision: String = accountRevision()) = synchronized(writeLock) {
        sendProgress(identity, progress, action, expectedAccountRevision)
    }
    private fun sendProgress(identity: PlaybackIdentity, percent: Double, action: String, revision: String) {
        require(action in setOf("start", "pause", "stop")) { "Invalid Simkl playback action." }
        require(percent.isFinite()) { "Invalid playback progress." }
        validateIdentity(identity)
        val now = elapsedRealtime()
        val scrobbleUntil = writeWindows[revision] ?: 0L
        if (now < scrobbleUntil) throw ApiException(429, "Simkl playback updates must wait for its 20-second write window.", "RATE_LIMIT", ((scrobbleUntil - now + 999) / 1000).toInt())
        val body = JSONObject().put("progress", round(percent.coerceIn(0.0, 100.0) * 100) / 100)
        body.put(if (identity.type == "movie") "movie" else "show", mediaObject(identity))
        if (identity.type != "movie") body.put("episode", JSONObject().put("season", identity.season).put("number", identity.episode))
        try {
            objectRequest("/scrobble/$action", "POST", body, revision, write = true)
            writeWindows[revision] = elapsedRealtime() + 20_000
        } catch (error: ApiException) {
            if (error.code == 400 && error.errorKey == "RATE_LIMIT") {
                writeWindows[revision] = elapsedRealtime() + maxOf(20, error.retryAfterSeconds) * 1000L
                throw ApiException(429, "Simkl is still processing a playback update. Retry after its write window.", "RATE_LIMIT", maxOf(20, error.retryAfterSeconds))
            }
            if (error.code != 409 || action != "stop") throw error
        }
    }

    fun markWatched(identity: PlaybackIdentity, expectedAccountRevision: String = accountRevision()) = synchronized(writeLock) {
        validateIdentity(identity)
        val media = mediaObject(identity)
        val body = if (identity.type == "movie") JSONObject().put("movies", JSONArray().put(media)) else {
            media.put("seasons", JSONArray().put(JSONObject().put("number", identity.season)
                .put("episodes", JSONArray().put(JSONObject().put("number", identity.episode)))))
            JSONObject().put("shows", JSONArray().put(media))
        }
        val value = objectRequest("/sync/history", "POST", body, expectedAccountRevision, write = true)
        val missing = value.optJSONObject("not_found")
        if (missing != null && listOf("movies", "shows", "episodes").any { (missing.optJSONArray(it)?.length() ?: 0) > 0 })
            throw ApiException(404, "Simkl could not match this title. Its watched status was not changed.")
        val added = value.optJSONObject("added")
        if (added == null || listOf("movies", "shows", "episodes").none { added.optInt(it, 0) > 0 || (added.optJSONArray(it)?.length() ?: 0) > 0 })
            throw ApiException(0, "Simkl did not confirm the watched-status update. Refresh before trying again.")
    }

    fun addToWatchlist(item: MediaItemData) {
        val identity = PlaybackIdentity(item.imdbId, item.type, item.title)
        require(item.imdbId.matches(IMDB_ID) && item.type in setOf("movie", "series")) { "A matched movie or series is required." }
        val media = mediaObject(identity).put("to", "plantowatch")
        val value = objectRequest("/sync/add-to-list", "POST", JSONObject().put(if (item.type == "movie") "movies" else "shows", JSONArray().put(media)), write = true)
        val missing = value.optJSONObject("not_found")
        if (missing != null && listOf("movies", "shows").any { (missing.optJSONArray(it)?.length() ?: 0) > 0 })
            throw ApiException(404, "Simkl could not match this title for its watchlist.")
        if (value.optJSONObject("added")?.let { added -> listOf("movies", "shows").any { (added.optJSONArray(it)?.length() ?: 0) > 0 } } != true)
            throw ApiException(0, "Simkl did not confirm the watchlist update. Refresh before trying again.")
    }

    private fun objectRequest(path: String, method: String = "GET", body: JSONObject? = null,
        expectedRevision: String = accountRevision(), write: Boolean = false): JSONObject {
        val text = request(path, method, body, expectedRevision, write)
        val value = try { JSONObject(text) } catch (_: Exception) { malformed() }
        if (value.has("error")) {
            val key = value.optString("error")
            if (key == "premium_only") throw ApiException(403, "Simkl custom lists require a Simkl PRO or VIP account.", key)
            throw ApiException(value.optInt("code", 0), "Simkl could not complete this request.", key.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }.orEmpty())
        }
        return value
    }
    private fun arrayRequest(path: String): JSONArray = try { JSONArray(request(path, "GET", null, accountRevision(), false)) }
        catch (error: ApiException) { throw error } catch (_: Exception) { malformed() }

    private fun request(path: String, method: String, body: JSONObject?, revision: String, write: Boolean): String {
        checkRevision(revision)
        if (write && "media:write" !in store.get("simkl.scope").split(' ')) throw ApiException(403, "Reconnect Simkl and approve media:write permission to update playback and history.")
        val access = accessToken(revision)
        fun call(token: String): String {
            checkRevision(revision)
            val id = clientId()
            return limited { transport(address(path, id), method, mapOf("User-Agent" to USER_AGENT, "Authorization" to "Bearer $token"), body) }
        }
        try { return call(access) } catch (error: ApiException) {
            if (error.code != 401 || error.errorKey == "user_token_required") throw error
            return call(accessToken(revision, access))
        }
    }

    private fun accessToken(revision: String, rejectedAccess: String? = null): String = synchronized(tokenLock) {
        val state = synchronized(authLock) {
            checkRevision(revision)
            Account(clientId(), revision, generation, store.get("simkl.access_token"), store.get("simkl.refresh_token"), store.get("simkl.expires_at").toLongOrNull() ?: 0)
        }
        if (state.access.isBlank()) throw ApiException(401, "Connect your Simkl account first.")
        if ((rejectedAccess == null && state.expires > epochSeconds() + 86400) ||
            (rejectedAccess != null && rejectedAccess != state.access)) return@synchronized state.access
        if (state.refresh.isBlank() || (store.get("simkl.refresh_expires_at").toLongOrNull() ?: 0) <= epochSeconds())
            throw ApiException(401, "Your Simkl sign-in expired. Connect Simkl again.")
        val value = try { oauth("/oauth2/token", JSONObject().put("grant_type", "refresh_token").put("client_id", state.client).put("refresh_token", state.refresh), state.client) }
        catch (error: ApiException) {
            if (error.errorKey == "invalid_grant") throw ApiException(401, "Your Simkl sign-in was revoked or expired. Connect Simkl again.", "invalid_grant")
            throw authError(error)
        }
        synchronized(authLock) {
            if (generation != state.generation || accountRevision() != state.revision || clientId() != state.client) ended()
            saveTokens(value, state.revision)
        }
        store.get("simkl.access_token")
    }

    private fun saveTokens(value: JSONObject, revision: String) {
        val access = tokenValue(value, "access_token")
        val refresh = tokenValue(value, "refresh_token")
        val expiry = value.optLong("expires_in", 0)
        val scope = value.optString("scope")
        if (expiry !in 1..31_536_000 || "media:read" !in scope.split(' ') || !value.optString("token_type").equals("Bearer", true)) malformed()
        store.putAll(mapOf("simkl.access_token" to access, "simkl.refresh_token" to refresh,
            "simkl.expires_at" to (epochSeconds() + expiry).toString(), "simkl.refresh_expires_at" to (epochSeconds() + 180 * 86400L).toString(),
            "simkl.scope" to scope, "simkl.account_revision" to revision))
    }
    private fun oauth(path: String, body: JSONObject, client: String): JSONObject {
        val text = limited { transport(address(path, client), "POST", mapOf("User-Agent" to USER_AGENT), body) }
        return try { JSONObject(text) } catch (_: Exception) { malformed() }
    }
    private fun address(path: String, client: String): String {
        check(path.startsWith('/') && !path.startsWith("//"))
        return "$BASE$path${if ('?' in path) '&' else '?'}client_id=${Net.encode(client)}&app-name=pov-standalone&app-version=0.3"
    }
    private fun <T> limited(call: () -> T): T {
        val remaining = synchronized(rateLock) { rateUntil - elapsedRealtime() }
        if (remaining > 0) throw ApiException(429, "Simkl is limiting requests. Wait before trying again.", retryAfterSeconds = ((remaining + 999) / 1000).toInt())
        try { return call() } catch (error: ApiException) {
            if (error.code == 429) synchronized(rateLock) { rateUntil = maxOf(rateUntil, elapsedRealtime() + maxOf(5, error.retryAfterSeconds) * 1000L) }
            throw error
        }
    }
    private fun checkRevision(revision: String) {
        if (revision.isBlank() || accountRevision() != revision || !connected()) throw ApiException(401, "The Simkl account changed. This queued update was not sent.")
    }
    private fun clientId(): String = store.get("simkl.client_id").trim().takeIf(::validClientId)
        ?: throw ApiException(0, "Add the Client ID from your registered Simkl AUTH V2 TV/device application in Settings.")
    private fun validClientId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,256}"))
    private fun emptyAccount() = listOf("access_token", "refresh_token", "expires_at", "refresh_expires_at", "scope", "account_revision", "library.movies", "library.shows")
        .associate { "simkl.$it" to "" }
    private fun tokenValue(value: JSONObject, key: String) = (value.opt(key) as? String)?.takeIf {
        it.isNotBlank() && it.length <= 4096 && it.all { char -> char.code in 33..126 }
    } ?: malformed()

    internal fun parseDeviceAuth(value: JSONObject): DeviceAuth {
        val code = tokenValue(value, "device_code")
        val userCode = tokenValue(value, "user_code")
        val verification = value.optString("verification_uri_complete").ifBlank { value.optString("verification_uri") }
        val uri = try { URI(verification) } catch (_: Exception) { malformed() }
        if (uri.scheme != "https" || uri.host !in setOf("simkl.com", "www.simkl.com") || uri.port !in setOf(-1, 443) ||
            uri.userInfo != null || uri.fragment != null || uri.path !in setOf("/pin", "/pin/")) malformed()
        if (uri.rawQuery != null && uri.rawQuery != "user_code=${Net.encode(userCode)}") malformed()
        val expires = value.optInt("expires_in", 0)
        val interval = value.optInt("interval", 5)
        if (expires !in 1..900 || interval !in 1..900 || userCode.length > 64) malformed()
        return DeviceAuth(code, userCode, verification, interval, expires)
    }
    private fun authError(error: ApiException): ApiException = when {
        error.errorKey == "invalid_client" -> ApiException(401, "Simkl rejected this Client ID. Register an AUTH V2 public TV/device app; AUTH V1 credentials and server apps requiring a secret cannot use this connection.", "invalid_client")
        error.code == 429 -> error
        error.code in 500..599 -> ApiException(error.code, "Simkl is temporarily unavailable. Try connecting again later.")
        else -> ApiException(error.code, "Simkl could not complete authorization. Check the Client ID and internet connection, then try again.", error.errorKey, error.retryAfterSeconds)
    }
    private fun ended(): Nothing = throw ApiException(410, "This Simkl linking session ended. Start linking again.")
    private fun malformed(): Nothing = throw ApiException(0, "Simkl returned an unreadable response.")
    private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    private fun stamp(value: JSONObject, key: String): String = if (value.isNull(key)) "" else value.optString(key)
    private fun mediaObject(identity: PlaybackIdentity) = JSONObject().put("title", identity.title).put("ids", JSONObject().put("imdb", identity.imdbId))
    private fun validateIdentity(identity: PlaybackIdentity) {
        require(IMDB_ID.matches(identity.imdbId) && identity.type in setOf("movie", "series")) { "A matched IMDb movie or series is required for Simkl tracking." }
        require(identity.type == "movie" || (identity.season >= 0 && identity.episode > 0)) { "A specific episode is required for Simkl tracking." }
    }
    private fun imdb(media: JSONObject): String? = media.optJSONObject("ids")?.optString("imdb")?.takeIf(IMDB_ID::matches)
    private fun libraryId(entry: JSONObject): String {
        val media = entry.optJSONObject("movie") ?: entry.optJSONObject("show") ?: entry
        val ids = media.optJSONObject("ids") ?: malformed()
        return ids.optLong("simkl", ids.optLong("simkl_id", -1)).takeIf { it > 0 }?.toString() ?: imdb(media) ?: malformed()
    }
    internal fun parseMedia(entry: JSONObject): MediaItemData? {
        val movie = entry.optJSONObject("movie")
        val media = movie ?: entry.optJSONObject("show") ?: entry
        val id = imdb(media) ?: return null
        if (media.optString("title").isBlank()) return null
        val type = if (movie != null || entry.optString("type") == "movie") "movie" else "series"
        fun image(value: String, category: String): String = when {
            value.isBlank() || value == "null" -> ""
            value.startsWith("https://") -> value
            value.matches(Regex("[A-Za-z0-9/_-]+")) -> "https://simkl.in/$category/${value}_m.jpg"
            else -> ""
        }
        return MediaItemData(id, type, media.optString("title"), media.optString("year").takeUnless { it == "null" }.orEmpty(),
            image(media.optString("poster"), "posters"), image(media.optString("fanart"), "fanart"),
            media.optString("overview").takeUnless { it == "null" }.orEmpty())
    }

    companion object {
        private const val BASE = "https://api.simkl.com"
        private const val USER_AGENT = "POV-Standalone/0.3"
        private val IMDB_ID = Regex("tt[0-9]{5,12}")
        private val authLock = Any()
        private val tokenLock = Any()
        private val libraryLock = Any()
        private val writeLock = Any()
        private val writeWindows = ConcurrentHashMap<String, Long>()
        private var generation = 0L
    }
}

/** Shared credential seam for offline tracker tests; production delegates to the Android Keystore. */
internal interface TrackerCredentials {
    fun get(key: String, default: String = ""): String
    fun putAll(values: Map<String, String>)
}
