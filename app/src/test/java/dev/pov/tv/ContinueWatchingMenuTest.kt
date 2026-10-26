package dev.pov.tv

import android.app.AlertDialog
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.pov.tv.data.PlaybackIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutorService

/** Actual native title menu, with no MainActivity.onCreate, player, or account requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ContinueWatchingMenuTest {
    private val identity = PlaybackIdentity("tt0903747", "series", "Breaking Bad", 1, 2)
    private val key = "local-resume-fixture"

    @Test fun `holding Select opens Resume and Remove without playing or hiding anything`() {
        val fixture = fixture()
        try {
            assertTrue(fixture.row.performLongClick())
            val dialog = dialog()
            assertTrue(dialog.isShowing)
            assertEquals(listOf("Resume", "Remove from Continue Watching"),
                (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() })
            assertEquals(0, fixture.resumes)
            assertFalse(fixture.history.isHidden(key, identity))
        } finally { fixture.close() }
    }

    @Test fun `Remove hides the title durably while retaining resume position and pending Trakt transaction`() {
        val fixture = fixture()
        try {
            val pendingBefore = fixture.history.pending()
            fixture.row.performLongClick()
            select(1)
            val reopened = PlaybackStore(fixture.activity)
            assertTrue(reopened.isHidden(key, identity))
            assertTrue(reopened.continueWatching().isEmpty())
            assertEquals(120_000L, reopened.resume(key))
            assertEquals(120_000L, reopened.recent().single().position)
            assertEquals(pendingBefore, reopened.pending())
            assertEquals(0, fixture.resumes)
            assertEquals(1, fixture.renders)
        } finally { fixture.close() }
    }

    @Test fun `Back cancels title options without hiding the title or acknowledging pending history`() {
        val fixture = fixture()
        try {
            val pendingBefore = fixture.history.pending()
            fixture.row.performLongClick()
            val menu = dialog()
            shadowOf(Looper.getMainLooper()).idle()
            menu.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            menu.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(menu.isShowing)
            assertEquals(identity.title, PlaybackStore(fixture.activity).continueWatching().single().title)
            assertEquals(pendingBefore, fixture.history.pending())
            assertEquals(120_000L, fixture.history.resume(key))
            assertEquals(0, fixture.resumes)
            assertEquals(0, fixture.renders)
        } finally { fixture.close() }
    }

    @Test fun `Resume invokes only the supplied local callback and preserves hidden and pending state`() {
        val fixture = fixture()
        try {
            val pendingBefore = fixture.history.pending()
            fixture.row.performLongClick(); select(0)
            assertEquals(1, fixture.resumes)
            assertEquals(0, fixture.renders)
            assertFalse(fixture.history.isHidden(key, identity))
            assertEquals(pendingBefore, fixture.history.pending())
        } finally { fixture.close() }
    }

    @Test fun `Menu key opens options for the focused Continue Watching target`() {
        val fixture = fixture()
        try {
            assertTrue(fixture.row.requestFocus())
            val focused = fixture.activity.window.decorView.findFocus()
            assertSame(fixture.row, focused)
            // Robolectric 4.16 shadows Activity.getCurrentFocus with an independent field;
            // bridge the verified native window focus before exercising Main's real dispatch.
            shadowOf(fixture.activity).setCurrentFocus(focused)
            assertSame(fixture.row, fixture.activity.currentFocus)
            assertTrue(fixture.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)))
            assertTrue(fixture.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)))
            assertTrue(dialog().isShowing)
            assertEquals("Remove from Continue Watching", dialog().listView.adapter.getItem(1).toString())
            assertEquals(0, fixture.resumes)
        } finally { fixture.close() }
    }

    private fun fixture(): Fixture {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(body) }
        val row = Button(activity).apply { text = identity.title; tag = "resume:$key"; isFocusableInTouchMode = true }
        body.addView(row)
        activity.setContentView(scroll)
        set(activity, "theme", ThemeSpec.CINEMATIC)
        set(activity, "body", body); set(activity, "scroller", scroll)
        set(activity, "focusController", TvFocusController(body, scroll))
        activity.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val history = PlaybackStore(activity)
        history.save(key, identity.title, identity, 120_000, 600_000)
        history.queue(identity, 20.0, "pause")
        set(activity, "history", history)
        val fixture = Fixture(activity, row, history)
        set(activity, "current", { fixture.renders++; Unit })
        MainActivity::class.java.getDeclaredMethod("bindContinueOptions", View::class.java, String::class.java,
            PlaybackIdentity::class.java, String::class.java, Function0::class.java).apply { isAccessible = true }
            .invoke(activity, row, key, identity, identity.title, { fixture.resumes++; Unit })
        // visible() attaches/initializes the native decor without running MainActivity.onCreate.
        controller.visible().windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        return fixture
    }
    private class Fixture(val activity: MainActivity, val row: Button, val history: PlaybackStore) {
        var resumes = 0
        var renders = 0
        fun close() {
            ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
            activity.finish()
            shadowOf(Looper.getMainLooper()).idle()
            for (name in listOf("worker", "images", "authWorker")) {
                (MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as ExecutorService).shutdownNow()
            }
        }
    }
    private fun dialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
    private fun select(index: Int) { val menu = dialog(); menu.listView.performItemClick(null, index, menu.listView.adapter.getItemId(index)) }
    private fun set(activity: MainActivity, name: String, value: Any) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
}
