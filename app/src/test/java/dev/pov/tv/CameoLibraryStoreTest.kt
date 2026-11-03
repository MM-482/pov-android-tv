package dev.pov.tv

import dev.pov.tv.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameoLibraryStoreTest {
    private lateinit var store: CameoLibraryStore
    @Before fun setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("cameo_library_v1", 0).edit().clear().commit()
        store = CameoLibraryStore(RuntimeEnvironment.getApplication())
    }
    private fun episode(addon: String, source: String, number: Int = 2): SavedMedia {
        val media = MediaItemData("custom:show", "series", "Fixture show", addonId = addon, catalogId = "catalog-a")
        return SavedMedia.catalog(media, PlaybackIdentity("custom:show", "series", "Fixture show", 1, number, source, addon))
    }
    @Test fun exactAddonAndEpisodeIdentitySurvivesPersistence() {
        val item = episode("addon-one", "video:custom:episode-2")
        val playlist = store.createPlaylist("Weekend")
        store.addToPlaylist(playlist.id, item)
        val reopened = CameoLibraryStore(RuntimeEnvironment.getApplication()).playlist(playlist.id).items.single().item
        assertEquals(item.canonicalKey, reopened.canonicalKey)
        assertEquals("video:custom:episode-2", reopened.identity?.sourceId)
        assertEquals("addon-one", reopened.media?.addonId)
        assertEquals("catalog-a", reopened.media?.catalogId)
    }
    @Test fun sameTitleFromDifferentAddonAndDifferentEpisodeRemainsDistinct() {
        val playlist = store.createPlaylist("Choices")
        listOf(episode("a", "ep-2"), episode("b", "ep-2"), episode("a", "ep-3", 3)).forEach { store.addToPlaylist(playlist.id, it) }
        store.addToPlaylist(playlist.id, episode("a", "ep-2"))
        assertEquals(3, store.playlist(playlist.id).items.size)
    }
    @Test fun repeatedPositiveLibraryActionsNeverRemoveOrDuplicateSavedTitles() {
        val first = episode("a", "episode-2")
        val anotherSource = episode("b", "episode-2")
        assertTrue(store.addToWatchlist(first))
        assertFalse(store.addToWatchlist(first))
        assertTrue(store.addToWatchlist(anotherSource))
        assertFalse(store.addToWatchlist(first))
        val reopened = CameoLibraryStore(RuntimeEnvironment.getApplication())
        assertEquals(listOf(anotherSource.canonicalKey, first.canonicalKey), reopened.watchlist().map { it.canonicalKey })
        assertTrue(reopened.isSaved(first))
        assertTrue(reopened.isSaved(anotherSource))
    }
    @Test fun exactAccountFileAndEditionRemainDistinct() {
        fun file(account: String, edition: String): SavedMedia {
            val descriptor = CameoServiceItem("video:one", account, "Same filename", mediaSourceId = edition)
            return SavedMedia("Same filename", "service", connectionId = account, serviceItem = descriptor.toJson().toString())
        }
        val playlist = store.createPlaylist("Versions")
        listOf(file("account-1", "edition-a"), file("account-2", "edition-a"), file("account-1", "edition-b")).forEach { store.addToPlaylist(playlist.id, it) }
        assertEquals(3, store.playlist(playlist.id).items.size)
    }
    @Test fun movingEntriesPreservesTheirStableIdsAndExactOrder() {
        val list = store.createPlaylist("Ordered")
        val a = episode("a", "one", 1); val b = episode("a", "two", 2); val c = episode("a", "three", 3)
        listOf(a, b, c).forEach { store.addToPlaylist(list.id, it) }
        val initial = store.playlist(list.id).items
        store.moveEntry(list.id, initial[2].id, -1)
        assertEquals(listOf(initial[0].id, initial[2].id, initial[1].id), store.playlist(list.id).items.map { it.id })
        assertEquals(c.canonicalKey, store.playlist(list.id).items[1].item.canonicalKey)
    }
    @Test fun continueWatchingCanResolveAServiceFileWithoutWatchlistingIt() {
        val descriptor = CameoServiceItem("video:file", "account", "Fixture.mkv", parentId = "folder")
        val item = SavedMedia("Fixture.mkv", "service", connectionId = "account", serviceItem = descriptor.toJson().toString())
        store.remember(item)
        assertTrue(store.watchlist().isEmpty())
        assertEquals("folder", org.json.JSONObject(store.findByHistoryKey(item.historyKey)!!.serviceItem).getString("parentId"))
    }
    @Test fun transientCredentialsCannotBePersistedAsServiceEntries() {
        val descriptor = CameoServiceItem("video:file", "account", "Fixture.mkv").toJson().put("url", "https://example.test/file?token=private")
        val item = SavedMedia("Fixture.mkv", "service", connectionId = "account", serviceItem = descriptor.toString())
        assertThrows(IllegalArgumentException::class.java) { store.remember(item) }
        assertNull(store.findByHistoryKey(item.historyKey))
    }
    @Test fun damagedLibraryIsPreservedRatherThanSilentlyReplaced() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("cameo_library_v1", 0)
        preferences.edit().putString("data", "not valid JSON").commit()
        assertThrows(IllegalStateException::class.java) { store.createPlaylist("New") }
        assertEquals("not valid JSON", preferences.getString("data", ""))
    }
}
