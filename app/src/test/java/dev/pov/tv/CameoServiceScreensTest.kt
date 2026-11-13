package dev.pov.tv

import android.app.Activity
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.pov.tv.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameoServiceScreensTest {
    private class Connections : DesktopConnectionStore { var value = ""; override fun get() = value; override fun put(value: String) { this.value = value } }
    private class Addons : AddonStore { var value = ""; override fun get() = value; override fun put(value: String) { this.value = value } }
    private class Http(private val answer: (String) -> String) : DesktopTransport {
        override fun request(url: String, method: String, headers: Map<String, String>, body: String?, contentType: String) = answer(url)
    }
    private class Host(override val context: Context) : CameoServiceScreens.Host {
        val actions = linkedMapOf<String, () -> Unit>()
        val fields = linkedMapOf<String, EditText>()
        var lastPage = ""
        var existingConnectionCalls = 0
        var existingServicesCalls = 0
        var prepared: Pair<SourceItem, PlaybackIdentity>? = null
        var preparedDownload = false
        var saved: CameoLibraryEntry? = null
        var playCalls = 0
        var currentBody: LinearLayout? = null
        val messages = mutableListOf<String>()
        var lastError: Throwable? = null
        override fun page(title: String, subtitle: String): LinearLayout { lastPage = title; actions.clear(); fields.clear(); return LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; currentBody = this; addView(TextView(context).apply { text = title }) } }
        override fun navigate(screen: () -> Unit) = screen()
        override fun <T> load(work: () -> T, done: (T) -> Unit) { try { done(work()) } catch (error: Throwable) { lastError = error } }
        override fun text(body: LinearLayout, message: String, size: Int) { messages += message; body.addView(TextView(context).apply { text = message }) }
        override fun button(body: LinearLayout, label: String, action: () -> Unit) { actions[label] = action; body.addView(Button(context).apply { text = label; setOnClickListener { action() } }) }
        override fun input(body: LinearLayout, label: String, value: String, secret: Boolean) = EditText(context).apply {
            hint = label; inputType = android.text.InputType.TYPE_CLASS_TEXT or if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
            setText(value); fields[label] = this; body.addView(this)
        }
        override fun prepareSource(source: SourceItem, identity: PlaybackIdentity, download: Boolean) { prepared = source to identity; preparedDownload = download }
        override fun play(playback: ProviderPlayback, title: String, identity: PlaybackIdentity?, key: String, download: Boolean) { playCalls++ }
        override fun playlist(connectionId: String, entry: DesktopMediaEntry) = Unit
        override fun playlist(entry: CameoLibraryEntry) { saved = entry }
        override fun watchlist(entry: CameoLibraryEntry, added: Boolean) { saved = if (added) entry else null }
        override fun isWatchlisted(entry: CameoLibraryEntry) = saved?.id == entry.id
        override fun subtitle(url: String, language: String, name: String) = Unit
        override fun existingConnections(body: LinearLayout) { existingConnectionCalls++ }
        override fun existingServices(body: LinearLayout) { existingServicesCalls++ }
        override fun notice(message: String) = Unit
        override fun error(error: Throwable) { lastError = error }
        fun click(label: String) = checkNotNull(actions[label]) { "Missing UI action: $label" }.invoke()
    }

    @Test fun `connections and service libraries retain existing adapters while adding new routes`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = Host(activity)
        val screens = CameoServiceScreens(host, DesktopServiceHub(Connections(), Http { error("No network expected") }), AddonCatalogApi(Addons(), Http { error("No network expected") }))
        screens.connections()
        assertEquals(1, host.existingConnectionCalls)
        assertTrue(host.actions.containsKey("Stremio-compatible add-ons"))
        host.click("+ Add media server or anime account")
        assertEquals(DesktopServiceHub.SPECS.map { it.label }.toSet(), host.actions.keys)
        screens.myServices()
        assertEquals(1, host.existingServicesCalls)
        assertTrue(host.actions.containsKey("Manage connections"))
        assertTrue(host.actions.containsKey("Browse add-on catalogs"))
    }

    @Test fun `custom catalog source selection preserves namespace and proxy headers through real screens`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = Host(activity)
        val http = Http { url -> when {
            url.endsWith("manifest.json") -> """{"id":"catalog.fixture","name":"Catalog fixture","types":["movie"],"resources":["catalog","meta","stream"],"catalogs":[{"id":"all","name":"All films","type":"movie"}]}"""
            url.contains("/catalog/") -> """{"metas":[{"id":"custom:42","type":"movie","name":"Film"}]}"""
            url.contains("/meta/") -> """{"meta":{"id":"custom:42","type":"movie","name":"Film","description":"Example"}}"""
            url.contains("/stream/") -> """{"streams":[{"name":"Edition","url":"https://video.example/file.mkv","behaviorHints":{"proxyHeaders":{"request":{"Authorization":"Bearer test-fixture"}}}}]}"""
            else -> error("Unexpected request")
        } }
        val api = AddonCatalogApi(Addons(), http)
        val installed = api.install("https://catalog.example/manifest.json")
        val screens = CameoServiceScreens(host, DesktopServiceHub(Connections(), Http { error("Unexpected server request") }), api)
        screens.addons()
        host.click("Catalog fixture · Enabled")
        host.click("All films · movie")
        host.click("Film")
        host.click("Add to watchlist")
        assertTrue(buttonLabels(host.currentBody!!).contains("Remove from watchlist"))
        assertFalse(buttonLabels(host.currentBody!!).contains("Add to watchlist"))
        host.click("Remove from watchlist")
        assertNull(host.saved)
        assertTrue(buttonLabels(host.currentBody!!).contains("Add to watchlist"))
        host.click("Choose source")
        host.click("Catalog fixture · Edition")
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 0, 0)
        assertNull(host.lastError)
        assertEquals(installed.id, host.prepared!!.second.catalogNamespace)
        assertEquals("custom:42", host.prepared!!.second.imdbId)
        assertEquals("Bearer test-fixture", host.prepared!!.first.requestHeaders["Authorization"])
    }

    @Test fun `manifest installation reads input before asynchronous work and routes to installed addon`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = Host(activity)
        val api = AddonCatalogApi(Addons(), Http { """{"id":"fixture","name":"Fixture","resources":["stream"]}""" })
        val screens = CameoServiceScreens(host, DesktopServiceHub(Connections(), Http { error("Unexpected request") }), api)
        screens.addons()
        host.click("+ Install from manifest URL")
        host.fields.getValue("Manifest URL (may contain private configuration)").setText("https://addon.example")
        host.click("Check manifest and install")
        assertNull(host.lastError)
        assertEquals("Fixture", host.lastPage)
        assertEquals(1, api.installed().size)
        host.click("Disable add-on")
        assertFalse(api.installed().single().enabled)
    }

    @Test fun `saved custom episode reopens original addon video ID and rejects changed episode mapping`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = Host(activity)
        var changed = false
        var streamCalls = 0
        val api = AddonCatalogApi(Addons(), Http { url -> when {
            url.endsWith("manifest.json") -> """{"id":"fixture","name":"Fixture","types":["series"],"resources":["meta","stream"]}"""
            url.contains("/meta/") -> """{"meta":{"id":"custom:42","name":"Show","type":"series","videos":[{"id":"cut:unique","season":2,"episode":${if (changed) 5 else 4},"title":"Part"}]}}"""
            url.contains("/stream/") -> { streamCalls++; assertTrue(url.endsWith("/cut%3Aunique.json")); """{"streams":[{"name":"Edition","url":"https://video.example/file.mkv"}]}""" }
            else -> error("Unexpected request")
        } })
        val addon = api.install("https://addon.example")
        val identity = PlaybackIdentity("custom:42", "series", "Show", 2, 4, addon.id)
        val entry = CameoLibraryEntry.catalog(MediaItemData("custom:42", "series", "Show"), identity)
            .copy(providerId = addon.id, folderId = "series", fileId = "cut:unique")
        val screens = CameoServiceScreens(host, DesktopServiceHub(Connections(), Http { error("Unexpected server request") }), api)
        screens.openSaved(entry)
        assertNull(host.lastError)
        host.click("Add to playlist")
        assertEquals("cut:unique", host.saved!!.fileId)
        assertEquals(addon.id, host.saved!!.identity!!.catalogNamespace)
        assertEquals(1, streamCalls)
        changed = true
        screens.openSaved(entry)
        assertNotNull(host.lastError)
        assertEquals(1, streamCalls)
    }

    @Test fun `one source addon failure is visible while working sources remain selectable`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = Host(activity)
        val api = AddonCatalogApi(Addons(), Http { url -> when {
            url.endsWith("manifest.json") -> """{"id":"fixture","name":"${if (url.contains("bad.example")) "Bad addon" else "Good addon"}","types":["movie"],"resources":["meta","stream"]}"""
            url.contains("/meta/") -> """{"meta":{"id":"custom:42","name":"Film","type":"movie"}}"""
            url.contains("bad.example") -> throw ApiException(401, "Do not expose raw fixture credentials")
            else -> """{"streams":[{"name":"Edition","url":"https://video.example/file.mkv"}]}"""
        } })
        val owner = api.install("https://good.example")
        api.install("https://bad.example")
        val screens = CameoServiceScreens(host, DesktopServiceHub(Connections(), Http { error("Unexpected request") }), api)
        screens.openSaved(CameoLibraryEntry.catalog(MediaItemData("custom:42", "movie", "Film"),
            PlaybackIdentity("custom:42", "movie", "Film", catalogNamespace = owner.id)).copy(providerId = owner.id, folderId = "movie", fileId = "custom:42"))
        host.click("Choose source")
        assertTrue(host.actions.containsKey("Good addon · Edition"))
        assertTrue(host.messages.any { it.contains("Bad addon: Authorization failed") })
        assertFalse(host.messages.any { it.contains("raw fixture credentials") })
    }

    @Test fun `advertised resolution filter retains explicit source selection and download action`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = Host(activity)
        val api = AddonCatalogApi(Addons(), Http { url -> when {
            url.endsWith("manifest.json") -> """{"id":"fixture","name":"Fixture","types":["movie"],"idPrefixes":["tt"],"resources":[{"name":"meta","types":["movie"]},{"name":"stream","types":["movie"]}]}"""
            url.contains("/meta/") -> """{"meta":{"id":"custom:42","name":"Film","type":"movie"}}"""
            else -> """{"streams":[{"name":"Edition 1080p","url":"https://video.example/a.mkv"},{"name":"Edition 720p","url":"https://video.example/b.mkv"},{"name":"Unspecified","url":"https://video.example/4k-address-is-not-evidence.mkv"}]}"""
        } })
        val addon = api.install("https://addon.example")
        val screens = CameoServiceScreens(host, DesktopServiceHub(Connections(), Http { error("Unexpected request") }), api)
        screens.openSaved(CameoLibraryEntry.catalog(MediaItemData("custom:42", "movie", "Film"),
            PlaybackIdentity("custom:42", "movie", "Film", catalogNamespace = addon.id)).copy(providerId = addon.id, fileId = "custom:42"))
        host.click("Choose source")
        host.click("Resolution: All resolutions")
        val qualityDialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(listOf("All resolutions", "1080p", "720p", CameoSourceQuality.UNKNOWN),
            (0 until qualityDialog.listView.adapter.count).map { qualityDialog.listView.adapter.getItem(it).toString() })
        qualityDialog.listView.performItemClick(null, 2, 2)
        assertTrue(buttonLabels(host.currentBody!!).contains("Fixture · Edition 720p"))
        assertFalse(buttonLabels(host.currentBody!!).contains("Fixture · Edition 1080p"))
        assertNull(host.prepared)
        host.click("Fixture · Edition 720p")
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 1, 1)
        assertEquals("https://video.example/b.mkv", host.prepared!!.first.directUrl)
        assertTrue(host.preparedDownload)
    }

    @Test fun `identically labeled stream editions have distinct opaque stable focus tags`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = Host(activity)
        val api = AddonCatalogApi(Addons(), Http { url -> when {
            url.endsWith("manifest.json") -> """{"id":"fixture","name":"Fixture","types":["movie"],"resources":["meta","stream"]}"""
            url.contains("/meta/") -> """{"meta":{"id":"custom:42","name":"Film","type":"movie"}}"""
            else -> """{"streams":[{"name":"Same edition","url":"https://video.example/file?token=fixture-secret","behaviorHints":{"proxyHeaders":{"request":{"X-Edition":"one"}}}},{"name":"Same edition","url":"https://video.example/file?token=fixture-secret","behaviorHints":{"proxyHeaders":{"request":{"X-Edition":"two"}}}}]}"""
        } })
        val addon = api.install("https://addon.example")
        val saved = CameoLibraryEntry.catalog(MediaItemData("custom:42", "movie", "Film"),
            PlaybackIdentity("custom:42", "movie", "Film", catalogNamespace = addon.id)).copy(providerId = addon.id, fileId = "custom:42")
        val screens = CameoServiceScreens(host, DesktopServiceHub(Connections(), Http { error("Unexpected request") }), api)
        screens.openSaved(saved); host.click("Choose source")
        val sourceButtons = buttons(host.currentBody!!).filter { it.text.toString() == "Fixture · Same edition" }
        assertEquals(2, sourceButtons.size)
        assertNotEquals(sourceButtons[0].tag, sourceButtons[1].tag)
        assertTrue(sourceButtons.all { it.parent is TvChoiceList })
        val tags = sourceButtons.map { it.tag.toString() }
        assertTrue(tags.all { it.matches(Regex("cameo:[a-f0-9]{64}")) })
        assertFalse(tags.any { it.contains("fixture-secret") || it.contains("video.example") })
        screens.openSaved(saved); host.click("Choose source")
        assertEquals(tags, buttons(host.currentBody!!).filter { it.text.toString() == "Fixture · Same edition" }.map { it.tag.toString() })
    }

    @Test fun `stale server detail cannot play or download using replacement account`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = Host(activity)
        var playbackInfoRequests = 0
        val http = Http { url -> when {
            url.endsWith("Users/Me") -> """{"Id":"user1","Name":"Owner"}"""
            url.contains("/Items?") -> """{"Items":[{"Id":"film1","Name":"Film","Type":"Movie","MediaSources":[{"Id":"edition1"}]}]}"""
            url.contains("/PlaybackInfo?") -> { playbackInfoRequests++; """{"MediaSources":[{"Id":"edition1"}]}""" }
            else -> error("Unexpected request")
        } }
        val services = DesktopServiceHub(Connections(), http)
        val connection = services.connect(DesktopConnectionInput("jellyfin", url = "https://media.example", token = "first-fixture"))
        val screens = CameoServiceScreens(host, services, AddonCatalogApi(Addons(), Http { error("No add-on request expected") }))
        screens.myServices()
        host.click("Jellyfin · Owner")
        host.click("Film")
        services.connect(DesktopConnectionInput("jellyfin", id = connection.id, token = "replacement-fixture"))
        host.click("Play")
        assertTrue(host.lastError is IllegalStateException)
        assertTrue(host.lastError!!.message!!.contains("account changed"))
        host.lastError = null
        host.click("Download")
        assertTrue(host.lastError is IllegalStateException)
        assertEquals(0, host.playCalls)
        assertEquals(0, playbackInfoRequests)
    }

    @Test fun `connection draft survives native form rebuild with password and selection only in memory`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = Host(activity)
        val screens = CameoServiceScreens(host, DesktopServiceHub(Connections(), Http { error("No account request expected") }),
            AddonCatalogApi(Addons(), Http { error("No add-on request expected") }))
        fun openForm() { screens.connections(); host.click("+ Add media server or anime account"); host.click("Jellyfin"); activity.setContentView(host.currentBody) }
        openForm()
        val fixtureServer="http://${listOf(192,168,0,12).joinToString(".")}:8096"
        host.fields.getValue("Server address (HTTPS or a local HTTP server)").setText(fixtureServer)
        host.fields.getValue("Username").setText("draft-user")
        host.fields.getValue("Password").apply { setText("fixture-only-unsaved-password"); requestFocus(); setSelection(3, 12) }
        val snapshot = CameoFormDrafts.capture(host.currentBody!!)
        assertFalse(snapshot.toString().contains("password"))
        openForm()
        assertEquals("", host.fields.getValue("Password").text.toString())
        CameoFormDrafts.restore(host.currentBody!!, snapshot)
        assertEquals(fixtureServer, host.fields.getValue("Server address (HTTPS or a local HTTP server)").text.toString())
        assertEquals("draft-user", host.fields.getValue("Username").text.toString())
        host.fields.getValue("Password").let { input ->
            assertEquals("fixture-only-unsaved-password", input.text.toString())
            assertEquals(3, input.selectionStart); assertEquals(12, input.selectionEnd); assertTrue(input.hasFocus())
        }
        assertNull(host.lastError)
    }

    private fun buttonLabels(group: android.view.ViewGroup): List<String> = (0 until group.childCount).flatMap { index ->
        when (val child = group.getChildAt(index)) {
            is Button -> listOf(child.text.toString())
            is android.view.ViewGroup -> buttonLabels(child)
            else -> emptyList()
        }
    }
    private fun buttons(group: android.view.ViewGroup): List<Button> = (0 until group.childCount).flatMap { index ->
        when (val child = group.getChildAt(index)) {
            is Button -> listOf(child)
            is android.view.ViewGroup -> buttons(child)
            else -> emptyList()
        }
    }
}
