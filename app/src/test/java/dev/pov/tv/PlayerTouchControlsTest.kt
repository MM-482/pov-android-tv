package dev.pov.tv

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowInstrumentation
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Fullscreen touch dispatch with local player doubles; no decoder, account or network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w360dp-h800dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerTouchControlsTest {
    private var previousFrameDelay = Duration.ofMillis(15)
    @Before fun useResponsiveFrames() {
        previousFrameDelay = ShadowChoreographer.getFrameDelay()
        // Keep real traversals/click dispatch active without SDK 28's default
        // 15 ms clock jump for every drawing callback obscuring hide deadlines.
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }
    @After fun restoreDrawingClock() { ShadowChoreographer.setFrameDelay(previousFrameDelay) }

    @Test fun `Media3 controls can be restored repeatedly then operated without replacing playback`() {
        withMedia3Screen { screen, player ->
            assertRepeatedRestoration(screen, player)
            screen.tap(screen.view.findViewById(androidx.media3.ui.R.id.exo_play_pause))
            assertFalse("The restored pause button receives its first tap", player.playWhenReady)
            assertEquals(1, player.pauses)
            screen.tap(screen.view.findViewById(androidx.media3.ui.R.id.exo_play_pause))
            assertTrue(player.playWhenReady)
            assertEquals(1, player.plays)
            assertOptionsOpenOnFirstTap(screen)
            assertSame(player, InAppPlaybackSession.currentPlayer)
            assertEquals(34_000L, player.currentPosition)
            assertEquals(0, player.prepares)
            assertEquals(0, player.stops)
            assertEquals(0, player.releases)
        }
    }

    @Test fun `LibVLC video and subtitle surfaces restore the same controls without reopening media`() {
        val driver = Driver()
        val player = LibVlcPlayer(driver, "en")
        try {
            player.setMediaItem(MediaItem.fromUri("https://fixture.invalid/touch-video.mkv"))
            player.prepare(); player.play(); driver.ready()
            driver.position = 34_000L
            driver.listener?.invoke(VlcEvent.CHANGED)
            val plays = driver.plays
            withScreen(player, PlaybackEngine.VLC) { screen ->
                assertNotNull("Exercise touch through VLC's subtitle surface too",
                    screen.view.findViewWithTag<View>("pov_vlc_subtitle_surface"))
                assertRepeatedRestoration(screen, player)
                assertEquals(plays, driver.plays)
                assertEquals(0, driver.pauses)
                screen.tap(screen.view.findViewById(androidx.media3.ui.R.id.exo_play_pause))
                assertFalse(player.playWhenReady)
                assertEquals(1, driver.pauses)
                driver.acknowledgePause()
                screen.tap(screen.view.findViewById(androidx.media3.ui.R.id.exo_play_pause))
                driver.ready()
                assertTrue(player.isPlaying)
                assertEquals(plays + 1, driver.plays)
                assertOptionsOpenOnFirstTap(screen)
                assertSame(player, InAppPlaybackSession.currentPlayer)
                assertEquals(34_000L, player.currentPosition)
                assertEquals(1, driver.opens)
                assertEquals(0, driver.stops)
                assertEquals(0, driver.releases)
            }
            assertEquals("Leaving fullscreen keeps the borrowed session alive", 0, driver.releases)
        } finally { player.release() }
    }

    @Test fun `restored timeline completes a long drag without being hidden or turned into a video tap`() {
        withMedia3Screen { screen, player ->
            screen.autoHide()
            screen.tapVideo()
            idle(32)
            assertTrue(screen.view.isControllerFullyVisible)
            val timeline = screen.view.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            val start = screen.point(timeline, .25f)
            val end = screen.point(timeline, .75f)
            val down = SystemClock.uptimeMillis()
            screen.touch(MotionEvent.ACTION_DOWN, start, down)
            idle(1_100)
            assertTrue("The strip must remain visible during an active scrub", screen.view.isControllerFullyVisible)
            screen.touch(MotionEvent.ACTION_MOVE, end, down)
            screen.touch(MotionEvent.ACTION_UP, end, down)
            val releasedAt = SystemClock.uptimeMillis()
            idle(32)
            assertEquals("The timeline owns the gesture and commits one seek", 1, player.seeks.size)
            assertTrue(player.currentPosition in 75_000L..105_000L)
            assertTrue("Finishing a scrub must not toggle the video overlay", screen.view.isControllerFullyVisible)
            assertTrue(player.isPlaying)
            assertEquals(0, player.pauses)
            idle(900)
            assertTrue(screen.timing("A finished scrub receives a fresh hide timeout", releasedAt), screen.view.isControllerFullyVisible)
            idle(100)
            assertFalse(screen.view.isControllerFullyVisible)
        }
    }

    @Test fun `ordinary button hold survives both hide timers and playback state callbacks`() {
        withMedia3Screen { screen, player ->
            val options = screen.view.findViewWithTag<View>("cameo_player_options")
            val point = screen.point(options)
            val down = SystemClock.uptimeMillis()
            screen.touch(MotionEvent.ACTION_DOWN, point, down)
            idle(600)
            // PlayerView also updates its own controller timer when playback changes.
            player.pause(); player.play()
            idle(1_100)
            assertTrue("Neither hide timer may remove a button while it is held", screen.view.isControllerFullyVisible)
            screen.touch(MotionEvent.ACTION_UP, point, down)
            idle(0)
            assertEquals("The configured timeout returns when the gesture ends", 1_000, screen.view.controllerShowTimeoutMs)
            assertTrue("The held button still receives its click", screen.activity.currentOptionSheet?.isShowing == true)
            screen.activity.currentOptionSheet!!.dismiss()
            screen.autoHide()
            assertTrue(player.isPlaying)
            assertEquals(0, player.stops)
        }
    }

    @Test fun `stopping and releasing the binding during a held touch restore its timeout`() {
        withMedia3Screen { screen, player ->
            val binding = PlayerActivity::class.java.getDeclaredField("controls").apply { isAccessible = true }
                .get(screen.activity) as PlayerControlVisibility.Binding
            val point = screen.videoPoint()
            screen.touch(MotionEvent.ACTION_DOWN, point, SystemClock.uptimeMillis())
            binding.stop()
            assertEquals(1_000, screen.view.controllerShowTimeoutMs)
            binding.start(); binding.show()
            screen.touch(MotionEvent.ACTION_DOWN, point, SystemClock.uptimeMillis())
            binding.release()
            assertEquals(1_000, screen.view.controllerShowTimeoutMs)
            assertSame(player, screen.view.player)
            assertTrue(player.isPlaying)
            assertEquals(0, player.stops)
        }
    }

    @Test fun `cancelled touches neither reveal hidden controls nor leave auto hide suspended`() {
        withMedia3Screen { screen, player ->
            screen.autoHide()
            val video = screen.videoPoint()
            var down = SystemClock.uptimeMillis()
            screen.touch(MotionEvent.ACTION_DOWN, video, down)
            screen.touch(MotionEvent.ACTION_CANCEL, video, down)
            idle(32)
            assertFalse("A cancelled video touch is not a click", screen.view.isControllerFullyVisible)
            screen.tapVideo()
            idle(32)
            assertTrue(screen.view.isControllerFullyVisible)
            val timeline = screen.point(screen.view.findViewById(androidx.media3.ui.R.id.exo_progress), .5f)
            down = SystemClock.uptimeMillis()
            screen.touch(MotionEvent.ACTION_DOWN, timeline, down)
            idle(1_100)
            assertTrue(screen.view.isControllerFullyVisible)
            screen.touch(MotionEvent.ACTION_CANCEL, timeline, down)
            idle(32)
            assertTrue("Cancellation leaves the controls available", screen.view.isControllerFullyVisible)
            assertTrue("A cancelled scrub must not seek", player.seeks.isEmpty())
            idle(1_001)
            assertFalse("Cancellation must resume the normal hide timer", screen.view.isControllerFullyVisible)
            screen.tapVideo()
            idle(32)
            assertTrue("A later tap must still restore controls", screen.view.isControllerFullyVisible)
            assertTrue(player.isPlaying)
            assertEquals(0, player.pauses)
        }
    }

    @Test fun `tapping visible video hides once and the next tap restores once`() {
        withMedia3Screen { screen, player ->
            assertTrue(screen.view.isControllerFullyVisible)
            screen.tapVideo()
            idle(32)
            assertFalse("Activity touch observation must not perform an extra toggle", screen.view.isControllerFullyVisible)
            screen.tapVideo()
            idle(32)
            assertTrue(screen.view.isControllerFullyVisible)
            assertTrue(player.isPlaying)
            assertEquals(0, player.pauses)
            assertEquals(0, player.stops)
        }
    }

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-mdpi")
    fun `TV remote reveal still consumes its center key without pausing and retains timeline routing`() {
        withMedia3Screen { screen, player ->
            screen.autoHide()
            screen.key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(screen.view.isControllerFullyVisible)
            val playPause = screen.view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
            assertTrue(playPause.hasFocus())
            assertTrue(playPause.isFocusableInTouchMode)
            assertEquals(0, player.pauses)
            screen.key(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(screen.focusTrace, screen.view.findViewById<View>(androidx.media3.ui.R.id.exo_progress).hasFocus())
            screen.key(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(playPause.hasFocus())
            assertTrue(player.isPlaying)
        }
    }

    private fun assertRepeatedRestoration(screen: Screen, player: Player) {
        screen.autoHide()
        repeat(2) {
            screen.tapVideo()
            val revealedAt = SystemClock.uptimeMillis()
            idle(32)
            assertTrue("A restored controller must survive the previously expired deadline", screen.view.isControllerFullyVisible)
            assertSame(player, screen.view.player)
            assertSame(player, InAppPlaybackSession.currentPlayer)
            assertTrue("Restoring chrome must not pause the movie", player.isPlaying)
            assertEquals(34_000L, player.currentPosition)
            idle(900)
            assertTrue(screen.timing("The user receives a full interval to operate controls", revealedAt), screen.view.isControllerFullyVisible)
            idle(100)
            assertFalse("Restored controls should still hide normally when unused", screen.view.isControllerFullyVisible)
        }
        screen.tapVideo()
        idle(32)
        assertTrue(screen.view.isControllerFullyVisible)
    }

    private fun assertOptionsOpenOnFirstTap(screen: Screen) {
        val options = screen.view.findViewWithTag<View>("cameo_player_options")
        assertFalse("Touch mode must not consume the first tap just to focus", options.isFocusableInTouchMode)
        screen.tap(options)
        assertTrue("The restored options button must open its sheet on the first tap", screen.activity.currentOptionSheet?.isShowing == true)
        screen.activity.currentOptionSheet!!.dismiss()
        idle(0)
    }

    private fun withMedia3Screen(block: (Screen, RecordingPlayer) -> Unit) {
        val player = RecordingPlayer()
        try {
            withScreen(player, PlaybackEngine.MEDIA3) { block(it, player) }
            assertEquals("Leaving fullscreen keeps the borrowed session alive", 0, player.releases)
        } finally { player.release() }
    }

    private fun withScreen(player: Player, engine: PlaybackEngine, block: (Screen) -> Unit) {
        val playerField = InAppPlaybackSession::class.java.getDeclaredField("player").apply { isAccessible = true }
        val engineField = InAppPlaybackSession::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val previousPlayer = playerField.get(InAppPlaybackSession)
        val previousEngine = engineField.get(InAppPlaybackSession)
        playerField.set(InAppPlaybackSession, player)
        engineField.set(InAppPlaybackSession, engine)
        val intent = Intent(RuntimeEnvironment.getApplication(), PlayerActivity::class.java).putExtra("reuse_session", true)
        val owner = Robolectric.buildActivity(PlayerActivity::class.java, intent)
        try {
            // Window focus reads the initial input mode on SDK 28. Direct Activity
            // key dispatch does not perform ViewRoot's normal exit from touch mode.
            val touchMode = !AdaptiveLayout.isTelevision(RuntimeEnvironment.getApplication())
            ShadowInstrumentation.getInstrumentation().setInTouchMode(touchMode)
            owner.setup().visible().windowFocusChanged(true)
            val activity = owner.get()
            val view = activity.findViewById<PlayerView>(R.id.pov_player)
            assertEquals("The attached window must use the fixture's input mode", touchMode, view.isInTouchMode)
            // Surface readiness is covered separately. Bind the offline player directly so
            // these tests exercise the real Activity/controller dispatch, not a decoder.
            view.player = player
            view.controllerShowTimeoutMs = 1_000
            val decor = activity.window.decorView
            val metrics = activity.resources.displayMetrics
            decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            idle(0)
            view.hideController()
            view.showController()
            block(Screen(activity, view))
        } finally {
            owner.pause().stop().destroy()
            playerField.set(InAppPlaybackSession, previousPlayer)
            engineField.set(InAppPlaybackSession, previousEngine)
        }
    }

    private class Screen(val activity: PlayerActivity, val view: PlayerView) {
        var focusTrace = ""
            private set
        fun point(target: View, fractionX: Float = .5f): Pair<Float, Float> {
            val location = IntArray(2)
            target.getLocationInWindow(location)
            return location[0] + target.width * fractionX to location[1] + target.height / 2f
        }
        fun videoPoint(): Pair<Float, Float> {
            val location = IntArray(2)
            view.getLocationInWindow(location)
            return location[0] + view.width / 2f to location[1] + view.height / 2f
        }
        fun tap(target: View) = tapAt(point(target))
        fun tapVideo() = tapAt(videoPoint())
        private fun tapAt(point: Pair<Float, Float>) {
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, point, down)
            touch(MotionEvent.ACTION_UP, point, down)
            idle(0)
        }
        fun touch(action: Int, point: Pair<Float, Float>, down: Long) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("The fullscreen view must handle the touch", activity.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
        fun autoHide() { idle(1_100); assertFalse("Playing controls should auto hide", view.isControllerFullyVisible) }
        fun key(code: Int) {
            // Model ViewRoot's input-stage switch to remote navigation; direct
            // Activity dispatch alone never performs that system transition.
            val root = ReflectionHelpers.callInstanceMethod<Any>(activity.window.decorView, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Boolean>(root, "ensureTouchMode",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
            // As in SourceScreenNavigationTest, Robolectric's Activity focus shadow
            // needs the real decor selection; all navigation still runs in Activity.
            shadowOf(activity).setCurrentFocus(activity.window.decorView.findFocus())
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            shadowOf(activity).setCurrentFocus(activity.window.decorView.findFocus())
            val downFocus = activity.currentFocus?.id
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            val timeline = view.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            focusTrace = "code=$code downFocus=$downFocus upFocus=${activity.currentFocus?.id} " +
                "timeline=${timeline.id} enabled=${timeline.isEnabled} shown=${timeline.isShown} " +
                "focusable=${timeline.isFocusable} touchMode=${view.isInTouchMode} size=${timeline.width}x${timeline.height}"
        }
        fun timing(message: String, since: Long): String {
            val binding = PlayerActivity::class.java.getDeclaredField("controls").apply { isAccessible = true }.get(activity)
            val last = binding.javaClass.getDeclaredField("lastInteractionAt").apply { isAccessible = true }.getLong(binding)
            return "$message: uptime elapsed=${SystemClock.uptimeMillis() - since}, " +
                "interaction age=${SystemClock.elapsedRealtime() - last}, timeout=${view.controllerShowTimeoutMs}"
        }
    }

    private class RecordingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var pauses = 0; var plays = 0; var prepares = 0; var stops = 0; var releases = 0
        val seeks = mutableListOf<Long>()
        private var playing = true
        private var position = 34_000L
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_TRACKS, Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD).build())
            .setPlaylist(listOf(MediaItemData.Builder("touch-fixture")
                .setMediaItem(MediaItem.fromUri("https://fixture.invalid/touch-video.mkv"))
                .setDurationUs(120_000_000).setIsSeekable(true).build()))
            .setContentPositionMs(PositionSupplier { position })
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            if (playWhenReady) plays++ else pauses++
            return Futures.immediateVoidFuture()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            position = positionMs; seeks += positionMs
            return Futures.immediateVoidFuture()
        }
        override fun handlePrepare(): ListenableFuture<*> { prepares++; return Futures.immediateVoidFuture() }
        override fun handleStop(): ListenableFuture<*> { stops++; return Futures.immediateVoidFuture() }
        override fun handleRelease(): ListenableFuture<*> { releases++; return Futures.immediateVoidFuture() }
    }

    private class Driver : VlcPlaybackDriver {
        override var listener: ((VlcEvent) -> Unit)? = null
        override var position = 0L
        override val duration = 120_000L
        override var seekable = false
        override var buffering = true
        override var actuallyPlaying = false
        override var renderedFrames = 0L
        override val videoSize = VideoSize(1920, 1080)
        var opens = 0; var plays = 0; var pauses = 0; var stops = 0; var releases = 0
        private var subtitles: SurfaceView? = null
        override fun tracks() = emptyList<VlcTrack>()
        override fun open(item: MediaItem, subtitleLanguage: String) { opens++; position = 0 }
        override fun play() { plays++ }
        override fun pause() { pauses++ }
        override fun stop() { stops++ }
        override fun seekTo(position: Long) { this.position = position; renderedFrames++ }
        override fun selectTrack(type: Int, id: Int) = true
        override fun selectAutomatic(type: Int, language: String) {}
        override fun attach(view: SurfaceView) {
            detach()
            val parent = view.parent as? ViewGroup ?: return
            // Match NativeVlcDriver's non-interactive subtitle overlay without loading LibVLC.
            subtitles = SurfaceView(view.context).apply { tag = "pov_vlc_subtitle_surface"; isFocusable = false }
            parent.addView(subtitles, parent.indexOfChild(view) + 1, FrameLayout.LayoutParams(-1, -1))
        }
        override fun detach() { subtitles?.let { (it.parent as? ViewGroup)?.removeView(it) }; subtitles = null }
        override fun addSubtitle(uri: Uri) = true
        override fun release() { releases++; detach() }
        fun ready() {
            buffering = false; seekable = true; actuallyPlaying = true
            listener?.invoke(VlcEvent.READY)
            renderedFrames++; listener?.invoke(VlcEvent.CHANGED); listener?.invoke(VlcEvent.CHANGED)
        }
        fun acknowledgePause() { actuallyPlaying = false; listener?.invoke(VlcEvent.PAUSED) }
    }

    companion object {
        private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }
}
