package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test

class PlaybackHealthMonitorTest {
    private fun sample(now: Long, position: Long = 0L, frames: Long? = 0L, playing: Boolean = true,
        buffering: Boolean = false, video: Boolean = true, ended: Boolean = false) =
        PlaybackHealthSample(now, playing, buffering, ended, position, video, frames)

    @Test fun `unopened media gets useful timeout and paused media never times out`() {
        val watchdog = PlaybackHealthMonitor()
        assertNull(watchdog.sample(sample(0, buffering = true)))
        assertNull(watchdog.sample(sample(29_999, buffering = true)))
        assertEquals(PlaybackHealthIssue.OPENING_TIMEOUT, watchdog.sample(sample(30_000, buffering = true)))
        assertNull(watchdog.sample(sample(31_000, playing = false)))
        assertNull(watchdog.sample(sample(900_000, playing = false)))
        assertNull(watchdog.sample(sample(901_000)))
        assertNull(watchdog.sample(sample(930_000)))
    }

    @Test fun `audio and unknown video counters use clock progress without fabricating frozen video evidence`() {
        for ((video, frames) in listOf(false to 0L, true to null)) {
            val watchdog = PlaybackHealthMonitor()
            watchdog.sample(sample(0, video = video, frames = frames))
            for (second in 1..100) assertNull(watchdog.sample(sample(second * 1_000L, second * 1_000L,
                video = video, frames = frames)))
        }
    }

    @Test fun `advancing timeline with frozen picture counters is distinguished from a stopped clock`() {
        val watchdog = PlaybackHealthMonitor()
        watchdog.sample(sample(0, position = 1_000, frames = 100))
        for (second in 1..44) assertNull(watchdog.sample(sample(second * 1_000L, (second + 1) * 1_000L, 100)))
        assertEquals(PlaybackHealthIssue.VIDEO_OUTPUT_STALLED, watchdog.sample(sample(45_000, 46_000, 100)))
        val clock = PlaybackHealthMonitor()
        clock.sample(sample(0, 1_000, frames = null, video = false))
        assertEquals(PlaybackHealthIssue.MEDIA_CLOCK_STALLED, clock.sample(sample(45_000, 1_000, frames = null, video = false)))
    }

    @Test fun `buffering has separate generous deadline and gives fresh stall grace after recovery`() {
        val watchdog = PlaybackHealthMonitor()
        watchdog.sample(sample(0, 1_000, 100))
        watchdog.sample(sample(1_000, 1_000, 100, buffering = true))
        assertNull(watchdog.sample(sample(46_000, 1_000, 100, buffering = true)))
        assertNull(watchdog.sample(sample(90_000, 1_000, 100, buffering = true)))
        assertEquals(PlaybackHealthIssue.BUFFERING_TIMEOUT, watchdog.sample(sample(91_000, 1_000, 100, buffering = true)))
        assertNull(watchdog.sample(sample(92_000, 1_000, 100)))
        assertNull(watchdog.sample(sample(136_000, 1_000, 100)))
        assertEquals(PlaybackHealthIssue.VIDEO_OUTPUT_STALLED, watchdog.sample(sample(137_000, 1_000, 100)))
    }

    @Test fun `seeks counter resets and ended playback do not reuse a stale stall deadline`() {
        val watchdog = PlaybackHealthMonitor()
        watchdog.sample(sample(0, 20_000, 100))
        assertNull(watchdog.sample(sample(44_000, 20_000, 100)))
        assertNull(watchdog.sample(sample(45_000, 5_000, 100)))
        assertNull(watchdog.sample(sample(89_000, 5_000, 100)))
        assertNull(watchdog.sample(sample(90_000, 5_000, 1)))
        assertNull(watchdog.sample(sample(500_000, 5_000, 1, ended = true)))
    }
}
