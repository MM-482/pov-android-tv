package dev.pov.tv.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class NzbSegment(val number: Int, val bytes: Long, val messageId: String)
data class NzbFile(val name: String, val subject: String, val estimatedBytes: Long, val segments: List<NzbSegment>)

/** Explicit NZB file selection and encrypted NNTP downloads, matching desktop Cameo's path. */
object EasynewsNzb {
    private const val MAX_NZB = 8 * 1024 * 1024
    internal const val MAX_ARTICLE = 16 * 1024 * 1024
    internal const val MAX_FILE = 64L * 1024 * 1024 * 1024
    private const val MAX_SEGMENTS = 100_000

    /** Creates descriptors only. The caller owns/closes input; parsing never contacts a server. */
    fun parse(input: InputStream): List<NzbFile> {
        val document = readBounded(input, MAX_NZB)
        if (document.isEmpty()) throw IOException("Choose a nonempty NZB smaller than 8 MB.")
        try {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            // KXmlParser does not implement the optional DOCDECL setting; unsupported means off.
            // Refuse enabled expansion/validation, and reject DOCDECL/custom-entity tokens below.
            listOf(XmlPullParser.FEATURE_PROCESS_DOCDECL, XmlPullParser.FEATURE_VALIDATION).forEach { feature ->
                try { parser.setFeature(feature, false) } catch (_: XmlPullParserException) { }
                if (parser.getFeature(feature)) throw IOException("The XML parser cannot disable external definitions.")
            }
            parser.setInput(ByteArrayInputStream(document), null)
            val files = mutableListOf<NzbFile>()
            val identities = mutableSetOf<String>()
            var rootSeen = false
            var fileOpen = false
            var segmentsOpen = false
            var subject = ""
            var current = mutableListOf<NzbSegment>()
            var message: StringBuilder? = null
            var number = 0
            var bytes = 0L
            var totalSegments = 0
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> throw IOException("NZB external definitions and document types are unsupported.")
                    XmlPullParser.START_TAG -> {
                        if (parser.depth > 16) throw IOException("This NZB exceeds the supported nesting limit.")
                        if (message != null) throw IOException("An NZB article identity contains nested XML.")
                        if (parser.depth == 1) {
                            if (rootSeen || parser.name != "nzb") throw IOException("This is not an NZB document.")
                            rootSeen = true
                        } else if (parser.depth == 2 && parser.name == "file") {
                            if (files.size >= 1000) throw IOException("This NZB contains too many files.")
                            fileOpen = true
                            subject = parser.getAttributeValue(null, "subject").orEmpty().take(2000)
                            current = mutableListOf()
                        } else if (fileOpen && parser.depth == 3 && parser.name == "segments") segmentsOpen = true
                        else if (fileOpen && segmentsOpen && parser.depth == 4 && parser.name == "segment") {
                            number = unsigned(parser.getAttributeValue(null, "number"))?.takeIf { it in 1..MAX_SEGMENTS }?.toInt()
                                ?: throw IOException("This NZB contains an invalid segment number.")
                            bytes = unsigned(parser.getAttributeValue(null, "bytes"))?.takeIf { it in 1..MAX_ARTICLE.toLong() }
                                ?: throw IOException("This NZB contains an invalid article size.")
                            if (++totalSegments > MAX_SEGMENTS) throw IOException("This NZB contains too many segments.")
                            message = StringBuilder()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.depth == 4 && parser.name == "segment" && message != null) {
                            var id = message.toString().trim()
                            if (id.startsWith('<') && id.endsWith('>')) id = id.substring(1, id.length - 1)
                            if (!validId(id)) throw IOException("This NZB contains an invalid article identity.")
                            current += NzbSegment(number, bytes, id)
                            message = null
                        } else if (parser.depth == 3 && parser.name == "segments") segmentsOpen = false
                        else if (parser.depth == 2 && parser.name == "file" && fileOpen) {
                            val segments = current.sortedBy { it.number }
                            val file = NzbFile(suggestedName(subject), subject, segments.sumOf { it.bytes }, segments)
                            validateFile(file)
                            val identity = MessageDigest.getInstance("SHA-256").digest(segments.joinToString("\n") { it.messageId }.toByteArray())
                                .joinToString("") { "%02x".format(it.toInt() and 255) }
                            if (!identities.add(identity)) throw IOException("The NZB repeats the same file.")
                            files += file
                            fileOpen = false
                            segmentsOpen = false
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE -> message?.append(parser.text)
                    XmlPullParser.ENTITY_REF -> {
                        if (parser.name !in setOf("amp", "lt", "gt", "apos", "quot") && !parser.name.startsWith('#'))
                            throw IOException("NZB custom entities are unsupported.")
                        val value = parser.text ?: throw IOException("NZB entity could not be resolved.")
                        message?.append(value)
                    }
                }
                if ((message?.length ?: 0) > 1100) throw IOException("This NZB contains an oversized article identity.")
            }
            if (!rootSeen || fileOpen || message != null || files.isEmpty()) throw IOException("This NZB has no complete downloadable files.")
            return files.toList()
        } catch (_: XmlPullParserException) {
            throw IOException("This NZB is invalid or contains unsupported external definitions.")
        }
    }

    fun validate(username: String, password: String, cancelled: () -> Boolean = { false }): String {
        checkCredentials(username, password)
        EasynewsNntpSession(username, password, cancelled).use { }
        return "Easynews NNTP connected over TLS"
    }

    /** Writes one user-selected file. Never replaces an existing destination, including a symlink. */
    fun download(file: NzbFile, username: String, password: String, destination: File,
        cancelled: () -> Boolean = { false }, progress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        checkCredentials(username, password)
        downloadWithSource(file, destination, cancelled, progress) { EasynewsNntpSession(username, password, cancelled) }
    }

    internal fun downloadWithSource(file: NzbFile, destination: File, cancelled: () -> Boolean = { false },
        progress: (done: Long, total: Long) -> Unit = { _, _ -> }, source: () -> NzbArticleSource) {
        validateFile(file)
        if (!destination.isAbsolute || Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw IOException("Choose a new local filename for the download.")
        val parent = destination.parentFile?.takeIf { it.isDirectory } ?: throw IOException("The download folder is unavailable.")
        checkCancelled(cancelled)
        val partial = Files.createTempFile(parent.toPath(), ".cameo-nzb-", ".part")
        try {
            var expectedSize: Long? = null
            var expectedName: String? = null
            var offset = 0L
            var fullCrc: Long? = null
            val checksum = CRC32()
            source().use { session ->
                Files.newOutputStream(partial, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).buffered(65536).use { output ->
                    file.segments.forEach { segment ->
                        checkCancelled(cancelled)
                        val part = NzbYEnc.decode(session.body(segment.messageId))
                        if (part.part != segment.number || part.total != file.segments.size || part.begin != offset ||
                            (expectedSize != null && expectedSize != part.size) || (expectedName != null && expectedName != part.name))
                            throw IOException("The articles do not form the same complete file. No finished file was saved.")
                        if (expectedSize == null) {
                            expectedSize = part.size
                            expectedName = part.name
                            val usable = parent.usableSpace
                            if (usable > 0 && usable < part.size) throw IOException("There is not enough free space for this download.")
                        }
                        part.fileCrc?.let { crc ->
                            if (fullCrc != null && fullCrc != crc) throw IOException("The articles disagree about the complete file checksum.")
                            fullCrc = crc
                        }
                        checkCancelled(cancelled)
                        output.write(part.data)
                        checksum.update(part.data)
                        offset = part.end
                        runCatching { progress(offset.coerceAtMost(part.size - 1), part.size) }
                    }
                    if (offset != expectedSize || (fullCrc != null && fullCrc != checksum.value))
                        throw IOException("The complete file failed its size or checksum check. No finished file was saved.")
                    output.flush()
                }
            }
            checkCancelled(cancelled)
            publishWithoutReplacing(partial.toFile(), destination, cancelled)
            runCatching { progress(offset, offset) }
        } finally {
            runCatching { Files.deleteIfExists(partial) }
        }
    }

    private fun publishWithoutReplacing(partial: File, destination: File, cancelled: () -> Boolean) {
        try {
            Files.createLink(destination.toPath(), partial.toPath())
            return
        } catch (_: FileAlreadyExistsException) {
            throw IOException("A file already exists at the selected destination.")
        } catch (_: UnsupportedOperationException) {
            // Removable filesystems may not support atomic hard-link publication.
        } catch (_: IOException) {
            if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS))
                throw IOException("A file already exists at the selected destination.")
        }
        var created = false
        try {
            Files.newOutputStream(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                created = true
                partial.inputStream().buffered().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancelled(cancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
            }
        } catch (error: Exception) {
            if (created) Files.deleteIfExists(destination.toPath())
            throw error
        }
    }

    private fun validateFile(file: NzbFile) {
        val segments = file.segments
        if (segments.isEmpty() || segments.size > MAX_SEGMENTS || segments.withIndex().any { (index, segment) ->
                segment.number != index + 1 || segment.bytes !in 1..MAX_ARTICLE.toLong() || !validId(segment.messageId)
            } || segments.map { it.messageId }.distinct().size != segments.size)
            throw IOException("The NZB file is incomplete or its parts are duplicated.")
        val estimated = segments.sumOf { it.bytes }
        if (estimated > MAX_FILE || file.estimatedBytes != estimated)
            throw IOException("The NZB file exceeds the supported size or has inconsistent metadata.")
    }
    internal fun validId(id: String) = id.isNotEmpty() && id.length <= 998 && '@' in id && id.all { it.code in 33..126 && it != '<' && it != '>' }
    internal fun suggestedName(subject: String): String {
        val quoted = Regex("\"([^\"]+)\"").find(subject)?.groupValues?.get(1) ?: subject
        var name = quoted.map { if (it.code < 32 || it.code == 127 || it in "<>:\"/\\|?*") '_' else it }.joinToString("")
            .trim().trim('.').take(220).trimEnd('.', ' ')
        if (name.isBlank()) name = "Usenet download"
        val stem = name.substringBefore('.').uppercase(Locale.ROOT)
        if (stem in setOf("CON", "PRN", "AUX", "NUL") || stem.matches(Regex("(?:COM|LPT)[1-9]"))) name = "_" + name
        return name
    }
    private fun unsigned(value: String?): Long? = value?.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size().toLong() + count > maximum) throw IOException("Choose an NZB smaller than 8 MB.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    internal fun checkCredentials(username: String, password: String) {
        if (username.isEmpty() || password.isEmpty() || username.toByteArray().size > 200 || password.toByteArray().size > 400 ||
            username.any { it.code < 33 || it.code == 127 } || password.any { it.code < 32 || it.code == 127 })
            throw IOException("Enter your Easynews username and password for encrypted NNTP access.")
    }
    internal fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Easynews download cancelled.")
    }
}

internal interface NzbArticleSource : Closeable { fun body(messageId: String): ByteArray }

/** Credentials go only to this fixed endpoint, with default trust and explicit TLS hostname verification. */
private class EasynewsNntpSession(username: String, password: String, private val cancelled: () -> Boolean) : NzbArticleSource {
    private var raw: Socket? = null
    private var socket: SSLSocket? = null
    private lateinit var input: BufferedInputStream
    init {
        try {
            EasynewsNzb.checkCancelled(cancelled)
            val connected = Socket().also { raw = it; it.connect(InetSocketAddress(HOST, 563), 30_000); it.soTimeout = 30_000 }
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(connected, HOST, 563, true) as SSLSocket
            socket = tls
            tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            tls.sslParameters = tls.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(HOST))
            }
            tls.startHandshake()
            tls.soTimeout = 1000
            input = BufferedInputStream(tls.inputStream, 16384)
            val welcome = status()
            if (!welcome.startsWith("200 ") && !welcome.startsWith("201 ")) throw IOException("Easynews NNTP is unavailable.")
            command("AUTHINFO USER " + username)
            var response = status()
            if (response.startsWith("381 ")) { command("AUTHINFO PASS " + password); response = status() }
            if (!response.startsWith("281 ")) throw IOException("Easynews did not accept this username and password.")
            command("CAPABILITIES")
            if (!status().startsWith("101 ")) throw IOException("Easynews NNTP capabilities could not be verified.")
            val capabilities = multiline(65536, 256).toString(Charsets.US_ASCII).split("\r\n")
            if (capabilities.none { it == "READER" || it.startsWith("VERSION ") })
                throw IOException("Easynews returned an unsupported NNTP capability list.")
        } catch (error: Exception) {
            close()
            EasynewsNzb.checkCancelled(cancelled)
            if (error is IOException && error.message?.startsWith("Easynews") == true) throw error
            throw IOException("The encrypted Easynews connection failed. Check your account and network connection.")
        }
    }
    override fun body(messageId: String): ByteArray {
        if (!EasynewsNzb.validId(messageId)) throw IOException("Invalid NZB article identity.")
        command("BODY <" + messageId + ">")
        val response = status()
        if (response.startsWith("430 ")) throw IOException("An article is unavailable on Easynews. No finished file was saved.")
        if (!response.startsWith("222 ") || ("<" + messageId + ">") !in response.split(' '))
            throw IOException("Easynews did not return the requested article.")
        return multiline(EasynewsNzb.MAX_ARTICLE, 200_000)
    }
    private fun command(value: String) {
        EasynewsNzb.checkCancelled(cancelled)
        if ('\r' in value || '\n' in value || value.toByteArray().size > 1022) throw IOException("Invalid NNTP request.")
        val output = socket?.outputStream ?: throw IOException("Easynews connection is closed.")
        output.write((value + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }
    private fun status(): String {
        val text = line(2048).toString(Charsets.US_ASCII)
        val code = text.take(3).toIntOrNull()
        if (text.length < 3 || code == null || code !in 100..599 || (text.length > 3 && text[3] != ' '))
            throw IOException("Easynews returned an invalid NNTP response.")
        return text
    }
    private fun line(maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val deadline = System.nanoTime() + 30_000_000_000L
        while (true) {
            EasynewsNzb.checkCancelled(cancelled)
            if (System.nanoTime() > deadline) throw IOException("Easynews connection timed out.")
            val value = try { input.read() } catch (_: SocketTimeoutException) { continue }
            if (value < 0) throw IOException("Easynews closed the connection before the article was complete.")
            if (value == 10) {
                val bytes = output.toByteArray()
                if (bytes.isEmpty() || bytes.last() != 13.toByte()) throw IOException("Easynews returned invalid line endings.")
                return bytes.copyOf(bytes.size - 1)
            }
            output.write(value)
            if (output.size() > maximum + 1) throw IOException("The NNTP response exceeded its size limit.")
        }
    }
    private fun multiline(maximumBytes: Int, maximumLines: Int): ByteArray {
        val output = ByteArrayOutputStream()
        repeat(maximumLines) {
            val bytes = line(16384)
            if (bytes.size == 1 && bytes[0] == 46.toByte()) return output.toByteArray()
            val skip = if (bytes.size >= 2 && bytes[0] == 46.toByte() && bytes[1] == 46.toByte()) 1 else 0
            if (bytes.isNotEmpty() && bytes[0] == 46.toByte() && skip == 0) throw IOException("Easynews returned invalid article escaping.")
            if (output.size().toLong() + bytes.size - skip + 2 > maximumBytes) throw IOException("The NNTP article exceeded its size limit.")
            output.write(bytes, skip, bytes.size - skip)
            output.write(13); output.write(10)
        }
        throw IOException("The NNTP article exceeded its line limit.")
    }
    override fun close() { runCatching { socket?.close() }; runCatching { raw?.close() } }
    companion object { private const val HOST = "news.easynews.com" }
}

internal data class NzbYEncPart(val data: ByteArray, val name: String, val size: Long, val begin: Long,
    val end: Long, val part: Int, val total: Int, val fileCrc: Long?)

/** Bounded yEnc decoding with required part CRCs and complete-file CRC when provided. */
internal object NzbYEnc {
    fun decode(body: ByteArray): NzbYEncPart {
        if (body.size > EasynewsNzb.MAX_ARTICLE) throw invalid()
        var header: Map<String, String>? = null
        var partHeader: Map<String, String>? = null
        var trailer: Map<String, String>? = null
        var cursor = 0
        var preamble = 0
        val output = ByteArrayOutputStream()
        while (cursor < body.size) {
            var end = cursor
            while (end < body.size && body[end] != 10.toByte()) end++
            var length = end - cursor
            if (length > 0 && body[cursor + length - 1] == 13.toByte()) length--
            val prefix = String(body, cursor, length.coerceAtMost(8), Charsets.US_ASCII)
            when {
                prefix.startsWith("=ybegin ") -> {
                    if (header != null) throw invalid()
                    header = fields(body, cursor, length, "=ybegin ", true)
                }
                header == null -> { preamble += length; if (preamble > 8192) throw invalid() }
                prefix.startsWith("=ypart ") -> {
                    if (partHeader != null || output.size() > 0 || trailer != null) throw invalid()
                    partHeader = fields(body, cursor, length, "=ypart ", false)
                }
                prefix.startsWith("=yend ") -> {
                    if (trailer != null) throw invalid()
                    trailer = fields(body, cursor, length, "=yend ", false)
                }
                trailer == null -> {
                    var escaped = false
                    for (index in cursor until cursor + length) {
                        val value = body[index].toInt() and 255
                        if (escaped) { output.write((value - 64 - 42) and 255); escaped = false }
                        else if (value == 61) escaped = true else output.write((value - 42) and 255)
                    }
                    if (escaped || output.size() > EasynewsNzb.MAX_ARTICLE) throw invalid()
                }
                length > 0 -> throw IOException("Articles containing multiple encoded files are unsupported.")
            }
            cursor = end + 1
        }
        val beginHeader = header ?: throw invalid()
        val endHeader = trailer ?: throw invalid()
        val name = beginHeader["name"].orEmpty()
        val size = number(beginHeader, "size")
        val line = number(beginHeader, "line")
        if (name.isEmpty() || name.length > 1024 || name.any { it.code < 32 } || size !in 1..EasynewsNzb.MAX_FILE ||
            line !in 1..4096 || number(endHeader, "size") != output.size().toLong()) throw invalid()
        val part: Int; val total: Int; val begin: Long; val end: Long; val expectedCrc: Long
        if ("part" in beginHeader || "total" in beginHeader) {
            val partNumber = number(beginHeader, "part")
            val totalParts = number(beginHeader, "total")
            val range = partHeader ?: throw invalid()
            if (partNumber < 1 || totalParts < partNumber || totalParts > 100_000) throw invalid()
            part = partNumber.toInt(); total = totalParts.toInt()
            begin = number(range, "begin") - 1; end = number(range, "end")
            if (begin < 0 || end <= begin || end > size || end - begin != output.size().toLong() ||
                ("part" in endHeader && number(endHeader, "part") != partNumber)) throw invalid()
            expectedCrc = crc(endHeader["pcrc32"].orEmpty())
        } else {
            if (partHeader != null || output.size().toLong() != size) throw invalid()
            part = 1; total = 1; begin = 0; end = size; expectedCrc = crc(endHeader["crc32"].orEmpty())
        }
        val data = output.toByteArray()
        if (CRC32().apply { update(data) }.value != expectedCrc)
            throw IOException("An article failed its yEnc checksum. No finished file was saved.")
        return NzbYEncPart(data, name, size, begin, end, part, total, endHeader["crc32"]?.let(::crc))
    }
    private fun fields(body: ByteArray, offset: Int, count: Int, prefix: String, filename: Boolean): Map<String, String> {
        var text = String(body, offset, count, Charsets.ISO_8859_1).substring(prefix.length)
        val values = linkedMapOf<String, String>()
        if (filename) {
            val index = text.indexOf(" name=")
            if (index < 0) throw invalid()
            values["name"] = text.substring(index + 6)
            text = text.substring(0, index)
        }
        text.split(' ').filter(String::isNotEmpty).forEach { entry ->
            val equals = entry.indexOf('=')
            if (equals <= 0) throw invalid()
            val key = entry.substring(0, equals)
            if (key in values) throw invalid()
            values[key] = entry.substring(equals + 1)
        }
        return values
    }
    private fun number(values: Map<String, String>, key: String): Long =
        values[key]?.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull() ?: -1
    private fun crc(value: String): Long = value.takeIf { it.matches(Regex("[0-9a-fA-F]{8}")) }?.toLongOrNull(16) ?: throw invalid()
    private fun invalid() = IOException("The article contains invalid yEnc data, sizes, offsets or checksums.")
}
