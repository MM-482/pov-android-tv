package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

internal class RealDebridProvider(private val http: ProviderTransport,
    private val waiting: ProviderPreparationWait = ProviderPreparationWait()) : ProviderBackend {
    private fun call(token: String, path: String, fields: List<Pair<String, String>>? = null): String =
        http.request("$BASE$path", if (fields == null) "GET" else "POST", mapOf("Authorization" to "Bearer $token"), fields)
    override fun account(token: String, username: String): ProviderAccount {
        val user = providerObject(call(token, "/user"))
        if (!user.has("id") || user.has("error")) throw ApiException(401, "Real-Debrid did not verify this account.")
        return ProviderAccount("realdebrid", user.providerString("username").ifBlank { "Real-Debrid account" },
            user.providerString("type") == "premium", user.providerString("expiration"))
    }
    override fun cloud(token: String, folderId: String): List<ProviderFolder> {
        if (folderId == "downloads") return downloads(token).map { row ->
            val file = downloadFile(row)
            ProviderFolder("realdebrid", file.id, file.name, true, listOf(file))
        }
        require(folderId.isBlank()) { "Choose a Real-Debrid transfer or generated-download folder." }
        val result = mutableListOf<ProviderFolder>()
        for (page in 1..20) {
            val values = providerArray(call(token, "/torrents?limit=500&page=$page")).providerObjects()
            result += values.map { ProviderFolder("realdebrid", it.providerString("id"),
                it.providerString("filename"), it.providerString("status") == "downloaded") }
            if (values.size < 500) return result + ProviderFolder("realdebrid", "downloads", "Generated downloads", true, directory = true)
        }
        throw ApiException(0, "This Real-Debrid library is too large to load at once.")
    }
    override fun files(token: String, folderId: String): List<ProviderFile> {
        if (folderId.startsWith("download:")) return listOf(downloadFile(exactDownload(token, folderId)))
        val value = info(token, folderId)
        return if (value.providerString("status") == "waiting_files_selection") allFiles(value, folderId) else parseFiles(value, folderId)
    }
    private fun downloads(token: String): List<JSONObject> {
        val result = linkedMapOf<String, JSONObject>()
        for (page in 1..100) {
            val rows = providerArray(call(token, "/downloads?limit=100&page=$page")).providerObjects()
            val before = result.size
            rows.forEach { row ->
                val id = row.providerString("id")
                if (id.matches(Regex("[A-Za-z0-9_-]{1,256}"))) result[id] = row
            }
            if (rows.size < 100 || result.size == before) return result.values.toList()
        }
        throw ApiException(0, "This generated-download history is too large to load at once. Manage older entries in Real-Debrid.")
    }
    private fun exactDownload(token: String, fileId: String): JSONObject {
        val id = fileId.removePrefix("download:")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "Choose an exact generated-download identity." }
        return downloads(token).singleOrNull { it.providerString("id") == id }
            ?: throw ApiException(404, "This generated Real-Debrid download is no longer available.")
    }
    private fun downloadFile(row: JSONObject) = ProviderFile("download:${row.providerString("id")}",
        row.providerString("filename").ifBlank { "Generated download ${row.providerString("id")}" }, row.optLong("filesize"))
    private fun allFiles(info: JSONObject, torrentId: String) = (info.optJSONArray("files") ?: JSONArray()).providerObjects().map { file ->
        ProviderFile("$torrentId:${file.providerString("id")}", file.providerString("path"), file.optLong("bytes"))
    }
    internal fun parseFiles(info: JSONObject, torrentId: String = ""): List<ProviderFile> {
        val links = info.optJSONArray("links") ?: JSONArray()
        var selectedIndex = 0
        return (info.optJSONArray("files") ?: JSONArray()).providerObjects().mapNotNull {
            if (it.optInt("selected") != 1) return@mapNotNull null
            val link = links.optString(selectedIndex++)
            ProviderFile(if (torrentId.isBlank()) it.providerString("id") else "$torrentId:${it.providerString("id")}",
                it.providerString("path"), it.optLong("bytes"), link)
        }
    }
    private fun info(token: String, id: String): JSONObject {
        val result = providerObject(call(token, "/torrents/info/${Net.encode(id)}"))
        check(result.providerString("id") == id) { "Real-Debrid returned a different transfer. Browse the folder again." }
        return result
    }
    override fun playback(token: String, file: ProviderFile): ProviderPlayback {
        if (file.id.startsWith("download:")) {
            val current = exactDownload(token, file.id)
            val restricted = current.providerString("link")
            if (restricted.isBlank()) return ProviderPlayback(providerHttps(current.providerString("download")))
            val refreshed = providerObject(call(token, "/unrestrict/link", listOf("link" to providerHttps(restricted))))
            return ProviderPlayback(providerHttps(refreshed.providerString("download")))
        }
        val parts = file.id.split(':')
        require(parts.size == 2 && parts[0].matches(Regex("[A-Za-z0-9_-]{1,256}")) && parts[1].matches(Regex("[0-9]{1,12}"))) {
            "This Real-Debrid file has no valid transfer identity."
        }
        val initial = info(token, parts[0])
        val chosen = (initial.optJSONArray("files") ?: JSONArray()).providerObjects().singleOrNull { it.providerString("id") == parts[1] }
            ?: throw ApiException(404, "This exact Real-Debrid file is no longer available.")
        if (chosen.optInt("selected") != 1) {
            check(initial.providerString("status") == "waiting_files_selection") {
                "This file was not selected in Real-Debrid. Choose an already-selected file from the transfer."
            }
            // Selection is a separate explicit file action, and is performed at most once.
            call(token, "/torrents/selectFiles/${Net.encode(parts[0])}", listOf("files" to parts[1]))
        }
        var first: JSONObject? = initial.takeIf { chosen.optInt("selected") == 1 }
        val link = waiting.await("Real-Debrid", parts[0]) {
            val value = first?.also { first = null } ?: info(token, parts[0])
            if (value.providerString("status") in setOf("error", "magnet_error", "virus", "dead"))
                throw ApiException(0, "Real-Debrid could not process the selected file.")
            if (value.providerString("status") != "downloaded") null else {
                val selected = (value.optJSONArray("files") ?: JSONArray()).providerObjects().filter { it.optInt("selected") == 1 }
                val links = value.optJSONArray("links") ?: JSONArray()
                val index = selected.indexOfFirst { it.providerString("id") == parts[1] }
                check(index >= 0 && selected.count { it.providerString("id") == parts[1] } == 1 && links.length() == selected.size) {
                    "Real-Debrid did not confirm an exact link for the selected file. Browse the folder again."
                }
                links.optString(index).takeIf { it.isNotBlank() && it != "null" }
            }
        }
        providerHttps(link)
        val response = providerObject(call(token, "/unrestrict/link", listOf("link" to link)))
        return ProviderPlayback(providerHttps(response.providerString("download")))
    }
    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        val added = providerObject(call(token, "/torrents/addMagnet", listOf("magnet" to providerHash(source.infoHash))))
        val id = added.providerString("id").takeIf { it.isNotBlank() }
            ?: throw ApiException(0, "Real-Debrid could not add this source.")
        return waiting.await("Real-Debrid", id) {
            val status = info(token, id)
            val state = status.providerString("status")
            if (state == "waiting_files_selection") {
                return@await providerPrepared(allFiles(status, id), source, identity, id)
            }
            if (state == "downloaded") return@await providerPrepared(parseFiles(status, id), source, identity, id)
            if (state in setOf("error", "magnet_error", "virus", "dead")) throw ApiException(0, "Real-Debrid could not process this source.")
            null
        }
    }
    companion object { private const val BASE = "https://api.real-debrid.com/rest/1.0" }
}
