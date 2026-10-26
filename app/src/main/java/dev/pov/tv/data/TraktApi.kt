package dev.pov.tv.data

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A separately registered Trakt client ID is required; no upstream app credentials are used. */
class TraktApi internal constructor(
    private val store: TraktCredentials,
    private val authTransport: (String, Map<String, String>, JSONObject) -> JSONObject = { url, headers, body ->
        Net.json(url, "POST", headers, body)
    },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val apiTransport: (String, String, Map<String, String>, JSONObject?) -> String = Net::raw
) {
    constructor(store: SecureStore) : this(object : TraktCredentials {
        override fun get(key: String, default: String) = store.get(key, default)
        override fun putAll(values: Map<String, String>) = store.putAll(values)
        override fun remove(key: String) = store.remove(key)
    })

    private data class Pending(
        val started: Long, val clientId: String, val expiresSeconds: Int,
        var lastPoll: Long, var interval: Long, val accountGeneration: Long
    )
    private val devices = ConcurrentHashMap<String, Pending>()
    private var deviceGeneration = 0L
    private val rateLock = Any()
    private var rateUntil = 0L

    fun configured(): Boolean = validClientId(store.get("trakt.client_id").trim())
    fun connected(): Boolean = configured() && store.has("trakt.access_token")
    /** Stable across token refreshes, different after every completed account authorization. */
    fun accountRevision(): String = store.get("trakt.account_revision")

    /** Invalidates pending authorization and refreshes without waiting on a network lock. */
    fun disconnect() = synchronized(authStateLock) {
        devices.clear(); deviceGeneration++; accountGeneration++
        store.putAll(emptyAccount())
    }

    /** Returns true when changing the client registration also cleared its old account. */
    fun setClientId(value: String): Boolean = synchronized(authStateLock) {
        val id = value.trim()
        require(validClientId(id)) { "Enter the Client ID from your registered Trakt application." }
        val changed = try { store.get("trakt.client_id").trim() != id } catch (_: Exception) { true }
        if (changed) {
            devices.clear(); deviceGeneration++; accountGeneration++
            store.putAll(emptyAccount() + ("trakt.client_id" to id))
        }
        changed
    }

    @Synchronized
    fun startDeviceAuth(): DeviceAuth {
        val (appClientId, generation, account) = synchronized(authStateLock) {
            devices.clear()
            Triple(clientId(), ++deviceGeneration, accountGeneration)
        }
        val started = elapsedRealtime()
        val value = try {
            oauth("/oauth/device/code", JSONObject().put("client_id", appClientId), appClientId)
        } catch (error: ApiException) {
            throw authError(error, starting = true)
        }
        val auth = parseDeviceAuth(value)
        synchronized(authStateLock) {
            if (generation != deviceGeneration || account != accountGeneration) endedSession()
            devices.clear()
            devices[auth.deviceCode] = Pending(started, appClientId, auth.expiresSeconds,
                started, auth.intervalSeconds.toLong(), account)
        }
        return auth
    }

    /** A dismissed dialog must never silently recreate a session or save its eventual tokens. */
    fun cancelDeviceAuth(auth: DeviceAuth? = null) {
        synchronized(authStateLock) {
            if (auth == null) { devices.clear(); deviceGeneration++ } else devices.remove(auth.deviceCode)
        }
    }

    @Synchronized
    fun pollDeviceAuth(auth: DeviceAuth): Boolean {
        val now = elapsedRealtime()
        val state = devices[auth.deviceCode]
            ?: throw ApiException(410, "This Trakt linking session has ended. Choose Connect Trakt to get a new code.")
        if (store.get("trakt.client_id").trim() != state.clientId ||
            synchronized(authStateLock) { state.accountGeneration != accountGeneration }) {
            devices.remove(auth.deviceCode)
            throw ApiException(0, "The Trakt Client ID changed. Choose Connect Trakt to get a new code.")
        }
        if (now - state.started >= state.expiresSeconds.toLong() * 1000) {
            devices.remove(auth.deviceCode)
            throw ApiException(410, "The Trakt linking code has expired. Start linking again.")
        }
        if (now - state.lastPoll < state.interval * 1000) return false
        state.lastPoll = now
        val value = try {
            oauth("/oauth/device/token", JSONObject().put("code", auth.deviceCode).put("client_id", state.clientId), state.clientId)
        } catch (e: ApiException) {
            when {
                // Trakt device flow specifies 400 as pending, rather than a generic auth error.
                e.code == 400 && (e.errorKey.isBlank() || e.errorKey == "authorization_pending") -> return false
                e.errorKey == "slow_down" || e.code == 429 -> {
                    state.interval = maxOf(state.interval + 5, e.retryAfterSeconds.toLong())
                    return false
                }
                e.code == 404 -> throw ApiException(404, "This Trakt linking code is invalid. Start linking again.")
                e.code == 409 -> throw ApiException(409, "This Trakt linking code was already used. Start linking again.")
                e.code == 410 || e.errorKey == "expired_token" -> throw ApiException(410, "The Trakt linking code has expired. Start linking again.")
                e.code == 418 || e.errorKey == "access_denied" -> throw ApiException(418, "Trakt authorization was declined.")
                else -> throw authError(e, starting = false)
            }
        }
        synchronized(authStateLock) {
            if (devices[auth.deviceCode] !== state || state.accountGeneration != accountGeneration) endedSession()
            // A pending refresh must not overwrite tokens from a newly completed connection.
            // This lock is short; holding it while waiting on tokenLock could block Disconnect.
            saveTokens(value, newAuthorization = true)
            accountGeneration++
            devices.remove(auth.deviceCode)
        }
        return true
    }

    fun watchlist(type: String): List<MediaItemData> {
        val kind = when (type) { "movie", "movies" -> "movies"; "series", "show", "shows" -> "shows"; else -> throw IllegalArgumentException("Invalid watchlist type.") }
        return pages("/users/me/watchlist/$kind?extended=full").mapNotNull(::parseMedia)
    }

    /** Public, exact external-ID lookup for anime matching; no OAuth token is sent. */
    fun lookupCatalogId(idType: String, id: String, type: String): MediaItemData? {
        require(idType == "trakt" || idType == "tvdb") { "This catalog ID provider is not supported." }
        require(id.matches(Regex("[1-9][0-9]{0,14}"))) { "This catalog identifier is invalid." }
        require(type == "movie" || type == "series") { "This catalog media type is not supported." }
        if (!configured()) return null
        val kind = if (type == "movie") "movie" else "show"
        val raw = rateLimited {
            apiTransport("$BASE/search/$idType/$id?type=$kind&extended=full&limit=10", "GET", headers(), null)
        }
        val records = try { JSONArray(raw) } catch (_: Exception) { malformed() }
        return parseCatalogLookup(records, idType, id, type)
    }

    internal fun parseCatalogLookup(records: JSONArray, idType: String, id: String, type: String): MediaItemData? {
        val kind = if (type == "movie") "movie" else "show"
        val matches = (0 until records.length()).mapNotNull { index ->
            val entry = records.optJSONObject(index) ?: return@mapNotNull null
            if (entry.optString("type") != kind) return@mapNotNull null
            val media = entry.optJSONObject(kind) ?: return@mapNotNull null
            if (media.optJSONObject("ids")?.opt(idType)?.toString() != id) return@mapNotNull null
            parseMedia(entry)?.takeIf { it.type == type && it.imdbId.matches(Regex("tt[0-9]{5,12}")) }
        }.distinctBy { it.imdbId }
        return matches.singleOrNull()
    }

    /** Trakt paused-playback records, including the show's IMDb ID for episode matching. */
    fun continueWatching(): List<Pair<PlaybackIdentity, Double>> =
        parsePlayback(arrayRequest("/sync/playback?limit=100"))

    internal fun parsePlayback(items: JSONArray): List<Pair<PlaybackIdentity, Double>> =
            (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val percent = item.optDouble("progress", 0.0)
                if (!percent.isFinite() || percent <= 0 || percent >= 100) return@mapNotNull null
                val movie = item.optJSONObject("movie")
                val show = item.optJSONObject("show")
                val episode = item.optJSONObject("episode")
                val identity = if (movie != null) {
                    val id = imdb(movie) ?: return@mapNotNull null
                    PlaybackIdentity(id, "movie", movie.optString("title"))
                } else if (show != null && episode != null) {
                    val id = imdb(show) ?: return@mapNotNull null
                    val season = episode.optInt("season", -1)
                    val number = episode.optInt("number", -1)
                    if (season < 0 || number <= 0) return@mapNotNull null
                    PlaybackIdentity(id, "series", show.optString("title"), season, number)
                } else return@mapNotNull null
                identity to percent
            }

    fun lists(): List<Pair<String, String>> = pages("/users/me/lists").mapNotNull { value ->
        val ids = value.optJSONObject("ids") ?: return@mapNotNull null
        val id = ids.optLong("trakt", -1)
        if (id < 0) null else id.toString() to value.optString("name", "Untitled list")
    }

    fun listItems(id: String): List<MediaItemData> {
        require(id.isNotBlank() && id.length <= 200) { "Invalid Trakt list." }
        return pages("/users/me/lists/${Net.encode(id)}/items?extended=full").mapNotNull(::parseMedia)
    }

    /** Show entries are added as shows; selecting an episode never adds the entire season. */
    fun setWatchlist(identity: PlaybackIdentity, add: Boolean) = synchronized(writeLock) {
        val body = watchlistBody(identity)
        val result = try {
            objectRequest(if (add) "/sync/watchlist" else "/sync/watchlist/remove", "POST", body)
        } catch (error: ApiException) {
            if (error.code == 420) throw ApiException(420, "Your Trakt watchlist limit has been reached.")
            throw error
        }
        val kind = if (isMovie(identity)) "movies" else "shows"
        if ((result.optJSONObject("not_found")?.optJSONArray(kind)?.length() ?: 0) > 0) {
            throw ApiException(404, "Trakt could not match this title. The watchlist was not changed.")
        }
        val confirmed = if (add) {
            (result.optJSONObject("added")?.optInt(kind, 0) ?: 0) +
                (result.optJSONObject("existing")?.optInt(kind, 0) ?: 0) > 0
        } else result.optJSONObject("deleted")?.has(kind) == true
        if (!confirmed) throw ApiException(0, "Trakt did not confirm the watchlist update. Please refresh before trying again.")
        Unit
    }

    internal fun watchlistBody(identity: PlaybackIdentity): JSONObject {
        require(identity.imdbId.matches(Regex("tt[0-9]+"))) { "A matched IMDb title is required for the Trakt watchlist." }
        require(isMovie(identity) || identity.type in setOf("series", "show", "shows")) { "Only movies and shows can be saved to this watchlist." }
        val item = JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId))
        return JSONObject().put(if (isMovie(identity)) "movies" else "shows", JSONArray().put(item))
    }

    /** Server-calculated next aired episode for up to ten most recently watched shows. */
    fun nextEpisodes(expectedRevision: String = accountRevision()): List<PlaybackIdentity> {
        val watched = try { JSONArray(accountBoundRead("/sync/watched/shows?extended=full", expectedRevision)) }
        catch (error: ApiException) { throw error } catch (_: Exception) { malformed() }
        val recent = (0 until watched.length()).mapNotNull { watched.optJSONObject(it) }
            .sortedByDescending { it.optString("last_watched_at") }
            .filter { it.optJSONObject("show")?.let(::imdb) != null }.take(10)
        return recent.mapNotNull { item ->
            val show = item.getJSONObject("show")
            val showId = show.optJSONObject("ids")?.optLong("trakt", -1)?.takeIf { it >= 0 }?.toString() ?: imdb(show)!!
            val progress = try {
                JSONObject(accountBoundRead("/shows/${Net.encode(showId)}/progress/watched?hidden=false&specials=false", expectedRevision))
            } catch (error: ApiException) {
                if (error.code == 404) return@mapNotNull null
                throw error
            } catch (_: Exception) { malformed() }
            parseNextEpisode(show, progress)
        }
    }

    internal fun parseNextEpisode(show: JSONObject, progress: JSONObject): PlaybackIdentity? {
        val id = imdb(show) ?: return null
        val episode = progress.optJSONObject("next_episode") ?: return null
        val season = episode.optInt("season", -1)
        val number = episode.optInt("number", -1)
        if (season < 0 || number <= 0) return null
        return PlaybackIdentity(id, "series", show.optString("title"), season, number)
    }

    /** Authorization changes and refreshes share tokenLock; an old queued event cannot cross accounts. */
    fun progressForAccount(identity: PlaybackIdentity, percent: Double, action: String, revision: String): Boolean =
        synchronized(writeLock) {
            synchronized(tokenLock) {
                if (revision.isBlank() || !connected() || accountRevision() != revision) false
                else { progress(identity, percent, action); true }
            }
        }

    /** Actions are intentionally sent only for an identified movie or a specific episode. */
    fun progress(identity: PlaybackIdentity, percent: Double, action: String) = synchronized(writeLock) {
        require(action in setOf("start", "pause", "stop")) { "Invalid playback action." }
        require(percent.isFinite()) { "Invalid playback progress." }
        validateIdentity(identity)
        val body = JSONObject().put("progress", percent.coerceIn(0.0, 100.0))
        if (isMovie(identity)) {
            body.put("movie", JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId)))
        } else {
            body.put("show", JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId)))
            body.put("episode", JSONObject().put("season", identity.season).put("number", identity.episode))
        }
        try {
            objectRequest("/scrobble/$action", "POST", body)
        } catch (e: ApiException) {
            // 409 on stop means Trakt already scrobbled this item within its duplicate window.
            if (e.code != 409 || action != "stop") throw e
        }
        Unit
    }

    fun markWatched(identity: PlaybackIdentity) = synchronized(writeLock) {
        validateIdentity(identity)
        val media = JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId))
        val body = if (isMovie(identity)) {
            JSONObject().put("movies", JSONArray().put(media))
        } else {
            val episode = JSONObject().put("number", identity.episode)
            val season = JSONObject().put("number", identity.season).put("episodes", JSONArray().put(episode))
            media.put("seasons", JSONArray().put(season))
            JSONObject().put("shows", JSONArray().put(media))
        }
        val result = objectRequest("/sync/history", "POST", body)
        val missing = result.optJSONObject("not_found")
        if (missing != null && listOf("movies", "shows", "episodes").any { (missing.optJSONArray(it)?.length() ?: 0) > 0 }) {
            throw ApiException(404, "Trakt could not match this title. Its watched status was not changed.")
        }
        val added = result.optJSONObject("added")
        val key = if (isMovie(identity)) "movies" else "episodes"
        if (added == null || added.optInt(key, 0) <= 0) {
            throw ApiException(0, "Trakt did not confirm the watched-status update. Please refresh before trying again.")
        }
        Unit
    }

    private fun pages(path: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        for (page in 1..100) {
            val items = arrayRequest(path + (if ('?' in path) "&" else "?") + "limit=100&page=$page")
            for (i in 0 until items.length()) items.optJSONObject(i)?.let(result::add)
            if (items.length() < 100) return result
        }
        throw ApiException(0, "This Trakt list is too large to load at once.")
    }

    private fun arrayRequest(path: String): JSONArray = try {
        JSONArray(request(path))
    } catch (e: ApiException) { throw e } catch (_: Exception) { malformed() }

    private fun objectRequest(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = try {
        val value = request(path, method, body)
        if (value.isBlank()) JSONObject() else JSONObject(value)
    } catch (e: ApiException) { throw e } catch (_: Exception) { malformed() }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): String {
        val access = accessToken()
        try {
            return rateLimited { apiTransport("$BASE$path", method, headers(access), body) }
        } catch (e: ApiException) {
            if (e.code != 401) throw e
            // A concurrent request may have already rotated the access token. Reuse that result.
            val refreshed = accessToken(forceForAccess = access)
            return rateLimited { apiTransport("$BASE$path", method, headers(refreshed), body) }
        }
    }

    /** A multi-request read must never adopt a replacement account between pages or a 401 retry. */
    private data class BoundRead(val access: String, val clientId: String, val revision: String)

    private fun boundReadSnapshot(revision: String, forceForAccess: String? = null): BoundRead {
        accessToken(forceForAccess, expectedRevision = revision)
        return synchronized(authStateLock) {
            ensureExpectedAccount(revision)
            BoundRead(store.get("trakt.access_token"), clientId(), revision)
        }
    }

    private fun ensureExpectedAccount(revision: String?) {
        if (revision != null && (revision.isBlank() || accountRevision() != revision || !connected())) {
            throw ApiException(401, "The Trakt account changed. Reopen this page with the current account.")
        }
    }

    private fun ensureBoundRead(account: BoundRead) = synchronized(authStateLock) {
        ensureExpectedAccount(account.revision)
        if (store.get("trakt.client_id").trim() != account.clientId) {
            throw ApiException(401, "The Trakt account changed. Reopen this page with the current account.")
        }
    }

    private fun accountBoundRead(path: String, revision: String): String {
        val account = boundReadSnapshot(revision)
        fun fetch(snapshot: BoundRead): String {
            val result = rateLimited {
                ensureBoundRead(snapshot)
                // All credentials were captured before I/O; no account lock is held on the network.
                apiTransport("$BASE$path", "GET", headers(snapshot.access, snapshot.clientId), null)
            }
            ensureBoundRead(snapshot)
            return result
        }
        return try { fetch(account) } catch (error: ApiException) {
            if (error.code != 401) throw error
            ensureBoundRead(account)
            fetch(boundReadSnapshot(revision, forceForAccess = account.access))
        }
    }

    private fun accessToken(forceForAccess: String? = null, expectedRevision: String? = null): String = synchronized(tokenLock) {
        val snapshot = synchronized(authStateLock) {
            ensureExpectedAccount(expectedRevision)
            AccountSnapshot(store.get("trakt.access_token"), store.get("trakt.refresh_token"),
                store.get("trakt.expires_at").toLongOrNull() ?: 0L, clientId(), accountGeneration)
        }
        val access = snapshot.access
        if (access.isBlank()) throw ApiException(401, "Connect your Trakt account first.")
        val needsRefresh = if (forceForAccess != null) access == forceForAccess else snapshot.expires <= System.currentTimeMillis() / 1000 + 60
        if (!needsRefresh) return@synchronized access
        val refresh = snapshot.refresh
        if (refresh.isBlank()) throw ApiException(401, "Your Trakt session has expired. Link your account again.")
        val redirect = store.get("trakt.redirect_uri", "urn:ietf:wg:oauth:2.0:oob").trim()
        if (redirect.isBlank() || redirect.length > 2048 || redirect.any { it.code < 32 || it.code == 127 }) {
            throw ApiException(0, "Enter the callback address registered for your Trakt app in Settings.")
        }
        // Refresh tokens are single-use. Persist an intent before sending, so a process interruption
        // or lost response cannot cause the same refresh token to be submitted a second time.
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(refresh.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        synchronized(authStateLock) {
            ensureExpectedAccount(expectedRevision)
            ensureCurrentAccount(snapshot)
            if (store.get("trakt.refresh_pending") == fingerprint) {
                throw ApiException(401, "The previous Trakt token refresh could not be confirmed. Link your account again.")
            }
            store.put("trakt.refresh_pending", fingerprint)
        }
        val value = try {
            oauth("/oauth/token", JSONObject().put("refresh_token", refresh)
                .put("client_id", snapshot.clientId).put("redirect_uri", redirect).put("grant_type", "refresh_token"), snapshot.clientId)
        } catch (e: ApiException) {
            // A 429 explicitly refuses the request, so this token can safely be attempted later.
            if (e.code == 429) synchronized(authStateLock) {
                if (snapshot.generation == accountGeneration && store.get("trakt.refresh_pending") == fingerprint) {
                    store.remove("trakt.refresh_pending")
                }
            }
            if (e.code == 400 || e.code == 401 || e.errorKey == "invalid_grant") {
                throw ApiException(401, "Your Trakt session has expired. Link your account again.")
            }
            throw e
        }
        synchronized(authStateLock) { ensureExpectedAccount(expectedRevision); ensureCurrentAccount(snapshot); saveTokens(value) }
        required(value, "access_token")
    }

    private data class AccountSnapshot(val access: String, val refresh: String, val expires: Long, val clientId: String, val generation: Long)

    private fun ensureCurrentAccount(snapshot: AccountSnapshot) {
        if (snapshot.generation != accountGeneration || store.get("trakt.client_id").trim() != snapshot.clientId ||
            store.get("trakt.access_token") != snapshot.access || store.get("trakt.refresh_token") != snapshot.refresh) {
            throw ApiException(401, "The Trakt connection changed while this request was running. Try again with the current account.")
        }
    }

    private fun emptyAccount() = mapOf("trakt.access_token" to "", "trakt.refresh_token" to "",
        "trakt.expires_at" to "", "trakt.account_revision" to "", "trakt.refresh_pending" to "")

    private fun endedSession(): Nothing = throw ApiException(410, "This Trakt linking session has ended. Choose Connect Trakt to get a new code.")

    private fun saveTokens(value: JSONObject, newAuthorization: Boolean = false) {
        val access = required(value, "access_token")
        val refresh = required(value, "refresh_token")
        val lifetime = value.optLong("expires_in", 0)
        if (lifetime <= 0) malformed()
        val created = value.optLong("created_at", System.currentTimeMillis() / 1000)
        val updates = mutableMapOf(
            "trakt.access_token" to access,
            "trakt.refresh_token" to refresh,
            "trakt.expires_at" to (created + lifetime).toString(),
            "trakt.refresh_pending" to ""
        )
        if (newAuthorization) updates["trakt.account_revision"] = UUID.randomUUID().toString()
        store.putAll(updates)
    }

    private fun oauth(path: String, body: JSONObject, appClientId: String = clientId()): JSONObject = rateLimited {
        authTransport("$AUTH_BASE$path", headers(appClientId = appClientId), body)
    }

    private fun headers(access: String = "", appClientId: String = clientId()): Map<String, String> = buildMap {
        put("trakt-api-version", "2")
        put("trakt-api-key", appClientId)
        if (access.isNotBlank()) put("Authorization", "Bearer $access")
    }

    private fun clientId(): String {
        val value = store.get("trakt.client_id").trim()
        if (value.isBlank()) throw ApiException(0,
            "Trakt is not configured. Add the Client ID from your registered Trakt application in Settings, save it, then choose Connect Trakt again.")
        if (!validClientId(value)) throw ApiException(0,
            "The Trakt Client ID is invalid. Copy the Client ID from your registered Trakt application into Settings, save it, then try again.")
        return value
    }

    private fun validClientId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,256}"))

    private fun parseDeviceAuth(value: JSONObject): DeviceAuth {
        fun code(key: String, maxLength: Int): String = (value.opt(key) as? String)?.takeIf {
            it.isNotBlank() && it.length <= maxLength && it.all { character -> character.code in 33..126 }
        } ?: invalidDeviceResponse()
        fun seconds(key: String): Int {
            val number = value.opt(key) as? Number ?: invalidDeviceResponse()
            val seconds = number.toLong()
            if (seconds !in 1..Int.MAX_VALUE.toLong() || number.toDouble() != seconds.toDouble()) invalidDeviceResponse()
            return seconds.toInt()
        }
        val url = (value.opt("verification_url") as? String) ?: invalidDeviceResponse()
        val target = try { URI(url) } catch (_: Exception) { invalidDeviceResponse() }
        if (target.scheme != "https" || target.host?.lowercase(Locale.ROOT) !in setOf("auth.trakt.tv", "trakt.tv", "www.trakt.tv") ||
            target.port !in setOf(-1, 443) || target.userInfo != null || target.rawQuery != null || target.rawFragment != null ||
            target.path !in setOf("/activate", "/activate/")) invalidDeviceResponse()
        return DeviceAuth(code("device_code", 1024), code("user_code", 64), url, seconds("interval"), seconds("expires_in"))
    }

    private fun invalidDeviceResponse(): Nothing = throw ApiException(0,
        "Trakt did not return a usable activation code. Choose Connect Trakt to try again. If this continues, update POV or check Trakt's service status.")

    /** Only fixed messages are displayed; never echo an OAuth response, URL, ID, or token. */
    private fun authError(error: ApiException, starting: Boolean): ApiException {
        val message = when {
            error.errorKey in setOf("invalid_client", "unauthorized_client", "invalid_client_id") || error.code in setOf(401, 403) ->
                "Trakt rejected this app's Client ID. Check the Client ID in Settings against your registered Trakt application, save it, then try again."
            error.code == 429 -> {
                val wait = maxOf(5, error.retryAfterSeconds)
                "Trakt is limiting requests. Wait $wait seconds, then choose Connect Trakt again."
            }
            error.code in 500..599 -> "Trakt is temporarily unavailable (HTTP ${error.code}). Try connecting again later."
            error.code in 300..399 -> "Trakt redirected the connection unexpectedly. Update POV and try again."
            error.code == 0 && error.message.orEmpty().contains("timed out") ->
                "The request to Trakt timed out. Check the TV's internet connection and try again."
            error.code == 0 && error.message.orEmpty().contains("secure connection") ->
                "A secure connection to Trakt could not be established. Check the TV's date, time, and internet connection."
            error.code == 0 && error.message.orEmpty().contains("unreadable") ->
                "Trakt returned an unreadable response. Try again later or update POV."
            error.code == 0 -> "Trakt could not be reached. Check the TV's internet connection and try again."
            starting && error.code in setOf(400, 422) ->
                "Trakt could not issue an activation code (HTTP ${error.code}). Check this app's Client ID in Settings, save it, then try again."
            else -> "Trakt could not complete the connection (HTTP ${error.code}). Choose Connect Trakt to try again."
        }
        return ApiException(error.code, message, error.errorKey, error.retryAfterSeconds)
    }

    private fun <T> rateLimited(block: () -> T): T {
        val remaining = synchronized(rateLock) { rateUntil - elapsedRealtime() }
        if (remaining > 0) throw ApiException(429, "Trakt is rate limited. Please wait before trying again.", retryAfterSeconds = ((remaining + 999) / 1000).toInt())
        try { return block() } catch (e: ApiException) {
            if (e.code == 429) synchronized(rateLock) {
                rateUntil = maxOf(rateUntil, elapsedRealtime() + maxOf(5, e.retryAfterSeconds).toLong() * 1000)
            }
            throw e
        }
    }

    internal fun parseMedia(value: JSONObject): MediaItemData? {
        val movie = value.optJSONObject("movie")
        val show = value.optJSONObject("show")
        val media = movie ?: show ?: return null
        val id = imdb(media) ?: return null
        val genres = media.optJSONArray("genres") ?: JSONArray()
        val rating = media.optDouble("rating", Double.NaN)
        return MediaItemData(
            imdbId = id, type = if (movie != null) "movie" else "series", title = media.optString("title"),
            year = media.optInt("year", 0).takeIf { it > 0 }?.toString().orEmpty(),
            description = media.optString("overview").takeUnless { it == "null" }.orEmpty(),
            genres = (0 until genres.length()).map { genres.optString(it) }.filter { it.isNotBlank() },
            runtime = media.optInt("runtime", 0).takeIf { it > 0 }?.toString().orEmpty(),
            rating = if (rating.isFinite()) String.format(Locale.US, "%.1f", rating) else ""
        )
    }

    private fun imdb(value: JSONObject): String? = value.optJSONObject("ids")?.optString("imdb")?.takeIf { it.matches(Regex("tt[0-9]+")) }
    private fun isMovie(identity: PlaybackIdentity): Boolean = identity.type in setOf("movie", "movies")
    private fun validateIdentity(identity: PlaybackIdentity) {
        require(identity.imdbId.matches(Regex("tt[0-9]+"))) { "A matched IMDb title is required for Trakt tracking." }
        require(isMovie(identity) || identity.type in setOf("series", "show", "shows", "episode")) { "Invalid playback title type." }
        if (!isMovie(identity)) require(identity.season >= 0 && identity.episode > 0) { "A specific season and episode are required for Trakt tracking." }
    }
    private fun required(value: JSONObject, key: String): String = value.optString(key).takeIf { it.isNotBlank() && it != "null" } ?: malformed()
    private fun malformed(): Nothing = throw ApiException(0, "Trakt returned an unreadable response.")

    companion object {
        private const val BASE = "https://api.trakt.tv"
        private const val AUTH_BASE = "https://auth.trakt.tv"
        // Shared by MainActivity and PlayerActivity clients; never held across network calls.
        private val authStateLock = Any()
        private var accountGeneration = 0L
        private val tokenLock = Any()
        private val writeLock = Any()
    }
}

/** Narrow account adapter keeps device-flow tests offline and independent of Android Keystore. */
internal interface TraktCredentials {
    fun get(key: String, default: String = ""): String
    fun putAll(values: Map<String, String>)
    fun remove(key: String)
    fun has(key: String): Boolean = get(key).isNotBlank()
    fun put(key: String, value: String) = putAll(mapOf(key to value))
}
