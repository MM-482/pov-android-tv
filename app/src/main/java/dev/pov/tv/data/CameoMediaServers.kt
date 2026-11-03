package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

internal class CameoMediaServers(private val http: CameoServiceTransport) {
    private fun root(c: CameoConnection): String = (if (c.kind == "seedr") "https://dav.seedr.cc/" else c.url).trimEnd('/') + "/"
    private fun endpoint(c: CameoConnection, path: String): String {
        val base = CameoServiceUrls.checked(root(c))
        require(base.query == null) { "Enter a server root without a query string." }
        return base.resolve(path.trimStart('/')).toString()
    }
    private fun headers(c: CameoConnection): Map<String, String> = when (c.kind) {
        "webdav", "seedr" -> {
            require(c.username.isNotBlank() && ':' !in c.username && c.password.isNotBlank()) { "Enter the WebDAV username and password." }
            mapOf("Authorization" to "Basic ${Base64.getEncoder().encodeToString((c.username + ":" + c.password).toByteArray(Charsets.UTF_8))}")
        }
        "plex" -> mapOf("X-Plex-Token" to c.token, "X-Plex-Product" to "Cameo", "X-Plex-Client-Identifier" to "cameo-android", "Accept" to "application/json")
        else -> mapOf("X-Emby-Authorization" to "MediaBrowser Client=\"Cameo\", Device=\"Android\", DeviceId=\"cameo-android\", Version=\"0.4\"") +
            if (c.token.isBlank()) emptyMap() else mapOf("X-Emby-Token" to c.token, "X-MediaBrowser-Token" to c.token)
    }
    private fun get(c: CameoConnection, path: String): JSONObject = providerObject(http.request(endpoint(c, path), headers = headers(c)))

    fun validate(draft: CameoConnection): CameoConnection {
        var c = draft.copy(url = root(draft))
        CameoServiceUrls.checked(c.url)
        when (c.kind) {
            "webdav", "seedr" -> { dav(c, ""); return c.copy(name = c.name.ifBlank { "${if (c.kind == "seedr") "Seedr" else "WebDAV"} · ${c.username}" }) }
            "plex" -> {
                require(c.token.isNotBlank()) { "Enter your Plex access token." }
                val account = providerObject(http.request("https://plex.tv/api/v2/user", headers = headers(c)))
                val name = account.providerString("username")
                check(name.isNotBlank()) { "Plex did not identify an account." }
                get(c, "library/sections")
                return c.copy(name = c.name.ifBlank { "Plex · $name" })
            }
            "jellyfin", "emby" -> {
                val user: JSONObject
                if (c.token.isBlank()) {
                    require(c.username.isNotBlank() && c.password.isNotBlank()) { "Enter a username and password, or a server access token." }
                    val raw = providerObject(http.request(endpoint(c, "Users/AuthenticateByName"), "POST", headers(c),
                        JSONObject().put("Username", c.username).put("Pw", c.password).toString()))
                    user = raw.optJSONObject("User") ?: throw ApiException(401, "The server did not return the authenticated user.")
                    c = c.copy(token = raw.providerString("AccessToken"), userId = user.providerString("Id"), password = "")
                } else {
                    if (c.kind == "emby") require(c.userId.isNotBlank()) { "An Emby token also needs its actual user ID. Or use username/password." }
                    user = get(c, if (c.kind == "emby") "Users/${Net.encode(c.userId)}" else "Users/Me")
                    c = c.copy(userId = user.providerString("Id"), password = "")
                }
                check(c.token.isNotBlank() && c.userId.isNotBlank()) { "The server did not verify this account." }
                return c.copy(name = c.name.ifBlank { "${if (c.kind == "emby") "Emby" else "Jellyfin"} · ${user.providerString("Name")}" })
            }
            else -> error("Unsupported media server.")
        }
    }
    fun browse(c: CameoConnection, parentId: String): List<CameoServiceItem> = when (c.kind) {
        "webdav", "seedr" -> dav(c, parentId)
        "plex" -> plex(c, parentId)
        else -> jellyfin(c, parentId)
    }
    private fun jellyfin(c: CameoConnection, parentId: String): List<CameoServiceItem> {
        require(c.userId.isNotBlank()) { "Reconnect this media server to identify its user." }
        val path = if (c.kind == "emby") "Users/${Net.encode(c.userId)}/Items" else "Items"
        val results = mutableListOf<CameoServiceItem>()
        var offset = 0
        while (offset < 10000) {
            val raw = get(c, "$path?UserId=${Net.encode(c.userId)}&Fields=MediaSources,Overview,ProviderIds&Limit=250&StartIndex=$offset&Recursive=false" +
                if (parentId.isBlank()) "" else "&ParentId=${Net.encode(parentId)}")
            val rows = (raw.optJSONArray("Items") ?: JSONArray()).providerObjects()
            for (row in rows) {
                val id = row.providerString("Id")
                if (id.isBlank()) continue
                val title = row.providerString("Name").ifBlank { "Video" }
                val folder = row.optBoolean("IsFolder")
                val type = when (row.providerString("Type")) { "Movie" -> "movie"; "Episode", "Series", "Season" -> "series"; else -> "video" }
                val imdb = row.optJSONObject("ProviderIds")?.providerString("Imdb").orEmpty()
                val identity = if (type == "movie" && imdb.matches(Regex("tt[0-9]{5,12}"))) PlaybackIdentity(imdb, "movie", title) else null
                val sources = (row.optJSONArray("MediaSources") ?: JSONArray()).providerObjects()
                if (folder || sources.isEmpty()) results += CameoServiceItem(id, c.id, title, folder, type,
                    remoteId = id, identity = identity, detail = row.providerString("Overview"))
                else for (source in sources) {
                    val sourceId = source.providerString("Id")
                    results += CameoServiceItem("$id|$sourceId", c.id,
                        title + if (sources.size > 1) " · ${source.providerString("Name")}" else "", type = type,
                        remoteId = id, mediaSourceId = sourceId, size = source.optLong("Size"), identity = identity,
                        detail = row.providerString("Overview"))
                }
            }
            offset += rows.size
            if (rows.size < 250 || offset >= raw.optInt("TotalRecordCount", Int.MAX_VALUE)) break
        }
        return results.distinctBy { it.id }
    }
    private fun plex(c: CameoConnection, parentId: String): List<CameoServiceItem> {
        val path = parentId.ifBlank { "/library/sections" }
        require(path.startsWith("/library/") && '?' !in path && '#' !in path && ".." !in path) { "Choose a valid Plex library folder." }
        val container = get(c, path + "?X-Plex-Container-Start=0&X-Plex-Container-Size=250").optJSONObject("MediaContainer")
            ?: throw ApiException(0, "Plex did not return its library.")
        val directories = (container.optJSONArray("Directory") ?: JSONArray()).providerObjects().mapNotNull { row ->
            val key = row.providerString("key")
            if (key.isBlank()) null else {
                val route = if (path == "/library/sections") "/library/sections/${Net.encode(key)}/all" else key
                CameoServiceItem(route, c.id, row.providerString("title"), folder = true, remoteId = route)
            }
        }
        val videos = (container.optJSONArray("Metadata") ?: JSONArray()).providerObjects().flatMap { row ->
            val ratingKey = row.providerString("ratingKey")
            val title = row.providerString("title")
            if (row.providerString("type") in setOf("show", "season", "collection", "artist", "album")) {
                val key = row.providerString("key").ifBlank {
                    if (ratingKey.isBlank()) "" else "/library/metadata/${Net.encode(ratingKey)}/children"
                }
                if (!key.startsWith("/library/") || '?' in key || '#' in key || ".." in key) emptyList()
                else listOf(CameoServiceItem(key, c.id, title, folder = true, remoteId = ratingKey.ifBlank { key }))
            } else if (ratingKey.isBlank()) emptyList() else {
                val parts = (row.optJSONArray("Media") ?: JSONArray()).providerObjects().flatMap { media ->
                    (media.optJSONArray("Part") ?: JSONArray()).providerObjects()
                }
                parts.map { part -> CameoServiceItem("$ratingKey|${part.providerString("id")}", c.id,
                    title + if (parts.size > 1) " · ${part.providerString("file").substringAfterLast('/').substringAfterLast('\\')}" else "",
                    type = if (row.providerString("type") == "movie") "movie" else "series", remoteId = ratingKey,
                    mediaSourceId = part.providerString("id"), size = part.optLong("size"), detail = row.providerString("summary")) }
            }
        }
        return directories + videos
    }
    private fun dav(c: CameoConnection, parentId: String): List<CameoServiceItem> {
        val address = CameoServiceUrls.insideRoot(root(c), parentId.ifBlank { root(c) })
        val text = http.request(address, "PROPFIND", headers(c) + mapOf("Depth" to "1", "Accept" to "application/xml"),
            "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>", "application/xml")
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "The WebDAV response contains unsupported XML declarations." }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        require(document.documentElement.localName == "multistatus" && document.documentElement.namespaceURI == "DAV:") {
            "This address did not return a WebDAV directory. Check the server address and account."
        }
        val responses = document.getElementsByTagNameNS("*", "response")
        val result = mutableListOf<CameoServiceItem>()
        for (index in 0 until responses.length) {
            val response = responses.item(index) as? Element ?: continue
            val href = response.textOf("href")
            if (href.isBlank()) continue
            val target = try { CameoServiceUrls.insideRoot(root(c), href) } catch (_: IllegalArgumentException) { continue }
            val uri = URI(target)
            if (uri.rawPath.trimEnd('/') == URI(address).rawPath.trimEnd('/')) continue
            val stats = response.getElementsByTagNameNS("*", "propstat")
            val good = (0 until stats.length).mapNotNull { stats.item(it) as? Element }.firstOrNull { it.textOf("status").contains(Regex("\\s200(?:\\s|$)")) } ?: continue
            val folder = good.getElementsByTagNameNS("*", "collection").length > 0
            val title = good.textOf("displayname").ifBlank { URLDecoder.decode(uri.rawPath.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8") }
            result += CameoServiceItem(uri.rawPath, c.id, title, folder, remoteId = uri.rawPath,
                size = good.textOf("getcontentlength").toLongOrNull() ?: 0)
        }
        return result.distinctBy { it.id }
    }
    private fun Element.textOf(name: String): String = getElementsByTagNameNS("*", name).item(0)?.textContent.orEmpty()
    fun resolve(c: CameoConnection, item: CameoServiceItem): ProviderPlayback {
        require(item.connectionId == c.id && !item.folder) { "Choose a file from this server account." }
        val url = when (c.kind) {
            "webdav", "seedr" -> CameoServiceUrls.insideRoot(root(c), item.remoteId)
            "plex" -> {
                val rows = get(c, "library/metadata/${Net.encode(item.remoteId)}").optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: JSONArray()
                val parts = rows.providerObjects().filter { it.providerString("ratingKey") == item.remoteId }.flatMap { row ->
                    (row.optJSONArray("Media") ?: JSONArray()).providerObjects().flatMap { media -> (media.optJSONArray("Part") ?: JSONArray()).providerObjects() }
                }
                val part = parts.singleOrNull { it.providerString("id") == item.mediaSourceId }
                    ?: throw IllegalStateException("This exact Plex video version is unavailable. Browse the title again.")
                val key = part.providerString("key")
                require(key.startsWith("/library/") && '?' !in key && '#' !in key && ".." !in key) { "Plex returned an unsupported file path." }
                endpoint(c, key)
            }
            else -> {
                val raw = get(c, "Items/${Net.encode(item.remoteId)}/PlaybackInfo?UserId=${Net.encode(c.userId)}")
                val sources = (raw.optJSONArray("MediaSources") ?: JSONArray()).providerObjects()
                val chosen = if (item.mediaSourceId.isBlank()) sources.singleOrNull() else sources.singleOrNull { it.providerString("Id") == item.mediaSourceId }
                require(chosen != null) { "Choose the exact available server video version." }
                require(!chosen.optBoolean("RequiresOpening")) { "This source needs a live server session. Choose a stored video." }
                endpoint(c, "Videos/${Net.encode(item.remoteId)}/stream?Static=true&MediaSourceId=${Net.encode(chosen.providerString("Id"))}")
            }
        }
        return ProviderPlayback(url, headers(c), CameoServiceUrls.origin(root(c)))
    }
}
