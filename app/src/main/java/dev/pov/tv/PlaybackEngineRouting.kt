package dev.pov.tv

import java.net.URI
import java.util.Locale

internal data class PlaybackEngineRoute(val engine: PlaybackEngine, val notice: String? = null)

/** Route identified manifests before loading native code; SIGABRT cannot become a Java error. */
internal object PlaybackEngineRouting {
    fun choose(preference: PlaybackEngine, address: String, protectedSource: Boolean): PlaybackEngineRoute {
        if (preference != PlaybackEngine.VLC) return PlaybackEngineRoute(preference)
        if (protectedSource) return PlaybackEngineRoute(PlaybackEngine.MEDIA3, "Using Media3 for this protected source")
        val path = try { URI(address).path?.lowercase(Locale.ROOT).orEmpty() } catch (_: Exception) { "" }
        val kind = when {
            path.endsWith(".mpd") -> "DASH"
            // HLS uses the same native adaptive plumbing. This is a precaution; the
            // reproduced Sony abort/no-output evidence is for the public DASH sample.
            path.endsWith(".m3u8") -> "HLS"
            else -> null
        }
        return if (kind == null) PlaybackEngineRoute(PlaybackEngine.VLC)
            else PlaybackEngineRoute(PlaybackEngine.MEDIA3, "Using Media3 for this $kind stream")
    }
}
