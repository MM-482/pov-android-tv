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
    val episodes: List<EpisodeData> = emptyList()
)

data class EpisodeData(
    val season: Int,
    val episode: Int,
    val title: String,
    val released: String = "",
    val thumbnail: String = ""
)

data class PlaybackIdentity(
    val imdbId: String,
    val type: String,
    val title: String,
    val season: Int = 0,
    val episode: Int = 0,
    val catalogNamespace: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("imdbId", imdbId).put("type", type).put("title", title)
        .put("season", season).put("episode", episode).put("catalogNamespace", catalogNamespace)

    companion object {
        fun fromJson(json: JSONObject): PlaybackIdentity = PlaybackIdentity(
            json.optString("imdbId"), json.optString("type"), json.optString("title"),
            json.optInt("season"), json.optInt("episode"), json.optString("catalogNamespace").takeUnless { it == "null" }.orEmpty()
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

/** Exact add-on request context, separate from the add-on that supplies the stream. */
data class SourceRequestIdentity(val catalogNamespace: String, val mediaId: String, val type: String,
    val videoId: String, val season: Int = 0, val episode: Int = 0)

data class SourceItem(
    val title: String, val provider: String, val infoHash: String = "",
    val fileIndex: Int? = null, val directUrl: String = "", val cached: Boolean = false,
    val detail: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val sourceAddonId: String = "", val resourceType: String = "", val resourceId: String = "",
    val requestIdentity: SourceRequestIdentity? = null
)

/** A selected source either streams directly or offers stable TorBox file identities. */
sealed interface PreparedSource {
    val identity: PlaybackIdentity

    data class Direct(val url: String, override val identity: PlaybackIdentity) : PreparedSource

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
