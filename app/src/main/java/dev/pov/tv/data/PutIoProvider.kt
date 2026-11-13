package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** put.io API v2, matching its official go-putio account/files/transfers contracts. */
internal class PutIoProvider(
    private val http: ProviderTransport,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val maximumAttempts: Int = 12
) : ProviderBackend {
    private fun call(token: String, path: String, fields: List<Pair<String, String>>? = null, json: JSONObject? = null): JSONObject {
        interrupted()
        val value = providerObject(http.request("$BASE$path", if (fields == null && json == null) "GET" else "POST",
            mapOf("Authorization" to "Bearer $token"), fields, json))
        if (value.providerString("status") != "OK") {
            val reason = value.providerString("error_type").lowercase(Locale.ROOT)
            throw ApiException(if ("auth" in reason || "token" in reason) 401 else 0,
                "put.io could not complete this request. Check the account and available storage.")
        }
        return value
    }

    override fun account(token: String, username: String): ProviderAccount {
        val info = call(token, "/account/info").optJSONObject("info")
            ?: throw ApiException(401, "put.io did not verify this account.")
        numeric(info.providerString("user_id"))
        return ProviderAccount("putio", info.providerString("username").ifBlank { "put.io account" },
            info.optBoolean("account_active"), info.providerString("plan_expiration_date"))
    }

    override fun cloud(token: String, folderId: String): List<ProviderFolder> {
        if (folderId == TRANSFERS) return transfers(token)
        if (folderId.startsWith("transfer:")) {
            val file = transferRoot(token, numeric(folderId.removePrefix("transfer:")))
            return if (isFolder(file)) listFolder(token, numeric(file.providerString("id"))) else listOf(asFolder(file))
        }
        val parentId = if (folderId.isBlank()) "0" else numeric(folderId, allowZero = true)
        return listFolder(token, parentId) + if (parentId == "0") listOf(
            ProviderFolder("putio", TRANSFERS, "Active transfers", true, directory = true)) else emptyList()
    }

    override fun files(token: String, folderId: String): List<ProviderFile> = when {
        folderId.startsWith("file:") -> listOf(asFile(file(token, numeric(folderId.removePrefix("file:")))))
        folderId.startsWith("transfer:") -> {
            val root = transferRoot(token, numeric(folderId.removePrefix("transfer:")))
            if (isFolder(root)) collectFiles(token, numeric(root.providerString("id"))) else listOf(asFile(root))
        }
        else -> listFolder(token, numeric(folderId, allowZero = true)).flatMap { it.files }
    }

    override fun playback(token: String, file: ProviderFile): ProviderPlayback {
        val id = numeric(file.id)
        // A fresh scoped URL is requested for the exact selected file every time it is played/resumed.
        return ProviderPlayback(providerHttps(call(token, "/files/$id/url?notunnel=0").providerString("url")))
    }

    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        SourceIdentityPolicy.selected(source, identity)
        val magnet = providerHash(source.infoHash)
        val created = call(token, "/transfers/add", listOf("url" to magnet)).optJSONObject("transfer")
            ?: throw ApiException(0, "put.io did not confirm the new transfer.")
        val transferId = numeric(created.providerString("id"))
        repeat(maximumAttempts.coerceIn(1, 40)) { attempt ->
            interrupted()
            val root = try { transferRoot(token, transferId) } catch (error: ApiException) {
                if (error.errorKey == "PUTIO_PROCESSING" || error.code == 404) null else throw error
            }
            if (root != null) {
                val choices = if (isFolder(root)) collectFiles(token, numeric(root.providerString("id"))) else listOf(asFile(root))
                if (choices.isNotEmpty()) return providerPrepared(choices, source, identity, "transfer:$transferId")
            }
            if (attempt + 1 < maximumAttempts.coerceIn(1, 40)) pause(1500)
        }
        throw processing()
    }

    private fun transfers(token: String): List<ProviderFolder> {
        val values = call(token, "/transfers/list").optJSONArray("transfers")
            ?: throw ApiException(0, "put.io returned an unreadable transfer list.")
        check(values.length() <= MAX_ITEMS) { "This put.io transfer list is too large to load." }
        return values.providerObjects().map { value ->
            ProviderFolder("putio", "transfer:${numeric(value.providerString("id"))}", name(value),
                value.providerString("status") == "COMPLETED")
        }.distinctBy { it.id }
    }

    private fun transferRoot(token: String, id: String): JSONObject {
        val transfer = call(token, "/transfers/$id").optJSONObject("transfer")
            ?: throw ApiException(0, "put.io returned unreadable transfer information.")
        check(transfer.providerString("id") == id) { "put.io returned a different transfer. Reopen the selected transfer." }
        if (transfer.providerString("status").uppercase(Locale.ROOT) in setOf("ERROR", "FAILED", "CANCELLED", "CANCELED")) {
            throw ApiException(0, "put.io could not process this source. Check the selected transfer in your account.")
        }
        val fileId = transfer.providerString("file_id")
        if (fileId.isBlank() || fileId == "0") throw processing()
        return file(token, numeric(fileId))
    }

    private fun file(token: String, id: String): JSONObject {
        val file = call(token, "/files/$id").optJSONObject("file")
            ?: throw ApiException(0, "put.io returned unreadable file information.")
        check(file.providerString("id") == id) { "put.io returned a different file. Reopen the selected file." }
        return file
    }

    private fun listFolder(token: String, id: String): List<ProviderFolder> {
        var response = call(token, "/files/list?parent_id=$id&per_page=1000")
        val result = mutableListOf<ProviderFolder>(); val cursors = mutableSetOf<String>()
        repeat(20) {
            val files = response.optJSONArray("files") ?: throw ApiException(0, "put.io returned an unreadable folder.")
            result += files.providerObjects().map(::asFolder)
            check(result.size <= MAX_ITEMS) { "This put.io folder is too large to load at once." }
            val cursor = response.providerString("cursor")
            if (cursor.isBlank()) return result.distinctBy { it.id }
            check(cursor.length <= 4096 && cursors.add(cursor)) { "put.io returned a repeated or invalid library page." }
            response = call(token, "/files/list/continue", json = JSONObject().put("cursor", cursor))
        }
        throw ApiException(0, "This put.io folder has too many pages to load at once.")
    }

    private fun collectFiles(token: String, rootId: String): List<ProviderFile> {
        val output = mutableListOf<ProviderFile>(); val seen = mutableSetOf<String>()
        fun scan(folderId: String, depth: Int, prefix: String) {
            interrupted()
            check(depth <= 8 && seen.add(folderId)) { "This put.io transfer has a repeated or overly deep folder structure." }
            listFolder(token, folderId).forEach { folder ->
                if (folder.directory) scan(folder.id, depth + 1, "$prefix${folder.name}/")
                else output += folder.files.map { it.copy(name = prefix + it.name) }
                check(output.size <= MAX_ITEMS && seen.size <= 1000) { "This put.io transfer has too many files to load at once." }
            }
        }
        scan(rootId, 0, "")
        return output.distinctBy { it.id }
    }

    private fun asFolder(value: JSONObject): ProviderFolder {
        val id = numeric(value.providerString("id"))
        return if (isFolder(value)) ProviderFolder("putio", id, name(value), true, directory = true)
        else ProviderFolder("putio", "file:$id", name(value), true, listOf(asFile(value)))
    }
    private fun asFile(value: JSONObject): ProviderFile {
        require(!isFolder(value)) { "Choose a put.io video file rather than a folder." }
        return ProviderFile(numeric(value.providerString("id")), name(value), value.optLong("size").coerceAtLeast(0))
    }
    private fun isFolder(value: JSONObject) = value.providerString("file_type") == "FOLDER" || value.providerString("content_type") == "application/x-directory"
    private fun name(value: JSONObject) = value.providerString("name").replace('\u0000', ' ').trim().take(1000).ifBlank { "put.io file" }
    private fun numeric(value: String, allowZero: Boolean = false) = value.also {
        require((if (allowZero) Regex("0|[1-9][0-9]{0,18}") else Regex("[1-9][0-9]{0,18}")).matches(it) && it.toLongOrNull() != null) {
            "put.io returned an invalid file or transfer identity."
        }
    }
    private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("put.io preparation was cancelled.") }
    private fun processing() = ApiException(0, "This source is still processing in put.io. The transfer remains in your account; open it there when ready.", "PUTIO_PROCESSING")

    companion object {
        private const val BASE = "https://api.put.io/v2"
        private const val TRANSFERS = "category:transfers"
        private const val MAX_ITEMS = 10_000
    }
}
