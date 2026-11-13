package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameoNzbTest {
    private fun nzb(segments: String, extra: String = "", subject: String = "&quot;sample.mp4&quot; yEnc") =
        """<?xml version="1.0"?><nzb xmlns="http://www.newzbin.com/DTD/2003/nzb"><file subject="$subject"><groups><group>alt.binaries.test</group></groups><segments>$segments</segments></file>$extra</nzb>"""
    private fun segment(number: Int = 1, bytes: Long = 123, id: String = "one@fixture") =
        """<segment bytes="$bytes" number="$number">$id</segment>"""
    private fun parse(value: String) = CameoNzb.parse(ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)))
    private fun file(count: Int = 1) = NzbFile("fixture", "sample.mp4", "", emptyList(),
        (1..count).map { NzbSegment(it, 123, "$it@fixture") }, 123L * count)
    private inline fun <reified T : Throwable> reject(action: () -> Unit): T {
        try { action() } catch (failure: Throwable) { if (failure is T) return failure else throw failure }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value.toString(16).padStart(8, '0')
    private fun encode(bytes: ByteArray, part: Int? = null, total: Int = 1, begin: Int = 1,
        size: Int = bytes.size, name: String = "sample.mp4", fullCrc: String? = null, corrupt: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        val header = "=ybegin " + (if (part == null) "" else "part=$part total=$total ") + "line=128 size=$size name=$name\r\n" +
            (if (part == null) "" else "=ypart begin=$begin end=${begin + bytes.size - 1}\r\n")
        output.write(header.toByteArray(Charsets.US_ASCII))
        bytes.forEach { byte ->
            val value = ((byte.toInt() and 255) + 42) and 255
            if (value in setOf(0, 10, 13, 61)) { output.write(61); output.write((value + 64) and 255) }
            else output.write(value)
        }
        val checksum = if (corrupt) "00000000" else crc(bytes)
        output.write(("\r\n=yend size=${bytes.size}" + (if (part == null) " crc32=$checksum" else " part=$part pcrc32=$checksum") +
            (if (fullCrc != null) " crc32=$fullCrc" else "") + "\r\n").toByteArray(Charsets.US_ASCII))
        return output.toByteArray()
    }
    private class Articles(private val responses: Map<String, ByteArray>) : NzbArticles {
        var authenticated = false; var closed = false; val requested = mutableListOf<String>()
        override fun authenticate(username: String, password: String) { assertEquals("fixture", username); assertEquals("fixture-password", password); authenticated = true }
        override fun body(messageId: String): ByteArray { assertTrue(authenticated); requested += messageId; return responses[messageId] ?: throw IOException("Missing article") }
        override fun close() { closed = true }
    }

    @Test fun `NZB reads namespace quoted filename and sorted actual article identities`() {
        val files = parse(nzb(segment(2, id="two@fixture") + segment()))
        assertEquals(1, files.size)
        assertEquals("sample.mp4", files.single().name)
        assertEquals(listOf("one@fixture", "two@fixture"), files.single().segments.map { it.messageId })
        assertEquals(246, files.single().estimatedBytes)
        assertEquals(listOf("alt.binaries.test"), files.single().groups)
        assertTrue(files.single().id.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun `NZB file identity depends on ordered messages rather than private input path`() {
        val first = parse(nzb(segment())).single()
        val renamed = parse(nzb(segment(), subject="&quot;renamed.mp4&quot;")).single()
        assertEquals(first.id, renamed.id)
        assertNotEquals(first.name, renamed.name)
    }

    @Test fun `NZB rejects missing duplicated or noncontiguous segment numbers`() {
        listOf(segment(2), segment() + segment(), segment() + segment(3, id="three@fixture"), segment(0)).forEach {
            reject<IOException> { parse(nzb(it)) }
        }
    }

    @Test fun `NZB rejects duplicate article identities and repeated files`() {
        reject<IOException> { parse(nzb(segment() + segment(2))) }
        val extra = """<file subject="other"><segments>${segment()}</segments></file>"""
        reject<IOException> { parse(nzb(segment(), extra)) }
    }

    @Test fun `NZB rejects DTD external entities and excessive nesting`() {
        val dtd = """<!DOCTYPE nzb [<!ENTITY x SYSTEM "file:///private-account">]>"""
        reject<IOException> { parse(dtd + nzb(segment(id="&x;" )).substringAfter("?>")) }
        reject<IOException> { parse("<nzb>" + "<nested>".repeat(17) + "</nested>".repeat(17) + "</nzb>") }
    }

    @Test fun `NZB rejects oversized empty invalid and command-injection articles`() {
        reject<IOException> { parse("") }
        reject<IOException> { parse("<root/>") }
        reject<IOException> { parse(nzb(segment(bytes=0))) }
        reject<IOException> { parse(nzb(segment(bytes=CameoNzb.MAX_ARTICLE_BYTES + 1L))) }
        listOf("bad id@fixture", "bad&#13;&#10;AUTHINFO PASS injected@fixture", "missing-at", "less&lt;@fixture").forEach {
            reject<IOException> { parse(nzb(segment(id=it))) }
        }
        reject<IOException> { CameoNzb.parse(ByteArrayInputStream(ByteArray(CameoNzb.MAX_NZB_BYTES + 1) { 32 })) }
    }

    @Test fun `NZB handles UTF16 input without processing declarations`() {
        val xml = nzb(segment()).replace("version=\"1.0\"", "version=\"1.0\" encoding=\"UTF-16\"")
        val files = CameoNzb.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_16)))
        assertEquals("sample.mp4", files.single().name)
    }

    @Test fun `suggested filename cannot provide a provider path`() {
        val result = parse(nzb(segment(), subject="&quot;../../unsafe/sample.mp4&quot;")).single()
        assertFalse(result.name.contains('/'))
        assertFalse(result.name.contains('\\'))
        assertFalse(result.name.startsWith('.'))
    }

    @Test fun `yEnc single part decodes every byte and verifies its checksum`() {
        val bytes = ByteArray(256) { it.toByte() }
        val result = CameoNzb.decodeYenc(encode(bytes))
        assertArrayEquals(bytes, result.data)
        assertEquals(1, result.part)
        assertEquals(1, result.total)
        assertEquals(0, result.begin)
        assertEquals(256, result.end)
    }

    @Test fun `yEnc multipart uses real part range and checksum`() {
        val bytes = "fixture bytes".toByteArray()
        val result = CameoNzb.decodeYenc(encode(bytes, part=2, total=3, begin=40, size=90))
        assertEquals(2, result.part)
        assertEquals(3, result.total)
        assertEquals(39, result.begin)
        assertEquals(39 + bytes.size.toLong(), result.end)
        assertArrayEquals(bytes, result.data)
    }

    @Test fun `yEnc rejects checksum corruption wrong offsets and truncated escapes`() {
        val bytes = "fixture bytes".toByteArray()
        reject<IOException> { CameoNzb.decodeYenc(encode(bytes, corrupt=true)) }
        val encoded = String(encode(bytes, part=1), Charsets.ISO_8859_1)
        reject<IOException> { CameoNzb.decodeYenc(encoded.replace("begin=1", "begin=2").toByteArray(Charsets.ISO_8859_1)) }
        reject<IOException> { CameoNzb.decodeYenc(encoded.replace("\r\n=yend", "=\r\n=yend").toByteArray(Charsets.ISO_8859_1)) }
    }

    @Test fun `yEnc rejects duplicate headers and unsupported multiple encoded files`() {
        val encoded = encode("fixture".toByteArray())
        reject<IOException> { CameoNzb.decodeYenc(encoded + encoded) }
        val duplicate = String(encoded, Charsets.ISO_8859_1).replace("line=128", "line=128 line=128")
        reject<IOException> { CameoNzb.decodeYenc(duplicate.toByteArray(Charsets.ISO_8859_1)) }
    }

    @Test fun `selected NZB download uses only chosen articles in order and closes session`() {
        val first = "first".toByteArray(); val second = "second".toByteArray(); val all = first + second
        val articles = Articles(mapOf("1@fixture" to encode(first,1,2,1,all.size),
            "2@fixture" to encode(second,2,2,first.size + 1,all.size,fullCrc=crc(all))))
        val output = ByteArrayOutputStream(); val progress = mutableListOf<Pair<Long,Long>>()
        val size = CameoNzb.downloadTo(file(2), "fixture", "fixture-password", output, { articles }, progress={ a,b -> progress += a to b })
        assertEquals(all.size.toLong(), size)
        assertArrayEquals(all, output.toByteArray())
        assertEquals(listOf("1@fixture", "2@fixture"), articles.requested)
        assertTrue(articles.closed)
        assertEquals(0L to all.size.toLong(), progress.first())
    }

    @Test fun `download rejects cross-file name part total and offset substitutions`() {
        val first = "first".toByteArray(); val second = "second".toByteArray(); val size = first.size + second.size
        val wrong = listOf(encode(second,2,2,first.size+1,size,name="other.mp4"),
            encode(second,1,2,first.size+1,size), encode(second,2,3,first.size+1,size), encode(second,2,2,first.size+2,size+1))
        wrong.forEach { secondPart ->
            val articles = Articles(mapOf("1@fixture" to encode(first,1,2,1,size), "2@fixture" to secondPart))
            reject<IOException> { CameoNzb.downloadTo(file(2), "fixture", "fixture-password", ByteArrayOutputStream(), { articles }) }
            assertTrue(articles.closed)
        }
    }

    @Test fun `download rejects complete file CRC mismatch and incomplete full size`() {
        val first = "first".toByteArray(); val second = "second".toByteArray(); val size = first.size + second.size
        val articles = Articles(mapOf("1@fixture" to encode(first,1,2,1,size), "2@fixture" to encode(second,2,2,first.size+1,size,fullCrc="00000000")))
        reject<IOException> { CameoNzb.downloadTo(file(2), "fixture", "fixture-password", ByteArrayOutputStream(), { articles }) }
        val incomplete = Articles(mapOf("1@fixture" to encode(first,1,1,1,size)))
        reject<IOException> { CameoNzb.downloadTo(file(), "fixture", "fixture-password", ByteArrayOutputStream(), { incomplete }) }
    }

    @Test fun `pre-cancelled or invalid credential downloads never open a connection`() {
        var opened = false
        reject<CancellationException> { CameoNzb.downloadTo(file(), "fixture", "fixture-password", ByteArrayOutputStream(), { opened=true; Articles(emptyMap()) }, { true }) }
        reject<IOException> { CameoNzb.downloadTo(file(), "fixture\r\nBODY bad", "fixture-password", ByteArrayOutputStream(), { opened=true; Articles(emptyMap()) }) }
        reject<IOException> { CameoNzb.downloadTo(file(), "fixture", "bad\r\npassword", ByteArrayOutputStream(), { opened=true; Articles(emptyMap()) }) }
        assertFalse(opened)
    }

    @Test fun `cancellation between article requests closes current session`() {
        val bytes = "fixture".toByteArray()
        var cancel = false
        val articles = Articles(mapOf("1@fixture" to encode(bytes,1,2,1,bytes.size*2)))
        reject<CancellationException> {
            CameoNzb.downloadTo(file(2), "fixture", "fixture-password", ByteArrayOutputStream(), { articles }, { cancel }, { a,_ -> if(a>0) cancel=true })
        }
        assertEquals(listOf("1@fixture"), articles.requested)
        assertTrue(articles.closed)
    }

    @Test fun `NNTP uses owner credentials and exact BODY command without logging response text`() {
        val body = "=ybegin line=128 size=1 name=x\r\nk\r\n=yend size=1 crc32=d3d99e8b\r\n"
        val input = "200 ready\r\n381 password required\r\n281 accepted\r\n101 capabilities\r\nVERSION 2\r\nREADER\r\n.\r\n222 0 <1@fixture> body\r\n$body.\r\n"
        val output = ByteArrayOutputStream()
        CameoNntpProtocol(ByteArrayInputStream(input.toByteArray(Charsets.ISO_8859_1)), output).use { protocol ->
            protocol.authenticate("fixture", "fixture-password")
            assertArrayEquals(body.toByteArray(Charsets.ISO_8859_1), protocol.body("1@fixture"))
        }
        assertEquals("AUTHINFO USER fixture\r\nAUTHINFO PASS fixture-password\r\nCAPABILITIES\r\nBODY <1@fixture>\r\n", output.toString("UTF-8"))
    }

    @Test fun `NNTP article identity refusal invalid line endings and dot termination are enforced`() {
        val inputs = listOf("222 0 <other@fixture> body\r\n", "430 unavailable\r\n", "222 0 <1@fixture> body\n")
        inputs.forEach { response -> reject<IOException> {
            CameoNntpProtocol(ByteArrayInputStream(response.toByteArray()), ByteArrayOutputStream()).body("1@fixture")
        } }
        val result = CameoNntpProtocol(ByteArrayInputStream("222 0 <1@fixture> body\r\n..leading\r\n.\r\n".toByteArray()), ByteArrayOutputStream()).body("1@fixture")
        assertEquals(".leading\r\n", String(result))
    }

    @Test fun `NNTP invalid credentials or IDs cannot inject a command`() {
        val output = ByteArrayOutputStream()
        val protocol = CameoNntpProtocol(ByteArrayInputStream(byteArrayOf()), output)
        reject<IOException> { protocol.authenticate("fixture\nBODY", "fixture-password") }
        reject<IOException> { protocol.body("one@fixture\r\nAUTHINFO PASS secret") }
        assertEquals(0, output.size())
    }
}
