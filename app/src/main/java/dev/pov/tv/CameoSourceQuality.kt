package dev.pov.tv

import dev.pov.tv.data.SourceItem

/** Display only resolutions explicitly advertised in source text, matching desktop PlaybackChoices. */
internal object CameoSourceQuality {
    const val UNKNOWN = "Quality not provided"
    private data class Quality(val label: String, val pattern: Regex)
    private fun pattern(value: String) = Regex("(?<![A-Za-z0-9])(?:$value)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
    private val qualities = listOf(
        Quality("4K", pattern("2160p?|4k|uhd")),
        Quality("1080p", pattern("1080[pi]?|fhd|full[ ._-]?hd")),
        Quality("720p", pattern("720p?")),
        Quality("576p", pattern("576p?")),
        Quality("480p", pattern("480p?")),
        Quality("360p", pattern("360p?")),
        Quality("240p", pattern("240p?")))

    fun label(source: SourceItem): String {
        // Provider name, URL, file size, codec and cache badges cannot establish video resolution.
        val advertised = source.title + " " + source.detail
        return qualities.firstOrNull { it.pattern.containsMatchIn(advertised) }?.label ?: UNKNOWN
    }

    fun options(sources: List<SourceItem>): List<String> {
        val present = sources.map(::label).toSet()
        return qualities.map { it.label }.filter { it in present } + if (UNKNOWN in present) listOf(UNKNOWN) else emptyList()
    }

    /** Retain original source order and every edition; selecting quality never selects a file. */
    fun filter(sources: List<SourceItem>, label: String): List<SourceItem> = sources.filter { CameoSourceQuality.label(it) == label }
}
