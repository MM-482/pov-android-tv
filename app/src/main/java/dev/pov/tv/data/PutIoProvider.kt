package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

internal class PutIoProvider(private val http: ProviderTransport,
    private val waiting: ProviderPreparationWait = ProviderPreparationWait()) : ProviderBackend {
    private fun call(token: String, path: String, fields: List<Pair<String, String>>? = null): JSONObject {
        val value = providerObject(http.request("$BASE$path", if (fields == null) "GET" else "POST",
            mapOf("Authorization" to "Bearer $token"), fields))
        if (value.providerString("status") != "OK") throw ApiException(0, "put.io could not complete this request. Check the account and file availability.")
        return value
    }
    override fun account(token: String, username: String): ProviderAccount {
        val info = call(token, "/account/info").optJSONObject("info") ?: throw ApiException(401, "put.io did not verify this account.")
        val name = info.providerString("username")
        check(name.isNotBlank()) { "put.io did not identify an account." }
        return ProviderAccount("putio", name, true)
    }
    private fun isFolder(item: JSONObject) = item.providerString("file_type") == "FOLDER" || item.providerString("content_type") == "application/x-directory"
    private fun parse(item: JSONObject) = ProviderFile(item.providerString("id"), item.providerString("name"), item.optLong("size"))
    override fun cloud(token: String, folderId: String): List<ProviderFolder> {
        if (folderId == "category:transfers") return (call(token, "/transfers/list").optJSONArray("transfers") ?: JSONArray())
            .providerObjects().filter { numericId(it.providerString("id")) }.map { transfer ->
                ProviderFolder("putio", "transfer:${transfer.providerString("id")}",
                    "${transfer.providerString("name")} · ${transfer.providerString("status")}",
                    numericId(transfer.providerString("file_id")), directory = true)
            }
        if (folderId.startsWith("transfer:")) {
            val transfer = transfer(token, folderId.removePrefix("transfer:"))
            val fileId = transfer.providerString("file_id")
            if (!numericId(fileId)) return emptyList()
            val file = exactFile(token, fileId)
            return if (isFolder(file)) cloud(token, fileId) else listOf(ProviderFolder("putio", fileId,
                file.providerString("name"), true, listOf(parse(file))))
        }
        require(folderId.isBlank() || folderId == "0" || numericId(folderId)) { "Choose a valid put.io folder." }
        val files = call(token, "/files/list?parent_id=${Net.encode(folderId.ifBlank { "0" })}").optJSONArray("files") ?: JSONArray()
        return files.providerObjects().filter { numericId(it.providerString("id")) }.map { item ->
            val folder = isFolder(item)
            ProviderFolder("putio", item.providerString("id"), item.providerString("name"), true,
                if (folder) emptyList() else listOf(parse(item)), directory = folder)
        } + if (folderId.isBlank() || folderId == "0") listOf(ProviderFolder("putio", "category:transfers", "Transfers", true, directory = true)) else emptyList()
    }
    private fun exactFile(token: String, fileId: String): JSONObject {
        require(numericId(fileId)) { "Choose an actual put.io file ID." }
        val file = call(token, "/files/${Net.encode(fileId)}").optJSONObject("file")
            ?: throw ApiException(404, "This put.io item is unavailable.")
        check(file.providerString("id") == fileId) { "put.io returned a different file. Refresh your library." }
        return file
    }
    override fun files(token: String, folderId: String): List<ProviderFile> {
        val root = exactFile(token, folderId)
        if (!isFolder(root)) return listOf(parse(root))
        val result = mutableListOf<ProviderFile>(); val visited = mutableSetOf<String>()
        fun descend(id: String, prefix: String, depth: Int) {
            check(depth <= 8 && visited.add(id)) { "This put.io folder is too deep or contains a cycle. Browse the exact subfolder." }
            cloud(token, id).forEach { item ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Preparation canceled.")
                if (item.directory) descend(item.id, "$prefix${item.name}/", depth + 1)
                else result += item.files.map { it.copy(name = prefix + it.name) }
                check(result.size <= 1000) { "This put.io folder contains too many files. Browse a smaller subfolder." }
            }
        }
        descend(folderId, "", 0)
        return result
    }
    override fun playback(token: String, file: ProviderFile): ProviderPlayback {
        require(numericId(file.id)) { "Choose an actual put.io file ID." }
        return ProviderPlayback(providerHttps(call(token, "/files/${Net.encode(file.id)}/url?notunnel=0").providerString("url")))
    }
    private fun transfer(token: String, transferId: String): JSONObject {
        require(numericId(transferId)) { "Choose a valid put.io transfer ID." }
        val current = call(token, "/transfers/${Net.encode(transferId)}").optJSONObject("transfer")
            ?: throw ApiException(404, "put.io has not returned this transfer yet.")
        check(current.providerString("id") == transferId) { "put.io returned a different transfer." }
        check(current.providerString("status") !in setOf("ERROR", "FAILED") && current.providerString("error_message").isBlank()) {
            "put.io could not prepare this transfer. Check the service account before choosing another source."
        }
        return current
    }
    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        val transfer = call(token, "/transfers/add", listOf("url" to providerHash(source.infoHash))).optJSONObject("transfer")
            ?: throw ApiException(0, "put.io did not return the new transfer.")
        val transferId = transfer.providerString("id")
        check(numericId(transferId)) { "put.io did not identify the transfer. Check your cloud library before retrying." }
        val prepared = waiting.await("put.io", transferId) {
            val fileId = transfer(token, transferId).providerString("file_id")
            if (!numericId(fileId)) null else files(token, fileId).takeIf { it.isNotEmpty() }?.let { fileId to it }
        }
        return providerPrepared(prepared.second, source, identity, prepared.first)
    }
    private fun numericId(value: String) = value.matches(Regex("[1-9][0-9]{0,18}"))
    companion object { private const val BASE = "https://api.put.io/v2" }
}
