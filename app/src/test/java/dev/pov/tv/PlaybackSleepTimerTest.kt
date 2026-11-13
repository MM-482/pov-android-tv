package dev.pov.tv

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackSleepTimerTest {
    private lateinit var player: ExoPlayer
    private val field = InAppPlaybackSession::class.java.getDeclaredField("player").apply { isAccessible = true }
    private var original: Any? = null

    @Before fun prepare() {
        PlaybackSleepTimer.cancel()
        original = field.get(null)
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        field.set(null, player)
        player.playWhenReady = true
    }

    @After fun cleanup() {
        PlaybackSleepTimer.cancel()
        field.set(null, original)
        player.release()
    }

    @Test fun `sleep timer remains session scoped and pauses only at its deadline`() {
        PlaybackSleepTimer.setMinutes(15)
        assertTrue(PlaybackSleepTimer.isActive)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(14))
        assertTrue(player.playWhenReady)
        assertTrue(PlaybackSleepTimer.remainingMillis in 59_000L..60_000L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertFalse(player.playWhenReady)
        assertFalse(PlaybackSleepTimer.isActive)
    }

    @Test fun `cancel and replacing interval remove the prior pending pause`() {
        PlaybackSleepTimer.setMinutes(15)
        PlaybackSleepTimer.setMinutes(30)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(15))
        assertTrue(player.playWhenReady)
        PlaybackSleepTimer.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(20))
        assertTrue(player.playWhenReady)
        assertEquals(0L, PlaybackSleepTimer.remainingMillis)
    }

    @Test fun `unsupported timer interval leaves existing deadline unchanged`() {
        PlaybackSleepTimer.setMinutes(15)
        try { PlaybackSleepTimer.setMinutes(-1); fail("Invalid interval accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(PlaybackSleepTimer.isActive)
        assertTrue(PlaybackSleepTimer.remainingMillis > 0)
    }
}
