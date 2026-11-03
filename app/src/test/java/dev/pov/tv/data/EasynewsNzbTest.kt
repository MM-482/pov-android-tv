package dev.pov.tv.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EasynewsNzbTest {
    @Test fun `parsing a namespaced NZB sorts explicit segments and only creates descriptors`() {
        val parsed = parse("""<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb"><file subject='Post "video.mkv" yEnc'><segments>
            <segment bytes="10" number="2">b@example.test</segment>
            <segment bytes="20" number="1">&lt;a@example.test&gt;</segment>
            </segments></file></nzb>""")
        assertEquals(1, parsed.size)
        assertEquals("video.mkv", parsed.single().name)
        assertEquals(30L, parsed.single().estimatedBytes)
        assertEquals(listOf("a@example.test", "b@example.test"), parsed.single().segments.map { it.messageId })
    }

    @Test fun `external definitions and custom entities are rejected before use`() {
        assertThrows(IOException::class.java) {
            parse("""<!DOCTYPE nzb [<!ENTITY stolen SYSTEM "file:///not-accessed">]><nzb><file subject="x"><segments><segment number="1" bytes="4">&stolen;</segment></segments></file></nzb>""")
        }
        assertThrows(IOException::class.java) { parse("""<!DOCTYPE nzb SYSTEM "https://not-accessed.invalid/nzb.dtd"><nzb/>""") }
    }

    @Test fun `incomplete duplicate and injection-shaped article identifiers are rejected`() {
        listOf(
            "<segment number='2' bytes='4'>a@example.test</segment>",
            "<segment number='1' bytes='4'>a@example.test</segment><segment number='1' bytes='4'>b@example.test</segment>",
            "<segment number='1' bytes='4'>a@example.test</segment><segment number='2' bytes='4'>a@example.test</segment>",
            "<segment number='1' bytes='4'>a@example.test&#13;&#10;BODY something</segment>",
            "<segment number='1' bytes='16777217'>a@example.test</segment>",
        ).forEach { content -> assertThrows(IOException::class.java) { parse(nzb(content)) } }
        val file = "<file subject='x'><segments><segment number='1' bytes='4'>a@example.test</segment></segments></file>"
        assertThrows(IOException::class.java) { parse("<nzb>$file$file</nzb>") }
    }

    @Test fun `deep XML and oversized documents are bounded`() {
        assertThrows(IOException::class.java) { parse("<nzb>" + "<x>".repeat(18) + "</x>".repeat(18) + "</nzb>") }
        assertThrows(IOException::class.java) { EasynewsNzb.parse(ByteArrayInputStream(ByteArray(8 * 1024 * 1024 + 1))) }
    }

    @Test fun `suggested filenames cannot introduce paths or reserved device names`() {
        assertEquals("_CON.txt", EasynewsNzb.suggestedName("\"CON.txt\""))
        val name = EasynewsNzb.suggestedName("Post \"../../movies\\bad:video.mkv\" yEnc")
        assertFalse('/' in name)
        assertFalse('\\' in name)
        assertFalse(':' in name)
        assertFalse(name.startsWith('.'))
    }

    @Test fun `single-part yEnc restores binary escapes and checks complete CRC`() {
        val data = ByteArray(256) { it.toByte() }
        val part = NzbYEnc.decode(encoded(data))
        assertArrayEquals(data, part.data)
        assertEquals(0L, part.begin)
        assertEquals(256L, part.end)
        assertEquals(1, part.total)
        val bad = encoded(data).toString(Charsets.ISO_8859_1).replace(Regex("crc32=[0-9a-f]{8}"), "crc32=00000000")
        assertThrows(IOException::class.java) { NzbYEnc.decode(bad.toByteArray(Charsets.ISO_8859_1)) }
    }

    @Test fun `two verified parts publish one complete file and report completion last`() = withFolder { folder ->
        val data = "A complete synthetic binary file".toByteArray()
        val first = data.copyOfRange(0, 10)
        val second = data.copyOfRange(10, data.size)
        val descriptor = descriptor(2)
        val requested = mutableListOf<String>()
        val progress = mutableListOf<Pair<Long, Long>>()
        val destination = File(folder, "download.bin")
        EasynewsNzb.downloadWithSource(descriptor, destination, progress = { done, total -> progress += done to total }) {
            source(mapOf("part1@example.test" to encoded(first, 1, 2, 0, data.size, null),
                "part2@example.test" to encoded(second, 2, 2, 10, data.size, crc(data))), requested)
        }
        assertArrayEquals(data, destination.readBytes())
        assertEquals(listOf("part1@example.test", "part2@example.test"), requested)
        assertEquals(data.size.toLong() to data.size.toLong(), progress.last())
        assertTrue(progress.dropLast(1).all { it.first < it.second })
        assertEquals(listOf("download.bin"), folder.listFiles()!!.map { it.name })
    }

    @Test fun `wrong offsets or full checksums never publish a finished file`() = withFolder { folder ->
        val data = "abcdef".toByteArray()
        val one = encoded(data.copyOfRange(0, 3), 1, 2, 0, 6, null)
        val offsetWrong = encoded(data.copyOfRange(3, 6), 2, 2, 2, 6, null)
        val crcWrong = encoded(data.copyOfRange(3, 6), 2, 2, 3, 6, "00000000")
        listOf(offsetWrong, crcWrong).forEach { second ->
            val destination = File(folder, "invalid.bin")
            assertThrows(IOException::class.java) {
                EasynewsNzb.downloadWithSource(descriptor(2), destination) {
                    source(mapOf("part1@example.test" to one, "part2@example.test" to second))
                }
            }
            assertFalse(destination.exists())
            assertTrue(folder.listFiles()!!.isEmpty())
        }
    }

    @Test fun `existing output remains untouched and no session is opened`() = withFolder { folder ->
        val existing = File(folder, "existing.bin").apply { writeText("keep") }
        var opened = false
        assertThrows(IOException::class.java) {
            EasynewsNzb.downloadWithSource(descriptor(1), existing) { opened = true; source(emptyMap()) }
        }
        assertFalse(opened)
        assertEquals("keep", existing.readText())
    }

    @Test fun `cancellation after article retrieval leaves no complete or partial output`() = withFolder { folder ->
        var cancelled = false
        val destination = File(folder, "cancelled.bin")
        assertThrows(CancellationException::class.java) {
            EasynewsNzb.downloadWithSource(descriptor(1), destination, cancelled = { cancelled }) {
                object : NzbArticleSource {
                    override fun body(messageId: String): ByteArray { cancelled = true; return encoded("test".toByteArray()) }
                    override fun close() = Unit
                }
            }
        }
        assertFalse(destination.exists())
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun `credentials with NNTP command injection are refused locally`() {
        assertThrows(IOException::class.java) { EasynewsNzb.checkCredentials("name\r\nBODY other", "password") }
        assertThrows(IOException::class.java) { EasynewsNzb.checkCredentials("name", "password\nQUIT") }
        EasynewsNzb.checkCredentials("fixture-user", "fixture password")
    }

    private fun parse(xml: String) = EasynewsNzb.parse(ByteArrayInputStream(xml.toByteArray()))
    private fun nzb(segments: String) = "<nzb><file subject='fixture.bin'><segments>$segments</segments></file></nzb>"
    private fun descriptor(count: Int): NzbFile = NzbFile("fixture.bin", "fixture", 10L * count,
        (1..count).map { NzbSegment(it, 10, "part$it@example.test") })
    private fun source(bodies: Map<String, ByteArray>, requested: MutableList<String> = mutableListOf()) = object : NzbArticleSource {
        override fun body(messageId: String): ByteArray { requested += messageId; return bodies[messageId] ?: throw IOException("Missing fixture") }
        override fun close() = Unit
    }
    private fun crc(data: ByteArray) = "%08x".format(CRC32().apply { update(data) }.value)
    private fun encoded(data: ByteArray, part: Int = 1, total: Int = 1, begin: Int = 0,
        size: Int = data.size, fullCrc: String? = if (total == 1) crc(data) else null): ByteArray {
        val output = ByteArrayOutputStream()
        fun line(value: String) { output.write((value + "\r\n").toByteArray(Charsets.ISO_8859_1)) }
        line(if (total == 1) "=ybegin line=128 size=$size name=fixture.bin" else "=ybegin part=$part total=$total line=128 size=$size name=fixture.bin")
        if (total > 1) line("=ypart begin=${begin + 1} end=${begin + data.size}")
        var width = 0
        data.forEach { byte ->
            val encoded = ((byte.toInt() and 255) + 42) and 255
            val escape = encoded in setOf(0, 10, 13, 61)
            if (width + (if (escape) 2 else 1) > 128) { line(""); width = 0 }
            if (escape) { output.write(61); output.write((encoded + 64) and 255); width += 2 }
            else { output.write(encoded); width++ }
        }
        line("")
        line(if (total == 1) "=yend size=${data.size} crc32=${fullCrc ?: crc(data)}"
            else "=yend size=${data.size} part=$part pcrc32=${crc(data)}${fullCrc?.let { " crc32=$it" }.orEmpty()}")
        return output.toByteArray()
    }
    private fun withFolder(block: (File) -> Unit) {
        val folder = Files.createTempDirectory("cameo-nzb-test-").toFile()
        try { block(folder) } finally { folder.deleteRecursively() }
    }
}
