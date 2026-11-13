package dev.pov.tv

/** Mirrors the portable caption defaults from Cameo desktop; never contains account data. */
internal data class CaptionAppearance(
    val size: Int = 16,
    val color: Int = 0xFFFFFF,
    val backgroundOpacity: Int = 0,
    val bottomMargin: Int = 0,
) {
    init {
        require(size in SIZES && color in COLORS && backgroundOpacity in BACKGROUNDS && bottomMargin in MARGINS)
    }

    fun vlcOptions(): List<String> = listOf(
        ":freetype-rel-fontsize=$size", ":freetype-color=$color",
        ":freetype-background-opacity=$backgroundOpacity", ":freetype-background-color=0",
        ":sub-margin=$bottomMargin",
    )

    companion object {
        val SIZES = listOf(22, 16, 12, 9)
        val SIZE_LABELS = listOf("Small", "Medium", "Large", "Extra large")
        val COLORS = listOf(0xFFFFFF, 0xFFFF00, 0x00FFFF)
        val COLOR_LABELS = listOf("White", "Yellow", "Cyan")
        val BACKGROUNDS = listOf(0, 128, 220)
        val BACKGROUND_LABELS = listOf("Transparent", "Soft black", "Dark black")
        val MARGINS = listOf(0, 40, 100)
        val MARGIN_LABELS = listOf("Normal", "Raised", "High")
    }
}

internal data class PlaybackChapter(val index: Int, val name: String, val startMillis: Long, val durationMillis: Long, val selected: Boolean)
internal data class NativePlaybackDetails(val decodedFrames: Long, val displayedFrames: Long, val droppedFrames: Long, val inputKbitPerSecond: Long)
