package dev.pov.tv

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import dev.pov.tv.data.*

/** Native screens shared by the touch and remote shells; all protocol work stays off the UI thread. */
class CameoServiceScreens(private val host: Host, private val services: DesktopServiceHub, private val addonApi: AddonCatalogApi) {
    interface Host {
        val context: Context
        fun page(title: String, subtitle: String): LinearLayout
        fun navigate(screen: () -> Unit)
        fun <T> load(work: () -> T, done: (T) -> Unit)
        fun text(body: LinearLayout, message: String, size: Int = 16)
        fun button(body: LinearLayout, label: String, action: () -> Unit)
        fun input(body: LinearLayout, label: String, value: String = "", secret: Boolean = false): EditText
        fun prepareSource(source: SourceItem, identity: PlaybackIdentity, download: Boolean = false)
        fun play(playback: ProviderPlayback, title: String, identity: PlaybackIdentity?, key: String, download: Boolean)
        fun playlist(connectionId: String, entry: DesktopMediaEntry)
        fun playlist(entry: CameoLibraryEntry)
        fun watchlist(entry: CameoLibraryEntry, added: Boolean)
        fun isWatchlisted(entry: CameoLibraryEntry): Boolean = false
        fun subtitle(url: String, language: String, name: String)
        fun existingConnections(body: LinearLayout)
        fun existingServices(body: LinearLayout)
        fun notice(message: String)
        fun error(error: Throwable)
    }

    fun connections() {
        val body = host.page("Connections", "Cloud services, tracking, subtitles, media servers and anime accounts")
        host.existingConnections(body)
        host.text(body, "Media servers and anime services", 22)
        host.button(body, "+ Add media server or anime account") { host.navigate(::newConnection) }
        services.connections().forEach { connection ->
            host.button(body, "${connection.label} · ${connection.accountName}") { host.navigate { connection(connection) } }
        }
        host.button(body, "Stremio-compatible add-ons") { host.navigate(::addons) }
    }

    fun myServices() {
        val body = host.page("My services", "Browse connected cloud libraries, media servers and anime lists")
        host.existingServices(body)
        val connections = services.connections()
        if (connections.isEmpty()) host.text(body, "Connect a media server or anime service in Connections to browse its library.")
        connections.forEach { connection -> host.button(body, "${connection.label} · ${connection.accountName}") { host.navigate { browse(connection) } } }
        host.button(body, "Manage connections") { host.navigate(::connections) }
        host.button(body, "Browse add-on catalogs") { host.navigate(::addons) }
    }

    /** Reopens the exact saved owner/title/video identity; custom IDs never go to the IMDb catalog. */
    fun openSaved(entry: CameoLibraryEntry) {
        val identity = entry.identity ?: run { host.error(IllegalArgumentException("This saved item has no catalog identity.")); return }
        val media = entry.media ?: run { host.error(IllegalArgumentException("This saved item has no metadata.")); return }
        if (media.imdbId != identity.imdbId || media.type != identity.type) {
            host.error(IllegalArgumentException("This saved item has conflicting catalog identities.")); return
        }
        val namespace = identity.catalogNamespace
        if (namespace.startsWith("service:")) {
            val connectionId = namespace.removePrefix("service:")
            val connection = services.connections().singleOrNull { it.id == connectionId }
                ?: run { host.error(IllegalStateException("The original service connection is unavailable.")); return }
            host.page(entry.name, "Checking the original service list…")
            host.load({
                check(entry.accountRevision.isBlank() || services.accountRevision(connectionId) == entry.accountRevision) { "The original service account changed. Reopen its list." }
                services.browse(connectionId).singleOrNull { it.id == media.imdbId }
                    ?: error("This item is no longer available in the original service list.")
            }) { current -> host.navigate { serviceItem(connection, current) } }
            return
        }
        val owner = addonApi.installed().singleOrNull { it.id == namespace && it.enabled }
            ?: run { host.error(IllegalStateException("The original catalog add-on is disabled or was removed.")); return }
        if (entry.providerId.isNotBlank() && entry.providerId != owner.id) {
            host.error(IllegalArgumentException("This saved item does not match its original catalog.")); return
        }
        val summary = AddonMedia(media, owner.id, identity.type, identity.imdbId)
        if (identity.type != "series" || identity.episode <= 0) { addonDetail(summary); return }
        host.page(entry.name, "Checking the original episode identity…")
        host.load({
            val current = addonApi.detail(owner.id, identity.type, identity.imdbId)
            val video = current.episodes.singleOrNull { it.id == entry.fileId && it.season == identity.season && it.number == identity.episode }
                ?: error("The original catalog no longer confirms this exact episode. Reopen its episode list.")
            current to video
        }) { (current, video) -> host.navigate { sourceList(current.item, video.id, video.season, video.number, owner.id) } }
    }

    private fun newConnection() {
        val body = host.page("Add connection", "Choose the service you want to connect")
        services.specs.forEach { spec -> host.button(body, spec.label) { host.navigate { connectionForm(spec) } } }
    }

    private fun connection(summary: DesktopConnectionSummary) {
        val body = host.page(summary.label, "${summary.accountName} · ${summary.kind}")
        if (summary.url.isNotBlank()) host.text(body, summary.url)
        host.button(body, "Open library") { host.navigate { browse(summary) } }
        host.button(body, "Verify connection") { host.load({ services.verify(summary.id) }) { verified ->
            host.notice("${verified.label} connected successfully")
            host.navigate { connection(verified) }
        } }
        host.button(body, "Edit connection") { services.specs.singleOrNull { it.id == summary.kind }?.let { spec -> host.navigate { connectionForm(spec, summary) } } }
        host.button(body, "Disconnect") { confirm("Disconnect ${summary.label}?", "This removes the saved connection from Cameo. Files and tracking data in the service are preserved.") {
            services.disconnect(summary.id)
            host.navigate(::connections)
        } }
    }

    private fun connectionForm(spec: DesktopServiceSpec, previous: DesktopConnectionSummary? = null) {
        val body = host.page(if (previous == null) "Connect ${spec.label}" else "Edit ${previous.label}",
            if (previous == null) "Credentials are checked before this account is saved." else "Leave secret fields empty to retain the saved credentials. Enter a new password to sign in again.")
        val label = host.input(body, "Display name", previous?.label ?: spec.label)
        val url = if (spec.requiresUrl) host.input(body, "Server address (HTTPS or a local HTTP server)", previous?.url.orEmpty()) else null
        val token = if (spec.requiresToken || spec.id in setOf("jellyfin", "emby", "kitsu"))
            host.input(body, if (spec.requiresToken) "Access token" else "Access token (or use username and password)", secret = true) else null
        val username = if (spec.usernamePassword) host.input(body, if (spec.id == "emby") "Username (or user ID for a token)" else "Username") else null
        val password = if (spec.usernamePassword) host.input(body, "Password", secret = true) else null
        val userId = if (spec.id == "emby") host.input(body, "User ID (required with an Emby token)") else null
        if (spec.id == "seedr") host.text(body, "Seedr uses its official WebDAV endpoint. Your Seedr plan must include WebDAV access.")
        if (spec.category == "anime") host.text(body, "Anime lists keep their service's exact title identities. Progress updates are explicit; adding this account does not automatically report playback.")
        if (spec.id in setOf("anilist", "myanimelist")) host.text(body, "Use an access token authorized for your own account with permission to read and update your anime list.")
        host.button(body, "Check and save") {
            val input = DesktopConnectionInput(spec.id, label.text.toString(), url?.text?.toString().orEmpty(), token?.text?.toString().orEmpty(),
                username?.text?.toString().orEmpty(), password?.text?.toString().orEmpty(), userId?.text?.toString().orEmpty(), previous?.id.orEmpty())
            host.load({ services.connect(input) }) { summary ->
                token?.text?.clear(); password?.text?.clear()
                host.notice("${summary.label} connected")
                host.navigate { connection(summary) }
            }
        }
    }

    private fun browse(connection: DesktopConnectionSummary, path: List<Pair<String, String>> = emptyList(), query: String = "") {
        val parent = path.lastOrNull()?.first.orEmpty()
        val body = host.page(path.lastOrNull()?.second ?: connection.label, connection.accountName)
        if (path.isNotEmpty()) host.button(body, "← Parent folder") { host.navigate { browse(connection, path.dropLast(1)) } }
        val search = host.input(body, "Search this library", query)
        host.button(body, "Search") { host.navigate { browse(connection, path, search.text.toString().trim()) } }
        val rows = section(body)
        host.text(rows, "Loading library…")
        host.load({
            val entries = services.browse(connection.id, parent, if (connection.kind in setOf("jellyfin", "emby")) query else "")
            if (query.isBlank() || connection.kind in setOf("jellyfin", "emby")) entries else entries.filter { it.name.contains(query, true) }
        }) { entries ->
            rows.removeAllViews()
            if (entries.isEmpty()) host.text(rows, "No matching items in this library.")
            entries.forEach { entry -> keyedButton(rows, (if (entry.directory) "▸ " else "") + entry.name,
                stableTag("server-entry", connection.id, entry.id, entry.mediaSourceId)) {
                if (entry.directory) host.navigate { browse(connection, path + (entry.id to entry.name)) }
                else host.navigate { serviceItem(connection, entry) }
            } }
        }
    }

    private fun serviceItem(connection: DesktopConnectionSummary, entry: DesktopMediaEntry) {
        val body = host.page(entry.name, connection.label)
        entry.media?.let { media ->
            if (media.year.isNotBlank()) host.text(body, media.year)
            if (media.description.isNotBlank()) host.text(body, media.description)
        }
        if (connection.kind in ANIME_SERVICES) {
            entry.media?.let { media -> libraryActions(body, CameoLibraryEntry.catalog(media,
                PlaybackIdentity(media.imdbId, media.type, media.title, catalogNamespace = "service:${connection.id}"))
                .copy(providerId = connection.id, accountRevision = entry.accountRevision.ifBlank { services.accountRevision(connection.id) })) }
            host.button(body, "Update anime progress, status or score") { host.navigate { animeProgress(connection, entry) } }
            entry.media?.let { media -> host.button(body, "Find sources in installed add-ons") { host.navigate { sourceList(media, entry.id, namespace = "service:${connection.id}") } } }
        } else {
            val identity = entry.media?.let { media ->
                val episode = media.episodes.singleOrNull()
                PlaybackIdentity(media.imdbId, media.type, media.title, episode?.season ?: 0, episode?.episode ?: 0, "service:${connection.id}")
            }
            val key = "service:${connection.id}:${entry.id}:${entry.mediaSourceId}"
            host.button(body, "Play") { host.load({ services.playback(connection.id, entry) }) { playback -> host.play(playback, entry.name, identity, key, false) } }
            host.button(body, "Download") { host.load({ services.playback(connection.id, entry) }) { playback -> host.play(playback, entry.name, identity, key, true) } }
            host.button(body, "Add to playlist") { host.playlist(connection.id, entry) }
        }
    }

    private fun animeProgress(connection: DesktopConnectionSummary, entry: DesktopMediaEntry) {
        val body = host.page("Anime progress", "${entry.name} · ${connection.label}")
        val progress = host.input(body, "Episodes watched", "0")
        progress.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        val score = host.input(body, "Score 0–10 (leave blank to retain)")
        score.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        var selected = "watching"
        host.button(body, "Choose status") { choose("Anime status", DesktopServiceHub.STATUSES.map(::statusLabel)) { index ->
            selected = DesktopServiceHub.STATUSES[index]
            host.notice("Status: ${statusLabel(selected)}")
        } }
        host.button(body, "Save progress") {
            val count = progress.text.toString().toIntOrNull()
            val rating = score.text.toString().trim().let { if (it.isEmpty()) -1 else it.toIntOrNull() }
            if (count == null || rating == null || count < 0 || rating !in -1..10) host.error(IllegalArgumentException("Enter an episode count and a score from 0 to 10."))
            else {
                val status = selected
                confirm("Save anime progress?", "${entry.name}: $count episodes · ${statusLabel(status)}" + if (rating >= 0) " · Score $rating/10" else "") {
                    host.load({ services.setAnimeProgress(connection.id, entry, count, status, rating) }) { host.notice("Anime progress confirmed by ${connection.label}") }
                }
            }
        }
    }

    fun addons() {
        val body = host.page("Add-ons", "Stremio-compatible catalogs, metadata, sources and subtitles")
        host.button(body, "+ Install from manifest URL") { host.navigate(::installAddon) }
        val installed = addonApi.installed()
        if (installed.isEmpty()) host.text(body, "Install a compatible add-on using its manifest address. Add-ons can supply their declared catalogs, metadata, video sources and subtitles.")
        installed.forEach { addon -> host.button(body, "${addon.name} · ${if (addon.enabled) "Enabled" else "Disabled"}") { host.navigate { addon(addon.id) } } }
    }

    private fun installAddon() {
        val body = host.page("Install add-on", "Paste the manifest address supplied by your add-on")
        val address = host.input(body, "Manifest URL (may contain private configuration)", secret = true)
        host.text(body, "Cameo stores configured manifest addresses encrypted. Installing an add-on does not send it your connected account credentials or enable playback-event reporting.")
        host.button(body, "Check manifest and install") {
            val manifestUrl = address.text.toString()
            host.load({ addonApi.install(manifestUrl) }) { installed ->
                address.text.clear(); host.notice("${installed.name} installed"); host.navigate { addon(installed.id) }
            }
        }
    }

    private fun addon(id: String) {
        val addon = addonApi.installed().singleOrNull { it.id == id } ?: run { addons(); return }
        val body = host.page(addon.name, "${addon.manifestId} · ${addon.version}")
        if (addon.description.isNotBlank()) host.text(body, addon.description)
        host.button(body, if (addon.enabled) "Disable add-on" else "Enable add-on") { addonApi.setEnabled(id, !addon.enabled); host.navigate { addon(id) } }
        host.button(body, "Refresh manifest") { host.load({ addonApi.refresh(id) }) { host.notice("Manifest refreshed"); host.navigate { addon(id) } } }
        host.button(body, "Remove add-on") { confirm("Remove ${addon.name}?", "This removes its saved configuration from Cameo.") { addonApi.remove(id); host.navigate(::addons) } }
        if (addon.enabled) {
            host.text(body, "Catalogs", 22)
            if (addon.catalogs.isEmpty()) host.text(body, "This add-on has no catalogs. Its declared source or subtitle resources remain available for matching titles.")
            addon.catalogs.forEach { catalog -> host.button(body, "${catalog.name} · ${catalog.type}") { host.navigate { addonCatalog(id, catalog) } } }
        }
    }

    private fun addonCatalog(addonId: String, catalog: AddonCatalog, query: String = "", genre: String = "", skip: Int = 0, previousOffsets: List<Int> = emptyList()) {
        val body = host.page(catalog.name, "${catalog.type} · Starting at item ${skip + 1}")
        val searchInput = if ("search" in catalog.extras) host.input(body, "Search", query) else null
        val genreInput = if ("genre" in catalog.extras) host.input(body, "Genre", genre) else null
        if (searchInput != null || genreInput != null) host.button(body, "Apply filters") {
            host.navigate { addonCatalog(addonId, catalog, searchInput?.text?.toString()?.trim().orEmpty(), genreInput?.text?.toString()?.trim().orEmpty()) }
        }
        if (catalog.requiredExtras.any { it !in setOf("search", "genre", "skip") }) {
            host.text(body, "This catalog requires additional filters that are not supported by its current Cameo view: ${catalog.requiredExtras.filterNot { it in setOf("search", "genre", "skip") }.joinToString()}.")
            return
        }
        if ("search" in catalog.requiredExtras && query.isBlank() || "genre" in catalog.requiredExtras && genre.isBlank()) {
            host.text(body, "Enter the required filters above to open this catalog.")
            return
        }
        val rows = section(body)
        host.text(rows, "Loading catalog…")
        host.load({ addonApi.catalog(addonId, catalog, query, skip, genre) }) { items ->
            rows.removeAllViews()
            if (items.isEmpty()) host.text(rows, "No titles were returned for these filters.")
            items.forEach { media -> keyedButton(rows, media.item.title + (if (media.item.year.isBlank()) "" else " · ${media.item.year}"),
                stableTag("addon-title", media.addonId, media.type, media.id)) {
                host.navigate { addonDetail(media) }
            } }
            if ("skip" in catalog.extras) {
                if (skip > 0) host.button(rows, "← Previous page") { host.navigate { addonCatalog(addonId, catalog, query, genre, previousOffsets.lastOrNull() ?: 0, previousOffsets.dropLast(1)) } }
                if (items.isNotEmpty()) host.button(rows, "Next page →") { host.navigate { addonCatalog(addonId, catalog, query, genre, skip + items.size, previousOffsets + skip) } }
            }
        }
    }

    private fun addonDetail(summary: AddonMedia) {
        val body = host.page(summary.item.title, "${summary.type} · Add-on catalog")
        val details = section(body)
        host.text(details, "Loading title details…")
        val owner = addonApi.installed().singleOrNull { it.id == summary.addonId }
        if (owner?.resources?.none { it.name == "meta" } != false) renderAddonDetail(details, summary)
        else host.load({ addonApi.detail(summary.addonId, summary.type, summary.id) }) { full -> renderAddonDetail(details, full) }
    }

    private fun renderAddonDetail(body: LinearLayout, media: AddonMedia) {
        body.removeAllViews()
        if (media.item.description.isNotBlank()) host.text(body, media.item.description)
        libraryActions(body, savedAddon(media, if (media.type == "series") "" else media.id))
        if (media.type == "series") {
            val seasons = media.episodes.groupBy { it.season }.toSortedMap()
            if (seasons.isEmpty()) host.text(body, "This metadata provider has not supplied playable episode identities for this title.")
            seasons.forEach { (season, episodes) ->
                host.text(body, if (season == 0) "Specials" else "Season $season", 22)
                episodes.forEach { episode -> keyedButton(body, "${episode.number}. ${episode.title}",
                    stableTag("addon-episode", media.addonId, media.id, episode.id)) {
                    host.navigate { sourceList(media.item, episode.id, episode.season, episode.number, media.addonId) }
                } }
            }
        } else host.button(body, "Choose source") { host.navigate { sourceList(media.item, media.id, namespace = media.addonId) } }
    }

    private fun sourceList(media: MediaItemData, videoId: String, season: Int = 0, episode: Int = 0, namespace: String = "") {
        val identity = PlaybackIdentity(media.imdbId, media.type, media.title, season, episode, namespace)
        val body = host.page(media.title, if (episode > 0) "Season $season · Episode $episode" else "Choose an add-on source")
        if (addonApi.installed().any { it.id == namespace }) libraryActions(body,
            savedAddon(AddonMedia(media, namespace, media.type, media.imdbId), videoId, season, episode))
        val rows = section(body)
        host.text(rows, "Finding sources…")
        host.load({
            val issues = mutableListOf<String>()
            val sources = addonApi.installed().filter { it.enabled && supports(it, "stream", identity.type, videoId) }.flatMap { addon ->
                try { addonApi.streams(addon.id, identity, videoId) } catch (error: Exception) { issues += "${addon.name}: ${addonIssue(error)}"; emptyList() }
            }
            sources to issues
        }) { (sources, issues) ->
            fun render(quality: String? = null) {
                rows.removeAllViews()
                if (sources.isEmpty()) host.text(rows, "No compatible add-on returned sources for this exact title or episode. Install a source add-on that supports its catalog identity.")
                else {
                    val qualities = CameoSourceQuality.options(sources)
                    host.button(rows, "Resolution: ${quality ?: "All resolutions"}") { choose("Choose resolution", listOf("All resolutions") + qualities) { index ->
                        render(if (index == 0) null else qualities[index - 1])
                        rows.post { rows.getChildAt(0)?.requestFocus() }
                    } }
                    val choices = if (quality == null) sources else CameoSourceQuality.filter(sources, quality)
                    choices.forEach { source -> keyedButton(rows, "${source.provider} · ${source.title}",
                        stableTag("addon-source", namespace, source.sourceAddonId, TvChoiceKeys.source(source), sources.indexOf(source).toString())) {
                        val actions = if (source.directUrl.isBlank()) listOf("Play / choose file", "Download / choose file") else listOf("Play", "Download")
                        choose("Selected source · ${CameoSourceQuality.label(source)}", actions) { index -> host.prepareSource(source, identity, index == 1) }
                    } }
                }
                if (issues.isNotEmpty()) host.text(rows, "Some add-ons could not supply this title:\n${issues.distinct().joinToString("\n")}")
                host.button(rows, "Find add-on subtitles") { host.navigate { addonSubtitles(media.type, videoId) } }
            }
            render()
        }
    }

    private fun addonSubtitles(type: String, videoId: String) {
        val body = host.page("Add-on subtitles", "Choose a subtitle file to download and attach to the current video")
        val rows = section(body)
        host.text(rows, "Searching subtitles…")
        host.load({
            val issues = mutableListOf<String>()
            val results = addonApi.installed().filter { it.enabled && supports(it, "subtitles", type, videoId) }.flatMap { addon ->
                try { addonApi.subtitles(addon.id, type, videoId) } catch (error: Exception) { issues += "${addon.name}: ${addonIssue(error)}"; emptyList() }
            }
            results to issues
        }) { (results, issues) ->
            rows.removeAllViews()
            if (results.isEmpty()) host.text(rows, "No compatible subtitle add-on returned subtitles for this exact video.")
            if (issues.isNotEmpty()) host.text(rows, "Some subtitle add-ons were unavailable:\n${issues.distinct().joinToString("\n")}")
            results.forEach { subtitle -> host.button(rows, "${subtitle.language} · ${subtitle.provider} · ${subtitle.id}") {
                host.subtitle(subtitle.url, subtitle.language, "${subtitle.provider} subtitle")
            } }
        }
    }

    private fun savedAddon(media: AddonMedia, videoId: String, season: Int = 0, episode: Int = 0) =
        CameoLibraryEntry.catalog(media.item, PlaybackIdentity(media.id, media.type, media.item.title, season, episode, media.addonId))
            .copy(providerId = media.addonId, folderId = media.type, fileId = videoId)
    private fun libraryActions(body: LinearLayout, entry: CameoLibraryEntry) {
        val actions = section(body)
        fun render() {
            val restoreFocus = actions.hasFocus()
            actions.removeAllViews()
            val saved = host.isWatchlisted(entry)
            keyedButton(actions, if (saved) "Remove from watchlist" else "Add to watchlist", stableTag("watchlist", entry.id)) { host.watchlist(entry, !saved); render() }
            keyedButton(actions, "Add to playlist", stableTag("playlist", entry.id)) { host.playlist(entry) }
            if (restoreFocus) actions.getChildAt(0)?.requestFocus()
        }
        render()
    }
    private fun addonIssue(error: Exception): String = when (error) {
        is ApiException -> when (error.code) {
            401, 403 -> "Authorization failed; check this add-on's configuration."
            404 -> "This resource or title is unavailable."
            429 -> "Rate limited; try again later."
            else -> "The add-on could not complete its request."
        }
        is IllegalArgumentException -> "This resource does not support the selected title or episode identity."
        else -> "The response was unavailable or could not be validated."
    }
    private fun supports(addon: InstalledAddon, name: String, type: String, id: String) = addon.resources.any { resource ->
        val types = if (resource.inheritGlobalFilters) addon.types else resource.types
        val prefixes = if (resource.inheritGlobalFilters) addon.idPrefixes else resource.idPrefixes
        resource.name == name && (types.isEmpty() || type in types) && (prefixes.isEmpty() || prefixes.any(id::startsWith))
    }
    private fun keyedButton(body: LinearLayout, label: String, key: String, action: () -> Unit) {
        host.button(body, label, action)
        body.getChildAt(body.childCount - 1)?.tag = key
    }
    private fun stableTag(vararg parts: String): String = "cameo:" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun section(body: LinearLayout) = TvChoiceList(host.context).apply {
        body.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun choose(title: String, options: List<String>, selected: (Int) -> Unit) {
        AlertDialog.Builder(host.context).setTitle(title).setItems(options.toTypedArray()) { _, index -> selected(index) }.setNegativeButton("Cancel", null).show()
    }
    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(host.context).setTitle(title).setMessage(message).setPositiveButton("Confirm") { _, _ -> action() }.setNegativeButton("Cancel", null).show()
    }
    private fun statusLabel(status: String) = when (status) { "onHold" -> "On hold"; "repeating" -> "Rewatching"; else -> status.replaceFirstChar { it.uppercase() } }
    companion object { private val ANIME_SERVICES = setOf("anilist", "myanimelist", "kitsu") }
}
