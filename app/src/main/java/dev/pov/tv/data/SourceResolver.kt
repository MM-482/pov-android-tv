package dev.pov.tv.data

import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** Stremio stream protocol adapter; torrent transfers are performed by TorBox, never on-device. */
class SourceResolver internal constructor(
    private val sourceEndpoint: () -> String,
    private val torbox: SourceTorBox,
    private val publicJson: (String) -> JSONObject = { address ->
        val bytes = LiveCameoServiceTransport.publicBytes(address, CameoServiceUrls.origin(address))
        try { JSONObject(bytes.toString(Charsets.UTF_8)) }
        catch (_: Exception) { throw ApiException(0, "The source provider returned an unreadable response.") }
    },
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep
) {
    constructor(store: SecureStore, torbox: TorBoxApi) : this({ store.get("sources.endpoint") }, ApiSourceTorBox(torbox))
    /** Fetching choices is read-only: checking the TorBox cache does not create a transfer. */
    fun sources(identity: PlaybackIdentity, expectedRevision: String? = null): List<SourceItem> = sourceChoices(identity, true, expectedRevision)

    /** Other accounts must not inherit TorBox cache claims or perform TorBox account requests. */
    fun sourcesUncached(identity: PlaybackIdentity): List<SourceItem> = sourceChoices(identity, false)

    private fun sourceChoices(identity: PlaybackIdentity, checkTorBox: Boolean, expectedRevision: String? = null): List<SourceItem> {
        validateIdentity(identity)
        val cacheRevision = if (checkTorBox) expectedRevision ?: torbox.accountRevision() else null
        val endpoint = normalizeEndpoint(sourceEndpoint())
        val id = identity.sourceId.ifBlank { if (identity.type == "series") {
            "${identity.imdbId}:${identity.season}:${identity.episode}"
        } else identity.imdbId }
        require(RESOURCE_ID.matches(id)) { "This title has no supported source identity." }
        val payload = try {
            publicJson("$endpoint/stream/${identity.type}/${Net.encode(id)}.json")
        } catch (error: InterruptedException) {
            throw error
        } catch (_: Exception) {
            throw IllegalStateException("The source provider could not be reached. Try again or check the source endpoint in Settings.")
        }
        val streams = payload.optJSONArray("streams") ?: return emptyList()
        val provider = URI(endpoint).host
        val choices = (0 until minOf(streams.length(), 300)).mapNotNull { index ->
            val stream = streams.optJSONObject(index) ?: return@mapNotNull null
            val hash = stream.optString("infoHash").lowercase(Locale.ROOT).takeIf(HASH::matches).orEmpty()
            val direct = try {
                val value = stream.optString("url")
                val uri = CameoServiceUrls.addonResource(value, endpoint)
                uri.scheme.lowercase(Locale.ROOT) + value.substring(value.indexOf(':'))
            } catch (_: IllegalArgumentException) { "" }
            if (hash.isBlank() && direct.isBlank()) return@mapNotNull null
            val description = stream.optString("description").ifBlank { stream.optString("title") }
            val filename = stream.optJSONObject("behaviorHints")?.optString("filename").orEmpty()
            val title = filename.ifBlank { description.lineSequence().firstOrNull().orEmpty() }
                .ifBlank { stream.optString("name").ifBlank { "Video source" } }.take(500)
            if (identity.type == "series" && EpisodeFilename.isExplicitMismatch(title, identity.season, identity.episode)) {
                return@mapNotNull null
            }
            val fileIndex = if (stream.has("fileIdx") && !stream.isNull("fileIdx")) {
                stream.optInt("fileIdx", -1).takeIf { it >= 0 }
            } else null
            SourceItem(
                title = title,
                provider = provider,
                infoHash = hash,
                fileIndex = fileIndex,
                directUrl = direct,
                detail = listOf(stream.optString("name"), description.lineSequence().drop(1).joinToString(" · "))
                    .filter { it.isNotBlank() }.joinToString(" · ").take(800),
                headers = requestHeaders(stream.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")),
                sourceId = id,
                origin = if (direct.isBlank()) "" else CameoServiceUrls.origin(if (direct.startsWith("http://")) endpoint else direct)
            )
        }.distinctBy { Triple(it.infoHash, it.fileIndex, it.directUrl) }
        if (!checkTorBox) return choices.map { it.copy(cached = false) }
        val cached = mutableSetOf<String>()
        var cacheAvailable = true
        try {
            choices.map { it.infoHash }.filter { it.isNotBlank() }.distinct().chunked(40).forEach {
                cached += torbox.forRevision(requireNotNull(cacheRevision)).cached(it).map { hash -> hash.lowercase(Locale.ROOT) }
            }
        } catch (_: Exception) {
            cacheAvailable = false
        }
        return choices.map { source ->
            source.copy(
                cached = source.infoHash in cached,
                detail = if (!cacheAvailable && source.infoHash.isNotBlank() && source.infoHash !in cached) {
                    listOf(source.detail, "Cache status unavailable").filter { it.isNotBlank() }.joinToString(" · ")
                } else source.detail
            )
        }.sortedByDescending { it.cached }
    }

    /** Called only after the user selects a source. May add that source to the user's TorBox library. */
    fun prepare(source: SourceItem, identity: PlaybackIdentity, expectedRevision: String? = null): PreparedSource {
        validateSelectedIdentity(identity)
        val revision = expectedRevision ?: torbox.accountRevision()
        if (expectedRevision != null && torbox.accountRevision() != expectedRevision) {
            throw ApiException(401, "The TorBox account changed. Reopen this source or cloud folder.")
        }
        val session = torbox.forRevision(revision)
        require(identity.type != "series" || !EpisodeFilename.isExplicitMismatch(source.title, identity.season, identity.episode)) {
            "This source identifies a different episode. Choose another source."
        }
        if (source.directUrl.isNotBlank()) {
            val target = CameoServiceUrls.addonResource(source.directUrl, source.origin)
            val url = target.scheme.lowercase(Locale.ROOT) + source.directUrl.substring(source.directUrl.indexOf(':'))
            val origin = CameoServiceUrls.origin(if (target.scheme.equals("http", true)) source.origin else url)
            return PreparedSource.Direct(url, identity, source.headers, origin)
        }
        val hash = source.infoHash.lowercase(Locale.ROOT)
        require(HASH.matches(hash)) { "The selected source has no valid torrent hash." }
        val transfer = session.addMagnet(hash)
        // Cached transfers normally expose files immediately. Uncached transfers stay in TorBox;
        // a bounded wait keeps the UI from spinning indefinitely and never deletes user transfers.
        val deadline = monotonicNanos() + 20_000_000_000L
        do {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Source preparation was cancelled.")
            val folder = try {
                session.folder("torrents", transfer)
            } catch (error: ApiException) {
                // A freshly accepted transfer may not have appeared in the library yet.
                if (error.code == 404 || error.errorKey == "ITEM_NOT_FOUND") null else throw error
            }
            if (folder != null && folder.ready && folder.files.isNotEmpty()) {
                check(folder.id == transfer && folder.kind == "torrents") { "TorBox returned a different transfer. Open the selected file in your cloud library." }
                val videos = folder.files.map { VideoCandidate(it.id, it.name, it.size) }
                val eligible = EpisodeFilename.choices(videos, source.title, identity)
                val files = eligible.map { CloudFile(it.id, it.name, it.size) }
                val preferred = EpisodeFilename.preferredId(eligible, source.title) ?: files.singleOrNull()?.id
                return PreparedSource.Files(folder, files, identity, preferred)
            }
            val remaining = deadline - monotonicNanos()
            if (remaining <= 0) break
            pause(minOf(1_500, (remaining + 999_999) / 1_000_000))
        } while (!Thread.currentThread().isInterrupted)
        throw IllegalStateException("This transfer is not ready to play yet. It remains in your TorBox library; try again once TorBox has finished processing it.")
    }

    /** Compatibility for callers that can handle only a single, unambiguous stream. */
    fun resolve(source: SourceItem, identity: PlaybackIdentity, expectedRevision: String? = null): String {
        val revision = expectedRevision ?: torbox.accountRevision()
        return when (val prepared = prepare(source, identity, revision)) {
            is PreparedSource.Direct -> {
                check(prepared.headers.isEmpty() && isHttps(prepared.url)) {
                    "This source needs its request headers or local server origin. Use the prepared playback request."
                }
                prepared.url
            }
            is PreparedSource.Files -> {
                val file = prepared.automaticFile ?: throw IllegalStateException("This source contains multiple video versions. Choose a video file before playing.")
                val url = torbox.forRevision(revision).streamUrl(prepared.folder.kind, prepared.folder.id, file.id)
                require(isHttps(url)) { "TorBox did not return a supported HTTPS playback link." }
                url
            }
        }
    }

    private fun validateIdentity(identity: PlaybackIdentity) {
        require(IMDB_ID.matches(identity.imdbId)) { "This title has no supported IMDb identifier." }
        validateMediaSelection(identity)
    }

    /** A selected add-on source already supplies its exact media identity and torrent hash. */
    private fun validateSelectedIdentity(identity: PlaybackIdentity) {
        val imdb = IMDB_ID.matches(identity.imdbId)
        require(imdb || identity.addonId.isNotBlank() && RESOURCE_ID.matches(identity.imdbId)) {
            "This title has no supported catalog identity."
        }
        validateMediaSelection(identity)
        require(imdb || identity.type != "series" || RESOURCE_ID.matches(identity.sourceId)) {
            "Select the exact add-on episode before preparing its source."
        }
    }

    private fun validateMediaSelection(identity: PlaybackIdentity) {
        require(identity.type == "movie" || identity.type == "series") { "This media type is not supported." }
        require(identity.type != "series" || (identity.season >= 0 && identity.episode > 0)) {
            "Choose an episode before searching for sources."
        }
    }

    private fun isHttps(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: Exception) { false }

    companion object {
        private const val DEFAULT_ENDPOINT = "https://torrentio.strem.fun"
        private val HASH = Regex("[a-f0-9]{40}")
        private val IMDB_ID = Regex("tt[0-9]{5,12}")
        private val RESOURCE_ID = Regex("[A-Za-z0-9][A-Za-z0-9:_.-]{0,499}")
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val TRANSPORT_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding", "trailer", "te",
            "upgrade", "proxy-connection", "proxy-authenticate", "proxy-authorization", "expect", "range", "accept-encoding")

        /** Syntax-only validation; the HTTP transport checks private DNS addresses before connecting. */
        fun normalizeEndpoint(value: String): String {
            var candidate = value.trim().ifEmpty { DEFAULT_ENDPOINT }.trimEnd('/')
            if (candidate.startsWith("stremio://", true)) candidate = "https://" + candidate.substring(10)
            if (candidate.endsWith("/manifest.json", true)) candidate = candidate.dropLast(14)
            require(candidate.length <= 4_096) { "The source provider address is too long." }
            val uri = CameoServiceUrls.checked(candidate)
            require(uri.query == null && uri.fragment == null) { "Use the add-on base URL or manifest URL, without a query or fragment." }
            return (uri.scheme.lowercase(Locale.ROOT) + candidate.substring(candidate.indexOf(':'))).trimEnd('/')
        }

        internal fun requestHeaders(requested: JSONObject?): Map<String, String> {
            if (requested == null) return emptyMap()
            val headers = linkedMapOf<String, String>()
            val names = mutableSetOf<String>()
            requested.keys().asSequence().forEach { key ->
                val normalized = key.lowercase(Locale.ROOT)
                val value = requested.opt(key) as? String
                if (headers.size < 64 && key.length <= 128 && HEADER_NAME.matches(key) && normalized !in TRANSPORT_HEADERS &&
                    value != null && value.length <= 16_384 && value.none(Char::isISOControl) && names.add(normalized)) headers[key] = value
            }
            return headers
        }
    }
}

internal data class VideoCandidate(val id: Long, val name: String, val size: Long)

/** The adapter never treats a provider's zero-based file index as a TorBox file ID. */
internal interface SourceTorBox {
    fun accountRevision(): String = ""
    fun forRevision(revision: String): SourceTorBox = this
    fun cached(hashes: List<String>): Set<String>
    fun addMagnet(hash: String): Long
    fun folder(kind: String, id: Long): CloudFolder
    fun streamUrl(kind: String, folderId: Long, fileId: Long): String
}

/** Production requests bind to one account; default methods keep existing offline mocks compatible. */
private class ApiSourceTorBox(private val api: TorBoxApi, private val revision: String? = null) : SourceTorBox {
    override fun accountRevision() = api.accountRevision()
    override fun forRevision(revision: String): SourceTorBox = ApiSourceTorBox(api, revision)
    override fun cached(hashes: List<String>) = api.cached(hashes, revision)
    override fun addMagnet(hash: String) = api.addMagnet(hash, revision)
    override fun folder(kind: String, id: Long) = api.folder(kind, id, revision)
    override fun streamUrl(kind: String, folderId: Long, fileId: Long) = api.streamUrl(kind, folderId, fileId, revision)
}

/** File selection intentionally does not equate a provider's zero-based fileIdx with a TorBox file id. */
internal object EpisodeFilename {
    private val videoExtension = Regex("(?i)\\.(mkv|mp4|m4v|avi|mov|ts|m2ts|mts|webm|mpg|mpeg|wmv|flv)$")
    private val sample = Regex("(?i)(?:^|[/\\\\ ._-])(?:sample|featurette|proof)(?:[/\\\\ ._-]|$)|(?:^|[/\\\\])(?:extras|trailers)[/\\\\]|[ ._-]trailer\\.[a-z0-9]+$")
    private val seasonEpisode = Regex("(?i)(?:^|[^a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})((?:(?:[ ._-]*(?:-|to)[ ._-]*e?\\d{1,3})|(?:[ ._-]*e\\d{1,3}))*)")
    private val crossEpisode = Regex("(?i)(?:^|[^a-z0-9])(\\d{1,2})x(\\d{1,3})(?:[ ._-]*-[ ._-]*(\\d{1,3}))?(?=$|[^0-9])")
    private val tailEpisode = Regex("(?i)(?:[ ._-]*(?:-|to)[ ._-]*e?(\\d{1,3}))|(?:[ ._-]*e(\\d{1,3}))")

    fun isExplicitMismatch(name: String, season: Int, episode: Int): Boolean {
        val markers = episodes(name)
        return markers.isNotEmpty() && (season to episode) !in markers
    }

    /** Preserve every eligible edition, in service order; choice belongs to the user. */
    fun choices(files: List<VideoCandidate>, sourceTitle: String, identity: PlaybackIdentity): List<VideoCandidate> {
        val videos = files.filter { it.id >= 0 && videoExtension.containsMatchIn(it.name) && !sample.containsMatchIn(it.name) }
        if (videos.isEmpty()) throw IllegalStateException("This transfer does not contain a playable video file yet.")
        if (videos.groupBy { it.id }.any { (_, entries) -> entries.distinct().size > 1 }) {
            throw IllegalStateException("TorBox returned conflicting video file identifiers. Refresh the cloud folder before selecting a file.")
        }
        val unique = videos.distinctBy { it.id }
        if (identity.type == "movie") return unique
        val matches = unique.filter { (identity.season to identity.episode) in episodes(it.name) }
        if (matches.isNotEmpty()) return matches
        val exact = unique.filter { filenameKey(it.name) == filenameKey(sourceTitle) && !isExplicitMismatch(it.name, identity.season, identity.episode) }
        if (exact.size == 1) return exact
        if (unique.size == 1 && !isExplicitMismatch(unique[0].name, identity.season, identity.episode)) return unique
        if (unique.all { isExplicitMismatch(it.name, identity.season, identity.episode) }) {
            throw IllegalStateException("This source contains a different episode. Choose another source.")
        }
        throw IllegalStateException("This pack's episode files could not be matched safely. Select the correct file and identify its episode from TorBox Cloud.")
    }

    fun preferredId(files: List<VideoCandidate>, sourceTitle: String): Long? =
        files.filter { filenameKey(it.name) == filenameKey(sourceTitle) }.singleOrNull()?.id

    fun select(files: List<VideoCandidate>, sourceTitle: String, identity: PlaybackIdentity): VideoCandidate {
        val videos = files.filter { videoExtension.containsMatchIn(it.name) && !sample.containsMatchIn(it.name) }
        if (videos.isEmpty()) throw IllegalStateException("This transfer does not contain a playable video file yet.")
        val exact = videos.filter { filenameKey(it.name) == filenameKey(sourceTitle) }
        if (exact.size == 1 && (identity.type != "series" || !isExplicitMismatch(exact[0].name, identity.season, identity.episode))) {
            return exact[0]
        }
        if (identity.type == "series") {
            val matched = videos.filter { (identity.season to identity.episode) in episodes(it.name) }
            if (matched.size == 1) return matched[0]
            if (matched.size > 1) throw IllegalStateException("This pack has multiple files for the selected episode. Choose another source or select the file from TorBox Cloud.")
        }
        if (videos.size == 1) {
            val only = videos[0]
            if (identity.type == "series" && isExplicitMismatch(only.name, identity.season, identity.episode)) {
                throw IllegalStateException("This source contains a different episode. Choose another source.")
            }
            return only
        }
        throw IllegalStateException("This pack contains multiple videos and the correct file could not be identified safely. Choose another source or select a file from TorBox Cloud.")
    }

    private fun filenameKey(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .replace(videoExtension, "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun episodes(name: String): Set<Pair<Int, Int>> {
        val found = mutableSetOf<Pair<Int, Int>>()
        seasonEpisode.findAll(name).forEach { match ->
            val season = match.groupValues[1].toInt()
            var previous = match.groupValues[2].toInt()
            found += season to previous
            tailEpisode.findAll(match.groupValues[3]).forEach { tail ->
                val rangeEnd = tail.groupValues[1].toIntOrNull()
                if (rangeEnd != null) {
                    // Bound ranges to avoid accepting malformed huge packs as every possible episode.
                    if (rangeEnd >= previous && rangeEnd - previous <= 100) {
                        for (episode in previous..rangeEnd) found += season to episode
                    }
                    previous = rangeEnd
                } else {
                    previous = tail.groupValues[2].toInt()
                    found += season to previous
                }
            }
        }
        crossEpisode.findAll(name).forEach { match ->
            val season = match.groupValues[1].toInt()
            val start = match.groupValues[2].toInt()
            val end = match.groupValues[3].toIntOrNull() ?: start
            if (end >= start && end - start <= 100) for (episode in start..end) found += season to episode
        }
        return found
    }
}
