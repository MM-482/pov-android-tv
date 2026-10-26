package dev.pov.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoSize
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import java.util.Locale

/** Native ownership stays with the session, never with either borrowing Activity. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class NativeVlcDriver(context: Context) : VlcPlaybackDriver {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    // Avoid native verbose logs containing signed playback URLs or provider credentials.
    private val library = LibVLC(app, arrayListOf("--quiet", "--no-video-title-show", "--network-caching=1500"))
    private val native = MediaPlayer(library)
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var closed = false
    private var focusHeld = false
    private var video: SurfaceView? = null
    private var subtitleSurface: SurfaceView? = null
    private var size = VideoSize.UNKNOWN
    private var loading = false
    private var readyForTracks = false
    private var normalVolume = 100
    private var outputCallback: IVLCVout.Callback? = null
    private val outputBinding = VlcOutputBinding { if (!closed) native.setVideoTrackEnabled(true) }
    private var voutCount = 0
    private val desiredTracks = mutableMapOf<Int, Int>()
    private val defaultTracks = mutableMapOf<Int, Int>()
    override var listener: ((VlcEvent) -> Unit)? = null
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({ change ->
            if (!closed && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
                focusHeld = false; listener?.invoke(VlcEvent.FOCUS_LOSS)
            } else if (!closed && change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) native.setVolume(normalVolume / 4)
            else if (!closed && change == AudioManager.AUDIOFOCUS_GAIN) native.setVolume(normalVolume)
        }, handler).build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!closed && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) listener?.invoke(VlcEvent.FOCUS_LOSS)
        }
    }
    private val layout = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        if (!closed && video === view) native.vlcVout.setWindowSize(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
    }

    init {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") app.registerReceiver(noisyReceiver, filter)
        native.setEventListener { event ->
            if (!closed) {
                val translated = when (event.type) {
                    MediaPlayer.Event.Opening -> { loading = true; VlcEvent.OPENING }
                    MediaPlayer.Event.Buffering -> { loading = event.buffering < 100f; VlcEvent.BUFFERING }
                    MediaPlayer.Event.Playing -> {
                        loading = false; readyForTracks = true
                        native.audioTrack.takeIf { it >= 0 }?.let { defaultTracks.putIfAbsent(C.TRACK_TYPE_AUDIO, it) }
                        defaultTracks.putIfAbsent(C.TRACK_TYPE_TEXT, native.spuTrack)
                        applyDesiredTracks(); VlcEvent.READY
                    }
                    MediaPlayer.Event.Paused -> VlcEvent.PAUSED
                    MediaPlayer.Event.Stopped -> VlcEvent.STOPPED
                    MediaPlayer.Event.EndReached -> VlcEvent.ENDED
                    MediaPlayer.Event.EncounteredError -> VlcEvent.ERROR
                    MediaPlayer.Event.ESAdded -> { applyDesiredTracks(); VlcEvent.CHANGED }
                    MediaPlayer.Event.ESSelected, MediaPlayer.Event.ESDeleted,
                    MediaPlayer.Event.LengthChanged, MediaPlayer.Event.SeekableChanged,
                    MediaPlayer.Event.TimeChanged -> VlcEvent.CHANGED
                    MediaPlayer.Event.Vout -> {
                        voutCount = event.voutCount
                        if (event.voutCount > 0) VlcEvent.FIRST_FRAME else VlcEvent.CHANGED
                    }
                    else -> null
                }
                translated?.let { listener?.invoke(it) }
            }
        }
    }

    override val position: Long get() = if (closed) 0 else native.time.coerceAtLeast(0)
    override val duration: Long get() = if (closed) 0 else native.length.coerceAtLeast(0)
    override val seekable: Boolean get() = !closed && native.isSeekable
    override val buffering: Boolean get() = loading
    override val videoSize: VideoSize get() = size
    override val actuallyPlaying: Boolean get() = !closed && native.isPlaying
    override val surfacesAttached: Boolean get() = !closed && native.vlcVout.areViewsAttached()
    override val surfacesReady: Boolean get() = outputBinding.surfacesReady
    override val nativeVideoTrack: Int get() = if (closed) -1 else native.videoTrack
    override val nativeVoutCount: Int get() = voutCount
    override val renderedFrames: Long get() {
        if (closed) return 0
        val media = native.media ?: return 0
        return try { media.stats?.displayedPictures?.toLong() ?: 0 } finally { media.release() }
    }

    override fun open(item: MediaItem, subtitleLanguage: String) {
        native.stop(); readyForTracks = false; desiredTracks.clear(); defaultTracks.clear(); size = VideoSize.UNKNOWN
        val media = Media(library, requireNotNull(item.localConfiguration).uri)
        try {
            media.setHWDecoderEnabled(true, false)
            if (subtitleLanguage.isNotBlank()) media.addOption(":sub-language=${languageAlternatives(subtitleLanguage)}")
            item.localConfiguration?.subtitleConfigurations?.forEach {
                media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, it.uri.toString()))
            }
            native.media = media
        } finally { media.release() }
    }

    override fun play() {
        if (!focusHeld) focusHeld = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) native.play() else listener?.invoke(VlcEvent.FOCUS_LOSS)
    }
    override fun pause() { native.pause() }
    override fun stop() { native.stop(); abandonFocus() }
    override fun seekTo(position: Long) { native.setTime(position.coerceAtLeast(0), false) }
    override fun setVolume(volume: Float) {
        normalVolume = (volume.coerceIn(0f, 1f) * 100).toInt()
        native.setVolume(normalVolume)
    }

    override fun tracks(): List<VlcTrack> {
        if (closed) return emptyList()
        val media = native.media
        val formats = mutableMapOf<Pair<Int, Int>, Format>()
        try {
            if (media != null) for (index in 0 until media.trackCount) {
                val track = media.getTrack(index) ?: continue
                val type = when (track.type) {
                    IMedia.Track.Type.Audio -> C.TRACK_TYPE_AUDIO
                    IMedia.Track.Type.Text -> C.TRACK_TYPE_TEXT
                    else -> continue
                }
                formats[type to track.id] = Format.Builder().setLanguage(track.language)
                    .setLabel(track.description).setCodecs(track.originalCodec)
                    .setSampleMimeType(mime(track.originalCodec, type))
                    .setChannelCount((track as? IMedia.AudioTrack)?.channels ?: Format.NO_VALUE)
                    .setAverageBitrate(track.bitrate.takeIf { it > 0 } ?: Format.NO_VALUE).build()
            }
        } finally { media?.release() }
        val result = mutableListOf<VlcTrack>()
        fun add(type: Int, descriptions: Array<MediaPlayer.TrackDescription>?, selected: Int) {
            descriptions?.filter { it.id >= 0 }?.forEach { track ->
                val format = VlcTrackFormats.named(formats[type to track.id], track.name, type)
                result += VlcTrack(track.id, type, format, track.id == selected)
            }
        }
        add(C.TRACK_TYPE_AUDIO, native.audioTracks, native.audioTrack)
        add(C.TRACK_TYPE_TEXT, native.spuTracks, native.spuTrack)
        return result
    }

    override fun selectTrack(type: Int, id: Int): Boolean {
        desiredTracks[type] = id
        if (!readyForTracks) return true
        return if (type == C.TRACK_TYPE_AUDIO) native.setAudioTrack(id) else native.setSpuTrack(id)
    }
    override fun selectAutomatic(type: Int, language: String) {
        desiredTracks.remove(type)
        if (!readyForTracks) return
        val choices = tracks().filter { it.type == type }
        val preferred = choices.firstOrNull { languageMatches(it.format.language, language) }
        val id = preferred?.id ?: defaultTracks[type] ?: choices.firstOrNull()?.id ?: -1
        if (type == C.TRACK_TYPE_AUDIO) native.setAudioTrack(id) else native.setSpuTrack(id)
    }

    private fun applyDesiredTracks() {
        if (!readyForTracks) return
        desiredTracks.forEach { (type, id) ->
            // ESAdded can precede the requested stream; leave it pending until it exists.
            if (id < 0 || tracks().any { it.id == id && it.type == type }) {
                if (type == C.TRACK_TYPE_AUDIO) native.setAudioTrack(id) else native.setSpuTrack(id)
            }
        }
        // Startup language selection belongs to the media's :sub-language option.
        // Do not restart an adaptive elementary stream on its first Playing/ESAdded
        // notification merely to reapply the same preference. Explicit menu Automatic
        // still calls selectAutomatic(), and pending manual/Off choices remain above.
    }

    override fun attach(view: SurfaceView) {
        if (video === view) return
        detach()
        video = view
        val parent = view.parent as? ViewGroup
        val subtitles = SurfaceView(view.context).apply {
            tag = "pov_vlc_subtitle_surface"; isFocusable = false
            setZOrderMediaOverlay(true); holder.setFormat(PixelFormat.TRANSLUCENT)
        }
        if (parent != null) {
            parent.addView(subtitles, parent.indexOfChild(view) + 1, FrameLayout.LayoutParams(-1, -1))
            subtitleSurface = subtitles
        }
        val vout = native.vlcVout
        val ready = outputBinding.begin()
        val callback = object : IVLCVout.Callback {
            override fun onSurfacesCreated(output: IVLCVout) {
                if (!closed && video === view && output === native.vlcVout) ready.created()
            }
            override fun onSurfacesDestroyed(output: IVLCVout) {
                if (video === view && output === native.vlcVout) ready.destroyed()
            }
        }
        outputCallback = callback
        vout.addCallback(callback)
        vout.setVideoView(view)
        subtitleSurface?.let(vout::setSubtitlesView)
        vout.setWindowSize(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
        vout.attachViews { _, width, height, visibleWidth, visibleHeight, sarNum, sarDen ->
            if (!closed && video === view && width > 0 && height > 0) {
                size = VideoSize(visibleWidth.takeIf { it > 0 } ?: width,
                    visibleHeight.takeIf { it > 0 } ?: height, 0,
                    if (sarDen > 0) sarNum.toFloat() / sarDen else 1f)
                listener?.invoke(VlcEvent.FIRST_FRAME)
            }
        }
        view.addOnLayoutChangeListener(layout)
    }

    override fun detach() {
        outputBinding.clear()
        outputCallback?.let { if (!closed) native.vlcVout.removeCallback(it) }
        outputCallback = null
        video?.removeOnLayoutChangeListener(layout)
        if (!closed && native.vlcVout.areViewsAttached()) native.vlcVout.detachViews()
        subtitleSurface?.let { (it.parent as? ViewGroup)?.removeView(it) }
        subtitleSurface = null; video = null
    }

    override fun addSubtitle(uri: Uri): Boolean {
        val result = native.addSlave(IMedia.Slave.Type.Subtitle, uri, true)
        // An explicitly loaded subtitle should not be replaced by a later language auto-selection.
        if (result) desiredTracks.remove(C.TRACK_TYPE_TEXT)
        return result
    }

    override fun release() {
        if (closed) return
        detach(); closed = true; listener = null
        native.setEventListener(null); native.stop(); native.release(); library.release()
        app.unregisterReceiver(noisyReceiver); abandonFocus()
    }

    private fun abandonFocus() { audio.abandonAudioFocusRequest(focusRequest); focusHeld = false }

    private fun languageMatches(raw: String?, preferred: String): Boolean {
        if (raw.isNullOrBlank() || preferred.isBlank()) return false
        return raw.lowercase(Locale.ROOT) in languageAlternatives(preferred).split(',')
    }
    private fun languageAlternatives(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val iso3 = try { locale.isO3Language } catch (_: Exception) { "" }
        return listOf(locale.language, iso3).filter(String::isNotBlank).distinct().joinToString(",")
    }
    private fun mime(codec: String?, type: Int): String = when (codec?.lowercase(Locale.ROOT)?.trim()) {
        "mp4a", "aac" -> "audio/mp4a-latm"
        "ac-3", "a52" -> "audio/ac3"
        "eac3", "e-ac-3" -> "audio/eac3"
        "dts", "dtsc", "dts " -> "audio/vnd.dts"
        "flac" -> "audio/flac"
        "opus" -> "audio/opus"
        "vorb" -> "audio/vorbis"
        "subt", "srt" -> "application/x-subrip"
        "ssa", "ass", "ssa " -> "text/x-ssa"
        "wvtt" -> "text/vtt"
        else -> if (type == C.TRACK_TYPE_AUDIO) "audio/x-vlc-unknown" else "text/x-vlc-subtitle"
    }
}
