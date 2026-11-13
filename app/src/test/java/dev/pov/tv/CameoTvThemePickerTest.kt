package dev.pov.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutorService

/** Main's actual renderer dispatch/picker, with no Activity startup or account/network calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CameoTvThemePickerTest {
    @Test fun `every removed TV theme opens a native Cameo shell with Cameo branding`() {
        listOf("glass", "essential", "premiere", "atelier", "horizon", "studio").forEach { savedId ->
            withScreen(savedId) { activity, root ->
                val expected = ThemeSpec.conceptFromId(savedId)
                assertEquals("cameo-tv:root:${expected.id}", root.tag)
                val labels = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue("Migrated shell must render the Cameo wordmark", "CAMEO" in labels)
                assertFalse("Removed POV branding must not be active", labels.any { it.equals("POV", ignoreCase = true) })
                assertEquals(expected.id, activity.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE).getString("theme", null))
            }
        }
    }

    @Test fun `actual TV Appearance screen offers exactly five Cameo cards and no legacy previews`() {
        withScreen("studio") { activity, _ ->
            invoke(activity, "appearance")
            val root = field(activity, "shellRoot") as FrameLayout
            layout(root)
            val cards = descendants(root).filter { (it.tag as? String)?.startsWith("theme:") == true }
            assertEquals(5, cards.size)
            assertEquals(ThemeSpec.cameoTvThemes.map { "theme:${it.id}" }, cards.map { it.tag })
            assertTrue(cards.all { it.isClickable && it.isFocusable })
            assertTrue(cards.all { it.contentDescription.toString().startsWith("Apply Cameo ") })
            val labels = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.contains("Choose one of the five Cameo TV designs."))
            assertFalse(labels.any { it.contains("previous TV themes") })
        }
    }

    private fun withScreen(savedId: String, test: (MainActivity, FrameLayout) -> Unit) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        shadowOf(requireNotNull(activity.getSystemService(UiModeManager::class.java))).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        activity.setTheme(R.style.AppTheme)
        val appearance = activity.getSharedPreferences("pov_appearance", Context.MODE_PRIVATE)
        appearance.edit().clear().putString("theme", savedId).commit()
        val preferences = ThemePreferences(activity)
        set(activity, "themePreferences", preferences)
        set(activity, "theme", preferences.selectedCameoTv())
        set(activity, "publicPreview", true)
        set(activity, "playbackPreferences", PlaybackPreferences(activity))
        try {
            invoke(activity, "shell")
            val root = field(activity, "shellRoot") as FrameLayout
            layout(root)
            test(activity, root)
        } finally {
            listOf("worker", "images", "authWorker").forEach { (field(activity, it) as ExecutorService).shutdownNow() }
        }
    }
    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 960, 540)
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun set(activity: MainActivity, name: String, value: Any) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
}
