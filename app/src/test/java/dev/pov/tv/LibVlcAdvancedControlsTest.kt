package dev.pov.tv

import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class LibVlcAdvancedControlsTest {
    private class Driver : VlcPlaybackDriver {
        override var listener: ((VlcEvent) -> Unit)? = null
        override var position = 0L
        override val duration = 120_000L
        override var seekable = false
        override var buffering = true
        override var renderedFrames = 0L
        override var actuallyPlaying = false
        override val videoSize = VideoSize(1920, 1080)
        var opens = 0
        var pauses = 0
        var speed = 1f
        var appearance = CaptionAppearance()
        private var delay = 0L
        override val subtitleDelayMillis: Long get() = delay
        override var chapterIndex = 0
        val selections = mutableListOf<Pair<Int, Int>>()
        override fun tracks() = listOf(VlcTrack(7, C.TRACK_TYPE_AUDIO,
            Format.Builder().setSampleMimeType("audio/aac").setLanguage("en").build(), true))
        override fun open(item: MediaItem, subtitleLanguage: String) {
            opens++; position = 0; renderedFrames = 0; actuallyPlaying = false; buffering = true; seekable = false
        }
        override fun play() {}
        override fun pause() { pauses++ }
        override fun stop() { actuallyPlaying = false }
        override fun seekTo(position: Long) { this.position = position; renderedFrames++ }
        override fun selectTrack(type: Int, id: Int): Boolean { selections += type to id; return true }
        override fun selectAutomatic(type: Int, language: String) {}
        override fun attach(view: SurfaceView) {}
        override fun detach() {}
        override fun addSubtitle(uri: Uri) = true
        override fun release() {}
        override fun setRate(speed: Float) { this.speed = speed }
        override fun setSubtitleDelayMillis(value: Long): Boolean { delay = value; return true }
        override fun configureCaptions(appearance: CaptionAppearance) { this.appearance = appearance }
        override fun chapters() = listOf(PlaybackChapter(0, "Beginning", 0, 30_000), PlaybackChapter(1, "Later", 30_000, 90_000))
        override fun selectChapter(index: Int): Boolean {
            val chapter = chapters().firstOrNull { it.index == index } ?: return false
            chapterIndex = index; position = chapter.startMillis; return true
        }
        fun ready() {
            val before = pauses
            buffering = false; seekable = true; actuallyPlaying = true; listener?.invoke(VlcEvent.READY)
            renderedFrames++; listener?.invoke(VlcEvent.CHANGED); listener?.invoke(VlcEvent.CHANGED)
            if (pauses > before) { actuallyPlaying = false; listener?.invoke(VlcEvent.PAUSED) }
        }
    }

    @Test fun `speed timing and chapter changes preserve player and pause state across surface handoffs`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        try {
            player.setMediaItem(MediaItem.fromUri("https://media.example/video.mkv")); player.prepare(); driver.ready()
            assertEquals(Player.STATE_READY, player.playbackState)
            player.setPlaybackSpeed(1.5f)
            assertEquals(1.5f, player.playbackParameters.speed)
            assertEquals(1.5f, driver.speed)
            assertTrue(player.setSubtitleDelayMillis(-500))
            assertEquals(-500L, player.subtitleDelayMillis)
            assertEquals(2, player.chapters().size)
            assertTrue(player.selectChapter(1)); assertFalse(player.selectChapter(50))
            assertEquals(30_000L, player.currentPosition)
            val context = RuntimeEnvironment.getApplication()
            val full = SurfaceView(context); val mini = SurfaceView(context)
            player.setVideoSurfaceView(full); player.setVideoSurfaceView(mini); player.clearVideoSurfaceView(full)
            assertEquals(1, driver.opens)
            assertFalse(player.playWhenReady)
            assertEquals(1.5f, player.playbackParameters.speed)
            assertEquals(-500L, player.subtitleDelayMillis)
        } finally { player.release() }
    }

    @Test fun `applying VLC caption defaults reloads current media while preserving position paused intent speed and tracks`() {
        val driver = Driver(); val player = LibVlcPlayer(driver, "en")
        try {
            player.setMediaItem(MediaItem.fromUri("https://media.example/video.mkv"), 32_000); player.prepare(); driver.ready()
            val group = player.currentTracks.groups.first().mediaTrackGroup
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group, 0)).build()
            player.setPlaybackSpeed(1.25f); player.setSubtitleDelayMillis(1000)
            val selected = CaptionAppearance(size = CaptionSize.EXTRA_LARGE, position = CaptionPosition.RAISED)
            player.setCaptionAppearance(selected)
            assertEquals(2, driver.opens)
            assertEquals(selected, driver.appearance)
            assertFalse(player.playWhenReady)
            driver.ready()
            assertEquals(32_000L, player.currentPosition)
            assertEquals(Player.STATE_READY, player.playbackState)
            assertFalse(player.playWhenReady)
            assertEquals(1.25f, player.playbackParameters.speed)
            assertEquals(1000L, player.subtitleDelayMillis)
            assertTrue(driver.selections.count { it == C.TRACK_TYPE_AUDIO to 7 } >= 2)
        } finally { player.release() }
    }
}
