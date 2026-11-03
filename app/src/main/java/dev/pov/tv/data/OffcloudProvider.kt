package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Official query-key authentication is isolated to the pinned API host; redirects stay disabled. */
internal class OffcloudProvider(private val http: ProviderTransport,
    private val waiting: ProviderPreparationWait = ProviderPreparationWait()) : ProviderBackend {
    private fun raw(token: String, path: String, fields: List<Pair<String, String>>? = null): String = http.request(
        "$BASE$path${if ('?' in path) '&' else '?'}key=${Net.encode(token)}", if (fields == null) "GET" else "POST", fields = fields)
    private fun checked(raw: String): JSONObject {
        val value = providerObject(raw)
        if (value.has("error") || value.has("not_available")) throw ApiException(0, "Offcloud could not complete this request. Check your account and available quota.")
        return value
    }
    override fun account(token: String, username: String): ProviderAccount {
        // The documented authenticated endpoint returns an array, including an empty valid list.
        providerArray(raw(token, "/remote/accounts", emptyList()))
        return ProviderAccount("offcloud", "Offcloud API account", false, "Account verified; plan availability depends on the selected source.")
    }
    override fun cloud(token: String, folderId: String): List<ProviderFolder> {
        require(folderId.isNotBlank()) { "Offcloud does not provide an account-wide library. Enter an existing cloud request ID to open its files." }
        return listOf(ProviderFolder("offcloud", folderId, "Offcloud request $folderId", true))
    }
    override fun files(token: String, folderId: String): List<ProviderFile> {
        require(folderId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}"))) { "Enter a valid Offcloud request ID." }
        val array = providerArray(raw(token, "/cloud/explore/${Net.encode(folderId)}?format=detailed"))
        return (0 until array.length()).mapNotNull { index ->
            val value = array.optJSONObject(index)
            val link = if (value == null) array.optString(index) else value.providerString("url").ifBlank { value.providerString("link") }
            if (link.isBlank()) return@mapNotNull null
            val name = value?.providerString("filename")?.ifBlank { value.providerString("fileName") }?.ifBlank { value.providerString("name") }.orEmpty()
                .ifBlank { try { URI(link).path.substringAfterLast('/') } catch (_: Exception) { "File" } }
            ProviderFile("$folderId:$index", name, value?.optLong("size") ?: 0, providerHttps(link))
        }
    }
    override fun playback(token: String, file: ProviderFile): ProviderPlayback {
        val request = file.id.substringBeforeLast(':', "")
        require(request.isNotBlank()) { "Reopen the Offcloud request and select its file." }
        val candidates = files(token, request)
        val fresh = candidates.singleOrNull { it.id == file.id && it.name == file.name }
            ?: throw ApiException(404, "This exact Offcloud file changed or is unavailable.")
        check(candidates.count { it.name == file.name } == 1) { "This Offcloud filename is ambiguous. Choose a request with one exact matching file." }
        return ProviderPlayback(providerHttps(fresh.link))
    }
    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        // This is called only after the user explicitly selects this source/account.
        val created = checked(raw(token, "/cloud", listOf("url" to providerHash(source.infoHash))))
        val id = created.providerString("requestId")
        check(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}"))) { "Offcloud did not identify the created request. Check the service before retrying." }
        val files = waiting.await("Offcloud", id) { files(token, id).takeIf { it.isNotEmpty() } }
        return providerPrepared(files, source, identity, id)
    }
    companion object { private const val BASE = "https://offcloud.com/api" }
}
