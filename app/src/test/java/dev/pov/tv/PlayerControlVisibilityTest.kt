package dev.pov.tv

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerControlVisibilityTest {
    private val chromeIds = listOf(androidx.media3.ui.R.id.exo_progress, androidx.media3.ui.R.id.exo_play_pause,
        androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_ffwd, R.id.pov_audio,
        R.id.pov_subtitles, R.id.pov_boost, R.id.pov_browse, R.id.pov_more, R.id.pov_stop, R.id.pov_loading_spinner)

    @Test fun `native tap after expired idle deadline reveals controls for the full timeout`() = withPlayingView { view, binding ->
        binding.show()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_001))
        assertFalse(view.isControllerFullyVisible)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        var revealedAt = -1L
        val controller = view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
        val revealObserver = PlayerControlView.VisibilityListener { visibility ->
            if (visibility == View.VISIBLE) revealedAt = SystemClock.elapsedRealtime()
        }
        controller.addVisibilityListener(revealObserver)
        // Exercise PlayerView's native touch-to-click reveal, without calling binding.show
        // or the Activity observer that could conceal an expired visibility-listener timer.
        try {
            touch(view, MotionEvent.ACTION_DOWN, 480f, 100f)
            touch(view, MotionEvent.ACTION_UP, 480f, 100f)
            assertTrue(view.isControllerFullyVisible)
            assertTrue("The real native controller must report a new reveal", revealedAt >= 0L)
            idleUntil(revealedAt + 999L)
            assertTrue("A fresh native reveal must not reuse the old expired app deadline", view.isControllerFullyVisible)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
            assertFalse("Revealed controls must still hide after their full idle timeout", view.isControllerFullyVisible)
        } finally { controller.removeVisibilityListener(revealObserver) }
    }

    @Test fun `held touch suspends native and app hide timers and release renews the full timeout`() = withPlayingView { view, binding ->
        binding.show()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
        binding.touchInteraction(MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        binding.refreshPlaybackState()
        assertTrue("Neither hide timer may dismiss controls while a finger remains down", view.isControllerFullyVisible)
        binding.touchInteraction(MotionEvent.ACTION_MOVE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(view.isControllerFullyVisible)
        binding.touchInteraction(MotionEvent.ACTION_UP)
        assertEquals("The configured timeout must be restored after the gesture", 1_000, view.controllerShowTimeoutMs)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertTrue(view.isControllerFullyVisible)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
        assertFalse(view.isControllerFullyVisible)
    }

    @Test fun `observing a hidden gesture never forces controls visible and cancellation restores timeout`() = withPlayingView { view, binding ->
        binding.show()
        binding.touchInteraction(MotionEvent.ACTION_DOWN)
        binding.touchInteraction(MotionEvent.ACTION_CANCEL)
        assertEquals(1_000, view.controllerShowTimeoutMs)
        view.hideController()
        binding.touchInteraction(MotionEvent.ACTION_DOWN)
        binding.touchInteraction(MotionEvent.ACTION_MOVE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(view.isControllerFullyVisible)
        binding.touchInteraction(MotionEvent.ACTION_UP)
        assertFalse("Only PlayerView's actual tap handling should reveal hidden chrome", view.isControllerFullyVisible)
    }

    @Test fun `playback refresh alone cannot continually postpone or reveal controls`() = withPlayingView { view, binding ->
        binding.show()
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            binding.refreshPlaybackState()
        }
        assertFalse("Playback polling is not user interaction", view.isControllerFullyVisible)
        repeat(6) { binding.refreshPlaybackState() }
        assertFalse(view.isControllerFullyVisible)
    }

    @Test fun `real timeline scrubbing holds chrome until scrub release even without Activity forwarding`() {
        var releasedAt = -1L
        withPlayingView(beforeBinding = { view ->
            // Observe the actual native release callback before the binding handles
            // it. Motion dispatch may itself consume simulated traversal time.
            view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress).addListener(object : TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: TimeBar, position: Long) = Unit
                override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit
                override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) { releasedAt = SystemClock.elapsedRealtime() }
            })
        }) { view, _ ->
            view.showController()
            val timeBar = view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
            assertTrue("The real timeline must have completed layout before touch dispatch", timeBar.width > 0 && timeBar.height > 0)
            assertTrue("The fixture player must expose actual timeline seeking", timeBar.isEnabled)
            timeBar.setDuration(120_000)
            touch(timeBar, MotionEvent.ACTION_DOWN, timeBar.width / 2f, timeBar.height / 2f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertTrue("The app timer must respect the actual TimeBar scrub state", view.isControllerFullyVisible)
            touch(timeBar, MotionEvent.ACTION_CANCEL, timeBar.width / 2f, timeBar.height / 2f)
            assertTrue("The real timeline must report scrub release", releasedAt >= 0L)
            idleUntil(releasedAt + 999L)
            assertTrue(view.isControllerFullyVisible)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
            assertFalse(view.isControllerFullyVisible)
        }
    }

    @Test fun `activity consumed directional interaction resets native and app playing hide deadlines`() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val view = LayoutInflater.from(activity).inflate(R.layout.pov_player, null, false) as PlayerView
        activity.setContentView(view)
        val playing = object : SimpleBasePlayer(Looper.getMainLooper()) {
            override fun getState(): State = State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_TRACKS).build())
                .setPlaylist(listOf(MediaItemData.Builder("playing-fixture")
                    .setMediaItem(MediaItem.fromUri("https://media.example/public-fixture.mkv"))
                    .setDurationUs(120_000_000).build()))
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }
        view.player = playing
        val binding = PlayerControlVisibility.bind(view)
        try {
            view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 960, 540)
            view.controllerShowTimeoutMs = 1_000
            binding.show()
            assertTrue(playing.isPlaying)
            assertTrue(view.isControllerFullyVisible)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
            // This is PlayerActivity's row-to-timeline path: Activity consumes
            // the key, so PlayerView.dispatchKeyEvent never refreshes its timer.
            binding.userInteraction()
            view.findViewById<View>(androidx.media3.ui.R.id.exo_progress).requestFocus()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
            assertTrue("The original Media3 deadline must not hide controls after fresh input", view.isControllerFullyVisible)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
            assertFalse("Fresh input must postpone, rather than disable, automatic hiding", view.isControllerFullyVisible)
            chromeIds.forEach { assertFalse("Chrome leaked after the refreshed timeout: $it", rendered(view.findViewById(it))) }
        } finally { binding.release(); view.player = null; owner.pause().stop().destroy() }
    }

    @Test fun `caption viewport clears visible controller follows its height and restores original padding`() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val view = LayoutInflater.from(activity).inflate(R.layout.pov_player, null, false) as PlayerView
        activity.setContentView(view)
        val subtitles = requireNotNull(view.subtitleView)
        subtitles.setPadding(3, 4, 5, 6)
        val binding = PlayerControlVisibility.bind(view)
        val strip = view.findViewById<View>(R.id.pov_bottom_scrim)
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 960, 540)
        }
        try {
            layout(); binding.start(); view.showController(); layout()
            assertEquals(6 + strip.height + 8, subtitles.paddingBottom)
            assertTrue("Caption drawable area must end above the playback strip", subtitles.height - subtitles.paddingBottom <= strip.top - 8)
            assertEquals(3, subtitles.paddingLeft); assertEquals(4, subtitles.paddingTop); assertEquals(5, subtitles.paddingRight)
            view.hideController()
            assertEquals(6, subtitles.paddingBottom)
            view.showController(); layout()
            assertEquals("Showing again must not accumulate padding", 6 + strip.height + 8, subtitles.paddingBottom)
            strip.layoutParams = strip.layoutParams.apply { height = 160 }
            layout()
            assertEquals(160, strip.height)
            assertEquals(174, subtitles.paddingBottom)
            assertTrue(subtitles.height - subtitles.paddingBottom <= strip.top - 8)
            binding.release()
            assertEquals(6, subtitles.paddingBottom)
            strip.layoutParams = strip.layoutParams.apply { height = 180 }
            layout()
            assertEquals("Released binding must not change captions on later layouts", 6, subtitles.paddingBottom)
        } finally { binding.release(); owner.pause().stop().destroy() }
    }

    @Test fun `focused audio action does not pin timeline loading indicator or other controls after timeout`() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val view = LayoutInflater.from(activity).inflate(R.layout.pov_player, null, false) as PlayerView
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 960, 540)
        val binding = PlayerControlVisibility.bind(view)
        try {
            val controller = view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
            controller.hide()
            controller.showTimeoutMs = 1_000
            controller.show()
            view.findViewById<View>(R.id.pov_buffering_status).visibility = View.VISIBLE
            view.findViewById<View>(R.id.pov_audio).requestFocus()
            // Making the previously GONE loading row visible requires a real layout pass before checking its rendered bounds.
            view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 960, 540)
            assertTrue(view.findViewById<View>(R.id.pov_audio).hasFocus())
            assertTrue(rendered(view.findViewById(R.id.pov_loading_spinner)))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_001))
            assertEquals(View.GONE, controller.visibility)
            chromeIds.forEach { assertFalse("Chrome leaked outside controller: $it", rendered(view.findViewById(it))) }
            assertFalse(rendered(view.findViewById(androidx.media3.ui.R.id.exo_buffering)))
            controller.show()
            assertTrue(controller.isFullyVisible)
            assertTrue(rendered(view.findViewById(androidx.media3.ui.R.id.exo_progress)))
            assertTrue(rendered(view.findViewById(R.id.pov_audio)))
        } finally { binding.release(); owner.pause().stop().destroy() }
    }

    @Test fun `all feature actions and loading indicator are physically within one bottom controller`() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val view = LayoutInflater.from(activity).inflate(R.layout.pov_player, null, false) as PlayerView
        activity.setContentView(view)
        val binding = PlayerControlVisibility.bind(view)
        try {
            val controller = view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
            controller.show()
            view.findViewById<View>(R.id.pov_buffering_status).visibility = View.VISIBLE
            view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 960, 540)
            val scrim = view.findViewById<View>(R.id.pov_bottom_scrim)
            val scrimBounds = Rect()
            assertTrue(scrim.getGlobalVisibleRect(scrimBounds))
            assertTrue("Controls should occupy only the lower part of the video", scrimBounds.top > 270)
            assertEquals("Playback strip should remain compact", 124, scrimBounds.height())
            assertEquals(16f, view.findViewById<android.widget.TextView>(R.id.pov_playback_title).textSize, 0.01f)
            for (id in chromeIds) {
                val child = view.findViewById<View>(id)
                val bounds = Rect()
                assertTrue("Missing visible bounds: $id", child.getGlobalVisibleRect(bounds))
                assertTrue("Control outside bottom scrim: $id", scrimBounds.contains(bounds))
                var parent = child.parent
                while (parent is View && parent !== controller) parent = parent.parent
                assertSame(controller, parent)
            }
        } finally { binding.release(); owner.pause().stop().destroy() }
    }

    @Test fun `paused indefinite controls still disappear as one root when explicitly hidden`() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val view = LayoutInflater.from(activity).inflate(R.layout.pov_player, null, false) as PlayerView
        activity.setContentView(view)
        val binding = PlayerControlVisibility.bind(view)
        try {
            val controller = view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
            controller.hide(); controller.showTimeoutMs = 0; controller.show()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            assertTrue(controller.isFullyVisible)
            controller.hide()
            assertEquals(View.GONE, controller.visibility)
            chromeIds.forEach { assertFalse(view.findViewById<View>(it).isShown) }
        } finally { binding.release(); owner.pause().stop().destroy() }
    }

    private fun withPlayingView(beforeBinding: (PlayerView) -> Unit = {}, test: (PlayerView, PlayerControlVisibility.Binding) -> Unit) {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val view = LayoutInflater.from(activity).inflate(R.layout.pov_player, null, false) as PlayerView
        activity.setContentView(view)
        val playing = object : SimpleBasePlayer(Looper.getMainLooper()) {
            override fun getState(): State = State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_TRACKS, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build())
                .setPlaylist(listOf(MediaItemData.Builder("touch-playing-fixture")
                    .setMediaItem(MediaItem.fromUri("https://media.example/public-fixture.mkv"))
                    .setDurationUs(120_000_000).setIsSeekable(true).build()))
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        view.player = playing
        beforeBinding(view)
        val binding = PlayerControlVisibility.bind(view)
        try {
            view.controllerShowTimeoutMs = 1_000
            binding.show()
            view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 960, 540)
            // A real window traversal lays out newly shown controller descendants.
            // Also give PlayerView focus so the first tap is not consumed only
            // taking focus, and finish initial window work before timing begins.
            view.requestFocus()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(playing.isPlaying)
            test(view, binding)
        } finally { binding.release(); view.player = null; playing.release(); owner.pause().stop().destroy() }
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try {
            assertTrue("The real view must handle the synthetic touch", view.dispatchTouchEvent(event))
            // Android View posts performClick for ACTION_UP; a paused test looper
            // must execute that actual click before checking the visibility result.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                shadowOf(Looper.getMainLooper()).idle()
        }
        finally { event.recycle() }
    }

    private fun idleUntil(deadline: Long) {
        // Native touch/cancel dispatch and Robolectric's traversal work can consume
        // simulated time (observed 150–360 ms). Check the complete 1,000 ms interval
        // from the actual reveal/release, rather than starting a second interval
        // after that work has already consumed part of the native timer.
        val remaining = deadline - SystemClock.elapsedRealtime()
        assertTrue("Gesture processing must finish before the tested idle deadline", remaining >= 0L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(remaining))
        assertEquals("The visibility assertion must run at the exact original deadline", deadline, SystemClock.elapsedRealtime())
    }

    private fun rendered(view: View): Boolean {
        val bounds = Rect()
        return view.isShown && view.alpha > 0 && view.getGlobalVisibleRect(bounds) && !bounds.isEmpty
    }
}

