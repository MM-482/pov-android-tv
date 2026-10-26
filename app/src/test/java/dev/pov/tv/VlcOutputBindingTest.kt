package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test

class VlcOutputBindingTest {
    @Test fun `ready new surfaces reenable video despite stale native vout count without transport commands`() {
        var videoEnabled = true
        var nativeVoutCount = 1
        val commands = mutableListOf<String>()
        val binding = VlcOutputBinding { videoEnabled = true; commands += "enable-video" }
        val old = binding.begin()
        old.created()
        commands.clear()
        // Exact upstream order: old detach disables the track. The async Vout=0
        // acknowledgement has not arrived when the new paired surfaces are ready.
        videoEnabled = false
        old.destroyed()
        val current = binding.begin()
        assertEquals(1, nativeVoutCount)
        current.created(); current.created()
        nativeVoutCount = 0
        assertTrue(videoEnabled)
        assertTrue(binding.surfacesReady)
        assertEquals(listOf("enable-video"), commands)
        assertEquals(0, nativeVoutCount)
    }

    @Test fun `old target callbacks after replacement or release cannot affect current surfaces`() {
        var enables = 0
        val binding = VlcOutputBinding { enables++ }
        val old = binding.begin()
        val current = binding.begin()
        old.created(); old.destroyed()
        assertEquals(0, enables)
        current.created()
        assertEquals(1, enables)
        old.destroyed()
        assertTrue(binding.surfacesReady)
        binding.clear()
        current.created(); current.destroyed()
        assertFalse(binding.surfacesReady)
        assertEquals(1, enables)
    }

    @Test fun `recreated output with reused view identity enables again once per binding`() {
        var enables = 0
        val binding = VlcOutputBinding { enables++ }
        val first = binding.begin()
        first.created(); first.destroyed()
        val recreated = binding.begin()
        recreated.created(); recreated.created(); first.created()
        assertEquals(2, enables)
        assertTrue(binding.surfacesReady)
    }
}
