package dev.pov.tv

import android.content.Context
import dev.pov.tv.data.PlaybackIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PlaybackStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)
    data class Recent(val key: String, val title: String, val identity: PlaybackIdentity?, val position: Long, val duration: Long)
    data class Pending(val identity: PlaybackIdentity, val percent: Double, val action: String, val revision: String)

    fun save(key: String, title: String, identity: PlaybackIdentity?, position: Long, duration: Long) = synchronized(lock) {
        if (position < 0 || duration <= 0) return@synchronized
        val entries = recent().filterNot { it.key == key }.toMutableList()
        entries.add(0, Recent(key, title, identity, position, duration))
        val json = JSONArray()
        entries.take(40).forEach {
            json.put(JSONObject().put("key", it.key).put("title", it.title)
                .put("identity", it.identity?.toJson()).put("position", it.position).put("duration", it.duration))
        }
        preferences.edit().putString("recent", json.toString()).apply()
    }

    fun recent(): List<Recent> = synchronized(lock) {
        try {
            val array = JSONArray(preferences.getString("recent", "[]"))
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Recent(item.getString("key"), item.getString("title"), item.optJSONObject("identity")?.let(PlaybackIdentity::fromJson),
                    item.optLong("position"), item.optLong("duration"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun resume(key: String): Long = recent().find { it.key == key }?.let {
        if (it.position > 30_000 && it.duration > 0 && it.position < it.duration * 0.95) it.position else 0
    } ?: 0

    /** Hiding a row is local to POV; it never removes a watch event from a connected service. */
    fun continueWatching(): List<Recent> = synchronized(lock) {
        recent().filter { it.duration > 0 && it.position < it.duration * 0.95 && !isHidden(it.key, it.identity) }
    }

    fun isHidden(key: String = "", identity: PlaybackIdentity? = null): Boolean = synchronized(lock) {
        val hidden = hiddenLocked()
        (key.isNotBlank() && "key:$key" in hidden) || (identity != null && identityMarker(identity) in hidden)
    }

    fun hideFromContinueWatching(key: String, identity: PlaybackIdentity?) = synchronized(lock) {
        require(key.isNotBlank() || identity != null) { "Choose a title to remove." }
        val hidden = hiddenLocked().toMutableSet()
        if (key.isNotBlank()) hidden += "key:$key"
        identity?.let { hidden += identityMarker(it) }
        writeHidden(hidden)
    }

    /** Only an intentional new play clears dismissal; periodic progress saves cannot resurrect it. */
    fun beginPlayback(key: String, identity: PlaybackIdentity?) = synchronized(lock) {
        val hidden = hiddenLocked().toMutableSet()
        hidden.remove("key:$key")
        identity?.let { hidden.remove(identityMarker(it)) }
        writeHidden(hidden)
    }

    private fun hiddenLocked(): Set<String> = try {
        val values = JSONArray(preferences.getString("hidden_continue", "[]"))
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun writeHidden(hidden: Set<String>) {
        check(preferences.edit().putString("hidden_continue", JSONArray(hidden.toList()).toString()).commit()) {
            "Continue Watching preference could not be saved."
        }
    }

    private fun identityMarker(identity: PlaybackIdentity) =
        "identity:${identity.type}:${identity.imdbId}:${identity.season}:${identity.episode}"

    /** Each replacement gets a revision so an older network completion cannot remove it. */
    fun queue(identity: PlaybackIdentity, percent: Double, action: String): String = synchronized(lock) {
        require(percent.isFinite() && percent in 0.0..100.0) { "Invalid playback progress." }
        require(action in setOf("start", "pause", "stop")) { "Invalid playback action." }
        val revision = UUID.randomUUID().toString()
        val kept = pendingLocked().filterNot { sameMedia(it.identity, identity) }.toMutableList()
        kept.add(Pending(identity, percent, action, revision))
        writePending(kept)
        revision
    }

    fun pending(): List<Pending> = synchronized(lock) { pendingLocked() }

    fun acknowledge(identity: PlaybackIdentity, revision: String) = synchronized(lock) {
        if (revision.isBlank()) return@synchronized
        val items = pendingLocked()
        val kept = items.filterNot { it.revision == revision && sameMedia(it.identity, identity) }
        if (kept.size != items.size) writePending(kept)
        Unit
    }

    /** Disconnecting or changing the Trakt account must discard its unsent updates. */
    fun clearPending() = synchronized(lock) {
        check(preferences.edit().remove("pending").commit()) { "Pending playback updates could not be removed." }
    }

    private fun pendingLocked(): List<Pending> {
        val array = try { JSONArray(preferences.getString("pending", "[]")) }
            catch (_: Exception) { throw IllegalStateException("Saved playback updates could not be read.") }
        var migrated = false
        val result = (0 until array.length()).map { index ->
            try {
                val item = array.getJSONObject(index)
                val revision = item.optString("revision").ifBlank { migrated = true; UUID.randomUUID().toString() }
                Pending(PlaybackIdentity.fromJson(item.getJSONObject("identity")), item.getDouble("percent"), item.getString("action"), revision)
            } catch (_: Exception) {
                throw IllegalStateException("A saved playback update could not be read.")
            }
        }
        // Migrate old entries once, rather than generating a different revision on each read.
        if (migrated) writePending(result)
        return result
    }

    private fun writePending(items: List<Pending>) {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("identity", it.identity.toJson()).put("percent", it.percent)
                .put("action", it.action).put("revision", it.revision))
        }
        check(preferences.edit().putString("pending", array.toString()).commit()) { "Playback updates could not be saved." }
    }

    private fun sameMedia(first: PlaybackIdentity, second: PlaybackIdentity): Boolean =
        first.imdbId == second.imdbId && first.type == second.type && first.season == second.season && first.episode == second.episode

    companion object {
        // MainActivity, PlayerActivity, and background retry use distinct store instances.
        private val lock = Any()
    }
}
