package dev.pov.tv

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameoFormDraftsTest {
    private fun form(activity: Activity, title: String, hint: String = "Access token", tag: String = "credential") =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply { text = title })
            addView(EditText(activity).apply { this.hint = hint; this.tag = tag; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD })
        }

    @Test fun `private draft cannot be restored into a different page even with identical fields`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val original = form(activity, "Connect first service")
        (original.getChildAt(1) as EditText).setText("fixture-private-draft")
        val snapshot = CameoFormDrafts.capture(original)
        val other = form(activity, "Connect another service")
        CameoFormDrafts.restore(other, snapshot)
        assertEquals("", (other.getChildAt(1) as EditText).text.toString())
    }

    @Test fun `same title requires matching field hints tags order and type before any restore`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val original = form(activity, "Connect service")
        (original.getChildAt(1) as EditText).setText("fixture-private-draft")
        val snapshot = CameoFormDrafts.capture(original)
        listOf(form(activity, "Connect service", hint = "A different token"), form(activity, "Connect service", tag = "different"),
            form(activity, "Connect service").apply { (getChildAt(1) as EditText).inputType = InputType.TYPE_CLASS_NUMBER },
            form(activity, "Connect service").apply { addView(EditText(activity).apply { hint = "Another field" }) }).forEach { other ->
            CameoFormDrafts.restore(other, snapshot)
            assertEquals("", (other.getChildAt(1) as EditText).text.toString())
        }
    }
}
