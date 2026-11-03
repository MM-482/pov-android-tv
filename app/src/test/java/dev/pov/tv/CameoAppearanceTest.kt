package dev.pov.tv

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class CameoAppearanceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("cameo_launcher_icon", Context.MODE_PRIVATE).edit().clear().commit()
        CameoIcons(context).choices.forEach { choice ->
            context.packageManager.setComponentEnabledSetting(ComponentName(context.packageName, "dev.pov.tv.${choice.alias}"),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }

    @Test fun `Cameo offers five named themes while old stored themes remain readable`() {
        assertEquals(listOf("Glass", "Cinema", "Slate", "Pearl", "Aurora"), ThemeSpec.selectableThemes.map { it.displayName })
        val expected = mapOf("cinematic" to ThemeSpec.ESSENTIAL, "cinema" to ThemeSpec.PREMIERE,
            "editorial" to ThemeSpec.ATELIER, "neon" to ThemeSpec.HORIZON, "studio" to ThemeSpec.STUDIO,
            "slate" to ThemeSpec.ESSENTIAL, "pearl" to ThemeSpec.ATELIER, "aurora" to ThemeSpec.HORIZON)
        val prefs = context.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE)
        expected.forEach { (stored, theme) ->
            prefs.edit().putString("theme", stored).putString("unrelated", "retained").commit()
            assertEquals(theme, ThemePreferences(context).selectedConcept())
            assertEquals("retained", prefs.getString("unrelated", null))
        }
    }

    @Test fun `choosing launcher icon leaves theme unchanged and enables exactly one alias`() {
        ThemePreferences(context).select(ThemeSpec.ATELIER)
        val icons = CameoIcons(context)
        assertEquals("blue", icons.selected())
        for (choice in icons.choices) {
            assertTrue(icons.select(choice.id))
            assertEquals(choice.id, CameoIcons(context).selected())
            assertEquals(ThemeSpec.ATELIER, ThemePreferences(context).selectedConcept())
            assertEquals(1, icons.choices.count {
                context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, "dev.pov.tv.${it.alias}")) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            })
        }
    }

    @Test fun `theme changes never change the selected launcher icon`() {
        val icons = CameoIcons(context)
        assertTrue(icons.select("mint"))
        ThemeSpec.selectableThemes.forEach {
            ThemePreferences(context).select(it)
            assertEquals("mint", CameoIcons(context).selected())
        }
    }
}
