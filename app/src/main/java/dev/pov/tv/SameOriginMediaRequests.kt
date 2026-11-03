package dev.pov.tv

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale
import java.util.UUID

internal data class SameOriginMediaRequest(
    val url: String, val headers: Map<String, String>, val origin: String, val expiresAt: Long,
)

/** Explicit server/add-on handoff; neither credentials nor private URLs enter Intents or history. */
internal object SameOriginMediaRequests {
    private val pending = linkedMapOf<String, SameOriginMediaRequest>()
    private val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val transportHeaders = setOf("host", "connection", "content-length", "transfer-encoding",
        "trailer", "te", "upgrade", "proxy-connection", "proxy-authenticate", "proxy-authorization",
        "expect", "range", "accept-encoding")

    @Synchronized fun add(url: String, headers: Map<String, String>, origin: String): String {
        val target = address(url)
        val scope = address(origin)
        require(target.scheme.equals("https", true) || sameOrigin(target, scope)) {
            "Local HTTP playback must use the configured server origin."
        }
        require(headers.size <= 64 && headers.keys.all { it.length <= 128 && headerName.matches(it) &&
            it.lowercase(Locale.ROOT) !in transportHeaders } &&
            headers.keys.map { it.lowercase(Locale.ROOT) }.distinct().size == headers.size &&
            headers.values.all(::validHeaderValue)) {
            "Unsupported media request headers."
        }
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeAll { it.value.expiresAt <= now }
        while (pending.size >= 8) pending.remove(pending.keys.first())
        val token = UUID.randomUUID().toString()
        pending[token] = SameOriginMediaRequest(url, headers.toMap(), scope.toString(), now + 300_000)
        return token
    }

    @Synchronized fun take(token: String): SameOriginMediaRequest? = pending.remove(token)
        ?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }

    internal fun validHeaderValue(value: String): Boolean = value.length <= 16_384 && value.none(Char::isISOControl)

    internal fun address(value: String): URI {
        val uri = URI(value)
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0 &&
            value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Use a valid media server address without embedded credentials." }
        return uri
    }

    internal fun sameOrigin(first: URI, second: URI): Boolean = first.scheme.equals(second.scheme, true) &&
        first.host.equals(second.host, true) && port(first) == port(second)
    private fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    internal fun privateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC)
    }
}

/** Loader-thread transport. Private headers follow only the exact configured origin. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SameOriginMediaDataSource(
    private val request: SameOriginMediaRequest,
    private val connect: (URI) -> HttpURLConnection = {
        (if (it.scheme.equals("http", true)) it.toURL().openConnection(Proxy.NO_PROXY) else it.toURL().openConnection()) as HttpURLConnection
    },
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()
    private var address: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) throw IOException("Only media GET requests are supported.")
        val origin = SameOriginMediaRequests.address(request.origin)
        try {
            var next = SameOriginMediaRequests.address(dataSpec.uri.toString())
            for (redirect in 0..5) {
                val sameOrigin = SameOriginMediaRequests.sameOrigin(origin, next)
                var connectionAddress = next
                var configuredHost: String? = null
                if (next.scheme.equals("http", true)) {
                    if (!sameOrigin) throw IOException("An HTTP media link left the configured local server.")
                    val addresses = resolve(next.host)
                    if (addresses.isEmpty() || addresses.any { !SameOriginMediaRequests.privateAddress(it) })
                        throw IOException("Unencrypted media connections are limited to your configured local network server.")
                    // Connect to the exact checked IP. Resolving the hostname again inside
                    // HttpURLConnection would otherwise reopen a DNS-rebinding window.
                    val numeric = addresses.first().hostAddress ?: throw IOException("The local server address is unavailable.")
                    val host = if (':' in numeric) "[$numeric]" else numeric
                    val port = if (next.port >= 0) ":${next.port}" else ""
                    connectionAddress = URI("http://$host$port${next.rawPath.orEmpty().ifEmpty { "/" }}" +
                        (next.rawQuery?.let { "?$it" } ?: ""))
                    configuredHost = next.rawAuthority
                }
                val current = connect(connectionAddress)
                connection = current
                current.instanceFollowRedirects = false
                current.connectTimeout = 15_000; current.readTimeout = 20_000
                current.requestMethod = "GET"
                configuredHost?.let { current.setRequestProperty("Host", it) }
                current.setRequestProperty("Accept-Encoding", "identity")
                // DataSpec headers may have originated in a manifest. They cannot widen credential scope.
                dataSpec.httpRequestHeaders.filterKeys { it.equals("Accept", true) || it.equals("User-Agent", true) }
                    .forEach { (key, value) -> if (SameOriginMediaRequests.validHeaderValue(value)) current.setRequestProperty(key, value) }
                if (sameOrigin) request.headers.forEach { (key, value) -> current.setRequestProperty(key, value) }
                if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) (dataSpec.position + dataSpec.length - 1).toString() else ""
                    current.setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                }
                val code = current.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = current.getHeaderField("Location") ?: throw IOException("The media server sent an invalid redirect.")
                    next = SameOriginMediaRequests.address(next.resolve(location).toString())
                    current.disconnect(); connection = null
                    if (redirect == 5) throw IOException("The media server sent too many redirects.")
                    continue
                }
                if (code == 416 && current.getHeaderField("Content-Range")?.substringAfter("bytes */", "")?.toLongOrNull() == dataSpec.position) {
                    remaining = 0; address = Uri.parse(next.toString()); opened = true; transferStarted(dataSpec); return 0
                }
                if (code !in 200..299) throw IOException(when (code) {
                    401, 403 -> "The server rejected the account or this playback link expired."
                    451 -> "This media source returned HTTP 451 and cannot be accessed."
                    else -> "The media server returned HTTP $code."
                })
                if (code == 206) {
                    val start = current.getHeaderField("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    if (start != dataSpec.position) throw IOException("The media server returned an unexpected byte range.")
                }
                val input = current.inputStream
                stream = input
                val skip = if (code == 200) dataSpec.position else 0L
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else
                    current.getHeaderFieldLong("Content-Length", -1).let { if (it >= 0) (it - skip).coerceAtLeast(0) else C.LENGTH_UNSET.toLong() }
                address = Uri.parse(next.toString()); opened = true; transferStarted(dataSpec)
                var toSkip = skip
                val scratch = ByteArray(8192)
                while (toSkip > 0) {
                    val count = input.read(scratch, 0, toSkip.coerceAtMost(scratch.size.toLong()).toInt())
                    if (count < 0) throw EOFException("The requested media position is unavailable.")
                    if (count == 0) continue
                    toSkip -= count; bytesTransferred(count)
                }
                return remaining
            }
            throw IOException("The media server could not open this video.")
        } catch (failure: IOException) { close(); throw failure }
        catch (_: Exception) { close(); throw IOException("The media connection could not be opened.") }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = if (remaining < 0) length else remaining.coerceAtMost(length.toLong()).toInt()
        val count = stream?.read(buffer, offset, requested) ?: C.RESULT_END_OF_INPUT
        if (count < 0 && remaining > 0) throw EOFException("The media stream ended before the requested bytes arrived.")
        if (count > 0) { if (remaining >= 0) remaining -= count; bytesTransferred(count) }
        return count
    }
    override fun getUri(): Uri? = address
    override fun getResponseHeaders(): Map<String, List<String>> = connection?.headerFields
        ?.mapNotNull { (key, value) -> key?.let { it to value } }?.toMap() ?: emptyMap()
    override fun close() {
        try { stream?.close() } finally {
            stream = null; connection?.disconnect(); connection = null; address = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
