package dev.pov.tv

import dev.pov.tv.data.EpisodeData
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameoLibraryStoreTest {
    private class Memory(var value: String = "{}") : CameoLibraryStorage {
        var writes = 0; var refuse = false
        override fun read() = value
        override fun write(value: String) { if(refuse) throw IllegalStateException("Storage unavailable"); this.value=value; writes++ }
    }
    private val movie = MediaItemData("tt1234567", "movie", "Movie")
    private fun entry(name: String) = CameoLibraryEntry("local:$name", name, "local", "content://documents/video/$name")
    private inline fun <reified T : Throwable> reject(work: () -> Unit) {
        try { work() } catch(error:Throwable) { if(error is T)return else throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun `batch import deduplicates exact document URI without playback or watchlist changes`() {
        CameoPlaybackQueue.clear()
        val memory=Memory();val store=CameoLibraryStore(memory)
        val imported=store.addLocalFiles(listOf("content://documents/video/1" to "One", "content://documents/video/2" to "Two", "content://documents/video/1" to "One again"))
        assertEquals(2,imported.size);assertEquals(2,store.local().size);assertEquals(1,memory.writes)
        assertTrue(store.watchlist().isEmpty());assertTrue(store.playlists().isEmpty());assertNull(CameoPlaybackQueue.current())
        assertEquals(store.local(),CameoLibraryStore(memory).local())
    }

    @Test fun `invalid bulk URI prevents the whole import transaction`() {
        val memory=Memory();val store=CameoLibraryStore(memory)
        reject<IllegalArgumentException> {store.addLocalFiles(listOf("content://documents/video/1" to "One", "file:///private/video" to "Two"))}
        assertEquals(0,memory.writes);assertTrue(store.local().isEmpty())
    }

    @Test fun `single and batch imports use same stable URI identity`() {
        val memory=Memory();val store=CameoLibraryStore(memory)
        val first=store.addLocal("content://documents/video/%E2%98%83", "One")
        val second=store.addLocalFiles(listOf("content://documents/video/%E2%98%83" to "Renamed")).single()
        assertEquals(first.id,second.id);assertEquals(listOf(second),store.local())
    }

    @Test fun `removing imported library entry preserves the file reference in playlists`() {
        val store=CameoLibraryStore(Memory());val entry=store.addLocal("content://documents/video/1", "One")
        val id=store.createPlaylist("Favorites");store.addToPlaylist(id,entry);store.removeLocal(entry.id)
        assertTrue(store.local().isEmpty());assertEquals(listOf(entry),store.playlists().single().entries)
    }

    @Test fun `local watchlist requires no external account and separates media type`() {
        val store=CameoLibraryStore(Memory());val series=movie.copy(type="series")
        store.setWatchlist(movie,true);store.setWatchlist(movie,true);store.setWatchlist(series,true)
        assertEquals(2,store.watchlist().size);assertTrue(store.isWatchlisted(movie));assertTrue(store.isWatchlisted(series))
        store.setWatchlist(movie,false)
        assertFalse(store.isWatchlisted(movie));assertTrue(store.isWatchlisted(series))
    }

    @Test fun `long imported filenames do not block other videos or change document identity`() {
        val store=CameoLibraryStore(Memory());val address="content://documents/video/long-name"
        val imported=store.addLocalFiles(listOf(address to "x".repeat(240),"content://documents/video/second" to "Second.mkv"))
        assertEquals(2,imported.size);assertEquals(address,imported.first().uri);assertEquals(240,imported.first().name.length)
        assertEquals(imported.first().id,store.addLocal(address,"Renamed display title").id)
        assertEquals(2,store.local().size)
    }

    @Test fun `catalog playlist identities preserve exact episode and addon namespace`() {
        val series=movie.copy(imdbId="custom:1",type="series")
        val first=PlaybackIdentity("custom:1","series","Show",2,1,"addon:first")
        val second=first.copy(catalogNamespace="addon:second")
        val a=CameoLibraryEntry.catalog(series,first);val b=CameoLibraryEntry.catalog(series,second)
        assertNotEquals("Different add-on namespaces must not collide",a.id,b.id)
        val store=CameoLibraryStore(Memory());val playlist=store.createPlaylist("Episodes")
        store.addToPlaylist(playlist,a);store.addToPlaylist(playlist,b);store.addToPlaylist(playlist,CameoLibraryEntry.catalog(series,first.copy(episode=2)))
        assertEquals(listOf(first,second,first.copy(episode=2)),store.playlists().single().entries.map{it.identity})
    }

    @Test fun `saved catalog metadata survives restart including episode detail fields`() {
        val memory=Memory();val store=CameoLibraryStore(memory)
        val complete=movie.copy(type="series",genres=listOf("Drama","Sci-Fi"),runtime="42m",rating="8.5",
            episodes=listOf(EpisodeData(2,1,"Episode","2026-01-01","https://public.example/episode.jpg")))
        store.setWatchlist(complete,true)
        assertEquals(complete,CameoLibraryStore(memory).watchlist().single().media)
    }

    @Test fun `playlist operations preserve saved order and leave imported files alone`() {
        val memory=Memory();val store=CameoLibraryStore(memory);val id=store.createPlaylist("  Queue  ")
        listOf(entry("One"),entry("Two"),entry("Three")).forEach {store.addToPlaylist(id,it)}
        store.moveInPlaylist(id,0,2);assertEquals(listOf("Two","Three","One"),store.playlists().single().entries.map{it.name})
        store.moveInPlaylist(id,2,0);store.removeFromPlaylist(id,1);store.renamePlaylist(id,"New name")
        assertEquals(listOf("One","Three"),CameoLibraryStore(memory).playlists().single().entries.map{it.name})
        assertEquals("New name",store.playlists().single().name)
        store.deletePlaylist(id);assertTrue(store.playlists().isEmpty())
    }

    @Test fun `playlist validation cannot mutate state on missing ids invalid order or invalid names`() {
        val memory=Memory();val store=CameoLibraryStore(memory);val id=store.createPlaylist("Queue")
        store.addToPlaylist(id,entry("One"));val previous=memory.value
        reject<IllegalArgumentException>{store.moveInPlaylist(id,0,1)}
        reject<IllegalArgumentException>{store.removeFromPlaylist(id,-1)}
        reject<IllegalArgumentException>{store.renamePlaylist(id," ")}
        reject<IllegalArgumentException>{store.addToPlaylist("missing",entry("Two"))}
        reject<IllegalArgumentException>{store.createPlaylist("x".repeat(161))}
        assertEquals(previous,memory.value)
    }

    @Test fun `damaged persisted library is reported without overwriting user data`() {
        val memory=Memory("broken private data");val store=CameoLibraryStore(memory)
        reject<IllegalStateException>{store.watchlist()}
        reject<IllegalStateException>{store.createPlaylist("Queue")}
        assertEquals("broken private data",memory.value);assertEquals(0,memory.writes)
    }

    @Test fun `failed storage commit leaves previous library retrievable`() {
        val memory=Memory();val store=CameoLibraryStore(memory);store.setWatchlist(movie,true)
        val previous=memory.value;memory.refuse=true
        reject<IllegalStateException>{store.setWatchlist(movie,false)}
        assertEquals(previous,memory.value);assertEquals(movie,store.watchlist().single().media)
    }
}
