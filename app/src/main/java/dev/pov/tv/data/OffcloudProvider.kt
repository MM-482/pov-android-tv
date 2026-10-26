package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset

/** Official query-key authentication is isolated to the pinned API host; redirects stay disabled. */
internal class OffcloudProvider(private val http: ProviderTransport) : ProviderBackend {
    private fun raw(token: String, path: String, json: JSONObject? = null): String = http.request(
        "$BASE$path${if ('?' in path) '&' else '?'}key=${Net.encode(token)}", if (json == null) "GET" else "POST", json = json)
    private fun checked(raw: String): JSONObject {
        val value = providerObject(raw)
        if (value.has("error") || value.has("not_available")) throw ApiException(0, "Offcloud could not complete this request. Check your account and available quota.")
        return value
    }
    override fun account(token: String, username: String): ProviderAccount {
        val value = checked(raw(token, "/account/info"))
        val expiration = value.providerString("expiration_date")
        if (expiration.isBlank() && !value.has("email") && !value.has("userId")) throw ApiException(401, "Offcloud did not verify this account.")
        val premium = try { LocalDate.parse(expiration).isAfter(LocalDate.now(ZoneOffset.UTC).minusDays(1)) } catch (_: Exception) { false }
        return ProviderAccount("offcloud", value.providerString("email").ifBlank { "Offcloud account" }, premium, expiration)
    }
    override fun cloud(token: String, folderId: String): List<ProviderFolder> = providerArray(raw(token, "/cloud/history")).providerObjects().map {
        ProviderFolder("offcloud", it.providerString("requestId"), it.providerString("fileName"), it.providerString("status") == "downloaded")
    }
    override fun files(token: String, folderId: String): List<ProviderFile> {
        val array = providerArray(raw(token, "/cloud/explore/${Net.encode(folderId)}?format=detailed"))
        return (0 until array.length()).mapNotNull { index ->
            val value = array.optJSONObject(index)
            val link = if (value == null) array.optString(index) else value.providerString("url").ifBlank { value.providerString("link") }
            if (link.isBlank()) return@mapNotNull null
            val name = value?.providerString("filename")?.ifBlank { value.providerString("fileName") }.orEmpty()
                .ifBlank { try { URI(link).path.substringAfterLast('/') } catch (_: Exception) { "File" } }
            ProviderFile(index.toString(), name, value?.optLong("size") ?: 0, providerHttps(link))
        }
    }
    override fun playback(token: String, file: ProviderFile) = ProviderPlayback(providerHttps(file.link))
    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        // The current POV adapter uses cache/download to obtain ready file URLs without creating
        // a permanent cloud transfer. No transfer is created just to discover cache availability.
        val array = providerArray(raw(token, "/cache/download", JSONObject().put("url", providerHash(source.infoHash))))
        val files = array.providerObjects().mapIndexed { index, item -> ProviderFile(index.toString(),
            item.providerString("filename"), item.optLong("size"), item.providerString("url")) }
        return providerPrepared(files, source, identity)
    }
    companion object { private const val BASE = "https://offcloud.com/api" }
}
