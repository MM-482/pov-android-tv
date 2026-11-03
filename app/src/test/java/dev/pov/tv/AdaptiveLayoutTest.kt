package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun `cover and unfolded windows keep bottom navigation while content gains columns`() {
        val cover = AdaptiveWindow(360, 780, false)
        val unfolded = AdaptiveWindow(720, 690, false)
        assertEquals(WindowWidthClass.COMPACT, cover.widthClass)
        assertTrue(cover.useBottomNavigation)
        assertEquals(WindowWidthClass.MEDIUM, unfolded.widthClass)
        assertTrue(unfolded.useBottomNavigation)
        assertEquals(328, cover.contentWidthDp)
        assertEquals(688, unfolded.contentWidthDp)
        assertTrue(unfolded.gridColumns() > cover.gridColumns())
        assertTrue(unfolded.splitFeature)
    }

    @Test fun `window size boundaries are based on dp not a physical device model`() {
        for (width in listOf(360, 599, 600, 720, 839, 840, 1024)) {
            val window = AdaptiveWindow(width, 900, false)
            assertTrue("Bottom navigation at ${width}dp", window.useBottomNavigation)
            assertEquals("No side navigation width is reserved at ${width}dp", width - (if (width >= 840) 56 else 32), window.contentWidthDp)
        }
        assertEquals(WindowWidthClass.COMPACT, AdaptiveWindow(599, 900, false).widthClass)
        assertEquals(WindowWidthClass.MEDIUM, AdaptiveWindow(600, 900, false).widthClass)
        assertEquals(WindowWidthClass.MEDIUM, AdaptiveWindow(839, 900, false).widthClass)
        assertEquals(WindowWidthClass.EXPANDED, AdaptiveWindow(840, 900, false).widthClass)
        assertTrue(AdaptiveWindow(1024, 800, false).splitFeature)
        assertFalse(AdaptiveWindow(591, 900, false).splitFeature)
        assertTrue(AdaptiveWindow(592, 900, false).splitFeature)
        assertTrue(AdaptiveWindow(600, 900, false).splitFeature)
    }

    @Test fun `TV windows retain separate remote navigation irrespective of width`() {
        for (width in listOf(360, 720, 1920)) {
            val tv = AdaptiveWindow(width, 1080, true)
            assertFalse(tv.useBottomNavigation)
            assertFalse(tv.splitFeature)
        }
    }

    @Test fun `small and large windows always have bounded usable grids`() {
        assertEquals(1, AdaptiveWindow(240, 600, false).gridColumns())
        assertTrue(AdaptiveWindow(320, 700, false).contentWidthDp >= 288)
        assertEquals(6, AdaptiveWindow(2600, 1200, false).gridColumns())
        assertEquals(1, AdaptiveWindow(20, 30, false).contentWidthDp)
    }
}
