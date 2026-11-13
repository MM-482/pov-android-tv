package dev.pov.tv.data

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/** Separate transport for user-configured media servers and the desktop-compatible adapters. */
interface DesktopTransport {
    fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
        body: String? = null, contentType: String = "application/json; charset=utf-8"): String
}

object DesktopUrlPolicy {
    fun address(raw: String): URI {
        val uri = try { URI(raw) } catch (_: Exception) { throw ApiException(0, "The server address is invalid.") }
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "Use an HTTPS address, or HTTP for a local network server."
        }
        require(uri.scheme == "https" || localHost(uri.host)) { "HTTP is supported only for local network servers. Use HTTPS for remote servers." }
        require(uri.port in -1..65535 && uri.port != 0 && '\\' !in raw &&
            !Regex("%2f|%5c|%00|%25(?:2e|2f|5c)", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath.orEmpty()) &&
            uri.path.orEmpty().split('/').none { it == ".." || it == "." }) {
            "The server returned an invalid path."
        }
        return uri
    }

    fun endpoint(raw: String): URI {
        val uri = address(raw.trim())
        require(uri.rawQuery == null) { "The server address must not contain a query string." }
        return URI(uri.toASCIIString().trimEnd('/') + "/")
    }

    fun sameOrigin(left: URI, right: URI): Boolean = left.scheme == right.scheme &&
        left.host.equals(right.host, true) && port(left) == port(right)

    fun within(root: URI, raw: String): URI {
        val target = address(root.resolve(raw).toASCIIString())
        require(sameOrigin(root, target) && target.path.startsWith(root.path)) { "This item is outside the configured server root." }
        return target
    }

    private fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
    internal fun localHost(value: String): Boolean {
        val host = value.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.endsWith(".local") || host == "::1") return true
        if (host.contains(':')) return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")
        val labels = host.split('.')
        // Ambiguous IPv4 forms can be interpreted differently by the URL stack and resolver.
        if (labels.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || it.length > 1 && it[0] == '0' }) return false
        val numbers = labels.map { it.toIntOrNull() ?: return false }
        if (numbers.size != 4 || numbers.any { it !in 0..255 }) return false
        return numbers[0] == 10 || numbers[0] == 127 || numbers[0] == 192 && numbers[1] == 168 ||
            numbers[0] == 172 && numbers[1] in 16..31 || numbers[0] == 169 && numbers[1] == 254
    }
}

object LiveDesktopTransport : DesktopTransport {
    override fun request(url: String, method: String, headers: Map<String, String>, body: String?, contentType: String): String {
        DesktopUrlPolicy.address(url)
        require(method in setOf("GET", "POST", "PATCH", "PROPFIND", "PUT")) { "Unsupported service request." }
        require(headers.all { (name, value) -> name.matches(Regex("[A-Za-z0-9-]+")) && value.none { it == '\r' || it == '\n' } }) {
            "The service returned invalid authorization headers."
        }
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Cameo-Android")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            body?.toByteArray(Charsets.UTF_8)?.let { bytes ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = when (code) {
                    401, 403 -> "The service did not authorize this account. Check its credentials and permissions."
                    404 -> "The selected service item is unavailable."
                    429 -> "The service is rate limited. Wait before trying again."
                    in 300..399 -> "The service redirected this request. Enter its final server address."
                    else -> "The service request failed (HTTP $code)."
                }
                throw ApiException(code, message)
            }
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 16 * 1024 * 1024) throw ApiException(0, "The service response is too large.")
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
        } catch (error: ApiException) { throw error }
        catch (_: java.net.SocketTimeoutException) { throw ApiException(0, "The service timed out. Please try again.") }
        catch (_: java.io.IOException) { throw ApiException(0, "The service could not be reached. Check its server address and connection.") }
        finally { connection?.disconnect() }
    }
}
