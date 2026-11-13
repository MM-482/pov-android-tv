package dev.pov.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar

/** All playback chrome lives under Media3's controller, including buffering status and feature actions. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object PlayerControlVisibility {
    fun bind(playerView: PlayerView): Binding = Binding(playerView)

    class Binding internal constructor(private val view: PlayerView) {
        private val handler = Handler(Looper.getMainLooper())
        private val subtitleView = view.subtitleView
        private val subtitlePadding = subtitleView?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
        private val controllerStrip = view.findViewById<View>(R.id.pov_bottom_scrim)
        private val timeBar = view.findViewById<View>(androidx.media3.ui.R.id.exo_progress) as? TimeBar
        private var active = true
        private var controllerVisible = view.isControllerFullyVisible
        private var touchHeld = false
        private var scrubbing = false
        private var heldControllerTimeout: Int? = null
        private var lastInteractionAt = SystemClock.elapsedRealtime()
        private val hide = Runnable {
            if (active && !touchHeld && !scrubbing && shouldAutoHide()) view.hideController()
        }
        private val controllerLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSubtitlePadding() }
        private val scrubListener = object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                if (!active) return
                scrubbing = true
                suspendHideWhileHeld()
            }
            override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (active) renewAfterRelease() else restoreControllerTimeout()
            }
        }

        init {
            view.isFocusableInTouchMode = CameoDevice.isTelevision(view.context)
            view.setControllerAnimationEnabled(false)
            view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                val visible = visibility == View.VISIBLE
                // A native touch reveal is fresh interaction even when our previous
                // idle deadline expired long ago. Repeated visibility/playback refreshes
                // while already visible must not renew that deadline.
                if (visible && !controllerVisible) lastInteractionAt = SystemClock.elapsedRealtime()
                controllerVisible = visible
                updateSubtitlePadding(visible)
                if (visible) {
                    if (touchHeld || scrubbing) suspendHideWhileHeld()
                    schedule()
                } else handler.removeCallbacks(hide)
            })
            controllerStrip?.addOnLayoutChangeListener(controllerLayout)
            timeBar?.addListener(scrubListener)
            refreshPlaybackState()
        }

        fun start() { active = true; lastInteractionAt = SystemClock.elapsedRealtime(); refreshPlaybackState() }
        fun stop() {
            active = false; touchHeld = false; scrubbing = false
            handler.removeCallbacks(hide); restoreControllerTimeout(); updateSubtitlePadding(false)
        }
        fun release() {
            stop()
            controllerStrip?.removeOnLayoutChangeListener(controllerLayout)
            timeBar?.removeListener(scrubListener)
            view.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
        }
        fun show() {
            lastInteractionAt = SystemClock.elapsedRealtime(); view.showController()
            if (touchHeld || scrubbing) suspendHideWhileHeld()
            schedule()
        }
        fun userInteraction() {
            // Activity handles some directional moves itself, before PlayerView can
            // reset Media3's own hide deadline. Refresh both timers for those moves.
            if (view.isControllerFullyVisible) show()
        }

        /** Observe Activity touch dispatch without consuming it or revealing hidden chrome. */
        fun touchInteraction(action: Int) {
            if (!active) return
            when (action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> { touchHeld = true; suspendHideWhileHeld() }
                MotionEvent.ACTION_MOVE -> if (touchHeld) suspendHideWhileHeld()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (touchHeld) {
                    touchHeld = false
                    renewAfterRelease()
                }
            }
        }

        private fun suspendHideWhileHeld() {
            handler.removeCallbacks(hide)
            if (view.isControllerFullyVisible && heldControllerTimeout == null) {
                heldControllerTimeout = view.controllerShowTimeoutMs
                // Media3 has its own deadline too. Keep both timers suspended for
                // a held touch; changing this setting never reveals a hidden view.
                view.controllerShowTimeoutMs = 0
            }
        }

        private fun restoreControllerTimeout() {
            val previous = heldControllerTimeout ?: return
            heldControllerTimeout = null
            view.controllerShowTimeoutMs = previous
        }

        private fun renewAfterRelease() {
            lastInteractionAt = SystemClock.elapsedRealtime()
            if (!touchHeld && !scrubbing) restoreControllerTimeout()
            schedule()
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
            if (active && !touchHeld && !scrubbing && view.isControllerFullyVisible && shouldAutoHide()) {
                val timeout = view.controllerShowTimeoutMs.takeIf { it > 0 } ?: 4_000
                val remaining = (timeout - (SystemClock.elapsedRealtime() - lastInteractionAt)).coerceAtLeast(1)
                handler.postDelayed(hide, remaining)
            }
        }
    }
}

