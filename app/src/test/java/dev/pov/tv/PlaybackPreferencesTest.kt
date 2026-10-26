package dev.pov.tv

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("pov_playback_preferences", Context.MODE_PRIVATE)

    @Test fun `fresh installs use Media3 and persisted choices survive reopening without account secrets`() {
        preferences.edit().clear().commit()
        val first = PlaybackPreferences(context)
        assertEquals(PlaybackEngine.MEDIA3, first.selectedEngine)
        first.selectedEngine = PlaybackEngine.VLC
        first.providerId = "realdebrid"
        first.subtitleLanguage = "  PT-BR  "
        val reopened = PlaybackPreferences(context)
        assertEquals(PlaybackEngine.VLC, reopened.selectedEngine)
        assertEquals("realdebrid", reopened.providerId)
        assertEquals("pt-br", reopened.subtitleLanguage)
        assertEquals(setOf("engine", "provider", "subtitle_language"), preferences.all.keys)
        assertTrue(preferences.all.keys.none { it.contains("token") || it.contains("password") || it.contains("api_key") })
    }

    @Test fun `invalid provider and subtitle input cannot replace valid saved preferences or retain private input`() {
        preferences.edit().clear().commit()
        val first = PlaybackPreferences(context)
        first.providerId = "torbox"; first.subtitleLanguage = "es"
        val privateInput = "https://example.invalid/?api_key=private-test-value"
        expectInvalid { first.providerId = privateInput }
        expectInvalid { first.subtitleLanguage = privateInput }
        expectInvalid { first.subtitleLanguage = "en\r\nAuthorization: private-test-value" }
        val reopened = PlaybackPreferences(context)
        assertEquals("torbox", reopened.providerId)
        assertEquals("es", reopened.subtitleLanguage)
        assertFalse(preferences.all.values.any { it.toString().contains("private-test-value") })
    }

    @Test fun `unknown persisted engine and provider values safely reopen with usable TV defaults`() {
        preferences.edit().clear().putString("engine", "REMOVED_ENGINE").putString("provider", "removed-service")
            .putString("subtitle_language", "  ").commit()
        val reopened = PlaybackPreferences(context)
        assertEquals(PlaybackEngine.MEDIA3, reopened.selectedEngine)
        assertEquals("torbox", reopened.providerId)
        assertEquals("en", reopened.subtitleLanguage)
    }

    @Test fun `wrong typed restored preferences fall back instead of crashing playback settings`() {
        preferences.edit().clear().putInt("engine", 7).putBoolean("provider", true).putInt("subtitle_language", 12).commit()
        val reopened = PlaybackPreferences(context)
        assertEquals(PlaybackEngine.MEDIA3, reopened.selectedEngine)
        assertEquals("torbox", reopened.providerId)
        assertEquals("en", reopened.subtitleLanguage)
    }

    @Test fun `corrupt persisted language cannot become a provider query or player language tag`() {
        preferences.edit().clear().putString("subtitle_language", "https://example.invalid/?token=private-test-value").commit()
        assertEquals("en", PlaybackPreferences(context).subtitleLanguage)
    }

    private fun expectInvalid(action: () -> Unit) {
        try { action(); fail("Invalid playback preference should be rejected") } catch (_: IllegalArgumentException) { }
    }
}
