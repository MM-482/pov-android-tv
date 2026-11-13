package dev.pov.tv

import android.app.Activity
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.util.Rational
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.exoplayer.ExoPlayer
import dev.pov.tv.subtitles.SubtitleSearchDialog
import dev.pov.tv.data.CatalogApi
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import java.util.Locale
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerActivity : Activity() {
    private lateinit var view: PlayerView
    private lateinit var controls: PlayerControlVisibility.Binding
    private lateinit var boostButton: Button
    private var activeDialog: Dialog? = null
    private var activeTrackType: Int? = null
    private var activeTrackChoices: List<PlayerTrackChoice> = emptyList()
    private var activeTracksLoading = false
    internal val currentOptionSheet: Dialog? get() = activeDialog
    private val player get() = InAppPlaybackSession.currentPlayer
    private val subtitleWorker = Executors.newSingleThreadExecutor()
    private val recommendationWorker = Executors.newSingleThreadExecutor()
    private var recommendationPlayer: Player? = null
    private var recommendation: PlaybackIdentity? = null
    private var recommendationRequested = false
    private val boost get() = InAppPlaybackSession.volumeBoost
    private val accent by lazy {
        if (CameoDevice.isTelevision(this)) ThemePreferences(this).selected().playbackAccent
        else {
            val selected = runCatching { getSharedPreferences("cameo_mobile_appearance", MODE_PRIVATE).getString("theme", "GLASS") }.getOrNull()
            (MobileCameoTheme.entries.firstOrNull { it.name == selected } ?: MobileCameoTheme.GLASS).let {
                if (it == MobileCameoTheme.PEARL) it.foundation.playbackAccent else it.palette.accent
            }
        }
    }
    private val health = PlaybackHealthMonitor()
    private val healthHandler = Handler(Looper.getMainLooper())
    private var healthIssueShown = false
    private var healthRenderer: String? = null
    private var castScan: VlcRendererScan? = null
    private var castOwner: LibVlcPlayer? = null
    private var replacingCastSheet = false
    private var castSheet: Dialog? = null
    private val healthCheck = object : Runnable {
        override fun run() {
            if (stopped || isFinishing || isDestroyed) return
            val current = player
            val remoteOutput = (current as? LibVlcPlayer)?.remoteOutputName
            if (healthRenderer != remoteOutput) { health.reset(); healthRenderer = remoteOutput }
            if (isInPictureInPictureMode) {
                health.reset()
                healthHandler.postDelayed(this, 1_000L)
                return
            }
            if (current != null && healthIssueShown && current.playWhenReady && activeDialog?.isShowing != true) {
                health.reset(); healthIssueShown = false
            }
            if (current != null && !healthIssueShown && InAppPlaybackSession.error == null) {
                val frames = if (remoteOutput != null) null else when (current) {
                    is LibVlcPlayer -> current.renderedOutputFrames
                    is ExoPlayer -> current.videoDecoderCounters?.renderedOutputBufferCount?.toLong()
                    else -> null
                }
                val issue = health.sample(PlaybackHealthSample(SystemClock.elapsedRealtime(),
                    current.playWhenReady && current.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                    current.playbackState == Player.STATE_BUFFERING, current.playbackState == Player.STATE_ENDED,
                    current.currentPosition.coerceAtLeast(0L), remoteOutput == null && current.videoSize.width > 0 && current.videoSize.height > 0, frames))
                if (issue != null) showHealthFailure(current, issue)
            }
            if (current != null && current.isPlaying && !healthIssueShown) maybeShowUpNext(current)
            healthHandler.postDelayed(this, 1_000L)
        }
    }
    private var stopped = false
    private var revealKey = KeyEvent.KEYCODE_UNKNOWN
    private var observedError: PlaybackException? = null
    private var noticeShown = false
    private val rowIds = listOf(androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause,
        R.id.pov_stop, androidx.media3.ui.R.id.exo_ffwd, R.id.pov_audio, R.id.pov_subtitles, R.id.pov_boost, R.id.pov_browse,
        R.id.pov_more)
    private val sessionListener: () -> Unit = {
        if (!stopped) {
            updateControls()
            activeTrackType?.let { type ->
                player?.let { current ->
                    val loading = current.playbackState == Player.STATE_IDLE || current.playbackState == Player.STATE_BUFFERING
                    if (activeDialog?.isShowing == true && (loading != activeTracksLoading ||
                        PlayerTrackOptions.choices(current.currentTracks, current.trackSelectionParameters, type) != activeTrackChoices)) {
                        showTracks(type)
                    }
                }
            }
            val failure = InAppPlaybackSession.error
            if (failure != null && observedError !== failure) showPlaybackFailure(failure)
            if (!InAppPlaybackSession.hasActive()) finish()
        }
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (::controls.isInitialized) controls.touchInteraction(event.actionMasked)
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        InAppPlaybackSession.initialize(this)
        view = LayoutInflater.from(this).inflate(R.layout.pov_player, null, false) as PlayerView
        setContentView(view)
        controls = PlayerControlVisibility.bind(view)
        configureButtons()
        val reuse = InAppPlaybackSession.hasActive() &&
            (intent.getBooleanExtra("reuse_session", false) || (savedInstanceState != null && InAppPlaybackSession.matches(intent)))
        if (!reuse && !InAppPlaybackSession.start(this, intent, savedInstanceState)) {
            showSheet("Cannot open video", InAppPlaybackSession.startFailure ?: "This playback link is invalid. Choose the source again.",
                listOf(PlayerSheetChoice("Back to browsing") { finish() }))
        }
    }

    private fun configureButtons() {
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(accent, 0xFF697D91.toInt(), 0xFFE3E8EF.toInt())
        )
        rowIds.forEachIndexed { index, id ->
            val button = view.findViewById<View>(id)
            button.isFocusable = true
            button.isFocusableInTouchMode = CameoDevice.isTelevision(this)
            button.nextFocusUpId = androidx.media3.ui.R.id.exo_progress
            button.nextFocusLeftId = rowIds[(index - 1).coerceAtLeast(0)]
            button.nextFocusRightId = rowIds[(index + 1).coerceAtMost(rowIds.lastIndex)]
            fun style(focused: Boolean) {
                button.background = GradientDrawable().apply {
                    setColor(if (focused) 0x22FFFFFF else Color.TRANSPARENT)
                    cornerRadius = dp(6).toFloat()
                    if (focused) setStroke(dp(1), accent)
                }
                if (button is TextView) {
                    button.setTextColor(if (focused) Color.WHITE else 0xFFE3E8EF.toInt())
                    button.compoundDrawableTintList = tint
                }
                if (button is ImageView) button.imageTintList = tint
            }
            style(false)
            button.setOnFocusChangeListener { _, focused -> style(focused) }
        }
        boostButton = view.findViewById(R.id.pov_boost)
        view.findViewById<View>(R.id.pov_audio).setOnClickListener { showTracks(C.TRACK_TYPE_AUDIO) }
        view.findViewById<View>(R.id.pov_subtitles).setOnClickListener { showTracks(C.TRACK_TYPE_TEXT) }
        boostButton.setOnClickListener { showBoost() }
        view.findViewById<View>(R.id.pov_browse).setOnClickListener { finish() }
        view.findViewById<View>(R.id.pov_more).setOnClickListener { showOptionsMenu() }
        view.findViewById<View>(R.id.pov_stop).setOnClickListener { InAppPlaybackSession.stop(); finish() }
        view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress).apply {
            setPlayedColor(accent)
            nextFocusDownId = androidx.media3.ui.R.id.exo_play_pause
            nextFocusUpId = id
        }
        view.findViewById<ProgressBar>(R.id.pov_loading_spinner).indeterminateTintList = ColorStateList.valueOf(accent)
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        health.reset(); healthIssueShown = false
        healthHandler.removeCallbacks(healthCheck)
        healthHandler.postDelayed(healthCheck, 1_000L)
        InAppPlaybackSession.addListener(sessionListener)
        InAppPlaybackSession.attachPlayerView(view)
        applyCaptionAppearance()
        controls.start()
        updateControls()
        InAppPlaybackSession.error?.let { if (observedError !== it) showPlaybackFailure(it) }
        controls.show()
        InAppPlaybackSession.engineNotice?.takeIf { !noticeShown }?.let {
            noticeShown = true; Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateControls() {
        view.findViewById<TextView>(R.id.pov_playback_title).text =
            "${InAppPlaybackSession.title.ifBlank { "Now playing" }} · ${InAppPlaybackSession.engine.label}" +
                ((player as? LibVlcPlayer)?.remoteOutputName?.let { " · Cast: $it" } ?: "")
        boostButton.text = when {
            !InAppPlaybackSession.supportsVolumeBoost -> "Boost · Unavailable"
            boost.gainDb == 0 -> "Boost · Off"
            boost.state == VolumeBoostState.UNAVAILABLE -> "Boost · Unavailable"
            else -> "Boost · +${boost.gainDb} dB"
        }
        controls.refreshPlaybackState()
    }

    private fun showPlaybackFailure(error: PlaybackException) {
        if (stopped || isFinishing || isDestroyed) return
        observedError = error
        if (error is VideoCompatibilityException) {
            showSheet("Choose a compatible video", error.issue.message,
                listOf(PlayerSheetChoice("Choose another source") { InAppPlaybackSession.stop(); finish() }))
            return
        }
        val scopedFailure = generateSequence(error.cause) { it.cause }.take(8)
            .filterIsInstance<OriginBoundMediaException>().firstOrNull()?.message
        showSheet("Playback stopped",
            scopedFailure ?: "The link may have expired, the connection may be unavailable, or this device may not support the format. Code: ${error.errorCodeName}",
            listOf(
                PlayerSheetChoice("Retry") { if (!stopped) { observedError = null; InAppPlaybackSession.retry() } },
                PlayerSheetChoice("Browse sources") { finish() }
            ))
    }

    private fun showHealthFailure(current: Player, issue: PlaybackHealthIssue) {
        if (player !== current || stopped) return
        healthIssueShown = true
        current.pause()
        val message = when (issue) {
            PlaybackHealthIssue.OPENING_TIMEOUT -> "This source has not produced playable output within 30 seconds. It may be slow, unavailable, or unsupported."
            PlaybackHealthIssue.BUFFERING_TIMEOUT -> "This source has remained buffering for 90 seconds. Check the network, retry, or choose a fresh source."
            PlaybackHealthIssue.VIDEO_OUTPUT_STALLED -> "The decoder has not reported a new video picture for 45 seconds. The picture may be frozen even if audio or the timeline continues. Some sources have long still images; you can continue waiting."
            PlaybackHealthIssue.MEDIA_CLOCK_STALLED -> "The playback clock has not advanced for 45 seconds while playback was requested. The source or decoder may have stopped responding."
        }
        showSheet("Playback needs attention", message, listOf(
            PlayerSheetChoice("Retry this source") {
                health.reset(); healthIssueShown = false
                if (player === current) InAppPlaybackSession.retry()
            },
            PlayerSheetChoice("Continue waiting") {
                health.reset(); healthIssueShown = false
                if (player === current) current.play()
            },
            PlayerSheetChoice("Choose another source") { InAppPlaybackSession.stop(); finish() },
        ))
    }

    private fun showTracks(type: Int) {
        val exo = player ?: return
        val title = if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles"
        val choices = PlayerTrackOptions.choices(exo.currentTracks, exo.trackSelectionParameters, type)
        val loading = exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_BUFFERING
        val detail = PlayerTrackOptions.description(choices, type, loading)
        val rows = choices.map { choice -> PlayerSheetChoice(choice.label, choice.checked, selectable = choice.selectable) {
            if (player === exo && !stopped) {
                val stillAvailable = choice.kind != TrackChoiceKind.TRACK || exo.currentTracks.groups.any {
                    it.mediaTrackGroup == choice.group && choice.index in 0 until it.length && it.isTrackSupported(choice.index)
                }
                if (stillAvailable) {
                    exo.trackSelectionParameters = PlayerTrackOptions.apply(exo.trackSelectionParameters, choice)
                    if (type == C.TRACK_TYPE_TEXT) InAppPlaybackSession.rememberSubtitleEnabled(choice.kind != TrackChoiceKind.OFF)
                }
                else showSheet(title, "Tracks changed. Open this menu again to choose a current track.",
                    listOf(PlayerSheetChoice("Close") {}))
            }
        } }.toMutableList()
        if (type == C.TRACK_TYPE_TEXT) {
            rows += PlayerSheetChoice("Subtitle appearance") { showCaptionAppearance() }
            rows += PlayerSheetChoice("Subtitle timing") { showSubtitleTiming() }
            rows += PlayerSheetChoice("Load SRT / VTT / ASS / SSA / SUB file") { chooseSubtitleFile() }
            rows += PlayerSheetChoice("Find subtitles online") { searchSubtitles() }
        }
        showSheet(title, detail, rows)
        activeTrackType = type
        activeTrackChoices = choices
        activeTracksLoading = loading
    }

    private fun showBoost() {
        if (!InAppPlaybackSession.supportsVolumeBoost) {
            showSheet("Volume boost", "Software volume boost is available with the Media3 engine. VLC currently uses its normal audio output. TV volume controls remain available.",
                listOf(PlayerSheetChoice("Close") {}))
            return
        }
        showSheet("Volume boost",
            "Boost decoded PCM audio in software for this playback session. Encoded passthrough bypasses it. Off restores normal audio.",
            SessionVolumeBoost.STEPS.map { db -> PlayerSheetChoice(
                if (db == 0) "Off · Normal audio" else "+$db dB", boost.gainDb == db
            ) {
                if (player != null && !stopped) {
                    val state = InAppPlaybackSession.setVolumeBoostGain(db)
                    updateControls()
                    val result = when (state) {
                        VolumeBoostState.OFF -> "Volume boost off"
                        VolumeBoostState.WAITING_FOR_AUDIO -> "Software boost will start when decoded audio flows"
                        VolumeBoostState.UNAVAILABLE -> "Software boost is unavailable on this audio route"
                        VolumeBoostState.REQUESTED -> "+${boost.gainDb} dB software boost active"
                    }
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                }
            } })
    }

    private fun showOptionsMenu() {
        showSheet("Playback options", listOfNotNull(InAppPlaybackSession.engineNotice,
            "Engine: ${InAppPlaybackSession.engine.label}. Choose an action. Playback continues while you browse.").joinToString("\n"),
            buildList {
                val vlc = player as? LibVlcPlayer
                if (vlc != null && (vlc.remoteOutputName != null || vlc.rendererSupported)) add(PlayerSheetChoice(
                    if (vlc.remoteOutputName != null) "Cast · ${vlc.remoteOutputName}" else "Cast to a display") { showCastOptions() })
                if (CameoPlaybackQueue.hasPrevious()) add(PlayerSheetChoice("Previous playlist item") {
                    if (CameoPlaybackQueue.previous()) finish()
                })
                if (CameoPlaybackQueue.hasNext()) add(PlayerSheetChoice("Next playlist item") {
                    if (CameoPlaybackQueue.next()) finish()
                })
                addAll(listOf(
                PlayerSheetChoice("Audio") { showTracks(C.TRACK_TYPE_AUDIO) },
                PlayerSheetChoice("Subtitles") { showTracks(C.TRACK_TYPE_TEXT) },
                PlayerSheetChoice("Playback speed") { showSpeed() },
                PlayerSheetChoice("Chapters") { showChapters() },
                PlayerSheetChoice("Sleep timer") { showSleepTimer() },
                PlayerSheetChoice("Playback details") { showPlaybackDetails() },
                PlayerSheetChoice("Volume") { showVolume() },
                PlayerSheetChoice("Volume boost") { showBoost() },
                PlayerSheetChoice("Browse while playing") { finish() },
                PlayerSheetChoice("System picture-in-picture") { enterSystemPictureInPicture() },
                PlayerSheetChoice("Stop playback") { InAppPlaybackSession.stop(); finish() }
                ))
            })
    }

    private fun showCastOptions() {
        val current = player as? LibVlcPlayer ?: return
        if (InAppPlaybackSession.hasProtectedHeaders) {
            showSheet("Cast unavailable for this source", "This source uses protected request headers. Keep it on this device; Cameo does not send account headers to a receiver.",
                listOf(PlayerSheetChoice("Close") {}))
            return
        }
        if (current.remoteOutputName != null) {
            showSheet("Cast · ${current.remoteOutputName}", "The VLC output route is selected. Receiver playback depends on the device, network and media format.", listOf(
                PlayerSheetChoice("Return playback to this device") {
                    if (player === current && current.disconnectRenderer()) { health.reset(); updateControls() }
                    else showSheet("Could not disconnect", "The current playback route could not be changed. Stop playback if the receiver is unavailable.",
                        listOf(PlayerSheetChoice("Stop playback") { InAppPlaybackSession.stop(); finish() }))
                },
                PlayerSheetChoice("Choose another display") { beginCastDiscovery(current) },
                PlayerSheetChoice("Close") {},
            ))
        } else beginCastDiscovery(current)
    }

    private fun beginCastDiscovery(current: LibVlcPlayer) {
        closeCastDiscovery()
        if (player !== current || stopped || InAppPlaybackSession.hasProtectedHeaders) return
        castOwner = current
        castScan = current.discoverRenderers({ snapshot ->
            if (castOwner === current && player === current && !stopped) renderCastPicker(current, snapshot)
        }, { message ->
            if (castOwner === current) {
                closeCastDiscovery()
                showSheet("Cast discovery unavailable", message, listOf(PlayerSheetChoice("Close") {}))
            }
        })
    }

    private fun renderCastPicker(current: LibVlcPlayer, snapshot: VlcRendererSnapshot) {
        val focusedReceiver = castSheet?.currentFocus?.contentDescription?.toString()
        replacingCastSheet = true
        showSheet("Choose a Cast display", if (snapshot.searching)
            "Searching for video-capable Chromecast displays for up to 30 seconds. Use the same local network. This does not confirm receiver playback."
            else "Search finished. Choose a display or search again. Some networks block discovery.", buildList {
                snapshot.choices.forEach { receiver -> add(PlayerSheetChoice(receiver.name) {
                    val allowed = player === current && !stopped && !InAppPlaybackSession.hasProtectedHeaders
                    val accepted = allowed && current.selectRenderer(receiver.id)
                    closeCastDiscovery()
                    if (accepted) {
                        health.reset(); updateControls()
                        showSheet("Cast route selected", "VLC is sending output to ${receiver.name}. Confirm that the receiver is displaying the video. Audio, subtitles and format support depend on the receiver.",
                            listOf(PlayerSheetChoice("Return playback to this device") {
                                if (player === current) { current.disconnectRenderer(); health.reset(); updateControls() }
                            }, PlayerSheetChoice("Close") {}))
                    } else showSheet("Display unavailable", "That display is no longer available or this source cannot be cast. Search again or continue on this device.",
                        listOf(PlayerSheetChoice("Search again") { beginCastDiscovery(current) }, PlayerSheetChoice("Close") {}))
                }) }
                if (snapshot.choices.isEmpty()) add(PlayerSheetChoice(if (snapshot.searching) "Searching…" else "No video displays found", selectable = false) {})
                if (!snapshot.searching) add(PlayerSheetChoice("Search again") { beginCastDiscovery(current) })
                add(PlayerSheetChoice("Cancel") { closeCastDiscovery() })
            })
        replacingCastSheet = false
        val dialog = activeDialog ?: return
        castSheet = dialog
        dialog.setOnDismissListener {
            // The shared sheet dismisses before invoking its row action. Keep the
            // selected renderer's lease until that synchronous action has run.
            if (!replacingCastSheet && castSheet === dialog) healthHandler.post {
                if (castSheet === dialog) closeCastDiscovery()
            }
            if (activeDialog === dialog) { activeDialog = null; if (!stopped) controls.show() }
        }
        if (focusedReceiver != null) {
            val matches = arrayListOf<View>()
            dialog.window?.decorView?.findViewsWithText(matches, focusedReceiver, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            matches.firstOrNull { it.contentDescription?.toString() == focusedReceiver }?.let { target -> target.post { target.requestFocus() } }
        }
    }

    private fun closeCastDiscovery() {
        castOwner = null
        castScan?.close(); castScan = null
        castSheet = null
    }

    private fun enterSystemPictureInPicture() {
        if (player == null) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            showSheet("Picture-in-picture unavailable", "This device does not advertise Android system picture-in-picture. Browse while playing keeps Cameo's in-app mini-player available.",
                listOf(PlayerSheetChoice("Browse while playing") { finish() }))
            return
        }
        val size = player?.videoSize
        val ratio = if (size != null && size.height > 0 && size.width > 0)
            (size.width * size.pixelWidthHeightRatio / size.height).takeIf { it.isFinite() && it > 0 } ?: (16f / 9f) else 16f / 9f
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio.coerceIn(0.42f, 2.38f) * 1000).toInt(), 1000))
            .apply { if (!bounds.isEmpty) setSourceRectHint(bounds) }.build()
        val entered = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
        if (!entered) showSheet("Picture-in-picture unavailable", "Android did not allow picture-in-picture. Check this app's picture-in-picture permission, or use Cameo's in-app mini-player.",
            listOf(PlayerSheetChoice("Browse while playing") { finish() }))
    }

    override fun onPictureInPictureModeChanged(inPictureInPicture: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPicture, newConfig)
        activeDialog?.dismiss()
        if (inPictureInPicture) {
            controls.stop()
            view.hideController()
            view.useController = false
        } else {
            view.useController = true
            controls.start()
            controls.show()
        }
    }

    private fun maybeShowUpNext(current: Player) {
        val identity = InAppPlaybackSession.currentIdentity ?: return
        if (CameoPlaybackQueue.hasNext() || dismissedUpNext.containsKey(current) ||
            !CameoUpNext.eligible(identity, current.currentPosition, current.duration)) return
        if (recommendationPlayer !== current) {
            recommendationPlayer = current
            recommendation = null
            recommendationRequested = false
        }
        if (!recommendationRequested) {
            recommendationRequested = true
            val recentIds = PlaybackStore(this).recent().mapNotNull { it.identity?.imdbId }.toSet()
            recommendationWorker.execute {
                val next = runCatching {
                    val catalog = CatalogApi()
                    val details = catalog.details(MediaItemData(identity.imdbId, identity.type, identity.title))
                    val candidates = if (identity.type == "movie") catalog.popular("movie") else emptyList()
                    CameoUpNext.select(identity, details, candidates, recentIds)
                }.getOrNull()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && recommendationPlayer === current && player === current)
                        recommendation = next
                }
            }
        }
        val next = recommendation ?: return
        if (activeDialog?.isShowing == true) return
        dismissedUpNext[current] = true
        val label = if (next.type == "series") "Next episode · ${next.title} · S${next.season} E${next.episode}"
            else "Recommended film · ${next.title}"
        showSheet("Up next", "$label. Choose a source explicitly before playback starts.", listOf(
            PlayerSheetChoice("Choose sources") {
                startActivity(Intent(this, MainActivity::class.java).putExtra("open_identity", next.toJson().toString())
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            },
            PlayerSheetChoice("Dismiss for this video") { dismissedUpNext[current] = true },
        ))
    }

    private fun showSpeed() {
        val current = player ?: return
        if (!current.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            showSheet("Playback speed", "This playback route does not expose speed control.", listOf(PlayerSheetChoice("Close") {}))
            return
        }
        showSheet("Playback speed", "Choose how quickly this video plays.",
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { speed ->
                PlayerSheetChoice(if (speed == 1f) "Normal · 1×" else "${String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')}×",
                    kotlin.math.abs(current.playbackParameters.speed - speed) < 0.01f) {
                    if (player === current) current.setPlaybackSpeed(speed)
                }
            })
    }

    private fun showVolume() {
        val current = player ?: return
        showSheet("Playback volume", "Adjust this video's volume. Device volume buttons remain available.",
            (0..100 step 10).map { percent -> PlayerSheetChoice(if (percent == 0) "Mute" else "$percent%",
                kotlin.math.abs(current.volume * 100 - percent) < 0.5f) {
                if (player === current) current.volume = percent / 100f
            } })
    }

    private fun showSleepTimer() {
        val detail = if (PlaybackSleepTimer.isActive)
            "Playback will pause in approximately ${(PlaybackSleepTimer.remainingMillis + 59_999) / 60_000} minutes. The timer stays active while you browse."
        else "Pause playback automatically after a set time, including while browsing in the mini-player."
        showSheet("Sleep timer", detail, listOf(PlayerSheetChoice("Off", !PlaybackSleepTimer.isActive) { PlaybackSleepTimer.cancel() }) +
            PlaybackSleepTimer.minuteChoices.map { minutes -> PlayerSheetChoice("$minutes minutes") {
                PlaybackSleepTimer.setMinutes(minutes)
            } })
    }

    private fun showChapters() {
        val current = player
        val vlc = current as? LibVlcPlayer
        if (vlc == null) {
            showSheet("Chapters", "Embedded chapter markers are available with the VLC playback engine. Choose VLC in Settings before opening this video, or use the timeline here.",
                listOf(PlayerSheetChoice("Close") {}))
            return
        }
        val chapters = runCatching { vlc.chapters() }.getOrDefault(emptyList())
        if (chapters.isEmpty()) {
            showSheet("Chapters", "This file has no readable chapter markers yet. Use the timeline to seek, or try again after the video has loaded.",
                listOf(PlayerSheetChoice("Close") {}))
            return
        }
        showSheet("Chapters", "Jump to a chapter in this video.", chapters.map { chapter ->
            PlayerSheetChoice("${chapter.name} · ${clock(chapter.startMillis)}", chapter.selected) {
                if (player === current && !vlc.selectChapter(chapter.index))
                    Toast.makeText(this, "That chapter is no longer available.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showSubtitleTiming() {
        val vlc = player as? LibVlcPlayer
        if (vlc == null) {
            showSheet("Subtitle timing", "Embedded subtitle timing adjustment is available with the VLC playback engine. Choose VLC in Settings before opening this video.",
                listOf(PlayerSheetChoice("Close") {}))
            return
        }
        fun change(action: () -> Boolean) {
            if (player !== vlc) return
            if (!action()) Toast.makeText(this, "This subtitle track could not change its timing.", Toast.LENGTH_SHORT).show()
            else runCatching { CaptionSessionStore(this).updateOffset(InAppPlaybackSession.currentIdentity,
                InAppPlaybackSession.currentPlaybackKey, vlc.subtitleDelayMicros) }.onFailure {
                Toast.makeText(this, "Timing changed for this video, but could not be saved for the next playback.", Toast.LENGTH_LONG).show()
            }
            showSubtitleTiming()
        }
        showSheet("Subtitle timing", "Current offset: ${String.format(Locale.ROOT, "%+.1f", vlc.subtitleDelayMicros / 1_000_000.0)} seconds.",
            listOf(
                PlayerSheetChoice("Earlier by 0.5 seconds") { change { vlc.adjustSubtitleDelay(-500_000L) } },
                PlayerSheetChoice("Later by 0.5 seconds") { change { vlc.adjustSubtitleDelay(500_000L) } },
                PlayerSheetChoice("Reset timing") { change(vlc::resetSubtitleDelay) },
            ))
    }

    private fun showCaptionAppearance(draft: CaptionAppearance = PlaybackPreferences(this).captionAppearance) {
        fun choose(title: String, values: List<Int>, labels: List<String>, selected: Int, update: (Int) -> CaptionAppearance) {
            showSheet(title, "Choose a caption default, then Apply from Subtitle appearance.", values.mapIndexed { index, value ->
                PlayerSheetChoice(labels[index], value == selected) { showCaptionAppearance(update(value)) }
            } + PlayerSheetChoice("Back to subtitle appearance") { showCaptionAppearance(draft) })
        }
        fun label(value: Int, values: List<Int>, labels: List<String>) = labels[values.indexOf(value).coerceAtLeast(0)]
        showSheet("Subtitle appearance", "Plain SRT/VTT defaults. Styled ASS captions may define their own appearance. Applying VLC defaults reloads this video at its current position and retains pause state.",
            listOf(
                PlayerSheetChoice("Size · ${label(draft.size, CaptionAppearance.SIZES, CaptionAppearance.SIZE_LABELS)}") {
                    choose("Caption size", CaptionAppearance.SIZES, CaptionAppearance.SIZE_LABELS, draft.size) { draft.copy(size = it) }
                },
                PlayerSheetChoice("Text color · ${label(draft.color, CaptionAppearance.COLORS, CaptionAppearance.COLOR_LABELS)}") {
                    choose("Caption text color", CaptionAppearance.COLORS, CaptionAppearance.COLOR_LABELS, draft.color) { draft.copy(color = it) }
                },
                PlayerSheetChoice("Background · ${label(draft.backgroundOpacity, CaptionAppearance.BACKGROUNDS, CaptionAppearance.BACKGROUND_LABELS)}") {
                    choose("Caption background", CaptionAppearance.BACKGROUNDS, CaptionAppearance.BACKGROUND_LABELS, draft.backgroundOpacity) { draft.copy(backgroundOpacity = it) }
                },
                PlayerSheetChoice("Bottom spacing · ${label(draft.bottomMargin, CaptionAppearance.MARGINS, CaptionAppearance.MARGIN_LABELS)}") {
                    choose("Caption bottom spacing", CaptionAppearance.MARGINS, CaptionAppearance.MARGIN_LABELS, draft.bottomMargin) { draft.copy(bottomMargin = it) }
                },
                PlayerSheetChoice("Apply caption appearance") {
                    PlaybackPreferences(this).captionAppearance = draft
                    applyCaptionAppearance()
                    (player as? LibVlcPlayer)?.reloadCaptionAppearance()
                },
            ))
    }

    private fun applyCaptionAppearance() {
        val appearance = PlaybackPreferences(this).captionAppearance
        view.subtitleView?.apply {
            setStyle(CaptionStyleCompat(0xFF000000.toInt() or appearance.color,
                appearance.backgroundOpacity shl 24, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
            setFractionalTextSize(1f / appearance.size)
            setBottomPaddingFraction(when (appearance.bottomMargin) { 40 -> 0.14f; 100 -> 0.22f; else -> 0.08f })
        }
    }

    private fun showPlaybackDetails() {
        val current = player ?: return
        val size = current.videoSize
        val rows = mutableListOf(
            PlayerSheetChoice("Engine: ${InAppPlaybackSession.engine.label}", selectable = false) {},
            PlayerSheetChoice("Picture: ${size.width} × ${size.height}", selectable = false) {},
            PlayerSheetChoice("Position: ${clock(current.currentPosition)} / ${clock(current.duration.coerceAtLeast(0))}", selectable = false) {},
            PlayerSheetChoice("Speed: ${current.playbackParameters.speed}×", selectable = false) {},
        )
        (current as? LibVlcPlayer)?.playbackDetails()?.let { details -> rows += listOf(
            PlayerSheetChoice("Decoded frames: ${details.decodedFrames}", selectable = false) {},
            PlayerSheetChoice("Displayed pictures: ${details.displayedFrames}", selectable = false) {},
            PlayerSheetChoice("Dropped pictures: ${details.droppedFrames}", selectable = false) {},
            PlayerSheetChoice("Input: ${details.inputKbitPerSecond} kbit/s", selectable = false) {},
        ) }
        (current as? ExoPlayer)?.videoDecoderCounters?.let { counters -> rows += listOf(
            PlayerSheetChoice("Rendered frames: ${counters.renderedOutputBufferCount}", selectable = false) {},
            PlayerSheetChoice("Dropped frames: ${counters.droppedBufferCount}", selectable = false) {},
        ) }
        rows += PlayerSheetChoice("Hardware decoding: automatic; not confirmed", selectable = false) {}
        showSheet("Playback details", "Current decoder readings. No source address or account information is shown.", rows)
    }

    private fun clock(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    private fun chooseSubtitleFile() {
        activeDialog?.dismiss()
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_TITLE, "Choose SRT, VTT, ASS, SSA, or text SUB subtitles")
            }, SUBTITLE_DOCUMENT)
        } catch (_: android.content.ActivityNotFoundException) {
            showSheet("No file picker available", "This device has no Android document provider. Install or enable a compatible file provider to choose subtitles from storage, USB, or a network provider.",
                listOf(PlayerSheetChoice("Close") {}))
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SUBTITLE_DOCUMENT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val expected = player ?: return
        subtitleWorker.execute {
            val imported = runCatching { ExternalSubtitleFiles.import(this, uri) }
            runOnUiThread {
                if (!isFinishing && !isDestroyed && player === expected) {
                    imported.fold(onSuccess = {
                        if (it.requiresVlc && player !is LibVlcPlayer) showSheet("Use VLC for this subtitle format",
                            "Text SUB subtitles require VLC. Choose VLC in Settings before opening this video, or choose SRT, VTT, ASS, or SSA here.", listOf(PlayerSheetChoice("Choose another subtitle") { chooseSubtitleFile() }))
                        else attachSubtitle(it.uri, it.displayName, PlaybackPreferences(this).subtitleLanguage, it.mimeType)
                    },
                        onFailure = { showSheet("Could not load subtitles", it.message ?: "The selected file could not be read.",
                            listOf(PlayerSheetChoice("Close") {})) })
                }
            }
        }
    }

    private fun searchSubtitles() {
        val identity = InAppPlaybackSession.subtitleIdentity
        if (identity == null) {
            showSheet("Identify this video first", "Online subtitle search needs a known movie or episode. Identify the video from your library, or load an SRT file directly.",
                listOf(PlayerSheetChoice("Load SRT file") { chooseSubtitleFile() }))
            return
        }
        val expected = player ?: return
        activeDialog?.dismiss()
        SubtitleSearchDialog.show(this, identity, PlaybackPreferences(this).subtitleLanguage) { subtitle ->
            if (!isFinishing && !isDestroyed && player === expected)
                attachSubtitle(subtitle.uri, subtitle.displayName, subtitle.language, subtitle.mimeType)
        }
    }

    private fun attachSubtitle(uri: Uri, displayName: String, language: String?, mimeType: String = "application/x-subrip") {
        if (InAppPlaybackSession.loadSubtitle(uri, displayName, language, mimeType)) {
            Toast.makeText(this, "Subtitles loaded: $displayName", Toast.LENGTH_SHORT).show()
            showTracks(C.TRACK_TYPE_TEXT)
        } else showSheet("Could not load subtitles", "The active engine could not attach this subtitle file. Try another SRT or source.",
            listOf(PlayerSheetChoice("Close") {}))
    }

    private fun showSheet(title: String, detail: String, choices: List<PlayerSheetChoice>) {
        activeDialog?.dismiss()
        activeTrackType = null
        activeTrackChoices = emptyList()
        activeTracksLoading = false
        if (isFinishing || isDestroyed) return
        controls.show()
        val dialog = PlayerOptionSheet.create(this, title, detail, choices, accent)
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                activeTrackType = null
                activeTrackChoices = emptyList()
                activeTracksLoading = false
                if (!stopped) controls.show()
            }
        }
        dialog.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == revealKey) {
            revealKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) controls.userInteraction()
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_CAPTIONS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_CAPTIONS) showTracks(C.TRACK_TYPE_TEXT) else showOptionsMenu()
            }
            return true
        }
        val dpad = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
        if (event.action == KeyEvent.ACTION_DOWN && dpad) {
            if (!view.isControllerFullyVisible) {
                controls.show()
                view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()
                revealKey = event.keyCode
                return true
            }
            val focused = currentFocus
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused != null && focused.id in rowIds) {
                view.findViewById<View>(androidx.media3.ui.R.id.exo_progress).requestFocus()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focused?.id == androidx.media3.ui.R.id.exo_progress) {
                view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()
                return true
            }
        }
        if (view.dispatchMediaKeyEvent(event)) {
            if (event.action == KeyEvent.ACTION_DOWN) controls.show()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { finish() }

    @Suppress("DEPRECATION")
    override fun finish() {
        if (!isFinishing && InAppPlaybackSession.error is VideoCompatibilityException) InAppPlaybackSession.stop()
        super.finish()
        // SurfaceView video and an Activity slide/scale animation do not compose smoothly on older TVs.
        overridePendingTransition(0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        InAppPlaybackSession.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        stopped = true
        closeCastDiscovery()
        healthHandler.removeCallbacks(healthCheck)
        health.reset()
        controls.stop()
        activeDialog?.dismiss(); activeDialog = null
        InAppPlaybackSession.removeListener(sessionListener)
        InAppPlaybackSession.detachPlayerView(view)
        super.onStop()
    }

    override fun onDestroy() {
        closeCastDiscovery()
        subtitleWorker.shutdownNow()
        recommendationWorker.shutdownNow()
        healthHandler.removeCallbacks(healthCheck)
        controls.release()
        InAppPlaybackSession.removeListener(sessionListener)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val dismissedUpNext = java.util.WeakHashMap<Player, Boolean>()
        private const val SUBTITLE_DOCUMENT = 910
        fun openCurrent(context: Context): Boolean {
            val intent = InAppPlaybackSession.currentIntent(context) ?: return false
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            @Suppress("DEPRECATION")
            if (context is Activity) context.overridePendingTransition(0, 0)
            return true
        }
    }
}

