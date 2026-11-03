package dev.pov.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

/** Window dimensions are dp in the current app window, never device-model assumptions. */
data class AdaptiveWindow(val widthDp: Int, val heightDp: Int, val isTelevision: Boolean) {
    val widthClass: WindowWidthClass get() = when {
        widthDp < 600 -> WindowWidthClass.COMPACT
        widthDp < 840 -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.EXPANDED
    }
    /** Touch navigation stays in the same place when a foldable opens or the window widens. */
    val useBottomNavigation: Boolean get() = !isTelevision
    val horizontalInsetDp: Int get() = if (widthDp >= 840) 28 else 16
    val contentWidthDp: Int get() = (widthDp - horizontalInsetDp * 2).coerceAtLeast(1)
    val splitFeature: Boolean get() = !isTelevision && contentWidthDp >= 560
    fun gridColumns(minimumCardWidthDp: Int = 148, gapDp: Int = 12): Int =
        ((contentWidthDp + gapDp) / (minimumCardWidthDp.coerceAtLeast(1) + gapDp)).coerceIn(1, 6)
}

object AdaptiveLayout {
    fun isTelevision(context: Context): Boolean {
        val configured = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return configured == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun snapshot(context: Context): AdaptiveWindow {
        val resources = context.resources
        val configuration = resources.configuration
        val density = resources.displayMetrics.density.coerceAtLeast(.1f)
        val width = configuration.screenWidthDp.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels / density).roundToInt()
        val height = configuration.screenHeightDp.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels / density).roundToInt()
        return AdaptiveWindow(width.coerceAtLeast(1), height.coerceAtLeast(1), isTelevision(context))
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
