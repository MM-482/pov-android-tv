package dev.pov.tv

import android.content.Context
import dev.pov.tv.data.PlaybackIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Native appearance migration must not consume playback history or write account credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConceptThemeMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `legacy concept migration and new theme selection survive reopen without changing playback or accounts`() {
        val appearance = context.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE)
        appearance.edit().clear().putString("theme", "neon").putString("unrelated", "keep").commit()
        val playback = PlaybackPreferences(context).apply { selectedEngine = PlaybackEngine.VLC; providerId = "realdebrid"; subtitleLanguage = "ja" }
        val identity = PlaybackIdentity("tt0903747", "series", "Series Fixture", 3, 7)
        val history = PlaybackStore(context)
        history.save("owned-resume-key", identity.title, identity, 120_000, 600_000)
        history.queue(identity, 20.0, "pause")
        val accounts = context.getSharedPreferences("pov_accounts_encrypted", Context.MODE_PRIVATE)
        // An inert opaque fixture marker; no SecureStore, token, Keystore or account service is used.
        accounts.edit().clear().putString("owned_fixture", "unchanged-opaque-fixture").commit()
        val beforeAccount = accounts.all.toMap()
        val beforePlayback = context.getSharedPreferences("pov_playback_preferences", Context.MODE_PRIVATE).all.toMap()
        val beforeHistory = context.getSharedPreferences("playback", Context.MODE_PRIVATE).all.toMap()
        assertEquals(ThemeSpec.HORIZON, ThemePreferences(context).selectedConcept())
        assertEquals("horizon", appearance.getString("theme", null))
        ThemeSpec.selectableThemes.forEach { theme ->
            ThemePreferences(context).select(theme)
            assertEquals(theme, ThemePreferences(context).selectedConcept())
        }
        assertEquals(beforeAccount, accounts.all)
        assertEquals(beforePlayback, context.getSharedPreferences("pov_playback_preferences", Context.MODE_PRIVATE).all)
        assertEquals(beforeHistory, context.getSharedPreferences("playback", Context.MODE_PRIVATE).all)
        assertEquals("keep", appearance.getString("unrelated", null))
        assertEquals(PlaybackEngine.VLC, playback.selectedEngine)
    }

    @Test fun `removed concept preference falls back once to Glass while retaining unrelated appearance settings`() {
        val appearance = context.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE)
        appearance.edit().clear().putString("theme", "removed-future-theme").putString("unrelated", "keep").commit()
        assertEquals(ThemeSpec.GLASS, ThemePreferences(context).selectedConcept())
        assertEquals("glass", appearance.getString("theme", null))
        assertEquals(ThemeSpec.GLASS, ThemePreferences(context).selectedConcept())
        assertEquals("keep", appearance.getString("unrelated", null))
    }
}
