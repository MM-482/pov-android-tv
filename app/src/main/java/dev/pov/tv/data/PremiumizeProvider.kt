package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

internal class PremiumizeProvider(private val http: ProviderTransport,
    private val waiting: ProviderPreparationWait = ProviderPreparationWait()) : ProviderBackend {
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
        if (folderId == "category:transfers") return transfers(token).map { transfer ->
            ProviderFolder("premiumize", "transfer:${transfer.providerString("id")}",
                "${transfer.providerString("name")} · ${transfer.providerString("status")}",
                transfer.providerString("status") in setOf("finished", "seeding"), directory = true)
        }
        if (folderId.startsWith("transfer:")) {
            val transfer = transfers(token).singleOrNull { it.providerString("id") == folderId.removePrefix("transfer:") }
                ?: throw ApiException(404, "This Premiumize transfer is no longer available.")
            check(transfer.providerString("status") !in setOf("error", "failed", "deleted")) { "Premiumize could not prepare this transfer. Check the account before selecting another source." }
            val fileId = transfer.providerString("file_id").takeIf { it.isNotBlank() && it != "0" }
            val directoryId = transfer.providerString("folder_id").takeIf { it.isNotBlank() && it != "0" }
            return when {
                fileId != null -> {
                    val item = details(token, fileId)
                    if (item.providerString("type") == "folder") cloud(token, fileId)
                    else listOf(ProviderFolder("premiumize", "file:$fileId", item.providerString("name"), true, listOf(parseFile(item))))
                }
                directoryId != null -> cloud(token, directoryId)
                else -> emptyList()
            }
        }
        val content = call(token, "/folder/list" + if (folderId.isBlank()) "" else "?id=${Net.encode(folderId)}")
            .optJSONArray("content") ?: JSONArray()
        return content.providerObjects().map { item ->
            val directory = item.providerString("type") == "folder"
            ProviderFolder("premiumize", if (directory) item.providerString("id") else "file:${item.providerString("id")}",
                item.providerString("name"), true, if (directory) emptyList() else listOf(parseFile(item)), directory)
        } + if (folderId.isBlank()) listOf(ProviderFolder("premiumize", "category:transfers", "Transfers", true, directory = true)) else emptyList()
    }
    private fun transfers(token: String) = (call(token, "/transfer/list").optJSONArray("transfers") ?: JSONArray()).providerObjects()
        .filter { it.providerString("id").isNotBlank() }
    private fun details(token: String, id: String): JSONObject = call(token, "/item/details?id=${Net.encode(id)}").also {
        check(it.providerString("id") == id) { "Premiumize returned a different file. Refresh this folder." }
    }
    override fun files(token: String, folderId: String): List<ProviderFile> {
        if (folderId.startsWith("file:")) return listOf(parseFile(details(token, folderId.removePrefix("file:"))))
        val results = mutableListOf<ProviderFile>(); val seen = mutableSetOf<String>()
        fun descend(id: String, prefix: String, depth: Int) {
            check(depth <= 8 && seen.add(id)) { "This Premiumize folder is too deep or contains a cycle. Browse an exact subfolder." }
            cloud(token, id).filter { it.id != "category:transfers" }.forEach { item ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Preparation canceled.")
                if (item.directory) descend(item.id, "$prefix${item.name}/", depth + 1)
                else results += item.files.map { it.copy(name = prefix + it.name) }
                check(results.size <= 1000) { "This Premiumize folder contains too many files. Browse a smaller subfolder." }
            }
        }
        descend(folderId, "", 0)
        return results
    }
    private fun parseFile(item: JSONObject): ProviderFile = ProviderFile(item.providerString("id"), item.providerString("name"),
        item.optLong("size"), item.providerString("link"))
    override fun playback(token: String, file: ProviderFile): ProviderPlayback {
        if (file.direct) return ProviderPlayback(providerHttps(file.link))
        val link = waiting.await("Premiumize", file.id) { details(token, file.id).providerString("link").takeIf { it.isNotBlank() } }
        return ProviderPlayback(providerHttps(link))
    }
    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        val data = call(token, "/transfer/directdl", listOf("src" to providerHash(source.infoHash)))
        val content = data.optJSONArray("content") ?: throw ApiException(0, "Premiumize did not return playable files for this source.")
        val files = content.providerObjects().mapIndexed { index, item -> ProviderFile(index.toString(),
            item.providerString("path"), item.optLong("size"), item.providerString("link"), direct = true) }
        return providerPrepared(files, source, identity)
    }
    override fun prepareTransfer(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        val created = call(token, "/transfer/create", listOf("src" to providerHash(source.infoHash)))
        val transferId = created.providerString("id")
        check(transferId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}"))) { "Premiumize did not identify the transfer. Check its cloud library before retrying." }
        val folder = "transfer:$transferId"
        val files = waiting.await("Premiumize", transferId) { files(token, folder).takeIf { it.isNotEmpty() } }
        return providerPrepared(files, source, identity, folder)
    }
    companion object { private const val BASE = "https://www.premiumize.me/api" }
}
