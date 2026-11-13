package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Official API v2 seedbox/downloader paths; account tokens never enter source-indexer requests. */
internal class DebridLinkProvider(
    private val http: ProviderTransport,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val maximumAttempts: Int = 12
) : ProviderBackend {
    private fun call(token: String, path: String, json: JSONObject? = null): JSONObject {
        interrupted()
        val response = providerObject(http.request("$BASE$path", if (json == null) "GET" else "POST",
            mapOf("Authorization" to "Bearer $token"), json = json))
        if (!response.optBoolean("success")) {
            val reason = response.providerString("error").lowercase(Locale.ROOT)
            val authorization = "token" in reason || "auth" in reason
            val rateLimited = "rate" in reason || "too_many" in reason
            throw ApiException(if (authorization) 401 else if (rateLimited) 429 else 0,
                if (authorization) "Debrid-Link authorization failed. Reconnect this account."
                else "Debrid-Link could not complete this request. Check the account and its allowance.")
        }
        return response
    }

    override fun account(token: String, username: String): ProviderAccount {
        val value = call(token, "/account/infos").optJSONObject("value")
            ?: throw ApiException(401, "Debrid-Link did not verify this account.")
        val name = value.providerString("username").trim()
        if (name.isEmpty()) throw ApiException(401, "Debrid-Link did not verify this account.")
        return ProviderAccount("debridlink", name, value.optInt("accountType") > 0,
            if (value.optLong("premiumLeft") > 0) "Premium account" else "Debrid-Link account")
    }

    override fun cloud(token: String, folderId: String): List<ProviderFolder> = when (folderId) {
        "" -> listOf(
            ProviderFolder("debridlink", SEEDBOX, "Seedbox", true, directory = true),
            ProviderFolder("debridlink", DOWNLOADER, "Generated links", true, directory = true))
        SEEDBOX -> pages(token, "/seedbox/list").map { value ->
            ProviderFolder("debridlink", "seedbox:${opaque(value.providerString("id"))}",
                name(value, "Transfer"), value.optDouble("downloadPercent") >= 100)
        }
        DOWNLOADER -> pages(token, "/downloader/list").map { value ->
            val file = downloadFile(value)
            ProviderFolder("debridlink", file.id, file.name,
                !value.optBoolean("expired") && !value.optBoolean("isProcessing"), listOf(file))
        }
        else -> listOf(ProviderFolder("debridlink", folderId, "Debrid-Link files", true,
            files(token, folderId)))
    }

    override fun files(token: String, folderId: String): List<ProviderFile> = when {
        folderId.startsWith("seedbox:") -> {
            val transferId = opaque(folderId.removePrefix("seedbox:"))
            seedFiles(transfer(token, transferId), transferId)
        }
        folderId.startsWith("download:") -> listOf(downloadFile(download(token, opaque(folderId.removePrefix("download:")))))
        else -> throw ApiException(0, "Choose a Debrid-Link seedbox transfer or generated link.")
    }

    override fun playback(token: String, file: ProviderFile): ProviderPlayback {
        val value = if (file.id.startsWith("seedfile:")) {
            val parts = file.id.split(':')
            require(parts.size == 3) { "This Debrid-Link file has no exact transfer identity." }
            val transferId = opaque(parts[1]); val fileId = opaque(parts[2])
            val transfer = transfer(token, transferId)
            val selected = (transfer.optJSONArray("files") ?: JSONArray()).providerObjects()
                .firstOrNull { it.providerString("id") == fileId }
                ?: throw ApiException(404, "The selected Debrid-Link file is no longer available.")
            val percent = selected.optDouble("downloadPercent", -1.0)
            if (!percent.isFinite() || percent < 100) throw processing()
            selected
        } else if (file.id.startsWith("download:")) {
            val selected = download(token, opaque(file.id.removePrefix("download:")))
            if (selected.optBoolean("isProcessing")) throw processing()
            if (selected.optBoolean("expired")) throw ApiException(0,
                "This Debrid-Link link has expired. Resolve its original link again in Debrid-Link.")
            selected
        } else throw ApiException(0, "Choose an exact Debrid-Link file before playing.")
        // Re-resolve each selected file instead of reusing a URL from an earlier account/library read.
        return ProviderPlayback(providerHttps(value.providerString("downloadUrl")))
    }

    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        SourceIdentityPolicy.selected(source, identity)
        val magnet = providerHash(source.infoHash)
        interrupted()
        val created = call(token, "/seedbox/add", JSONObject().put("url", magnet).put("wait", false).put("structureType", "list"))
            .optJSONObject("value") ?: throw ApiException(0, "Debrid-Link did not confirm the new transfer.")
        val transferId = opaque(created.providerString("id"))
        repeat(maximumAttempts.coerceIn(1, 40)) { attempt ->
            interrupted()
            val value = try { transfer(token, transferId) } catch (error: ApiException) {
                if (error.code == 404) null else throw error
            }
            if (value != null && value.optDouble("downloadPercent") >= 100) {
                val choices = seedFiles(value, transferId)
                if (choices.isNotEmpty()) return providerPrepared(choices, source, identity, "seedbox:$transferId")
            }
            if (attempt + 1 < maximumAttempts.coerceIn(1, 40)) pause(1500)
        }
        throw processing()
    }

    private fun transfer(token: String, id: String): JSONObject {
        val values = call(token, "/seedbox/list?ids=${Net.encode(id)}&structureType=list").optJSONArray("value")
            ?: throw ApiException(0, "Debrid-Link returned unreadable transfer information.")
        return values.providerObjects().firstOrNull { it.providerString("id") == id }
            ?: throw ApiException(404, "The selected Debrid-Link transfer is not available yet.")
    }

    private fun download(token: String, id: String): JSONObject {
        val values = call(token, "/downloader/list?ids=${Net.encode(id)}").optJSONArray("value")
            ?: throw ApiException(0, "Debrid-Link returned unreadable link information.")
        return values.providerObjects().firstOrNull { it.providerString("id") == id }
            ?: throw ApiException(404, "The selected Debrid-Link link is no longer available.")
    }

    private fun seedFiles(value: JSONObject, transferId: String): List<ProviderFile> {
        val files = value.optJSONArray("files") ?: return emptyList()
        check(files.length() <= MAX_ITEMS) { "This Debrid-Link transfer has too many files to load." }
        return files.providerObjects().map { file ->
            ProviderFile("seedfile:$transferId:${opaque(file.providerString("id"))}", name(file, "Video"), file.optLong("size").coerceAtLeast(0))
        }.distinctBy { it.id }
    }

    private fun downloadFile(value: JSONObject) = ProviderFile("download:${opaque(value.providerString("id"))}",
        name(value, "Generated link"), value.optLong("size").coerceAtLeast(0))

    private fun pages(token: String, path: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>(); val seenPages = mutableSetOf<Int>()
        var page = 0
        repeat(100) {
            check(seenPages.add(page)) { "Debrid-Link returned a repeated library page. Try refreshing its library." }
            val response = call(token, "$path?page=$page&perPage=100&structureType=list")
            val values = response.optJSONArray("value") ?: throw ApiException(0, "Debrid-Link returned an unreadable library.")
            result += values.providerObjects()
            check(result.size <= MAX_ITEMS) { "This Debrid-Link library is too large to load at once." }
            val pagination = response.optJSONObject("pagination")
            if (pagination == null || !pagination.has("next") || pagination.isNull("next")) return result.distinctBy { it.providerString("id") }
            val next = pagination.opt("next")?.toString()?.toIntOrNull()
                ?: throw ApiException(0, "Debrid-Link returned an invalid library page.")
            if (next < 0) return result.distinctBy { it.providerString("id") }
            check(next <= 1_000_000) { "Debrid-Link returned an invalid library page." }
            page = next
        }
        throw ApiException(0, "This Debrid-Link library has too many pages to load at once.")
    }

    private fun opaque(value: String): String = value.also {
        require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}"))) { "Debrid-Link returned an invalid file or transfer identity." }
    }
    private fun name(value: JSONObject, fallback: String) = value.providerString("name").replace('\u0000', ' ').trim().take(1000).ifBlank { fallback }
    private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Debrid-Link preparation was cancelled.") }
    private fun processing() = ApiException(0, "This file is still processing in Debrid-Link. The transfer remains in your cloud; open it there when ready.")
    companion object {
        private const val BASE = "https://debrid-link.com/api/v2"
        private const val SEEDBOX = "category:seedbox"
        private const val DOWNLOADER = "category:downloader"
        private const val MAX_ITEMS = 10_000
    }
}
