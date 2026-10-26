package dev.pov.tv.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.Locale
import javax.net.ssl.SSLException

/** Messages deliberately never contain request URLs, server bodies, or credentials. */
class ApiException(
    val code: Int,
    message: String,
    val errorKey: String = "",
    val retryAfterSeconds: Int = 0
) : IOException(message)
data class NetReply(val body: String, val headers: Map<String, String> = emptyMap())

/** Blocking HTTPS client. Call from a worker thread, never the Android main thread. */
object Net {
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun json(
        url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
        body: JSONObject? = null
    ): JSONObject = parseObject(raw(url, method, headers, body))

    /** Public Cinemeta metadata only: there is no API for supplying headers, tokens, or a body. */
    fun publicJson(url: String): JSONObject {
        publicMetadataTarget(url)
        return parseObject(request(url, "GET", emptyMap(), null, "application/json", publicMetadata = true))
    }

    fun raw(
        url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
        body: JSONObject? = null
    ): String = request(url, method, headers, body?.toString()?.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    fun form(url: String, fields: List<Pair<String, String>>, headers: Map<String, String> = emptyMap()): String =
        request(url, "POST", headers, fields.joinToString("&") { encode(it.first) + "=" + encode(it.second) }
            .toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded; charset=utf-8")

    /** Pagination/quota metadata only; authentication and cookie response headers are not exposed. */
    fun reply(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: JSONObject? = null): NetReply {
        var metadata: Map<String, String> = emptyMap()
        val text = request(url, method, headers, body?.toString()?.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8",
            responseMetadata = { metadata = it })
        return NetReply(text, metadata)
    }

    internal fun multipart(
        url: String, fields: Map<String, String>, headers: Map<String, String>
    ): JSONObject {
        val boundary = "pov-${UUID.randomUUID()}"
        val bytes = buildString {
            fields.forEach { (key, value) ->
                require(key.matches(Regex("[a-z_]+")))
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n")
            }
            append("--$boundary--\r\n")
        }.toByteArray(Charsets.UTF_8)
        return parseObject(request(url, "POST", headers, bytes, "multipart/form-data; boundary=$boundary"))
    }

    private fun parseObject(text: String): JSONObject = try {
        if (text.isBlank()) JSONObject() else JSONObject(text)
    } catch (_: Exception) {
        throw ApiException(0, "The service returned an unreadable response.")
    }

    private fun request(
        address: String, method: String, headers: Map<String, String>, bytes: ByteArray?, contentType: String,
        publicMetadata: Boolean = false, redirectsLeft: Int = 5, responseMetadata: ((Map<String, String>) -> Unit)? = null
    ): String {
        val target = try { URL(address) } catch (_: Exception) {
            throw ApiException(0, "The service address is invalid.")
        }
        if (target.protocol != "https" || target.host.isNullOrBlank() || target.userInfo != null) {
            throw ApiException(0, "A secure HTTPS service address is required.")
        }
        if (publicMetadata) {
            publicMetadataTarget(address)
            check(method == "GET" && headers.isEmpty() && bytes == null) { "Public metadata requests cannot carry credentials." }
        }
        var connection: HttpURLConnection? = null
        try {
            connection = target.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            // Do not forward authorization or a token query to an unexpected redirect target.
            connection.instanceFollowRedirects = false
            connection.requestMethod = method.uppercase()
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "POV-Standalone-AndroidTV/0.1")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (bytes != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status in 200..299 && responseMetadata != null) {
                responseMetadata(listOf("X-Next-Cursor", "X-Has-More", "X-Total-Items", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                    "X-RateLimit-Reset", "Retry-After").mapNotNull { name -> connection.getHeaderField(name)?.let { name.lowercase(Locale.ROOT) to it } }.toMap())
            }
            if (publicMetadata && status in setOf(301, 302, 303, 307, 308)) {
                val next = publicMetadataRedirect(target, connection.getHeaderField("Location"), redirectsLeft)
                connection.disconnect()
                // Construct a fresh credential-free GET for every approved hop. Account/provider
                // requests never opt in, and redirect query strings are rejected before any request.
                return request(next.toExternalForm(), "GET", emptyMap(), null, "application/json",
                    publicMetadata = true, redirectsLeft = redirectsLeft - 1)
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 16 * 1024 * 1024) {
                        throw ApiException(0, "The service response is too large.")
                    }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }.orEmpty()
            if (status !in 200..299) {
                val serverError = try {
                    JSONObject(text).optString("error").takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }.orEmpty()
                } catch (_: Exception) { "" }
                val retry = retrySeconds(connection.getHeaderField("Retry-After"))
                val message = when (status) {
                    400, 422 -> "The service could not accept this request."
                    401, 403 -> "Account authorization is required or has expired."
                    404 -> "The requested item is unavailable."
                    409 -> "The service has already received this update."
                    410 -> "The authorization code has expired."
                    418 -> "Authorization was declined."
                    429 -> "The service is busy. Please wait before trying again."
                    in 500..599 -> "The service is temporarily unavailable."
                    in 300..399 -> "The service redirected this request unexpectedly."
                    else -> "The service request failed (HTTP $status)."
                }
                throw ApiException(status, message, serverError, retry)
            }
            return text
        } catch (e: ApiException) {
            throw e
        } catch (_: SocketTimeoutException) {
            throw ApiException(0, "The service request timed out. Please try again.")
        } catch (_: SSLException) {
            throw ApiException(0, "A secure connection to the service could not be established.")
        } catch (_: IOException) {
            throw ApiException(0, "The service could not be reached. Check your connection.")
        } finally {
            connection?.disconnect()
        }
    }

    internal fun publicMetadataTarget(address: String): URL {
        val target = try { URL(address) } catch (_: Exception) {
            throw ApiException(0, "The public catalog address is invalid.")
        }
        if (target.protocol != "https" || target.host.lowercase(Locale.ROOT) !in PUBLIC_METADATA_HOSTS ||
            target.userInfo != null || target.query != null || target.ref != null || target.port !in setOf(-1, 443)) {
            throw ApiException(0, "The public catalog returned an unsupported address.")
        }
        return target
    }

    internal fun publicMetadataRedirect(current: URL, location: String?, redirectsLeft: Int): URL {
        if (redirectsLeft <= 0) throw ApiException(0, "The public catalog redirected too many times.")
        if (location.isNullOrBlank()) throw ApiException(0, "The public catalog returned an incomplete redirect.")
        publicMetadataTarget(current.toExternalForm())
        val next = try { URL(current, location) } catch (_: Exception) {
            throw ApiException(0, "The public catalog returned an invalid redirect.")
        }
        return publicMetadataTarget(next.toExternalForm())
    }

    private fun retrySeconds(header: String?): Int {
        if (header.isNullOrBlank()) return 0
        header.toLongOrNull()?.let { return it.coerceIn(0, 86400).toInt() }
        return try {
            ((ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() -
                System.currentTimeMillis() / 1000).coerceIn(0, 86400)).toInt()
        } catch (_: Exception) { 0 }
    }

    private val PUBLIC_METADATA_HOSTS = setOf("v3-cinemeta.strem.io", "cinemeta-catalogs.strem.io")
}
