package dev.pov.tv

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pov.tv.data.SourceItem
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

/** The reported movie-version bug involves tall multiline cards, not short episode buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SourceChoiceNavigationTest {
    private lateinit var activity: Activity
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var choices: TvChoiceList
    private lateinit var controller: TvFocusController
    private val activated = mutableListOf<Int>()

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        scroll = ScrollView(activity).apply { isFillViewport = true }
        body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(scroll)
        controller = TvFocusController(body, scroll)
        rebuild(70)
        shadowOf(Looper.getMainLooper()).idle()
        layout()
    }

    @After fun cleanup() { activity.finish(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun `every tall source version selects and activates sequentially in both directions`() {
        assertTrue(controller.first())
        for (index in 0 until 70) {
            if (index > 0) assertTrue(controller.move(View.FOCUS_DOWN))
            assertSelected(index)
            assertTrue(choices.getChildAt(index).performClick())
        }
        assertEquals((0 until 70).toList(), activated)
        for (index in 68 downTo 0) { controller.move(View.FOCUS_UP); assertSelected(index) }
        controller.move(View.FOCUS_UP); assertSelected(0)
    }

    @Test fun `platform focus links do not skip middle versions and mini only enters at the final row`() {
        val shell = FrameLayout(activity)
        (scroll.parent as ViewGroup).removeView(scroll)
        shell.addView(scroll, FrameLayout.LayoutParams(600, 180))
        val mini = FrameLayout(activity).apply { isFocusable = true; isFocusableInTouchMode = true }
        shell.addView(mini, FrameLayout.LayoutParams(150, 80).apply { leftMargin = 620; topMargin = 200 })
        activity.setContentView(shell)
        shadowOf(Looper.getMainLooper()).idle()
        shell.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        shell.layout(0, 0, 960, 540)
        val router = MiniPlayerFocusRouter(body, mini)
        controller.first()
        for (index in 0 until 69) {
            val selected = choices.getChildAt(index)
            assertSame(choices.getChildAt(index + 1), FocusFinder.getInstance().findNextFocus(body, selected, View.FOCUS_DOWN))
            assertFalse("Mini must not intercept an intermediate source", router.route(View.FOCUS_DOWN, selected))
            controller.move(View.FOCUS_DOWN)
        }
        assertTrue(router.route(View.FOCUS_DOWN, choices.getChildAt(69)))
        assertSame(mini, shell.findFocus())
        assertTrue(router.route(View.FOCUS_UP, mini))
        assertSame(choices.getChildAt(69), shell.findFocus())
    }

    @Test fun `Back snapshot restores exact middle file after cards with duplicate names are rebuilt`() {
        rebuild(7, fileRows = true); layout()
        controller.restore(TvFocusSnapshot(TvChoiceKeys.file("torrents", 55, 104), 0))
        assertSelected(4)
        val before = body.findFocus()
        val snapshot = controller.capture()
        rebuild(7, fileRows = true); layout()
        assertTrue(controller.restore(snapshot))
        assertSelected(4)
        assertNotSame(before, body.findFocus())
        assertEquals("file:torrents:55:104", snapshot.key)
    }

    @Test fun `long text cannot own focus or scroll inside the source card`() {
        controller.first()
        repeat(35) { controller.move(View.FOCUS_DOWN) }
        val row = body.findFocus() as TvChoiceRow
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, row.descendantFocusability)
        val labels = descendants(row).filterIsInstance<TextView>()
        assertEquals(3, labels.size)
        labels.forEach {
            assertFalse(it.isFocusable)
            assertFalse(it.isClickable)
            assertNull(it.movementMethod)
            assertTrue(it.maxLines in 1..2)
            assertEquals(0, it.scrollY)
            assertEquals(0, it.scrollX)
        }
        assertSelected(35)
    }

    @Test fun `same torrent and file index retain source key across provider metadata updates`() {
        val source = SourceItem("Example Movie", "Provider One", "ABCDEF", 2, cached = false, detail = "old size")
        assertEquals(TvChoiceKeys.source(source), TvChoiceKeys.source(source.copy(title = "New label", provider = "Provider Two", infoHash = "abcdef", cached = true, detail = "new size")))
        assertNotEquals(TvChoiceKeys.source(source), TvChoiceKeys.source(source.copy(fileIndex = 3)))
    }

    @Test fun `direct source keys distinguish signed URLs without including private URL text`() {
        val source = SourceItem("Example Movie", "Fixture", directUrl = "https://example.invalid/movie?token=private-value")
        val key = TvChoiceKeys.source(source)
        assertTrue(key.matches(Regex("source:[a-f0-9]{64}")))
        assertFalse(key.contains("private-value"))
        assertFalse(key.contains("https"))
        assertNotEquals(key, TvChoiceKeys.source(source.copy(directUrl = "https://example.invalid/other")))
    }

    @Test fun `cloud identities distinguish same named files and folders by kind and actual file id`() {
        assertNotEquals(TvChoiceKeys.file("torrents", 55, 1), TvChoiceKeys.file("torrents", 55, 2))
        assertNotEquals(TvChoiceKeys.file("torrents", 55, 1), TvChoiceKeys.file("usenet", 55, 1))
        assertNotEquals(TvChoiceKeys.folder("torrents", 55), TvChoiceKeys.folder("webdl", 55))
    }

    private fun rebuild(count: Int, fileRows: Boolean = false) {
        body.removeAllViews()
        choices = TvChoiceList(activity)
        body.addView(choices, LinearLayout.LayoutParams(-1, -2))
        repeat(count) { index ->
            val key = if (fileRows) TvChoiceKeys.file("torrents", 55, 100L + index) else "source-fixture:$index"
            choices.addChoice(TvChoiceRow(activity, key,
                "Example Movie same filename repeated with deliberately long title that wraps across several lines in a source version card".repeat(3),
                "${index + 1}.00 GB · 1080p · synthetic public fixture",
                if (fileRows) "" else "READY IN TORBOX · Public fixture") { activated += index })
        }
    }

    private fun layout() {
        scroll.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
        scroll.layout(0, 0, 600, 180)
    }

    private fun assertSelected(index: Int) {
        val selected = body.findFocus()
        assertSame("Version $index must own focus", choices.getChildAt(index), selected)
        assertTrue(controller.hasVisibleLeafFocus())
        val bounds = Rect()
        selected!!.getDrawingRect(bounds); body.offsetDescendantRectToMyCoords(selected, bounds)
        assertTrue("Complete selected card must be visible above viewport top", bounds.top >= scroll.scrollY)
        assertTrue("Complete selected card must be visible below viewport bottom", bounds.bottom <= scroll.scrollY + scroll.height)
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
