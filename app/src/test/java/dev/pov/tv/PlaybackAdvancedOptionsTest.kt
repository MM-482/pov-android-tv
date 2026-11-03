package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test

class PlaybackAdvancedOptionsTest {
    private class Clock {
        var now = 10_000L
        var expirations = 0
        val tasks = mutableListOf<Pair<Runnable, Long>>()
        val timer = PlaybackSleepTimer({ now }, { task, delay -> tasks += task to delay },
            { task -> tasks.removeAll { it.first === task } }, { expirations++ })
    }

    @Test fun `presentation independent deadline expires once and canceled old callbacks cannot pause newer playback`() {
        val c = Clock()
        c.timer.setMinutes(15)
        val originalDeadline = c.timer.deadline
        val oldTask = c.tasks.single().first
        c.now += 3 * 60_000
        assertEquals(12 * 60_000L, c.timer.remainingMillis)
        assertEquals(originalDeadline, c.timer.deadline)
        c.timer.setMinutes(30)
        oldTask.run()
        assertEquals(0, c.expirations)
        assertEquals(30 * 60_000L, c.timer.remainingMillis)
        val currentTask = c.tasks.single().first
        c.now += 30 * 60_000
        currentTask.run(); currentTask.run()
        assertEquals(1, c.expirations)
        assertNull(c.timer.deadline)
    }

    @Test fun `elapsed restored timer pauses immediately instead of silently restarting`() {
        val c = Clock()
        c.timer.restore(c.now - 2_000)
        assertEquals(0L, c.tasks.single().second)
        c.tasks.single().first.run()
        assertEquals(1, c.expirations)
        assertNull(c.timer.remainingMillis)
    }

    @Test fun `stop or off cancels timer and cannot affect later session`() {
        val c = Clock()
        c.timer.setMinutes(90)
        val task = c.tasks.single().first
        c.timer.setMinutes(null)
        c.now += 91 * 60_000
        task.run()
        assertEquals(0, c.expirations)
        assertTrue(c.tasks.isEmpty())
        assertNull(c.timer.deadline)
    }

    @Test fun `device sleep cannot resume playback after the sleep deadline while Handler is delayed`() {
        val c = Clock()
        c.timer.setMinutes(15)
        val delayed = c.tasks.single().first
        c.now += 16 * 60_000
        assertTrue(c.timer.expireIfDue())
        delayed.run()
        assertEquals(1, c.expirations)
        assertFalse(c.timer.expireIfDue())
    }
}
