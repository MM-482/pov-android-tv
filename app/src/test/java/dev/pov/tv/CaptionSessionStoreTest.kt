package dev.pov.tv

import android.net.Uri
import dev.pov.tv.data.PlaybackIdentity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class CaptionSessionStoreTest {
    private class Metadata : CaptionSessionMetadata {
        var value = "{}"
        var rejectWrites = false
        var writeCalls = 0
        override fun read() = value
        override fun write(value: String) { writeCalls++; check(!rejectWrites) { "Fixture write unavailable" }; this.value = value }
    }
    private data class Fixture(val store: CaptionSessionStore, val metadata: Metadata, val cache: File, val files: File, val subtitle: File)
    private val identity = PlaybackIdentity("tt1234567", "series", "Series", 1, 2)
    private val srt = "1\n00:00:01,000 --> 00:00:03,500\nCaption.\n"
    private fun fixture(): Fixture {
        val app = RuntimeEnvironment.getApplication()
        val id = UUID.randomUUID().toString()
        val cache = File(app.cacheDir, "caption-fixture-$id").apply { mkdirs() }
        val files = File(app.filesDir, "caption-fixture-$id").apply { mkdirs() }
        val subtitle = File(cache, "selected.srt").apply { writeText(srt) }
        val metadata = Metadata()
        return Fixture(CaptionSessionStore(cache, files, metadata), metadata, cache, files, subtitle)
    }

    @Test fun `retained document survives cache removal source URL change and reopening without extra player preparation`() {
        val f = fixture()
        assertTrue(f.store.remember(identity, "https://media.example/video?token=fixture-private", Uri.fromFile(f.subtitle), "English", "en", "application/x-subrip", 1_250_000))
        f.subtitle.delete()
        val reopened = CaptionSessionStore(f.cache, f.files, f.metadata)
        val result = reopened.restore(identity.copy(title = "Updated title"), "different-provider-file")
        assertEquals(1_250_000L, result?.offsetMicros)
        assertEquals(srt, File(requireNotNull(result?.configuration?.uri?.path)).readText())
        assertFalse(f.metadata.value.contains("fixture-private"))
        assertFalse(f.metadata.value.contains("https://"))
        assertFalse(f.metadata.value.contains(identity.imdbId))
    }

    @Test fun `exact next episode and other namespace never inherit the previous caption`() {
        val f = fixture()
        f.store.remember(identity, "", Uri.fromFile(f.subtitle), "English", "en", "application/x-subrip")
        assertNull(f.store.restore(identity.copy(episode = 3)))
        assertNull(f.store.restore(identity.copy(season = 2)))
        assertNull(f.store.restore(identity.copy(catalogNamespace = "other-addon")))
        assertNotNull(f.store.restore(identity))
    }

    @Test fun `caption off and embedded timing persist only for the same exact identity`() {
        val f = fixture()
        f.store.updateOffset(identity, "", -500_000)
        f.store.setEnabled(identity, "", false)
        val selected = f.store.restore(identity)
        assertEquals(-500_000L, selected?.offsetMicros)
        assertFalse(requireNotNull(selected).enabled)
        assertNull(selected.configuration)
        assertNull(f.store.restore(identity.copy(episode = 3)))
    }

    @Test fun `unknown title fallback accepts stable opaque key but never a resolved URL`() {
        assertNull(CaptionSessionStore.key(null, "https://media.example/video?token=fixture-private"))
        assertEquals(CaptionSessionStore.key(null, "server:file-123"), CaptionSessionStore.key(null, "server:file-123"))
        assertNotEquals(CaptionSessionStore.key(null, "server:file-123"), CaptionSessionStore.key(null, "server:file-124"))
        assertNotEquals(CaptionSessionStore.key(identity.copy(catalogNamespace = "ab:c"), ""),
            CaptionSessionStore.key(identity.copy(catalogNamespace = "ab", imdbId = "c:${identity.imdbId}"), ""))
    }

    @Test fun `missing retained file is ignored rather than reusing an unrelated cache document`() {
        val f = fixture()
        f.store.remember(identity, "", Uri.fromFile(f.subtitle), "English", "en", "application/x-subrip")
        val retained = File(requireNotNull(f.store.restore(identity)?.configuration?.uri?.path))
        assertTrue(retained.delete())
        assertNull(f.store.restore(identity))
    }

    @Test fun `non-owned paths and forged stored filenames are rejected without touching outside file`() {
        val f = fixture()
        val outside = File(f.cache.parentFile, "outside-${UUID.randomUUID()}.srt").apply { writeText(srt) }
        try { f.store.remember(identity, "", Uri.fromFile(outside), "English", "en", "application/x-subrip"); fail("Outside file accepted") }
        catch (_: IllegalArgumentException) { }
        f.metadata.value = JSONObject().put(requireNotNull(CaptionSessionStore.key(identity, "")),
            JSONObject().put("file", "../${outside.name}").put("mime", "application/x-subrip")).toString()
        assertNull(f.store.restore(identity))
        assertEquals(srt, outside.readText())
    }

    @Test fun `failed encrypted metadata write preserves original caption and removes the new unused copy`() {
        val f = fixture()
        f.store.remember(identity, "", Uri.fromFile(f.subtitle), "Original", "en", "application/x-subrip")
        val original = f.metadata.value
        f.metadata.rejectWrites = true
        try { f.store.remember(identity, "", Uri.fromFile(f.subtitle), "Replacement", "es", "application/x-subrip"); fail("Metadata write ignored") }
        catch (_: IllegalStateException) { }
        assertEquals(original, f.metadata.value)
        assertEquals(1, File(f.files, "cameo-caption-selections").listFiles()?.size)
        assertEquals("Original", f.store.restore(identity)?.configuration?.label)
    }

    @Test fun `malformed or wrong-shaped caption index cannot be overwritten by restore or edits`() {
        val f = fixture()
        f.store.remember(identity, "", Uri.fromFile(f.subtitle), "Original", "en", "application/x-subrip")
        val retained = File(requireNotNull(f.store.restore(identity)?.configuration?.uri?.path))
        val originalBytes = retained.readBytes()
        val directory = retained.parentFile!!
        val originalFiles = directory.list()!!.toSet()
        val validKey = requireNotNull(CaptionSessionStore.key(identity, ""))
        for (malformed in listOf("{", "[]", "{} fixture-trailing-data", "{\"unexpected-entry\":{}}", JSONObject().put(validKey, "fixture-invalid-entry").toString())) {
            f.metadata.value = malformed
            f.metadata.writeCalls = 0
            val operations = listOf<() -> Unit>(
                { f.store.restore(identity); Unit },
                { f.store.updateOffset(identity, "", 500_000); Unit },
                { f.store.setEnabled(identity, "", false); Unit },
                { f.store.remember(identity, "", Uri.fromFile(f.subtitle), "Replacement", "es", "application/x-subrip"); Unit },
            )
            operations.forEach { operation ->
                try { operation(); fail("Unreadable caption index was accepted") }
                catch (failure: IllegalStateException) {
                    assertTrue(failure.message.orEmpty().contains("preserved"))
                    assertFalse(failure.message.orEmpty().contains("fixture-invalid-entry"))
                }
                assertEquals(malformed, f.metadata.value)
                assertEquals(0, f.metadata.writeCalls)
                assertEquals(originalFiles, directory.list()!!.toSet())
                assertArrayEquals(originalBytes, retained.readBytes())
            }
        }
    }
}
