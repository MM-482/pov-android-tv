package dev.pov.tv

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import dev.pov.tv.data.TraktApi
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * One in-app playback session shared by browsing and fullscreen.
 * No background service: playback pauses when all application activities leave the foreground.
 * Views borrow the player; destroying an Activity does not destroy the session.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object InAppPlaybackSession {
    private var application: Application? = null
    private var player: Player? = null
    private var mediaSession: MediaSession? = null
    private var targetView: WeakReference<PlayerView>? = null
    private lateinit var history: PlaybackStore
    private lateinit var trakt: TraktApi
    private var sourceUrl = ""
    private var sourceHeaders: Map<String, String> = emptyMap()
    private var sourceKey = ""
    private var identity: PlaybackIdentity? = null
    var title: String = ""
        private set
    var error: PlaybackException? = null
        private set
    internal var startFailure: String? = null
        private set
    /** Preserved for the existing decoder/PCM instrumentation audit. UI uses currentPlayer. */
    val playerOrNull: ExoPlayer? get() = player as? ExoPlayer
    val currentPlayer: Player? get() = player
    internal fun ownsVideoTarget(view: PlayerView): Boolean = targetView?.get() === view && view.player === player
    internal var videoSurfaceLossCount = 0
        private set
    internal var videoSurfaceRebindCount = 0
        private set
    internal var videoTargetSwitchCount = 0
        private set
    private val videoBindingGeometry = java.util.WeakHashMap<PlayerView, VideoBindingGeometry>()
    internal fun videoBindingGeometry(view: PlayerView): VideoBindingGeometry? = videoBindingGeometry[view]?.copy()
    var engine: PlaybackEngine = PlaybackEngine.MEDIA3
        private set
    var engineNotice: String? = null
        private set
    internal val subtitleIdentity: PlaybackIdentity? get() = identity
    internal val supportsVolumeBoost: Boolean get() = engine == PlaybackEngine.MEDIA3
    internal val volumeBoost = SessionVolumeBoost(
        dispatchToMain = { task -> handler.post { task() } },
        onStateChanged = { notifyChanged() },
    )
    private var remotePercent = 0.0
    private var releasing = false
    private var lastSyncAction = ""
    private var lastSyncAt = 0L
    private var resumeAfterBackground = false
    private var backgroundPause = false
    private val listeners = linkedSetOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private val surfaceAttachment = SurfaceReadyAttachment<Player, PlayerView>(
        currentSession = { player?.takeUnless { releasing } },
        isReady = { view ->
            val current = player
            if (current is ExoPlayer) Media3VideoLayout.prime(view, current.videoSize)
            val surface = view.videoSurfaceView as? SurfaceView
            view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
                view.isLaidOut && !view.isLayoutRequested && view.width > 0 && view.height > 0 &&
                surface?.isShown == true && surface.windowVisibility == View.VISIBLE &&
                surface.width > 0 && surface.height > 0 && !surface.isLayoutRequested &&
                surface.holder.surface.isValid && surface.holder.surfaceFrame.width() > 0 && surface.holder.surfaceFrame.height() > 0 &&
                (current !is ExoPlayer || Media3VideoLayout.geometryMatchesSurface(view))
        },
        observeReadiness = { view, ready -> observeSurfaceReadiness(view, ready) },
        attach = { exo, view ->
            val previous = targetView?.get()
            if (exo is ExoPlayer) {
                val surface = view.videoSurfaceView as SurfaceView
                val content = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
                videoBindingGeometry[view] = VideoBindingGeometry(surface.width, surface.height,
                    surface.holder.surfaceFrame.width(), surface.holder.surfaceFrame.height(), content.width, content.height)
            }
            if (previous === view) {
                if (view.player !== exo) view.player = exo
                else (view.videoSurfaceView as? SurfaceView)?.let {
                    exo.clearVideoSurfaceView(it)
                    exo.setVideoSurfaceView(it)
                    videoSurfaceRebindCount++
                }
            } else {
                PlayerView.switchTargetView(exo, previous, view)
                videoTargetSwitchCount++
            }
            previous?.keepScreenOn = false
            targetView = WeakReference(view)
            videoBindingGeometry[view]?.layoutRequestedAfterBinding = view.isLayoutRequested ||
                view.videoSurfaceView?.isLayoutRequested == true
            view.keepScreenOn = exo.isPlaying
            // Pending attachment can finish after Activity.onStart. Refresh controller scheduling
            // only after ownership is committed; same-target reentry below does not notify again.
            notifyChanged()
        },
        keepObservingAfterAttach = true,
        observeSurfaceLoss = { view, lost ->
            val holder = (view.videoSurfaceView as? SurfaceView)?.holder
            val callback = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) { lost() }
            }
            holder?.addCallback(callback)
            ({ holder?.removeCallback(callback) })
        },
        onSurfaceLost = { exo, view ->
            // A SurfaceView can recreate its native surface while its PlayerView
            // and holder identities remain unchanged (for example on window return).
            // Clear once now; the persistent readiness observer rebinds once the
            // replacement has real dimensions. Playback/position remain untouched.
            if (targetView?.get() === view && view.player === exo) {
                (view.videoSurfaceView as? SurfaceView)?.let(exo::clearVideoSurfaceView)
                videoSurfaceLossCount++
            }
        },
    )
    private val syncWorker = Executors.newSingleThreadExecutor()
    private val checkpoint = object : Runnable {
        override fun run() {
            if (player == null || releasing) return
            saveLocal()
            handler.postDelayed(this, 15_000)
        }
    }
    private val foregroundGate = PlaybackForegroundGate(
        schedule = { handler.postDelayed(it, 350) },
        cancel = { handler.removeCallbacks(it) },
        onBackground = {
            val current = player
            resumeAfterBackground = current != null && current.playWhenReady &&
                current.playbackState != Player.STATE_ENDED && current.playerError == null && error == null
            backgroundPause = true
            current?.pause()
            backgroundPause = false
            handler.removeCallbacks(checkpoint)
        },
        onForeground = {
            if (resumeAfterBackground && error == null && player?.playerError == null && player?.playbackState != Player.STATE_ENDED) player?.play()
            resumeAfterBackground = false
            restartCheckpoint()
        }
    )

    fun initialize(context: Context) {
        if (application != null) return
        val app = context.applicationContext as Application
        application = app
        history = PlaybackStore(app)
        trakt = TraktApi(SecureStore(app))
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foregroundGate.started(activity) }
            override fun onActivityStopped(activity: Activity) { foregroundGate.stopped(activity) }
            override fun onActivityDestroyed(activity: Activity) { foregroundGate.stopped(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    fun hasActive(): Boolean = player != null
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun prepareRequest(url: String, headers: Map<String, String>): String = ProtectedPlaybackRequests.add(url, headers)

    /** Returns false for an invalid link without disturbing an existing session. */
    internal fun start(context: Context, intent: Intent, saved: Bundle? = null): Boolean {
        initialize(context)
        startFailure = null
        val address = intent.getStringExtra("url").orEmpty()
        val uri = try { Uri.parse(address) } catch (_: RuntimeException) { null }
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return false
        val requestToken = intent.getStringExtra("playback_request")?.takeIf(String::isNotBlank)
        val request = requestToken?.let(ProtectedPlaybackRequests::take)
        if ((requestToken != null && (request == null || request.url != address)) ||
            (saved?.getBoolean("protectedPlayback", false) == true && request == null)) {
            startFailure = "Reopen this protected source from the library to refresh its credentials."
            return false
        }
        stopInternal(notify = false)
        videoSurfaceLossCount = 0; videoSurfaceRebindCount = 0; videoTargetSwitchCount = 0
        sourceUrl = address
        sourceHeaders = request?.headers ?: emptyMap()
        sourceKey = intent.getStringExtra("key").orEmpty()
        title = intent.getStringExtra("title").orEmpty()
        identity = try { intent.getStringExtra("identity")?.let { PlaybackIdentity.fromJson(JSONObject(it)) } } catch (_: Exception) { null }
        val position = (saved?.getLong("position") ?: intent.getLongExtra("resume", 0L)).coerceAtLeast(0)
        remotePercent = saved?.getDouble("remotePercent") ?: if (position == 0L) intent.getDoubleExtra("resume_percent", 0.0) else 0.0
        if (!remotePercent.isFinite() || remotePercent !in 0.0..100.0) remotePercent = 0.0
        val shouldPlay = saved?.getBoolean("playWhenReady", true) ?: intent.getBooleanExtra("playWhenReady", true)
        val parameters = try { saved?.getBundle("tracks")?.let(TrackSelectionParameters::fromBundle) } catch (_: RuntimeException) { null }
        releasing = false
        error = null
        lastSyncAction = ""
        lastSyncAt = 0L
        val preferences = PlaybackPreferences(context)
        engine = saved?.getString("engine")?.let { value -> PlaybackEngine.entries.firstOrNull { it.name == value } }
            ?: preferences.selectedEngine
        val route = PlaybackEngineRouting.choose(engine, address, sourceHeaders.isNotEmpty())
        engine = route.engine
        engineNotice = route.notice
        val exo: Player = try {
            if (engine == PlaybackEngine.VLC) LibVlcPlayer(NativeVlcDriver(application!!), preferences.subtitleLanguage)
            else createMedia3Player()
        } catch (_: LinkageError) {
            startFailure = "The embedded VLC engine is unavailable on this device. Choose Media3 in Settings."
            sourceHeaders = emptyMap()
            return false
        } catch (_: RuntimeException) {
            startFailure = "The playback engine could not start. Try another engine in Settings."
            sourceHeaders = emptyMap()
            return false
        }
        player = exo
        val openApp = PendingIntent.getActivity(application, 0, Intent(application, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSession.Builder(application!!, exo).setSessionActivity(openApp).build()
        configureSessionListener(exo)
        exo.setMediaItem(MediaItem.Builder().setUri(address)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build(), position)
        val initialTracks = parameters ?: exo.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(preferences.subtitleLanguage)
            .setSelectUndeterminedTextLanguage(false).build()
        exo.trackSelectionParameters = if (exo is ExoPlayer) Media3DecoderPolicy.trackParameters(initialTracks) else initialTracks
        exo.prepare()
        exo.playWhenReady = shouldPlay
        restartCheckpoint()
        notifyChanged()
        return true
    }

    private fun createMedia3Player(): ExoPlayer {
        val gainProcessor = volumeBoost.createAudioProcessor()
        val renderers = object : DefaultRenderersFactory(application!!) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val sink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(gainProcessor))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
                    .build()
                return object : ForwardingAudioSink(sink) {
                    private var encodedRoute = false
                    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                        super.configure(inputFormat, specifiedBufferSize, outputChannels)
                        // Keep device-supported encoded passthrough playable; it bypasses PCM DSP.
                        encodedRoute = inputFormat.sampleMimeType != MimeTypes.AUDIO_RAW
                        if (encodedRoute) gainProcessor.onEncodedPassthrough()
                    }
                    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                        // A pending format switch may drain old PCM during this call. The selected
                        // encoded route still must finish with an honest bypass status.
                        if (encodedRoute) gainProcessor.onEncodedPassthrough()
                        return handled
                    }
                }
            }
        }
        renderers.setMediaCodecSelector(Media3DecoderPolicy.selector())
        if (Media3DecoderPolicy.affected()) renderers.setEnableDecoderFallback(true)
        val builder = ExoPlayer.Builder(application!!, renderers)
        if (sourceHeaders.isNotEmpty()) {
            val headers = sourceHeaders.toMap()
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(application!!,
                DataSource.Factory { ProtectedHttpDataSource(headers) })))
        }
        return builder
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
    }

    private fun configureSessionListener(exo: Player) {
        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (!isCurrent(exo) || enforceVideoCompatibility(exo, tracks)) return
                notifyChanged()
            }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                if (isCurrent(exo)) notifyChanged()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isCurrent(exo)) return
                if (isPlaying && enforceVideoCompatibility(exo)) return
                targetView?.get()?.keepScreenOn = isPlaying
                // Buffering is not a user pause. Only genuine playback starts are recorded here.
                if (isPlaying && exo.duration > 0) { saveLocal(); sync("start") }
                notifyChanged()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!isCurrent(exo)) return
                if (playWhenReady && enforceVideoCompatibility(exo)) return
                if (!backgroundPause && !playWhenReady) resumeAfterBackground = false
                if (!playWhenReady && exo.playbackState != Player.STATE_ENDED && exo.duration > 0) {
                    saveLocal(); sync("pause")
                }
                notifyChanged()
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (isCurrent(exo) && playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE && exo.duration > 0) {
                    saveLocal(); sync("pause")
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (!isCurrent(exo)) return
                if (state != Player.STATE_ENDED && enforceVideoCompatibility(exo)) return
                if (state == Player.STATE_READY && remotePercent > 0 && exo.duration > 0) {
                    val percent = remotePercent
                    remotePercent = 0.0
                    exo.seekTo((exo.duration * percent / 100).toLong())
                }
                if (state == Player.STATE_ENDED) {
                    saveLocal(); sync("stop")
                    volumeBoost.resetGain()
                    targetView?.get()?.keepScreenOn = false
                    handler.removeCallbacks(checkpoint)
                }
                notifyChanged()
            }
            override fun onPlayerError(failure: PlaybackException) {
                if (!isCurrent(exo)) return
                if (error !is VideoCompatibilityException) error = failure
                saveLocal(); sync("pause")
                exo.pause()
                volumeBoost.resetGain()
                handler.removeCallbacks(checkpoint)
                notifyChanged()
            }
        })
    }

    internal fun matches(intent: Intent): Boolean = player != null &&
        sourceUrl == intent.getStringExtra("url").orEmpty() && sourceKey == intent.getStringExtra("key").orEmpty()

    fun attachPlayerView(view: PlayerView) {
        val exo = player ?: run { view.player = null; return }
        if (targetView?.get() === view && view.player === exo) {
            view.keepScreenOn = exo.isPlaying
            return
        }
        if (exo is ExoPlayer) Media3VideoLayout.prime(view, exo.videoSize)
        surfaceAttachment.request(exo, view)
    }

    fun detachPlayerView(view: PlayerView) {
        surfaceAttachment.cancel(view)
        if (targetView?.get() === view) targetView = null
        if (view.player != null) view.player = null
        videoBindingGeometry.remove(view)
        view.keepScreenOn = false
    }

    fun retry() {
        val exo = player ?: return
        // A decoder capability failure is deterministic for this copy; don't restart audio only.
        if (error is VideoCompatibilityException) { notifyChanged(); return }
        error = null
        exo.prepare(); exo.play()
        restartCheckpoint()
        notifyChanged()
    }

    fun stop() = stopInternal(notify = true)

    private fun stopInternal(notify: Boolean) {
        val exo = player
        if (exo != null) {
            saveLocal()
            if (exo.playbackState != Player.STATE_ENDED) {
                val watched = exo.duration > 0 && exo.currentPosition.toDouble() / exo.duration >= 0.80
                sync(if (watched) "stop" else "pause")
            }
        }
        releasing = true
        surfaceAttachment.cancelAll()
        handler.removeCallbacks(checkpoint)
        resumeAfterBackground = false
        volumeBoost.close()
        targetView?.get()?.let { it.player = null; it.keepScreenOn = false }
        targetView = null
        videoBindingGeometry.clear()
        mediaSession?.release(); mediaSession = null
        exo?.release(); player = null
        sourceUrl = ""; sourceHeaders = emptyMap(); sourceKey = ""; title = ""; identity = null
        remotePercent = 0.0; error = null; engineNotice = null
        if (notify) notifyChanged()
    }

    internal fun saveState(out: Bundle) {
        val exo = player ?: return
        out.putLong("position", exo.currentPosition.coerceAtLeast(0))
        out.putBoolean("playWhenReady", exo.playWhenReady && exo.playbackState != Player.STATE_ENDED)
        out.putDouble("remotePercent", remotePercent)
        out.putString("engine", engine.name)
        out.putBoolean("protectedPlayback", sourceHeaders.isNotEmpty())
        out.putBundle("tracks", exo.trackSelectionParameters.toBundle())
    }

    internal fun currentIntent(context: Context): Intent? {
        val exo = player ?: return null
        return Intent(context, PlayerActivity::class.java).putExtra("reuse_session", true)
            .putExtra("url", sourceUrl).putExtra("title", title).putExtra("key", sourceKey)
            .putExtra("resume", exo.currentPosition).putExtra("resume_percent", remotePercent)
            .putExtra("playWhenReady", exo.playWhenReady)
            .also { value -> identity?.let { value.putExtra("identity", it.toJson().toString()) } }
    }

    /** Caller has copied/downloaded a local SRT. Changes affect this session, keeping its clock and pause state. */
    internal fun loadSubtitle(uri: Uri, displayName: String, language: String?, mimeType: String = MimeTypes.APPLICATION_SUBRIP): Boolean {
        val current = player ?: return false
        if (uri.scheme != "file" && uri.scheme != "content") return false
        if (current is LibVlcPlayer) return current.addSubtitle(uri)
        val item = current.currentMediaItem ?: return false
        val external = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType).setLabel(displayName.take(160))
            .setLanguage(language).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val position = current.currentPosition.coerceAtLeast(0)
        val playing = current.playWhenReady
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(language).setSelectUndeterminedTextLanguage(true).build()
        current.setMediaItem(item.buildUpon().setSubtitleConfigurations(listOf(external)).build(), position)
        current.prepare(); current.playWhenReady = playing
        return true
    }

    internal fun setVolumeBoostGain(db: Int): VolumeBoostState {
        if (!supportsVolumeBoost) return if (db == 0) VolumeBoostState.OFF else VolumeBoostState.UNAVAILABLE
        val result = volumeBoost.setGain(db)
        notifyChanged()
        return result
    }

    private fun isCurrent(exo: Player) = !releasing && player === exo
    private fun observeSurfaceReadiness(view: PlayerView, ready: () -> Unit): () -> Unit {
        val surface = view.videoSurfaceView as? SurfaceView
        val holder = surface?.holder
        fun recordGeometry() {
            if (targetView?.get() !== view || view.player !== player) return
            val recorded = videoBindingGeometry[view] ?: return
            val currentSurface = surface ?: return
            val width = currentSurface.width
            val height = currentSurface.height
            val bufferWidth = holder?.surfaceFrame?.width() ?: 0
            val bufferHeight = holder?.surfaceFrame?.height() ?: 0
            if (recorded.surfaceWidthAfter != width || recorded.surfaceHeightAfter != height) recorded.surfaceResizeCount++
            if (recorded.bufferWidthAfter != bufferWidth || recorded.bufferHeightAfter != bufferHeight) recorded.bufferResizeCount++
            recorded.surfaceWidthAfter = width; recorded.surfaceHeightAfter = height
            recorded.bufferWidthAfter = bufferWidth; recorded.bufferHeightAfter = bufferHeight
        }
        val surfaceCallback = object : SurfaceHolder.Callback {
            // Finish the framework's created/changed callback dispatch before
            // installing output callbacks on this holder or rebinding its player.
            override fun surfaceCreated(holder: SurfaceHolder) { view.post { ready() } }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                recordGeometry(); view.post { ready() }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }
        // Creation can precede visible-window/layout state. The first eligible draw retries the
        // same pending request; no surface is attached while its holder is still invalid.
        val observer = view.viewTreeObserver
        val preDraw = ViewTreeObserver.OnPreDrawListener { ready(); true }
        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> recordGeometry() }
        holder?.addCallback(surfaceCallback)
        surface?.addOnLayoutChangeListener(layout)
        observer.addOnPreDrawListener(preDraw)
        return {
            holder?.removeCallback(surfaceCallback)
            surface?.removeOnLayoutChangeListener(layout)
            if (observer.isAlive) observer.removeOnPreDrawListener(preDraw)
            else view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        }
    }
    private fun enforceVideoCompatibility(exo: Player, tracks: Tracks = exo.currentTracks): Boolean {
        // Media3 capability reports cannot establish LibVLC's hardware/software decoder support.
        if (exo !is ExoPlayer) return false
        val existing = error as? VideoCompatibilityException
        val issue = existing?.issue ?: VideoPlaybackCompatibility.issue(tracks, exo.playbackState, exo.videoFormat)
        if (issue == null) return false
        if (existing == null) error = VideoCompatibilityException(issue)
        resumeAfterBackground = false
        exo.pause()
        volumeBoost.resetGain()
        targetView?.get()?.keepScreenOn = false
        handler.removeCallbacks(checkpoint)
        notifyChanged()
        return true
    }
    private fun notifyChanged() { listeners.toList().forEach { it() } }
    private fun restartCheckpoint() {
        handler.removeCallbacks(checkpoint)
        if (player != null && player?.playbackState != Player.STATE_ENDED && error == null) handler.postDelayed(checkpoint, 15_000)
    }
    private fun saveLocal() {
        if (error is VideoCompatibilityException) return
        // Public-stream audits and other transient playback must not create history entries.
        if (sourceKey.isBlank()) return
        val exo = player ?: return
        if (exo.duration <= 0) return
        try { history.save(sourceKey, title, identity, exo.currentPosition.coerceAtLeast(0), exo.duration) }
        catch (_: Exception) { /* Preserve playback if local storage is unavailable. */ }
    }
    private fun sync(action: String) {
        if (error is VideoCompatibilityException) return
        val media = identity ?: return
        val exo = player ?: return
        // These integrations default to off. MainActivity's per-service consent dialog names
        // the IDs/progress/events sent; only its explicit Enable tracking action enables them.
        // The queue also rechecks the same account-bound consent immediately before dispatch.
        if (exo.duration > 0) {
            try {
                if (AdditionalTracking.isEnabled(application!!, "mdblist") || AdditionalTracking.isEnabled(application!!, "simkl")) {
                    val percent = (exo.currentPosition.toDouble() * 100 / exo.duration).coerceIn(0.0, 100.0)
                    AdditionalTracking.record(application!!, media, percent, action)
                }
            } catch (_: Exception) { /* Optional, explicitly enabled tracking cannot interrupt playback. */ }
        }
        val connected = try { trakt.connected() } catch (_: Exception) { false }
        if (!connected || exo.duration <= 0) return
        val percent = (exo.currentPosition.toDouble() * 100 / exo.duration).coerceIn(0.0, 100.0)
        val now = android.os.SystemClock.elapsedRealtime()
        if (action == lastSyncAction && now - lastSyncAt < 3_000) return
        lastSyncAction = action; lastSyncAt = now
        val accountRevision = try { trakt.accountRevision() } catch (_: Exception) { return }
        val revision = try { if (action != "start") history.queue(media, percent, action) else null } catch (_: Exception) { null }
        syncWorker.execute {
            try {
                if (trakt.progressForAccount(media, percent, action, accountRevision) && revision != null) history.acknowledge(media, revision)
            } catch (_: Exception) { /* Pending progress retries at a future app launch. */ }
        }
    }
}

