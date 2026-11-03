package dev.pov.tv.data

import org.json.JSONObject

data class MediaItemData(
    val imdbId: String,
    val type: String,
    val title: String,
    val year: String = "",
    val poster: String = "",
    val background: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val runtime: String = "",
    val rating: String = "",
    val episodes: List<EpisodeData> = emptyList(),
    val addonId: String = "",
    val catalogId: String = ""
)

data class EpisodeData(
    val season: Int,
    val episode: Int,
    val title: String,
    val released: String = "",
    val thumbnail: String = "",
    val sourceId: String = "",
    val sourceIds: List<String> = emptyList()
)

data class PlaybackIdentity(
    val imdbId: String,
    val type: String,
    val title: String,
    val season: Int = 0,
    val episode: Int = 0,
    val sourceId: String = "",
    val addonId: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("imdbId", imdbId).put("type", type).put("title", title)
        .put("season", season).put("episode", episode)
        .put("sourceId", sourceId).put("addonId", addonId)

    companion object {
        fun fromJson(json: JSONObject): PlaybackIdentity = PlaybackIdentity(
            json.optString("imdbId"), json.optString("type"), json.optString("title"),
            json.optInt("season"), json.optInt("episode"),
            json.optString("sourceId"), json.optString("addonId")
        )
    }
}

data class CloudFile(val id: Long, val name: String, val size: Long)
data class CloudFolder(
    val id: Long, val kind: String, val name: String, val ready: Boolean,
    val files: List<CloudFile>
)

data class DeviceAuth(
    val deviceCode: String, val userCode: String, val verificationUrl: String,
    val intervalSeconds: Int, val expiresSeconds: Int
)

data class SourceItem(
    val title: String, val provider: String, val infoHash: String = "",
    val fileIndex: Int? = null, val directUrl: String = "", val cached: Boolean = false,
    val detail: String = "",
    val headers: Map<String, String> = emptyMap(),
    val addonId: String = "",
    val sourceId: String = "",
    val origin: String = ""
)

/** A selected source either streams directly or offers stable TorBox file identities. */
sealed interface PreparedSource {
    val identity: PlaybackIdentity

    data class Direct(val url: String, override val identity: PlaybackIdentity,
        val headers: Map<String, String> = emptyMap(), val origin: String = "") : PreparedSource

    data class Files(
        val folder: CloudFolder,
        val files: List<CloudFile>,
        override val identity: PlaybackIdentity,
        val preferredFileId: Long?
    ) : PreparedSource {
        /** An exact filename can suggest focus, but never hides other available versions. */
        val automaticFile: CloudFile? get() = files.singleOrNull()
    }
}
