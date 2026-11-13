package dev.pov.tv

/** Native addSlave may publish ESAdded synchronously; retire a previous Off before that callback. */
internal object ExplicitSubtitleSelection {
    fun attach(type: Int, desiredTracks: MutableMap<Int, Int>, add: () -> Boolean): Boolean {
        val previous = desiredTracks.remove(type)
        fun rollback() { if (previous != null && type !in desiredTracks) desiredTracks[type] = previous }
        return try {
            add().also { if (!it) rollback() }
        } catch (_: RuntimeException) { rollback(); false }
    }
}
