package dev.pov.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/** All playback chrome lives under Media3's controller, including buffering status and feature actions. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object PlayerControlVisibility {
    fun bind(playerView: PlayerView): Binding = Binding(playerView)

    class Binding internal constructor(private val view: PlayerView) {
        private val handler = Handler(Looper.getMainLooper())
        private val subtitleView = view.subtitleView
        private val subtitlePadding = subtitleView?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
        private val controllerStrip = view.findViewById<View>(R.id.pov_bottom_scrim)
        private var active = true
        private var touchInProgress = false
        private var timeoutBeforeTouch: Int? = null
        private var lastInteractionAt = SystemClock.elapsedRealtime()
        private val hide = Runnable {
            if (active && !touchInProgress && shouldAutoHide()) view.hideController()
        }
        private val controllerLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSubtitlePadding() }

        init {
            view.setControllerAnimationEnabled(false)
            view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                updateSubtitlePadding(visibility == View.VISIBLE)
                if (visibility == View.VISIBLE) {
                    // PlayerView also reveals controls itself for touch/accessibility input.
                    // Reusing the expired app deadline hides that reveal again after 1 ms.
                    lastInteractionAt = SystemClock.elapsedRealtime()
                    schedule()
                } else handler.removeCallbacks(hide)
            })
            controllerStrip?.addOnLayoutChangeListener(controllerLayout)
            refreshPlaybackState()
        }

        fun start() {
            touchInProgress = false
            restoreTouchTimeout()
            active = true
            lastInteractionAt = SystemClock.elapsedRealtime()
            refreshPlaybackState()
        }
        fun stop() {
            active = false
            touchInProgress = false
            restoreTouchTimeout()
            handler.removeCallbacks(hide)
            updateSubtitlePadding(false)
        }
        fun release() {
            stop()
            controllerStrip?.removeOnLayoutChangeListener(controllerLayout)
            view.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
        }
        fun show() { lastInteractionAt = SystemClock.elapsedRealtime(); view.showController(); schedule() }
        fun userInteraction() {
            // Activity handles some directional moves itself, before PlayerView can
            // reset Media3's own hide deadline. Refresh both timers for those moves.
            if (view.isControllerFullyVisible) show()
        }

        /** Observe the gesture without consuming it or duplicating PlayerView's click toggle. */
        fun touchInteraction(event: MotionEvent) {
            if (!active) return
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!touchInProgress) {
                        timeoutBeforeTouch = view.controllerShowTimeoutMs
                        // Hold Media3's timer too, including state-driven showController()
                        // calls during the gesture. Keeping the configured value at zero
                        // prevents those calls from rearming a hide under the user's finger.
                        view.controllerShowTimeoutMs = 0
                    }
                    touchInProgress = true
                    userInteraction()
                    handler.removeCallbacks(hide)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchInProgress = false
                    restoreTouchTimeout()
                    userInteraction()
                }
            }
        }

        private fun restoreTouchTimeout() {
            val timeout = timeoutBeforeTouch ?: return
            timeoutBeforeTouch = null
            view.controllerShowTimeoutMs = timeout
        }

        fun refreshPlaybackState() {
            updateSubtitlePadding()
            view.findViewById<View>(R.id.pov_buffering_status)?.visibility =
                if (view.player?.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            // This timeout is independent of focused descendants. Merely focusing Audio/Boost must not pin chrome.
            schedule()
        }

        private fun updateSubtitlePadding(visible: Boolean = view.isControllerFullyVisible) {
            val subtitles = subtitleView ?: return
            val original = subtitlePadding ?: return
            // Reserve the measured playback strip only while it is visible. This affects
            // Media3's subtitle viewport; VLC's separate native subtitle surface is untouched.
            val stripHeight = controllerStrip?.let { it.height.takeIf { height -> height > 0 } ?: it.layoutParams?.height?.coerceAtLeast(0) ?: 0 } ?: 0
            val gap = (8 * view.resources.displayMetrics.density).toInt()
            val bottom = original[3] + if (active && visible && stripHeight > 0) stripHeight + gap else 0
            if (subtitles.paddingLeft != original[0] || subtitles.paddingTop != original[1] ||
                subtitles.paddingRight != original[2] || subtitles.paddingBottom != bottom) {
                subtitles.setPadding(original[0], original[1], original[2], bottom)
            }
        }

        private fun shouldAutoHide(): Boolean {
            val player = view.player ?: return false
            return player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        }

        private fun schedule() {
            handler.removeCallbacks(hide)
            if (active && !touchInProgress && view.isControllerFullyVisible && shouldAutoHide()) {
                val timeout = view.controllerShowTimeoutMs.takeIf { it > 0 } ?: 4_000
                val remaining = (timeout - (SystemClock.elapsedRealtime() - lastInteractionAt)).coerceAtLeast(1)
                handler.postDelayed(hide, remaining)
            }
        }
    }
}

