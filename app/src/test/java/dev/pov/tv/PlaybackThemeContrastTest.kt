package dev.pov.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PlaybackThemeContrastTest {
    @Test fun `every selectable theme retains visible focus against the dark player controller`() {
        val scrim = luminance(0xFF10141C.toInt())
        ThemeSpec.selectableThemes.forEach { theme ->
            val foreground = luminance(theme.playbackAccent)
            assertTrue("${theme.displayName} player focus must remain visible", (foreground + .05) / (scrim + .05) >= 3.0)
        }
    }

    @Test fun `light theme keeps its dark home accent while playback receives a light focus color`() {
        assertTrue(luminance(ThemeSpec.ATELIER.palette.accent) < .1)
        assertTrue(luminance(ThemeSpec.ATELIER.playbackAccent) > .8)
    }

    private fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
    }
}
