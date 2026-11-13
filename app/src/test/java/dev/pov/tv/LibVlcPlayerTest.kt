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
        override var remoteOutputName: String? = null
        override val rendererSupported = true
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
        var lastRate = 1f
        var delayMicros = 0L
        var acceptDelay = true
        override val subtitleDelayMicros: Long get() = delayMicros
        var chapterList = listOf(PlaybackChapter(0, "Opening", 0, 30_000, true), PlaybackChapter(1, "Arrival", 30_000, 90_000, false))
        var chapterSelected = -1
        override fun tracks() = trackList
        override fun open(item: MediaItem, subtitleLanguage: String) { position = 0; renderedFrames = 0; actuallyPlaying = false; delayMicros = 0; lastRate = 1f }
        override fun play() { plays++ }
        override fun pause() { pauses++ }
        override fun stop() { stops++ }
        override fun seekTo(position: Long) { this.position = position; seeks += position; renderedFrames++ }
        override fun setVolume(volume: Float) { volumes += volume }
        override fun setPlaybackSpeed(speed: Float): Boolean { lastRate = speed; return true }
        override fun setSubtitleDelayMicros(delay: Long): Boolean { if (!acceptDelay) return false; delayMicros = delay; return true }
        override fun chapters() = chapterList
        override fun selectChapter(index: Int): Boolean {
            if (chapterList.none { it.index == index }) return false
            chapterSelected = index; return true
        }
        override fun playbackDetails() = NativePlaybackDetails(20, renderedFrames, 1, 2500)
        override fun selectTrack(type: Int, id: Int): Boolean { selections += type to id; return true }
        override fun selectAutomatic(type: Int, language: String) { automaticSelections += type to language }
        override fun attach(view: SurfaceView) { attached = view }
        override fun detach() { attached = null }
        override fun addSubtitle(uri: Uri) = true
        override fun selectRenderer(id: String): Boolean {
            if (id != "fixture-display") return false
            remoteOutputName = "Fixture display"
            return true
        }
        override fun disconnectRenderer(): Boolean { remoteOutputName = null; return true }
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

    @Test fun `remote route accepts native clock without pretending local pictures were rendered`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/fixture.mp4"))
        player.prepare(); player.play(); driver.started()
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertTrue(player.selectRenderer("fixture-display"))
        assertEquals("Fixture display", player.remoteOutputName)
        assertEquals(0L, player.renderedOutputFrames)
        assertEquals(Player.STATE_READY, player.playbackState)
        player.seekTo(22_000)
        assertEquals(22_000L, driver.position)
        assertEquals(1, driver.plays)
        player.release()
    }

    @Test fun `disconnect preserves current native player clock and pause while requiring fresh local output`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/fixture.mp4"))
        player.prepare(); player.play(); driver.ready()
        driver.position = 31_000
        assertTrue(player.selectRenderer("fixture-display"))
        player.pause(); driver.acknowledgePause()
        val plays = driver.plays; val pauses = driver.pauses
        assertTrue(player.disconnectRenderer())
        assertNull(player.remoteOutputName)
        assertFalse(player.playWhenReady)
        assertEquals(31_000L, driver.position)
        assertEquals(plays, driver.plays); assertEquals(pauses, driver.pauses)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        driver.outputFrame()
        assertEquals(Player.STATE_READY, player.playbackState)
        player.release()
    }

    @Test fun `desktop playback speed reaches VLC and survives a caption reload while paused`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/fixture.mkv"), 15_000)
        player.prepare(); driver.ready()
        player.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, driver.lastRate)
        assertEquals(1.5f, player.playbackParameters.speed)
        player.adjustSubtitleDelay(500_000)
        driver.position = 23_000
        assertTrue(player.reloadCaptionAppearance())
        assertFalse(player.playWhenReady)
        driver.ready()
        assertEquals(23_000L, player.currentPosition)
        assertEquals(1.5f, driver.lastRate)
        assertEquals(500_000L, driver.subtitleDelayMicros)
        assertTrue(driver.selections.contains(C.TRACK_TYPE_AUDIO to 7))
        player.release()
    }

    @Test fun `chapters caption timing and safe diagnostics are exposed without private source details`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/fixture.mkv")); player.prepare(); driver.ready()
        assertEquals("Arrival", player.chapters()[1].name)
        assertTrue(player.selectChapter(1)); assertEquals(1, driver.chapterSelected)
        assertFalse(player.selectChapter(99))
        assertTrue(player.adjustSubtitleDelay(-500_000)); assertEquals(-500_000L, player.subtitleDelayMicros)
        assertTrue(player.resetSubtitleDelay()); assertEquals(0L, player.subtitleDelayMicros)
        assertEquals(2500L, player.playbackDetails()?.inputKbitPerSecond)
        player.release()
        assertTrue(player.chapters().isEmpty()); assertNull(player.playbackDetails()); assertFalse(player.selectChapter(1))
    }

    @Test fun `restored caption delay survives early refusal until native input becomes ready`() {
        val driver = Driver().apply { acceptDelay = false }; val player = LibVlcPlayer(driver, "en")
        player.setMediaItem(MediaItem.fromUri("https://media.example/fixture.mkv"))
        player.prepare()
        assertTrue(player.restoreSubtitleDelay(1_250_000L))
        assertEquals(0L, driver.subtitleDelayMicros)
        driver.ready()
        assertEquals(0L, driver.subtitleDelayMicros)
        driver.acceptDelay = true
        driver.listener?.invoke(VlcEvent.CHANGED)
        assertEquals(1_250_000L, driver.subtitleDelayMicros)
        player.setMediaItem(MediaItem.fromUri("https://media.example/next-episode.mkv"))
        player.prepare(); driver.ready()
        assertEquals(0L, driver.subtitleDelayMicros)
        player.release()
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
