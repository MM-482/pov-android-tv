package dev.pov.tv

import android.app.Activity
import android.app.Instrumentation
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Point
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import android.content.Context

/**
 * Opt-in real-device audit using only the exact public Shaka DASH or IETF CELLAR MKV fixture.
 * Each mode accepts only its specific public URL. No account values are accepted or reported.
 * This is a platform Instrumentation runner; it does not need AndroidX Test or JUnit.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackAuditInstrumentation : Instrumentation() {
    private var testUrl = PUBLIC_SAMPLE
    private var sourceFixtureMode = false
    private var miniOnlyMode = false
    private var vlcMode = false
    private var featuresMode = false
    private var adaptiveFallbackMode = false
    private var counterDiagnostic = false
    private var expectedChoiceIndex = -2
    private val expectedPublicUrl: String get() = if (vlcMode) PUBLIC_VLC_SAMPLE else PUBLIC_SAMPLE
    private val report = Bundle()
    private var stage = "initializing"
        set(value) {
            field = value
            if (value.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
                sendStatus(0, Bundle().apply { putString("audit_stage", value) })
            }
        }
    private var main: MainActivity? = null
    private var fullscreen: PlayerActivity? = null
    private var auditPlayer: ExoPlayer? = null
    private var vlcAuditPlayer: LibVlcPlayer? = null
    private val ownedPlayer: Player? get() = vlcAuditPlayer ?: auditPlayer
    private var originalPlaybackPreferences: Map<String, *>? = null
    private var subtitleFixture: File? = null
    private var originalHiddenContinue: String? = null
    private var originalPending: String? = null
    private var hiddenSnapshotTaken = false
    private var originalRecent: String? = null
    private var originalBrowseRoot: View? = null
    private var firstMiniPlayerView: PlayerView? = null
    private var auditHomeEdge: View? = null
    private var observedFirstFrames = 0
    private var controlsRevealAttempts = 0
    private val firstFramesByTarget = java.util.IdentityHashMap<PlayerView, Int>()
    private val firstFrameObserver = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (ownedPlayer is ExoPlayer) return
            observedFirstFrames++
            publicVideoTargets().firstOrNull(InAppPlaybackSession::ownsVideoTarget)?.let {
                firstFramesByTarget[it] = (firstFramesByTarget[it] ?: 0) + 1
            }
        }
    }
    private var unmatchedMedia3FirstFrames = 0
    private var media3DecoderName: String? = null
    private var media3DecoderInitializations = 0
    private var media3DecoderReleases = 0
    private var media3ObserverRegistered = false
    private data class TargetObservation(val switched: Int, val observedAt: Long, val fromSessionNotification: Boolean)
    private val targetObservations = java.util.IdentityHashMap<PlayerView, TargetObservation>()
    private val observeMedia3TargetAttachment: () -> Unit = { observeCurrentMedia3Target(true) }
    private val installMedia3Observer: () -> Unit = {
        if (!media3ObserverRegistered && testUrl == PUBLIC_SAMPLE &&
            InAppPlaybackSession.matches(Intent().putExtra("url", PUBLIC_SAMPLE).putExtra("key", ""))) {
            InAppPlaybackSession.playerOrNull?.let {
                auditPlayer = it
                it.addAnalyticsListener(media3FrameObserver)
                media3ObserverRegistered = true
            }
        }
    }
    private val media3FrameObserver = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long) {
            media3DecoderName = decoderName
            media3DecoderInitializations++
        }
        override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            media3DecoderReleases++
        }
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            observedFirstFrames++
            // Generic Player.Listener has no output identity. A late outgoing
            // surface event must not count as the incoming mini's first frame.
            val target = publicVideoTargets().firstOrNull {
                (it.videoSurfaceView as? android.view.SurfaceView)?.holder?.surface === output
            }
            if (target == null) unmatchedMedia3FirstFrames++
            else firstFramesByTarget[target] = (firstFramesByTarget[target] ?: 0) + 1
        }
    }

    private fun publicVideoTargets(): List<PlayerView> = buildList {
        fullscreen?.takeUnless { it.isDestroyed }?.let { add(playerViewFor(it)) }
        main?.takeUnless { it.isDestroyed }?.let { addAll(descendants(it.findViewById(android.R.id.content)).filterIsInstance<PlayerView>()) }
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        sourceFixtureMode = arguments?.getString("audit_mode") == "source_choices"
        miniOnlyMode = arguments?.getString("audit_mode") == "mini_only"
        vlcMode = arguments?.getString("audit_mode") == "vlc"
        featuresMode = arguments?.getString("audit_mode") == "features"
        adaptiveFallbackMode = arguments?.getString("audit_mode") == "adaptive_fallback"
        counterDiagnostic = arguments?.getString("counter_diagnostic") == "true"
        report.putBoolean("counter_diagnostic_mode", counterDiagnostic)
        testUrl = arguments?.getString("test_url") ?: expectedPublicUrl
        start()
    }

    override fun onStart() {
        super.onStart()
        var passed = false
        try {
            if (sourceFixtureMode) auditSourceChoices() else if (vlcMode) auditVlc() else if (featuresMode) auditFeatures() else audit()
            passed = true
        } catch (failure: Throwable) {
            report.putString("error_stage", stage)
            report.putString("error_class", failure.javaClass.simpleName.take(80))
            val errorCode = try { onMain { InAppPlaybackSession.error?.errorCode ?: 0 } } catch (_: Throwable) { 0 }
            report.putInt("player_error_code", errorCode)
            if (vlcMode) {
                try { onMain { recordVlcState() } } catch (_: Throwable) { report.putBoolean("vlc_numeric_diagnostics_unavailable", true) }
            }
            try { onMain { recordFailureFocus() } } catch (_: Throwable) { /* Numeric focus diagnostics are optional. */ }
            if (sourceFixtureMode) {
                try { onMain { recordChoiceFocusFailure() } } catch (_: Throwable) { report.putBoolean("choice_focus_diagnostics_unavailable", true) }
            }
            if (stage.contains("attached")) {
                try { onMain { recordPendingMedia3Geometry() } } catch (_: Throwable) { /* Public numeric geometry only. */ }
            }
            if (stage == "right_edge_enters_visible_mini" || stage.startsWith("home_content_edge")) {
                try { onMain { recordMiniFocus() } } catch (diagnosticFailure: Throwable) {
                    report.putString("mini_diagnostic_error_class", diagnosticFailure.javaClass.simpleName.take(80))
                }
            }
            if (stage == "unified_controller_geometry" && testUrl == PUBLIC_SAMPLE) {
                try {
                    onMain { recordControllerGeometry(playerViewFor(fullscreen!!)) }
                    capturePublicScreenshot("pov-public-player-controls.png")
                    report.putBoolean("public_geometry_failure_screenshot_saved", true)
                } catch (diagnosticFailure: Throwable) {
                    report.putString("geometry_diagnostic_error_class", diagnosticFailure.javaClass.simpleName.take(80))
                }
            }
            if (stage == "audio_bottom_sheet_visible" && testUrl == PUBLIC_SAMPLE) {
                try {
                    onMain { recordSheetGeometry(fullscreen!!.currentOptionSheet!!) }
                    capturePublicScreenshot("pov-public-player-audio.png")
                    report.putBoolean("public_audio_sheet_failure_screenshot_saved", true)
                } catch (diagnosticFailure: Throwable) {
                    report.putString("sheet_diagnostic_error_class", diagnosticFailure.javaClass.simpleName.take(80))
                }
            }
        } finally {
            try {
                onMain {
                    ownedPlayer?.removeListener(firstFrameObserver)
                    InAppPlaybackSession.removeListener(installMedia3Observer)
                    InAppPlaybackSession.removeListener(observeMedia3TargetAttachment)
                    auditPlayer?.removeAnalyticsListener(media3FrameObserver)
                    if (InAppPlaybackSession.currentPlayer === ownedPlayer && ownedPlayer != null) InAppPlaybackSession.stop()
                    fullscreen?.takeUnless { it.isFinishing || it.isDestroyed }?.finish()
                    if (sourceFixtureMode || vlcMode || featuresMode) main?.takeUnless { it.isFinishing || it.isDestroyed }?.finish()
                    restorePlaybackPreferences()
                    if (hiddenSnapshotTaken) {
                        val preferences = targetContext.getSharedPreferences("playback", 0)
                        val editor = preferences.edit()
                        originalHiddenContinue?.let { editor.putString("hidden_continue", it) } ?: editor.remove("hidden_continue")
                        checkAudit(editor.commit())
                        report.putBoolean("hidden_continue_restored", preferences.getString("hidden_continue", null) == originalHiddenContinue)
                        report.putBoolean("pending_tracking_unchanged", preferences.getString("pending", null) == originalPending)
                        checkAudit(report.getBoolean("hidden_continue_restored") && report.getBoolean("pending_tracking_unchanged"))
                    }
                }
                subtitleFixture?.delete()
                waitForIdleSync()
                val unchanged = onMain {
                    targetContext.getSharedPreferences("playback", 0).getString("recent", null) == originalRecent
                }
                report.putBoolean("history_unchanged_after_cleanup", unchanged)
                if (!unchanged) passed = false
            } catch (cleanupFailure: Throwable) {
                passed = false
                report.putString("cleanup_error_class", cleanupFailure.javaClass.simpleName.take(80))
            }
            report.putBoolean("passed", passed)
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
        }
    }

    private fun audit() {
        stage = "public_sample_validation"
        checkAudit(testUrl == PUBLIC_SAMPLE)
        report.putBoolean("verified_public_sample", true)
        stage = "session_idle_precondition"
        originalRecent = onMain { targetContext.getSharedPreferences("playback", 0).getString("recent", null) }
        checkAudit(onMain { !InAppPlaybackSession.hasActive() })
        onMain { selectAuditEngine(if (adaptiveFallbackMode) PlaybackEngine.VLC else PlaybackEngine.MEDIA3) }
        main = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .putExtra("playback_audit_preview", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
        waitForIdleSync()
        originalBrowseRoot = onMain { main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) }
        stage = "launch_paused_sample"
        // Register on the owned public session before prepare/first surface can
        // initialize its decoder; no codec identity is obtained from private logs.
        onMain {
            InAppPlaybackSession.addListener(installMedia3Observer)
            InAppPlaybackSession.addListener(observeMedia3TargetAttachment)
        }
        fullscreen = startActivitySync(Intent(targetContext, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("url", testUrl).putExtra("title", "Public playback audit")
            .putExtra("key", "").putExtra("playWhenReady", false)) as PlayerActivity
        waitForIdleSync()
        auditPlayer = onMain { InAppPlaybackSession.playerOrNull }
        checkAudit(auditPlayer != null)
        onMain { installMedia3Observer(); InAppPlaybackSession.removeListener(installMedia3Observer) }
        if (adaptiveFallbackMode) {
            checkAudit(onMain { InAppPlaybackSession.engine == PlaybackEngine.MEDIA3 &&
                InAppPlaybackSession.currentPlayer === auditPlayer && PlaybackPreferences(targetContext).selectedEngine == PlaybackEngine.VLC })
            report.putBoolean("adaptive_fallback_actual_media3", true)
            report.putBoolean("adaptive_fallback_vlc_preference_preserved", true)
            press(fullscreen!!, KeyEvent.KEYCODE_MENU)
            await("adaptive_fallback_notice_rendered", 3_000) {
                val notice = InAppPlaybackSession.engineNotice
                notice == "Using Media3 for this DASH stream" && fullscreen!!.currentOptionSheet?.let { dialog ->
                    validSheetGeometry(dialog) && descendants(dialog.window!!.decorView)
                        .filterIsInstance<android.widget.TextView>().any { it.text.contains(notice) && fullyRendered(it) }
                } == true
            }
            report.putBoolean("adaptive_fallback_notice_rendered", true)
            closeSheet(onMain { fullscreen!!.currentOptionSheet!! })
        }
        onMain { auditPlayer!!.volume = 0f }
        await("sample_ready", 30_000) {
            val player = auditPlayer!!
            checkAudit(player.playerError == null)
            player.playbackState == Player.STATE_READY
        }
        report.putBoolean("ready_while_paused", onMain { !auditPlayer!!.playWhenReady })
        checkAudit(report.getBoolean("ready_while_paused"))
        onMain { recordPublicSoftwareDecoders() }
        if (Media3DecoderPolicy.affected()) {
            await("sony_supported_avc_alternative_selected", 5_000) {
                media3DecoderName == "OMX.google.h264.decoder" && auditPlayer!!.videoFormat?.sampleMimeType == "video/avc"
            }
            report.putBoolean("sony_vp9_decoder_policy_active", true)
            report.putBoolean("public_supported_h264_alternative_selected", true)
            report.putBoolean("public_software_h264_decoder_selected", media3DecoderName == "OMX.google.h264.decoder")
            report.putBoolean("public_stalled_mtk_vp9_decoder_excluded", media3DecoderName != "OMX.MTK.VIDEO.DECODER.VP9")
            checkAudit(report.getBoolean("public_stalled_mtk_vp9_decoder_excluded"))
            onMain { recordMedia3Decoder("before_mini") }
        }
        if (miniOnlyMode) {
            auditMiniOnly()
            return
        }

        stage = "supported_tracks"
        val counts = onMain {
            val p = auditPlayer!!
            val audio = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_AUDIO)
                .filter { it.kind == TrackChoiceKind.TRACK && it.selectable }
            val text = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_TEXT)
                .filter { it.kind == TrackChoiceKind.TRACK && it.selectable }
            audio.size to text.size
        }
        report.putInt("supported_audio_tracks", counts.first)
        report.putInt("supported_subtitle_tracks", counts.second)
        checkAudit(counts.first >= 2 && counts.second >= 1)
        onMain {
            val p = auditPlayer!!
            for ((type, name) in listOf(C.TRACK_TYPE_AUDIO to "audio", C.TRACK_TYPE_TEXT to "subtitle")) {
                val exposed = p.currentTracks.groups.filter { it.type == type }.sumOf { it.length }
                val choices = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, type)
                    .filter { it.kind == TrackChoiceKind.TRACK }
                checkAudit(choices.size == exposed)
                report.putInt("${name}_tracks_exposed", exposed)
                report.putInt("${name}_tracks_unavailable", choices.count { !it.selectable })
            }
        }
        report.putBoolean("all_exposed_audio_and_subtitle_tracks_listed", true)

        val audioChoice = onMain {
            val p = auditPlayer!!
            val tracks = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_AUDIO)
                .filter { it.kind == TrackChoiceKind.TRACK && it.selectable }
            val language = tracks.first().group!!.getFormat(tracks.first().index).language
            tracks.firstOrNull { it.group!!.getFormat(it.index).language != language } ?: tracks.last()
        }
        onMain {
            val p = auditPlayer!!
            p.trackSelectionParameters = PlayerTrackOptions.apply(p.trackSelectionParameters, audioChoice)
        }
        await("audio_override_selected", 12_000) {
            auditPlayer!!.currentTracks.groups.any {
                it.mediaTrackGroup == audioChoice.group && it.isTrackSelected(audioChoice.index)
            }
        }
        report.putBoolean("audio_track_switched", true)
        checkAudit(onMain {
            val p = auditPlayer!!
            PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_AUDIO).any {
                it.group == audioChoice.group && it.index == audioChoice.index && it.selected && it.checked
            }
        })
        report.putBoolean("manual_audio_choice_matches_actual_selected_track", true)

        val subtitleChoice = onMain {
            val p = auditPlayer!!
            PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_TEXT)
                .first { it.kind == TrackChoiceKind.TRACK && it.selectable }
        }
        onMain {
            val p = auditPlayer!!
            p.trackSelectionParameters = PlayerTrackOptions.apply(p.trackSelectionParameters, subtitleChoice)
        }
        await("subtitle_override_selected", 12_000) {
            auditPlayer!!.currentTracks.groups.any {
                it.mediaTrackGroup == subtitleChoice.group && it.isTrackSelected(subtitleChoice.index)
            }
        }
        report.putBoolean("subtitle_track_selected", true)
        stage = "subtitle_off_and_automatic"
        onMain {
            val p = auditPlayer!!
            val off = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_TEXT)
                .first { it.kind == TrackChoiceKind.OFF }
            p.trackSelectionParameters = PlayerTrackOptions.apply(p.trackSelectionParameters, off)
        }
        await("subtitle_off", 5_000) {
            val p = auditPlayer!!
            C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes &&
                p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.none { it.isSelected }
        }
        report.putBoolean("subtitles_off", true)
        onMain {
            val p = auditPlayer!!
            for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
                val automatic = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, type)
                    .first { it.kind == TrackChoiceKind.AUTOMATIC }
                p.trackSelectionParameters = PlayerTrackOptions.apply(p.trackSelectionParameters, automatic)
            }
            checkAudit(C.TRACK_TYPE_TEXT !in p.trackSelectionParameters.disabledTrackTypes)
            checkAudit(p.trackSelectionParameters.overrides.values.none {
                it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT
            })
        }
        report.putBoolean("audio_and_subtitles_automatic_restored", true)
        checkAudit(onMain {
            val p = auditPlayer!!
            val choices = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_AUDIO)
            choices.first().checked && choices.any { it.kind == TrackChoiceKind.TRACK && it.selected && !it.checked }
        })
        report.putBoolean("automatic_mode_separate_from_renderer_selected_tracks", true)

        onMain { auditPlayer!!.play() }
        await("muted_playback_started", 15_000) { auditPlayer!!.isPlaying }
        report.putBoolean("muted_playback_started", true)
        stage = "volume_boost_request_and_reset"
        val framesBeforeBoost = onMain { InAppPlaybackSession.volumeBoost.processedPcmFrames }
        val boostState = onMain { InAppPlaybackSession.setVolumeBoostGain(3) }
        report.putBoolean("boost_initial_waiting_for_audio", boostState == VolumeBoostState.WAITING_FOR_AUDIO)
        await("software_boost_pcm_processed", 5_000) {
            InAppPlaybackSession.volumeBoost.state == VolumeBoostState.REQUESTED &&
                InAppPlaybackSession.volumeBoost.processedPcmFrames > framesBeforeBoost
        }
        report.putBoolean("boost_pcm_processed", true)
        report.putBoolean("boost_effect_requested", true)
        report.putLong("boost_processed_pcm_frame_delta", onMain {
            InAppPlaybackSession.volumeBoost.processedPcmFrames - framesBeforeBoost
        })
        onMain {
            InAppPlaybackSession.setVolumeBoostGain(0)
            checkAudit(InAppPlaybackSession.volumeBoost.state == VolumeBoostState.OFF)
            checkAudit(InAppPlaybackSession.volumeBoost.gainDb == 0)
        }
        report.putBoolean("boost_reset", true)

        val playerView = onMain { requireNotNull(fullscreen!!.findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<PlayerView>("pov_fullscreen_player")) }
        stage = "unified_controller_geometry"
        onMain { playerView.controllerShowTimeoutMs = 4_000 }
        revealControls(fullscreen!!)
        await("unified_controller_geometry", 3_000) { validControllerGeometry(playerView) }
        report.putBoolean("all_actions_in_bottom_controller_bounds", true)
        report.putBoolean("compact_controller_height_within_120_to_135_dp", true)
        report.putInt("compact_controller_height_dp", onMain {
            (playerView.findViewById<View>(R.id.pov_bottom_scrim).height / targetContext.resources.displayMetrics.density).toInt()
        })
        capturePublicScreenshot("pov-public-player-controls.png")
        report.putBoolean("public_geometry_screenshot_saved", true)
        report.putBoolean("buffering_indicator_inside_controller", onMain { insideController(
            playerView.findViewById(R.id.pov_loading_spinner), playerView) })
        report.putBoolean("independent_buffering_spinner_hidden", onMain {
            !rendered(playerView.findViewById(androidx.media3.ui.R.id.exo_buffering))
        })
        stage = "audio_picker_remote_navigation"
        onMain { playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus() }
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        checkAudit(onMain { fullscreen!!.currentFocus?.id == R.id.pov_stop })
        report.putBoolean("stop_one_dpad_right_from_play", true)
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        checkAudit(onMain { fullscreen!!.currentFocus?.id == R.id.pov_audio })
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_CENTER)
        await("audio_bottom_sheet_visible", 3_000) {
            fullscreen!!.currentOptionSheet?.isShowing == true && validSheetGeometry(fullscreen!!.currentOptionSheet!!)
        }
        report.putBoolean("audio_picker_reachable_by_dpad", true)
        capturePublicScreenshot("pov-public-player-audio.png")
        traverseAudioSheet(fullscreen!!.currentOptionSheet!!)
        closeSheet(fullscreen!!.currentOptionSheet!!)
        stage = "subtitle_picker_remote_navigation"
        onMain { playerView.findViewById<View>(R.id.pov_audio).requestFocus() }
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        checkAudit(onMain { fullscreen!!.currentFocus?.id == R.id.pov_subtitles })
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_CENTER)
        await("subtitle_bottom_sheet_visible", 3_000) { fullscreen!!.currentOptionSheet?.isShowing == true }
        closeSheet(fullscreen!!.currentOptionSheet!!)
        report.putBoolean("subtitle_picker_reachable_by_dpad", true)
        stage = "boost_picker_remote_navigation"
        onMain { playerView.findViewById<View>(R.id.pov_subtitles).requestFocus() }
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        checkAudit(onMain { fullscreen!!.currentFocus?.id == R.id.pov_boost })
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_CENTER)
        await("boost_bottom_sheet_visible", 3_000) { fullscreen!!.currentOptionSheet?.isShowing == true }
        closeSheet(fullscreen!!.currentOptionSheet!!)
        report.putBoolean("boost_picker_reachable_by_dpad", true)
        revealControls(fullscreen!!)
        onMain { playerView.findViewById<View>(R.id.pov_boost).requestFocus() }
        SystemClock.sleep(300)
        capturePublicScreenshot("pov-public-player-controls.png")
        report.putBoolean("public_controls_screenshot_saved", true)
        stage = "manual_controls_hide"
        onMain { playerView.hideController(); checkAudit(allControlsHidden(playerView)) }
        report.putBoolean("manual_hide_includes_timeline_loading_and_actions", true)
        stage = "rendered_loading_fixture"
        onMain { playerView.controllerShowTimeoutMs = 4_000 }
        revealControls(fullscreen!!)
        onMain {
            // Deliberately exercise rendered loading chrome; this is not a network-buffering claim.
            playerView.findViewById<View>(R.id.pov_buffering_status).visibility = View.VISIBLE
        }
        await("rendered_loading_fixture_visible", 3_000) {
            rendered(playerView.findViewById(R.id.pov_loading_spinner)) && validControllerGeometry(playerView)
        }
        report.putBoolean("loading_visibility_fixture_used", true)
        report.putBoolean("loading_fixture_rendered_before_timeout", true)
        stage = "timed_controls_hide_with_action_focused"
        onMain { playerView.controllerShowTimeoutMs = 800 }
        revealControls(fullscreen!!)
        onMain {
            playerView.findViewById<View>(R.id.pov_audio).requestFocus()
            checkAudit(fullscreen!!.currentFocus?.id == R.id.pov_audio)
        }
        await("timed_controls_hide_with_action_focused", 3_000) { allControlsHidden(playerView) }
        report.putBoolean("focused_audio_does_not_pin_controls", true)
        report.putBoolean("timeout_hides_timeline_loading_and_actions", true)
        report.putBoolean("rendered_loading_fixture_hidden_with_controller", true)
        SystemClock.sleep(250)
        capturePublicScreenshot("pov-public-player-hidden.png")
        report.putBoolean("public_hidden_screenshot_saved", true)
        onMain { playerView.findViewById<View>(R.id.pov_buffering_status).visibility = View.GONE }
        stage = "dpad_restores_controls"
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_UP)
        checkAudit(onMain { validControllerGeometry(playerView) })
        report.putBoolean("dpad_restores_complete_controls", true)

        preparePublicMovingWindow()
        stage = "browse_handoff"
        onMain { recordMedia3Decoder("before_mini") }
        val beforeBrowse = onMain { auditPlayer!!.currentPosition }
        val framesBeforeBrowse = onMain { renderedVideoFrames() }
        onMain { fullscreen!!.onBackPressed() }
        waitForIdleSync()
        await("mini_view_attached_same_player", 5_000) {
            InAppPlaybackSession.playerOrNull === auditPlayer &&
                descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>()
                    .any { it.player === auditPlayer }
        }
        checkAudit(onMain { main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) === originalBrowseRoot })
        report.putBoolean("browse_root_retained_after_fullscreen", true)
        firstMiniPlayerView = onMain {
            descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>().first { it.player === auditPlayer }
        }
        await("browse_playback_advances", 7_000) {
            auditPlayer!!.isPlaying && auditPlayer!!.currentPosition > beforeBrowse + 400
        }
        report.putBoolean("browse_uses_same_player", true)
        report.putBoolean("position_advances_while_browsing", true)
        verifyPublicVideoMotion("mini", main!!, firstMiniPlayerView!!, true, framesBeforeBrowse)
        val framesBeforeReturn = onMain { renderedVideoFrames() }
        fullscreen = returnToFullscreen()
        stage = "return_position_preserved"
        checkAudit(onMain {
            InAppPlaybackSession.playerOrNull === auditPlayer &&
                auditPlayer!!.currentPosition >= beforeBrowse && playerViewFor(fullscreen!!).player === auditPlayer
        })
        report.putBoolean("return_uses_same_player_without_reset", true)
        verifyPublicVideoMotion("fullscreen", fullscreen!!, playerViewFor(fullscreen!!), false, framesBeforeReturn)

        stage = "paused_handoff"
        onMain { auditPlayer!!.pause() }
        revealControls(fullscreen!!)
        val immediatePausePosition = onMain { auditPlayer!!.currentPosition }
        report.putBoolean("pause_request_applied", onMain { !auditPlayer!!.playWhenReady })
        // pause() changes application state before the playback thread/audio clock acknowledges it.
        // Compare the handoff with an acknowledged, stable paused clock, not the pre-ack snapshot.
        val pausedAt = awaitPausedClock(auditPlayer!!)
        report.putLong("pause_settle_position_delta_ms", pausedAt - immediatePausePosition)
        stage = "paused_browse_handoff"
        onMain { fullscreen!!.onBackPressed() }
        waitForIdleSync()
        await("paused_mini_view_attached", 5_000) {
            descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>()
                .any { it.player === auditPlayer }
        }
        checkAudit(onMain {
            main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) === originalBrowseRoot &&
                firstMiniPlayerView?.player === auditPlayer &&
                descendants(main!!.findViewById(android.R.id.content)).any { it === firstMiniPlayerView }
        })
        report.putBoolean("browse_root_retained_across_repeat_handoffs", true)
        report.putBoolean("existing_mini_player_view_reused", true)
        SystemClock.sleep(700)
        stage = "paused_browse_handoff"
        val browsePaused = onMain { !auditPlayer!!.playWhenReady && !auditPlayer!!.isPlaying }
        val browseSamePlayer = onMain { InAppPlaybackSession.playerOrNull === auditPlayer }
        val browsePauseDelta = onMain { auditPlayer!!.currentPosition - pausedAt }
        report.putBoolean("paused_browse_still_paused", browsePaused)
        report.putBoolean("paused_browse_same_player", browseSamePlayer)
        report.putLong("paused_browse_position_delta_ms", browsePauseDelta)
        checkAudit(browsePaused && browseSamePlayer && kotlin.math.abs(browsePauseDelta) < 250)
        fullscreen = returnToFullscreen()
        stage = "paused_fullscreen_handoff"
        val fullscreenPaused = onMain { !auditPlayer!!.playWhenReady && !auditPlayer!!.isPlaying }
        val fullscreenSamePlayer = onMain { InAppPlaybackSession.playerOrNull === auditPlayer }
        val fullscreenPauseDelta = onMain { auditPlayer!!.currentPosition - pausedAt }
        report.putBoolean("paused_fullscreen_still_paused", fullscreenPaused)
        report.putBoolean("paused_fullscreen_same_player", fullscreenSamePlayer)
        report.putLong("paused_fullscreen_position_delta_ms", fullscreenPauseDelta)
        checkAudit(fullscreenPaused && fullscreenSamePlayer && kotlin.math.abs(fullscreenPauseDelta) < 250)
        report.putBoolean("paused_handoff_preserves_pause_and_position", true)
        stage = "paused_controls_remain_visible"
        revealControls(fullscreen!!)
        SystemClock.sleep(1_200)
        checkAudit(onMain { playerViewFor(fullscreen!!).isControllerFullyVisible })
        report.putBoolean("paused_controls_remain_visible", true)
        stage = "explicit_stop"
        onMain { InAppPlaybackSession.stop() }
        waitForIdleSync()
        checkAudit(onMain { !InAppPlaybackSession.hasActive() && InAppPlaybackSession.playerOrNull == null })
        report.putBoolean("stop_releases_session", true)
        stage = "history_preserved"
        checkAudit(onMain { targetContext.getSharedPreferences("playback", 0).getString("recent", null) == originalRecent })
        report.putBoolean("history_unchanged", true)
        if (adaptiveFallbackMode) {
            checkAudit(onMain { PlaybackPreferences(targetContext).selectedEngine == PlaybackEngine.VLC })
            report.putBoolean("adaptive_fallback_preference_unchanged_after_stop", true)
        }
    }

    /** Uses only the exact public URL, guest browsing, an untracked source key, and an owned SRT. */
    private fun auditVlc() {
        stage = "vlc_public_sample_validation"
        checkAudit(testUrl == PUBLIC_VLC_SAMPLE)
        report.putBoolean("verified_public_sample", true)
        report.putBoolean("native_fixture_is_progressive_multitrack", true)
        originalRecent = onMain { targetContext.getSharedPreferences("playback", 0).getString("recent", null) }
        checkAudit(onMain { !InAppPlaybackSession.hasActive() })
        onMain { selectAuditEngine(PlaybackEngine.VLC) }
        main = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .putExtra("playback_audit_preview", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
        waitForIdleSync()
        originalBrowseRoot = onMain { main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) }
        stage = "vlc_launch_owned_public_sample"
        fullscreen = startActivitySync(Intent(targetContext, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("url", testUrl).putExtra("title", "Public VLC playback audit")
            .putExtra("key", "").putExtra("playWhenReady", false)) as PlayerActivity
        waitForIdleSync()
        vlcAuditPlayer = onMain { InAppPlaybackSession.currentPlayer as? LibVlcPlayer }
        checkAudit(vlcAuditPlayer != null)
        onMain { vlcAuditPlayer!!.addListener(firstFrameObserver) }
        onMain { vlcAuditPlayer!!.volume = 0f }
        report.putBoolean("embedded_vlc_engine_active", onMain { InAppPlaybackSession.engine == PlaybackEngine.VLC })
        await("vlc_ready_while_paused", 40_000) {
            checkAudit(vlcAuditPlayer!!.playerError == null)
            vlcAuditPlayer!!.playbackState == Player.STATE_READY && !vlcAuditPlayer!!.playWhenReady
        }
        report.putBoolean("ready_while_paused", true)
        onMain { vlcAuditPlayer!!.play() }
        await("vlc_actual_video_output", 20_000) {
            val current = vlcAuditPlayer!!
            current.isPlaying && current.currentPosition > 500 && current.renderedOutputFrames > 0
        }
        val playerView = onMain { playerViewFor(fullscreen!!) }
        verifyPublicVideoMotion("vlc_fullscreen", fullscreen!!, playerView, false, onMain { renderedVideoFrames() })
        report.putLong("vlc_displayed_picture_count", onMain { vlcAuditPlayer!!.renderedOutputFrames })

        val counts = onMain {
            val tracks = vlcAuditPlayer!!.currentTracks
            tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length } to
                tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.sumOf { it.length }
        }
        report.putInt("vlc_audio_track_count", counts.first)
        report.putInt("vlc_subtitle_track_count", counts.second)
        checkAudit(counts.first >= 2 && counts.second >= 1)
        revealControls(fullscreen!!)
        stage = "vlc_audio_sheet"
        onMain { playerView.findViewById<View>(R.id.pov_audio).performClick() }
        await("vlc_audio_sheet_visible", 3_000) { fullscreen!!.currentOptionSheet?.let(::validSheetGeometry) == true }
        traverseAudioSheet(onMain { fullscreen!!.currentOptionSheet!! }, requireScrolling = false)
        closeSheet(onMain { fullscreen!!.currentOptionSheet!! })
        report.putBoolean("vlc_audio_menu_rendered_and_reachable", true)
        for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            val choice = onMain {
                val current = vlcAuditPlayer!!
                PlayerTrackOptions.choices(current.currentTracks, current.trackSelectionParameters, type)
                    .last { it.kind == TrackChoiceKind.TRACK && it.selectable }
            }
            onMain { vlcAuditPlayer!!.trackSelectionParameters = PlayerTrackOptions.apply(vlcAuditPlayer!!.trackSelectionParameters, choice) }
            await("vlc_native_track_selected_${type}", 8_000) {
                vlcAuditPlayer!!.currentTracks.groups.any { it.mediaTrackGroup == choice.group && it.isTrackSelected(choice.index) }
            }
        }
        report.putBoolean("vlc_audio_and_subtitle_native_selection_confirmed", true)
        revealControls(fullscreen!!)
        onMain { playerView.findViewById<View>(R.id.pov_subtitles).performClick() }
        await("vlc_subtitle_sheet_visible", 3_000) { fullscreen!!.currentOptionSheet?.let(::validSheetGeometry) == true }
        closeSheet(onMain { fullscreen!!.currentOptionSheet!! })
        report.putBoolean("vlc_subtitle_menu_rendered", true)
        ensureVlcFixtureTime()

        stage = "vlc_local_srt_fixture"
        val fixture = File.createTempFile("pov-public-audit-", ".srt", targetContext.cacheDir)
        subtitleFixture = fixture
        fixture.writeText("1\n00:00:00,000 --> 00:01:00,000\nPOV public subtitle audit\n", Charsets.UTF_8)
        val normalized = fixture.inputStream().use(ExternalSubtitleFiles::readSrt)
        fixture.writeText(normalized, Charsets.UTF_8)
        checkAudit(onMain { InAppPlaybackSession.loadSubtitle(Uri.fromFile(fixture), "Public audit SRT", "en") })
        await("vlc_external_srt_selected", 8_000) {
            val tracks = vlcAuditPlayer!!.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            tracks.sumOf { it.length } > counts.second && tracks.any { it.isSelected }
        }
        report.putBoolean("vlc_owned_local_srt_attached_and_selected", true)
        report.putBoolean("vlc_srt_fixture_parser_used", true)
        report.putBoolean("document_picker_available", onMain {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .resolveActivity(targetContext.packageManager) != null
        })
        ensureVlcFixtureTime()

        stage = "vlc_browse_handoff"
        val beforeBrowse = onMain { vlcAuditPlayer!!.currentPosition }
        val framesBeforeBrowse = onMain { renderedVideoFrames() }
        onMain { fullscreen!!.onBackPressed() }
        waitForIdleSync()
        await("vlc_mini_attached_same_session", 6_000) {
            InAppPlaybackSession.currentPlayer === vlcAuditPlayer &&
                descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>().any { it.player === vlcAuditPlayer }
        }
        firstMiniPlayerView = onMain { descendants(main!!.findViewById(android.R.id.content))
            .filterIsInstance<PlayerView>().first { it.player === vlcAuditPlayer } }
        await("vlc_mini_clock_advances", 7_000) { vlcAuditPlayer!!.currentPosition > beforeBrowse + 400 }
        verifyPublicVideoMotion("vlc_mini", main!!, firstMiniPlayerView!!, true, framesBeforeBrowse)
        report.putBoolean("vlc_mini_same_player_and_advancing_clock", true)
        fullscreen = returnToFullscreen()
        checkAudit(onMain { playerViewFor(fullscreen!!).player === vlcAuditPlayer &&
            InAppPlaybackSession.currentPlayer === vlcAuditPlayer && vlcAuditPlayer!!.currentPosition >= beforeBrowse })
        verifyPublicVideoMotion("vlc_returned_fullscreen", fullscreen!!, playerViewFor(fullscreen!!), false, onMain { renderedVideoFrames() })
        report.putBoolean("vlc_fullscreen_return_same_session", true)
        ensureVlcFixtureTime()

        onMain { vlcAuditPlayer!!.pause() }
        val pausedAt = awaitPausedClock(vlcAuditPlayer!!)
        onMain { fullscreen!!.onBackPressed() }
        waitForIdleSync()
        await("vlc_paused_mini_attached", 6_000) { firstMiniPlayerView?.player === vlcAuditPlayer }
        SystemClock.sleep(600)
        val miniDelta = onMain { vlcAuditPlayer!!.currentPosition - pausedAt }
        report.putLong("vlc_paused_mini_position_delta_ms", miniDelta)
        checkAudit(onMain { !vlcAuditPlayer!!.isPlaying && !vlcAuditPlayer!!.playWhenReady } && kotlin.math.abs(miniDelta) < 250)
        fullscreen = returnToFullscreen()
        val fullscreenDelta = onMain { vlcAuditPlayer!!.currentPosition - pausedAt }
        report.putLong("vlc_paused_fullscreen_position_delta_ms", fullscreenDelta)
        checkAudit(onMain { playerViewFor(fullscreen!!).player === vlcAuditPlayer && !vlcAuditPlayer!!.playWhenReady } && kotlin.math.abs(fullscreenDelta) < 250)
        report.putBoolean("vlc_paused_handoffs_preserve_session_and_clock", true)

        stage = "vlc_direct_visible_stop"
        revealControls(fullscreen!!)
        onMain {
            val stop = playerViewFor(fullscreen!!).findViewById<View>(R.id.pov_stop)
            checkAudit(fullyRendered(stop) && insideController(stop, playerViewFor(fullscreen!!)))
            playerViewFor(fullscreen!!).findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()
        }
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        checkAudit(onMain { fullscreen!!.currentFocus?.id == R.id.pov_stop })
        report.putBoolean("vlc_stop_one_dpad_right_from_play", true)
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_CENTER)
        await("vlc_direct_stop_released", 4_000) { !InAppPlaybackSession.hasActive() && InAppPlaybackSession.currentPlayer == null }
        report.putBoolean("vlc_visible_stop_releases_session", true)
    }

    private fun ensureVlcFixtureTime() {
        val needsRewind = onMain { vlcAuditPlayer!!.duration > 0 && vlcAuditPlayer!!.currentPosition > vlcAuditPlayer!!.duration / 2 }
        if (!needsRewind) return
        onMain { vlcAuditPlayer!!.seekTo(1_000) }
        await("vlc_progressive_fixture_rewind", 7_000) { vlcAuditPlayer!!.isPlaying && vlcAuditPlayer!!.currentPosition < 5_000 }
        report.putBoolean("native_progressive_fixture_rewound_for_audit", true)
    }

    private fun recordVlcState() {
        val current = vlcAuditPlayer ?: return
        if (InAppPlaybackSession.currentPlayer !== current || testUrl != PUBLIC_VLC_SAMPLE ||
            !InAppPlaybackSession.matches(Intent().putExtra("url", PUBLIC_VLC_SAMPLE).putExtra("key", ""))) return
        report.putLong("vlc_diagnostic_position_ms", current.currentPosition)
        report.putLong("vlc_diagnostic_duration_ms", current.duration)
        report.putLong("vlc_diagnostic_displayed_pictures", current.renderedOutputFrames)
        report.putInt("vlc_diagnostic_playback_state", current.playbackState)
        report.putBoolean("vlc_diagnostic_adapter_is_playing", current.isPlaying)
        report.putBoolean("vlc_diagnostic_native_is_playing", current.nativeIsPlaying)
        report.putBoolean("vlc_diagnostic_play_when_ready", current.playWhenReady)
        report.putBoolean("vlc_diagnostic_output_ready", current.startupOutputReady)
        report.putBoolean("vlc_diagnostic_pause_ack_pending", current.nativePausePending)
        report.putBoolean("vlc_diagnostic_play_ack_pending", current.nativePlayPending)
        report.putBoolean("vlc_diagnostic_seek_pending", current.startupSeekPending)
        report.putBoolean("vlc_diagnostic_surfaces_attached", current.nativeSurfacesAttached)
        report.putBoolean("vlc_diagnostic_surfaces_ready", current.nativeSurfacesReady)
        report.putInt("vlc_diagnostic_video_track", current.nativeVideoTrack)
        report.putInt("vlc_diagnostic_vout_count", current.nativeVoutCount)
        report.putInt("vlc_diagnostic_video_width", current.videoSize.width)
        report.putInt("vlc_diagnostic_video_height", current.videoSize.height)
        fullscreen?.takeUnless { it.isFinishing || it.isDestroyed }?.let { activity ->
            val view = playerViewFor(activity)
            report.putBoolean("vlc_diagnostic_view_has_player", view.player === current)
            report.putBoolean("vlc_diagnostic_video_surface_valid", (view.videoSurfaceView as? android.view.SurfaceView)?.holder?.surface?.isValid == true)
            report.putBoolean("vlc_diagnostic_subtitle_surface_valid", view.findViewWithTag<android.view.SurfaceView>("pov_vlc_subtitle_surface")?.holder?.surface?.isValid == true)
        }
    }

    private fun selectAuditEngine(engine: PlaybackEngine) {
        checkAudit(!InAppPlaybackSession.hasActive())
        if (originalPlaybackPreferences == null) originalPlaybackPreferences =
            targetContext.getSharedPreferences("pov_playback_preferences", Context.MODE_PRIVATE).all.toMap()
        PlaybackPreferences(targetContext).apply { selectedEngine = engine; subtitleLanguage = "en" }
    }

    /** A known synthetic row exercises the production menu; no actual recent/history row is edited. */
    private fun auditFeatures() {
        stage = "continue_fixture_precondition"
        checkAudit(onMain { !InAppPlaybackSession.hasActive() })
        onMain {
            val preferences = targetContext.getSharedPreferences("playback", 0)
            originalRecent = preferences.getString("recent", null)
            originalHiddenContinue = preferences.getString("hidden_continue", null)
            originalPending = preferences.getString("pending", null)
            hiddenSnapshotTaken = true
            PlaybackStore(targetContext).beginPlayback(CONTINUE_FIXTURE_KEY, null)
        }
        main = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .putExtra("playback_audit_preview", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
        waitForIdleSync()
        onMain { main!!.showContinueAuditFixture() }
        await("continue_fixture_visible", 4_000) {
            main!!.findViewById<ViewGroup>(android.R.id.content)
                .findViewWithTag<View>("resume:$CONTINUE_FIXTURE_KEY")?.let(::fullyRendered) == true
        }
        onMain {
            val row = main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("resume:$CONTINUE_FIXTURE_KEY")
            checkAudit(row.requestFocus() && row.performLongClick())
        }
        stage = "continue_fixture_remove_menu_action"
        await("continue_fixture_owned_dialog_visible", 3_000) { main!!.currentContinueOptionsDialog?.isShowing == true }
        val dialog = onMain { requireNotNull(main!!.currentContinueOptionsDialog).also {
            checkAudit(it.listView.adapter.count == 2 && it.listView.adapter.getItem(1).toString() == "Remove from Continue Watching")
            it.listView.requestFocus(); it.listView.setSelection(0)
        } }
        press(dialog, KeyEvent.KEYCODE_DPAD_DOWN)
        checkAudit(onMain { dialog.listView.selectedItemPosition == 1 })
        press(dialog, KeyEvent.KEYCODE_DPAD_CENTER)
        waitForIdleSync()
        await("continue_fixture_removed_from_visible_rows", 4_000) {
            main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("resume:$CONTINUE_FIXTURE_KEY") == null
        }
        checkAudit(onMain { PlaybackStore(targetContext).isHidden(CONTINUE_FIXTURE_KEY) })
        report.putBoolean("continue_fixture_production_long_press_menu_used", true)
        report.putBoolean("continue_fixture_remove_selected_with_native_dpad", true)
        report.putBoolean("continue_fixture_removed_from_visible_rows", true)
        report.putBoolean("continue_fixture_hidden_persists_in_fresh_store", true)
        onMain { main!!.showContinueAuditFixture() }
        checkAudit(onMain { main!!.findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<View>("resume:$CONTINUE_FIXTURE_KEY") == null })
        report.putBoolean("continue_fixture_stays_hidden_after_page_refresh", true)
        checkAudit(onMain { targetContext.getSharedPreferences("playback", 0).getString("recent", null) == originalRecent })
        report.putBoolean("continue_fixture_did_not_modify_recent_history", true)
    }

    private fun restorePlaybackPreferences() {
        val original = originalPlaybackPreferences ?: return
        val preferences = targetContext.getSharedPreferences("pov_playback_preferences", Context.MODE_PRIVATE)
        val editor = preferences.edit().clear()
        original.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        checkAudit(editor.commit())
        report.putBoolean("playback_preferences_restored", preferences.all == original)
        checkAudit(preferences.all == original)
    }

    /** Offline production-row fixture: no service request, media link, or account/library label. */
    private fun auditSourceChoices() {
        stage = "offline_source_fixture_precondition"
        originalRecent = onMain { targetContext.getSharedPreferences("playback", 0).getString("recent", null) }
        checkAudit(onMain { !InAppPlaybackSession.hasActive() })
        main = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("source_choice_preview", true)) as MainActivity
        waitForIdleSync()
        await("offline_source_rows_available", 5_000) { fixtureRows("source:").size == 70 }
        report.putBoolean("offline_synthetic_source_fixture", true)
        report.putInt("source_choice_rows", 70)
        fun assertHeader(name: String) {
            expectedChoiceIndex = -1
            await(name, 3_000) {
                val header = main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("fixture:source-service")
                header != null && main!!.currentFocus === header && fullyRendered(header)
            }
        }
        fun step(index: Int, key: Int, name: String) {
            expectedChoiceIndex = index
            stage = name
            press(main!!, key)
            assertFocusedFixtureRow(onMain { fixtureRows("source:")[index] }, name)
        }
        // Do not request focus on a source row: the production-like header must
        // receive initial focus, and native Down must enter the first source.
        assertHeader("source_natural_header_focus")
        for (index in 0 until 70) step(index, KeyEvent.KEYCODE_DPAD_DOWN, "source_round1_down_$index")
        for (index in 68 downTo 0) step(index, KeyEvent.KEYCODE_DPAD_UP, "source_round1_up_$index")
        expectedChoiceIndex = -1
        press(main!!, KeyEvent.KEYCODE_DPAD_UP)
        assertHeader("source_up_returns_to_header")
        report.putBoolean("source_header_to_first_and_back_by_native_dpad", true)
        step(0, KeyEvent.KEYCODE_DPAD_DOWN, "source_second_entry_first_row")
        step(1, KeyEvent.KEYCODE_DPAD_DOWN, "source_second_entry_second_row")
        report.putBoolean("source_top_down_again_does_not_jump_to_bottom", true)
        for (index in 2 until 70) step(index, KeyEvent.KEYCODE_DPAD_DOWN, "source_round2_down_$index")
        for (index in 68 downTo 0) step(index, KeyEvent.KEYCODE_DPAD_UP, "source_round2_up_$index")
        report.putInt("source_complete_roundtrips", 2)
        report.putBoolean("source_two_complete_native_dpad_roundtrips", true)
        report.putBoolean("all_source_rows_reachable_in_both_directions", true)
        var fileSelections = 0
        var currentSourceIndex = 0
        for (sourceIndex in listOf(1, 17, 35, 52, 68)) {
            for (index in currentSourceIndex + 1..sourceIndex) step(index, KeyEvent.KEYCODE_DPAD_DOWN, "source_selecting_middle_$index")
            val source = onMain { fixtureRows("source:")[sourceIndex] }
            assertFocusedFixtureRow(source, "source_middle_${sourceIndex}_focused")
            val key = onMain { source.tag as String }
            press(main!!, KeyEvent.KEYCODE_DPAD_CENTER)
            await("fixture_file_rows_available", 5_000) { fixtureRows("file:torrents:1234:").size == 7 }
            val files = onMain { fixtureRows("file:torrents:1234:") }
            // The child page's normal focus restoration must choose the first
            // file; programmatic row focus would conceal another entry failure.
            expectedChoiceIndex = 0
            assertFocusedFixtureRow(files.first(), "file_natural_first_row_visible")
            for (fileIndex in 0..6) {
                expectedChoiceIndex = fileIndex
                if (fileIndex > 0) press(main!!, KeyEvent.KEYCODE_DPAD_DOWN)
                assertFocusedFixtureRow(files[fileIndex], "file_down_${fileIndex}_visible")
                if (fileIndex in 1..5) {
                    press(main!!, KeyEvent.KEYCODE_DPAD_CENTER)
                    await("file_middle_${fileIndex}_exact_selection", 2_000) {
                        val selection = main!!.findViewById<ViewGroup>(android.R.id.content)
                            .findViewWithTag<android.widget.TextView>("fixture:selection")
                        selection?.text?.toString() == "Selected file version ${fileIndex + 1}" &&
                            main!!.currentFocus === files[fileIndex]
                    }
                    fileSelections++
                }
            }
            for (fileIndex in 5 downTo 0) {
                expectedChoiceIndex = fileIndex
                press(main!!, KeyEvent.KEYCODE_DPAD_UP)
                assertFocusedFixtureRow(files[fileIndex], "file_up_${fileIndex}_visible")
            }
            expectedChoiceIndex = sourceIndex
            press(main!!, KeyEvent.KEYCODE_BACK)
            await("back_restores_exact_source_choice", 3_000) {
                val focused = main!!.currentFocus
                focused is TvChoiceRow && focused.tag == key && fullyRendered(focused)
            }
            currentSourceIndex = sourceIndex
        }
        report.putInt("middle_sources_opened_by_dpad", 5)
        report.putInt("middle_files_selected_by_dpad", fileSelections)
        report.putBoolean("all_file_rows_reachable_in_both_directions", true)
        report.putBoolean("all_focused_source_file_cards_fully_visible", true)
        report.putBoolean("back_restores_exact_source_choice", true)
        report.putBoolean("source_file_children_do_not_steal_focus", true)
    }

    /** Only numeric positions from the owned synthetic choice fixture; never labels or a hierarchy. */
    private fun recordChoiceFocusFailure() {
        val activity = main ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val sources = fixtureRows("source:")
        val files = fixtureRows("file:torrents:1234:")
        val focused = activity.currentFocus
        fun contains(row: View): Boolean {
            var current: View? = focused
            while (current != null) {
                if (current === row) return true
                current = current.parent as? View
            }
            return false
        }
        report.putInt("choice_expected_index", expectedChoiceIndex)
        report.putInt("source_fixture_row_count", sources.size)
        report.putInt("file_fixture_row_count", files.size)
        report.putInt("source_focused_row_index", sources.indexOfFirst(::contains))
        report.putInt("file_focused_row_index", files.indexOfFirst(::contains))
        report.putBoolean("source_header_focused", focused?.tag == "fixture:source-service")
        report.putBoolean("choice_row_itself_focused", (sources + files).any { it === focused })
        val scroll = descendants(root).filterIsInstance<android.widget.ScrollView>().firstOrNull()
        report.putInt("choice_scroll_y", scroll?.scrollY ?: -1)
        report.putInt("choice_viewport_height", scroll?.height ?: -1)
        report.putInt("choice_content_height", scroll?.getChildAt(0)?.height ?: -1)
        focused?.let { report.putString("choice_focused_screen_bounds", visibleScreenBounds(it)?.flattenToString() ?: "none") }
    }

    private fun auditMiniOnly() {
        report.putBoolean("mini_only_mode", true)
        onMain { auditPlayer!!.play() }
        await("muted_playback_started", 15_000) { auditPlayer!!.isPlaying }
        preparePublicMovingWindow()
        val beforeBrowse = onMain { auditPlayer!!.currentPosition }
        val framesBeforeBrowse = onMain { renderedVideoFrames() }
        onMain { fullscreen!!.onBackPressed() }
        waitForIdleSync()
        await("mini_view_attached_same_player", 5_000) {
            InAppPlaybackSession.playerOrNull === auditPlayer &&
                descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>().any { it.player === auditPlayer }
        }
        checkAudit(onMain { main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) === originalBrowseRoot })
        report.putBoolean("browse_root_retained_after_fullscreen", true)
        firstMiniPlayerView = onMain {
            descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>().first { it.player === auditPlayer }
        }
        await("browse_playback_advances", 7_000) { auditPlayer!!.isPlaying && auditPlayer!!.currentPosition > beforeBrowse + 400 }
        report.putBoolean("position_advances_while_browsing", true)
        verifyPublicVideoMotion("mini", main!!, firstMiniPlayerView!!, true, framesBeforeBrowse)
        val framesBeforeReturn = onMain { renderedVideoFrames() }
        fullscreen = returnToFullscreen()
        checkAudit(onMain { InAppPlaybackSession.playerOrNull === auditPlayer &&
            playerViewFor(fullscreen!!).player === auditPlayer && auditPlayer!!.currentPosition >= beforeBrowse })
        report.putBoolean("return_uses_same_player_without_reset", true)
        verifyPublicVideoMotion("fullscreen", fullscreen!!, playerViewFor(fullscreen!!), false, framesBeforeReturn)
        val framesBeforeRepeatBrowse = onMain { renderedVideoFrames() }
        onMain { fullscreen!!.onBackPressed() }
        waitForIdleSync()
        await("repeat_mini_reuses_view", 5_000) {
            firstMiniPlayerView?.player === auditPlayer &&
                main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) === originalBrowseRoot
        }
        report.putBoolean("existing_mini_player_view_reused", true)
        verifyPublicVideoMotion("repeat_mini", main!!, firstMiniPlayerView!!, true, framesBeforeRepeatBrowse)
        onMain { InAppPlaybackSession.stop() }
        checkAudit(onMain { !InAppPlaybackSession.hasActive() })
        report.putBoolean("stop_releases_session", true)
    }

    private fun fixtureRows(prefix: String): List<TvChoiceRow> =
        descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<TvChoiceRow>()
            .filter { (it.tag as? String)?.startsWith(prefix) == true }

    private fun assertFocusedFixtureRow(row: TvChoiceRow, name: String) {
        await(name, 3_000) {
            main!!.currentFocus === row && row.hasFocus() && fullyRendered(row) &&
                row.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    private fun returnToFullscreen(): PlayerActivity {
        stage = "main_home_to_mini"
        enterMiniFromHomeEdge()
        stage = "mini_menu_key"
        press(main!!, KeyEvent.KEYCODE_MENU)
        await("mini_menu_dialog", 3_000) {
            main!!.currentPlaybackOptionsDialog?.let {
                it.isShowing && it.window?.decorView?.tag == "pov_mini_playback_options"
            } == true
        }
        report.putBoolean("mini_menu_opens_playback_options", true)
        closeSheet(onMain { main!!.currentPlaybackOptionsDialog!! })
        stage = "mini_hold_select_fallback"
        onMain {
            val mini = main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("return-to-video")
            mini.requestFocus()
            checkAudit(mini.performLongClick())
        }
        await("mini_hold_select_dialog", 3_000) { main!!.currentPlaybackOptionsDialog?.isShowing == true }
        closeSheet(onMain { main!!.currentPlaybackOptionsDialog!! })
        report.putBoolean("mini_hold_select_opens_playback_options", true)
        stage = "mini_center_returns_fullscreen"
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        try {
            onMain { main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("return-to-video").requestFocus() }
            press(main!!, KeyEvent.KEYCODE_DPAD_CENTER)
            val result = waitForMonitorWithTimeout(monitor, 5_000) as? PlayerActivity
            checkAudit(result != null)
            waitForIdleSync()
            report.putBoolean("mini_center_returns_fullscreen", true)
            return result!!
        } finally { removeMonitor(monitor) }
    }

    private fun enterMiniFromHomeEdge() {
        onMain {
            val home = main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("nav:home")
            checkAudit(home != null)
            home.requestFocus(); home.performClick()
        }
        waitForIdleSync()
        var edge: View? = null
        await("home_content_edge_available", 15_000) {
            val field = MainActivity::class.java.getDeclaredField("body").apply { isAccessible = true }
            val body = field.get(main) as ViewGroup
            edge = body.getFocusables(View.FOCUS_FORWARD).lastOrNull { candidate ->
                val next = FocusFinder.getInstance().findNextFocus(body, candidate, View.FOCUS_RIGHT)
                candidate !== body && candidate.isShown && candidate.isEnabled && candidate.isFocusable &&
                    (next == null || next === candidate || next === body)
            }
            auditHomeEdge = edge
            edge != null
        }
        onMain { checkAudit(edge!!.requestFocus()) }
        waitForIdleSync()
        await("home_content_edge_focused_visible", 3_000) { main!!.currentFocus === edge && fullyRendered(edge!!) }
        press(main!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        await("right_edge_enters_visible_mini", 3_000) {
            val mini = main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<ViewGroup>("return-to-video")
            mini != null && mini.hasFocus() && rendered(mini) && mini.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        report.putBoolean("home_right_edge_enters_focused_mini", true)
    }

    private fun playerViewFor(activity: PlayerActivity): PlayerView = requireNotNull(
        activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<PlayerView>("pov_fullscreen_player"))

    private fun traverseAudioSheet(dialog: Dialog, requireScrolling: Boolean = true) {
        val rows = onMain {
            descendants(dialog.window!!.decorView).filter { view ->
                (view.tag as? String)?.startsWith("pov_sheet_choice_") == true
            }.sortedBy { (it.tag as String).removePrefix("pov_sheet_choice_").toInt() }
        }
        checkAudit(rows.size >= 3)
        val initiallyOffscreen = onMain { !fullyRendered(rows.last()) }
        if (requireScrolling) checkAudit(initiallyOffscreen)
        onMain { checkAudit(rows.first().requestFocus()) }
        await("audio_sheet_first_row_focused_visible", 3_000) {
            dialog.currentFocus === rows.first() && fullyRendered(rows.first())
        }
        for (index in 1 until rows.size) {
            press(dialog, KeyEvent.KEYCODE_DPAD_DOWN)
            await("audio_sheet_row_${index}_focused_visible", 3_000) {
                dialog.currentFocus === rows[index] && fullyRendered(rows[index])
            }
        }
        report.putInt("audio_sheet_rows_reached_by_dpad", rows.size)
        report.putBoolean("audio_sheet_last_row_initially_offscreen", initiallyOffscreen)
        report.putBoolean("audio_sheet_all_focused_rows_fully_visible", true)
        report.putBoolean("audio_sheet_last_choice_reached_by_dpad", true)
    }

    private val chromeIds = listOf(androidx.media3.ui.R.id.exo_progress, androidx.media3.ui.R.id.exo_play_pause,
        androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_ffwd, R.id.pov_audio, R.id.pov_subtitles,
        R.id.pov_boost, R.id.pov_browse, R.id.pov_stop, R.id.pov_loading_spinner)

    private fun validControllerGeometry(playerView: PlayerView): Boolean {
        val bottom = playerView.findViewById<View>(R.id.pov_bottom_scrim)
        val screenBounds = Rect()
        val bottomBounds = Rect()
        if (!playerView.getGlobalVisibleRect(screenBounds) || !bottom.getGlobalVisibleRect(bottomBounds)) return false
        if (bottomBounds.top < screenBounds.top + screenBounds.height() / 2 || !screenBounds.contains(bottomBounds)) return false
        val heightDp = bottomBounds.height() / playerView.resources.displayMetrics.density
        if (heightDp !in 120f..135f) return false
        return chromeIds.filter { it != R.id.pov_loading_spinner }.all { id ->
            val child = playerView.findViewById<View>(id)
            val bounds = Rect()
            rendered(child) && child.getGlobalVisibleRect(bounds) && bottomBounds.contains(bounds) && insideController(child, playerView)
        }
    }

    /** Fixed view IDs and numeric geometry only: never labels, titles, URLs, or account state. */
    private fun recordControllerGeometry(playerView: PlayerView) {
        val metrics = targetContext.resources.displayMetrics
        report.putInt("geometry_display_density_dpi", metrics.densityDpi)
        report.putInt("geometry_display_width_px", metrics.widthPixels)
        report.putInt("geometry_display_height_px", metrics.heightPixels)
        report.putInt("geometry_font_scale_percent", (targetContext.resources.configuration.fontScale * 100).toInt())
        val namedViews = listOf(
            "player" to playerView,
            "controller" to playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller),
            "scrim" to playerView.findViewById<View>(R.id.pov_bottom_scrim),
            "actions" to playerView.findViewById<View>(R.id.pov_player_actions),
            "loading_status" to playerView.findViewById<View>(R.id.pov_buffering_status),
            "timeline" to playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress),
            "play_pause" to playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause),
            "rewind" to playerView.findViewById<View>(androidx.media3.ui.R.id.exo_rew),
            "forward" to playerView.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd),
            "audio" to playerView.findViewById<View>(R.id.pov_audio),
            "subtitles" to playerView.findViewById<View>(R.id.pov_subtitles),
            "boost" to playerView.findViewById<View>(R.id.pov_boost),
            "browse" to playerView.findViewById<View>(R.id.pov_browse),
            "stop" to playerView.findViewById<View>(R.id.pov_stop),
            "loading_spinner" to playerView.findViewById<View>(R.id.pov_loading_spinner)
        )
        namedViews.forEach { (name, view) ->
            if (view == null) {
                report.putBoolean("geometry_${name}_missing", true)
            } else {
                val visibleBounds = Rect()
                val hasVisibleBounds = view.getGlobalVisibleRect(visibleBounds)
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                report.putString("geometry_${name}_bounds", visibleBounds.flattenToString())
                report.putString("geometry_${name}_layout", "${location[0]} ${location[1]} ${view.width} ${view.height}")
                report.putString("geometry_${name}_padding", "${view.paddingLeft} ${view.paddingTop} ${view.paddingRight} ${view.paddingBottom}")
                report.putInt("geometry_${name}_visibility", view.visibility)
                report.putBoolean("geometry_${name}_shown", view.isShown)
                report.putBoolean("geometry_${name}_has_visible_bounds", hasVisibleBounds)
                report.putBoolean("geometry_${name}_inside_controller", insideController(view, playerView))
            }
        }
    }

    private fun validSheetGeometry(dialog: Dialog): Boolean {
        val content = dialog.window?.decorView?.findViewWithTag<View>("pov_player_sheet") ?: return false
        val bounds = visibleScreenBounds(content) ?: return false
        val display = targetContext.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
        return fullyRendered(content) && !bounds.isEmpty && display.contains(bounds) &&
            bounds.bottom > display.height() * 0.7
    }

    private fun recordSheetGeometry(dialog: Dialog) {
        report.putBoolean("sheet_dialog_showing", dialog.isShowing)
        report.putInt("sheet_display_width_px", targetContext.resources.displayMetrics.widthPixels)
        report.putInt("sheet_display_height_px", targetContext.resources.displayMetrics.heightPixels)
        val window = dialog.window
        report.putBoolean("sheet_window_missing", window == null)
        window?.attributes?.let {
            report.putInt("sheet_window_gravity", it.gravity)
            report.putInt("sheet_window_width", it.width)
            report.putInt("sheet_window_height", it.height)
            report.putInt("sheet_window_x", it.x)
            report.putInt("sheet_window_y", it.y)
        }
        val decor = window?.decorView
        val content = decor?.findViewWithTag<View>("pov_player_sheet")
        for ((name, view) in listOf("decor" to decor, "content" to content, "focus" to dialog.currentFocus)) {
            report.putBoolean("sheet_${name}_missing", view == null)
            if (view != null) {
                val bounds = Rect()
                val hasBounds = view.getGlobalVisibleRect(bounds)
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                report.putString("sheet_${name}_bounds", bounds.flattenToString())
                report.putString("sheet_${name}_screen_bounds", visibleScreenBounds(view)?.flattenToString() ?: "none")
                report.putString("sheet_${name}_layout", "${location[0]} ${location[1]} ${view.width} ${view.height}")
                report.putInt("sheet_${name}_visibility", view.visibility)
                report.putBoolean("sheet_${name}_shown", view.isShown)
                report.putBoolean("sheet_${name}_has_bounds", hasBounds)
                report.putBoolean("sheet_${name}_window_focus", view.hasWindowFocus())
            }
        }
    }

    private fun insideController(child: View, playerView: PlayerView): Boolean {
        val controller = playerView.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
        var parent = child.parent
        while (parent is View && parent !== controller) parent = parent.parent
        return parent === controller
    }

    private fun allControlsHidden(playerView: PlayerView): Boolean =
        playerView.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller).visibility == View.GONE &&
            chromeIds.all { !rendered(playerView.findViewById(it)) } &&
            !rendered(playerView.findViewById(androidx.media3.ui.R.id.exo_buffering))

    private fun rendered(view: View): Boolean {
        val bounds = Rect()
        return view.isShown && view.alpha > 0 && view.getGlobalVisibleRect(bounds) && !bounds.isEmpty
    }

    private fun fullyRendered(view: View): Boolean {
        val visible = visibleScreenBounds(view) ?: return false
        val location = IntArray(2)
        if (!rendered(view)) return false
        view.getLocationOnScreen(location)
        return visible.contains(Rect(location[0], location[1], location[0] + view.width, location[1] + view.height))
    }

    private fun visibleScreenBounds(view: View): Rect? {
        val visible = Rect()
        val rootOffset = Point()
        if (!view.getGlobalVisibleRect(visible, rootOffset)) return null
        val screenOrigin = IntArray(2)
        view.getLocationOnScreen(screenOrigin)
        // getGlobalVisibleRect is relative to the view root, which starts at a dialog's window
        // origin. Translate its already-clipped rectangle into screen coordinates before comparison.
        visible.offset(screenOrigin[0] - rootOffset.x - view.scrollX,
            screenOrigin[1] - rootOffset.y - view.scrollY)
        return visible
    }

    private fun press(activity: Activity, key: Int, settleIdle: Boolean = true) {
        checkAudit(onMain { !activity.isFinishing && !activity.isDestroyed })
        val previousStage = stage
        await("activity_window_has_input_focus", 1_500) { activity.hasWindowFocus() }
        stage = previousStage
        injectKey(key, settleIdle)
    }

    private fun revealControls(activity: PlayerActivity) {
        // Exercise the same reveal/interaction path as a TV remote. Calling PlayerView.showController()
        // directly bypasses the app's last-user-interaction deadline and can immediately hide chrome.
        val playerView = onMain { playerViewFor(activity) }
        report.putInt("controls_reveal_attempt", ++controlsRevealAttempts)
        report.putInt("controls_reveal_timeout_ms", onMain { playerView.controllerShowTimeoutMs })
        report.putBoolean("controls_reveal_visible_before_input", onMain { playerView.isControllerFullyVisible })
        val started = SystemClock.elapsedRealtime()
        // Synchronous injection already waits for native input dispatch. Waiting
        // for the whole UI to become idle can outlast the very timeout under test.
        press(activity, KeyEvent.KEYCODE_DPAD_UP, settleIdle = false)
        report.putLong("controls_reveal_input_dispatch_ms", SystemClock.elapsedRealtime() - started)
        report.putBoolean("controls_reveal_visible_after_input", onMain { playerView.isControllerFullyVisible })
        await("remote_controls_visible", 1_000) { playerViewFor(activity).isControllerFullyVisible }
    }

    private fun press(dialog: Dialog, key: Int) {
        checkAudit(onMain { dialog.isShowing })
        val previousStage = stage
        await("dialog_window_has_input_focus", 1_500) { dialog.window?.decorView?.hasWindowFocus() == true }
        stage = previousStage
        injectKey(key)
    }

    private fun injectKey(key: Int, settleIdle: Boolean = true) {
        // Direct Activity/Dialog.dispatchKeyEvent bypasses ViewRootImpl's unhandled directional
        // navigation. Inject into the actual focused window so DPAD tests exercise native focus.
        val downAt = SystemClock.uptimeMillis()
        fun event(action: Int) = KeyEvent(downAt, SystemClock.uptimeMillis(), action, key, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD)
        checkAudit(uiAutomation.injectInputEvent(event(KeyEvent.ACTION_DOWN), true))
        checkAudit(uiAutomation.injectInputEvent(event(KeyEvent.ACTION_UP), true))
        if (settleIdle) waitForIdleSync()
    }

    private fun closeSheet(dialog: Dialog) {
        press(dialog, KeyEvent.KEYCODE_BACK)
        checkAudit(onMain { !dialog.isShowing })
    }

    private fun recordFailureFocus() {
        val activity = fullscreen?.takeUnless { it.isDestroyed || it.isFinishing } ?: main
        val dialog = fullscreen?.currentOptionSheet ?: main?.currentContinueOptionsDialog ?: main?.currentPlaybackOptionsDialog
        fun record(prefix: String, focused: View?) {
            val id = focused?.id ?: View.NO_ID
            report.putInt("${prefix}_resource_id", id)
            report.putString("${prefix}_view_class", focused?.javaClass?.simpleName ?: "none")
            if (id != View.NO_ID) {
                try { report.putString("${prefix}_resource_name", targetContext.resources.getResourceEntryName(id)) }
                catch (_: android.content.res.Resources.NotFoundException) { /* Dynamically assigned IDs have no resource name. */ }
            }
        }
        record("failure_activity_focus", activity?.currentFocus)
        record("failure_dialog_focus", dialog?.currentFocus)
        report.putBoolean("failure_dialog_showing", dialog?.isShowing == true)
    }

    private fun recordMiniFocus() {
        val activity = main ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val mini = root.findViewWithTag<ViewGroup>("return-to-video")
        val field = MainActivity::class.java.getDeclaredField("body").apply { isAccessible = true }
        val body = field.get(activity) as ViewGroup
        val focused = activity.currentFocus
        var ancestor = focused
        while (ancestor != null && ancestor !== body) ancestor = ancestor.parent as? View
        val insideBody = ancestor === body
        report.putBoolean("mini_diagnostic_focused_is_mini", focused === mini)
        report.putBoolean("mini_diagnostic_has_focus", mini?.hasFocus() == true)
        report.putBoolean("mini_diagnostic_shown", mini?.isShown == true)
        report.putBoolean("mini_diagnostic_blocks_descendants", mini?.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS)
        report.putBoolean("mini_diagnostic_focused_inside_body", insideBody)
        report.putBoolean("mini_diagnostic_root_retained", root.getChildAt(0) === originalBrowseRoot)
        report.putBoolean("mini_diagnostic_next_is_focused", insideBody &&
            FocusFinder.getInstance().findNextFocus(body, focused, View.FOCUS_RIGHT) === focused)
        report.putString("mini_diagnostic_screen_bounds", mini?.let { visibleScreenBounds(it)?.flattenToString() } ?: "none")
        val edge = auditHomeEdge
        report.putBoolean("edge_diagnostic_missing", edge == null)
        if (edge != null) {
            report.putBoolean("edge_diagnostic_is_current_focus", focused === edge)
            report.putBoolean("edge_diagnostic_attached", edge.isAttachedToWindow)
            report.putBoolean("edge_diagnostic_has_focus", edge.hasFocus())
            report.putBoolean("edge_diagnostic_shown", edge.isShown)
            report.putInt("edge_diagnostic_width", edge.width)
            report.putInt("edge_diagnostic_height", edge.height)
            report.putInt("edge_diagnostic_scale_x_permille", (edge.scaleX * 1000).toInt())
            report.putInt("edge_diagnostic_scale_y_permille", (edge.scaleY * 1000).toInt())
            val origin = IntArray(2)
            edge.getLocationOnScreen(origin)
            report.putString("edge_diagnostic_screen_origin", "${origin[0]} ${origin[1]}")
            report.putString("edge_diagnostic_visible_screen_bounds", visibleScreenBounds(edge)?.flattenToString() ?: "none")
            var parent = edge.parent as? View
            var index = 0
            while (parent != null && index < 8) {
                if (parent is android.widget.HorizontalScrollView || parent is android.widget.ScrollView) {
                    report.putString("edge_scroll_${index}_class", parent.javaClass.simpleName)
                    report.putString("edge_scroll_${index}_bounds", visibleScreenBounds(parent)?.flattenToString() ?: "none")
                    report.putString("edge_scroll_${index}_offset", "${parent.scrollX} ${parent.scrollY}")
                    report.putString("edge_scroll_${index}_size", "${parent.width} ${parent.height}")
                }
                parent = parent.parent as? View
                index++
            }
        }
    }

    private fun capturePublicScreenshot(name: String) {
        checkAudit(onMain { ownsFocusedPublicPlayback() })
        val screenshot = uiAutomation.takeScreenshot() ?: throw AuditFailure()
        try {
            checkAudit(onMain { ownsFocusedPublicPlayback() })
            FileOutputStream(File(targetContext.cacheDir, name)).use {
                checkAudit(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }

    private fun renderedVideoFrames(): Int {
        vlcAuditPlayer?.let { return it.renderedOutputFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        val counters = auditPlayer?.videoDecoderCounters ?: throw AuditFailure()
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount
    }

    /** Select motion before changing targets; a post-handoff seek could conceal a broken surface. */
    private fun preparePublicMovingWindow() {
        stage = "public_moving_window_before_handoff"
        checkAudit(testUrl == PUBLIC_SAMPLE && !vlcMode)
        val before = onMain { renderedVideoFrames() }
        onMain {
            checkAudit(ownsFocusedPublicPlayback())
            auditPlayer!!.seekTo(10_000)
        }
        await("verified_public_moving_window_ready_before_handoff", 15_000) {
            auditPlayer!!.isPlaying && auditPlayer!!.playbackState == Player.STATE_READY &&
                auditPlayer!!.currentPosition in 10_000L..15_000L && renderedVideoFrames() > before
        }
        // Independently decoded exact public file: same mini grid/ROI at10s changes
        //225/231/177 cells at+1/+2/+3s. The opening0s window can be nearly static.
        report.putBoolean("controlled_public_moving_window_before_handoff", true)
        report.putLong("controlled_public_moving_window_start_ms", onMain { auditPlayer!!.currentPosition })
    }

    /** Pixel arrays stay in memory and contain only the owned public video ROI, never browsing UI. */
    private fun verifyPublicVideoMotion(name: String, activity: Activity, view: PlayerView, mini: Boolean, framesBefore: Int) {
        checkAudit(name in MOTION_AUDIT_NAMES)
        if (testUrl == PUBLIC_SAMPLE && ownedPlayer is ExoPlayer) {
            report.putBoolean("${name}_qualification_without_counter_delay", !counterDiagnostic)
            if (counterDiagnostic) observeBeforeTargetScreenshots(name, activity, view)
        }
        val first = awaitPublicVideoPicture(name, activity, view, mini)
        stage = "${name}_public_video_motion"
        onMain { recordMedia3Decoder("${name}_baseline") }
        onMain { recordMedia3BindingGeometry(name, view) }
        val baselineFrames = onMain { renderedVideoFrames() }
        report.putInt("${name}_video_pre_baseline_buffer_delta", baselineFrames - framesBefore)
        var last: PublicVideoSample? = null
        var largestChange = 0
        var largestRawChange = 0
        var largestRgbDelta = 0
        var largestMeanRgbDelta = 0
        var renderedDelta = 0
        var pictureComparisons = 0
        var rejectedFollowupPictures = 0
        var motionConfirmed = false
        val positionBefore = onMain { ownedPlayer!!.currentPosition }
        try {
            for (attempt in 1..3) {
                SystemClock.sleep(1_000)
                val current = samplePublicVideoGrid(activity, view, mini)
                last?.pixels?.recycle()
                last = current
                checkAudit(first.region == current.region)
                renderedDelta = onMain { renderedVideoFrames() - baselineFrames }
                // Neither an opening shutter nor a later black transition is
                // continuing visible video motion between two actual pictures.
                if (!isPublicVideoPicture(current.grid)) {
                    rejectedFollowupPictures++
                    continue
                }
                pictureComparisons++
                val differences = current.grid.indices.map { index ->
                    val before = first.grid[index]
                    val after = current.grid[index]
                    kotlin.math.abs(((before shr 16) and 255) - ((after shr 16) and 255)) +
                        kotlin.math.abs(((before shr 8) and 255) - ((after shr 8) and 255)) +
                        kotlin.math.abs((before and 255) - (after and 255))
                }
                val changed = differences.count { it >= 24 }
                largestRawChange = maxOf(largestRawChange, differences.count { it > 0 })
                largestRgbDelta = maxOf(largestRgbDelta, differences.maxOrNull() ?: 0)
                largestMeanRgbDelta = maxOf(largestMeanRgbDelta, (differences.average() * 1000).toInt())
                largestChange = maxOf(largestChange, changed)
                if (changed >= 8 && renderedDelta > 0) {
                    motionConfirmed = true
                    break
                }
            }
            report.putInt("${name}_video_changed_grid_cells", largestChange)
            report.putInt("${name}_video_raw_changed_grid_cells", largestRawChange)
            report.putInt("${name}_video_max_grid_rgb_delta", largestRgbDelta)
            report.putInt("${name}_video_mean_grid_rgb_delta_milli", largestMeanRgbDelta)
            report.putInt("${name}_video_rendered_buffer_delta", renderedDelta)
            report.putInt("${name}_motion_picture_comparisons", pictureComparisons)
            report.putInt("${name}_motion_rejected_followup_pictures", rejectedFollowupPictures)
            report.putBoolean("${name}_visible_public_video_changes", largestChange >= 8)
            report.putBoolean("${name}_decoder_rendering_continues", renderedDelta > 0)
            report.putLong("${name}_motion_start_position_ms", positionBefore)
            report.putLong("${name}_motion_end_position_ms", onMain { ownedPlayer!!.currentPosition })
            recordPublicGridStats("${name}_first", first.grid)
            last?.let { recordPublicGridStats("${name}_last", it.grid) }
            onMain { recordMedia3Decoder("${name}_after_motion") }
            onMain { recordMedia3BindingGeometry(name, view) }
            if (testUrl == PUBLIC_SAMPLE && Media3DecoderPolicy.affected()) {
                val preserved = onMain {
                    auditPlayer!!.videoDecoderCounters?.let {
                        it.ensureUpdated()
                        media3DecoderName == "OMX.google.h264.decoder" && it.decoderInitCount == 1 && it.decoderReleaseCount == 0 &&
                            media3DecoderInitializations == 1 && media3DecoderReleases == 0
                    } == true
                }
                report.putBoolean("${name}_single_software_decoder_preserved", preserved)
                checkAudit(preserved)
            }
            if (!motionConfirmed) {
                onMain { recordPublicVideoTarget(name, activity, view, mini) }
                try {
                    capturePublicVideoSample("pov-public-motion-${name}-before.png", first, activity, view, mini)
                    last?.let { capturePublicVideoSample("pov-public-motion-${name}-after.png", it, activity, view, mini) }
                    report.putBoolean("${name}_public_roi_failure_saved", last != null)
                } catch (_: Throwable) { report.putBoolean("${name}_public_roi_failure_capture_unavailable", true) }
            }
            checkAudit(motionConfirmed)
        } finally {
            first.pixels.recycle()
            last?.pixels?.recycle()
        }
    }

    private data class PublicVideoSample(val region: Rect, val grid: IntArray, val pixels: Bitmap)

    private fun observeCurrentMedia3Target(fromSessionNotification: Boolean) {
        val current = auditPlayer ?: return
        if (testUrl != PUBLIC_SAMPLE || InAppPlaybackSession.currentPlayer !== current ||
            !InAppPlaybackSession.matches(Intent().putExtra("url", PUBLIC_SAMPLE).putExtra("key", ""))) return
        val target = publicVideoTargets().firstOrNull(InAppPlaybackSession::ownsVideoTarget) ?: return
        val switched = InAppPlaybackSession.videoTargetSwitchCount
        val previous = targetObservations[target]
        if (previous?.switched == switched) return
        targetObservations[target] = TargetObservation(switched, SystemClock.elapsedRealtime(), fromSessionNotification)
        val name = if (target.rootView === main?.window?.decorView) {
            if (previous == null) "mini" else "repeat_mini"
        } else "fullscreen"
        recordMedia3Decoder("${name}_counter_0")
        report.putLong("${name}_counter_0_position_ms", current.currentPosition)
        report.putLong("${name}_counter_0_elapsed_ms", 0)
    }

    /** No target screenshot is taken until this bounded observation has completed. */
    private fun observeBeforeTargetScreenshots(name: String, activity: Activity, view: PlayerView) {
        stage = "${name}_before_screenshot_counters"
        onMain { observeCurrentMedia3Target(false) }
        val observation = onMain { requireNotNull(targetObservations[view]) }
        report.putBoolean("${name}_attachment_observed_at_notification", observation.fromSessionNotification)
        report.putBoolean("${name}_counter_interval_precedes_target_screenshots", true)
        for (seconds in listOf(1, 3, 6)) {
            val remaining = observation.observedAt + seconds * 1_000 - SystemClock.elapsedRealtime()
            if (remaining > 0) SystemClock.sleep(remaining)
            onMain {
                checkAudit(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus() &&
                    InAppPlaybackSession.currentPlayer === auditPlayer && InAppPlaybackSession.ownsVideoTarget(view))
                recordMedia3Decoder("${name}_counter_$seconds")
                report.putLong("${name}_counter_${seconds}_position_ms", auditPlayer!!.currentPosition)
                report.putLong("${name}_counter_${seconds}_elapsed_ms", SystemClock.elapsedRealtime() - observation.observedAt)
            }
        }
    }

    /** Exact public fixtures only; wait for a picture without changing player state or output. */
    private fun awaitPublicVideoPicture(name: String, activity: Activity, view: PlayerView, mini: Boolean): PublicVideoSample {
        stage = "${name}_public_video_baseline"
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 5_000
        var attempts = 0
        var firstRejected: PublicVideoSample? = null
        var latestRejected: PublicVideoSample? = null
        report.putBoolean("${name}_baseline_picture_observed", false)
        try {
            while (true) {
                val current = samplePublicVideoGrid(activity, view, mini)
                attempts++
                if (isPublicVideoPicture(current.grid)) {
                    report.putBoolean("${name}_baseline_picture_observed", true)
                    return current
                }
                latestRejected?.takeUnless { it === firstRejected }?.pixels?.recycle()
                latestRejected = current
                if (firstRejected == null) firstRejected = current
                if (SystemClock.elapsedRealtime() >= deadline) {
                    recordPublicGridStats("${name}_first", firstRejected.grid)
                    recordPublicGridStats("${name}_last", current.grid)
                    onMain { recordPublicVideoTarget(name, activity, view, mini) }
                    try {
                        capturePublicVideoSample("pov-public-motion-${name}-before.png", firstRejected, activity, view, mini)
                        capturePublicVideoSample("pov-public-motion-${name}-after.png", current, activity, view, mini)
                        report.putBoolean("${name}_public_roi_failure_saved", true)
                    } catch (_: Throwable) { report.putBoolean("${name}_public_roi_failure_capture_unavailable", true) }
                    throw AuditFailure()
                }
                SystemClock.sleep(150)
            }
        } finally {
            report.putLong("${name}_baseline_wait_ms", SystemClock.elapsedRealtime() - started)
            report.putInt("${name}_baseline_samples", attempts)
            firstRejected?.pixels?.recycle()
            latestRejected?.takeUnless { it === firstRejected }?.pixels?.recycle()
        }
    }

    private fun isPublicVideoPicture(grid: IntArray): Boolean {
        val luma = grid.map { (((it shr 16) and 255) * 299 + ((it shr 8) and 255) * 587 + (it and 255) * 114) / 1000 }
        return luma.count { it > 8 } * 100 >= luma.size * 10 &&
            (luma.maxOrNull() ?: 0) - (luma.minOrNull() ?: 0) >= 16 &&
            grid.map { it and 0xF8F8F8 }.toSet().size >= 4
    }

    private fun recordPublicGridStats(prefix: String, grid: IntArray) {
        val luma = grid.map { (((it shr 16) and 255) * 299 + ((it shr 8) and 255) * 587 + (it and 255) * 114) / 1000 }
        report.putInt("${prefix}_roi_mean_luma", luma.average().toInt())
        report.putInt("${prefix}_roi_black_cells_percent", luma.count { it <= 8 } * 100 / luma.size)
        report.putInt("${prefix}_roi_luma_range", (luma.maxOrNull() ?: 0) - (luma.minOrNull() ?: 0))
        report.putInt("${prefix}_roi_unique_colors", grid.map { it and 0xF8F8F8 }.toSet().size)
    }

    /** Public-session numeric codec diagnostics, with only a sanitized codec identifier. */
    private fun recordMedia3Decoder(prefix: String) {
        val current = auditPlayer ?: return
        if (testUrl != PUBLIC_SAMPLE || InAppPlaybackSession.currentPlayer !== current ||
            !InAppPlaybackSession.matches(Intent().putExtra("url", PUBLIC_SAMPLE).putExtra("key", ""))) return
        report.putBoolean("${prefix}_decoder_name_available", media3DecoderName != null)
        media3DecoderName?.takeIf { it.matches(Regex("[A-Za-z0-9_.$-]{1,100}")) }?.let {
            report.putString("${prefix}_decoder_class", it.replace('-', '_'))
            report.putString("${prefix}_decoder_name", it)
        }
        android.os.Build.DEVICE.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) }?.let {
            report.putString("${prefix}_device_name", it)
        }
        report.putInt("${prefix}_decoder_init_events", media3DecoderInitializations)
        report.putInt("${prefix}_decoder_release_events", media3DecoderReleases)
        report.putInt("${prefix}_playback_state", current.playbackState)
        report.putBoolean("${prefix}_is_loading", current.isLoading)
        report.putBoolean("${prefix}_is_playing", current.isPlaying)
        report.putInt("${prefix}_playback_suppression", current.playbackSuppressionReason)
        report.putLong("${prefix}_buffered_position_ms", current.bufferedPosition)
        report.putInt("${prefix}_sdk_api", android.os.Build.VERSION.SDK_INT)
        report.putBoolean("${prefix}_device_is_svp_dtv15", android.os.Build.DEVICE == "SVP-DTV15")
        current.videoFormat?.let {
            report.putInt("${prefix}_format_width", it.width)
            report.putInt("${prefix}_format_height", it.height)
            report.putInt("${prefix}_format_frame_rate_milli", if (it.frameRate > 0) (it.frameRate * 1000).toInt() else -1)
            report.putBoolean("${prefix}_format_is_h264", it.sampleMimeType == "video/avc")
            report.putBoolean("${prefix}_format_is_vp9", it.sampleMimeType == "video/x-vnd.on2.vp9")
        }
        current.videoDecoderCounters?.let {
            it.ensureUpdated()
            report.putInt("${prefix}_decoder_init_count", it.decoderInitCount)
            report.putInt("${prefix}_decoder_release_count", it.decoderReleaseCount)
            report.putInt("${prefix}_queued_input_count", it.queuedInputBufferCount)
            report.putInt("${prefix}_skipped_input_count", it.skippedInputBufferCount)
            report.putInt("${prefix}_rendered_output_count", it.renderedOutputBufferCount)
            report.putInt("${prefix}_skipped_output_count", it.skippedOutputBufferCount)
            report.putInt("${prefix}_dropped_output_count", it.droppedBufferCount)
            report.putInt("${prefix}_dropped_input_count", it.droppedInputBufferCount)
            report.putInt("${prefix}_dropped_to_keyframe_count", it.droppedToKeyframeCount)
        }
    }

    private fun recordPublicSoftwareDecoders() {
        val current = auditPlayer ?: return
        if (testUrl != PUBLIC_SAMPLE || InAppPlaybackSession.currentPlayer !== current ||
            !InAppPlaybackSession.matches(Intent().putExtra("url", PUBLIC_SAMPLE).putExtra("key", ""))) return
        val formats = current.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).map(group::getTrackFormat) }
        for ((label, mime) in listOf("h264" to "video/avc", "vp9" to "video/x-vnd.on2.vp9")) {
            val candidates = formats.filter { it.sampleMimeType == mime }
            report.putInt("software_${label}_public_format_count", candidates.size)
            val fixture = candidates.firstOrNull { it.width == 720 && it.height == 576 }
            report.putBoolean("software_${label}_public_720x576_available", fixture != null)
            try {
                val decoders = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mime, false, false).filter { it.softwareOnly || it.name.startsWith("OMX.google.") || it.name.startsWith("c2.android.") }
                report.putInt("software_${label}_decoder_count", decoders.size)
                decoders.take(8).forEachIndexed { index, decoder ->
                    val prefix = "software_${label}_$index"
                    decoder.name.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) }?.let {
                        report.putString("${prefix}_decoder_name", it)
                    }
                    val supported = candidates.filter(decoder::isFormatSupported)
                    report.putInt("${prefix}_supported_public_formats", supported.size)
                    report.putBoolean("${prefix}_supports_public_720x576", fixture?.let(decoder::isFormatSupported) == true)
                    report.putBoolean("${prefix}_software_only", decoder.softwareOnly)
                    report.putBoolean("${prefix}_hardware_accelerated", decoder.hardwareAccelerated)
                    supported.maxByOrNull { it.width.toLong() * it.height }?.let {
                        report.putInt("${prefix}_max_public_width", it.width)
                        report.putInt("${prefix}_max_public_height", it.height)
                    }
                }
            } catch (failure: Exception) { report.putString("software_${label}_query_error_class", failure.javaClass.simpleName.take(80)) }
        }
    }

    /** Numeric properties of specifically owned public video views, never a UI hierarchy dump. */
    private fun recordPublicVideoTarget(name: String, activity: Activity, view: PlayerView, mini: Boolean) {
        recordMedia3Decoder("${name}_target_diagnostic")
        recordMedia3BindingGeometry(name, view)
        val region = publicVideoRegion(activity, view, mini)
        report.putString("${name}_roi_screen_bounds", region.flattenToString())
        report.putBoolean("${name}_session_owns_video_target", InAppPlaybackSession.ownsVideoTarget(view))
        report.putInt("${name}_observed_first_frame_events_total", observedFirstFrames)
        report.putInt("${name}_observed_first_frame_events_for_target", firstFramesByTarget[view] ?: 0)
        report.putInt("${name}_unmatched_media3_first_frame_events", unmatchedMedia3FirstFrames)
        report.putInt("${name}_surface_loss_count", InAppPlaybackSession.videoSurfaceLossCount)
        report.putInt("${name}_surface_rebind_count", InAppPlaybackSession.videoSurfaceRebindCount)
        report.putInt("${name}_target_switch_count", InAppPlaybackSession.videoTargetSwitchCount)
        report.putBoolean("${name}_target_window_hardware_accelerated", view.isHardwareAccelerated)
        report.putInt("${name}_video_width", ownedPlayer!!.videoSize.width)
        report.putInt("${name}_video_height", ownedPlayer!!.videoSize.height)
        val surface = view.videoSurfaceView
        report.putBoolean("${name}_video_surface_valid", (surface as? android.view.SurfaceView)?.holder?.surface?.isValid == true)
        val shutter = view.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)
        for ((label, target) in listOf("player" to view, "surface" to surface, "shutter" to shutter, "mini_frame" to if (mini) view.parent as? View else null)) {
            if (target == null) continue
            report.putInt("${name}_${label}_visibility", target.visibility)
            report.putBoolean("${name}_${label}_shown", target.isShown)
            report.putInt("${name}_${label}_alpha_permille", (target.alpha * 1000).toInt())
            report.putInt("${name}_${label}_z_permille", (target.z * 1000).toInt())
            report.putString("${name}_${label}_screen_bounds", visibleScreenBounds(target)?.flattenToString() ?: "none")
        }
        if (mini) {
            val frame = view.parent as? View
            val shell = frame?.parent as? ViewGroup
            report.putInt("${name}_mini_shell_child_index", if (frame != null && shell != null) shell.indexOfChild(frame) else -1)
            report.putInt("${name}_mini_shell_child_count", shell?.childCount ?: -1)
        }
    }

    private fun recordMedia3BindingGeometry(prefix: String, view: PlayerView) {
        if (testUrl != PUBLIC_SAMPLE || InAppPlaybackSession.currentPlayer !== auditPlayer || auditPlayer == null ||
            !InAppPlaybackSession.matches(Intent().putExtra("url", PUBLIC_SAMPLE).putExtra("key", ""))) return
        val binding = InAppPlaybackSession.videoBindingGeometry(view) ?: return
        report.putInt("${prefix}_bind_surface_width", binding.surfaceWidthBefore)
        report.putInt("${prefix}_bind_surface_height", binding.surfaceHeightBefore)
        report.putInt("${prefix}_bind_buffer_width", binding.bufferWidthBefore)
        report.putInt("${prefix}_bind_buffer_height", binding.bufferHeightBefore)
        report.putInt("${prefix}_bind_content_width", binding.contentWidthBefore)
        report.putInt("${prefix}_bind_content_height", binding.contentHeightBefore)
        report.putBoolean("${prefix}_layout_requested_by_binding", binding.layoutRequestedAfterBinding)
        report.putInt("${prefix}_post_bind_surface_resizes", binding.surfaceResizeCount)
        report.putInt("${prefix}_post_bind_buffer_resizes", binding.bufferResizeCount)
        report.putInt("${prefix}_post_bind_surface_width", binding.surfaceWidthAfter)
        report.putInt("${prefix}_post_bind_surface_height", binding.surfaceHeightAfter)
        report.putInt("${prefix}_post_bind_buffer_width", binding.bufferWidthAfter)
        report.putInt("${prefix}_post_bind_buffer_height", binding.bufferHeightAfter)
    }

    private fun recordPendingMedia3Geometry() {
        if (testUrl != PUBLIC_SAMPLE || InAppPlaybackSession.currentPlayer !== auditPlayer || auditPlayer == null ||
            !InAppPlaybackSession.matches(Intent().putExtra("url", PUBLIC_SAMPLE).putExtra("key", ""))) return
        publicVideoTargets().forEachIndexed { index, view ->
            val prefix = "pending_surface_$index"
            val surface = view.videoSurfaceView as? android.view.SurfaceView ?: return@forEachIndexed
            val content = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
            report.putBoolean("${prefix}_has_player", view.player === auditPlayer)
            report.putBoolean("${prefix}_player_layout_pending", view.isLayoutRequested)
            report.putBoolean("${prefix}_content_layout_pending", content?.isLayoutRequested == true)
            report.putBoolean("${prefix}_surface_layout_pending", surface.isLayoutRequested)
            report.putInt("${prefix}_view_width", surface.width)
            report.putInt("${prefix}_view_height", surface.height)
            report.putInt("${prefix}_buffer_width", surface.holder.surfaceFrame.width())
            report.putInt("${prefix}_buffer_height", surface.holder.surfaceFrame.height())
        }
    }

    private fun capturePublicVideoSample(name: String, sample: PublicVideoSample, activity: Activity, view: PlayerView, mini: Boolean) {
        checkAudit(onMain { publicVideoRegion(activity, view, mini) == sample.region })
        FileOutputStream(File(targetContext.cacheDir, name)).use {
            checkAudit(sample.pixels.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        checkAudit(onMain { publicVideoRegion(activity, view, mini) == sample.region })
    }

    private fun publicVideoRegion(activity: Activity, view: PlayerView, mini: Boolean): Rect {
        val current = ownedPlayer ?: throw AuditFailure()
        checkAudit(testUrl == expectedPublicUrl && InAppPlaybackSession.currentPlayer === current)
        checkAudit(InAppPlaybackSession.matches(Intent().putExtra("url", expectedPublicUrl).putExtra("key", "")))
        checkAudit(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus())
        checkAudit(view.isAttachedToWindow && view.hasWindowFocus() && view.player === current &&
            view.rootView === activity.window.decorView && current.isPlaying)
        if (mini) {
            checkAudit(activity === main && view === firstMiniPlayerView && main!!.currentPlaybackOptionsDialog?.isShowing != true)
        } else {
            checkAudit(activity === fullscreen && view === playerViewFor(fullscreen!!) && fullscreen!!.currentOptionSheet?.isShowing != true)
        }
        val surface = view.videoSurfaceView ?: throw AuditFailure()
        checkAudit(fullyRendered(surface))
        val video = visibleScreenBounds(surface) ?: throw AuditFailure()
        // Inset from surface edges; keep the mini ROI above its caption and fullscreen ROI above chrome.
        return Rect(video.left + video.width() * 20 / 100, video.top + video.height() * 20 / 100,
            video.left + video.width() * 80 / 100, video.top + video.height() * (if (mini) 50 else 60) / 100)
            .also { checkAudit(it.width() >= 40 && it.height() >= 24) }
    }

    private fun samplePublicVideoGrid(activity: Activity, view: PlayerView, mini: Boolean): PublicVideoSample {
        val region = onMain { publicVideoRegion(activity, view, mini) }
        val screenshot = uiAutomation.takeScreenshot() ?: throw AuditFailure()
        var crop: Bitmap? = null
        var readable: Bitmap? = null
        var transferred = false
        try {
            // Revalidate after capture as well: user navigation cannot make an unrelated window a sample.
            checkAudit(onMain { publicVideoRegion(activity, view, mini) == region })
            checkAudit(Rect(0, 0, screenshot.width, screenshot.height).contains(region))
            val cropped = Bitmap.createBitmap(screenshot, region.left, region.top, region.width(), region.height())
            crop = cropped
            val pixels = cropped.copy(Bitmap.Config.ARGB_8888, false) ?: throw AuditFailure()
            readable = pixels
            val grid = IntArray(20 * 12) { index ->
                val x = ((index % 20 + 0.5) * pixels.width / 20).toInt().coerceIn(1, pixels.width - 2)
                val y = ((index / 20 + 0.5) * pixels.height / 12).toInt().coerceIn(1, pixels.height - 2)
                var red = 0; var green = 0; var blue = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val color = pixels.getPixel(x + dx, y + dy)
                    red += (color shr 16) and 255; green += (color shr 8) and 255; blue += color and 255
                }
                (red / 9 shl 16) or (green / 9 shl 8) or (blue / 9)
            }
            transferred = true
            return PublicVideoSample(region, grid, pixels)
        } finally {
            if (!transferred) readable?.takeUnless { it === crop || it === screenshot }?.recycle()
            crop?.takeUnless { it === screenshot }?.recycle()
            screenshot.recycle()
        }
    }

    private fun ownsFocusedPublicPlayback(): Boolean {
        val activity = fullscreen ?: return false
        if (testUrl != expectedPublicUrl || ownedPlayer == null ||
            InAppPlaybackSession.currentPlayer !== ownedPlayer || activity.isFinishing || activity.isDestroyed) return false
        if (!InAppPlaybackSession.matches(Intent().putExtra("url", expectedPublicUrl).putExtra("key", ""))) return false
        val ownsActivityFocus = activity.hasWindowFocus()
        val sheet = activity.currentOptionSheet
        val ownsSheetFocus = sheet?.isShowing == true && sheet.window?.decorView?.hasWindowFocus() == true
        return ownsActivityFocus || ownsSheetFocus
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun await(name: String, timeoutMs: Long, predicate: () -> Boolean) {
        stage = name
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (onMain(predicate)) return
            SystemClock.sleep(100)
        }
        throw AuditFailure()
    }

    private fun awaitPausedClock(player: Player): Long {
        stage = "paused_clock_acknowledgment"
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var lastPosition = Long.MIN_VALUE
        var stableSince = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = onMain { Triple(player.playWhenReady, player.isPlaying, player.currentPosition) }
            val now = SystemClock.elapsedRealtime()
            if (!snapshot.first && !snapshot.second && lastPosition != Long.MIN_VALUE &&
                kotlin.math.abs(snapshot.third - lastPosition) <= 20) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= 350) return snapshot.third
            } else stableSince = 0L
            lastPosition = snapshot.third
            SystemClock.sleep(100)
        }
        report.putBoolean("pause_clock_play_when_ready", onMain { player.playWhenReady })
        report.putBoolean("pause_clock_is_playing", onMain { player.isPlaying })
        throw AuditFailure()
    }

    private fun checkAudit(value: Boolean) { if (!value) throw AuditFailure() }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        runOnMainSync {
            try { result.set(block()) } catch (failure: Throwable) { error.set(failure) }
        }
        error.get()?.let { throw it }
        return result.get()
    }

    private class AuditFailure : RuntimeException()

    companion object {
        private val MOTION_AUDIT_NAMES = setOf("mini", "fullscreen", "repeat_mini", "vlc_fullscreen", "vlc_mini", "vlc_returned_fullscreen")
        private const val CONTINUE_FIXTURE_KEY = "pov-public-continue-audit"
        // Published in shaka-project/shaka-player demo/common/assets.js as multilingual with subtitles.
        private const val PUBLIC_SAMPLE = "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd"
        // Official IETF CELLAR Matroska test 5: Blender Elephants Dream, 2 AAC + 8 SubRip tracks.
        private const val PUBLIC_VLC_SAMPLE = "https://raw.githubusercontent.com/ietf-wg-cellar/matroska-test-files/master/test_files/test5.mkv"
    }
}

