package dev.pov.tv

import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CameoPlaybackQueueTest {
    @Before fun before() {CameoPlaybackQueue.clear()}
    @After fun after() {CameoPlaybackQueue.clear()}
    private fun local(name:String)=CameoLibraryEntry(name,name,"local","content://documents/video/$name")

    @Test fun `starting selected entry opens it once and follows exact saved order`() {
        val entries=listOf(local("One"),local("Two"),local("Three"));val opened=mutableListOf<String>()
        CameoPlaybackQueue.start("Queue",entries,1){opened+=it.name}
        assertEquals(listOf("Two"),opened);assertTrue(CameoPlaybackQueue.hasPrevious());assertTrue(CameoPlaybackQueue.hasNext())
        assertTrue(CameoPlaybackQueue.onPlaybackEnded());assertEquals(listOf("Two","Three"),opened)
        assertFalse(CameoPlaybackQueue.next());assertEquals(entries.last(),CameoPlaybackQueue.current())
        assertTrue(CameoPlaybackQueue.previous());assertEquals("Two",CameoPlaybackQueue.current()!!.name)
    }

    @Test fun `empty playlist never calls opener`() {
        var calls=0
        try {CameoPlaybackQueue.start("Empty",emptyList()){calls++};fail("Expected empty playlist error")}catch(_:IllegalArgumentException){}
        assertEquals(0,calls);assertNull(CameoPlaybackQueue.current())
    }

    @Test fun `queue takes snapshot and clear prevents late end event opening another entry`() {
        val entries=mutableListOf(local("One"),local("Two"));var calls=0
        CameoPlaybackQueue.start("Queue",entries){calls++};entries.clear()
        assertTrue(CameoPlaybackQueue.next());assertEquals(2,calls)
        CameoPlaybackQueue.clear();assertFalse(CameoPlaybackQueue.onPlaybackEnded());assertFalse(CameoPlaybackQueue.hasPrevious())
        assertFalse(CameoPlaybackQueue.hasNext());assertNull(CameoPlaybackQueue.current());assertEquals("",CameoPlaybackQueue.name)
    }

    @Test fun `catalog dispatch preserves exact episode and does not resolve a source inside queue`() {
        val identity=PlaybackIdentity("tt1234567","series","Show",2,3)
        val entry=CameoLibraryEntry.catalog(MediaItemData(identity.imdbId,identity.type,identity.title),identity)
        val opened=mutableListOf<CameoLibraryEntry>()
        CameoPlaybackQueue.start("Episodes",listOf(entry)){opened+=it}
        assertEquals(listOf(entry),opened);assertEquals(identity,opened.single().identity)
        assertEquals("catalog",opened.single().kind)
        assertTrue(CameoPlaybackQueue.acceptsPlay("fresh-resolved-key",identity))
        assertFalse(CameoPlaybackQueue.acceptsPlay("different-episode",identity.copy(episode=4)))
    }

    @Test fun `identical custom resource names in separate addon namespaces cannot share queue`() {
        val identity=PlaybackIdentity("custom:1","series","Show",1,1,"addon:first")
        val entry=CameoLibraryEntry.catalog(MediaItemData(identity.imdbId,identity.type,identity.title),identity)
        CameoPlaybackQueue.start("Episodes",listOf(entry)){}
        assertTrue(CameoPlaybackQueue.acceptsPlay("resolved",identity))
        assertFalse(CameoPlaybackQueue.acceptsPlay("resolved",identity.copy(catalogNamespace="addon:second")))
        assertFalse(CameoPlaybackQueue.acceptsPlay("resolved",identity.copy(catalogNamespace="")))
    }

    @Test fun `provider entries cannot adopt another file or account on matching title alone`() {
        val identity=PlaybackIdentity("tt1234567","movie","Movie")
        val entry=CameoLibraryEntry("provider:old-account:7","Movie","provider",identity=identity,
            providerId="putio",fileId="7",accountRevision="old-account")
        CameoPlaybackQueue.start("Files",listOf(entry)){}
        assertTrue(CameoPlaybackQueue.acceptsPlay(entry.id,identity))
        assertFalse(CameoPlaybackQueue.acceptsPlay("provider:new-account:7",identity))
        assertFalse(CameoPlaybackQueue.acceptsPlay("provider:old-account:8",identity))
    }
}
