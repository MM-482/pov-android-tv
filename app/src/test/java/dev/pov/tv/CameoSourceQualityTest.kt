package dev.pov.tv

import dev.pov.tv.data.SourceItem
import org.junit.Assert.*
import org.junit.Test

class CameoSourceQualityTest {
    private fun source(title: String, detail: String = "", provider: String = "Fixture") = SourceItem(title, provider, detail=detail)

    @Test fun `reported desktop resolution labels and common aliases are recognized`() {
        mapOf("Film_2160p_HEVC.mkv" to "4K", "Film 4k" to "4K", "Film UHD" to "4K", "Film 2160" to "4K",
            "Film_1080p_HEVC.mkv" to "1080p", "Film FHD" to "1080p", "Film Full-HD" to "1080p", "Film 1080i" to "1080p",
            "Film [720p]" to "720p", "Film 576p" to "576p", "Film 480" to "480p", "Film 360p" to "360p", "Film 240p" to "240p").forEach { (text,expected) ->
            assertEquals(text,expected,CameoSourceQuality.label(source(text)))
        }
    }

    @Test fun `quality can come from source detail but never from provider URL size or codec`() {
        assertEquals("1080p",CameoSourceQuality.label(source("Film","Version: 1080p • 3GB")))
        val misleading=source("Film REMUX HEVC HDR 90GB","Original quality • cached","4K Provider").copy(directUrl="https://media.example/2160p/movie.mkv")
        assertEquals(CameoSourceQuality.UNKNOWN,CameoSourceQuality.label(misleading))
        assertEquals(CameoSourceQuality.UNKNOWN,CameoSourceQuality.label(source("Film HD")))
    }

    @Test fun `substring year and unrelated numeric tokens are not resolutions`() {
        listOf("Film 10800p","Film x1080p60","Film 7200","Movie 2026 24000MB","Film Number1080","Film S04E04K").forEach {
            assertEquals(it,CameoSourceQuality.UNKNOWN,CameoSourceQuality.label(source(it)))
        }
    }

    @Test fun `quality options contain only present explicit labels in descending order then unknown`() {
        val sources=listOf(source("Film720p"),source("Film 480p"),source("Film 4K"),source("Film 1080p"),source("Other 4K"),source("Film 720p"))
        assertEquals(listOf("4K","1080p","720p","480p",CameoSourceQuality.UNKNOWN),CameoSourceQuality.options(sources))
        assertEquals(emptyList<String>(),CameoSourceQuality.options(emptyList()))
        assertEquals(listOf(CameoSourceQuality.UNKNOWN),CameoSourceQuality.options(listOf(source("Unspecified"))))
        assertFalse(CameoSourceQuality.options(sources).contains("Original"))
    }

    @Test fun `filter keeps every edition in original order and never guesses the largest`() {
        val a=source("Film.1080p.EditionA").copy(infoHash="a".repeat(40))
        val b=source("Film.720p.EditionB").copy(infoHash="b".repeat(40))
        val c=source("Film.1080p.EditionC").copy(infoHash="c".repeat(40))
        val d=source("Unspecified").copy(infoHash="d".repeat(40))
        val all=listOf(a,b,c,d)
        assertEquals(listOf(a,c),CameoSourceQuality.filter(all,"1080p"))
        assertSame(a,CameoSourceQuality.filter(all,"1080p")[0])
        assertSame(c,CameoSourceQuality.filter(all,"1080p")[1])
        assertEquals(listOf(d),CameoSourceQuality.filter(all,CameoSourceQuality.UNKNOWN))
        assertTrue(CameoSourceQuality.filter(all,"Original").isEmpty())
    }

    @Test fun `classification has no side effects on source credentials or file identifiers`() {
        val input=source("Film 1080p").copy(fileIndex=17,requestHeaders=mapOf("Authorization" to "fixture-only"),sourceAddonId="addon")
        val filtered=CameoSourceQuality.filter(listOf(input),"1080p").single()
        assertEquals(17,filtered.fileIndex);assertEquals(input.requestHeaders,filtered.requestHeaders);assertEquals("addon",filtered.sourceAddonId)
    }
}
