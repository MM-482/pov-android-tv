package dev.pov.tv

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.view.FocusFinder
import android.graphics.Rect
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MiniPlayerFocusRouterTest {
    private lateinit var activity: Activity
    private lateinit var content: LinearLayout
    private lateinit var mini: FrameLayout
    private lateinit var first: Button
    private lateinit var last: Button
    private lateinit var router: MiniPlayerFocusRouter
    private lateinit var root: FrameLayout

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        root = FrameLayout(activity)
        content = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; isFocusable = false }
        first = Button(activity).apply { text = "First"; isFocusableInTouchMode = true }
        last = Button(activity).apply { text = "Last"; isFocusableInTouchMode = true }
        content.addView(first, LinearLayout.LayoutParams(150, 60))
        content.addView(last, LinearLayout.LayoutParams(150, 60))
        mini = FrameLayout(activity).apply {
            addView(Button(activity).apply { text = "Video surface must not steal focus" }, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(content, FrameLayout.LayoutParams(500, 200))
        root.addView(mini, FrameLayout.LayoutParams(200, 120).apply { leftMargin = 700; topMargin = 400 })
        activity.setContentView(root)
        router = MiniPlayerFocusRouter(content, mini)
        shadowOf(Looper.getMainLooper()).idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 960, 540)
    }
    @After fun cleanup() { activity.finish() }

    @Test fun `right moves within content then enters mini card at the edge`() {
        first.requestFocus()
        assertFalse(router.route(View.FOCUS_RIGHT, first))
        last.requestFocus()
        assertTrue(router.route(View.FOCUS_RIGHT, last))
        assertSame(mini, root.findFocus())
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, mini.descendantFocusability)
    }

    @Test fun `down at the content edge enters mini and left returns to exact prior action`() {
        last.requestFocus()
        assertTrue(router.route(View.FOCUS_DOWN, last))
        assertSame(mini, root.findFocus())
        assertTrue(router.route(View.FOCUS_LEFT, mini))
        assertSame(last, root.findFocus())
    }

    @Test fun `a self-referencing directional target counts as an edge instead of trapping focus`() {
        last.id = View.generateViewId()
        last.nextFocusRightId = last.id
        last.requestFocus()
        assertTrue(router.route(View.FOCUS_RIGHT, last))
        assertSame(mini, root.findFocus())
    }

    @Test fun `last card enters mini even when global focus finds an offscreen card in another row`() {
        val (row, offscreen, _) = twoHorizontalRows()
        row.scrollTo(230, 0)
        last.requestFocus()
        assertTrue(offscreen.isShown)
        assertFalse(offscreen.getGlobalVisibleRect(Rect()))
        assertSame(offscreen, FocusFinder.getInstance().findNextFocus(content, last, View.FOCUS_RIGHT))
        assertTrue(router.route(View.FOCUS_RIGHT, last))
        assertSame(mini, root.findFocus())
    }

    @Test fun `a middle card keeps native Right when the next card needs horizontal scrolling`() {
        val (row, _, middle) = twoHorizontalRows()
        row.scrollTo(80, 0)
        middle.requestFocus()
        assertTrue(last.isShown)
        assertFalse(last.getGlobalVisibleRect(Rect()))
        assertFalse(router.route(View.FOCUS_RIGHT, middle))
        assertSame(middle, root.findFocus())
    }

    private fun twoHorizontalRows(): Triple<HorizontalScrollView, Button, Button> {
        content.removeAllViews()
        content.orientation = LinearLayout.VERTICAL
        val middle = Button(activity).apply { text = "Middle"; isFocusableInTouchMode = true }
        val firstLine = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; isFocusable = false }
        listOf(first, middle, last).forEach { firstLine.addView(it, LinearLayout.LayoutParams(150, 60)) }
        val firstRow = HorizontalScrollView(activity).apply {
            isFocusable = false; isFocusableInTouchMode = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(firstLine)
        }
        val offscreen = Button(activity).apply { text = "Other row offscreen card"; isFocusableInTouchMode = true }
        val otherLine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; isFocusable = false
            addView(View(activity), LinearLayout.LayoutParams(600, 60))
            addView(offscreen, LinearLayout.LayoutParams(150, 60))
        }
        val otherRow = HorizontalScrollView(activity).apply {
            isFocusable = false; isFocusableInTouchMode = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(otherLine)
        }
        content.addView(firstRow, LinearLayout.LayoutParams(220, 80))
        content.addView(otherRow, LinearLayout.LayoutParams(220, 80))
        shadowOf(Looper.getMainLooper()).idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 960, 540)
        return Triple(firstRow, offscreen, middle)
    }

    @Test fun `mini does not wrap focus offscreen at right or down edges`() {
        mini.requestFocus()
        assertTrue(router.route(View.FOCUS_RIGHT, mini))
        assertTrue(router.route(View.FOCUS_DOWN, mini))
        assertSame(mini, root.findFocus())
    }

    @Test fun `a stopped hidden mini never captures content focus`() {
        mini.visibility = View.GONE
        last.requestFocus()
        assertFalse(router.route(View.FOCUS_RIGHT, last))
        assertSame(last, root.findFocus())
    }

    @Test fun `if the remembered action was removed return selects a surviving content action`() {
        last.requestFocus(); router.route(View.FOCUS_RIGHT, last)
        content.removeView(last)
        assertTrue(router.route(View.FOCUS_UP, mini))
        assertSame(first, root.findFocus())
    }
}
