package dev.pov.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Native attached views: focus must remain on a visible action across several screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TvFocusControllerTest {
    private lateinit var activity: Activity
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var controller: TvFocusController

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalFadingEdgeEnabled = false
        }
        body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body, android.widget.FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(scroll)
        controller = TvFocusController(body, scroll)
        rebuild()
        shadowOf(Looper.getMainLooper()).idle()
        layout()
    }

    @After fun cleanup() { activity.finish(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun `scroll containers cannot become invisible remote selections`() {
        assertFalse(scroll.isFocusable)
        assertFalse(scroll.isFocusableInTouchMode)
        assertFalse(body.isFocusable)
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, scroll.descendantFocusability)
        assertTrue(controller.first())
        assertSelected(0)
        assertNotSame(scroll, activity.currentFocus)
        assertNotSame(body, activity.currentFocus)
    }

    @Test fun `down and up always select an actual visible leaf across thirty episode actions`() {
        controller.reset()
        assertTrue(controller.first())
        assertSelected(0)
        for (index in 1 until 30) {
            assertTrue(controller.move(View.FOCUS_DOWN))
            assertSelected(index)
        }
        assertTrue("Traversal must have moved through several screen heights", scroll.scrollY > scroll.height * 4)
        val bottomScroll = scroll.scrollY
        assertTrue(controller.move(View.FOCUS_DOWN))
        assertSelected(29)
        assertEquals("Down at the last action must not scroll into empty space", bottomScroll, scroll.scrollY)
        for (index in 28 downTo 0) {
            assertTrue(controller.move(View.FOCUS_UP))
            assertSelected(index)
        }
        val topScroll = scroll.scrollY
        assertTrue(controller.move(View.FOCUS_UP))
        assertSelected(0)
        assertEquals(topScroll, scroll.scrollY)
    }

    @Test fun `snapshot restores the selected episode and viewport after rebuilding child views`() {
        controller.reset(); controller.first()
        repeat(18) { controller.move(View.FOCUS_DOWN) }
        val oldSelection = body.findFocus()
        val snapshot = controller.capture()
        assertEquals("episode:18", snapshot.key)
        assertTrue(snapshot.scrollY > 0)
        rebuild(); layout()
        assertTrue(controller.restore(snapshot))
        assertSelected(18)
        assertNotSame("Restoration must identify the rebuilt action by its stable key", oldSelection, body.findFocus())
        assertEquals(snapshot.scrollY, scroll.scrollY)
    }

    @Test fun `a removed action does not masquerade as a successful restored selection`() {
        assertFalse(controller.restore(TvFocusSnapshot("episode:removed", 500)))
        controller.reset()
        assertTrue(controller.first())
        assertSelected(0)
    }

    @Test fun `last episode remains reachable and is revealed above an overlapping mini player`() {
        body.setPadding(0, 0, 0, 96)
        val obstruction = Rect()
        controller = TvFocusController(body, scroll) { obstruction }
        layout()
        val viewport = Rect()
        assertTrue(scroll.getGlobalVisibleRect(viewport))
        obstruction.set(viewport.right - 150, viewport.top + 100, viewport.right, viewport.bottom)
        controller.first()
        repeat(29) { controller.move(View.FOCUS_DOWN) }
        assertSelected(29)
        val selectedBounds = Rect()
        assertTrue(body.getChildAt(29).getGlobalVisibleRect(selectedBounds))
        assertTrue("The selected episode must be visible above the mini player", selectedBounds.bottom <= obstruction.top)
    }

    private fun rebuild() {
        body.removeAllViews()
        repeat(30) { index ->
            body.addView(Button(activity).apply {
                text = "Episode ${index + 1}"
                tag = "episode:$index"
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                setOnFocusChangeListener { _, focused ->
                    setBackgroundColor(if (focused) Color.CYAN else Color.DKGRAY)
                    setTextColor(if (focused) Color.BLACK else Color.WHITE)
                }
            }, LinearLayout.LayoutParams(-1, 48))
        }
    }

    private fun layout() {
        scroll.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
        scroll.layout(0, 0, 600, 180)
    }

    private fun assertSelected(index: Int) {
        val selected = body.findFocus()
        assertSame("Episode $index should own the visible selection", body.getChildAt(index), selected)
        assertTrue(controller.hasVisibleLeafFocus())
        assertTrue(selected!!.isFocusable)
        val bounds = Rect()
        selected.getDrawingRect(bounds)
        body.offsetDescendantRectToMyCoords(selected, bounds)
        assertTrue("Selected action top must be inside the viewport", bounds.top >= scroll.scrollY)
        assertTrue("Selected action bottom must be inside the viewport", bounds.bottom <= scroll.scrollY + scroll.height)
    }
}
