package dev.pov.tv

import androidx.media3.common.C
import androidx.media3.common.Format

/** Native track descriptions and parsed stream metadata can arrive separately or disagree. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object VlcTrackFormats {
    fun named(metadata: Format?, nativeName: String?, type: Int): Format {
        require(type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_TEXT)
        val base = metadata ?: Format.Builder().setSampleMimeType(
            if (type == C.TRACK_TYPE_AUDIO) "audio/x-vlc-unknown" else "text/x-vlc-subtitle"
        ).build()
        val label = clean(nativeName) ?: clean(base.label)
        // Format 1.8 populates labels from label. buildUpon retains that list, so changing
        // only label can violate Format's label-is-part-of-labels invariant and crash.
        return base.buildUpon().setLabels(emptyList()).setLabel(label).build()
    }

    private fun clean(value: String?): String? = value?.replace(Regex("[\\p{Cntrl}]"), " ")
        ?.trim()?.take(160)?.takeIf(String::isNotBlank)
}
