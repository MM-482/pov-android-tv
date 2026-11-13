package dev.pov.tv

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.UUID

/** Server/add-on headers are ephemeral. Intent extras contain only a single-use opaque handoff. */
internal object ScopedPlaybackRequests {
    private val pending = linkedMapOf<String, ProtectedPlaybackRequest>()
    private val headerNames = listOf("Authorization", "X-Emby-Token", "X-Plex-Token", "User-Agent", "Referer", "Origin",
        "Accept", "Accept-Language", "Cookie").associateBy { it.lowercase(Locale.ROOT) }

    internal fun normalizedHeaders(headers: Map<String, String>): Map<String, String> {
        require(headers.size <= headerNames.size) { "Too many playback headers." }
        val normalized = linkedMapOf<String, String>()
        headers.forEach { (name, value) ->
            val key = headerNames[name.lowercase(Locale.ROOT)] ?: throw IllegalArgumentException("Unsupported playback header.")
            require(key !in normalized && value.length <= 16_384 && value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "Invalid playback header."
            }
            normalized[key] = value
        }
        return normalized.toMap()
    }

    internal fun approved(address: String): Boolean = try {
        val uri = URI(address)
        MediaAddressPolicy.accepts(address) && (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            (uri.port == -1 || uri.port in 1..65535) && uri.rawFragment == null
    } catch (_: Exception) { false }

    @Synchronized fun add(url: String, headers: Map<String, String>): String {
        require(approved(url)) { "Choose an HTTPS media address or an explicit local-network HTTP address." }
        val safeHeaders = normalizedHeaders(headers)
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeAll { it.value.expiresAt < now }
        while (pending.size >= 8) pending.remove(pending.keys.first())
        val token = UUID.randomUUID().toString()
        pending[token] = ProtectedPlaybackRequest(url, safeHeaders, now + 300_000L)
        return token
    }

    @Synchronized fun take(token: String): ProtectedPlaybackRequest? =
        pending.remove(token)?.takeIf { it.expiresAt >= SystemClock.elapsedRealtime() }
}

/** Messages deliberately omit URLs, header contents and platform exception text. */
internal class OriginBoundMediaException(message: String) : IOException(message)

/**
 * Credentials belong to the initial origin, including scheme and effective port. Public CDN
 * redirects and manifest subresources can open elsewhere, but receive none of those headers.
 * A URL is revalidated before every connection, and HTTPS is never downgraded on a redirect.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class OriginBoundHttpDataSource(
    initialUrl: String,
    headers: Map<String, String>,
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : BaseDataSource(true) {
    private val origin = URI(initialUrl.also { require(ScopedPlaybackRequests.approved(it)) { "Invalid initial media address." } })
    private val headers = ScopedPlaybackRequests.normalizedHeaders(headers)
    private var connection: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()
    private var address: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        close()
        transferInitializing(dataSpec)
        try {
            if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) fail("Only video GET requests are supported.")
            val perRequest = ScopedPlaybackRequests.normalizedHeaders(dataSpec.httpRequestHeaders)
            var next = URI(dataSpec.uri.toString())
            val requestedEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) null else
                try { Math.addExact(dataSpec.position, dataSpec.length - 1) }
                catch (_: ArithmeticException) { fail("The requested video range is invalid.") }
            for (redirect in 0..5) {
                if (!ScopedPlaybackRequests.approved(next.toString())) fail("The video redirected to an unsupported address.")
                // Initial HTTPS credentials must not be eligible on a plaintext manifest resource either.
                if (origin.scheme.equals("https", true) && !next.scheme.equals("https", true))
                    fail("The video connection cannot downgrade HTTPS to HTTP.")
                val current = connect(next)
                connection = current
                current.instanceFollowRedirects = false
                current.connectTimeout = 15_000
                current.readTimeout = 20_000
                current.requestMethod = "GET"
                current.setRequestProperty("Accept-Encoding", "identity")
                if (MediaAddressPolicy.sameOrigin(origin, next)) {
                    (perRequest + headers).forEach { (key, value) -> current.setRequestProperty(key, value) }
                }
                if (dataSpec.position > 0 || requestedEnd != null)
                    current.setRequestProperty("Range", "bytes=${dataSpec.position}-${requestedEnd ?: ""}")
                val code = current.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    if (redirect == 5) fail("The video source sent too many redirects.")
                    val location = current.getHeaderField("Location") ?: fail("The video source sent an invalid redirect.")
                    val redirected = next.resolve(location)
                    if (next.scheme.equals("https", true) && !redirected.scheme.equals("https", true))
                        fail("The video connection cannot downgrade HTTPS to HTTP.")
                    next = redirected
                    current.disconnect()
                    connection = null
                    continue
                }
                if (code == 416) {
                    val total = Regex("(?i)^bytes \\*/(\\d+)$").matchEntire(current.getHeaderField("Content-Range").orEmpty())
                        ?.groupValues?.get(1)?.toLongOrNull()
                    if (total != null && dataSpec.position == total) {
                        // Decoders legitimately probe exactly one byte beyond the final valid byte.
                        remaining = 0L
                        stream = ByteArrayInputStream(ByteArray(0))
                        address = Uri.parse(next.toString())
                        opened = true
                        transferStarted(dataSpec)
                        return 0L
                    }
                    fail("The video source could not provide the requested byte range (HTTP 416).")
                }
                if (code !in 200..299) fail(when (code) {
                    401, 403 -> "The video source refused access (HTTP $code). Check the account or choose a fresh link."
                    451 -> "The video source returned HTTP 451: access is unavailable for legal reasons. Changing quality on that endpoint does not remove the restriction."
                    else -> "The video source returned HTTP $code. Try another available source."
                })
                val encoding = current.getHeaderField("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) fail("The video source returned unsupported compressed byte ranges.")
                val rawLength = current.getHeaderField("Content-Length")
                val contentLength = rawLength?.toLongOrNull()?.takeIf { it >= 0 }
                if (rawLength != null && contentLength == null) fail("The video source returned an invalid content length.")
                val skipped = if (code == 200) dataSpec.position else 0L
                val available = if (code == 206) {
                    val range = Regex("(?i)^bytes (\\d+)-(\\d+)/(\\d+|\\*)$")
                        .matchEntire(current.getHeaderField("Content-Range").orEmpty())
                        ?: fail("The video source returned an invalid partial range.")
                    val start = range.groupValues[1].toLongOrNull() ?: fail("Invalid video range start.")
                    val end = range.groupValues[2].toLongOrNull() ?: fail("Invalid video range end.")
                    val total = range.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
                    if (start != dataSpec.position || end < start || end == Long.MAX_VALUE ||
                        (range.groupValues[3] != "*" && (total == null || total <= end)) ||
                        (requestedEnd != null && end > requestedEnd)) fail("The video source returned an unexpected byte range.")
                    val count = end - start + 1
                    if (contentLength != null && contentLength != count) fail("The video source returned an inconsistent partial range length.")
                    count
                } else contentLength?.let {
                    if (dataSpec.position > it) fail("The video source cannot seek beyond this file.")
                    it - skipped
                }
                remaining = when {
                    dataSpec.length != C.LENGTH_UNSET.toLong() && available != null -> minOf(dataSpec.length, available)
                    dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                    available != null -> available
                    else -> C.LENGTH_UNSET.toLong()
                }
                stream = current.inputStream
                address = Uri.parse(next.toString())
                opened = true
                transferStarted(dataSpec)
                var toSkip = skipped
                val scratch = ByteArray(8192)
                while (toSkip > 0) {
                    val read = requireNotNull(stream).read(scratch, 0, minOf(toSkip, scratch.size.toLong()).toInt())
                    if (read < 0) fail("The video source ended before the requested position.")
                    if (read == 0) continue
                    toSkip -= read
                    bytesTransferred(read)
                }
                return remaining
            }
            fail("The video source could not be opened.")
        } catch (failure: OriginBoundMediaException) { close(); throw failure }
        catch (_: IOException) { close(); fail("The video connection could not be opened. Check the network or choose a fresh source.") }
        catch (_: RuntimeException) { close(); fail("The video source sent an invalid request or response.") }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = if (remaining < 0) length else minOf(remaining, length.toLong()).toInt()
        val read = try { stream?.read(buffer, offset, requested) ?: C.RESULT_END_OF_INPUT }
        catch (_: IOException) { fail("The video connection was interrupted. Retry or choose a fresh source.") }
        if (read < 0 && remaining > 0L) fail("The video source ended before its advertised length.")
        if (read > 0) { if (remaining >= 0) remaining -= read; bytesTransferred(read) }
        return read
    }

    override fun getUri(): Uri? = address
    override fun getResponseHeaders(): Map<String, List<String>> {
        val fields: Map<String?, List<String>> = connection?.headerFields ?: return emptyMap()
        return fields.mapNotNull { (key, values) -> key?.let { it to values } }.toMap()
    }

    override fun close() {
        runCatching { stream?.close() }
        stream = null
        connection?.disconnect()
        connection = null
        address = null
        remaining = C.LENGTH_UNSET.toLong()
        if (opened) { opened = false; transferEnded() }
    }

    private fun fail(message: String): Nothing = throw OriginBoundMediaException(message)
}
