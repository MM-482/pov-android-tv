package dev.pov.tv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import dev.pov.tv.subtitles.SubtitleSearchDialog
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
    private val boost get() = InAppPlaybackSession.volumeBoost
    private val accent by lazy { ThemePreferences(this).selected().playbackAccent }
    private var stopped = false
    private var revealKey = KeyEvent.KEYCODE_UNKNOWN
    private var observedError: PlaybackException? = null
    private var noticeShown = false
    private val optionsId = View.generateViewId()
    private var lastControlWidth = 0
    private val rowIds = listOf(androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause,
        R.id.pov_stop, androidx.media3.ui.R.id.exo_ffwd, R.id.pov_audio, R.id.pov_subtitles, R.id.pov_boost, R.id.pov_browse)
        .toMutableList().apply { add(size - 1, optionsId) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
        val focusInTouchMode = AdaptiveLayout.isTelevision(this)
        view.isFocusableInTouchMode = focusInTouchMode
        val actions = view.findViewById<LinearLayout>(R.id.pov_player_actions)
        actions.addView(Button(this).apply {
            id = optionsId; tag = "cameo_player_options"; text = "Options"; textSize = 13f; isAllCaps = false
            minWidth = 0; minimumWidth = 0; setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "Playback options"
            setOnClickListener { showOptionsMenu() }
        }, actions.indexOfChild(view.findViewById(R.id.pov_browse)), LinearLayout.LayoutParams(dp(84), dp(44)))
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(accent, 0xFF697D91.toInt(), 0xFFE3E8EF.toInt())
        )
        rowIds.forEachIndexed { index, id ->
            val button = view.findViewById<View>(id)
            button.isFocusable = true
            // Touch buttons should activate on the first tap instead of only taking focus.
            button.isFocusableInTouchMode = focusInTouchMode
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
        view.findViewById<View>(R.id.pov_stop).setOnClickListener { InAppPlaybackSession.stop(); finish() }
        view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress).apply {
            setPlayedColor(accent)
            nextFocusDownId = androidx.media3.ui.R.id.exo_play_pause
            nextFocusUpId = id
        }
        view.findViewById<ProgressBar>(R.id.pov_loading_spinner).indeterminateTintList = ColorStateList.valueOf(accent)
        view.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ -> adaptControls(right - left) }
        view.post { adaptControls(view.width) }
    }

    /** Runtime width handles cover screens, multi-window and folding without changing the session. */
    private fun adaptControls(width: Int) {
        if (width <= 0 || width == lastControlWidth) return
        lastControlWidth = width
        val narrow = width < dp((800 * resources.configuration.fontScale.coerceAtLeast(1f)).toInt())
        listOf(R.id.pov_audio, R.id.pov_subtitles, R.id.pov_boost).forEach {
            view.findViewById<View>(it).visibility = if (narrow) View.GONE else View.VISIBLE
        }
        view.findViewById<View>(R.id.pov_browse).visibility = if (width < dp(480)) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.pov_bottom_scrim).let {
            it.setPadding(dp(if (narrow) 12 else 30), it.paddingTop, dp(if (narrow) 12 else 30), it.paddingBottom)
        }
        val visible = rowIds.filter { view.findViewById<View>(it).visibility == View.VISIBLE }
        visible.forEachIndexed { index, id -> view.findViewById<View>(id).apply {
            nextFocusLeftId = visible[(index - 1).coerceAtLeast(0)]
            nextFocusRightId = visible[(index + 1).coerceAtMost(visible.lastIndex)]
        } }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lastControlWidth = 0
        activeDialog?.dismiss()
        view.post { adaptControls(view.width) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!intent.getBooleanExtra("reuse_session", false) && !InAppPlaybackSession.start(this, intent)) {
            showSheet("Cannot open video", InAppPlaybackSession.startFailure ?: "Choose the video from your library again.",
                listOf(PlayerSheetChoice("Back to browsing") { finish() }))
        } else InAppPlaybackSession.attachPlayerView(view)
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        InAppPlaybackSession.addListener(sessionListener)
        InAppPlaybackSession.attachPlayerView(view)
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
            buildString {
                append(InAppPlaybackSession.title.ifBlank { "Now playing" }); append(" · "); append(InAppPlaybackSession.engine.label)
                if (InAppPlaybackSession.playbackSpeed != 1f) append(" · ${InAppPlaybackSession.playbackSpeed}×")
                InAppPlaybackSession.sleepRemainingMillis?.let { append(" · Sleep ${(it + 59_999) / 60_000} min") }
            }
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
        showSheet("Playback stopped",
            "The link may have expired, the connection may be unavailable, or this device may not support the format. Code: ${error.errorCodeName}",
            listOf(
                PlayerSheetChoice("Retry") { if (!stopped) { observedError = null; InAppPlaybackSession.retry() } },
                PlayerSheetChoice("Browse sources") { finish() }
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
                if (stillAvailable) exo.trackSelectionParameters = PlayerTrackOptions.apply(exo.trackSelectionParameters, choice)
                else showSheet(title, "Tracks changed. Open this menu again to choose a current track.",
                    listOf(PlayerSheetChoice("Close") {}))
            }
        } }.toMutableList()
        if (type == C.TRACK_TYPE_TEXT) {
            rows += PlayerSheetChoice("Load SRT from device / USB / file provider") { chooseSubtitleFile() }
            rows += PlayerSheetChoice("Find subtitles online") { searchSubtitles() }
            rows += PlayerSheetChoice("Subtitle appearance") { showCaptionAppearance() }
            rows += PlayerSheetChoice("Subtitle timing") { showSubtitleTiming() }
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
                if (InAppPlaybackSession.canGoPrevious) add(PlayerSheetChoice("Previous playlist item") { InAppPlaybackSession.previousQueueItem() })
                if (InAppPlaybackSession.canGoNext) add(PlayerSheetChoice("Next playlist item") { InAppPlaybackSession.nextQueueItem() })
                addAll(listOf(
                PlayerSheetChoice("Audio") { showTracks(C.TRACK_TYPE_AUDIO) },
                PlayerSheetChoice("Subtitles") { showTracks(C.TRACK_TYPE_TEXT) },
                PlayerSheetChoice("Playback speed · ${InAppPlaybackSession.playbackSpeed}×") { showSpeed() },
                PlayerSheetChoice("Sleep timer") { showSleepTimer() },
                PlayerSheetChoice("Chapters") { showChapters() },
                PlayerSheetChoice("Volume boost") { showBoost() },
                PlayerSheetChoice("Playback details") { showPlaybackDetails() },
                PlayerSheetChoice("Browse while playing") { finish() },
                PlayerSheetChoice("Stop playback") { InAppPlaybackSession.stop(); finish() }
            )) })
    }

    private fun showSpeed() {
        val expected = player ?: return
        showSheet("Playback speed", "Choose how quickly this video plays.",
            listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { speed ->
                PlayerSheetChoice(if (speed == 1f) "Normal · 1×" else "$speed×", expected.playbackParameters.speed == speed) {
                    if (player === expected) InAppPlaybackSession.setPlaybackSpeed(speed)
                }
            })
    }

    private fun showSleepTimer() {
        val remaining = InAppPlaybackSession.sleepRemainingMillis
        showSheet("Sleep timer", remaining?.let { "Playback will pause in about ${(it + 59_999) / 60_000} minutes." }
            ?: "Pause playback automatically after a set time. The timer stays active while you browse.",
            listOf(PlayerSheetChoice("Off", remaining == null) { InAppPlaybackSession.setSleepTimer(null) }) +
                PlaybackSleepTimer.MINUTES.map { minutes -> PlayerSheetChoice("$minutes minutes") {
                    InAppPlaybackSession.setSleepTimer(minutes)
                } })
    }

    private fun showSubtitleTiming() {
        if (!InAppPlaybackSession.supportsSubtitleTiming) {
            showSheet("Subtitle timing", "Subtitle timing controls are available with VLC. Select VLC in Settings before opening a supported video.",
                listOf(PlayerSheetChoice("Close") {}))
            return
        }
        val expected = player
        val offset = InAppPlaybackSession.subtitleDelayMillis
        fun change(value: Long) {
            if (player !== expected) return
            if (!InAppPlaybackSession.setSubtitleDelayMillis(value)) Toast.makeText(this, "This subtitle track cannot change timing yet.", Toast.LENGTH_SHORT).show()
            showSubtitleTiming()
        }
        showSheet("Subtitle timing", "Current offset: ${offset / 1000.0} seconds", listOf(
            PlayerSheetChoice("Earlier by 0.5 seconds") { change(offset - 500) },
            PlayerSheetChoice("Later by 0.5 seconds") { change(offset + 500) },
            PlayerSheetChoice("Reset timing", offset == 0L) { change(0) },
        ))
    }

    private fun showCaptionAppearance(draft: CaptionAppearance = InAppPlaybackSession.captionAppearance) {
        showSheet("Subtitle appearance", "Plain subtitle defaults. Styled captions may define their own appearance. VLC reloads the video at the current position when you apply changes.", listOf(
            PlayerSheetChoice("Size · ${draft.size.label}") {
                showSheet("Subtitle size", "Choose a size.", CaptionSize.entries.map { value ->
                    PlayerSheetChoice(value.label, value == draft.size) { showCaptionAppearance(draft.copy(size = value)) }
                })
            },
            PlayerSheetChoice("Text color · ${draft.color.label}") {
                showSheet("Subtitle color", "Choose a text color.", CaptionColor.entries.map { value ->
                    PlayerSheetChoice(value.label, value == draft.color) { showCaptionAppearance(draft.copy(color = value)) }
                })
            },
            PlayerSheetChoice("Background · ${draft.background.label}") {
                showSheet("Subtitle background", "Choose a background.", CaptionBackground.entries.map { value ->
                    PlayerSheetChoice(value.label, value == draft.background) { showCaptionAppearance(draft.copy(background = value)) }
                })
            },
            PlayerSheetChoice("Bottom spacing · ${draft.position.label}") {
                showSheet("Subtitle position", "Choose bottom spacing.", CaptionPosition.entries.map { value ->
                    PlayerSheetChoice(value.label, value == draft.position) { showCaptionAppearance(draft.copy(position = value)) }
                })
            },
            PlayerSheetChoice("Apply caption defaults") {
                runCatching { InAppPlaybackSession.setCaptionAppearance(draft) }.onFailure {
                    showSheet("Could not save captions", "Try applying these preferences again.", listOf(PlayerSheetChoice("Close") {}))
                }
            },
        ))
    }

    private fun showChapters() {
        val expected = player ?: return
        val chapters = InAppPlaybackSession.chapters()
        val detail = if (expected !is LibVlcPlayer) "Chapter navigation is available with VLC for videos that contain chapter markers."
            else if (chapters.isEmpty()) "This video has no chapter markers available. You can use the timeline to seek."
            else "Jump to a chapter in this video."
        showSheet("Chapters", detail, if (chapters.isEmpty()) listOf(PlayerSheetChoice("Close") {}) else chapters.map { chapter ->
            val seconds = chapter.startMillis / 1000
            val clock = "%d:%02d".format(seconds / 60, seconds % 60)
            PlayerSheetChoice("${chapter.title} · $clock", chapter.index == InAppPlaybackSession.currentChapterIndex) {
                if (player === expected && !InAppPlaybackSession.selectChapter(chapter.index))
                    Toast.makeText(this, "This chapter is no longer available.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showPlaybackDetails() {
        val current = player ?: return
        val size = current.videoSize
        val dimensions = if (size.width > 0 && size.height > 0) "${size.width} × ${size.height}" else "Not reported"
        val details = buildList {
            add("Engine: ${InAppPlaybackSession.engine.label}"); add("Picture: $dimensions")
            add("Speed: ${current.playbackParameters.speed}×")
            add("Seekable: ${if (current.isCurrentMediaItemSeekable) "Yes" else "No"}")
            add("Audio and video format support depend on this device and source.")
        }.joinToString("\n")
        showSheet("Playback details", details, listOf(PlayerSheetChoice("Close") {}))
    }

    private fun chooseSubtitleFile() {
        activeDialog?.dismiss()
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_TITLE, "Choose an SRT subtitle file")
            }, SUBTITLE_DOCUMENT)
        } catch (_: android.content.ActivityNotFoundException) {
            showSheet("No file picker available", "This TV has no Android document provider. Install or enable a compatible file provider to choose subtitles from storage, USB, or a network provider.",
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
                    imported.fold(onSuccess = { attachSubtitle(it.uri, it.displayName, PlaybackPreferences(this).subtitleLanguage) },
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Keep both hide deadlines fresh while preserving native video taps,
        // button clicks and timeline drags. Revealing here would toggle twice.
        if (::controls.isInitialized) controls.touchInteraction(event)
        return super.dispatchTouchEvent(event)
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
        controls.stop()
        activeDialog?.dismiss(); activeDialog = null
        InAppPlaybackSession.removeListener(sessionListener)
        InAppPlaybackSession.detachPlayerView(view)
        super.onStop()
    }

    override fun onDestroy() {
        subtitleWorker.shutdownNow()
        controls.release()
        InAppPlaybackSession.removeListener(sessionListener)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
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

