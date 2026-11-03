package dev.pov.tv

/** Device caption defaults shared by both playback engines. */
enum class CaptionSize(val label: String, val fraction: Float, val vlcSize: Int) {
    SMALL("Small", .04f, 22), MEDIUM("Medium", .0533f, 16),
    LARGE("Large", .0667f, 12), EXTRA_LARGE("Extra large", .08f, 9)
}
enum class CaptionColor(val label: String, val argb: Int) {
    WHITE("White", -1), YELLOW("Yellow", 0xFFFFFF00.toInt()), CYAN("Cyan", 0xFF00FFFF.toInt())
}
enum class CaptionBackground(val label: String, val opacity: Int) {
    TRANSPARENT("Transparent", 0), SOFT_BLACK("Soft black", 128), DARK_BLACK("Dark black", 220)
}
enum class CaptionPosition(val label: String, val padding: Float, val vlcMargin: Int) {
    NORMAL("Normal", .08f, 0), RAISED("Raised", .14f, 40), HIGH("High", .22f, 100)
}
data class CaptionAppearance(
    val size: CaptionSize = CaptionSize.MEDIUM,
    val color: CaptionColor = CaptionColor.WHITE,
    val background: CaptionBackground = CaptionBackground.TRANSPARENT,
    val position: CaptionPosition = CaptionPosition.NORMAL,
) {
    internal fun vlcOptions(): List<String> = listOf(
        ":freetype-rel-fontsize=${size.vlcSize}", ":freetype-color=${color.argb and 0xFFFFFF}",
        ":freetype-background-opacity=${background.opacity}", ":freetype-background-color=0",
        ":sub-margin=${position.vlcMargin}",
    )
}

internal data class PlaybackChapter(val index: Int, val title: String, val startMillis: Long, val durationMillis: Long)

/** Monotonic session timer: presentation/lifecycle changes cannot restart or extend it. */
internal class PlaybackSleepTimer(
    private val now: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onExpired: () -> Unit,
) {
    var deadline: Long? = null
        private set
    val remainingMillis: Long? get() = deadline?.let { (it - now()).coerceAtLeast(0) }
    private var pending: Runnable? = null
    private var generation = 0L

    fun setMinutes(minutes: Int?) {
        require(minutes == null || minutes in MINUTES) { "Choose a supported sleep timer." }
        restore(minutes?.let { now() + it * 60_000L })
    }

    fun restore(value: Long?) {
        clear()
        deadline = value ?: return
        val expected = generation
        val task = object : Runnable {
            override fun run() {
                if (generation != expected || deadline == null) return
                val remaining = remainingMillis ?: return
                if (remaining > 0) schedule(this, remaining)
                else { clear(); onExpired() }
            }
        }
        pending = task
        schedule(task, remainingMillis ?: 0)
    }

    fun clear() {
        generation++
        pending?.let(cancel)
        pending = null
        deadline = null
    }

    /** Handler delays use uptime; recheck elapsed time when waking from device sleep. */
    fun expireIfDue(): Boolean {
        val remaining = remainingMillis ?: return false
        if (remaining > 0) return false
        clear(); onExpired(); return true
    }

    companion object { val MINUTES = listOf(15, 30, 45, 60, 90) }
}
