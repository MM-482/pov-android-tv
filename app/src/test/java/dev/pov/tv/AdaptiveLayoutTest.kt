package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun `cover window uses bottom navigation while unfolded window gains rail and more columns`() {
        val cover = AdaptiveWindow(360, 780, false)
        val unfolded = AdaptiveWindow(720, 690, false)
        assertEquals(WindowWidthClass.COMPACT, cover.widthClass)
        assertFalse(cover.useNavigationRail)
        assertEquals(WindowWidthClass.MEDIUM, unfolded.widthClass)
        assertTrue(unfolded.useNavigationRail)
        assertTrue(unfolded.gridColumns() > cover.gridColumns())
        assertTrue(unfolded.splitFeature)
    }

    @Test fun `window size boundaries are based on dp not a physical device model`() {
        assertFalse(AdaptiveWindow(599, 900, false).useNavigationRail)
        assertTrue(AdaptiveWindow(600, 900, false).useNavigationRail)
        assertEquals(WindowWidthClass.MEDIUM, AdaptiveWindow(839, 900, false).widthClass)
        assertEquals(WindowWidthClass.EXPANDED, AdaptiveWindow(840, 900, false).widthClass)
        assertTrue(AdaptiveWindow(1024, 800, false).splitFeature)
        assertFalse(AdaptiveWindow(600, 900, false).splitFeature)
        assertTrue(AdaptiveWindow(680, 900, false).splitFeature)
    }

    @Test fun `TV windows retain separate remote navigation irrespective of width`() {
        val tv = AdaptiveWindow(1920, 1080, true)
        assertFalse(tv.useNavigationRail)
        assertFalse(tv.splitFeature)
    }

    @Test fun `small and large windows always have bounded usable grids`() {
        assertEquals(1, AdaptiveWindow(240, 600, false).gridColumns())
        assertTrue(AdaptiveWindow(320, 700, false).contentWidthDp >= 288)
        assertEquals(6, AdaptiveWindow(2600, 1200, false).gridColumns())
        assertEquals(1, AdaptiveWindow(20, 30, false).contentWidthDp)
    }
}
