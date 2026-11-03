package dev.pov.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.widget.TextView
import dev.pov.tv.data.*
import java.util.Locale

interface CameoFeatureHost {
    val activity: Activity
    val secureStore: SecureStore
    val generation: Int
    val featureScreenVisible: Boolean
    fun page(title: String, subtitle: String, refresh: () -> Unit): Int
    fun action(label: String, action: () -> Unit): View
    fun note(message: String)
    fun navigate(screen: () -> Unit)
    fun <T> load(id: Int, work: () -> T, done: (T) -> Unit)
    fun play(source: ProviderPlayback, title: String, identity: PlaybackIdentity?, key: String, automatic: Boolean = false)
    fun details(item: MediaItemData)
    fun sources(identity: PlaybackIdentity)
    fun resolveSource(source: SourceItem, identity: PlaybackIdentity)
    fun legacyConnections()
    fun legacyLibrary()
}

/** Platform-native screens shared by mobile and TV; the host supplies its adaptive controls. */
class CameoFeatures(private val host: CameoFeatureHost) {
    val library = CameoLibraryStore(host.activity)
    val connections = CameoConnections(host.secureStore)
    val addonRepository = AddonRepository(host.secureStore)
    private val preferences = host.activity.getSharedPreferences("cameo_features", android.content.Context.MODE_PRIVATE)
    private val importer = LocalLibraryImporter(host.activity)
    private var pendingDownload: Pair<ProviderPlayback, String>? = null
    private var pendingNzbAccount: String? = null
    private var pendingNzbFile: Pair<String, NzbFile>? = null
    private var pendingDiagnosticReport: String? = null
    private var screen = ""
    private var queueId = ""
    private var queueEntry = ""
    private var queueItem: SavedMedia? = null
    private var advancing = false
    private var localPage = 0
    private var localQuery = ""
    private val downloadRows = mutableMapOf<String, TextView>()
    private val downloadChanged: () -> Unit = {
        if (screen == "downloads" && host.featureScreenVisible && !host.activity.isDestroyed) refreshDownloads()
    }

    init { CameoDownloads.initialize(host.activity); CameoDownloads.addListener(downloadChanged) }
    fun close() { CameoDownloads.removeListener(downloadChanged); clearQueue() }

    fun library() {
        screen = "library"
        host.page("Your library", "Local videos, saved titles, playlists, and your connected services.", ::library)
        host.action("Local videos · ${library.local().size}") { host.navigate(::localLibrary) }
        host.action("Watchlist · ${library.watchlist().size}") { host.navigate(::watchlist) }
        host.action("Playlists · ${library.playlists().size}") { host.navigate(::playlists) }
        host.action("Downloads") { host.navigate(::downloads) }
        host.action("Import videos") { pickVideos() }
        host.action("Import a folder") { pickFolder() }
        host.action("Open a stream URL") { openStream() }
        host.action("Connected services") { host.navigate(::connections) }
        host.action("Add-on catalogs") { host.navigate(::addons) }
        host.action("Existing playback-service cloud") { host.navigate(host::legacyLibrary) }
    }

    fun localLibrary() {
        screen = "local"
        host.page("Local videos", "Files stay in the location you selected. Removing an entry does not delete the video.", ::localLibrary)
        host.action("Import videos") { pickVideos() }
        host.action("Import a folder") { pickFolder() }
        host.action("Search local videos${if (localQuery.isBlank()) "" else " · $localQuery"}") { prompt("Video title", localQuery) { query -> localQuery = query.trim(); localPage = 0; localLibrary() } }
        val items = library.local().filter { localQuery.isBlank() || it.title.contains(localQuery, ignoreCase = true) }
        localPage = localPage.coerceAtMost(((items.size - 1).coerceAtLeast(0)) / 100)
        if (items.isEmpty()) host.note(if (localQuery.isBlank()) "Choose videos or a folder to start your local library." else "No matching local videos.")
        if (localPage > 0) host.action("Previous page") { localPage--; localLibrary() }
        items.drop(localPage * 100).take(100).forEach { item -> host.action(item.title + item.size.takeIf { it > 0 }?.let { " · ${size(it)}" }.orEmpty()) { savedOptions(item, allowRemoval = true) } }
        if ((localPage + 1) * 100 < items.size) host.action("More local videos") { localPage++; localLibrary() }
    }

    fun watchlist() {
        screen = "watchlist"
        host.page("Watchlist", "Saved on this device. Your connected tracking-service lists remain separate.", ::watchlist)
        val items = library.watchlist()
        if (items.isEmpty()) host.note("Use Save in a title's options to add it here.")
        items.forEach { item -> host.action(item.title) { savedOptions(item) } }
    }

    fun playlists() {
        screen = "playlists"
        host.page("Playlists", "Keep exact episodes, local videos, and service files in the order you choose.", ::playlists)
        host.action("Create playlist") { prompt("Playlist name") { name ->
            safely { val list = library.createPlaylist(name); host.navigate { playlist(list.id) } }
        } }
        val lists = library.playlists()
        if (lists.isEmpty()) host.note("Create a playlist, then add titles or videos from their options.")
        lists.forEach { list -> host.action("${list.name} · ${list.items.size} items") { host.navigate { playlist(list.id) } } }
    }

    fun playlist(id: String): Unit = safely {
        screen = "playlist"
        val list = library.playlist(id)
        host.page(list.name, "${list.items.size} items · playback follows this saved order.") { playlist(id) }
        if (list.items.isNotEmpty()) host.action("Play playlist") { playSaved(list.items.first().item, id, list.items.first().id) }
        host.action("Rename playlist") { prompt("Playlist name", list.name) { name -> safely { library.renamePlaylist(id, name); playlist(id) } } }
        host.action("Delete playlist") { confirm("Delete ${list.name}? Your video files will stay where they are.") {
            library.deletePlaylist(id); if (queueId == id) clearQueue(); playlists()
        } }
        list.items.forEachIndexed { index, entry ->
            host.action("${index + 1}. ${entry.item.title}") {
                choose(entry.item.title, listOf(
                    "Play from here" to { playSaved(entry.item, id, entry.id) },
                    "Move up" to { safely { library.moveEntry(id, entry.id, -1); playlist(id) } },
                    "Move down" to { safely { library.moveEntry(id, entry.id, 1); playlist(id) } },
                    "Remove from playlist" to { safely { library.removeFromPlaylist(id, entry.id); playlist(id) } }
                ))
            }
        }
    }

    fun mediaOptions(item: MediaItemData, episode: PlaybackIdentity? = null) = savedOptions(SavedMedia.catalog(item, episode))

    fun savedOptions(item: SavedMedia, allowRemoval: Boolean = false) {
        val choices = mutableListOf<Pair<String, () -> Unit>>()
        choices += "Play / find sources" to { playSaved(item) }
        if (item.kind == "catalog" && item.media != null) choices += "Title details" to { host.navigate { host.details(item.media) } }
        choices += (if (library.isSaved(item)) "Remove from device watchlist" else "Save to device watchlist") to {
            safely { val added = library.toggleWatchlist(item); toast(if (added) "Saved to watchlist" else "Removed from watchlist")
                if (screen == "watchlist" && host.featureScreenVisible) watchlist() }
        }
        choices += "Add to playlist" to { addToPlaylist(item) }
        val trackedIdentity = item.identity ?: item.media?.let { PlaybackIdentity(it.imdbId, it.type, it.title, addonId = it.addonId) }
        if (trackedIdentity != null && trackedIdentity.imdbId.matches(Regex("tt[0-9]{5,12}")))
            choices += "Update a tracking-service account" to { chooseTracker(trackedIdentity) }
        if (item.kind == "service") choices += "Download video" to {
            val id = host.generation
            host.load(id, { connections.resolve(item.connectionId, CameoServiceItem.fromJson(org.json.JSONObject(item.serviceItem)), cancelled(id)) }) { source -> download(source, item.title) }
        }
        if (allowRemoval) choices += "Remove from local library" to { safely { library.removeLocal(item.canonicalKey); localLibrary() } }
        choose(item.title, choices)
    }

    private fun addToPlaylist(item: SavedMedia) {
        val choices = library.playlists().map { list -> list.name to {
            safely { library.addToPlaylist(list.id, item); toast("Added to ${list.name}") }
        } }.toMutableList()
        choices += "Create playlist" to { prompt("Playlist name") { name -> safely {
            val list = library.createPlaylist(name); library.addToPlaylist(list.id, item); toast("Added to ${list.name}")
        } } }
        choose("Add to playlist", choices)
    }

    fun playSaved(item: SavedMedia, playlistId: String = "", entryId: String = "", automatic: Boolean = false) {
        safely {
            library.remember(item)
            if (playlistId.isBlank()) clearQueue() else {
                queueId = playlistId; queueEntry = entryId; queueItem = item; bindQueue()
            }
            when (item.kind) {
                "local" -> host.play(ProviderPlayback(item.uri), item.title, item.identity, item.historyKey, automatic)
                "service" -> {
                    val id = host.generation
                    host.load(id, {
                        connections.resolve(item.connectionId, CameoServiceItem.fromJson(org.json.JSONObject(item.serviceItem)), cancelled(id))
                    }) { playback -> host.play(playback, item.title, item.identity, item.historyKey, automatic) }
                }
                else -> {
                    val identity = item.identity ?: item.media?.takeIf { it.type == "movie" }?.let {
                        PlaybackIdentity(it.imdbId, it.type, it.title, addonId = it.addonId)
                    }
                    if (identity != null) {
                        host.navigate { host.sources(identity) }
                        if (!host.activity.hasWindowFocus()) host.activity.startActivity(Intent(host.activity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    else item.media?.let { host.navigate { host.details(it) } }
                }
            }
        }
    }

    fun resumeSaved(key: String): Boolean {
        if (key.startsWith("stream:")) {
            toast("Paste the stream address again to resume. Direct stream addresses are not saved.")
            openStream(); return true
        }
        if (key.startsWith("download:")) {
            val job = CameoDownloads.list().find { "download:${it.id}" == key && it.status == "complete" } ?: return false
            host.play(ProviderPlayback(job.destination), job.title, null, key); return true
        }
        val item = library.findByHistoryKey(key) ?: return false
        playSaved(item); return true
    }

    fun beforePlayback(key: String, identity: PlaybackIdentity?) {
        val queued = queueItem ?: return
        val expected = queued.identity
        val matches = if (queued.kind != "catalog") queued.historyKey == key else {
            val media = queued.media
            identity != null && identity.imdbId == (expected?.imdbId ?: media?.imdbId) &&
                identity.addonId == (expected?.addonId ?: media?.addonId.orEmpty()) &&
                (expected == null || (identity.season == expected.season && identity.episode == expected.episode && identity.sourceId == expected.sourceId))
        }
        if (!matches) clearQueue() else bindQueue()
    }

    private fun bindQueue() {
        if (queueId.isBlank()) return
        val list = runCatching { library.playlist(queueId) }.getOrNull() ?: run { clearQueue(); return }
        val index = list.items.indexOfFirst { it.id == queueEntry }
        if (index < 0) { clearQueue(); return }
        InAppPlaybackSession.setQueueNavigation(
            if (index > 0) ({ moveQueue(-1, false) }) else null,
            if (index < list.items.lastIndex) ({ moveQueue(1, false) }) else null)
        InAppPlaybackSession.setOnEnded { moveQueue(1, true) }
    }

    private fun moveQueue(direction: Int, automatic: Boolean) {
        if (advancing) return
        safely {
            val list = library.playlist(queueId); val index = list.items.indexOfFirst { it.id == queueEntry }
            if (index < 0 || index + direction !in list.items.indices) { clearQueue(); return@safely }
            val next = list.items[index + direction]
            advancing = true
            try { playSaved(next.item, list.id, next.id, automatic = automatic) } finally { advancing = false }
        }
    }
    private fun clearQueue() {
        queueId = ""; queueEntry = ""; queueItem = null
        InAppPlaybackSession.clearQueueNavigation(); InAppPlaybackSession.setOnEnded(null)
    }

    fun connections() {
        screen = "connections"
        host.page("Connections", "Save multiple accounts and browse each service independently.", ::connections)
        host.action("Add connection") { choose("Choose a service", connections.specs.map { spec -> spec.label to { connectionForm(spec.kind) } }) }
        host.action("Existing accounts, tracking, and subtitles") { host.navigate(host::legacyConnections) }
        val accounts = connections.list()
        if (accounts.isEmpty()) host.note("Add a playback service, media server, tracker, or subtitle account.")
        accounts.forEach { connection ->
            host.action("${connection.name.ifBlank { connection.kind }} · ${connection.kind}") { host.navigate { connection(connection.id) } }
        }
    }

    fun connection(id: String) {
        screen = "connection"
        val saved = connections.list().find { it.id == id } ?: run { connections(); return }
        host.page(saved.name.ifBlank { saved.kind }, saved.kind) { connection(id) }
        if (saved.kind in CameoConnections.SUBTITLE_KINDS) {
            host.note("Choose this account from Download subtitles in the player. Searches and downloads use the account you select.")
        } else if (saved.kind == "offcloud") host.action("Open a transfer by request ID") {
            prompt("Offcloud request ID") { request -> host.navigate { browseConnection(id, request.trim(), "Offcloud transfer") } }
        } else if (saved.kind == "easynews") {
            host.action("Search Easynews") { prompt("Search videos") { title -> easynewsSearch(id, title) } }
            host.action("Import NZB") { pickNzb(id) }
        } else host.action("Browse library") { host.navigate { browseConnection(id) } }
        if (saved.kind in CameoConnections.MANUAL_TRACKER_KINDS)
            host.note("Updates from this account are manual. Open a catalog title or a library entry to change its watchlist or watched status.")
        if (connections.specs.first { it.kind == saved.kind }.supportsSources) host.action("Use this account for sources") {
            selectPlaybackAccount(id); toast("${saved.name.ifBlank { saved.kind }} selected")
        }
        host.action("Edit connection") { connectionForm(saved.kind, saved) }
        host.action("Remove connection") { confirm("Remove this saved connection from Cameo?") { connections.remove(id); connections() } }
    }

    private fun connectionForm(kind: String, existing: CameoConnection? = null) {
        val spec = connections.specs.first { it.kind == kind }
        val activity = host.activity
        val fields = linkedMapOf<String, EditText>()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val values = mapOf("name" to existing?.name.orEmpty(), "url" to existing?.url.orEmpty(),
            "token" to existing?.token.orEmpty(), "username" to existing?.username.orEmpty(), "password" to existing?.password.orEmpty(),
            "clientId" to existing?.clientId.orEmpty(), "userId" to existing?.userId.orEmpty(), "apiKey" to existing?.apiKey.orEmpty())
        (listOf("name") + spec.fields).distinct().forEach { key ->
            val input = EditText(activity).apply {
                hint = when (key) { "name" -> "Account label"; "url" -> "Server address"; "clientId" -> "Client ID"; "userId" -> "User ID"; "apiKey" -> "API key"; else -> key.replaceFirstChar { it.titlecase() } }
                setText(values[key].orEmpty()); setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or if (key in setOf("token", "password", "apiKey")) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
                minHeight = dp(52)
            }
            fields[key] = input; column.addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val scroll = ScrollView(activity).apply { addView(column) }
        val dialog = AlertDialog.Builder(activity).setTitle(spec.label).setView(scroll)
            .setPositiveButton("Connect", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun value(key: String) = fields[key]?.text?.toString().orEmpty()
                val draft = CameoConnection(id = existing?.id.orEmpty(), kind = kind, name = value("name"), url = value("url"),
                    token = value("token"), username = value("username"), password = value("password"), clientId = value("clientId"), userId = value("userId"), apiKey = value("apiKey"))
                dialog.dismiss()
                toast("Checking ${spec.label}…")
                host.load(host.generation, { connections.save(draft) }) { result ->
                    toast("${result.name.ifBlank { spec.label }} connected"); connection(result.id)
                }
            }
        }
        dialog.show()
    }

    fun browseConnection(id: String, parentId: String = "", title: String = "Service library") {
        screen = "service-library"
        val generation = host.page(title, "Choose a folder or exact video file.") { browseConnection(id, parentId, title) }
        host.load(generation, { connections.browse(id, parentId) }) { items ->
            if (items.isEmpty()) host.note("No items were returned for this location.")
            items.forEach { item -> host.action((if (item.folder) "▸ " else "") + item.title + if (item.detail.isBlank()) "" else " · ${item.detail}") {
                if (item.folder) host.navigate { browseConnection(id, item.id, item.title) }
                else if (connections.get(id).kind in setOf("anilist", "myanimelist", "kitsu")) animeOptions(item)
                else if (connections.get(id).kind in CameoConnections.MANUAL_TRACKER_KINDS) {
                    val identity = item.identity
                    if (identity == null) toast("This service entry has no supported catalog identity.")
                    else if (!identity.imdbId.matches(Regex("tt[0-9]{5,12}"))) choose(item.title, listOf(
                        "Update this tracking account" to { trackerOptions(id, identity) },
                        "Find matching catalog title" to { findCatalogMatch(item.title) }
                    ))
                    else choose(item.title, listOf(
                        "Open title" to { host.navigate { host.details(MediaItemData(identity.imdbId, identity.type, identity.title)) } },
                        "Update this tracking account" to { trackerOptions(id, identity) },
                        "Save title / playlist" to { mediaOptions(MediaItemData(identity.imdbId, identity.type, identity.title), identity.takeIf { it.type == "movie" || it.episode > 0 }) }
                    ))
                }
                else {
                    val saved = SavedMedia(item.title, "service", identity = item.identity, connectionId = item.connectionId,
                        serviceItem = item.toJson().toString(), size = item.size)
                    savedOptions(saved)
                }
            } }
        }
    }

    fun selectedPlaybackAccount(): String = preferences.getString("playback_account", "").orEmpty()
        .takeIf { id -> connections.list().any { it.id == id } }.orEmpty()
    fun selectPlaybackAccount(id: String) { preferences.edit().putString("playback_account", id).apply() }
    fun playbackAccountName(): String? = selectedPlaybackAccount().takeIf(String::isNotBlank)?.let { id ->
        connections.get(id).let { it.name.ifBlank { it.kind } }
    }

    fun addons() {
        screen = "addons"
        host.page("Add-ons", "Install your own compatible catalogs, metadata, sources, and subtitles.", ::addons)
        host.action("Install add-on") { prompt("Add-on manifest address") { address ->
            host.load(host.generation, { addonRepository.install(address) }) { addon -> toast("${addon.name} installed"); addons() }
        } }
        val installed = addonRepository.list()
        if (installed.isEmpty()) host.note("No add-ons installed. Your existing catalog and source provider remain available.")
        installed.forEach { addon -> host.action("${addon.name} · ${if (addon.enabled) "Enabled" else "Disabled"}") {
            choose(addon.name, listOf(
                "Browse catalogs" to { host.navigate { addonCatalogs(addon) } },
                (if (addon.enabled) "Disable" else "Enable") to { addonRepository.setEnabled(addon.id, !addon.enabled); addons() },
                "Remove add-on" to { confirm("Remove ${addon.name} from Cameo?") { addonRepository.remove(addon.id); addons() } }
            ))
        } }
    }

    private fun addonCatalogs(addon: CameoAddon) {
        screen = "addon-catalogs"
        val id = host.page(addon.name, "Available catalogs") { addonCatalogs(addon) }
        host.load(id, { addonRepository.catalogs(addon.id) }) { catalogs ->
            if (catalogs.isEmpty()) host.note("This add-on provides sources, metadata, or subtitles without its own catalog.")
            catalogs.forEach { catalog -> host.action("${catalog.name} · ${catalog.type}") { host.navigate { addonCatalog(addon, catalog) } } }
        }
    }

    private fun addonCatalog(addon: CameoAddon, catalog: CameoAddonCatalog, query: String = "", skip: Int = 0, genre: String = "") {
        screen = "addon-catalog"
        val id = host.page(catalog.name, listOf(addon.name, genre, query).filter(String::isNotBlank).joinToString(" · ")) { addonCatalog(addon, catalog, query, skip, genre) }
        if (catalog.searchable) host.action("Search this catalog") { prompt("Search", query) { term -> addonCatalog(addon, catalog, term, 0, genre) } }
        if (catalog.genres.isNotEmpty()) host.action("Genre · ${genre.ifBlank { "All" }}") {
            choose("Genre", (listOf("") + catalog.genres).map { value -> value.ifBlank { "All" } to { addonCatalog(addon, catalog, query, 0, value) } })
        }
        if (skip > 0) host.action("First page") { addonCatalog(addon, catalog, query, 0, genre) }
        host.load(id, { addonRepository.catalog(addon.id, catalog.id, catalog.type, query, skip, genre) }) { items ->
            if (items.isEmpty()) host.note("No titles were returned.")
            items.forEach { item -> host.action(item.title + if (item.year.isBlank()) "" else " · ${item.year}") { host.navigate { host.details(item) } } }
            if (items.isNotEmpty()) host.action("More titles") { addonCatalog(addon, catalog, query, skip + items.size, genre) }
        }
    }

    fun sources(identity: PlaybackIdentity, quality: String = "All") {
        screen = "sources"
        val id = host.page(identity.title, "Select a video source and playback account.") { sources(identity, quality) }
        host.action("Playback account · ${playbackAccountName() ?: "Existing playback account"}") {
            val choices = connections.list().filter { c -> connections.specs.any { it.kind == c.kind && it.supportsSources } }
                .map { c -> c.name.ifBlank { c.kind } to { selectPlaybackAccount(c.id); sources(identity, quality) } }.toMutableList()
            choices += "Existing playback account" to { selectPlaybackAccount(""); sources(identity, quality) }
            choices += "Add connection" to { host.navigate(::connections) }
            choose("Playback account", choices)
        }
        host.load(id, {
            val failures = mutableListOf<Throwable>()
            val installed = runCatching { addonRepository.sources(identity) }.onFailure { failures += it }.getOrDefault(emptyList())
            val defaults = runCatching {
                addonRepository.sourcesFromEndpoint(identity, host.secureStore.get("sources.endpoint", "https://torrentio.strem.fun"))
            }.onFailure { failures += it }.getOrDefault(emptyList())
            val all = (installed + defaults).distinctBy { listOf(it.addonId, it.directUrl, it.infoHash, it.fileIndex, it.title) }
            if (all.isEmpty() && failures.isNotEmpty()) throw IllegalStateException("Sources could not be loaded. Check the add-on or provider configuration.", failures.first())
            all
        }) { items ->
            val qualities = listOf("All") + items.map { sourceQuality(it.title + " " + it.detail) }.distinct().sortedBy { listOf("4K", "1080p", "720p", "SD", "Other").indexOf(it) }
            host.action("Quality · $quality") { choose("Quality", qualities.map { q -> q to { sources(identity, q) } }) }
            if (items.isEmpty()) host.note("No sources were returned. Check your add-on or source-provider configuration.")
            items.filter { quality == "All" || sourceQuality(it.title + " " + it.detail) == quality }.forEach { source ->
                host.action("${source.title} · ${source.provider}") { sourceOptions(source, identity) }
            }
        }
    }

    private fun sourceOptions(source: SourceItem, identity: PlaybackIdentity) {
        if (source.directUrl.isNotBlank()) {
            val address = java.net.URI(source.directUrl)
            val origin = source.origin.ifBlank { "${address.scheme}://${address.rawAuthority}" }
            require(address.scheme.equals("https", true) || source.origin.isNotBlank() &&
                SameOriginMediaRequests.sameOrigin(address, java.net.URI(source.origin))) { "A local add-on stream must stay on its configured server." }
            val playback = ProviderPlayback(source.directUrl, source.headers, origin)
            choose(source.title, listOf(
                "Play" to { host.play(playback, identity.title, identity, playbackKey(identity, source.addonId)) },
                "Download video" to { download(playback, source.title) },
                "Save title / playlist" to { mediaOptions(MediaItemData(identity.imdbId, identity.type, identity.title, addonId = identity.addonId), identity) }
            ))
            return
        }
        val selected = selectedPlaybackAccount()
        if (selected.isBlank()) { host.resolveSource(source, identity); return }
        confirm("Prepare this source in ${playbackAccountName()}? Your service may need to transfer the file.") {
            val id = host.generation
            host.load(id, { connections.prepare(selected, source, identity, cancelled(id)) }) { prepared ->
                if (prepared.playback != null) choose(source.title, listOf(
                    "Play" to { host.play(prepared.playback, identity.title, identity, playbackKey(identity, "$selected:${source.addonId}")) },
                    "Download video" to { download(prepared.playback, source.title) },
                    "Save title / playlist" to { mediaOptions(MediaItemData(identity.imdbId, identity.type, identity.title, addonId = identity.addonId), identity) }
                ))
                else choose("Choose the exact video", prepared.files.map { file -> file.name to {
                    val item = runCatching { connections.fileItem(selected, file, prepared.folderId, identity) }.getOrNull()
                    if (item != null) savedOptions(SavedMedia(file.name, "service", identity = identity, connectionId = selected,
                        serviceItem = item.toJson().toString(), size = file.size))
                    else {
                        val fileGeneration = host.generation
                        host.load(fileGeneration, { connections.playback(selected, file, cancelled(fileGeneration)) }) { playback ->
                            choose(file.name, listOf("Play" to { host.play(playback, identity.title, identity, playbackKey(identity, "$selected:${source.addonId}")) },
                                "Download" to { download(playback, file.name) },
                                "Save catalog title" to { mediaOptions(MediaItemData(identity.imdbId, identity.type, identity.title, addonId = identity.addonId), identity) }))
                        }
                    }
                } })
            }
        }
    }

    private fun animeOptions(item: CameoServiceItem) {
        choose(item.title, listOf(
            "Update episodes, status, or score" to {
                prompt("Episodes watched", "0") { count ->
                    val progress = count.toIntOrNull()
                    if (progress == null || progress < 0) toast("Enter a whole episode count.") else {
                        val statuses = listOf("planned", "watching", "completed", "onHold", "dropped", "repeating")
                        choose("Status", statuses.map { status -> status to {
                            prompt("Score 0–10 (leave blank to keep)") { scoreText ->
                                val score = if (scoreText.isBlank()) -1 else scoreText.toIntOrNull()
                                if (score == null || score !in -1..10) toast("Enter a score from 0 to 10.") else confirm("Update ${item.title}: $progress episodes, $status?") {
                                    host.load(host.generation, { connections.updateAnime(item.connectionId, item, progress, status, score) }) { toast("Anime list updated") }
                                }
                            }
                        } })
                    }
                }
            },
            "Find matching catalog title" to { findCatalogMatch(item.title) }
        ))
    }

    private fun findCatalogMatch(title: String) {
        host.load(host.generation, { CatalogApi(host.activity).search(title, "series") }) { matches ->
            if (matches.isEmpty()) toast("No matching titles found.")
            else choose("Confirm the matching title", matches.map { media -> media.title to { host.navigate { host.details(media) } } })
        }
    }

    private fun chooseTracker(identity: PlaybackIdentity) {
        val accounts = connections.list().filter { it.kind in CameoConnections.MANUAL_TRACKER_KINDS }
        if (accounts.isEmpty()) {
            choose("Tracking accounts", listOf("Add a tracking account" to { host.navigate(::connections) }))
            return
        }
        choose("Choose a tracking account", accounts.map { account ->
            "${account.name.ifBlank { account.kind }} · ${account.kind}" to { trackerOptions(account.id, identity) }
        })
    }

    private fun trackerOptions(id: String, identity: PlaybackIdentity) {
        val account = connections.get(id)
        val name = account.name.ifBlank { account.kind }
        val title = identity.title + if (identity.type == "series" && identity.episode > 0) " · S${identity.season} E${identity.episode}" else ""
        fun change(label: String, work: () -> Unit) {
            confirm("$label for $title in $name?") {
                host.load(host.generation, work) { toast("$name updated") }
            }
        }
        val choices = mutableListOf<Pair<String, () -> Unit>>()
        choices += "Add to watchlist" to { change("Add to watchlist") { connections.updateWatchlist(id, identity, true) } }
        if (account.kind != "simkl") choices += "Remove from watchlist" to { change("Remove from watchlist") { connections.updateWatchlist(id, identity, false) } }
        if (identity.type == "movie" || identity.episode > 0) {
            choices += "Mark watched" to { change("Mark watched") { connections.updateWatched(id, identity, true) } }
            choices += (if (account.kind == "simkl") "Remove Simkl watching record" else "Mark unwatched") to {
                change(if (account.kind == "simkl") "Remove the watchlist and watched record" else "Mark unwatched") { connections.updateWatched(id, identity, false) }
            }
        } else choices += "Select an episode to update watched history" to {
            if (identity.imdbId.matches(Regex("tt[0-9]{5,12}"))) host.navigate { host.details(MediaItemData(identity.imdbId, identity.type, identity.title)) }
            else findCatalogMatch(identity.title)
        }
        choose("$title · $name", choices)
    }

    private fun easynewsSearch(id: String, title: String) {
        screen = "easynews"
        val identity = PlaybackIdentity("", "movie", title)
        val generation = host.page("Easynews · $title", "Choose a result to play or download.") { easynewsSearch(id, title) }
        host.load(generation, { connections.searchEasynews(id, identity) }) { items ->
            if (items.isEmpty()) host.note("No matching files were returned.")
            items.forEach { item -> host.action(item.title) {
                savedOptions(SavedMedia(item.title, "service", identity = item.identity, connectionId = item.connectionId,
                    serviceItem = item.toJson().toString(), size = item.size))
            } }
        }
    }

    private fun sourceQuality(name: String): String = when {
        Regex("(?i)(\\b2160p\\b|\\b4k\\b|\\buhd\\b)").containsMatchIn(name) -> "4K"
        Regex("(?i)\\b1080[pi]\\b").containsMatchIn(name) -> "1080p"
        Regex("(?i)\\b720p\\b").containsMatchIn(name) -> "720p"
        Regex("(?i)\\b(480|576)[pi]\\b").containsMatchIn(name) -> "SD"
        else -> "Other"
    }

    private fun playbackKey(identity: PlaybackIdentity, account: String): String {
        val value = identity.toJson().put("account", account).toString()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "cameo-catalog:$digest"
    }

    fun download(source: ProviderPlayback, title: String) {
        try { DownloadTransfer.requireSingleFile(source) } catch (error: Exception) { toast(error.message ?: "This stream cannot be downloaded as one file."); return }
        pendingDownload = source to title
        val suggested = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(150).ifBlank { "Cameo video" }
        try { host.activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"; putExtra(Intent.EXTRA_TITLE, suggested)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, SAVE_DOWNLOAD) } catch (_: Exception) { pendingDownload = null; toast("No document provider is available to save this video.") }
    }

    fun openStream() {
        val field = EditText(host.activity).apply {
            hint = "HTTPS video URL or local media-server URL"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(host.activity).setTitle("Open a stream URL").setView(field)
            .setPositiveButton("Open") { _, _ -> safely {
                val value = field.text.toString().trim()
                require(value.length <= 16_384) { "This media address is too long." }
                val address = SameOriginMediaRequests.address(value)
                val source = ProviderPlayback(value, origin = "${address.scheme}://${address.rawAuthority}")
                val key = "stream:" + java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                choose("Network stream", listOf(
                    "Play" to { host.play(source, "Network stream", null, key) },
                    "Download video file" to { download(source, "Cameo video") }
                ))
            } }.setNegativeButton("Cancel", null).show()
    }

    fun diagnostics() {
        screen = "diagnostics"
        host.page("Troubleshooting", "Review or save a report with app, device, and playback details. Account secrets, media addresses, and viewing titles are excluded.", ::diagnostics)
        fun showReport(connectivity: Boolean) {
            val id = host.generation
            host.load(id, { CameoDiagnostics.report(host.activity, connectivity) }) { report ->
                AlertDialog.Builder(host.activity).setTitle(if (connectivity) "Public connectivity report" else "App diagnostics")
                    .setMessage(report).setPositiveButton("Save report") { _, _ ->
                        pendingDiagnosticReport = report
                        pick(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            type = "text/plain"; addCategory(Intent.CATEGORY_OPENABLE)
                            putExtra(Intent.EXTRA_TITLE, "Cameo-diagnostics.txt")
                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }, SAVE_DIAGNOSTICS)
                    }.setNegativeButton("Close", null).show()
            }
        }
        host.action("Review app diagnostics") { showReport(false) }
        host.action("Check public service connectivity") { showReport(true) }
        host.note("The connectivity check contacts a fixed set of public endpoints without your account credentials. Reports stay on your device unless you choose to share a saved file.")
    }

    private fun pickNzb(accountId: String) {
        pendingNzbAccount = accountId
        pick(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, PICK_NZB)
    }

    private fun saveNzb(accountId: String, file: NzbFile) {
        pendingNzbFile = accountId to file
        pick(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, SAVE_NZB)
    }

    fun downloads() {
        screen = "downloads"
        downloadRows.clear()
        host.page("Downloads", "Transfers run while Cameo is running. Complete files are saved to the destination you chose.", ::downloads)
        host.action("Clear finished entries") { CameoDownloads.clearFinished() }
        val jobs = CameoDownloads.list()
        if (jobs.isEmpty()) host.note("Choose Download from a service file or source to save a video.")
        jobs.forEach { job ->
            val row = host.action(downloadLabel(job)) { safely {
                val job = CameoDownloads.list().find { it.id == job.id } ?: return@safely
                if (job.active) choose(job.title, listOf("Cancel download" to { CameoDownloads.cancel(job.id) }))
                else if (job.status == "complete") {
                    val uri = Uri.parse(job.destination)
                    if (job.video)
                        host.play(ProviderPlayback(job.destination), job.title, null, "download:${job.id}")
                    else runCatching { host.activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                        host.activity.contentResolver.getType(uri) ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        .onFailure { toast("No installed app can open this file type.") }
                }
                else if (job.error.isNotBlank()) AlertDialog.Builder(host.activity).setTitle(job.title).setMessage(job.error).setPositiveButton("Close", null).show()
            } }
            if (row is TextView) downloadRows[job.id] = row
        }
    }

    private fun refreshDownloads() {
        val jobs = CameoDownloads.list()
        if (jobs.map { it.id }.toSet() != downloadRows.keys) { downloads(); return }
        jobs.forEach { job -> downloadRows[job.id]?.let { it.text = downloadLabel(job); it.contentDescription = it.text } }
    }
    private fun downloadLabel(job: CameoDownload): String {
        val progress = if (job.total > 0) "${(job.bytes * 100 / job.total).coerceIn(0, 100)}%" else size(job.bytes)
        return "${job.title} · ${job.status.replaceFirstChar { it.titlecase() }}${if (job.active) " · $progress" else ""}"
    }

    private fun pickVideos() = pick(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }, PICK_VIDEOS)
    private fun pickFolder() = pick(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }, PICK_FOLDER)
    private fun pick(intent: Intent, request: Int) { try { host.activity.startActivityForResult(intent, request) }
        catch (_: Exception) { toast("No file picker is available on this device.") } }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode !in setOf(PICK_VIDEOS, PICK_FOLDER, SAVE_DOWNLOAD, PICK_NZB, SAVE_NZB, SAVE_DIAGNOSTICS)) return false
        if (resultCode != Activity.RESULT_OK || data == null) {
            if (requestCode == SAVE_DOWNLOAD) pendingDownload = null
            if (requestCode == PICK_NZB) pendingNzbAccount = null
            if (requestCode == SAVE_NZB) pendingNzbFile = null
            if (requestCode == SAVE_DIAGNOSTICS) pendingDiagnosticReport = null
            return true
        }
        val uris = (data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
            ?: listOfNotNull(data.data)).distinct()
        val canRead = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        val canWrite = data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
        uris.forEach { uri -> if (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            runCatching {
                when {
                    canRead && canWrite -> host.activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    canRead -> host.activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    canWrite -> host.activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
        } }
        if (uris.isEmpty()) { toast("No file was selected."); return true }
        if (requestCode == SAVE_DIAGNOSTICS) {
            val report = pendingDiagnosticReport; pendingDiagnosticReport = null
            if (report == null) { toast("Create the diagnostic report again."); return true }
            host.load(host.generation, {
                val descriptor = host.activity.contentResolver.openFileDescriptor(uris.first(), "rw")
                    ?: error("The report destination could not be opened.")
                if (descriptor.statSize != 0L) { descriptor.close(); error("Choose a new empty destination for the report.") }
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(report.toByteArray(Charsets.UTF_8)) }
            }) { toast("Diagnostic report saved") }
        } else if (requestCode == PICK_NZB) {
            val accountId = pendingNzbAccount; pendingNzbAccount = null
            if (accountId == null) { toast("Choose the NZB again."); return true }
            host.load(host.generation, {
                host.activity.contentResolver.openInputStream(uris.first())?.use(EasynewsNzb::parse)
                    ?: error("This NZB could not be opened.")
            }) { files ->
                if (files.isEmpty()) toast("No downloadable files were found in this NZB.")
                else choose("Choose an NZB file", files.map { file -> "${file.name} · ${size(file.estimatedBytes)}" to { saveNzb(accountId, file) } })
            }
        } else if (requestCode == SAVE_NZB) {
            val selected = pendingNzbFile; pendingNzbFile = null
            if (selected == null) toast("Import the NZB again.") else safely {
                val account = connections.get(selected.first)
                require(account.kind == "easynews") { "Choose the original Easynews account." }
                CameoDownloads.startNzb(host.activity, selected.second, account.username, account.password.ifBlank { account.token }, uris.first())
                host.navigate(::downloads)
            }
        } else if (requestCode == SAVE_DOWNLOAD) {
            val selected = pendingDownload; pendingDownload = null
            if (selected != null && uris.size == 1) safely {
                CameoDownloads.start(host.activity, selected.second, selected.first, uris.single()); host.navigate(::downloads)
            } else toast("Choose the download again to start it.")
        } else {
            toast("Importing videos…")
            host.load(host.generation, {
                val result = if (requestCode == PICK_FOLDER) importer.tree(uris.first()) else importer.documents(uris)
                library.addLocal(result.items) to result
            }) { (count, result) ->
                toast("Imported $count videos" + if (result.unreadableFolders > 0) " · ${result.unreadableFolders} folders could not be read" else "")
                localLibrary()
            }
        }
        return true
    }

    private fun choose(title: String, choices: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(host.activity).setTitle(title).setItems(choices.map { it.first }.toTypedArray()) { _, which -> safely(choices[which].second) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun prompt(title: String, initial: String = "", done: (String) -> Unit) {
        val field = EditText(host.activity).apply { setSingleLine(); setText(initial); selectAll() }
        AlertDialog.Builder(host.activity).setTitle(title).setView(field).setPositiveButton("Save") { _, _ -> done(field.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(host.activity).setMessage(message).setPositiveButton("Continue") { _, _ -> safely(action) }.setNegativeButton("Cancel", null).show()
    }
    private fun safely(action: () -> Unit) { try { action() } catch (error: Exception) { toast(uiErrorMessage(error)) } }
    private fun cancelled(id: Int): () -> Boolean = { id != host.generation || host.activity.isDestroyed || Thread.currentThread().isInterrupted }
    private fun toast(message: String) = Toast.makeText(host.activity, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = AdaptiveLayout.dp(host.activity, value)
    private fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
        else -> "${bytes / 1000} KB"
    }
    companion object {
        private const val PICK_VIDEOS = 4101; private const val PICK_FOLDER = 4102; private const val SAVE_DOWNLOAD = 4103
        private const val PICK_NZB = 4104; private const val SAVE_NZB = 4105
        private const val SAVE_DIAGNOSTICS = 4106
    }
}
