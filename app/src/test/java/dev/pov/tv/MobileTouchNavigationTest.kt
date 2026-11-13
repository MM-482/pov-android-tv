package dev.pov.tv

import android.app.Activity
import android.app.UiModeManager
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.ui.PlayerView
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

/** Native down/up input, from another focused control: performClick would conceal the first-tap bug. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MobileTouchNavigationTest {
    @Test fun `first mobile touch activates actual Main generic and primary buttons`() {
        for (factory in listOf("button", "primaryButton")) {
            val main = Robolectric.buildActivity(MainActivity::class.java).get()
            main.setTheme(R.style.AppTheme)
            var calls = 0
            try {
                val action: () -> Unit = { calls++ }
                val control = MainActivity::class.java.getDeclaredMethod(factory, String::class.java, Function0::class.java)
                    .apply { isAccessible = true }.invoke(main, "First touch fixture", action) as View
                withAttachedControl(control) { target ->
                    assertFalse("$factory must not consume touch to acquire focus", target.isFocusableInTouchMode)
                    touchOnce(target)
                    assertEquals("$factory must run on the first down/up gesture", 1, calls)
                }
            } finally { closeMainWorkers(main) }
        }
    }

    @Test fun `first mobile touch activates a source or file choice`() {
        var calls = 0
        withHost { activity ->
            val row = TvChoiceRow(activity, "fixture:source", "1080p fixture", "A selectable source edition") { calls++ }
            attachControl(activity, row)
            assertFalse(row.isFocusableInTouchMode)
            touchOnce(row)
            assertEquals(1, calls)
        }
    }

    @Test
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun `mini player video and caption respond on first touch after focus router initialization`() {
        for (captionTouch in listOf(false, true)) withHost { activity ->
            var calls = 0
            val mini = FrameLayout(activity).apply {
                isFocusable = true; isFocusableInTouchMode = false; isClickable = true
                setOnClickListener { calls++ }
            }
            mini.addView(PlayerView(activity).apply {
                useController = false; isClickable = false; isFocusable = false; isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }, FrameLayout.LayoutParams(-1, -1))
            val caption = LinearLayout(activity).apply {
                isClickable = false; isFocusable = false
                addView(TextView(activity).apply { text = "Tap to return"; isClickable = false; isFocusable = false }, LinearLayout.LayoutParams(-1, -1))
            }
            mini.addView(caption, FrameLayout.LayoutParams(-1, 28, Gravity.BOTTOM))
            MiniPlayerFocusRouter(LinearLayout(activity), mini)
            assertFalse("Router must preserve the mini player's mobile touch behavior", mini.isFocusableInTouchMode)
            attachControl(activity, mini)
            touchOnce(mini, mini.width / 2f, if (captionTouch) mini.height - 14f else mini.height / 3f)
            assertEquals("One ${if (captionTouch) "caption" else "video-area"} touch returns to playback", 1, calls)
        }
    }

    @Test fun `first mobile touch selects an unfocused player option and closes its sheet`() {
        withHost { activity ->
            var calls = 0
            val dialog = PlayerOptionSheet.create(activity, "Audio", "Controlled track choices", listOf(
                PlayerSheetChoice("English", checked = true) {},
                PlayerSheetChoice("Japanese") { calls++ }
            ), 0xFF00D5ED.toInt())
            try {
                dialog.show(); shadowOf(Looper.getMainLooper()).idle()
                val decor = dialog.window!!.decorView
                measure(decor)
                val target = decor.findViewWithTag<Button>("pov_sheet_choice_1")
                assertFalse(target.isFocusableInTouchMode)
                assertFalse("Second track starts unfocused", target.isFocused)
                touchOnce(target)
                assertEquals("One touch chooses the requested track", 1, calls)
                assertFalse(dialog.isShowing)
            } finally { dialog.dismiss() }
        }
    }

    @Test fun `native touch fixture exposes focus consuming controls rather than invoking clicks directly`() {
        var calls = 0
        withHost { activity ->
            val oldBehavior = TextView(activity).apply {
                text = "Focusable touch fixture"; isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                setOnClickListener { calls++ }
            }
            attachControl(activity, oldBehavior)
            touchOnce(oldBehavior)
            assertEquals("Native first touch is consumed acquiring focus for the old behavior", 0, calls)
            assertTrue(oldBehavior.isFocused)
            touchOnce(oldBehavior)
            assertEquals("The same old behavior requires a second touch", 1, calls)
        }
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-television-mdpi")
    fun `television choices Main buttons and player options retain remote focus`() {
        withHost(television = true) { activity ->
            val main = Robolectric.buildActivity(MainActivity::class.java).get()
            main.setTheme(R.style.AppTheme)
            try {
                for (factory in listOf("button", "primaryButton")) {
                    val action: () -> Unit = {}
                    val button = MainActivity::class.java.getDeclaredMethod(factory, String::class.java, Function0::class.java)
                        .apply { isAccessible = true }.invoke(main, "Remote fixture", action) as View
                    attachControl(activity, button)
                    assertTrue(button.isFocusable && button.isFocusableInTouchMode)
                    assertTrue(button.requestFocus())
                }
                val row = TvChoiceRow(activity, "fixture:tv-source", "Source") {}
                attachControl(activity, row)
                assertTrue(row.isFocusable && row.isFocusableInTouchMode)
                assertTrue(row.requestFocus())
                val dialog = PlayerOptionSheet.create(activity, "Audio", "", listOf(PlayerSheetChoice("Track") {}), 0xFF00D5ED.toInt())
                try {
                    dialog.show(); shadowOf(Looper.getMainLooper()).idle()
                    val option = dialog.window!!.decorView.findViewWithTag<Button>("pov_sheet_choice_0")
                    assertTrue(option.isFocusable && option.isFocusableInTouchMode)
                    assertTrue(option.requestFocus())
                } finally { dialog.dismiss() }
            } finally { closeMainWorkers(main) }
        }
    }

    @Test @Config(qualifiers = "w360dp-h820dp-port-mdpi")
    fun `active playback keeps all five mobile navigation targets on cover displays`() { assertMobilePlaybackNavigation() }

    @Test @Config(qualifiers = "w720dp-h780dp-port-mdpi")
    fun `active playback keeps all five mobile navigation targets on unfolded displays`() { assertMobilePlaybackNavigation() }

    @Test fun `mobile render completion preserves touch scrolling and restores saved position without first row focus`() {
        withHost { activity ->
            val main = Robolectric.buildActivity(MainActivity::class.java).get()
            main.setTheme(R.style.AppTheme)
            try {
                val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                repeat(30) { index -> body.addView(TextView(activity).apply {
                    text = "Row $index"; tag = "fixture:$index"; isFocusable = true; isFocusableInTouchMode = false; isClickable = true
                }, LinearLayout.LayoutParams(-1, 70)) }
                val scroll = ScrollView(activity).apply { addView(body) }
                val focus = TvFocusController(body, scroll)
                activity.setContentView(scroll); measure(activity.window.decorView)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(body.isInTouchMode)
                setMainField(main, "body", body); setMainField(main, "scroller", scroll); setMainField(main, "focusController", focus)
                setMainField(main, "generation", 42); setMainField(main, "wantsContentFocus", true); setMainField(main, "pendingFocus", null)
                scroll.scrollTo(0, 300); assertEquals(300, scroll.scrollY)
                invokeMain(main, "focusAfterRender", Integer.TYPE, 42)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("An asynchronous render must preserve the reader's touch scroll", 300, scroll.scrollY)
                assertFalse("Render must not focus the first row", body.getChildAt(0).isFocused)
                assertNull(mainField(main, "pendingFocus")); assertEquals(false, mainField(main, "wantsContentFocus"))
                scroll.scrollTo(0, 650)
                setMainField(main, "pendingFocus", TvFocusSnapshot("fixture:0", 470)); setMainField(main, "wantsContentFocus", true)
                invokeMain(main, "focusAfterRender", Integer.TYPE, 42)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("Saved mobile position is restored without revealing the top row", 470, scroll.scrollY)
                assertFalse(body.getChildAt(0).isFocused)
                assertNull(mainField(main, "pendingFocus")); assertEquals(false, mainField(main, "wantsContentFocus"))
            } finally { closeMainWorkers(main) }
        }
    }

    @Test fun `mobile render completion cannot replace a restored unkeyed input caret in keyboard mode`() {
        withHost(touchMode=false) { activity ->
            val main=Robolectric.buildActivity(MainActivity::class.java).get()
            main.setTheme(R.style.AppTheme)
            try {
                val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
                val name=EditText(activity).apply {hint="Display name";setText("Synthetic server")}
                val password=EditText(activity).apply {hint="Password";setText("fixture-only-password")}
                body.addView(name,LinearLayout.LayoutParams(-1,60));body.addView(password,LinearLayout.LayoutParams(-1,60))
                val scroll=ScrollView(activity).apply{addView(body)}
                activity.setContentView(scroll);measure(activity.window.decorView);shadowOf(Looper.getMainLooper()).idle()
                assertTrue(password.requestFocus());password.setSelection(3,11)
                assertFalse(body.isInTouchMode)
                setMainField(main,"body",body);setMainField(main,"scroller",scroll)
                val focus=TvFocusController(body,scroll)
                setMainField(main,"focusController",focus);setMainField(main,"generation",43)
                // This is the real legacy capture for an untagged EditText, not a row identity.
                val restore=focus.capture();assertNull(restore.key)
                setMainField(main,"pendingFocus",restore);setMainField(main,"wantsContentFocus",true)
                repeat(3){invokeMain(main,"focusAfterRender",Integer.TYPE,43);shadowOf(Looper.getMainLooper()).idle()}
                assertTrue("Late render callbacks must keep the restored password field focused",password.hasFocus())
                assertFalse(name.hasFocus());assertEquals(3,password.selectionStart);assertEquals(11,password.selectionEnd)
                assertNull(mainField(main,"pendingFocus"));assertEquals(false,mainField(main,"wantsContentFocus"))
            } finally {closeMainWorkers(main)}
        }
    }

    @Test fun `legacy mobile window consistently leaves system bar padding to the mobile root`() {
        val main=Robolectric.buildActivity(MainActivity::class.java).get()
        main.setTheme(R.style.AppTheme)
        try {
            for(theme in listOf(MobileCameoTheme.GLASS,MobileCameoTheme.PEARL)) {
                setMainField(main,"theme",theme.foundation)
                MainActivity::class.java.getDeclaredMethod("applyMobileSystemBars").apply{isAccessible=true}.invoke(main)
                val flags=main.window.decorView.systemUiVisibility
                assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_STABLE!=0)
                assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN!=0)
                assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION!=0)
                assertEquals(theme==MobileCameoTheme.PEARL,flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR!=0)
                assertEquals(0,flags and (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION))
            }
        } finally {closeMainWorkers(main)}
    }

    private fun assertMobilePlaybackNavigation() {
        MobileCameoTheme.entries.forEach { theme -> withHost { activity ->
            val main = Robolectric.buildActivity(MainActivity::class.java).get()
            main.setTheme(R.style.AppTheme)
            try {
                setMainField(main, "theme", theme.foundation)
                val destinations = listOf("home", "movie", "series", "anime", "library", "search", "settings").map { key -> ConceptDestination(key, key) {} }
                val shell = MobileCameoRenderer(activity, theme).shell(destinations) { "home" }
                activity.setContentView(shell.root); stabilize(activity.window.decorView)
                @Suppress("UNCHECKED_CAST")
                val navigation = mainField(main, "navigationViews") as MutableMap<String, View>
                navigation.putAll(shell.navigationViews)
                val expectedKeys = listOf("home", "movie", "anime", "library", "search")
                fun bounds(view: View) = Rect(0, 0, view.width, view.height).also { shell.root.offsetDescendantRectToMyCoords(view, it) }
                val original = navigation.mapValues { bounds(it.value) }
                for (active in listOf(true, false, true, true, false, true)) {
                    invokeMain(main, "updatePlaybackNavigation", java.lang.Boolean.TYPE, active)
                    stabilize(activity.window.decorView)
                    assertEquals("${theme.title} retains exactly its five phone destinations", expectedKeys, navigation.keys.toList())
                    navigation.forEach { (key, cell) ->
                        assertTrue("${theme.title} $key remains visible and tappable", cell.width > 0 && cell.height > 0 && cell.isClickable)
                        assertEquals("${theme.title} $key is not squeezed by a Now playing row", original[key], bounds(cell))
                    }
                }
                val host = requireNotNull(navigation["home"]?.parent as? LinearLayout)
                val stale = TextView(activity).apply { tag = "nav:nowplaying"; text = "Stale Now playing" }
                host.addView(stale, LinearLayout.LayoutParams(-1, 35)); navigation["nowplaying"] = stale
                invokeMain(main, "updatePlaybackNavigation", java.lang.Boolean.TYPE, true)
                stabilize(activity.window.decorView)
                assertNull("${theme.title} removes a stale full-width playback cell", stale.parent)
                assertEquals(expectedKeys, navigation.keys.toList())
                navigation.forEach { (key, cell) -> assertEquals(original[key], bounds(cell)) }
            } finally { closeMainWorkers(main) }
        } }
    }

    private fun setMainField(main: MainActivity, name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(main, value)
    private fun mainField(main: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(main)
    private fun invokeMain(main: MainActivity, name: String, type: Class<*>, value: Any) = MainActivity::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(main, value)

    private fun withAttachedControl(control: View, test: (View) -> Unit) = withHost { activity ->
        attachControl(activity, control); test(control)
    }

    private fun withHost(television: Boolean = false, touchMode: Boolean = !television, test: (Activity) -> Unit) {
        ShadowInstrumentation.getInstrumentation().setInTouchMode(touchMode)
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, !television)
        shadowOf(requireNotNull(activity.getSystemService(UiModeManager::class.java))).setCurrentModeType(
            if (television) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL)
        controller.setup().visible()
        try { test(activity) }
        finally { controller.pause().stop().destroy(); ShadowInstrumentation.getInstrumentation().setInTouchMode(false) }
    }

    private fun attachControl(activity: Activity, target: View) {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val sentinel = TextView(activity).apply {
            text = "Other focused control"; isFocusable = true; isFocusableInTouchMode = true
        }
        container.addView(sentinel, LinearLayout.LayoutParams(-1, 60))
        container.addView(target, LinearLayout.LayoutParams(-1, 90))
        activity.setContentView(container)
        measure(activity.window.decorView)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Control is attached to a real Android view root", target.isAttachedToWindow)
        assertTrue(sentinel.requestFocus())
        assertFalse("Target begins without focus", target.isFocused)
    }

    private fun measure(root: View) {
        val width = root.resources.displayMetrics.widthPixels.coerceAtLeast(390)
        val height = root.resources.displayMetrics.heightPixels.coerceAtLeast(540)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    private fun stabilize(root: View) {
        // The mobile shell applies inset padding after attachment, then requests another layout.
        repeat(3) { measure(root); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun touchOnce(target: View, x: Float = target.width / 2f, y: Float = target.height / 2f) {
        assertTrue("Touch target has a measured hit area", target.width > 0 && target.height > 0)
        assertTrue("Native touch dispatch uses touch mode", target.isInTouchMode)
        val start = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to start, MotionEvent.ACTION_UP to start + 40)) {
            val event = MotionEvent.obtain(start, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(target.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    }

    private fun closeMainWorkers(main: MainActivity) {
        for (name in listOf("worker", "images", "authWorker")) (MainActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(main) as ExecutorService).shutdownNow()
    }
}
