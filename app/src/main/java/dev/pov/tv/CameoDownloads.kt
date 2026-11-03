package dev.pov.tv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.pov.tv.data.ProviderPlayback
import dev.pov.tv.data.EasynewsNzb
import dev.pov.tv.data.NzbFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class CameoDownload(val id: String, val title: String, val destination: String, val status: String,
    val bytes: Long = 0, val total: Long = 0, val error: String = "", val video: Boolean = true) {
    val active: Boolean get() = status in setOf("queued", "downloading", "saving")
}

/** Transfers run while the app process is alive. Authentication stays in memory. */
object CameoDownloads {
    private val workers = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val jobs = linkedMapOf<String, CameoDownload>()
    private val canceled = mutableMapOf<String, AtomicBoolean>()
    private val listeners = mutableSetOf<() -> Unit>()
    private var application: Context? = null

    fun initialize(context: Context) = synchronized(lock) {
        if (application != null) return@synchronized
        application = context.applicationContext
        context.cacheDir.listFiles { file -> file.name.startsWith("cameo-download-") && file.name.endsWith(".part") }
            ?.forEach { it.delete() }
        val values = try { JSONArray(context.getSharedPreferences("cameo_downloads", Context.MODE_PRIVATE).getString("jobs", "[]")) }
            catch (_: Exception) { JSONArray() }
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val recovered = CameoDownload(item.optString("id"), item.optString("title"), item.optString("destination"),
                item.optString("status"), item.optLong("bytes"), item.optLong("total"), item.optString("error"), item.optBoolean("video", true))
            if (recovered.id.isNotBlank()) jobs[recovered.id] = if (recovered.active) recovered.copy(status = "interrupted", error = "Start this download again from its title or file.") else recovered
        }
        persist()
    }
    fun list(): List<CameoDownload> = synchronized(lock) { jobs.values.toList().asReversed() }
    fun addListener(listener: () -> Unit) = synchronized(lock) { listeners.add(listener); Unit }
    fun removeListener(listener: () -> Unit) = synchronized(lock) { listeners.remove(listener); Unit }
    fun cancel(id: String) = synchronized(lock) { canceled[id]?.set(true); Unit }
    fun clearFinished() = synchronized(lock) { jobs.entries.removeAll { !it.value.active }; persist(); notifyChanged() }

    fun start(context: Context, title: String, source: ProviderPlayback, destination: Uri): String {
        require(source.url.isNotBlank()) { "Choose a playable file first." }
        DownloadTransfer.requireSingleFile(source)
        require(source.headers.entries.none { (key, value) -> '\r' in key || '\n' in key || '\r' in value || '\n' in value }) { "Invalid download request." }
        return startTransfer(context, title, destination) { file, cancellation, progress ->
            DownloadTransfer.copy(source, file, cancellation, progress)
        }
    }

    fun startNzb(context: Context, file: NzbFile, username: String, password: String, destination: Uri): String =
        startTransfer(context, file.name, destination, video = LocalLibraryImporter.isVideo(file.name, "")) { temporary, cancellation, progress ->
            check(temporary.length() == 0L && temporary.delete()) { "The temporary download could not be prepared." }
            EasynewsNzb.download(file, username, password, temporary, cancellation, progress)
        }

    private fun startTransfer(context: Context, title: String, destination: Uri, video: Boolean = true,
        transfer: (File, () -> Boolean, (Long, Long) -> Unit) -> Unit): String {
        initialize(context)
        require(destination.scheme == "content") { "Choose a new destination with the device's save dialog." }
        val id = UUID.randomUUID().toString()
        val cancellation = AtomicBoolean(false)
        synchronized(lock) {
            require(jobs.values.count { it.active } < 8) { "Wait for a download to finish before adding another." }
            jobs[id] = CameoDownload(id, title, destination.toString(), "queued", video = video)
            canceled[id] = cancellation
            while (jobs.size > 100) { val oldest = jobs.entries.firstOrNull { !it.value.active } ?: break; jobs.remove(oldest.key) }
            persist(); notifyChanged()
        }
        workers.execute {
            val app = context.applicationContext
            var temporary: File? = null
            var exportStarted = false
            try {
                temporary = File.createTempFile("cameo-download-", ".part", app.cacheDir)
                update(id, "downloading")
                transfer(temporary, { cancellation.get() }) { bytes, total -> update(id, "downloading", bytes, total, persist = false) }
                checkCanceled(cancellation)
                update(id, "saving", temporary.length(), temporary.length())
                // Opening without truncate and checking the descriptor prevents silently replacing a populated file.
                val descriptor = app.contentResolver.openFileDescriptor(destination, "rw") ?: throw IOException("The selected destination could not be opened.")
                if (descriptor.statSize != 0L) { descriptor.close(); throw IOException("Choose a new empty destination. An existing file will not be overwritten.") }
                exportStarted = true
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                        temporary.inputStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                checkCanceled(cancellation)
                                val count = input.read(buffer); if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        output.flush()
                }
                update(id, "complete", temporary.length(), temporary.length())
            } catch (error: Exception) {
                if (exportStarted) runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, destination) }
                update(id, if (cancellation.get()) "cancelled" else "failed", error = if (cancellation.get()) "" else when {
                    error.message?.startsWith("Choose a new empty") == true -> error.message.orEmpty()
                    error.message?.startsWith("HTTP ") == true -> error.message.orEmpty()
                    error.message?.startsWith("Download ") == true -> error.message.orEmpty()
                    else -> "The download could not finish. Check available space, access, and the source, then start it again."
                })
            } finally {
                temporary?.delete()
                synchronized(lock) { canceled.remove(id) }
            }
        }
        return id
    }
    private fun checkCanceled(value: AtomicBoolean) { if (value.get()) throw IOException("Download canceled.") }
    private fun update(id: String, status: String, bytes: Long? = null, total: Long? = null, error: String = "", persist: Boolean = true) = synchronized(lock) {
        val old = jobs[id] ?: return@synchronized
        jobs[id] = old.copy(status = status, bytes = bytes ?: old.bytes, total = total ?: old.total, error = error)
        if (persist) persist()
        notifyChanged()
    }
    private fun notifyChanged() {
        val callbacks = listeners.toList()
        handler.post { callbacks.forEach { it() } }
    }
    private fun persist() {
        val app = application ?: return
        val values = JSONArray(jobs.values.map { JSONObject().put("id", it.id).put("title", it.title)
            .put("destination", it.destination).put("status", it.status).put("bytes", it.bytes).put("total", it.total).put("error", it.error).put("video", it.video) })
        app.getSharedPreferences("cameo_downloads", Context.MODE_PRIVATE).edit().putString("jobs", values.toString()).apply()
    }
}

/** Manual redirect handling never forwards account headers to another origin. */
internal object DownloadTransfer {
    private val adaptiveTypes = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl", "application/dash+xml")
    fun requireSingleFile(source: ProviderPlayback) {
        val path = URI(source.url).path.orEmpty().lowercase(java.util.Locale.ROOT)
        require(!path.endsWith(".m3u8") && !path.endsWith(".mpd")) { "Download supports complete video files. HLS and DASH streams can be played but cannot be saved as one video." }
    }
    fun copy(source: ProviderPlayback, destination: File, canceled: () -> Boolean, progress: (Long, Long) -> Unit,
        connect: (URI) -> HttpURLConnection = {
            (if (it.scheme.equals("http", true)) it.toURL().openConnection(java.net.Proxy.NO_PROXY) else it.toURL().openConnection()) as HttpURLConnection
        }) {
        requireSingleFile(source)
        val initial = URI(source.url)
        val trusted = source.origin.takeIf(String::isNotBlank)?.let(::URI) ?: initial
        require(approved(initial, trusted)) { "Download requires HTTPS or your configured local media server." }
        var address = initial
        for (hop in 0..5) {
            if (canceled()) throw IOException("Download canceled.")
            require(approved(address, trusted)) { "Download redirected to an unsupported address." }
            val networkAddress = if (address.scheme.equals("http", true)) pinnedPrivateAddress(address) else address
            val request = connect(networkAddress)
            try {
                request.instanceFollowRedirects = false; request.connectTimeout = 15_000; request.readTimeout = 30_000
                request.requestMethod = "GET"; request.setRequestProperty("Accept-Encoding", "identity")
                if (networkAddress != address) request.setRequestProperty("Host", address.rawAuthority)
                if (sameOrigin(address, trusted)) source.headers.forEach { (key, value) ->
                    if (key.lowercase() !in setOf("host", "connection", "content-length", "range")) request.setRequestProperty(key, value)
                }
                val code = request.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = request.getHeaderField("Location") ?: throw IOException("Download redirect is incomplete.")
                    address = address.resolve(location)
                    if (hop == 5) throw IOException("Download redirected too many times.")
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code: the source did not provide this download.")
                val contentType = request.getHeaderField("Content-Type").orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
                if (contentType in adaptiveTypes) throw IOException("Download supports complete video files. This address returned an HLS or DASH stream.")
                if (contentType == "text/html") throw IOException("Download source returned a web page instead of a video file.")
                val total = request.getHeaderFieldLong("Content-Length", -1).coerceAtLeast(0)
                var bytes = 0L; var lastReport = 0L
                request.inputStream.use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (canceled()) throw IOException("Download canceled.")
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); bytes += count
                        val now = System.nanoTime()
                        if (now - lastReport > 500_000_000) { progress(bytes, total); lastReport = now }
                    }
                } }
                if (total > 0 && bytes != total) throw IOException("Download ended before the complete file arrived.")
                progress(bytes, total); return
            } finally { request.disconnect() }
        }
        throw IOException("Download could not be opened.")
    }
    internal fun sameOrigin(a: URI, b: URI): Boolean = a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) &&
        effectivePort(a) == effectivePort(b)
    private fun effectivePort(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    private fun approved(uri: URI, trusted: URI): Boolean {
        if (uri.userInfo != null || uri.host.isNullOrBlank()) return false
        if (uri.scheme.equals("https", true)) return true
        return uri.scheme.equals("http", true) && sameOrigin(uri, trusted) && privateHost(uri.host)
    }
    private fun privateHost(host: String): Boolean = try {
        InetAddress.getAllByName(host).let { addresses -> addresses.isNotEmpty() && addresses.all { address ->
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
        } }
    } catch (_: Exception) { false }
    private fun pinnedPrivateAddress(address: URI): URI {
        val addresses = InetAddress.getAllByName(address.host)
        require(addresses.isNotEmpty() && addresses.all { value -> value.isLoopbackAddress || value.isSiteLocalAddress || value.isLinkLocalAddress ||
            (value.address.size == 16 && (value.address[0].toInt() and 0xfe) == 0xfc) }) { "Download local-server address changed." }
        val host = addresses.first().hostAddress.orEmpty().replace("%", "%25")
        val numeric = if (':' in host) "[$host]" else host
        return URI("http://$numeric${if (address.port >= 0) ":${address.port}" else ""}${address.rawPath.orEmpty()}${address.rawQuery?.let { "?$it" }.orEmpty()}")
    }
}
