package dev.pov.tv

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.pov.tv.data.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class CameoDownload(val id: String, val name: String, val destination: String, val status: String,
    val bytes: Long = 0, val total: Long = -1, val message: String = "",val kind:String="video")

/** Explicit save-to-document transfers. Resolved URLs/headers exist in memory only. */
object CameoDownloads {
    private val executor = Executors.newFixedThreadPool(2)
    private val jobs = linkedMapOf<String, CameoDownload>()
    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private var store: SecureStore? = null
    private var app: Context? = null
    var loadError:String?=null
        private set

    @Synchronized fun initialize(context: Context) {
        if (app != null) return
        app = context.applicationContext; store = SecureStore(context)
        val saved = runCatching { restoreSaved(store!!.get("cameo.downloads.v1", "[]")) }.getOrElse {
            loadError="The saved download list could not be read. It has not been replaced. Check this device's saved app data before creating another download."
            return
        }
        saved.forEach { job -> jobs[job.id] = job }
        if (saved.isNotEmpty()) persist()
    }

    internal fun restoreSaved(value:String):List<CameoDownload> {
        require(value.length<=1024*1024){"The saved download list is too large."}
        val saved=JSONArray(value);require(saved.length()<=200)
        val ids=mutableSetOf<String>()
        return (0 until saved.length()).map { index ->
            val item = saved.getJSONObject(index)
            val destination = item.optString("destination")
            val uri=URI(destination);require(uri.scheme=="content"&&!uri.authority.isNullOrBlank())
            val id=item.getString("id");require(id.isNotBlank()&&ids.add(id))
            val kind=item.optString("kind","video");require(kind in setOf("video","document"))
            val previous = item.getString("status")
            require(previous in setOf("queued", "downloading", "completed", "failed", "cancelled", "interrupted"))
            val bytes=item.getLong("bytes");val total=item.optLong("total",-1);require(bytes>=0&&total>=-1)
            val status = if (previous in setOf("queued", "downloading")) "interrupted" else previous
            CameoDownload(id, safeName(item.getString("name")), destination,
                status, bytes, total, if (status == "interrupted")
                    "The app closed before this file finished. Choose Download again to restart." else when (status) {
                        "cancelled" -> "Download cancelled. A partial file may remain at the selected location."
                        "failed" -> "The download did not finish. Choose a new empty file and try again."
                        else -> ""
                    },kind)
        }
    }

    @Synchronized fun list(): List<CameoDownload> = jobs.values.toList().asReversed()

    fun start(context: Context, address: String, headers: Map<String, String>, destination: Uri, name: String): String {
        initialize(context)
        check(loadError==null){loadError.orEmpty()}
        require(MediaAddressPolicy.accepts(address)) { "Choose a secure stream or a local video." }
        require(destination.scheme == "content") { "Choose a download location using Android's save dialog." }
        require(!URI(address).path.orEmpty().lowercase().let { it.endsWith(".m3u8") || it.endsWith(".mpd") }) {
            "Adaptive HLS/DASH streams cannot be saved as a single offline video. Choose a direct file."
        }
        val safeHeaders = ScopedPlaybackRequests.normalizedHeaders(headers)
        val safeName = safeName(name)
        val id = UUID.randomUUID().toString()
        val cancel = AtomicBoolean(false); cancelled[id] = cancel
        update(CameoDownload(id, safeName, destination.toString(), "queued"))
        executor.execute {
            try {
                if (cancel.get()) throw InterruptedException()
                val input = if (Uri.parse(address).scheme == "content") {
                    app!!.contentResolver.openInputStream(Uri.parse(address)) ?: throw IOException("The local video is unavailable.")
                } else open(id, URI(address), safeHeaders)
                input.use { source ->
                    val expected = connections[id]?.getHeaderFieldLong("Content-Length", -1) ?: documentSize(Uri.parse(address)) ?: -1L
                    val mime = connections[id]?.contentType ?: if (Uri.parse(address).scheme == "content")
                        app!!.contentResolver.getType(Uri.parse(address)).orEmpty() else ""
                    var savedAt = 0L
                    val written = DownloadValidation.copyValidated(source, mime, expected, cancel::get,
                        confirmDestination = { DownloadValidation.requireEmptyDestination(documentSize(destination)) {
                            app!!.contentResolver.openInputStream(destination)
                        } },
                        openDestination = { app!!.contentResolver.openOutputStream(destination, "w")
                            ?: throw DownloadValidationException("The selected save location is unavailable.") },
                        progress = { bytes ->
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - savedAt > 750 || savedAt == 0L) {
                                update(CameoDownload(id, safeName, destination.toString(), "downloading", bytes, expected))
                                savedAt = now
                            }
                        })
                    update(CameoDownload(id, safeName, destination.toString(), "completed", written, expected))
                }
            } catch (_: InterruptedException) {
                status(id, "cancelled", "Download cancelled. A partial file may remain at the selected location.")
            } catch (error: Exception) {
                status(id, if (cancel.get()) "cancelled" else "failed", if (cancel.get()) "Download cancelled." else safeError(error))
            } finally { connections.remove(id)?.disconnect(); cancelled.remove(id) }
        }
        return id
    }

    /** Shares queue/cancellation with native NZB preparation without retaining its private request. */
    fun startCustom(context: Context, name: String, destination: Uri, expectedBytes: Long,
        transfer: (cancel: () -> Boolean, progress: (Long, Long) -> Unit) -> Unit): String {
        initialize(context)
        check(loadError==null){loadError.orEmpty()}
        require(destination.scheme == "content") { "Choose a download location using Android's save dialog." }
        val label = safeName(name)
        val id = UUID.randomUUID().toString()
        val cancel = AtomicBoolean(false)
        cancelled[id] = cancel
        update(CameoDownload(id, label, destination.toString(), "queued", total = expectedBytes.coerceAtLeast(-1),kind="document"))
        executor.execute {
            var lastBytes = 0L
            var lastTotal = expectedBytes.coerceAtLeast(-1)
            var savedAt = 0L
            try {
                if (cancel.get()) throw InterruptedException()
                DownloadValidation.requireEmptyDestination(documentSize(destination)) { app!!.contentResolver.openInputStream(destination) }
                if (cancel.get()) throw InterruptedException()
                update(CameoDownload(id, label, destination.toString(), "downloading", total = lastTotal,kind="document"))
                transfer(cancel::get) { bytes, total ->
                    if (cancel.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                    lastBytes = bytes.coerceAtLeast(0)
                    lastTotal = total.coerceAtLeast(-1)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - savedAt > 750 || savedAt == 0L) {
                        update(CameoDownload(id, label, destination.toString(), "downloading", lastBytes, lastTotal,kind="document"))
                        savedAt = now
                    }
                }
                if (cancel.get()) throw InterruptedException()
                update(CameoDownload(id, label, destination.toString(), "completed", lastBytes, lastTotal,kind="document"))
            } catch (_: InterruptedException) {
                status(id, "cancelled", "Download cancelled. A partial file may remain at the selected location.")
            } catch (_: java.util.concurrent.CancellationException) {
                status(id, "cancelled", "Download cancelled. A partial file may remain at the selected location.")
            } catch (error: Exception) {
                status(id, if (cancel.get()) "cancelled" else "failed", if (cancel.get()) "Download cancelled." else safeError(error))
            } finally { cancelled.remove(id) }
        }
        return id
    }

    private fun open(id: String, initial: URI, headers: Map<String, String>): java.io.InputStream {
        var target = initial
        for (redirect in 0..5) {
            require(ScopedPlaybackRequests.approved(target.toString())) { "The source redirected to an unsupported address." }
            val connection = target.toURL().openConnection() as HttpURLConnection
            connections[id] = connection
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            DownloadValidation.headersFor(initial, target, headers).forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (cancelled[id]?.get() == true) { connection.disconnect(); throw InterruptedException() }
            val code = connection.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: throw IOException("The source sent an invalid redirect.")
                val next = DownloadValidation.redirectDestination(target, location)
                target = next; connection.disconnect(); continue
            }
            if (code !in 200..299) throw IOException("The media server returned HTTP $code. Check the source or your connection.")
            if (code == 206) {
                val range = Regex("(?i)^bytes 0-(\\d+)/(\\d+)$").matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                    ?: throw DownloadValidationException("The source returned an incomplete range instead of a complete video file.")
                val end = range.groupValues[1].toLongOrNull()
                val total = range.groupValues[2].toLongOrNull()
                val length = connection.getHeaderFieldLong("Content-Length", -1)
                if (end == null || total == null || total <= 0 || end == Long.MAX_VALUE || end + 1 != total || length != total)
                    throw DownloadValidationException("The source returned an incomplete range instead of a complete video file.")
            }
            val type = connection.contentType.orEmpty().lowercase()
            if (type.startsWith("text/") || listOf("html", "xml", "json", "mpegurl", "dash").any(type::contains))
                throw DownloadValidationException("The source returned a page, document, or adaptive manifest instead of a video file.")
            return connection.inputStream
        }
        throw IOException("The source sent too many redirects.")
    }

    fun cancel(id: String) { cancelled[id]?.set(true); connections[id]?.disconnect() }
    @Synchronized fun forget(id: String) {
        check(loadError==null){loadError.orEmpty()}
        require(jobs[id]?.status !in setOf("queued", "downloading")) { "Cancel this download before removing it." }
        jobs.remove(id); persist()
    }
    @Synchronized private fun update(job: CameoDownload) { jobs[job.id] = job; persist() }
    @Synchronized private fun status(id: String, status: String, message: String) {
        jobs[id]?.let { update(it.copy(status = status, message = message)) }
    }
    private fun safeError(error: Exception): String {
        // Never copy an exception containing a signed URL, local path, or account header into the queue.
        val message = error.message.orEmpty()
        return if (error is DownloadValidationException) message else if (message.startsWith("The media server returned HTTP ")) message else when {
            error is java.net.SocketTimeoutException -> "The download timed out. Check the connection and try again."
            message.contains("adaptive", true) -> "This adaptive stream cannot be saved as one offline file."
            else -> "The download could not finish. Check the source, network and available storage, then choose Download again."
        }
    }
    private fun documentSize(uri: Uri): Long? {
        if (uri.scheme != "content") return null
        return runCatching { app!!.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { value -> value >= 0 } else null
        } }.getOrNull()
    }
    private fun safeName(name: String): String = name.replace(Regex("[\\p{Cntrl}]"), "").take(180)
        .takeUnless { it.contains("://") }.orEmpty().ifBlank { "Video download" }
    @Synchronized private fun persist() {
        check(loadError==null){loadError.orEmpty()}
        store?.put("cameo.downloads.v1", JSONArray().apply { jobs.values.toList().takeLast(200).forEach { job ->
            put(JSONObject().put("id", job.id).put("name", job.name).put("destination", job.destination)
                .put("status", job.status).put("bytes", job.bytes).put("total", job.total).put("message", job.message).put("kind",job.kind))
        } }.toString())
    }
}
