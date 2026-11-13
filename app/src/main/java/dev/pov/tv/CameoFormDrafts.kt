package dev.pov.tv

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Ephemeral form drafts for an in-place window/fold rebuild; never persisted or serialized. */
internal object CameoFormDrafts {
    internal class Snapshot internal constructor(internal val page: String?, internal val fields: List<Field>) {
        override fun toString() = "CameoFormDrafts.Snapshot"
    }
    internal class Field internal constructor(internal val key: Key, internal val value: String,
        internal val start: Int, internal val end: Int, internal val focused: Boolean) {
        override fun toString() = "CameoFormDrafts.Field"
    }
    internal data class Key(val tag: String?, val hint: String, val inputType: Int, val ordinal: Int)

    /** Supply the current form body, whose first non-input text is its page title. */
    fun capture(root: View): Snapshot {
        val inputs = descendants(root).filterIsInstance<EditText>()
        return Snapshot(page(root), inputs.mapIndexed { index, input ->
            Field(key(input, index), input.text.toString(), input.selectionStart, input.selectionEnd, input.hasFocus())
        })
    }

    fun restore(root: View, snapshot: Snapshot) {
        val inputs = descendants(root).filterIsInstance<EditText>()
        // A draft must never move into a different form, even when both contain password fields.
        if (snapshot.page.isNullOrBlank() || page(root) != snapshot.page ||
            inputs.mapIndexed(::keyAt) != snapshot.fields.map { it.key }) return
        inputs.zip(snapshot.fields).forEach { (input, field) ->
            input.setText(field.value)
            selection(input, field)
        }
        val focused = snapshot.fields.indexOfFirst { it.focused }
        if (focused >= 0) {
            inputs[focused].requestFocus()
            selection(inputs[focused], snapshot.fields[focused])
        }
    }

    private fun keyAt(index: Int, input: EditText) = key(input, index)
    private fun key(input: EditText, index: Int) = Key(input.tag?.let { tag ->
        when (tag) { is String -> tag; is Number -> tag.toString(); else -> null }
    }, input.hint?.toString().orEmpty(), input.inputType, index)

    private fun selection(input: EditText, field: Field) {
        if (field.start >= 0 && field.end >= 0) input.setSelection(field.start.coerceIn(0, input.length()), field.end.coerceIn(0, input.length()))
    }

    private fun page(root: View): String? = descendants(root).filterIsInstance<TextView>()
        .firstOrNull { it !is EditText && it !is Button && it.text.isNotBlank() }?.text?.toString()

    private fun descendants(root: View): List<View> {
        val views = mutableListOf<View>()
        fun visit(view: View) {
            views += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        return views
    }
}
