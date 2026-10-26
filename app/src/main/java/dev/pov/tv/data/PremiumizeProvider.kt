package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

internal class PremiumizeProvider(private val http: ProviderTransport) : ProviderBackend {
    private fun call(token: String, path: String, fields: List<Pair<String, String>>? = null): JSONObject {
        val value = providerObject(http.request("$BASE$path", if (fields == null) "GET" else "POST",
            mapOf("Authorization" to "Bearer $token"), fields))
        if (value.providerString("status") != "success") throw ApiException(
            if (value.providerString("code") == "authentication_failed") 401 else 0,
            "Premiumize could not complete this request. Check the account and its available quota.")
        return value
    }
    override fun account(token: String, username: String): ProviderAccount {
        val user = call(token, "/account/info")
        if (!user.has("customer_id")) throw ApiException(401, "Premiumize did not verify this account.")
        return ProviderAccount("premiumize", "Premiumize ${user.providerString("customer_id")}",
            user.optLong("premium_until") > System.currentTimeMillis() / 1000, user.optLong("premium_until").toString())
    }
    override fun cloud(token: String, folderId: String): List<ProviderFolder> {
        val content = call(token, "/folder/list" + if (folderId.isBlank()) "" else "?id=${Net.encode(folderId)}")
            .optJSONArray("content") ?: JSONArray()
        return content.providerObjects().map { item ->
            val directory = item.providerString("type") == "folder"
            ProviderFolder("premiumize", if (directory) item.providerString("id") else "file:${item.providerString("id")}",
                item.providerString("name"), true, if (directory) emptyList() else listOf(parseFile(item)), directory)
        }
    }
    override fun files(token: String, folderId: String): List<ProviderFile> = if (folderId.startsWith("file:")) {
        listOf(parseFile(call(token, "/item/details?id=${Net.encode(folderId.removePrefix("file:"))}")))
    } else cloud(token, folderId).flatMap { it.files }
    private fun parseFile(item: JSONObject): ProviderFile = ProviderFile(item.providerString("id"), item.providerString("name"),
        item.optLong("size"), item.providerString("link"))
    override fun playback(token: String, file: ProviderFile): ProviderPlayback {
        if (file.direct) return ProviderPlayback(providerHttps(file.link))
        val fresh = call(token, "/item/details?id=${Net.encode(file.id)}")
        return ProviderPlayback(providerHttps(fresh.providerString("link")))
    }
    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        val data = call(token, "/transfer/directdl", listOf("src" to providerHash(source.infoHash)))
        val content = data.optJSONArray("content") ?: throw ApiException(0, "Premiumize did not return playable files for this source.")
        val files = content.providerObjects().mapIndexed { index, item -> ProviderFile(index.toString(),
            item.providerString("path"), item.optLong("size"), item.providerString("link"), direct = true) }
        return providerPrepared(files, source, identity)
    }
    companion object { private const val BASE = "https://www.premiumize.me/api" }
}
