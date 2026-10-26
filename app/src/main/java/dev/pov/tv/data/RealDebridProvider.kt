package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

internal class RealDebridProvider(private val http: ProviderTransport) : ProviderBackend {
    private fun call(token: String, path: String, fields: List<Pair<String, String>>? = null): String =
        http.request("$BASE$path", if (fields == null) "GET" else "POST", mapOf("Authorization" to "Bearer $token"), fields)
    override fun account(token: String, username: String): ProviderAccount {
        val user = providerObject(call(token, "/user"))
        if (!user.has("id") || user.has("error")) throw ApiException(401, "Real-Debrid did not verify this account.")
        return ProviderAccount("realdebrid", user.providerString("username").ifBlank { "Real-Debrid account" },
            user.providerString("type") == "premium", user.providerString("expiration"))
    }
    override fun cloud(token: String, folderId: String): List<ProviderFolder> {
        val result = mutableListOf<ProviderFolder>()
        for (page in 1..20) {
            val values = providerArray(call(token, "/torrents?limit=500&page=$page")).providerObjects()
            result += values.map { ProviderFolder("realdebrid", it.providerString("id"),
                it.providerString("filename"), it.providerString("status") == "downloaded") }
            if (values.size < 500) return result
        }
        throw ApiException(0, "This Real-Debrid library is too large to load at once.")
    }
    override fun files(token: String, folderId: String): List<ProviderFile> = parseFiles(info(token, folderId), folderId)
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
    private fun info(token: String, id: String): JSONObject = providerObject(call(token, "/torrents/info/${Net.encode(id)}"))
    override fun playback(token: String, file: ProviderFile): ProviderPlayback {
        var link = file.link
        if (link.isBlank()) {
            val parts = file.id.split(':')
            require(parts.size == 2) { "This Real-Debrid file has no valid transfer identity." }
            // Only the explicitly chosen file is selected for a new cloud transfer.
            call(token, "/torrents/selectFiles/${Net.encode(parts[0])}", listOf("files" to parts[1]))
            for (attempt in 0 until 12) {
                val value = info(token, parts[0])
                if (value.providerString("status") == "downloaded") {
                    link = parseFiles(value, parts[0]).firstOrNull { it.id == file.id }?.link.orEmpty()
                    if (link.isBlank()) throw ApiException(0, "Real-Debrid did not confirm the selected file. Choose it again from your cloud.")
                    break
                }
                if (value.providerString("status") in setOf("error", "magnet_error", "virus", "dead")) {
                    throw ApiException(0, "Real-Debrid could not process the selected file.")
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (link.isBlank()) Thread.sleep(1500)
            }
            if (link.isBlank()) throw ApiException(0, "The selected file is still processing in Real-Debrid. It remains in your cloud.")
        }
        providerHttps(link)
        val response = providerObject(call(token, "/unrestrict/link", listOf("link" to link)))
        return ProviderPlayback(providerHttps(response.providerString("download")))
    }
    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        val added = providerObject(call(token, "/torrents/addMagnet", listOf("magnet" to providerHash(source.infoHash))))
        val id = added.providerString("id").takeIf { it.isNotBlank() }
            ?: throw ApiException(0, "Real-Debrid could not add this source.")
        repeat(12) {
            val status = info(token, id)
            val state = status.providerString("status")
            if (state == "waiting_files_selection") {
                val choices = (status.optJSONArray("files") ?: JSONArray()).providerObjects().map {
                    ProviderFile("$id:${it.providerString("id")}", it.providerString("path"), it.optLong("bytes"))
                }
                return providerPrepared(choices, source, identity, id)
            }
            if (state == "downloaded") return providerPrepared(parseFiles(status, id), source, identity, id)
            if (state in setOf("error", "magnet_error", "virus", "dead")) throw ApiException(0, "Real-Debrid could not process this source.")
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            Thread.sleep(1500)
        }
        throw ApiException(0, "This source is still processing in Real-Debrid. It remains in your cloud; try it there when ready.")
    }
    companion object { private const val BASE = "https://api.real-debrid.com/rest/1.0" }
}
