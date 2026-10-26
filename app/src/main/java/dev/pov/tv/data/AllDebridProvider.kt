package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

internal class AllDebridProvider(private val http: ProviderTransport) : ProviderBackend {
    private fun call(token: String, path: String, fields: List<Pair<String, String>>? = null): JSONObject {
        val value = providerObject(http.request("$BASE$path", if (fields == null) "GET" else "POST",
            mapOf("Authorization" to "Bearer $token"), fields))
        if (value.providerString("status") != "success") {
            val error = value.optJSONObject("error")?.providerString("code").orEmpty()
            throw ApiException(if (error.startsWith("AUTH_")) 401 else 0,
                if (error.startsWith("AUTH_")) "AllDebrid authorization failed. Reconnect this account." else "AllDebrid could not complete this request.")
        }
        return value.optJSONObject("data") ?: throw ApiException(0, "AllDebrid returned an unreadable response.")
    }
    override fun account(token: String, username: String): ProviderAccount {
        val user = call(token, "/v4/user").optJSONObject("user") ?: throw ApiException(401, "AllDebrid did not verify this account.")
        return ProviderAccount("alldebrid", user.providerString("username").ifBlank { "AllDebrid account" }, user.optBoolean("isPremium"),
            user.optLong("premiumUntil").takeIf { it > 0 }?.toString().orEmpty())
    }
    override fun cloud(token: String, folderId: String): List<ProviderFolder> {
        val magnets = call(token, "/v4.1/magnet/status", emptyList()).optJSONArray("magnets") ?: JSONArray()
        return magnets.providerObjects().map { ProviderFolder("alldebrid", it.providerString("id"),
            it.providerString("filename"), it.optInt("statusCode") == 4) }
    }
    override fun files(token: String, folderId: String): List<ProviderFile> {
        val magnets = call(token, "/v4/magnet/files", listOf("id[]" to folderId)).optJSONArray("magnets") ?: JSONArray()
        val value = magnets.providerObjects().firstOrNull { it.providerString("id") == folderId }
            ?: throw ApiException(404, "This AllDebrid folder is unavailable.")
        if (value.has("error")) throw ApiException(0, "This AllDebrid folder is not ready to play.")
        return flatten(value.optJSONArray("files") ?: JSONArray())
    }
    internal fun flatten(values: JSONArray, prefix: String = ""): List<ProviderFile> = values.providerObjects().flatMap { item ->
        val name = prefix + item.providerString("n")
        val children = item.optJSONArray("e")
        if (children != null) flatten(children, "$name/") else listOf(ProviderFile(item.providerString("l"), name,
            item.optLong("s"), item.providerString("l")))
    }
    override fun playback(token: String, file: ProviderFile): ProviderPlayback =
        ProviderPlayback(providerHttps(call(token, "/v4/link/unlock", listOf("link" to providerHttps(file.link))).providerString("link")))
    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        val added = call(token, "/v4/magnet/upload", listOf("magnets[]" to providerHash(source.infoHash)))
            .optJSONArray("magnets")?.optJSONObject(0)
        val id = added?.providerString("id")?.takeIf { it.isNotBlank() }
            ?: throw ApiException(0, "AllDebrid could not add this source.")
        repeat(12) {
            val data = call(token, "/v4.1/magnet/status", listOf("id" to id)).opt("magnets")
            val status = if (data is JSONObject) data else (data as? JSONArray)?.optJSONObject(0)
            if (status?.optInt("statusCode") == 4) return providerPrepared(files(token, id), source, identity, id)
            if ((status?.optInt("statusCode", 0) ?: 0) >= 5) throw ApiException(0, "AllDebrid could not process this source.")
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            Thread.sleep(1500)
        }
        throw ApiException(0, "This source is still processing in AllDebrid. It remains in your cloud; try it there when ready.")
    }
    companion object { private const val BASE = "https://api.alldebrid.com" }
}
