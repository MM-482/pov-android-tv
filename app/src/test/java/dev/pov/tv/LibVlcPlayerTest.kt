package dev.pov.tv

import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class LibVlcPlayerTest {
    private class Driver : VlcPlaybackDriver {
        override var listener: ((VlcEvent) -> Unit)? = null
        override var position = 0L
        override var duration = 120_000L
        override var seekable = false
        override var buffering = true
        override var renderedFrames = 0L
        override var actuallyPlaying = false
        override val videoSize = VideoSize(1920, 1080)
        var trackList = listOf(
            VlcTrack(7, C.TRACK_TYPE_AUDIO, Format.Builder().setSampleMimeType("audio/ac3").setLanguage("en").build(), true),
            VlcTrack(42, C.TRACK_TYPE_TEXT, Format.Builder().setSampleMimeType("application/x-subrip").setLanguage("es").build(), false))
        var plays = 0
        var pauses = 0
        var stops = 0
        var released = false
        var attached: SurfaceView? = null
        val selections = mutableListOf<Pair<Int, Int>>()
        val automaticSelections = mutableListOf<Pair<Int, String>>()
        val seeks = mutableListOf<Long>()
        val volumes = mutableListOf<Float>()
        override fun tracks() = trackList
        override fun open(item: MediaItem, subtitleLanguage: String) { position = 0; renderedFrames = 0; actuallyPlaying = false }
        override fun play() { plays++ }
        override fun pause() { pauses++ }
        override fun stop() { stops++ }
        override fun seekTo(position: Long) { this.position = position; seeks += position; renderedFrames++ }
        override fun setVolume(volume: Float) { volumes += volume }
        override fun selectTrack(type: Int, id: Int): Boolean { selections += type to id; return true }
        override fun selectAutomatic(type: Int, language: String) { automaticSelections += type to language }
        override fun attach(view: SurfaceView) { attached = view }
        override fun detach() { attached = null }
        override fun addSubtitle(uri: Uri) = true
        override fun release() { released = true }
        fun started() { buffering = false; seekable = true; actuallyPlaying = true; listener?.invoke(VlcEvent.READY) }
        fun outputFrame() { renderedFrames++; listener?.invoke(VlcEvent.CHANGED) }
        fun acknowledgePause() { actuallyPlaying = false; listener?.invoke(VlcEvent.PAUSED) }
        fun ready() {
            val previousPauses = pauses
            started(); outputFrame(); listener?.invoke(VlcEvent.CHANGED)
            if (pauses > previousPauses) acknowledgePause()
        }
    }

    @Test fun `prepare while paused seeks resume clock once and does not turn into autoplay`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/video.mkv"), 15_000)
        player.prepare()
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        driver.ready()
        assertEquals(listOf(15_000L), driver.seeks)
        assertEquals(15_000L, player.currentPosition)
        assertFalse(player.playWhenReady)
        assertTrue(driver.pauses > 0)
        player.play(); assertTrue(player.playWhenReady)
        player.pause(); assertFalse(player.playWhenReady)
        player.release()
    }

    @Test fun `fresh zero-position open does not seek during initial native playing notification`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/video.mpd"), 0)
        player.prepare()
        driver.ready()
        assertTrue("Fresh playback must not reset the demux with a redundant zero seek", driver.seeks.isEmpty())
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.playWhenReady)
        assertEquals(1, driver.pauses)
        player.release()
    }

    @Test fun `paused prepare silently waits for first output then native pause acknowledgement`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/progressive.mkv")); player.prepare()
        driver.started()
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertEquals(0, driver.pauses)
        assertEquals(0f, driver.volumes.last())
        driver.outputFrame()
        assertEquals(1, driver.pauses)
        assertTrue(player.nativePausePending)
        assertEquals("Paused READY cannot precede native acknowledgement", Player.STATE_BUFFERING, player.playbackState)
        driver.acknowledgePause()
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.playWhenReady)
        assertFalse(player.nativeIsPlaying)
        assertTrue(player.startupOutputReady)
        assertEquals(1f, driver.volumes.last())
        player.release()
    }

    @Test fun `explicit play during pending native startup does not enqueue duplicate play commands`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/progressive.mkv")); player.prepare()
        assertTrue(player.nativePlayPending)
        player.play()
        assertEquals(1, driver.plays)
        driver.started(); driver.outputFrame()
        assertFalse(player.nativePlayPending)
        assertTrue(player.isPlaying)
        assertEquals(0, driver.pauses)
        player.release()
    }

    @Test fun `late buffering cannot demote acknowledged paused ready or request repeated pauses`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/progressive.mkv")); player.prepare(); driver.ready()
        assertEquals(Player.STATE_READY, player.playbackState)
        val pauses = driver.pauses
        driver.buffering = true; driver.listener?.invoke(VlcEvent.BUFFERING)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.isPlaying)
        assertEquals(pauses, driver.pauses)
        player.release()
    }

    @Test fun `initial Vout alone cannot expose first frame but prepared target handoff can release its shutter`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        var notifications = 0
        player.addListener(object : Player.Listener { override fun onRenderedFirstFrame() { notifications++ } })
        player.setMediaItem(MediaItem.fromUri("https://media.example/progressive.mkv")); player.prepare()
        driver.listener?.invoke(VlcEvent.FIRST_FRAME)
        assertEquals(0, notifications)
        driver.ready()
        assertTrue(notifications > 0)
        val first = notifications
        val plays = driver.plays
        player.setVideoSurfaceView(SurfaceView(RuntimeEnvironment.getApplication()))
        driver.listener?.invoke(VlcEvent.FIRST_FRAME)
        assertTrue("A new prepared target must not remain behind PlayerView's shutter", notifications > first)
        assertFalse(player.playWhenReady)
        assertEquals(plays, driver.plays)
        player.release()
    }

    @Test fun `play crossed with queued pause resumes once after its acknowledgement without self play loop`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/progressive.mkv")); player.prepare()
        driver.started(); driver.outputFrame()
        assertEquals(1, driver.pauses)
        val plays = driver.plays
        player.play()
        assertEquals("Wait for queued pause before issuing crossed Play", plays, driver.plays)
        driver.acknowledgePause()
        assertEquals(plays + 1, driver.plays)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        driver.started()
        assertTrue(player.isPlaying)
        driver.acknowledgePause() // Unsolicited native/system pause must not start a retry loop.
        assertFalse(player.playWhenReady)
        assertEquals(plays + 1, driver.plays)
        player.release()
    }

    @Test fun `newer pause cancels a play waiting for the previous pause acknowledgement`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/progressive.mkv")); player.prepare()
        driver.started(); driver.outputFrame()
        val plays = driver.plays
        player.play(); player.pause(); driver.acknowledgePause()
        assertEquals(plays, driver.plays)
        assertFalse(player.playWhenReady)
        assertEquals(Player.STATE_READY, player.playbackState)
        player.release()
    }

    @Test fun `pause requested before queued play starts is honored on its eventual native playing event`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/progressive.mkv")); player.prepare(); driver.ready()
        val pauses = driver.pauses
        player.play(); player.pause()
        assertEquals(pauses, driver.pauses) // Native is still paused; no redundant Pause command.
        driver.started()
        assertEquals(pauses + 1, driver.pauses)
        driver.acknowledgePause()
        assertFalse(player.playWhenReady)
        assertEquals(Player.STATE_READY, player.playbackState)
        player.release()
    }

    @Test fun `startup observation is bounded without retrying play and later native output can still finish`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/progressive.mkv")); player.prepare(); driver.started()
        val plays = driver.plays
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(plays, driver.plays)
        assertEquals(0, driver.pauses)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        driver.outputFrame(); driver.acknowledgePause()
        assertEquals(Player.STATE_READY, player.playbackState)
        player.release()
    }

    @Test fun `bounded startup observation detects displayed picture without a new time event and stop cancels it`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/progressive.mkv")); player.prepare(); driver.started()
        driver.renderedFrames = 1 // The native stats update can precede its next TimeChanged event.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(1, driver.pauses)
        driver.acknowledgePause()
        assertEquals(Player.STATE_READY, player.playbackState)
        player.stop()
        val plays = driver.plays
        driver.started(); driver.outputFrame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertEquals(plays, driver.plays)
        player.release()
    }

    @Test fun `switching views keeps player and clock while stale old view clear cannot detach new output`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/video.mkv"), 9_000)
        player.prepare(); driver.ready()
        val app = RuntimeEnvironment.getApplication()
        val fullscreen = SurfaceView(app); val mini = SurfaceView(app)
        player.setVideoSurfaceView(fullscreen)
        assertSame(fullscreen, driver.attached)
        player.setVideoSurfaceView(mini)
        player.clearVideoSurfaceView(fullscreen)
        assertSame(mini, driver.attached)
        assertEquals(9_000L, player.currentPosition)
        player.clearVideoSurfaceView(mini)
        assertNull(driver.attached)
        player.release()
    }

    @Test fun `text track override targets native id and subtitle off leaves audio active`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "es")
        player.setMediaItem(MediaItem.fromUri("https://media.example/video.mkv"))
        player.prepare(); driver.ready()
        val choices = PlayerTrackOptions.choices(player.currentTracks, player.trackSelectionParameters, C.TRACK_TYPE_TEXT)
        val spanish = choices.single { it.kind == TrackChoiceKind.TRACK }
        player.trackSelectionParameters = PlayerTrackOptions.apply(player.trackSelectionParameters, spanish)
        assertTrue(driver.selections.contains(C.TRACK_TYPE_TEXT to 42))
        val off = PlayerTrackOptions.choices(player.currentTracks, player.trackSelectionParameters, C.TRACK_TYPE_TEXT)
            .single { it.kind == TrackChoiceKind.OFF }
        player.trackSelectionParameters = PlayerTrackOptions.apply(player.trackSelectionParameters, off)
        assertTrue(driver.selections.contains(C.TRACK_TYPE_TEXT to -1))
        assertFalse(driver.selections.contains(C.TRACK_TYPE_AUDIO to -1))
        assertTrue(player.addSubtitle(Uri.parse("file:///private/new.srt")))
        assertFalse(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
        player.release()
    }

    @Test fun `native error and end have distinct states and release ignores late callbacks`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/video.mkv")); player.prepare(); player.play(); driver.ready()
        driver.listener?.invoke(VlcEvent.ENDED)
        assertEquals(Player.STATE_ENDED, player.playbackState)
        driver.listener?.invoke(VlcEvent.ERROR)
        assertNotNull(player.playerError)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        driver.listener?.invoke(VlcEvent.PAUSED)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertNotNull(player.playerError)
        val late = driver.listener
        player.release()
        late?.invoke(VlcEvent.READY)
        assertTrue(driver.released); assertNull(driver.listener)
    }

    @Test fun `explicit Automatic remains available after subtitle Off and retains preferred language`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "es")
        player.setMediaItem(MediaItem.fromUri("https://media.example/video.mkv")); player.prepare(); driver.ready()
        val off = PlayerTrackOptions.choices(player.currentTracks, player.trackSelectionParameters, C.TRACK_TYPE_TEXT)
            .single { it.kind == TrackChoiceKind.OFF }
        player.trackSelectionParameters = PlayerTrackOptions.apply(player.trackSelectionParameters, off)
        assertTrue(driver.selections.contains(C.TRACK_TYPE_TEXT to -1))
        val automatic = PlayerTrackOptions.choices(player.currentTracks, player.trackSelectionParameters, C.TRACK_TYPE_TEXT)
            .single { it.kind == TrackChoiceKind.AUTOMATIC }
        player.trackSelectionParameters = PlayerTrackOptions.apply(player.trackSelectionParameters, automatic)
        assertFalse(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
        assertTrue(driver.automaticSelections.contains(C.TRACK_TYPE_TEXT to "es"))
        player.release()
    }

    @Test fun `audio focus loss pauses VLC and updates shared control state`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/video.mkv")); player.prepare(); player.play(); driver.ready()
        driver.listener?.invoke(VlcEvent.FOCUS_LOSS)
        assertFalse(player.playWhenReady)
        assertTrue(driver.pauses > 0)
        player.release()
    }
}
