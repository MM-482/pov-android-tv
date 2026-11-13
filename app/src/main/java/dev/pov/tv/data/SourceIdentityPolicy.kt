package dev.pov.tv.data

/** One selection guard for every transfer backend. Plain catalog lookup remains IMDb-only. */
internal object SourceIdentityPolicy {
    private val imdb = Regex("tt[0-9]{5,12}")

    fun catalog(identity: PlaybackIdentity) = selection(identity, allowCustom = false)

    fun selected(source: SourceItem, identity: PlaybackIdentity) {
        val custom = !imdb.matches(identity.imdbId)
        selection(identity, allowCustom = custom && identity.catalogNamespace.isNotBlank())
        val request = source.requestIdentity
        require(!custom || request != null) { "This title has no verified add-on source identity. Reopen its catalog and exact episode." }
        if (request != null) {
            require(source.sourceAddonId.isNotBlank() && source.sourceAddonId.length <= 200 &&
                request.catalogNamespace == identity.catalogNamespace && request.mediaId == identity.imdbId &&
                request.type == identity.type && request.season == identity.season && request.episode == identity.episode &&
                source.resourceType == identity.type && request.videoId == source.resourceId &&
                request.videoId.length in 1..1000 && request.videoId.none { it.code < 32 || it.code == 127 } &&
                (identity.type != "movie" || request.videoId == identity.imdbId)) {
                "This source belongs to a different catalog title or episode. Choose it again from the original catalog."
            }
        }
        require(identity.type != "series" || !EpisodeFilename.isExplicitMismatch(source.title, identity.season, identity.episode)) {
            "This source identifies a different episode. Choose another source."
        }
    }

    private fun selection(identity: PlaybackIdentity, allowCustom: Boolean) {
        require(imdb.matches(identity.imdbId) || allowCustom && identity.imdbId.length in 1..1000 &&
            identity.imdbId.none { it.code < 32 || it.code == 127 } && identity.catalogNamespace.length in 1..200 &&
            identity.catalogNamespace.none { it.code < 32 || it.code == 127 }) { "This title has no verified catalog identifier." }
        require(identity.type in setOf("movie", "series")) { "This media type is not supported." }
        require(identity.type != "series" || identity.season >= 0 && identity.episode > 0) { "Choose an episode before searching for sources." }
    }
}
