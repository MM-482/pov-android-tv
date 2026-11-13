package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test

class VlcRendererDiscoveryTest {
    @Test fun `duplicate discovery retains once and close releases exactly once`() {
        val retained = mutableListOf<Int>(); val released = mutableListOf<Int>()
        val leases = RendererLeaseBook<Int>({ retained += it; true }, { released += it }, { true })
        leases.add(7, "Living room"); leases.add(7, "Duplicate")
        assertEquals(listOf(7), retained)
        assertEquals(listOf("Living room"), leases.choices().map { it.name })
        leases.clear(); leases.clear()
        assertEquals(listOf(7), released)
    }
    @Test fun `removed receiver cannot be selected and stale lease is released`() {
        val released = mutableListOf<Int>(); val selected = mutableListOf<Int>()
        val leases = RendererLeaseBook<Int>({ true }, { released += it }, { selected += it; true })
        leases.add(3, "Display")
        val id = leases.choices().single().id
        leases.remove(3); leases.remove(3)
        assertFalse(leases.select(id)); assertTrue(selected.isEmpty())
        assertEquals(listOf(3), released)
    }
    @Test fun `select does not release the picker reference before player accepts route`() {
        var released = false; var selected = false
        val leases = RendererLeaseBook<Int>({ true }, { released = true }, { assertFalse(released); selected = true; true })
        leases.add(1, "Display")
        assertTrue(leases.select(leases.choices().single().id)); assertTrue(selected)
        leases.clear(); assertTrue(released)
    }
    @Test fun `failed retain never exposes receiver or attempts release`() {
        var released = false
        val leases = RendererLeaseBook<Int>({ false }, { released = true }, { true })
        leases.add(1, "Display"); assertTrue(leases.choices().isEmpty())
        leases.clear(); assertFalse(released)
    }
    @Test fun `old picker token cannot select a different receiver in a new scan`() {
        val old = RendererLeaseBook<Int>({ true }, {}, { true })
        old.add(1, "Old")
        val token = old.choices().single().id
        old.clear()
        val current = RendererLeaseBook<Int>({ true }, {}, { true })
        current.add(2, "New")
        assertFalse(current.select(token))
    }
    @Test fun `receiver labels are bounded and contain no control characters`() {
        val leases = RendererLeaseBook<Int>({ true }, {}, { true })
        leases.add(1, "\u0000\n\t")
        leases.add(2, "x".repeat(200))
        assertEquals("Cast display", leases.choices()[0].name)
        assertEquals(120, leases.choices()[1].name.length)
    }
}
