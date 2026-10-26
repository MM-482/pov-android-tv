package dev.pov.tv.data

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** TorBox v1 API. These methods perform real requests and must run off the UI thread. */
class TorBoxApi internal constructor(
    private val store: TorBoxCredentials,
    private val transport: (String, String, Map<String, String>, JSONObject?) -> JSONObject = Net::json,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val multipartTransport: (String, Map<String, String>, Map<String, String>) -> JSONObject = Net::multipart
) {
    constructor(store: SecureStore) : this(object : TorBoxCredentials {
        override fun get(key: String) = store.get(key)
        override fun put(key: String, value: String) { store.put(key, value) }
        override fun remove(key: String) { store.remove(key) }
    })

    private data class Pending(val started: Long, val expiresSeconds: Int, val intervalSeconds: Int,
        val credentialGeneration: Long, var lastPoll: Long = 0)
    private val devices = mutableMapOf<String, Pending>()
    private var authGeneration = 0L
    private data class Account(val token: String, val generation: Long) {
        val revision: String get() = generation.toString() + ":" + MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun accountRevision(): String = synchronized(authStateLock) {
        store.get("torbox.token").takeIf { it.isNotBlank() }?.let { Account(it, accountGeneration).revision }.orEmpty()
    }

    @Synchronized
    fun startDeviceAuth(): DeviceAuth {
        val (generation, account) = synchronized(authStateLock) { devices.clear(); ++authGeneration to credentialGeneration }
        val started = elapsedRealtime()
        val value = objectData(call("/user/auth/device/start?app=${Net.encode("POV Standalone Android TV")}", authenticated = false))
        val auth = DeviceAuth(
            required(value, "device_code"), value.optString("code", value.optString("user_code")),
            value.optString("verification_url", value.optString("friendly_verification_url")),
            value.optInt("interval", 5).coerceAtLeast(1), value.optInt("expires_in", 600).coerceAtLeast(1)
        )
        if (auth.userCode.isBlank() || !auth.verificationUrl.startsWith("https://")) malformed()
        synchronized(authStateLock) {
            if (generation != authGeneration || account != credentialGeneration) endedSession()
            devices[auth.deviceCode] = Pending(started, auth.expiresSeconds, auth.intervalSeconds, account)
        }
        return auth
    }

    /** Cancel without waiting on a device/token or account-verification network request. */
    fun cancelDeviceAuth(auth: DeviceAuth? = null) {
        synchronized(authStateLock) {
            if (auth == null) { devices.clear(); authGeneration++ }
            else devices.remove(auth.deviceCode)
        }
    }

    /** A late device or API-key verification must not silently undo Disconnect. */
    fun disconnect() = synchronized(authStateLock) {
        devices.clear(); authGeneration++; credentialGeneration++
        store.remove("torbox.token")
        accountGeneration++
    }

    /** False means pending; an expired, declined, or failed authorization is an exception. */
    @Synchronized
    fun pollDeviceAuth(auth: DeviceAuth): Boolean {
        val now = elapsedRealtime()
        val state = synchronized(authStateLock) {
            devices[auth.deviceCode]?.takeIf { it.credentialGeneration == credentialGeneration }
        } ?: endedSession()
        if (now - state.started >= state.expiresSeconds.toLong() * 1000) {
            synchronized(authStateLock) { devices.remove(auth.deviceCode) }
            throw ApiException(410, "The TorBox linking code has expired. Start linking again.")
        }
        if (state.lastPoll != 0L && now - state.lastPoll < state.intervalSeconds.toLong() * 1000) return false
        state.lastPoll = now
        val response = try {
            call("/user/auth/device/token", "POST", JSONObject().put("device_code", auth.deviceCode), authenticated = false)
        } catch (e: ApiException) {
            when (e.errorKey) {
                "DEVICE_CODE_NOT_USED", "authorization_pending" -> return false
                "ITEM_NOT_FOUND", "expired_token" -> throw ApiException(410, "The TorBox linking code has expired. Start linking again.")
                "DEVICE_CODE_USED" -> throw ApiException(409, "This TorBox linking code has already been used. Start linking again.")
                else -> throw e
            }
        }
        val token = required(objectData(response), "access_token")
        synchronized(authStateLock) { if (devices[auth.deviceCode] !== state || state.credentialGeneration != credentialGeneration) endedSession() }
        // Verify the token before replacing a working account connection.
        verifyAccount(token)
        synchronized(authStateLock) {
            if (devices[auth.deviceCode] !== state || state.credentialGeneration != credentialGeneration) endedSession()
            store.put("torbox.token", token)
            credentialGeneration++
            accountGeneration++
            devices.remove(auth.deviceCode)
        }
        return true
    }

    fun setApiKey(key: String) {
        val token = key.trim()
        if (token.isBlank() || token.length > 4096 || token.any { it.code !in 33..126 }) {
            throw ApiException(0, "Enter a valid TorBox API key.")
        }
        val generation = synchronized(authStateLock) { devices.clear(); authGeneration++; ++credentialGeneration }
        verifyAccount(token)
        synchronized(authStateLock) {
            if (generation != credentialGeneration) {
                throw ApiException(401, "The TorBox connection changed while this request was running. Try again with the current account.")
            }
            store.put("torbox.token", token)
            accountGeneration++
        }
    }

    fun account(expectedRevision: String? = null): JSONObject {
        val account = snapshot(expectedRevision)
        val result = verifyAccount(account.token)
        ensureAccount(account)
        return result
    }

    fun cloud(kind: String, expectedRevision: String? = null): List<CloudFolder> {
        checkKind(kind)
        val account = snapshot(expectedRevision)
        val result = mutableListOf<CloudFolder>()
        val seen = mutableSetOf<Long>()
        var offset = 0
        while (true) {
            val data = call("/$kind/mylist?limit=1000&offset=$offset&bypass_cache=true", account = account)
            val array = when (data) {
                is JSONArray -> data
                JSONObject.NULL, null -> JSONArray()
                else -> malformed()
            }
            var added = 0
            for (i in 0 until array.length()) {
                val item = parseFolder(array.getJSONObject(i), kind)
                if (seen.add(item.id)) { result.add(item); added++ }
            }
            if (array.length() < 1000 || added == 0) break
            offset += array.length()
            if (offset >= 100_000) throw ApiException(0, "This cloud library is too large to load at once.")
        }
        ensureAccount(account)
        return result
    }

    fun folder(kind: String, id: Long, expectedRevision: String? = null): CloudFolder {
        checkKind(kind)
        require(id >= 0) { "Invalid TorBox folder." }
        val account = snapshot(expectedRevision)
        val data = call("/$kind/mylist?id=$id&bypass_cache=true", account = account)
        val value = when (data) {
            is JSONObject -> data
            is JSONArray -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }.firstOrNull { it.optLong("id", -1) == id }
            else -> null
        } ?: throw ApiException(404, "This TorBox folder is unavailable.")
        val folder = parseFolder(value, kind)
        if (folder.id != id) throw ApiException(0, "TorBox returned a different folder. Reopen your library.")
        ensureAccount(account)
        return folder
    }

    fun streamUrl(kind: String, folderId: Long, fileId: Long, expectedRevision: String? = null): String {
        checkKind(kind)
        require(folderId >= 0 && fileId >= 0) { "Invalid TorBox file." }
        val idKey = when (kind) { "usenet" -> "usenet_id"; "webdl" -> "web_id"; else -> "torrent_id" }
        val account = snapshot(expectedRevision)
        // TorBox requires the API key in this particular endpoint's query. Never log it.
        val value = call("/$kind/requestdl?token=${Net.encode(account.token)}&$idKey=$folderId&file_id=$fileId&redirect=false", account = account)
        val result = (value as? String).orEmpty()
        if (!result.startsWith("https://")) throw ApiException(0, "TorBox did not return a secure playback link.")
        return result
    }

    fun cached(hashes: List<String>, expectedRevision: String? = null): Set<String> {
        val normalized = hashes.map { it.lowercase() }.distinct()
        if (normalized.any { !it.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}")) }) {
            throw ApiException(0, "A source contains an invalid torrent identifier.")
        }
        if (normalized.isEmpty()) return emptySet()
        val account = snapshot(expectedRevision)
        val result = mutableSetOf<String>()
        normalized.chunked(100).forEach { batch ->
            val value = call("/torrents/checkcached?format=list&list_files=false", "POST", JSONObject().put("hashes", JSONArray(batch)), account = account)
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) {
                    val entry = value.optJSONObject(i)
                    val hash = entry?.optString("hash")?.lowercase() ?: value.optString(i).lowercase()
                    if (hash in batch) result.add(hash)
                }
                is JSONObject -> value.keys().forEach { hash -> if (hash.lowercase() in batch) result.add(hash.lowercase()) }
                JSONObject.NULL, null -> Unit
                else -> malformed()
            }
        }
        ensureAccount(account)
        return result
    }

    /** Adds only the selected source. No torrent is created merely by browsing results. */
    fun addMagnet(hash: String, expectedRevision: String? = null): Long {
        val value = hash.trim()
        if (!value.matches(Regex("[A-Fa-f0-9]{40}|[A-Za-z2-7]{32}"))) {
            throw ApiException(0, "A source contains an invalid torrent identifier.")
        }
        val account = snapshot(expectedRevision)
        ensureAccount(account)
        val response = multipartTransport("$BASE/torrents/createtorrent", mapOf(
            "magnet" to "magnet:?xt=urn:btih:$value", "seed" to "3", "allow_zip" to "false"
        ), mapOf("Authorization" to "Bearer ${account.token}"))
        ensureAccount(account)
        val data = objectData(unwrap(response))
        return data.optLong("torrent_id", -1).takeIf { it >= 0 }
            ?: throw ApiException(0, "TorBox did not return a torrent identifier.")
    }

    private fun verifyAccount(token: String): JSONObject = objectData(unwrap(transport("$BASE/user/me", "GET", mapOf("Authorization" to "Bearer $token"), null)))

    private fun snapshot(expectedRevision: String? = null): Account = synchronized(authStateLock) {
        val token = store.get("torbox.token").takeIf { it.isNotBlank() } ?: throw ApiException(401, "Connect your TorBox account first.")
        val account = Account(token, accountGeneration)
        if (expectedRevision != null && expectedRevision != account.revision) accountChanged()
        account
    }
    private fun ensureAccount(account: Account) = synchronized(authStateLock) {
        if (accountGeneration != account.generation || store.get("torbox.token") != account.token) accountChanged()
        Unit
    }
    private fun accountChanged(): Nothing = throw ApiException(401, "The TorBox account changed. Reopen this source or cloud folder.")

    private fun call(path: String, method: String = "GET", body: JSONObject? = null, authenticated: Boolean = true, account: Account? = null): Any? {
        val session = if (authenticated) account ?: snapshot() else null
        if (session != null) ensureAccount(session)
        val result = transport("$BASE$path", method, session?.let { mapOf("Authorization" to "Bearer ${it.token}") } ?: emptyMap(), body)
        if (session != null) ensureAccount(session)
        return unwrap(result)
    }

    private fun unwrap(json: JSONObject): Any? {
        if (!json.optBoolean("success", false)) {
            val error = json.optString("error").takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }.orEmpty()
            val message = when (error) {
                "DEVICE_CODE_NOT_USED" -> "Waiting for TorBox authorization."
                "ITEM_NOT_FOUND" -> "The requested TorBox item is unavailable."
                "BAD_TOKEN", "AUTH_ERROR", "UNAUTHORIZED" -> "Connect your TorBox account again."
                "DOWNLOAD_TOO_LARGE", "ACTIVE_LIMIT", "MONTHLY_LIMIT" -> "Your TorBox account limit prevents this request."
                else -> "TorBox could not complete this request."
            }
            throw ApiException(0, message, error)
        }
        return json.opt("data")
    }

    private fun objectData(value: Any?): JSONObject = value as? JSONObject ?: malformed()
    private fun required(value: JSONObject, key: String): String = value.optString(key).takeIf { it.isNotBlank() } ?: malformed()
    private fun checkKind(kind: String) { require(kind in setOf("torrents", "usenet", "webdl")) { "Invalid TorBox library type." } }
    private fun malformed(): Nothing = throw ApiException(0, "TorBox returned an unreadable response.")
    private fun endedSession(): Nothing = throw ApiException(410, "This TorBox linking session has ended. Choose Connect TorBox to get a new code.")

    internal fun parseFolder(value: JSONObject, kind: String): CloudFolder {
        val id = value.optLong("id", -1)
        if (id < 0) malformed()
        val files = value.optJSONArray("files") ?: JSONArray()
        val parsed = (0 until files.length()).mapNotNull { index ->
            files.optJSONObject(index)?.let { item ->
                val fileId = item.optLong("id", -1)
                if (fileId < 0) null else CloudFile(fileId,
                    item.optString("short_name", item.optString("name", "File ${fileId}")), item.optLong("size"))
            }
        }
        val ready = value.optBoolean("download_finished", false) && value.optBoolean("download_present", true)
        return CloudFolder(id, kind, value.optString("name", "Download $id"), ready, parsed)
    }

    companion object {
        private const val BASE = "https://api.torbox.app/v1/api"
        private val authStateLock = Any()
        private var credentialGeneration = 0L
        private var accountGeneration = 0L
    }
}

/** Narrow adapter permits offline authorization tests without Android Keystore. */
internal interface TorBoxCredentials {
    fun get(key: String): String
    fun put(key: String, value: String)
    fun remove(key: String) { put(key, "") }
}
