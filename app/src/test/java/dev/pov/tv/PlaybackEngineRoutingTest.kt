package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test

class PlaybackEngineRoutingTest {
    @Test fun `VLC preference routes recognized DASH and precautionary HLS manifests before native startup`() {
        for ((address, kind) in listOf("https://public.example/video.mpd?token=fixture" to "DASH",
            "https://public.example/VIDEO.M3U8" to "HLS", "https://public.example/video%2Empd" to "DASH")) {
            val preference = PlaybackEngine.VLC
            val route = PlaybackEngineRouting.choose(preference, address, false)
            assertEquals(PlaybackEngine.MEDIA3, route.engine)
            assertTrue(route.notice!!.contains(kind))
            assertEquals(PlaybackEngine.VLC, preference)
        }
    }
    @Test fun `progressive native playback and Media3 preference retain their selected engine`() {
        val native = PlaybackEngineRouting.choose(PlaybackEngine.VLC, "https://public.example/test5.mkv?label=video.mpd", false)
        assertEquals(PlaybackEngine.VLC, native.engine); assertNull(native.notice)
        val media3 = PlaybackEngineRouting.choose(PlaybackEngine.MEDIA3, "https://public.example/video.mpd", false)
        assertEquals(PlaybackEngine.MEDIA3, media3.engine); assertNull(media3.notice)
    }
    @Test fun `protected source route retains its specific authorization explanation`() {
        val route = PlaybackEngineRouting.choose(PlaybackEngine.VLC, "https://members.easynews.com/video.mpd", true)
        assertEquals(PlaybackEngine.MEDIA3, route.engine)
        assertEquals("Using Media3 for this protected source", route.notice)
    }
}
