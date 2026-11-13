package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackIdentityNamespaceTest {
    @Test fun `addon namespace survives resume serialization`() {
        val identity = PlaybackIdentity("custom:42", "series", "Show", 2, 4, "catalog-owner")
        assertEquals(identity, PlaybackIdentity.fromJson(identity.toJson()))
    }
    @Test fun `old native catalog history retains empty namespace`() {
        val identity = PlaybackIdentity.fromJson(JSONObject("""{"imdbId":"tt1234567","type":"movie","title":"Film"}"""))
        assertEquals("", identity.catalogNamespace)
        assertEquals("tt1234567", identity.imdbId)
    }
    @Test fun `different catalog owners do not share playback identity`() {
        val identity = PlaybackIdentity("custom:42", "movie", "Film", catalogNamespace = "owner-one")
        assertNotEquals(identity, identity.copy(catalogNamespace = "owner-two"))
        assertEquals("", PlaybackIdentity.fromJson(identity.toJson().put("catalogNamespace", JSONObject.NULL)).catalogNamespace)
    }
}
