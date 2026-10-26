package dev.pov.tv

import android.view.View
import android.view.ViewGroup
import android.graphics.Rect
import android.widget.HorizontalScrollView
import java.lang.ref.WeakReference

/** The video surface never owns remote focus. The mini card is a reachable action at content edges. */
internal class MiniPlayerFocusRouter(private val content: ViewGroup, private val mini: ViewGroup) {
    private var previous: WeakReference<View>? = null

    init {
        mini.isFocusable = true
        mini.isFocusableInTouchMode = true
        mini.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    fun route(direction: Int, focused: View?): Boolean {
        if (!mini.isShown || !mini.isEnabled || focused == null) return false
        if (focused === mini) {
            if (direction == View.FOCUS_LEFT || direction == View.FOCUS_UP) {
                val target = previous?.get()?.takeIf { it.isShown && it.isEnabled && it.isFocusable && inside(it, content) }
                    ?: content.getFocusables(View.FOCUS_FORWARD).firstOrNull { it !== content && it.isShown && it.isEnabled }
                return target?.requestFocus() == true
            }
            return direction == View.FOCUS_RIGHT || direction == View.FOCUS_DOWN
        }
        if (!inside(focused, content) || direction !in listOf(View.FOCUS_RIGHT, View.FOCUS_DOWN)) return false
        if (direction == View.FOCUS_RIGHT) {
            val row = horizontalScroller(focused)
            if (row != null) {
                // Global focus search can see offscreen cards belonging to another horizontal row.
                // The current row owns Right until its final item, including items it must scroll to.
                if (hasItemToRight(row, focused)) return false
                previous = WeakReference(focused)
                return mini.requestFocus()
            }
        }
        val next = TvChoiceList.findNext(content, focused, direction)
        if (next != null && next !== focused && next !== content && inside(next, content) && next.isShown && next.isFocusable) return false
        previous = WeakReference(focused)
        return mini.requestFocus()
    }

    private fun horizontalScroller(view: View): HorizontalScrollView? {
        var parent = view.parent as? View
        while (parent != null && parent !== content) {
            if (parent is HorizontalScrollView) return parent
            parent = parent.parent as? View
        }
        return null
    }

    private fun hasItemToRight(row: HorizontalScrollView, focused: View): Boolean {
        val origin = Rect(0, 0, focused.width, focused.height)
        row.offsetDescendantRectToMyCoords(focused, origin)
        return row.getFocusables(View.FOCUS_FORWARD).any { candidate ->
            if (candidate === row || candidate === focused || !candidate.isShown || !candidate.isEnabled ||
                !candidate.isFocusable || candidate.width <= 0) return@any false
            val bounds = Rect(0, 0, candidate.width, candidate.height)
            row.offsetDescendantRectToMyCoords(candidate, bounds)
            bounds.centerX() > origin.centerX()
        }
    }

    private fun inside(view: View, parent: ViewGroup): Boolean {
        var item: View? = view
        while (item != null) {
            if (item === parent) return true
            item = item.parent as? View
        }
        return false
    }
}
