package dev.pov.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** A session timer: browsing/mini-player Activity changes must not cancel a requested sleep. */
internal object PlaybackSleepTimer {
    val minuteChoices = listOf(15, 30, 45, 60, 90)
    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private val expire = object : Runnable {
        override fun run() {
            if (deadline == 0L) return
            if (remainingMillis > 0L) handler.postDelayed(this, remainingMillis)
            else {
                deadline = 0L
                InAppPlaybackSession.pauseForSleepTimer()
            }
        }
    }

    val remainingMillis: Long get() = if (deadline == 0L) 0L else (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    val isActive: Boolean get() = deadline != 0L

    fun setMinutes(minutes: Int) {
        require(minutes in minuteChoices) { "Choose a supported sleep interval." }
        handler.removeCallbacks(expire)
        deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.postDelayed(expire, minutes * 60_000L)
    }

    fun cancel() { deadline = 0L; handler.removeCallbacks(expire) }
}
