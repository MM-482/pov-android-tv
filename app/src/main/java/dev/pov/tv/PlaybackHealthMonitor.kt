package dev.pov.tv

internal enum class PlaybackHealthIssue {
    OPENING_TIMEOUT, BUFFERING_TIMEOUT, VIDEO_OUTPUT_STALLED, MEDIA_CLOCK_STALLED,
}

internal data class PlaybackHealthSample(
    val nowMillis: Long,
    val wantsPlayback: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    val positionMillis: Long,
    val videoExpected: Boolean,
    val renderedFrames: Long?,
)

/** Pure monotonic watchdog. A paused clock and an active network buffer are different states. */
internal class PlaybackHealthMonitor(
    private val openingTimeoutMillis: Long = 30_000L,
    private val stalledTimeoutMillis: Long = 45_000L,
    private val bufferingTimeoutMillis: Long = 90_000L,
) {
    private var previous: PlaybackHealthSample? = null
    private var openedAt = 0L
    private var clockAdvancedAt = 0L
    private var pictureAdvancedAt = 0L
    private var bufferingAt: Long? = null
    private var confirmed = false

    fun reset() { previous = null; bufferingAt = null; confirmed = false }

    fun sample(current: PlaybackHealthSample): PlaybackHealthIssue? {
        if (!current.wantsPlayback || current.ended) { reset(); return null }
        val old = previous
        if (old == null || current.nowMillis < old.nowMillis ||
            current.positionMillis < old.positionMillis || current.positionMillis - old.positionMillis > 15_000L ||
            (current.renderedFrames != null && old.renderedFrames != null && current.renderedFrames < old.renderedFrames)) {
            openedAt = current.nowMillis
            clockAdvancedAt = current.nowMillis
            pictureAdvancedAt = current.nowMillis
            bufferingAt = current.nowMillis.takeIf { current.buffering }
            confirmed = (current.renderedFrames ?: 0L) > 0L ||
                ((!current.videoExpected || current.renderedFrames == null) && current.positionMillis > 0L)
            previous = current
            return null
        }
        if (current.positionMillis > old.positionMillis + 50L) clockAdvancedAt = current.nowMillis
        if (current.renderedFrames != null && current.renderedFrames > (old.renderedFrames ?: 0L)) {
            pictureAdvancedAt = current.nowMillis
            confirmed = true
        }
        if ((!current.videoExpected || current.renderedFrames == null) && current.positionMillis > old.positionMillis + 50L)
            confirmed = true
        if (current.buffering) {
            if (bufferingAt == null) bufferingAt = current.nowMillis
        } else if (bufferingAt != null) {
            bufferingAt = null
            clockAdvancedAt = current.nowMillis
            pictureAdvancedAt = current.nowMillis
        }
        previous = current
        if (!confirmed && current.nowMillis - openedAt >= openingTimeoutMillis) return PlaybackHealthIssue.OPENING_TIMEOUT
        if (current.buffering) return if (confirmed && current.nowMillis - requireNotNull(bufferingAt) >= bufferingTimeoutMillis)
            PlaybackHealthIssue.BUFFERING_TIMEOUT else null
        if (confirmed && current.videoExpected && current.renderedFrames != null &&
            current.nowMillis - pictureAdvancedAt >= stalledTimeoutMillis) return PlaybackHealthIssue.VIDEO_OUTPUT_STALLED
        if (confirmed && current.nowMillis - clockAdvancedAt >= stalledTimeoutMillis) return PlaybackHealthIssue.MEDIA_CLOCK_STALLED
        return null
    }
}
