package dev.pov.tv.subtitles

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal data class SubtitleRequest(
    val url: String, val method: String = "GET", val headers: Map<String, String> = emptyMap(),
    val body: JSONObject? = null, val maxBytes: Int = 2 * 1024 * 1024,
    val allowedHosts: Set<String>, val allowSubdomains: Boolean = false,
    val download: Boolean = false
)
internal data class SubtitleResponse(val bytes: ByteArray, val contentType: String) {
    fun json(): JSONObject = try { JSONObject(String(bytes, Charsets.UTF_8)) }
        catch (_: Exception) { throw SubtitleException("The subtitle service returned an unreadable response.") }
}
internal fun interface SubtitleTransport { fun execute(request: SubtitleRequest): SubtitleResponse }

/** Provider credentials never cross origins. Downloads strip all credentials on every redirect. */
internal class SubtitleHttp(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : SubtitleTransport {
    override fun execute(request: SubtitleRequest): SubtitleResponse {
        var target = checkedUrl(request.url, request.allowedHosts, request.allowSubdomains)
        var headers = request.headers
        var method = request.method
        var body = request.body
        repeat(5) { hop ->
            if (Thread.currentThread().isInterrupted) throw SubtitleException("Subtitle request cancelled.")
            var connection: HttpURLConnection? = null
            try {
                connection = open(target)
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 25_000
                connection.requestMethod = method
                connection.setRequestProperty("User-Agent", "POV-Standalone v0.3")
                connection.setRequestProperty("Accept", if (request.download) "*/*" else "application/json")
                headers.forEach { (key, value) ->
                    if (value.any { it == '\r' || it == '\n' }) throw SubtitleException("A saved subtitle credential is invalid.")
                    connection.setRequestProperty(key, value)
                }
                if (body != null) {
                    val payload = body.toString().toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(payload.size)
                    connection.outputStream.use { it.write(payload) }
                }
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 4) throw SubtitleException("The subtitle service redirected too many times.")
                    val location = connection.getHeaderField("Location")
                        ?: throw SubtitleException("The subtitle service returned an incomplete download link.")
                    val next = checkedUrl(URL(target, location).toExternalForm(), request.allowedHosts, request.allowSubdomains)
                    if (!request.download && (method != "GET" || next.host != target.host || next.port != target.port)) {
                        throw SubtitleException("The subtitle service redirected an authenticated request unexpectedly.")
                    }
                    if (request.download) { headers = emptyMap(); method = "GET"; body = null }
                    target = next
                } else {
                    if (code !in 200..299) {
                        val retry = connection.getHeaderField("Retry-After")?.toIntOrNull()?.coerceIn(0, 86400) ?: 0
                        throw httpError(code, retry)
                    }
                    if (connection.contentLengthLong > request.maxBytes) throw SubtitleException("The subtitle download is too large.")
                    val bytes = connection.inputStream.use { boundedRead(it, request.maxBytes) }
                    return SubtitleResponse(bytes, connection.contentType.orEmpty())
                }
            } catch (error: SubtitleException) { throw error
            } catch (_: IOException) { throw SubtitleException("The subtitle service could not be reached. Check your connection and try again.")
            } catch (_: Exception) { throw SubtitleException("The subtitle request could not be completed.")
            } finally { connection?.disconnect() }
        }
        throw SubtitleException("The subtitle service redirected too many times.")
    }

    companion object {
        fun checkedUrl(value: String, hosts: Set<String>, subdomains: Boolean = false): URL {
            val url = try { URL(value) } catch (_: Exception) { throw SubtitleException("The subtitle service returned an invalid address.") }
            val host = url.host.lowercase(Locale.ROOT)
            if (url.protocol != "https" || url.userInfo != null || url.port !in setOf(-1, 443) || url.ref != null ||
                hosts.none { host == it || (subdomains && host.endsWith(".$it")) }) {
                throw SubtitleException("The subtitle service returned an unsupported secure download address.")
            }
            return url
        }

        fun boundedRead(input: java.io.InputStream, limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw SubtitleException("Subtitle request cancelled.")
                val length = input.read(buffer)
                if (length < 0) break
                if (length > limit - output.size()) throw SubtitleException("The subtitle download is too large.")
                output.write(buffer, 0, length)
            }
            return output.toByteArray()
        }

        private fun httpError(code: Int, retry: Int) = SubtitleException(when (code) {
            401 -> "Authorization failed. Check this provider's API key and account in Settings."
            402, 406 -> "This provider's download quota or account plan does not allow this download."
            403 -> "This provider denied access. Check the API key, account permissions, and plan."
            404, 410 -> "This subtitle is no longer available. Search again and choose another result."
            429 -> if (retry > 0) "The provider's request or download quota was reached. Try again in $retry seconds." else "The provider's request or download quota was reached. Try again after its quota resets."
            in 500..599 -> "The subtitle provider is temporarily unavailable. Try again later."
            else -> "The subtitle provider could not complete this request (HTTP $code)."
        }, code, retry)
    }
}
