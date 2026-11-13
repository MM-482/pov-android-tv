package dev.pov.tv

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowInstrumentation
import java.time.Duration
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h640dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MobileThemePickerRendererTest {
    @Test fun `compact phone has two natural portrait choices per row`() { fixture(320, 640) }
    @Test @Config(qualifiers = "w416dp-h657dp-port-mdpi") fun `cover phone keeps two compact choices and one line names`() { fixture(416, 657) }
    @Test @Config(qualifiers = "w657dp-h416dp-land-mdpi") fun `landscape phone fits more compact choices without stretching portrait art`() { fixture(657, 416) }
    @Test @Config(qualifiers = "w816dp-h616dp-land-mdpi") fun `unfolded phone uses multiple natural portrait columns`() { fixture(816, 616) }

    @Test @Config(qualifiers = "w657dp-h416dp-land-mdpi")
    fun `actual mobile shell keeps landscape picker names above bottom navigation without scrolling`() {
        MobileCameoTheme.entries.forEach { theme ->
            val controller = Robolectric.buildActivity(Activity::class.java)
            val activity = controller.get(); activity.setTheme(R.style.AppTheme)
            shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
            controller.setup().visible()
            val main = Robolectric.buildActivity(MainActivity::class.java).get()
            main.setTheme(R.style.AppTheme)
            try {
                val shell = MobileCameoRenderer(activity, theme).shell(emptyList()) { "settings" }
                for ((name, value) in listOf("theme" to theme.foundation, "shellRoot" to shell.root, "scroller" to shell.scroller)) {
                    MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(main, value)
                }
                MainActivity::class.java.getDeclaredMethod("mobilePageInsets", java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(main, false)
                shell.body.setPadding(16, 16, 16, 16)
                shell.body.addView(TextView(activity).apply { text = "Appearance"; textSize = 29f }, LinearLayout.LayoutParams(-1, 34))
                shell.body.addView(TextView(activity).apply { text = "Choose a mobile appearance."; textSize = 12f }, LinearLayout.LayoutParams(-1, 36))
                MobileThemePickerRenderer(activity).render(shell.body, theme) {}
                // Reserve representative status/gesture space around the actual app canvas.
                val window = FrameLayout(activity).apply { setPadding(0, 24, 0, 16); addView(shell.root, FrameLayout.LayoutParams(-1, -1)) }
                activity.setContentView(window)
                repeat(2) {
                    window.measure(View.MeasureSpec.makeMeasureSpec(657, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(416, View.MeasureSpec.EXACTLY))
                    window.layout(0, 0, 657, 416); shadowOf(Looper.getMainLooper()).idle()
                }
                assertEquals(0, shell.scroller.scrollY)
                assertEquals(View.GONE, shell.root.findViewWithTag<View>("mobile:categories").visibility)
                val navTop = bounds(window, shell.root.findViewWithTag<View>("mobile:bottom-nav")).top
                MobileCameoTheme.entries.forEach { choice ->
                    val name = shell.body.findViewWithTag<TextView>("mobile-theme-name:${choice.name}")
                    assertEquals(1, name.lineCount)
                    assertTrue("${theme.title} keeps ${choice.title} caption fully above navigation", bounds(window, name).bottom <= navTop)
                    assertTrue("${choice.title} caption remains inside the scroll viewport", bounds(window, name).bottom <= bounds(window, shell.scroller).bottom)
                }
            } finally {
                for (name in listOf("worker", "images", "authWorker")) (MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(main) as ExecutorService).shutdownNow()
                controller.pause().stop().destroy()
            }
        }
    }

    private fun fixture(width: Int, height: Int) {
        ShadowInstrumentation.getInstrumentation().setInTouchMode(true)
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get(); activity.setTheme(R.style.AppTheme)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        controller.setup().visible()
        try {
            val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 0, 16, 0) }
            val scroll = ScrollView(activity).apply { addView(body) }
            val applied = mutableListOf<MobileCameoTheme>()
            MobileThemePickerRenderer(activity).render(body, MobileCameoTheme.GLASS) { applied += it }
            activity.setContentView(scroll)
            repeat(2) {
                scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                scroll.layout(0, 0, width, height); shadowOf(Looper.getMainLooper()).idle()
            }
            val grid = body.findViewWithTag<LinearLayout>("mobile-theme-grid")
            val rows = descendants(grid).filterIsInstance<LinearLayout>().filter { it.tag == "mobile-theme-row" }
            val cards = descendants(grid).filter { (it.tag as? String)?.startsWith("mobile-theme:") == true }
            assertEquals(5, cards.size)
            assertEquals(if (width < 600) 2 else MobileThemePickerRenderer.geometry(width - 32, height).columns, rows.first().childCount)
            assertTrue("Wide phone layouts show more than two columns", width < 600 || rows.first().childCount > 2)
            val firstBounds = bounds(grid, cards.first())
            assertTrue("Preview cards stay compact instead of filling the full page", firstBounds.width() < width / 2)
            assertEquals("Only the selected choice has a tick", 1, descendants(grid).count { it.tag == "mobile-theme-selected" })
            cards.forEach { card ->
                val rect = bounds(grid, card)
                assertTrue("Each portrait card has a complete horizontal hit area", rect.left >= 0 && rect.right <= grid.width)
                assertTrue(card.isClickable && card.width >= 48 && card.height >= 48)
                assertFalse("First touch must apply the theme", card.isFocusableInTouchMode)
            }
            MobileCameoTheme.entries.forEach { theme ->
                val image = body.findViewWithTag<ImageView>("mobile-theme-image:${theme.name}")
                val name = body.findViewWithTag<TextView>("mobile-theme-name:${theme.name}")
                assertEquals(ImageView.ScaleType.FIT_CENTER, image.scaleType)
                assertNotNull(image.drawable)
                assertTrue("Preview keeps a natural portrait box", image.width < image.height && image.height <= 300)
                val matrix = FloatArray(9); image.imageMatrix.getValues(matrix)
                assertEquals("Artwork is scaled uniformly", matrix[Matrix.MSCALE_X], matrix[Matrix.MSCALE_Y], .001f)
                assertEquals(theme.title, name.text.toString()); assertEquals(1, name.lineCount)
                assertEquals(0, name.layout.getEllipsisCount(0))
                assertEquals(theme.title.length, name.layout.getLineEnd(0))
            }
            // Dispatch through the card so real child hit testing proves image and footer touches bubble once.
            val first = cards.first()
            val image = body.findViewWithTag<ImageView>("mobile-theme-image:GLASS")
            val imageRect = bounds(first as ViewGroup, image)
            touch(first, imageRect.exactCenterX(), imageRect.exactCenterY())
            assertEquals(listOf(MobileCameoTheme.GLASS), applied)
            val second = cards[1]
            val footer = body.findViewWithTag<TextView>("mobile-theme-name:CINEMA")
            val footerRect = bounds(second as ViewGroup, footer)
            touch(second, footerRect.exactCenterX(), footerRect.exactCenterY())
            assertEquals(listOf(MobileCameoTheme.GLASS, MobileCameoTheme.CINEMA), applied)
        } finally { controller.pause().stop().destroy(); ShadowInstrumentation.getInstrumentation().setInTouchMode(false) }
    }
    private fun bounds(root: ViewGroup, view: View) = Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun touch(view: View, x: Float, y: Float) {
        assertTrue(view.isInTouchMode)
        val time = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to time, MotionEvent.ACTION_UP to time + 40)) {
            val event = MotionEvent.obtain(time, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    }
}
