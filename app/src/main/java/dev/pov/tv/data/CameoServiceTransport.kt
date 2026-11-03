package dev.pov.tv.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.Authenticator
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Separate transport for explicit local media servers and XML/WebDAV bodies. No redirect replay. */
interface CameoServiceTransport {
    fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
        body: String? = null, contentType: String = "application/json"): String
}

object CameoServiceUrls {
    fun checked(value: String): URI {
        require(value.length in 1..8192 && value.none { it.code < 32 || it.code == 127 }) { "Enter a valid service address." }
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid service address.") }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
            uri.port in -1..65535 && uri.port != 0 &&
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true) && isLocalHost(uri.host))) {
            "Use HTTPS, or HTTP for a private local media server."
        }
        return uri
    }
    fun isLocalHost(value: String): Boolean {
        val host = value.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").substringBefore('%')
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        val rawParts = host.split('.')
        val parts = rawParts.mapNotNull(String::toIntOrNull)
        if (rawParts.size == 4 && rawParts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) } && parts.size == 4 && parts.all { it in 0..255 }) return parts[0] == 10 || parts[0] == 127 ||
            parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31 || parts[0] == 169 && parts[1] == 254
        if (':' in host && host.matches(Regex("[0-9a-f:]+"))) {
            val prefix = host.substringBefore(':').toIntOrNull(16) ?: -1
            return prefix in 0xfc00..0xfdff || prefix in 0xfe80..0xfebf
        }
        return false
    }
    fun origin(value: String): String = checked(value).let { "${it.scheme.lowercase(Locale.ROOT)}://${it.rawAuthority}" }
    internal fun privateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isMulticastAddress) return false
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
    }
    fun sameOrigin(a: URI, b: URI): Boolean = a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) &&
        (if (a.port == -1) if (a.scheme.equals("https", true)) 443 else 80 else a.port) ==
        (if (b.port == -1) if (b.scheme.equals("https", true)) 443 else 80 else b.port)
    /** An add-on may serve local resources only from the explicitly installed origin. */
    fun addonResource(value: String, configuredOrigin: String): URI = checked(value).also { target ->
        require(!target.scheme.equals("http", true) || configuredOrigin.isNotBlank() &&
            sameOrigin(target, checked(configuredOrigin))) { "A local add-on resource must stay on its configured server." }
    }
    internal fun privateDns(upstream: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = upstream.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !privateAddress(it) }) {
                throw IOException("Unencrypted service connections are limited to the configured local network.")
            }
        }
    }
    fun insideRoot(root: String, address: String): String {
        val base = checked(root.trimEnd('/') + "/")
        require(base.query == null) { "Use a server root without a query string." }
        val target = checked(base.resolve(address).toString())
        val decoded = URLDecoder.decode(target.rawPath.replace("+", "%2B"), "UTF-8")
        val rootPath = URLDecoder.decode(base.rawPath.replace("+", "%2B"), "UTF-8")
        require(sameOrigin(base, target) && target.query == null && '\\' !in decoded &&
            decoded.split('/').none { it == "." || it == ".." } &&
            (decoded == rootPath.trimEnd('/') || decoded.startsWith(rootPath))) { "This file is outside the configured server folder." }
        return target.toString()
    }
}

object LiveCameoServiceTransport : CameoServiceTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    // The checked DNS result is the address OkHttp connects to. A proxy cannot redirect a LAN request.
    private val localClient = client.newBuilder().proxy(Proxy.NO_PROXY).dns(CameoServiceUrls.privateDns()).build()

    /** Credential-free add-on metadata/subtitle bytes, with bounded redirects and response size. */
    fun publicBytes(url: String, configuredOrigin: String, maxBytes: Int = 16 * 1024 * 1024): ByteArray {
        require(maxBytes in 1..16 * 1024 * 1024) { "Choose a bounded add-on response size." }
        var target = CameoServiceUrls.addonResource(url, configuredOrigin)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        try {
            repeat(5) { hop ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Add-on request canceled.")
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw ApiException(0, "The add-on request timed out.")
                val transport = if (target.scheme.equals("http", true)) localClient else client
                val call = transport.newCall(Request.Builder().url(target.toString()).get()
                    .header("Accept", "*/*").header("User-Agent", "Cameo-Android/0.4").build())
                call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        if (hop == 4) throw ApiException(response.code, "The add-on redirected too many times.")
                        val location = response.header("Location")
                            ?: throw ApiException(response.code, "The add-on returned an incomplete redirect.")
                        val next = try { target.resolve(location).toString() }
                        catch (_: Exception) { throw ApiException(response.code, "The add-on returned an invalid redirect.") }
                        target = CameoServiceUrls.addonResource(next, configuredOrigin)
                    } else {
                        if (response.code !in 200..299) throw ApiException(response.code, when (response.code) {
                            404, 410 -> "This add-on resource is no longer available. Search again."
                            401, 403 -> "The add-on denied access. Check its configuration."
                            429 -> "The add-on is busy. Wait before trying again."
                            else -> "The add-on request failed (HTTP ${response.code})."
                        })
                        val body = response.body ?: return byteArrayOf()
                        if (body.contentLength() > maxBytes) throw ApiException(0, "The add-on response is too large.")
                        val bytes = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException("Add-on request canceled.")
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > maxBytes - bytes.size()) throw ApiException(0, "The add-on response is too large.")
                                bytes.write(buffer, 0, count)
                            }
                        }
                        return bytes.toByteArray()
                    }
                }
            }
            throw ApiException(0, "The add-on redirected too many times.")
        } catch (error: ApiException) { throw error
        } catch (_: IOException) { throw ApiException(0, "The add-on could not be reached. Check its configured address and connection.") }
    }
    override fun request(url: String, method: String, headers: Map<String, String>, body: String?, contentType: String): String {
        val target = CameoServiceUrls.checked(url)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Service request canceled.")
        val request = Request.Builder().url(target.toString()).header("Accept", "application/json")
            .header("User-Agent", "Cameo-Android/0.4")
        headers.forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z0-9-]+")) && value.none { it == '\r' || it == '\n' || it.code == 0 }) { "Invalid service request header." }
            request.header(key, value)
        }
        val payload = body?.toByteArray(Charsets.UTF_8)?.toRequestBody(contentType.toMediaType())
        request.method(method, payload)
        try {
            val transport = if (target.scheme.equals("http", true)) localClient else client
            return transport.newCall(request.build()).execute().use { response ->
                val status = response.code
                if (status !in 200..299) throw ApiException(status, when (status) {
                    401, 403 -> "Account authorization is required or has expired."
                    404 -> "The requested service item is no longer available."
                    451 -> "The service has restricted access to this item."
                    429 -> "This service is busy. Wait before trying again."
                    in 300..399 -> "The service redirected the account request. Check its configured address."
                    else -> "The service request failed (HTTP $status)."
                })
                val input = response.body?.byteStream() ?: return@use ""
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("The service request was canceled.")
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(bytes.size() + count <= 16 * 1024 * 1024) { "The service response is too large." }
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
        } catch (error: ApiException) { throw error
        } catch (_: IOException) { throw ApiException(0, "The service could not be reached. Check its address and connection.")
        }
    }
}
