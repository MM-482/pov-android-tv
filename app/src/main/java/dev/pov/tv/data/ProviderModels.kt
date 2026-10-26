package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class ProviderSpec(val id: String, val label: String, val credentialLabel: String, val credentialUrl: String,
    val cloud: Boolean, val magnet: Boolean, val experimental: Boolean = false)
data class ProviderAccount(val provider: String, val label: String, val premium: Boolean, val detail: String = "")
data class ProviderFile(val id: String, val name: String, val size: Long = 0, val link: String = "", val direct: Boolean = false)
data class ProviderFolder(val provider: String, val id: String, val name: String, val ready: Boolean,
    val files: List<ProviderFile> = emptyList(), val directory: Boolean = false)
data class ProviderPlayback(val url: String, val headers: Map<String, String> = emptyMap())
data class ProviderPreparedSource(val playback: ProviderPlayback? = null, val files: List<ProviderFile> = emptyList(),
    val preferredFileId: String? = null, val folderId: String = "") {
    val automaticFile: ProviderFile? get() = files.singleOrNull()
}

/** Injectable transport and credentials permit account-free API contract tests. */
interface ProviderTransport {
    fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
        fields: List<Pair<String, String>>? = null, json: JSONObject? = null): String
}
object LiveProviderTransport : ProviderTransport {
    private val cooldowns = mutableMapOf<String, Long>()
    override fun request(url: String, method: String, headers: Map<String, String>, fields: List<Pair<String, String>>?, json: JSONObject?): String {
        val host = try { URI(url).host.orEmpty() } catch (_: Exception) { "" }
        val remaining = synchronized(cooldowns) { (cooldowns[host] ?: 0) - System.nanoTime() }
        if (remaining > 0) throw ApiException(429, "This provider is rate limited. Please wait before trying again.",
            retryAfterSeconds = ((remaining + 999_999_999) / 1_000_000_000).toInt())
        try {
            return if (fields != null) Net.form(url, fields, headers) else Net.raw(url, method, headers, json)
        } catch (error: ApiException) {
            if (error.code == 429) synchronized(cooldowns) {
                cooldowns[host] = System.nanoTime() + maxOf(5, error.retryAfterSeconds).toLong() * 1_000_000_000
            }
            throw error
        }
    }
}
interface ProviderCredentials {
    fun get(key: String): String
    fun putAll(values: Map<String, String>)
}
internal class EncryptedProviderCredentials(private val store: SecureStore) : ProviderCredentials {
    override fun get(key: String) = store.get(key)
    override fun putAll(values: Map<String, String>) = store.putAll(values)
}

internal fun providerObject(raw: String): JSONObject = try { JSONObject(raw) } catch (_: Exception) {
    throw ApiException(0, "The provider returned an unreadable response.")
}
internal fun providerArray(raw: String): JSONArray = try { JSONArray(raw) } catch (_: Exception) {
    throw ApiException(0, "The provider returned an unreadable response.")
}
internal fun JSONObject.providerString(key: String): String = optString(key).takeUnless { it == "null" }.orEmpty()
internal fun providerHttps(value: String): String {
    val uri = try { URI(value) } catch (_: Exception) { null }
    if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
        throw ApiException(0, "The provider did not return a secure playback link.")
    }
    return value
}
internal fun providerHash(hash: String): String {
    require(hash.matches(Regex("[a-fA-F0-9]{40}"))) { "This source has no supported torrent identifier." }
    return "magnet:?xt=urn:btih:${hash.lowercase()}"
}
internal fun providerPick(files: List<ProviderFile>, title: String, identity: PlaybackIdentity): ProviderFile {
    val chosen = EpisodeFilename.select(files.mapIndexed { index, file -> VideoCandidate(index.toLong(), file.name, file.size) }, title, identity)
    return files[chosen.id.toInt()]
}
internal fun providerPrepared(files: List<ProviderFile>, source: SourceItem, identity: PlaybackIdentity, folderId: String = ""): ProviderPreparedSource {
    val candidates = files.mapIndexed { index, file -> VideoCandidate(index.toLong(), file.name, file.size) }
    val eligible = EpisodeFilename.choices(candidates, source.title, identity)
    val choices = eligible.map { files[it.id.toInt()] }
    val preferred = EpisodeFilename.preferredId(eligible, source.title)?.let { files[it.toInt()].id }
    return ProviderPreparedSource(files = choices, preferredFileId = preferred ?: choices.singleOrNull()?.id, folderId = folderId)
}
internal fun JSONArray.providerObjects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

internal interface ProviderBackend {
    fun account(token: String, username: String): ProviderAccount
    fun cloud(token: String, folderId: String): List<ProviderFolder>
    fun files(token: String, folderId: String): List<ProviderFile>
    fun playback(token: String, file: ProviderFile): ProviderPlayback
    fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource
    fun resolve(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPlayback {
        val value = prepare(token, source, identity)
        value.playback?.let { return it }
        val file = value.automaticFile ?: throw ApiException(0, "This source has multiple video versions. Choose a file to play.")
        return playback(token, file)
    }
}
