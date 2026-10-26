package dev.pov.tv

import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.ExecutorService

/** Real Main dispatch/global reveal and asynchronously arriving source cards, including the top action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SourceScreenNavigationTest {
    @Test fun `top control enters first stream and all seventy streams traverse without bottom jumps`() {
        for (theme in ThemeSpec.selectableThemes) fixture(theme).use { screen ->
            screen.render()
            traverse(screen, visitMini = false)
        }
    }

    @Test fun `synthetic visible mini router preserves source traversal and returns from the bottom edge`() {
        // Routing coverage only: no player, media session, account, or video surface is started.
        for (theme in ThemeSpec.selectableThemes) fixture(theme).use { screen ->
            screen.render()
            screen.showSyntheticMini()
            traverse(screen, visitMini = true)
        }
    }

    @Test fun `Back restores a middle stream and its next Down reaches only the next stream`() {
        fixture(ThemeSpec.GLASS).use { screen ->
            screen.render(); screen.key(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(35) { screen.key(KeyEvent.KEYCODE_DPAD_DOWN) }
            screen.assertRow(35)
            val selectedKey = screen.rows()[35].tag
            val selectedScroll = screen.scroll.scrollY
            screen.key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(screen.rows().isEmpty())
            screen.activity.onBackPressed(); screen.settle()
            screen.assertRow(35)
            assertEquals(selectedKey, screen.body.findFocus()?.tag)
            assertEquals("Back retains the exact source viewport", selectedScroll, screen.scroll.scrollY)
            screen.key(KeyEvent.KEYCODE_DPAD_DOWN); screen.assertRow(36)
            repeat(36) { screen.key(KeyEvent.KEYCODE_DPAD_UP) }
            screen.assertRow(0)
            screen.key(KeyEvent.KEYCODE_DPAD_UP)
            screen.key(KeyEvent.KEYCODE_DPAD_DOWN); screen.assertRow(0)
        }
    }

    private fun traverse(screen: Fixture, visitMini: Boolean) {
        assertEquals("fixture:source-service", screen.body.findFocus()?.tag)
        screen.key(KeyEvent.KEYCODE_DPAD_DOWN)
        for (index in 0 until 70) {
            if (index > 0) screen.key(KeyEvent.KEYCODE_DPAD_DOWN)
            screen.assertRow(index)
        }
        if (visitMini) {
            screen.key(KeyEvent.KEYCODE_DPAD_DOWN)
            screen.assertMini()
            screen.key(KeyEvent.KEYCODE_DPAD_UP)
            screen.assertRow(69)
        }
        for (index in 68 downTo 0) {
            screen.key(KeyEvent.KEYCODE_DPAD_UP)
            screen.assertRow(index)
        }
        screen.key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("fixture:source-service", screen.body.findFocus()?.tag)
        screen.key(KeyEvent.KEYCODE_DPAD_DOWN); screen.assertRow(0)
        screen.key(KeyEvent.KEYCODE_DPAD_DOWN); screen.assertRow(1)
    }

    private class Fixture(val owner: ActivityController<MainActivity>, val activity: MainActivity,
        val body: LinearLayout, val scroll: ScrollView, val host: FrameLayout, val mini: FrameLayout,
        val listener: ViewTreeObserver.OnGlobalFocusChangeListener) : AutoCloseable {
        fun render() {
            invoke(activity, "sourceChoiceAudit"); settle()
            assertEquals("Actual Sony pixel width must be exercised", 1920, activity.resources.displayMetrics.widthPixels)
            assertEquals(70, rows().size)
            assertEquals("A source card is 202 actual pixels on the 1920px TV", 202, rows().first().height)
            val list = rows().first().parent as TvChoiceList
            assertEquals("Source row gap is 18 actual pixels", 18,
                (rows().first().layoutParams as LinearLayout.LayoutParams).bottomMargin)
            assertTrue("Seventy rows exceed Android 8 FocusFinder's squared-distance overflow range", list.height > 15_000)
        }
        fun showSyntheticMini() {
            val header = body.findFocus()
            assertEquals("fixture:source-service", header?.tag)
            set(activity, "miniFocus", MiniPlayerFocusRouter(body, mini))
            mini.visibility = View.VISIBLE
            settle()
            assertTrue("Synthetic mini is a visible focus target", mini.isShown && mini.isFocusable)
            assertSame("Showing the synthetic mini must not move natural header focus", header, body.findFocus())
        }
        fun rows() = descendants(body).filterIsInstance<TvChoiceRow>().filter { (it.tag as? String)?.startsWith("source:") == true }
        fun settle() {
            layout(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160)); layout()
            shadowOf(Looper.getMainLooper()).idle(); layout()
        }
        private fun layout() {
            val scale = activity.resources.displayMetrics.widthPixels / 960f
            val width = (700 * scale).toInt()
            val height = (350 * scale).toInt()
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, height)
        }
        fun key(code: Int) {
            val focused = activity.window.decorView.findFocus()
            assertNotNull("Main's native window owns a selected target", focused)
            assertTrue("Only actual source content or the synthetic mini owns focus", focused === body.findFocus() || focused === mini)
            // Robolectric shadows Activity.currentFocus separately from the real decor focus.
            // Bridge only the verified native selection; all key events still use Main dispatch.
            shadowOf(activity).setCurrentFocus(focused)
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            shadowOf(activity).setCurrentFocus(activity.window.decorView.findFocus())
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            settle()
        }
        fun assertRow(index: Int) {
            val row = rows()[index]
            assertSame("Source $index must own focus", row, body.findFocus())
            val bounds = android.graphics.Rect(0, 0, row.width, row.height)
            body.offsetDescendantRectToMyCoords(row, bounds)
            assertTrue("Source $index top must be visible", bounds.top >= scroll.scrollY)
            assertTrue("Source $index bottom must be visible", bounds.bottom <= scroll.scrollY + scroll.height)
        }
        fun assertMini() {
            assertSame("Down at the final source reaches the synthetic mini", mini, activity.window.decorView.findFocus())
            assertFalse("The source list releases focus at its bottom edge", body.hasFocus())
        }
        override fun close() {
            activity.window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(listener)
            for (name in listOf("worker", "images", "authWorker")) (field(activity, name) as ExecutorService).shutdownNow()
            activity.finish(); shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun fixture(theme: ThemeSpec): Fixture {
        val owner = Robolectric.buildActivity(MainActivity::class.java)
        val activity = owner.get()
        activity.setTheme(R.style.AppTheme)
        activity.window.decorView // Initialize Main's native dispatch target without starting accounts/network.
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { isFillViewport = true; isSmoothScrollingEnabled = false; addView(body) }
        val mini = FrameLayout(activity).apply { visibility = View.GONE; tag = "fixture:synthetic-mini-no-playback" }
        val host = FrameLayout(activity).apply {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(mini, FrameLayout.LayoutParams(494, 290, Gravity.BOTTOM or Gravity.END))
        }
        val controller = TvFocusController(body, scroll)
        set(activity, "theme", theme); set(activity, "body", body); set(activity, "scroller", scroll)
        set(activity, "focusController", controller); set(activity, "heroBackdrop", FrameLayout(activity)); set(activity, "publicPreview", true)
        activity.setContentView(host)
        val listener = field(activity, "contentFocusChanged") as ViewTreeObserver.OnGlobalFocusChangeListener
        activity.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
        // Attach Main's real decor without invoking Main.onCreate/accounts/catalogs. This is the
        // same manual lifecycle used by ContinueWatchingMenuTest; no second Activity handles keys.
        owner.visible().windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        return Fixture(owner, activity, body, scroll, host, mini, listener)
    }
    companion object {
        private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
        private fun set(activity: MainActivity, name: String, value: Any) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
        private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    }
}
