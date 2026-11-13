package dev.pov.tv

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.Gravity
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Real attach/listener/layout behavior with explicit window data, without needing another SDK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MobileCameoInsetsTest {
    private class TrackingRoot(context: Context) : FrameLayout(context) {
        var requests = 0
        override fun requestApplyInsets() { requests++; super.requestApplyInsets() }
    }

    @Test fun `a shell created before attachment requests real insets and ignores consumed child values`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = TrackingRoot(activity)
        val bars = Rect(0, 28, 0, 24)
        val full = insets(bars)
        MobileCameoInsetBinding(root) { full }
        assertFalse(root.isAttachedToWindow)
        assertEquals("A request must wait until a window exists", 0, root.requests)
        try {
            activity.setContentView(root); controller.visible()
            layoutWindow(activity.window.decorView)
            placeInWindow(root, Rect(0, 0, 390, 844))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(root.isAttachedToWindow)
            assertTrue("Cold attachment requests a fresh inset dispatch", root.requests >= 2)
            root.dispatchApplyWindowInsets(full.consumeSystemWindowInsets().consumeStableInsets())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Consumed child dispatch must retain full-window top/bottom protection", bars, padding(root))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `late full window information is reread on layout after the initial zero dispatch`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get(); val root = TrackingRoot(activity)
        var full: WindowInsets? = null
        MobileCameoInsetBinding(root) { full }
        try {
            activity.setContentView(root); controller.visible(); layoutWindow(activity.window.decorView)
            placeInWindow(root, Rect(0, 0, 390, 844))
            root.dispatchApplyWindowInsets(insets(Rect()).consumeSystemWindowInsets())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Rect(), padding(root))
            full = insets(Rect(0, 28, 0, 24))
            // No second child dispatch: the later traversal must read rootWindowInsets again.
            placeInWindow(root, Rect(0, 0, 390, 843)); placeInWindow(root, Rect(0, 0, 390, 844))
            assertEquals(Rect(0, 28, 0, 24), padding(root))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `zero full window data does not discard meaningful top and bottom child insets`() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get();val root=TrackingRoot(activity)
        MobileCameoInsetBinding(root){insets(Rect())}
        try {
            activity.setContentView(root);controller.visible();layoutWindow(activity.window.decorView)
            placeInWindow(root,Rect(0,0,390,844))
            root.dispatchApplyWindowInsets(insets(Rect(0,28,0,24)))
            assertEquals(Rect(0,28,0,24),padding(root))
        } finally {controller.pause().stop().destroy()}
    }

    @Test fun `already inset legacy content receives no duplicate padding from full window values`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get(); val root = TrackingRoot(activity)
        val full = insets(Rect(8, 28, 12, 24))
        MobileCameoInsetBinding(root) { full }
        try {
            activity.setContentView(root); controller.visible(); layoutWindow(activity.window.decorView)
            placeInWindow(root, Rect(8, 28, 378, 820))
            root.dispatchApplyWindowInsets(full.consumeSystemWindowInsets().consumeStableInsets())
            assertEquals("A parent already outside system bars must not be padded again", Rect(), padding(root))
            placeInWindow(root, Rect(0, 0, 390, 844))
            assertEquals("Edge-to-edge geometry reserves every exposed bar edge", Rect(8, 28, 12, 24), padding(root))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `fold or rotation replaces old edge padding from fresh window data without another child dispatch`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get(); val root = TrackingRoot(activity)
        var full = insets(Rect(0, 28, 0, 24))
        MobileCameoInsetBinding(root) { full }
        try {
            activity.setContentView(root); controller.visible(); layoutWindow(activity.window.decorView)
            placeInWindow(root, Rect(0, 0, 390, 844))
            root.dispatchApplyWindowInsets(full.consumeSystemWindowInsets().consumeStableInsets())
            assertEquals(Rect(0, 28, 0, 24), padding(root))
            full = insets(Rect(0, 28, 24, 0))
            placeInWindow(root, Rect(0, 0, 389, 844)); placeInWindow(root, Rect(0, 0, 390, 844))
            assertEquals("The new window edge replaces stale bottom padding", Rect(0, 28, 24, 0), padding(root))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `real window snapshot supplies system bars when both view root and child are zero`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get(); val root = TrackingRoot(activity)
        val snapshot = MobileCameoWindowInsets(Rect(0, 0, 390, 844), Rect(0, 28, 0, 24))
        MobileCameoInsetBinding(root, currentWindow = { snapshot }, fullWindowInsets = { insets(Rect()) })
        try {
            activity.setContentView(root); controller.visible(); layoutWindow(activity.window.decorView)
            placeInWindow(root, Rect(0, 0, 390, 844))
            root.dispatchApplyWindowInsets(insets(Rect()).consumeSystemWindowInsets())
            assertEquals(Rect(0, 28, 0, 24), padding(root))
            root.dispatchApplyWindowInsets(insets(Rect(0, 10, 0, 10)))
            assertEquals("Current window information wins over stale child data", Rect(0, 28, 0, 24), padding(root))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `window metrics fallback uses the new fold frame instead of stale decor dimensions`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get(); val root = TrackingRoot(activity)
        var snapshot = MobileCameoWindowInsets(Rect(0, 0, 390, 844), Rect(0, 28, 0, 24))
        MobileCameoInsetBinding(root, currentWindow = { snapshot }, fullWindowInsets = { insets(Rect()) })
        try {
            activity.setContentView(root); controller.visible(); layoutWindow(activity.window.decorView)
            placeInWindow(root, Rect(0, 0, 390, 844))
            assertEquals(Rect(0, 28, 0, 24), padding(root))
            snapshot = MobileCameoWindowInsets(Rect(0, 0, 320, 640), Rect(0, 28, 0, 24))
            placeInWindow(root, Rect(0, 0, 320, 640))
            assertEquals("A new window frame still protects the navigation edge", Rect(0, 28, 0, 24), padding(root))
            placeInWindow(root, Rect(0, 28, 320, 616))
            assertEquals("Already fitted content is not inset a second time", Rect(), padding(root))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `meaningful view root data does not request the fallback window service`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get(); val root = TrackingRoot(activity)
        var fallbackRequests = 0
        MobileCameoInsetBinding(root, currentWindow = { fallbackRequests++; null }, fullWindowInsets = { insets(Rect(0, 28, 0, 24)) })
        try {
            activity.setContentView(root); controller.visible(); layoutWindow(activity.window.decorView)
            placeInWindow(root, Rect(0, 0, 390, 844))
            root.dispatchApplyWindowInsets(insets(Rect()).consumeSystemWindowInsets())
            assertEquals(Rect(0, 28, 0, 24), padding(root))
            assertEquals("WindowMetrics is a bounded fallback", 0, fallbackRequests)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `padding discovered during layout schedules a new traversal that positions header and navigation`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get(); val root = TrackingRoot(activity)
        val header = View(activity).apply { tag = "mobile:header" }
        val navigation = View(activity).apply { tag = "mobile:bottom-nav" }
        val scrollingContent = View(activity)
        root.addView(header, FrameLayout.LayoutParams(-1, 48, Gravity.TOP))
        root.addView(scrollingContent, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 48; bottomMargin = 64 })
        root.addView(navigation, FrameLayout.LayoutParams(-1, 62, Gravity.BOTTOM))
        var full = insets(Rect())
        MobileCameoInsetBinding(root) { full }
        try {
            activity.setContentView(root); controller.visible(); layoutWindow(activity.window.decorView)
            placeInWindow(root, Rect(0, 0, 390, 844))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertEquals(0, header.top)
            assertEquals(root.height, navigation.bottom)
            // A changed frame makes the callback discover insets only after FrameLayout laid its children.
            placeInWindow(root, Rect(0, 0, 390, 843))
            full = insets(Rect(0, 28, 0, 24))
            root.layoutParams = FrameLayout.LayoutParams(390, 844).apply { leftMargin = root.left; topMargin = root.top }
            root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
            root.layout(root.left, root.top, root.left + 390, root.top + 844)
            shadowOf(Looper.getMainLooper()).idle()
            // Honor a genuinely requested second traversal; never force one merely to make the test pass.
            if (root.isLayoutRequested) {
                root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
                root.layout(root.left, root.top, root.left + 390, root.top + 844)
            }
            assertEquals(Rect(0, 28, 0, 24), Rect(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom))
            assertEquals("Header must actually move below the top inset", 28, header.top)
            assertEquals("Content must actually honor header plus inset", 76, scrollingContent.top)
            assertEquals("Navigation must actually move above the bottom inset", root.height - 24, navigation.bottom)
        } finally { controller.pause().stop().destroy() }
    }

    private fun padding(view: View): Rect {
        shadowOf(Looper.getMainLooper()).idle()
        return Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
    }

    @Suppress("DEPRECATION")
    private fun insets(bars: Rect): WindowInsets = WindowInsets::class.java.getDeclaredConstructor(Rect::class.java)
        .apply { isAccessible = true }.newInstance(bars)

    private fun layoutWindow(window: View) {
        repeat(2) {
            window.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
            window.layout(0, 0, 390, 844); shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Set real child bounds relative to the window, including an already inset content-parent case. */
    private fun placeInWindow(root: View, frame: Rect) {
        val windowLocation = IntArray(2); val parentLocation = IntArray(2)
        root.rootView.getLocationOnScreen(windowLocation)
        (root.parent as View).getLocationOnScreen(parentLocation)
        val left = windowLocation[0] + frame.left - parentLocation[0]
        val top = windowLocation[1] + frame.top - parentLocation[1]
        root.layoutParams = FrameLayout.LayoutParams(frame.width(), frame.height()).apply { leftMargin = left; topMargin = top }
        root.measure(View.MeasureSpec.makeMeasureSpec(frame.width(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.EXACTLY))
        root.layout(left, top, left + frame.width(), top + frame.height())
    }
}
