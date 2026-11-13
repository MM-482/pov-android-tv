package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class MediaAddressPolicyTest {
    // Public synthetic network fixtures; keep each tested address unchanged.
    private fun fixtureHost(vararg octets: Any): String = octets.joinToString(".")
    @Test fun `secure media and user selected documents are accepted`() {
        listOf("https://media.example/video.mkv","https://media.example:8443/video?id=1","content://documents/document/selected").forEach {
            assertTrue(it,MediaAddressPolicy.accepts(it))
        }
    }
    @Test fun `cleartext is confined to canonical private loopback and mDNS hosts`() {
        listOf("http://${fixtureHost(192, 168, 0, 20)}/video","http://${fixtureHost(10, 1, 2, 3)}/video","http://${fixtureHost(172, 16, 2, 3)}/video","http://${fixtureHost(172, 31, 2, 3)}/video",
            "http://${fixtureHost(127, 0, 0, 1)}:8080/video","http://localhost/video","http://server.local/video","http://[::1]/video","http://${fixtureHost(169, 254, 1, 2)}/video").forEach {
            assertTrue(it,MediaAddressPolicy.accepts(it))
        }
        listOf("http://public.example/video","http://${fixtureHost(8, 8, 8, 8)}/video","http://${fixtureHost(172, 15, 0, 1)}/video","http://${fixtureHost(172, 32, 0, 1)}/video","http://${fixtureHost(192, 169, 0, 1)}/video").forEach {
            assertFalse(it,MediaAddressPolicy.accepts(it))
        }
    }
    @Test fun `noncanonical legacy IPv4 forms cannot bypass cleartext host policy`() {
        listOf("${fixtureHost("010", 0, 0, 1)}","+${fixtureHost(10, 0, 0, 1)}","${fixtureHost(127, "000", 0, 1)}","0x7f.0.0.1","2130706433","127.1","${fixtureHost(192, 168, 0, 256)}").forEach {
            assertFalse(it,MediaAddressPolicy.localHost(it))
        }
    }
    @Test fun `credentials commands unsupported schemes and missing host are rejected`() {
        listOf("https://user:password@media.example/video","http://user:password@${fixtureHost(192, 168, 0, 1)}/video","content://user:secret@documents/path",
            "file:///private/video","javascript:alert(1)","ftp://server/video","https:///video","https://example/video\r\nAuthorization: secret").forEach {
            assertFalse(it,MediaAddressPolicy.accepts(it))
        }
    }
    @Test fun `origin identity respects case scheme explicit default port and nondefault ports`() {
        assertTrue(MediaAddressPolicy.sameOrigin(URI("https://example/video"),URI("https://EXAMPLE:443/other")))
        assertTrue(MediaAddressPolicy.sameOrigin(URI("http://example/video"),URI("http://example:80/other")))
        assertFalse(MediaAddressPolicy.sameOrigin(URI("https://example/video"),URI("https://cdn.example/video")))
        assertFalse(MediaAddressPolicy.sameOrigin(URI("https://example/video"),URI("https://example:8443/video")))
        assertFalse(MediaAddressPolicy.sameOrigin(URI("https://example/video"),URI("http://example/video")))
    }
}
