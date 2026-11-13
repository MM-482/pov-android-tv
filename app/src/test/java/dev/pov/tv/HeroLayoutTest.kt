package dev.pov.tv

import android.app.UiModeManager
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.pov.tv.data.MediaItemData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService

/** Exercises the actual native view tree without starting account or network work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-mdpi")
class HeroLayoutTest {
    @Test fun `long feature titles cannot shrink or hide the primary action in any hero theme`() {
        for (theme in ThemeSpec.entries.filter { it != ThemeSpec.DASHBOARD }) {
            val (hero, activity) = render(theme, "An Extremely Long Movie Title That Must Never Hide the Main Play Action")
            val action = descendants(hero).filterIsInstance<TextView>().single { it.text.toString().contains("Explore") }
            val scale = activity.resources.displayMetrics.widthPixels / 960f
            assertEquals("${theme.displayName} action height", (38 * scale).toInt(), action.height)
            val top = topWithin(action, hero)
            assertTrue("${theme.displayName} action inside hero", top >= 0 && top + action.height <= hero.height)
            val synopsis = descendants(hero).filterIsInstance<TextView>().single { it.text.toString().startsWith("A long synopsis") }
            assertTrue("${theme.displayName} synopsis above action", topWithin(synopsis, hero) + synopsis.height <= top)
            shutdown(activity)
        }
    }

    @Test fun `short feature title leaves the action fully visible in the selected cinematic design`() {
        val (hero, activity) = render(ThemeSpec.CINEMATIC, "AFTERLIGHT")
        val action = descendants(hero).filterIsInstance<TextView>().single { it.text.toString().contains("Explore") }
        assertEquals((38 * activity.resources.displayMetrics.widthPixels / 960f).toInt(), action.height)
        assertTrue(action.isFocusable)
        assertTrue(action.isClickable)
        shutdown(activity)
    }

    private fun render(theme: ThemeSpec, title: String): Pair<ViewGroup, MainActivity> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        shadowOf(requireNotNull(activity.getSystemService(UiModeManager::class.java))).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        activity.setTheme(R.style.AppTheme)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        set(activity, "theme", theme); set(activity, "body", body)
        set(activity, "heroBackdrop", FrameLayout(activity).apply { layoutParams = FrameLayout.LayoutParams(-1, 1) })
        set(activity, "backdropArt", ImageView(activity)); set(activity, "history", PlaybackStore(activity))
        val item = MediaItemData("tt1234567", "movie", title, "2026", description = "A long synopsis " + "that must fit before the action. ".repeat(40), runtime = "1h 48m")
        MainActivity::class.java.getDeclaredMethod("hero", MediaItemData::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, item, true)
        val width = ((960 - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2) * activity.resources.displayMetrics.widthPixels / 960f).toInt()
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        body.layout(0, 0, width, body.measuredHeight)
        return body.getChildAt(0) as ViewGroup to activity
    }

    private fun topWithin(view: View, parent: View): Int {
        var top = view.top; var current = view.parent
        while (current is View && current !== parent) { top += current.top; current = current.parent }
        return top
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun set(activity: MainActivity, name: String, value: Any) { MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value) }
    private fun shutdown(activity: MainActivity) { for (name in listOf("worker", "images")) (MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as ExecutorService).shutdownNow() }
}
