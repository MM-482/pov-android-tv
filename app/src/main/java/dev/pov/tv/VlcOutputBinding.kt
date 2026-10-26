package dev.pov.tv

/** One video-enable acknowledgement for the current pair of native output surfaces. */
internal class VlcOutputBinding(private val enableVideo: () -> Unit) {
    class Callbacks(val created: () -> Unit, val destroyed: () -> Unit)
    private var generation = 0L
    var surfacesReady = false
        private set

    fun begin(): Callbacks {
        val expected = ++generation
        surfacesReady = false
        return Callbacks(created = {
            if (generation == expected && !surfacesReady) {
                surfacesReady = true
                // LibVLC's built-in re-enable depends on an asynchronous Vout=0
                // count. A fast detach/attach can reach new surfaces before that
                // old count arrives. Enable this ready binding explicitly.
                enableVideo()
            }
        }, destroyed = { if (generation == expected) clear() })
    }

    fun clear() { generation++; surfacesReady = false }
}
