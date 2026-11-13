package dev.pov.tv

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.RendererDiscoverer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.RendererVideoSupport

/** Starts only from the explicit picker. Discovery stops after thirty seconds or on close. */
internal class NativeVlcRendererDiscovery(
    private val app: Context,
    private val library: LibVLC,
    private val route: (RendererItem, String) -> Boolean,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var active: Scan? = null
    val supported: Boolean get() = runCatching { RendererDiscoverer.list(library)?.any { it.name == "microdns_renderer" } == true }.getOrDefault(false)

    fun discover(update: (VlcRendererSnapshot) -> Unit, failure: (String) -> Unit): VlcRendererScan? {
        close()
        if (!supported) { failure("This VLC build does not provide Chromecast discovery."); return null }
        return Scan(update, failure).also { active = it; it.start() }
    }
    fun select(id: String): Boolean = active?.select(id) ?: false
    override fun close() { active?.close(); active = null }

    private inner class Scan(
        private val update: (VlcRendererSnapshot) -> Unit,
        private val failure: (String) -> Unit,
    ) : VlcRendererScan {
        private val discoverers = mutableListOf<RendererDiscoverer>()
        private var lock: WifiManager.MulticastLock? = null
        private var closed = false
        private var searching = false
        private val leases = RendererLeaseBook<RendererItem>({ it.retain() }, { it.release() }, {
            route(it, it.displayName.filter { ch -> !ch.isISOControl() }.take(120).ifBlank { "Cast display" })
        })
        private val timeout = Runnable { stopDiscovery(); publish() }
        fun start() {
            try {
                val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                lock = wifi?.createMulticastLock("cameo-cast-picker")?.apply { setReferenceCounted(false); acquire() }
                val discoverer = RendererDiscoverer(library, "microdns_renderer")
                discoverers += discoverer
                discoverer.setEventListener { event ->
                    if (!closed && searching) {
                        val item = event.item
                        if (event.type == RendererDiscoverer.Event.ItemAdded && RendererVideoSupport.isVideoChromecast(item))
                            leases.add(item, item.displayName)
                        else if (event.type == RendererDiscoverer.Event.ItemDeleted) leases.remove(item)
                        publish()
                    }
                }
                searching = true
                if (!discoverer.start()) throw IllegalStateException("Discovery did not start")
                handler.postDelayed(timeout, 30_000L)
                publish()
            } catch (_: Exception) {
                close()
                failure("Cast discovery could not start. Connect to the receiver's local network and check Android network permissions.")
            }
        }
        fun select(id: String): Boolean = !closed && leases.select(id)
        private fun publish() { if (!closed) update(VlcRendererSnapshot(leases.choices(), searching)) }
        private fun stopDiscovery() {
            searching = false
            handler.removeCallbacks(timeout)
            // stop() itself releases this wrapper; do not call release() a second time.
            discoverers.toList().forEach { runCatching { it.stop() } }
            discoverers.clear()
            lock?.let { runCatching { if (it.isHeld) it.release() } }
            lock = null
        }
        override fun close() {
            if (closed) return
            closed = true
            stopDiscovery()
            leases.clear()
            if (active === this) active = null
        }
    }
}
