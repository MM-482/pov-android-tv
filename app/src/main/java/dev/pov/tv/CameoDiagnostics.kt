package dev.pov.tv

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.media3.common.Player
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Local, allowlisted diagnostics. This provider never exports or uploads a report itself. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object CameoDiagnostics {
    private const val CHECK_DEADLINE_SECONDS = 6L
    private const val MAX_DOCUMENT_BYTES = 65_536
    private val allowedAbis = setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "mips", "mips64")
    private data class Endpoint(val label: String, val address: String, val json: Boolean)
    // Fixed public APIs, matching the desktop metadata/provider diagnostics. No saved configuration is used.
    private val endpoints = listOf(
        Endpoint("Movie/show metadata", "https://v3-cinemeta.strem.io/meta/movie/tt0816692.json", true),
        Endpoint("Real-Debrid public API", "https://api.real-debrid.com/rest/1.0/time", false),
    )
    private val checks by lazy {
        // DNS can outlive a network call timeout. A bounded pool and no queue prevent repeated
        // reports from accumulating stuck workers; each report also has its own total deadline.
        ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, SynchronousQueue(), { task ->
            Thread(task, "cameo-public-diagnostics").apply { isDaemon = true }
        }).apply { allowCoreThreadTimeOut(true) }
    }
    private val publicClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .build()
    }

    /**
     * Default: entirely offline. Run includeConnectivity=true on a worker after an explicit user action.
     * Public checks take at most six seconds; the main-thread playback snapshot has a 750 ms deadline.
     * Only fixed field names, enums, numbers and fixed status messages enter the returned text.
     */
    @JvmStatic
    fun report(context: Context, includeConnectivity: Boolean = false): String {
        require(!includeConnectivity || Looper.myLooper() != Looper.getMainLooper()) {
            "Run public connectivity diagnostics on a background worker."
        }
        val preferences = PlaybackPreferences(context)
        val config = context.resources.configuration
        return buildString {
            appendLine("CAMEO ANDROID TROUBLESHOOTING REPORT")
            appendLine("Generated UTC: ${Instant.now()}")
            appendLine("App: ${BuildConfig.VERSION_NAME}; build=${BuildConfig.VERSION_CODE}; debug=${BuildConfig.DEBUG}")
            appendLine("Android API: ${Build.VERSION.SDK_INT}; target=${context.applicationInfo.targetSdkVersion}")
            appendLine("64-bit process: ${Process.is64Bit()}")
            appendLine("Supported ABI: ${Build.SUPPORTED_ABIS.filter { it in allowedAbis }.joinToString().ifBlank { "not reported" }}")
            appendLine("UI: ${if (config.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION) "TV" else "mobile/tablet"}")
            appendLine("Current window: ${config.screenWidthDp} x ${config.screenHeightDp} dp; smallest=${config.smallestScreenWidthDp} dp")
            appendLine("Orientation: ${when (config.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                Configuration.ORIENTATION_PORTRAIT -> "portrait"
                else -> "undefined"
            }}")
            appendLine("Media3: ${androidx.media3.common.MediaLibraryInfo.VERSION}")
            appendLine("VLC: bundled; native availability is not tested by this report")
            appendLine("TLS: platform certificate validation; no certificate bypass")
            appendLine()
            appendLine("PLAYBACK PREFERENCES")
            appendLine("Preferred engine: ${preferences.selectedEngine.name}")
            val captions = preferences.captionAppearance
            appendLine("Captions: size=${captions.size.name}; color=${captions.color.name}; background=${captions.background.name}; position=${captions.position.name}")
            appendLine()
            appendLine("CURRENT PLAYBACK (media identity excluded)")
            appendLine(playbackSnapshot())
            appendLine()
            appendLine("PUBLIC CONNECTIVITY CHECKS (no account credentials sent)")
            if (includeConnectivity) appendLine(publicConnectivity())
            else appendLine("Not run. This is an offline report.")
            appendLine()
            appendLine("Account names, credentials, URLs, private paths, media titles and saved library contents are excluded.")
            appendLine("No request bodies, response bodies or exception messages are recorded. This report is not uploaded automatically.")
            appendLine("Public reachability does not test account access, playback permission or device decoding support.")
        }
    }

    private fun playbackSnapshot(): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return safePlaybackSnapshot()
        val handler = Handler(Looper.getMainLooper())
        val capture = FutureTask(::safePlaybackSnapshot)
        if (!handler.post(capture)) return "Playback snapshot unavailable."
        return try { capture.get(750, TimeUnit.MILLISECONDS) }
        catch (_: Exception) { "Playback snapshot unavailable." }
        finally { handler.removeCallbacks(capture); capture.cancel(false) }
    }

    private fun safePlaybackSnapshot(): String = try {
        val player = InAppPlaybackSession.currentPlayer
        if (player == null) "Active session: false"
        else buildString {
            appendLine("Active session: true; engine=${InAppPlaybackSession.engine.name}")
            appendLine("State: ${when (player.playbackState) {
                Player.STATE_IDLE -> "idle"
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> "ready"
                Player.STATE_ENDED -> "ended"
                else -> "unknown"
            }}; playing=${player.isPlaying}; play requested=${player.playWhenReady}")
            val size = player.videoSize
            appendLine("Picture: ${size.width} x ${size.height}; seekable=${player.isCurrentMediaItemSeekable}")
            appendLine("Speed: ${player.playbackParameters.speed}; subtitle timing supported=${InAppPlaybackSession.supportsSubtitleTiming}")
            appendLine("Track groups: ${player.currentTracks.groups.size}; sleep timer active=${InAppPlaybackSession.sleepRemainingMillis != null}")
            appendLine("PCM boost: ${InAppPlaybackSession.volumeBoost.state.name}; gain=${InAppPlaybackSession.volumeBoost.gainDb} dB")
            appendLine("Video target switches: ${InAppPlaybackSession.videoTargetSwitchCount}; surface losses=${InAppPlaybackSession.videoSurfaceLossCount}; rebinds=${InAppPlaybackSession.videoSurfaceRebindCount}")
            append("Playback error code: ${(InAppPlaybackSession.error ?: player.playerError)?.errorCode ?: "none"}")
        }
    } catch (_: Exception) { "Playback snapshot unavailable." }

    private fun publicConnectivity(): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHECK_DEADLINE_SECONDS)
        val calls = endpoints.map { endpoint ->
            publicClient.newCall(Request.Builder().url(endpoint.address)
                .header("Accept", "application/json").header("User-Agent", "Cameo-Diagnostics").get().build())
        }
        val pending = calls.mapIndexed { index, call ->
            runCatching { checks.submit(Callable { probe(call, endpoints[index].json) }) }.getOrNull()
        }
        return try {
            endpoints.mapIndexed { index, endpoint ->
                val future = pending[index]
                val result = if (future == null) "Check unavailable; an earlier check is still running." else {
                    try { future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
                    catch (_: Exception) { "Timed out." }
                }
                "${endpoint.label}: $result"
            }.joinToString("\n")
        } finally {
            calls.forEach(Call::cancel)
            pending.forEach { it?.cancel(true) }
        }
    }

    /** No exception text, Location header or remote response content may leave this function. */
    internal fun probe(call: Call, expectJson: Boolean): String = try {
        call.execute().use { response ->
            when {
                response.code in 300..399 -> "HTTP ${response.code}; redirect not followed."
                response.code !in 200..299 -> "HTTP ${response.code}; public endpoint reached."
                !expectJson -> "HTTP ${response.code}; public endpoint reached."
                else -> {
                    val body = response.body
                    if (body == null) "HTTP ${response.code}; empty public document."
                    else if (body.contentLength() > MAX_DOCUMENT_BYTES) "HTTP ${response.code}; public document too large to validate."
                    else {
                        val bytes = body.byteStream().use { it.readBytesLimited(MAX_DOCUMENT_BYTES + 1) }
                        if (bytes.size > MAX_DOCUMENT_BYTES) "HTTP ${response.code}; public document too large to validate."
                        else {
                            val parsed = runCatching { JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue() }.getOrNull()
                            if (parsed is JSONObject || parsed is JSONArray) "HTTP ${response.code}; valid public JSON received."
                            else "HTTP ${response.code}; unexpected public document."
                        }
                    }
                }
            }
        }
    } catch (_: UnknownHostException) { "DNS lookup failed." }
    catch (_: SSLException) { "TLS validation failed." }
    catch (_: InterruptedIOException) { "Timed out." }
    catch (_: Exception) { "Network request failed." }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (output.size() < limit) {
            val count = read(chunk, 0, minOf(chunk.size, limit - output.size()))
            if (count < 0) break
            if (count > 0) output.write(chunk, 0, count)
        }
        return output.toByteArray()
    }
}
