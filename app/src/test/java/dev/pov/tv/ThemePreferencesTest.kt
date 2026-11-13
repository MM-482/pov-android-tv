package dev.pov.tv

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThemePreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearAppearance() {
        context.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `new TV installs use Cameo Glass`() {
        assertEquals(ThemeSpec.CAMEO_GLASS, ThemePreferences(context).selected())
    }

    @Test fun `every theme choice survives creating a new preference owner`() {
        for (theme in ThemeSpec.selectableThemes) {
            ThemePreferences(context).select(theme)
            assertEquals(theme, ThemePreferences(context).selected())
        }
    }

    @Test fun `unknown stored theme safely falls back without changing other preferences`() {
        val saved = context.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE)
        saved.edit().putString("theme", "removed-future-theme").putString("unrelated", "keep").commit()
        assertEquals(ThemeSpec.CAMEO_GLASS, ThemePreferences(context).selected())
        assertEquals("cameo-glass", saved.getString("theme", null))
        assertEquals("keep", saved.getString("unrelated", null))
    }

    @Test fun `selecting a legacy definition can never restore a removed TV choice`() {
        ThemeSpec.entries.filterNot { it.isCameoTv }.forEach { legacy ->
            ThemePreferences(context).select(legacy)
            val expected = ThemeSpec.conceptFromId(legacy.id)
            assertEquals(expected, ThemePreferences(context).selected())
            assertEquals(expected.id, context.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE).getString("theme", null))
        }
    }
}
