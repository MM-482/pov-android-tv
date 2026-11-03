package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** Seedbox operations mirror the desktop adapter; transfer and file IDs stay distinct. */
internal class DebridLinkProvider(private val http: ProviderTransport,
    private val waiting: ProviderPreparationWait = ProviderPreparationWait()) : ProviderBackend {
    private fun call(token: String, path: String, body: JSONObject? = null): Any {
        val result = providerObject(http.request("$BASE$path", if (body == null) "GET" else "POST",
            mapOf("Authorization" to "Bearer $token"), json = body))
        if (!result.optBoolean("success")) throw ApiException(0, "Debrid-Link could not complete this request. Check the account and available quota.")
        return result.opt("value") ?: throw ApiException(0, "Debrid-Link returned an incomplete response.")
    }
    override fun account(token: String, username: String): ProviderAccount {
        val user = call(token, "/account/infos") as? JSONObject ?: throw ApiException(401, "Debrid-Link did not verify this account.")
        val name = user.providerString("username").ifBlank { user.providerString("email") }
        check(name.isNotBlank()) { "Debrid-Link did not identify an account." }
        return ProviderAccount("debridlink", name, user.optLong("premiumLeft") > 0 || user.optBoolean("premium"))
    }
    private fun transfers(token: String, id: String = ""): List<JSONObject> =
        ((call(token, "/seedbox/list?structureType=list" + if (id.isBlank()) "" else "&ids=${Net.encode(id)}") as? JSONArray)
            ?: JSONArray()).providerObjects().filter { id.isBlank() || it.providerString("id") == id }
    override fun cloud(token: String, folderId: String): List<ProviderFolder> = transfers(token).map {
        ProviderFolder("debridlink", it.providerString("id"), it.providerString("name"), it.optInt("downloadPercent") >= 100)
    }
    override fun files(token: String, folderId: String): List<ProviderFile> = transfers(token, folderId).flatMap { transfer ->
        (transfer.optJSONArray("files") ?: JSONArray()).providerObjects().filter { it.providerString("id").isNotBlank() && it.providerString("name").isNotBlank() }.map { file ->
            ProviderFile("$folderId:${file.providerString("id")}", file.providerString("name"), file.optLong("size"))
        }
    }
    override fun playback(token: String, file: ProviderFile): ProviderPlayback {
        val parts = file.id.split(':', limit = 2)
        require(parts.size == 2) { "Refresh the seedbox folder and select a file." }
        val exact = waiting.await("Debrid-Link", parts[0]) {
            val transfer = transfers(token, parts[0]).singleOrNull() ?: throw ApiException(404, "This seedbox transfer is unavailable.")
            val match = (transfer.optJSONArray("files") ?: JSONArray()).providerObjects().singleOrNull { it.providerString("id") == parts[1] }
                ?: throw ApiException(404, "This seedbox file is no longer available.")
            if (match.optInt("downloadPercent") >= 100 && transfer.optInt("downloadPercent") >= 100) match else null
        }
        return ProviderPlayback(providerHttps(exact.providerString("downloadUrl")))
    }
    override fun prepare(token: String, source: SourceItem, identity: PlaybackIdentity): ProviderPreparedSource {
        val created = call(token, "/seedbox/add", JSONObject().put("url", providerHash(source.infoHash))
            .put("wait", false).put("structureType", "list")) as? JSONObject
            ?: throw ApiException(0, "Debrid-Link did not return the new transfer.")
        val id = created.providerString("id")
        check(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}"))) { "Debrid-Link did not identify the transfer. Check your cloud library before retrying." }
        val files = waiting.await("Debrid-Link", id) {
            val transfer = transfers(token, id).singleOrNull()
            if (transfer == null || transfer.optInt("downloadPercent") < 100) null else files(token, id).takeIf { it.isNotEmpty() }
        }
        return providerPrepared(files, source, identity, id)
    }
    companion object { private const val BASE = "https://debrid-link.com/api/v2" }
}
