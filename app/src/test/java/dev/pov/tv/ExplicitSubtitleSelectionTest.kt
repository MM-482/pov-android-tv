package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test

class ExplicitSubtitleSelectionTest {
    @Test fun `prior Off is absent before synchronous native track added callback`() {
        val desired = mutableMapOf(3 to -1, 1 to 7)
        assertTrue(ExplicitSubtitleSelection.attach(3, desired) {
            assertFalse("ESAdded must not observe stale subtitle Off", desired.containsKey(3))
            assertEquals(7, desired[1])
            true
        })
        assertFalse(desired.containsKey(3))
        assertEquals(7, desired[1])
    }

    @Test fun `failed attach restores prior Off without losing audio or a newer explicit choice`() {
        val desired = mutableMapOf(3 to -1, 1 to 7)
        assertFalse(ExplicitSubtitleSelection.attach(3, desired) { false })
        assertEquals(-1, desired[3]); assertEquals(7, desired[1])
        assertFalse(ExplicitSubtitleSelection.attach(3, desired) { desired[3] = 42; false })
        assertEquals(42, desired[3])
        assertFalse(ExplicitSubtitleSelection.attach(3, desired) { throw IllegalStateException("Fixture native failure") })
        assertEquals(42, desired[3])
    }
}
