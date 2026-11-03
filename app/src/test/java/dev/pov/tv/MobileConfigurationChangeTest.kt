package dev.pov.tv

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutorService
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.math.roundToInt

/** Actual Activity resize/navigation callback, with synthetic content and a non-network player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w360dp-h800dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MobileConfigurationChangeTest {
    @Test fun `fold resize keeps five bottom controls and preserves route back stack scroll and shared playback`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        assertTrue("Production manifest dispatches density/font-scale changes to the retained Activity",
            mainManifestChanges().containsAll(setOf("density", "fontScale")))
        set(activity, "theme", ThemeSpec.GLASS)
        set(activity, "publicPreview", true)
        set(activity, "playbackAuditPreview", true)
        set(activity, "playbackPreferences", PlaybackPreferences(activity))
        set(activity, "destination", "anime")
        set(activity, "resumed", true)
        val sessionField = InAppPlaybackSession::class.java.getDeclaredField("player").apply { isAccessible = true }
        val previousPlayer = sessionField.get(InAppPlaybackSession)
        val player = RecordingPlayer()
        sessionField.set(InAppPlaybackSession, player)
        try {
            invoke(activity, "shell")
            // Attach Main's decor without invoking onCreate/accounts, as the existing remote fixtures do.
            // View.post queues restoration on an unattached view until that attachment happens.
            controller.visible().windowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            var detailRenders = 0
            val parentScreen: () -> Unit = { renderFixture(activity, "Anime overview") }
            val detailScreen: () -> Unit = { detailRenders++; renderFixture(activity, "Chosen series · season 3") }
            set(activity, "current", parentScreen)
            parentScreen()
            invoke(activity, "navigate", arrayOf(kotlin.jvm.functions.Function0::class.java), detailScreen)
            @Suppress("UNCHECKED_CAST")
            val seasons = field(activity, "selectedSeasons") as MutableMap<String, Int>
            seasons["anime:fixture"] = 3
            @Suppress("UNCHECKED_CAST")
            val pages = field(activity, "catalogOffsets") as MutableMap<String, ArrayDeque<Int>>
            pages["series"] = ArrayDeque(listOf(0, 20))
            layout(activity, 360, 800)
            shadowOf(Looper.getMainLooper()).idle()
            (field(activity, "scroller") as ScrollView).scrollTo(0, 420)
            val savedScroll = (field(activity, "scroller") as ScrollView).scrollY
            assertTrue(savedScroll > 0)
            assertTrue(player.isPlaying)
            assertBottomNavigationAndPlaybackClearance(activity)

            val resizedWindows = listOf(Triple("w600dp-h800dp-port-mdpi", 600, 800),
                Triple("w720dp-h690dp-land-mdpi", 720, 690),
                Triple("w720dp-h690dp-land-xhdpi", 720, 690),
                Triple("w840dp-h800dp-land-mdpi", 840, 800),
                Triple("w1024dp-h800dp-land-mdpi", 1024, 800),
                Triple("w360dp-h800dp-port-mdpi", 360, 800))
            resizedWindows.forEach { (qualifiers, width, height) ->
                RuntimeEnvironment.setQualifiers(qualifiers)
                assertEquals(if (qualifiers.endsWith("xhdpi")) 2f else 1f, activity.resources.displayMetrics.density, .001f)
                activity.onConfigurationChanged(Configuration(activity.resources.configuration))
                layout(activity, width, height)
                shadowOf(Looper.getMainLooper()).idle()
                layout(activity, width, height)
                assertSame(detailScreen, field(activity, "current"))
                assertEquals("anime", field(activity, "destination"))
                assertEquals(1, (field(activity, "backStack") as ArrayDeque<*>).size)
                assertEquals(3, seasons["anime:fixture"])
                assertEquals(listOf(0, 20), pages["series"]?.toList())
                assertEquals("Restored scroll at $qualifiers", savedScroll, (field(activity, "scroller") as ScrollView).scrollY)
                assertSame(player, InAppPlaybackSession.currentPlayer)
                assertTrue(player.isPlaying)
                assertTrue(player.currentPosition >= 34_000)
                assertEquals(0, player.stops)
                assertEquals(0, player.releases)
                assertEquals(0, player.pauses)
                assertBottomNavigationAndPlaybackClearance(activity)
            }
            assertEquals(1 + resizedWindows.size, detailRenders)
            activity.onBackPressed()
            assertEquals(0, (field(activity, "backStack") as ArrayDeque<*>).size)
            assertSame(parentScreen, field(activity, "current"))
            assertEquals("Anime overview", ((field(activity, "body") as LinearLayout).getChildAt(0) as TextView).text.toString())
        } finally {
            set(activity, "playbackAuditPreview", false)
            if (field(activity, "miniFrame") != null) invoke(activity, "updateMiniPlayer")
            sessionField.set(InAppPlaybackSession, previousPlayer)
            for (name in listOf("worker", "images", "authWorker")) (field(activity, name) as ExecutorService).shutdownNow()
            player.release()
            activity.finish()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun assertBottomNavigationAndPlaybackClearance(activity: MainActivity) {
        val root = field(activity, "shellRoot") as ViewGroup
        val density = activity.resources.displayMetrics.density
        val navigation = root.findViewWithTag<LinearLayout>("mobile:navigation")
        val frame = root.findViewWithTag<FrameLayout>("mobile:navigation-frame")
        val expectedLabels = listOf("Home", "Browse", "Anime", "Library", "Search")
        val expectedKeys = listOf("home", "movie", "anime", "library", "search")
        assertEquals("mobile-shell:bottom", root.tag)
        assertEquals(LinearLayout.HORIZONTAL, navigation.orientation)
        assertEquals(5, navigation.childCount)
        @Suppress("UNCHECKED_CAST")
        val navigationViews = field(activity, "navigationViews") as Map<String, View>
        assertSame(navigationViews["movie"], navigationViews["series"])
        assertTrue("The selected section survives window changes", navigationViews.getValue("anime").isSelected)
        expectedKeys.forEachIndexed { index, key ->
            val button = navigation.getChildAt(index) as ViewGroup
            assertSame(navigationViews.getValue(key), button)
            assertEquals("nav:$key", button.tag)
            assertEquals(expectedLabels[index], (button.getChildAt(1) as TextView).text.toString())
            assertTrue("$key remains at least 48dp wide", button.width >= (48 * density).roundToInt())
            assertTrue("$key remains at least 48dp tall", button.height >= (48 * density).roundToInt())
        }
        fun bounds(view: View) = Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
        val navBounds = bounds(frame)
        val frameMargins = frame.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals("Navigation stays anchored above the system bottom inset",
            root.height - root.paddingBottom - frameMargins.bottomMargin, navBounds.bottom)
        assertTrue("Content ends above the bottom menu", bounds(field(activity, "scroller") as View).bottom <= navBounds.top)
        val miniPlayer = field(activity, "miniFrame") as View
        assertEquals(View.VISIBLE, miniPlayer.visibility)
        assertTrue("Playing video leaves the five bottom controls clear", bounds(miniPlayer).bottom <= navBounds.top)
        assertTrue("Scrollable content keeps mini-player clearance", (field(activity, "body") as View).paddingBottom >= (184 * density).roundToInt())
    }

    private fun renderFixture(activity: MainActivity, title: String) {
        val generation = invoke(activity, "page", arrayOf(String::class.java, String::class.java), title, "Offline public fixture") as Int
        val body = field(activity, "body") as LinearLayout
        body.addView(TextView(activity).apply { text = "Long synthetic episode list" }, LinearLayout.LayoutParams(-1, 2200))
        invoke(activity, "focusAfterRender", arrayOf(Int::class.javaPrimitiveType!!), generation)
    }
    private fun layout(activity: MainActivity, width: Int, height: Int) {
        val root = field(activity, "shellRoot") as View
        val density = activity.resources.displayMetrics.density
        val pixelWidth = (width * density).roundToInt()
        val pixelHeight = (height * density).roundToInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(pixelWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(pixelHeight, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, pixelWidth, pixelHeight)
    }
    private fun mainManifestChanges(): Set<String> {
        // AGP's local-test manifest deliberately has no app activities. Robolectric synthesizes
        // ActivityInfo(configChanges=0) for buildActivity, so verify the real app declaration.
        val properties = Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")?.use(properties::load)
        val testManifest = properties.getProperty("android_merged_manifest")?.let(::File)?.absoluteFile
        val intermediates = generateSequence(testManifest?.parentFile) { it.parentFile }.firstOrNull { it.name == "intermediates" }
        val variant = testManifest?.let { path -> intermediates?.let { path.relativeTo(it).invariantSeparatorsPath.split('/').getOrNull(1) } }
            ?.removeSuffix("UnitTest") ?: "debug"
        val taskVariant = variant.replaceFirstChar { it.uppercase() }
        val merged = intermediates?.resolve("merged_manifests/$variant/process${taskVariant}Manifest/AndroidManifest.xml")
        // A clean test-only build may not produce the APK's merged manifest; the checked-in app
        // declaration is the input in that case. Full builds verify the production merge itself.
        val manifest = merged?.takeIf { it.isFile } ?: File("src/main/AndroidManifest.xml")
        assertTrue("The application manifest is available for this integration fixture", manifest.isFile)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val android = "http://schemas.android.com/apk/res/android"
        val nodes = document.getElementsByTagName("activity")
        val main = (0 until nodes.length).map { nodes.item(it) as Element }.single {
            it.getAttributeNS(android, "name") in setOf(".MainActivity", MainActivity::class.java.name)
        }
        return main.getAttributeNS(android, "configChanges").split('|').toSet()
    }
    private class RecordingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var releases = 0; var stops = 0; var pauses = 0
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_RELEASE, Player.COMMAND_STOP,
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE).build())
            .setPlaylist(listOf(MediaItemData.Builder("fold-fixture").setMediaItem(MediaItem.fromUri("https://fixture.invalid/video.mkv")).setDurationUs(120_000_000).build()))
            .setContentPositionMs(34_000)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        override fun handleStop(): ListenableFuture<*> { stops++; return Futures.immediateVoidFuture() }
        override fun handleRelease(): ListenableFuture<*> { releases++; return Futures.immediateVoidFuture() }
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (!playWhenReady) pauses++
            return Futures.immediateVoidFuture()
        }
    }
    private fun invoke(activity: MainActivity, name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? =
        MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(activity, *args)
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun set(activity: MainActivity, name: String, value: Any) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
}
