package dev.pov.tv

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.UUID

internal data class ProtectedPlaybackRequest(val url: String, val headers: Map<String, String>, val expiresAt: Long)

/** Credentials travel through process memory, never an Intent, state Bundle, or playback history. */
internal object ProtectedPlaybackRequests {
    private val pending = linkedMapOf<String, ProtectedPlaybackRequest>()
    @Synchronized fun add(url: String, headers: Map<String, String>): String {
        require(approved(url)) { "Protected playback requires an Easynews HTTPS media host." }
        require(headers.keys.all { it.equals("Authorization", true) } && headers.values.none { '\r' in it || '\n' in it }) {
            "Unsupported protected playback headers."
        }
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeAll { it.value.expiresAt < now }
        while (pending.size >= 8) pending.remove(pending.keys.first())
        val token = UUID.randomUUID().toString()
        pending[token] = ProtectedPlaybackRequest(url, headers.toMap(), now + 300_000)
        return token
    }
    @Synchronized fun take(token: String): ProtectedPlaybackRequest? = pending.remove(token)?.takeIf { it.expiresAt >= SystemClock.elapsedRealtime() }
    fun approved(address: String): Boolean = try {
        val uri = URI(address)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        uri.scheme.equals("https", true) && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            (host == "easynews.com" || host.endsWith(".easynews.com"))
    } catch (_: Exception) { false }
}

/** Manual redirects validate the destination before any Authorization header is sent. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class ProtectedHttpDataSource(
    private val headers: Map<String, String>,
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()
    private var address: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        require(dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET) { "Only media GET requests are supported." }
        var next = dataSpec.uri.toString()
        try {
            for (redirect in 0..5) {
                if (!ProtectedPlaybackRequests.approved(next)) throw IOException("Protected playback redirected outside the approved media service.")
                val current = connect(URI(next))
                connection = current
                current.instanceFollowRedirects = false
                current.connectTimeout = 15_000; current.readTimeout = 20_000
                current.requestMethod = "GET"
                current.setRequestProperty("Accept-Encoding", "identity")
                dataSpec.httpRequestHeaders.forEach { (key, value) -> current.setRequestProperty(key, value) }
                headers.forEach { (key, value) -> current.setRequestProperty(key, value) }
                if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) (dataSpec.position + dataSpec.length - 1).toString() else ""
                    current.setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                }
                val code = current.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = current.getHeaderField("Location") ?: throw IOException("The media service sent an invalid redirect.")
                    next = URI(next).resolve(location).toString()
                    current.disconnect(); connection = null
                    if (redirect == 5) throw IOException("The media service sent too many redirects.")
                    continue
                }
                if (code !in 200..299) throw IOException(if (code == 401 || code == 403)
                    "The media service rejected these credentials or the playback link expired." else "The media service returned HTTP $code.")
                val input = current.inputStream
                stream = input
                val skip = if (code == 200) dataSpec.position else 0L
                if (code == 206 && dataSpec.position > 0) {
                    val rangeStart = current.getHeaderField("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    if (rangeStart != dataSpec.position) throw IOException("The media service returned an unexpected byte range.")
                }
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                    else current.getHeaderFieldLong("Content-Length", -1).let { if (it >= 0) (it - skip).coerceAtLeast(0) else C.LENGTH_UNSET.toLong() }
                address = Uri.parse(next)
                opened = true; transferStarted(dataSpec)
                var toSkip = skip
                val scratch = ByteArray(8192)
                while (toSkip > 0) {
                    val read = input.read(scratch, 0, toSkip.coerceAtMost(scratch.size.toLong()).toInt())
                    if (read < 0) throw IOException("The media service did not provide the requested video position.")
                    toSkip -= read; bytesTransferred(read)
                }
                return remaining
            }
            throw IOException("The media service could not open this video.")
        } catch (failure: IOException) { close(); throw failure }
        catch (_: RuntimeException) { close(); throw IOException("The protected media connection could not be opened.") }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val request = if (remaining < 0) length else remaining.coerceAtMost(length.toLong()).toInt()
        val read = stream?.read(buffer, offset, request) ?: C.RESULT_END_OF_INPUT
        if (read > 0) { if (remaining >= 0) remaining -= read; bytesTransferred(read) }
        return read
    }
    override fun getUri(): Uri? = address
    override fun getResponseHeaders(): Map<String, List<String>> {
        val fields: Map<String?, List<String>> = connection?.headerFields ?: return emptyMap()
        return fields.mapNotNull { (key, values) -> key?.let { it to values } }.toMap()
    }
    override fun close() {
        try { stream?.close() } finally {
            stream = null; connection?.disconnect(); connection = null; address = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
