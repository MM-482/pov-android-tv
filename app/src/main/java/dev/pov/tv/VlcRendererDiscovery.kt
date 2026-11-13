package dev.pov.tv

internal data class VlcRendererChoice(val id: String, val name: String)
internal data class VlcRendererSnapshot(val choices: List<VlcRendererChoice>, val searching: Boolean)
internal fun interface VlcRendererScan : AutoCloseable { override fun close() }

/** Picker-owned leases are separate from MediaPlayer's selected-renderer ownership. */
internal class RendererLeaseBook<T>(
    private val retain: (T) -> Boolean,
    private val release: (T) -> Unit,
    private val select: (T) -> Boolean,
) {
    private data class Entry<T>(val value: T, val choice: VlcRendererChoice)
    private val entries = linkedMapOf<String, Entry<T>>()
    private var serial = 0L
    private val session = java.util.UUID.randomUUID().toString()
    fun add(value: T, name: String) {
        if (entries.values.any { it.value == value } || !retain(value)) return
        val id = "$session:${++serial}"
        val safe = name.filter { !it.isISOControl() }.take(120).ifBlank { "Cast display" }
        entries[id] = Entry(value, VlcRendererChoice(id, safe))
    }
    fun remove(value: T) {
        val id = entries.entries.firstOrNull { it.value.value == value }?.key ?: return
        entries.remove(id)?.let { release(it.value) }
    }
    fun choices(): List<VlcRendererChoice> = entries.values.map { it.choice }
    fun select(id: String): Boolean = entries[id]?.let { select(it.value) } ?: false
    fun clear() { val owned = entries.values.toList(); entries.clear(); owned.forEach { release(it.value) } }
}
