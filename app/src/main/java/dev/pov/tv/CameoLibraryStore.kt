package dev.pov.tv

import android.content.Context
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Durable references contain catalog/file identities, never resolved playback URLs or account secrets. */
data class SavedMedia(
    val title: String,
    val kind: String = "catalog",
    val media: MediaItemData? = null,
    val identity: PlaybackIdentity? = null,
    val uri: String = "",
    val connectionId: String = "",
    val serviceItem: String = "",
    val size: Long = 0,
) {
    val canonicalKey: String get() {
        val parts = when (kind) {
            "local" -> listOf(kind, uri)
            "service" -> {
                val item = JSONObject(serviceItem)
                listOf(kind, connectionId, item.optString("id"), item.optString("remoteId"), item.optString("mediaSourceId"))
            }
            else -> listOf(kind, media?.type.orEmpty(), media?.imdbId ?: identity?.imdbId.orEmpty(),
                media?.addonId ?: identity?.addonId.orEmpty(), media?.catalogId.orEmpty())
        } + listOf(identity?.season?.toString().orEmpty(), identity?.episode?.toString().orEmpty(), identity?.sourceId.orEmpty())
        return MessageDigest.getInstance("SHA-256").digest(JSONArray(parts).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    val historyKey: String get() = "cameo:$canonicalKey"

    fun toJson(): JSONObject = JSONObject().put("title", title).put("kind", kind)
        .put("media", media?.let(::mediaJson)).put("identity", identity?.toJson())
        .put("uri", uri).put("connectionId", connectionId)
        .put("serviceItem", serviceItem.takeIf(String::isNotBlank)?.let(::JSONObject)).put("size", size)

    fun validate(): SavedMedia {
        require(title.isNotBlank() && title.length <= 2000) { "Choose a named title or video." }
        when (kind) {
            "local" -> require(uri.startsWith("content://")) { "Choose a video using the device's file picker." }
            "service" -> {
                val item = JSONObject(serviceItem)
                require(connectionId.isNotBlank() && item.optString("id").isNotBlank() && !item.optBoolean("folder")) {
                    "Choose an actual service video, not a folder."
                }
                require(item.optString("connectionId") == connectionId) { "This file belongs to a different connection." }
                require(listOf("headers", "token", "password", "url", "streamUrl", "directUrl").none(item::has)) {
                    "Only a durable file reference can be saved."
                }
            }
            "catalog" -> require((media?.imdbId ?: identity?.imdbId).orEmpty().isNotBlank()) { "Choose a catalog title." }
            else -> error("Unsupported saved item.")
        }
        return this
    }

    companion object {
        fun catalog(item: MediaItemData, episode: PlaybackIdentity? = null) = SavedMedia(
            title = episode?.let { if (it.episode > 0) "${item.title} · S${it.season} E${it.episode}" else item.title } ?: item.title,
            media = item.copy(episodes = emptyList()), identity = episode)

        fun fromJson(value: JSONObject): SavedMedia = SavedMedia(
            title = value.getString("title"), kind = value.optString("kind", "catalog"),
            media = value.optJSONObject("media")?.let(::mediaFromJson),
            identity = value.optJSONObject("identity")?.let(PlaybackIdentity::fromJson),
            uri = value.optString("uri"), connectionId = value.optString("connectionId"),
            serviceItem = value.optJSONObject("serviceItem")?.toString().orEmpty(), size = value.optLong("size"))

        private fun mediaJson(item: MediaItemData): JSONObject = JSONObject()
            .put("imdbId", item.imdbId).put("type", item.type).put("title", item.title)
            .put("year", item.year).put("poster", item.poster).put("background", item.background)
            .put("description", item.description).put("genres", JSONArray(item.genres))
            .put("runtime", item.runtime).put("rating", item.rating).put("addonId", item.addonId).put("catalogId", item.catalogId)

        private fun mediaFromJson(value: JSONObject): MediaItemData = MediaItemData(
            imdbId = value.getString("imdbId"), type = value.optString("type", "movie"), title = value.getString("title"),
            year = value.optString("year"), poster = value.optString("poster"), background = value.optString("background"),
            description = value.optString("description"), genres = value.optJSONArray("genres")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            }.orEmpty(), runtime = value.optString("runtime"), rating = value.optString("rating"),
            addonId = value.optString("addonId"), catalogId = value.optString("catalogId"))
    }
}

data class CameoPlaylistEntry(val id: String, val item: SavedMedia)
data class CameoPlaylist(val id: String, val name: String, val items: List<CameoPlaylistEntry>)

/** The existing playback/account stores remain untouched by library writes. */
class CameoLibraryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("cameo_library_v1", Context.MODE_PRIVATE)

    fun local(): List<SavedMedia> = synchronized(lock) { saved(read(), "local") }
    fun watchlist(): List<SavedMedia> = synchronized(lock) { saved(read(), "watchlist") }
    fun isSaved(item: SavedMedia): Boolean = watchlist().any { it.canonicalKey == item.canonicalKey }
    fun remember(item: SavedMedia) = synchronized(lock) {
        item.validate(); val state = read()
        val values = listOf(item) + saved(state, "recent_refs").filterNot { it.canonicalKey == item.canonicalKey }
        state.put("recent_refs", JSONArray(values.take(80).map { it.toJson() })); write(state)
    }
    fun addLocal(items: List<SavedMedia>): Int = synchronized(lock) {
        val state = read()
        val existing = saved(state, "local").associateByTo(linkedMapOf()) { it.canonicalKey }
        val before = existing.size
        items.forEach { item -> require(item.kind == "local"); item.validate(); existing[item.canonicalKey] = item }
        require(existing.size <= 50_000) { "The local library can contain up to 50,000 videos." }
        state.put("local", JSONArray(existing.values.map { it.toJson() })); write(state)
        existing.size - before
    }
    fun removeLocal(key: String) = synchronized(lock) {
        val state = read(); state.put("local", JSONArray(saved(state, "local").filterNot { it.canonicalKey == key }.map { it.toJson() })); write(state)
    }
    /** The positive '+ Library' action is idempotent and never removes an existing title. */
    fun addToWatchlist(item: SavedMedia): Boolean = synchronized(lock) {
        item.validate(); val state = read(); val values = saved(state, "watchlist").toMutableList()
        if (values.any { it.canonicalKey == item.canonicalKey }) return@synchronized false
        require(values.size < 5000) { "Your watchlist is full." }
        values.add(0, item)
        state.put("watchlist", JSONArray(values.map { it.toJson() })); write(state)
        true
    }
    fun toggleWatchlist(item: SavedMedia): Boolean = synchronized(lock) {
        item.validate(); val state = read(); val values = saved(state, "watchlist").toMutableList()
        val added = values.none { it.canonicalKey == item.canonicalKey }
        if (added) { require(values.size < 5000) { "Your watchlist is full." }; values.add(0, item) }
        else values.removeAll { it.canonicalKey == item.canonicalKey }
        state.put("watchlist", JSONArray(values.map { it.toJson() })); write(state); added
    }
    fun playlists(): List<CameoPlaylist> = synchronized(lock) { playlists(read()) }
    fun playlist(id: String): CameoPlaylist = playlists().find { it.id == id } ?: error("This playlist is no longer available.")
    fun createPlaylist(name: String): CameoPlaylist = synchronized(lock) {
        val state = read(); val lists = playlists(state).toMutableList()
        require(lists.size < 200) { "You can save up to 200 playlists." }
        val result = CameoPlaylist(UUID.randomUUID().toString(), validName(name), emptyList())
        lists.add(result); savePlaylists(state, lists); result
    }
    fun renamePlaylist(id: String, name: String) = changePlaylist(id) { it.copy(name = validName(name)) }
    fun deletePlaylist(id: String) = synchronized(lock) {
        val state = read(); val lists = playlists(state); require(lists.any { it.id == id }) { "This playlist no longer exists." }
        savePlaylists(state, lists.filterNot { it.id == id })
    }
    fun addToPlaylist(id: String, item: SavedMedia) = changePlaylist(id) { playlist ->
        item.validate()
        if (playlist.items.any { it.item.canonicalKey == item.canonicalKey }) playlist
        else {
            require(playlists().sumOf { it.items.size } < 1000) { "You can save up to 1,000 playlist entries." }
            playlist.copy(items = playlist.items + CameoPlaylistEntry(UUID.randomUUID().toString(), item))
        }
    }
    fun removeFromPlaylist(id: String, entryId: String) = changePlaylist(id) { it.copy(items = it.items.filterNot { entry -> entry.id == entryId }) }
    fun moveEntry(id: String, entryId: String, direction: Int) = changePlaylist(id) { playlist ->
        require(direction == -1 || direction == 1)
        val entries = playlist.items.toMutableList(); val index = entries.indexOfFirst { it.id == entryId }
        require(index >= 0) { "This playlist entry no longer exists." }
        val destination = index + direction
        if (destination in entries.indices) entries.add(destination, entries.removeAt(index))
        playlist.copy(items = entries)
    }
    fun findByHistoryKey(key: String): SavedMedia? = synchronized(lock) {
        (local() + watchlist() + saved(read(), "recent_refs") + playlists().flatMap { it.items.map { entry -> entry.item } }).find { it.historyKey == key }
    }

    private fun changePlaylist(id: String, change: (CameoPlaylist) -> CameoPlaylist): CameoPlaylist = synchronized(lock) {
        val state = read(); val lists = playlists(state).toMutableList(); val index = lists.indexOfFirst { it.id == id }
        require(index >= 0) { "This playlist is no longer available." }
        val updated = change(lists[index]); lists[index] = updated; savePlaylists(state, lists); updated
    }
    private fun read(): JSONObject = try { JSONObject(preferences.getString("data", "{}") ?: "{}") }
        catch (_: Exception) { throw IllegalStateException("The saved library could not be read. It has not been overwritten.") }
    private fun write(value: JSONObject) { check(preferences.edit().putString("data", value.toString()).commit()) { "The library could not be saved." } }
    private fun saved(state: JSONObject, key: String): List<SavedMedia> = state.optJSONArray(key)?.let { array ->
        (0 until array.length()).map { SavedMedia.fromJson(array.getJSONObject(it)) }
    }.orEmpty()
    private fun playlists(state: JSONObject): List<CameoPlaylist> = state.optJSONArray("playlists")?.let { array ->
        (0 until array.length()).map { index ->
            val value = array.getJSONObject(index); val entries = value.optJSONArray("items") ?: JSONArray()
            CameoPlaylist(value.getString("id"), value.getString("name"), (0 until entries.length()).map {
                val entry = entries.getJSONObject(it); CameoPlaylistEntry(entry.getString("id"), SavedMedia.fromJson(entry.getJSONObject("item")))
            })
        }
    }.orEmpty()
    private fun savePlaylists(state: JSONObject, lists: List<CameoPlaylist>) {
        state.put("playlists", JSONArray(lists.map { list -> JSONObject().put("id", list.id).put("name", list.name)
            .put("items", JSONArray(list.items.map { JSONObject().put("id", it.id).put("item", it.item.toJson()) })) })); write(state)
    }
    private fun validName(name: String): String = name.trim().also {
        require(it.length in 1..80 && it.none { char -> char.code < 32 }) { "Enter a playlist name from 1 to 80 characters." }
    }
    companion object { private val lock = Any() }
}
