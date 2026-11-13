package dev.pov.tv

import java.net.URI

/** Local documents need no storage-wide permission. Cleartext is limited to explicit LAN addresses. */
internal object MediaAddressPolicy {
    fun accepts(address: String): Boolean = try {
        val uri = URI(address)
        when (uri.scheme?.lowercase()) {
            "content" -> !uri.authority.isNullOrBlank() && uri.userInfo == null
            "https" -> !uri.host.isNullOrBlank() && uri.userInfo == null
            "http" -> uri.userInfo == null && localHost(uri.host.orEmpty())
            else -> false
        } && address.none { it == '\r' || it == '\n' }
    } catch (_: Exception) { false }
    fun localHost(host: String): Boolean {
        val value = host.lowercase().removePrefix("[").removeSuffix("]")
        if (value == "localhost" || value == "::1" || value.endsWith(".local")) return true
        val parts=value.split('.')
        if(parts.size!=4)return false
        if(parts.any{!it.matches(Regex("0|[1-9][0-9]{0,2}"))})return false
        val bytes = parts.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        return bytes.size == 4 && (bytes[0] == 10 || bytes[0] == 127 ||
            bytes[0] == 192 && bytes[1] == 168 || bytes[0] == 172 && bytes[1] in 16..31 ||
            bytes[0] == 169 && bytes[1] == 254)
    }
    fun sameOrigin(first: URI, second: URI): Boolean = first.scheme.equals(second.scheme, true) &&
        first.host.equals(second.host, true) && port(first) == port(second)
    private fun port(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
}
