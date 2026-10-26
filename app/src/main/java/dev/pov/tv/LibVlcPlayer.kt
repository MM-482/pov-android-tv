package dev.pov.tv

import android.os.Looper
import android.os.Handler
import android.os.SystemClock
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal data class VlcTrack(val id: Int, val type: Int, val format: Format, val selected: Boolean)
internal enum class VlcEvent { OPENING, BUFFERING, READY, PAUSED, STOPPED, ENDED, ERROR, CHANGED, FIRST_FRAME, FOCUS_LOSS }

/** Small seam for testing control/state/surface handoffs without loading a native library. */
internal interface VlcPlaybackDriver {
    var listener: ((VlcEvent) -> Unit)?
    val position: Long
    val duration: Long
    val seekable: Boolean
    val buffering: Boolean
    val videoSize: VideoSize
    val renderedFrames: Long get() = 0L
    val actuallyPlaying: Boolean get() = false
    val surfacesAttached: Boolean get() = false
    val surfacesReady: Boolean get() = false
    val nativeVideoTrack: Int get() = -1
    val nativeVoutCount: Int get() = 0
    fun tracks(): List<VlcTrack>
    fun open(item: MediaItem, subtitleLanguage: String)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(position: Long)
    fun setVolume(volume: Float) {}
    fun selectTrack(type: Int, id: Int): Boolean
    fun selectAutomatic(type: Int, language: String)
    fun attach(view: SurfaceView)
    fun detach()
    fun addSubtitle(uri: android.net.Uri): Boolean
    fun release()
}

/**
 * LibVLC behind the same Media3 Player contract used by POV's UI and MediaSession.
 * Decoding and rendering are VLC's; this adapter exposes only operations it implements.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class LibVlcPlayer(
    private val driver: VlcPlaybackDriver,
    private val subtitleLanguage: String,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var item: MediaItem? = null
    private var requestedPlay = false
    private var prepared = false
    private var status = Player.STATE_IDLE
    private var failure: PlaybackException? = null
    private var parameters = TrackSelectionParameters.Builder().setPreferredTextLanguage(subtitleLanguage).build()
    private var pendingSeek: Long? = null
    private var output: Any? = null
    private var closed = false
    private var firstFrame = false
    private var normalVolume = 1f
    private var outputReady = false
    private var renderedFrameAnnounced = false
    private var pauseInFlight = false
    private var playInFlight = false
    private var pauseAcknowledged = false
    private var resumeAfterPauseAck = false
    private var settlingSeek: Long? = null
    private var seekFrameBaseline = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var readinessDeadline = 0L
    private val readinessCheck = object : Runnable {
        override fun run() {
            if (closed || !prepared || failure != null) return
            reconcileOutput()
            invalidateState()
            // Only poll initial output/resume acknowledgement, never issue repeated Play.
            // Later native events still reconcile if a slow source exceeds this polling window.
            if (!readyForUse() && SystemClock.elapsedRealtime() < readinessDeadline) handler.postDelayed(this, 50)
        }
    }
    internal val renderedOutputFrames: Long get() = driver.renderedFrames
    internal val nativeIsPlaying: Boolean get() = driver.actuallyPlaying
    internal val startupOutputReady: Boolean get() = outputReady
    internal val nativePausePending: Boolean get() = pauseInFlight
    internal val nativePlayPending: Boolean get() = playInFlight
    internal val startupSeekPending: Boolean get() = pendingSeek != null || settlingSeek != null
    internal val nativeSurfacesAttached: Boolean get() = driver.surfacesAttached
    internal val nativeSurfacesReady: Boolean get() = driver.surfacesReady
    internal val nativeVideoTrack: Int get() = driver.nativeVideoTrack
    internal val nativeVoutCount: Int get() = driver.nativeVoutCount

    init { driver.listener = ::onDriverEvent }

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_RELEASE, Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TRACKS,
            Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, Player.COMMAND_SET_VIDEO_SURFACE,
        ).addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, driver.seekable)
            .addIf(Player.COMMAND_SEEK_TO_DEFAULT_POSITION, driver.seekable)
            .addIf(Player.COMMAND_SEEK_BACK, driver.seekable)
            .addIf(Player.COMMAND_SEEK_FORWARD, driver.seekable).build()
        val playlist = item?.let {
            listOf(MediaItemData.Builder("pov-vlc-item").setMediaItem(it)
                .setMediaMetadata(it.mediaMetadata).setTracks(media3Tracks())
                .setDurationUs(if (driver.duration > 0) driver.duration * 1000 else C.TIME_UNSET)
                .setIsSeekable(driver.seekable).build())
        } ?: emptyList()
        return State.Builder().setAvailableCommands(commands).setPlaylist(playlist)
            .setPlayWhenReady(requestedPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (item == null) Player.STATE_IDLE else status)
            .setPlayerError(failure).setIsLoading(status == Player.STATE_BUFFERING)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .setTrackSelectionParameters(parameters).setVideoSize(driver.videoSize)
            .setVolume(normalVolume)
            .setNewlyRenderedFirstFrame(firstFrame.also { firstFrame = false })
            .setContentPositionMs(PositionSupplier { pendingSeek ?: settlingSeek ?: driver.position.coerceAtLeast(0) })
            .setContentBufferedPositionMs(PositionSupplier { driver.position.coerceAtLeast(0) })
            .build()
    }

    override fun handleSetMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        require(items.size == 1) { "VLC playback supports one active video" }
        item = items.single(); failure = null; prepared = false; status = Player.STATE_IDLE
        resetPreparation()
        // A fresh open already starts at zero. Seeking back to zero on the first Playing
        // callback needlessly resets an adaptive demux while its clock is initializing.
        pendingSeek = startPositionMs.takeIf { it > 0 }
        driver.open(items.single(), subtitleLanguage)
        return done()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        requestedPlay = playWhenReady
        if (prepared && failure == null) {
            if (playWhenReady && status == Player.STATE_ENDED) {
                resetPreparation()
                item?.let { driver.open(it, subtitleLanguage) }
                pendingSeek = null; status = Player.STATE_BUFFERING
                scheduleReadiness()
            }
            if (playWhenReady) {
                applyEffectiveVolume()
                if (pauseInFlight) {
                    // Acknowledge the queued Pause before fulfilling a newer explicit Play.
                    resumeAfterPauseAck = true
                } else {
                    requestNativePlay()
                    updateStatus()
                }
            } else {
                resumeAfterPauseAck = false
                applyEffectiveVolume()
                // Pausing before the first picture can freeze VLC's preparation pipeline.
                // Decode the first frame silently, then pause exactly once and await its ack.
                if (readyForUse()) requestNativePause()
                updateStatus()
            }
        }
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val current = item ?: return done()
        if (failure != null || status == Player.STATE_ENDED) driver.open(current, subtitleLanguage)
        resetPreparation()
        failure = null; prepared = true; status = Player.STATE_BUFFERING
        applyEffectiveVolume()
        requestNativePlay()
        scheduleReadiness()
        return done()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        normalVolume = volume.coerceIn(0f, 1f)
        applyEffectiveVolume()
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.coerceAtLeast(0).let { if (driver.duration > 0) it.coerceAtMost(driver.duration) else it }
        if (status == Player.STATE_ENDED) {
            resetPreparation()
            item?.let { driver.open(it, subtitleLanguage) }
            pendingSeek = target.takeIf { it > 0 }; prepared = true; status = Player.STATE_BUFFERING
            applyEffectiveVolume(); requestNativePlay(); scheduleReadiness()
            return done()
        }
        if (!prepared || !driver.seekable) pendingSeek = target else { pendingSeek = null; driver.seekTo(target) }
        return done()
    }

    override fun handleSetTrackSelectionParameters(value: TrackSelectionParameters): ListenableFuture<*> {
        // Reject stale groups rather than selecting an unrelated native track after a stream change.
        val current = media3Tracks()
        for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            if (type in value.disabledTrackTypes) {
                driver.selectTrack(type, -1)
            } else {
                val override = value.overrides.values.firstOrNull { it.type == type }
                if (override != null) {
                    val group = current.groups.firstOrNull { it.mediaTrackGroup == override.mediaTrackGroup }
                    val index = override.trackIndices.firstOrNull()
                    if (group != null && index != null && index in 0 until group.length) {
                        val id = group.getTrackFormat(index).id?.substringAfterLast(':')?.toIntOrNull()
                        if (id != null && !driver.selectTrack(type, id)) return done()
                    } else return done()
                } else driver.selectAutomatic(type, if (type == C.TRACK_TYPE_TEXT)
                    value.preferredTextLanguages.firstOrNull() ?: subtitleLanguage else "")
            }
        }
        parameters = value
        return done()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        require(videoOutput is SurfaceView) { "POV VLC requires a SurfaceView video output" }
        if (output !== videoOutput) { driver.detach(); output = videoOutput; driver.attach(videoOutput) }
        return done()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        // PlayerView switches the new target before clearing the old target.
        if (videoOutput == null || output === videoOutput) { driver.detach(); output = null }
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        resetPreparation()
        requestedPlay = false; prepared = false; status = Player.STATE_IDLE; driver.stop()
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacks(readinessCheck)
        closed = true; driver.listener = null; driver.detach(); output = null; driver.release()
        return done()
    }

    fun addSubtitle(uri: android.net.Uri): Boolean {
        if (closed || item == null) return false
        val added = driver.addSubtitle(uri)
        if (added) {
            item = item?.buildUpon()?.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(uri).build()))?.build()
            parameters = parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            invalidateState()
        }
        return added
    }

    private fun onDriverEvent(event: VlcEvent) {
        if (closed || !prepared) return
        // Native pause/track events can arrive after an error; they cannot revive a failed session.
        if (failure != null && event != VlcEvent.ERROR) return
        when (event) {
            VlcEvent.OPENING -> Unit
            VlcEvent.READY -> { playInFlight = false; pauseAcknowledged = false }
            VlcEvent.BUFFERING -> Unit
            VlcEvent.PAUSED -> {
                val expectedPause = pauseInFlight
                pauseInFlight = false
                playInFlight = false
                pauseAcknowledged = true
                val resume = resumeAfterPauseAck && requestedPlay
                resumeAfterPauseAck = false
                if (resume) {
                    applyEffectiveVolume()
                    requestNativePlay() // One deferred explicit Play, never an unsolicited retry loop.
                } else if (requestedPlay && !expectedPause) {
                    // Native/system pause without a pending app command is an actual pause.
                    requestedPlay = false
                }
                applyEffectiveVolume()
            }
            VlcEvent.ENDED -> { status = Player.STATE_ENDED; playInFlight = false; handler.removeCallbacks(readinessCheck) }
            VlcEvent.ERROR -> {
                requestedPlay = false; status = Player.STATE_IDLE
                playInFlight = false
                handler.removeCallbacks(readinessCheck)
                failure = PlaybackException("VLC could not play this video", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
            }
            VlcEvent.FOCUS_LOSS -> {
                requestedPlay = false; resumeAfterPauseAck = false
                playInFlight = false
                // Audio focus must be honored even before initial video output exists.
                requestNativePause()
            }
            VlcEvent.FIRST_FRAME -> {
                // Once this decoder has produced output, a borrowed target's Vout/layout
                // notification must also release that PlayerView's shutter on a handoff.
                // Initial preparation still requires displayed-picture statistics below.
                if (readyForUse()) firstFrame = true
            }
            VlcEvent.STOPPED, VlcEvent.CHANGED -> Unit
        }
        if (failure == null && status != Player.STATE_ENDED) reconcileOutput()
        invalidateState()
    }

    private fun resetPreparation() {
        handler.removeCallbacks(readinessCheck)
        outputReady = false; renderedFrameAnnounced = false
        pauseInFlight = false; playInFlight = false; pauseAcknowledged = false; resumeAfterPauseAck = false
        settlingSeek = null; firstFrame = false
    }

    private fun scheduleReadiness() {
        readinessDeadline = SystemClock.elapsedRealtime() + 15_000
        handler.removeCallbacks(readinessCheck)
        handler.postDelayed(readinessCheck, 50)
    }

    private fun readyForUse() = outputReady && pendingSeek == null && settlingSeek == null

    private fun applyEffectiveVolume() {
        val preparingPaused = prepared && !requestedPlay && !readyForUse()
        driver.setVolume(if (preparingPaused) 0f else normalVolume)
    }

    private fun requestNativePause() {
        if (pauseInFlight || pauseAcknowledged || !driver.actuallyPlaying) return
        pauseInFlight = true
        driver.pause()
    }

    private fun requestNativePlay() {
        if (playInFlight || driver.actuallyPlaying) return
        pauseAcknowledged = false
        playInFlight = true
        driver.play()
    }

    private fun reconcileOutput() {
        if (closed || !prepared || failure != null) return
        val frames = driver.renderedFrames
        if (!outputReady && frames > 0) outputReady = true
        val target = pendingSeek
        if (outputReady && target != null && driver.seekable && driver.actuallyPlaying) {
            pendingSeek = null; settlingSeek = target; seekFrameBaseline = frames
            driver.seekTo(target)
            updateStatus()
            return
        }
        settlingSeek?.let { seek ->
            val newPicture = frames > seekFrameBaseline || (frames in 1 until seekFrameBaseline)
            if (newPicture && kotlin.math.abs(driver.position - seek) <= 1_000) settlingSeek = null
        }
        if (readyForUse()) {
            if (!renderedFrameAnnounced) { renderedFrameAnnounced = true; firstFrame = true }
            handler.removeCallbacks(readinessCheck)
            if (!requestedPlay) requestNativePause()
        }
        updateStatus()
    }

    private fun updateStatus() {
        if (failure != null || status == Player.STATE_ENDED || !prepared) return
        status = when {
            !readyForUse() || pauseInFlight -> Player.STATE_BUFFERING
            requestedPlay && (!driver.actuallyPlaying || driver.buffering) -> Player.STATE_BUFFERING
            else -> Player.STATE_READY // Late Buffering must not undo an acknowledged paused READY.
        }
    }

    private fun media3Tracks(): Tracks = Tracks(driver.tracks().map { track ->
        val format = track.format.buildUpon().setId("vlc:${track.type}:${track.id}").build()
        val group = TrackGroup("vlc:${track.type}:${track.id}", format)
        Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(track.selected))
    })

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
