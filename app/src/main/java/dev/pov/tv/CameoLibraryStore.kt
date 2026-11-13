package dev.pov.tv

import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.EpisodeData
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Stable identities are saved; resolved provider links and authentication headers are not. */
data class CameoLibraryEntry(
    val id: String,
    val name: String,
    val kind: String,
    val uri: String = "",
    val media: MediaItemData? = null,
    val identity: PlaybackIdentity? = null,
    val providerId: String = "",
    val folderId: String = "",
    val fileId: String = "",
    val accountRevision: String = "",
    val remoteSourceId: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind)
        .put("uri", uri).put("media", media?.let { item -> JSONObject().put("id", item.imdbId)
            .put("type", item.type).put("title", item.title).put("year", item.year).put("poster", item.poster)
            .put("background", item.background).put("description", item.description).put("genres",JSONArray(item.genres))
            .put("runtime",item.runtime).put("rating",item.rating).put("episodes",JSONArray().apply{item.episodes.forEach{episode->put(JSONObject()
                .put("season",episode.season).put("episode",episode.episode).put("title",episode.title).put("released",episode.released).put("thumbnail",episode.thumbnail))}}) })
        .put("identity", identity?.toJson()).put("provider", providerId).put("folder", folderId)
        .put("file", fileId).put("revision", accountRevision).put("remoteSourceId", remoteSourceId)

    companion object {
        fun catalog(item: MediaItemData, identity: PlaybackIdentity? = null): CameoLibraryEntry {
            val selected = identity ?: PlaybackIdentity(item.imdbId, item.type, item.title)
            return CameoLibraryEntry("catalog:${selected.catalogNamespace}:${selected.type}:${selected.imdbId}:${selected.season}:${selected.episode}",
                selected.title + if (selected.episode > 0) " · S${selected.season} E${selected.episode}" else "",
                "catalog", media = item, identity = selected)
        }
        fun fromJson(value: JSONObject): CameoLibraryEntry {
            val item = value.optJSONObject("media")?.let { MediaItemData(it.getString("id"), it.getString("type"),
                it.getString("title"), it.optString("year"), it.optString("poster"), it.optString("background"), it.optString("description"),
                it.optJSONArray("genres")?.let{values->(0 until values.length()).map{index->values.getString(index)}}.orEmpty(),it.optString("runtime"),it.optString("rating"),
                it.optJSONArray("episodes")?.let{values->(0 until values.length()).map{index->values.getJSONObject(index).let{episode->EpisodeData(episode.getInt("season"),episode.getInt("episode"),episode.getString("title"),episode.optString("released"),episode.optString("thumbnail"))}}}.orEmpty()) }
            val entry = CameoLibraryEntry(value.getString("id"), value.getString("name"), value.getString("kind"),
                value.optString("uri"), item, value.optJSONObject("identity")?.let(PlaybackIdentity::fromJson),
                value.optString("provider"), value.optString("folder"), value.optString("file"), value.optString("revision"), value.optString("remoteSourceId"))
            require(entry.kind in setOf("local", "stream", "catalog", "provider", "server")) { "Unsupported saved library entry." }
            return entry
        }
    }
}

data class CameoPlaylist(val id: String, val name: String, val entries: List<CameoLibraryEntry>)

internal interface CameoLibraryStorage {
    fun read(): String
    fun write(value: String)
}

/** One encrypted transaction keeps library, watchlist and playlist references consistent. */
class CameoLibraryStore internal constructor(private val storage: CameoLibraryStorage) {
    constructor(store: SecureStore) : this(object : CameoLibraryStorage {
        override fun read() = store.get("cameo.library.v1", "{}")
        override fun write(value: String) = store.put("cameo.library.v1", value)
    })

    private fun state(): JSONObject = try { JSONObject(storage.read()) }
        catch (_: Exception) { throw IllegalStateException("Your saved library could not be read. It has not been replaced.") }
    private fun entries(array: JSONArray?): List<CameoLibraryEntry> = array?.let {
        (0 until it.length()).map { index -> CameoLibraryEntry.fromJson(it.getJSONObject(index)) }
    }.orEmpty()
    private fun array(entries: List<CameoLibraryEntry>) = JSONArray().apply { entries.forEach { put(it.toJson()) } }
    private fun names(value: String): String = value.trim().also { require(it.isNotBlank() && it.length <= 160) { "Use a name between 1 and 160 characters." } }
    private fun fileName(value: String): String = value.trim().ifBlank { "Video" }.take(512)

    fun local(): List<CameoLibraryEntry> = synchronized(lock) { entries(state().optJSONArray("local")) }
    fun watchlist(): List<CameoLibraryEntry> = synchronized(lock) { entries(state().optJSONArray("watchlist")) }
    fun isWatchlisted(item: MediaItemData,namespace:String="") = watchlist().any { entry -> entry.identity?.catalogNamespace.orEmpty()==namespace && entry.media?.let { it.imdbId == item.imdbId && it.type == item.type } == true }

    fun addLocal(address: String, name: String): CameoLibraryEntry = synchronized(lock) {
        val target = URI(address)
        require(target.scheme == "content" && !target.authority.isNullOrBlank()) { "Choose a video using Android's file picker." }
        val id = MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString("") { "%02x".format(it) }
        val entry = CameoLibraryEntry("local:$id", fileName(name), "local", address)
        val root = state(); root.put("local", array(entries(root.optJSONArray("local")).filterNot { it.id == entry.id } + entry))
        storage.write(root.toString()); entry
    }

    fun addLocalFiles(files: List<Pair<String,String>>): List<CameoLibraryEntry> = synchronized(lock) {
        val added=files.map { (address,name)->
            val uri=URI(address);require(uri.scheme=="content"&&!uri.authority.isNullOrBlank()) { "Choose local files with Android's document picker." }
            val id=MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
            CameoLibraryEntry("local:$id",fileName(name),"local",address)
        }.distinctBy {it.id}
        val root=state();val ids=added.map{it.id}.toSet()
        root.put("local",array(entries(root.optJSONArray("local")).filterNot{it.id in ids}+added));storage.write(root.toString());added
    }

    fun removeLocal(id: String) = synchronized(lock) {
        val root = state(); root.put("local", array(entries(root.optJSONArray("local")).filterNot { it.id == id }))
        // Removing an imported item does not delete a file or silently alter a saved playlist.
        storage.write(root.toString())
    }

    fun setWatchlist(item: MediaItemData, added: Boolean) = synchronized(lock) {
        val entry = CameoLibraryEntry.catalog(item)
        val root = state(); val kept = entries(root.optJSONArray("watchlist")).filterNot { it.id == entry.id }
        root.put("watchlist", array(if (added) kept + entry else kept)); storage.write(root.toString())
    }

    fun setWatchlist(entry:CameoLibraryEntry,added:Boolean)=synchronized(lock){
        val root=state();val kept=entries(root.optJSONArray("watchlist")).filterNot{it.id==entry.id}
        root.put("watchlist",array(if(added)kept+entry else kept));storage.write(root.toString())
    }
    fun playlists(): List<CameoPlaylist> = synchronized(lock) { playlists(state()) }
    private fun playlists(root: JSONObject): List<CameoPlaylist> {
        val values = root.optJSONArray("playlists") ?: return emptyList()
        return (0 until values.length()).map { index -> values.getJSONObject(index).let {
            CameoPlaylist(it.getString("id"), it.getString("name"), entries(it.optJSONArray("entries")))
        } }
    }
    private fun savePlaylists(root: JSONObject, values: List<CameoPlaylist>) {
        require(values.size <= 500) { "The playlist limit has been reached." }
        root.put("playlists", JSONArray().apply { values.forEach { playlist ->
            require(playlist.entries.size <= 5000) { "This playlist has too many entries." }
            put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("entries", array(playlist.entries)))
        } }); storage.write(root.toString())
    }
    fun createPlaylist(name: String): String = synchronized(lock) {
        val root = state(); val id = UUID.randomUUID().toString()
        savePlaylists(root, playlists(root) + CameoPlaylist(id, names(name), emptyList())); id
    }
    fun renamePlaylist(id: String, name: String) = change(id) { it.copy(name = names(name)) }
    fun deletePlaylist(id: String) = synchronized(lock) {
        val root = state(); savePlaylists(root, playlists(root).filterNot { it.id == id })
    }
    fun addToPlaylist(id: String, entry: CameoLibraryEntry) = change(id) { it.copy(entries = it.entries + entry) }
    fun removeFromPlaylist(id: String, index: Int) = change(id) {
        require(index in it.entries.indices); it.copy(entries = it.entries.filterIndexed { row, _ -> row != index })
    }
    fun moveInPlaylist(id: String, from: Int, to: Int) = change(id) {
        require(from in it.entries.indices && to in it.entries.indices)
        val values = it.entries.toMutableList(); values.add(to, values.removeAt(from)); it.copy(entries = values)
    }
    private fun change(id: String, update: (CameoPlaylist) -> CameoPlaylist) = synchronized(lock) {
        val root = state(); val values = playlists(root)
        require(values.any { it.id == id }) { "This playlist no longer exists." }
        savePlaylists(root, values.map { if (it.id == id) update(it) else it })
    }
    companion object { private val lock = Any() }
}
