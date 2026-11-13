package dev.pov.tv

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class NzbSegment(val number: Int, val bytes: Long, val messageId: String)
internal data class NzbFile(val id: String, val name: String, val subject: String, val groups: List<String>,
    val segments: List<NzbSegment>, val estimatedBytes: Long)

/** Explicit owner-selected NZB download. This is not an indexer, archive extractor or PAR2 repairer. */
internal object CameoNzb {
    const val MAX_NZB_BYTES = 8 * 1024 * 1024
    const val MAX_ARTICLE_BYTES = 16 * 1024 * 1024
    const val MAX_FILE_BYTES = 64L * 1024 * 1024 * 1024

    fun parse(context: Context, uri: Uri): List<NzbFile> {
        require(uri.scheme == "content") { "Choose an NZB document using the Android file picker." }
        return context.contentResolver.openInputStream(uri)?.use(::parse)
            ?: throw IOException("The selected document provider could not open this NZB.")
    }

    internal fun parse(input: InputStream): List<NzbFile> {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (output.size() <= MAX_NZB_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_NZB_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
        if (output.size() !in 1..MAX_NZB_BYTES) throw IOException("Choose an NZB file smaller than 8 MB.")
        val files = mutableListOf<NzbFile>(); val identities = mutableSetOf<String>()
        var segmentCount = 0
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            // nextToken exposes any DOCDECL for explicit rejection; no document declaration is processed.
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(ByteArrayInputStream(output.toByteArray()), null)
            val path = mutableListOf<String>(); var rootSeen = false
            var subject = ""; var groups = mutableListOf<String>(); var segments = mutableListOf<NzbSegment>()
            var text = StringBuilder(); var number = 0; var size = 0L
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> throw IOException("NZB documents with external definitions are unsupported.")
                    XmlPullParser.START_TAG -> {
                        path += parser.name
                        if (path.size > 16) throw IOException("This NZB exceeds the supported nesting limit.")
                        if (path.size == 1) {
                            if (rootSeen || parser.name != "nzb") throw IOException("This is not an NZB document.")
                            rootSeen = true
                        }
                        if (path == listOf("nzb", "file")) {
                            if (files.size >= 1000) throw IOException("This NZB contains too many files.")
                            subject = parser.getAttributeValue(null, "subject").orEmpty().take(2000)
                            groups = mutableListOf(); segments = mutableListOf()
                        }
                        if (path == listOf("nzb", "file", "groups", "group")) text = StringBuilder()
                        if (path == listOf("nzb", "file", "segments", "segment")) {
                            number = parser.getAttributeValue(null, "number")?.toIntOrNull() ?: 0
                            size = parser.getAttributeValue(null, "bytes")?.toLongOrNull() ?: 0
                            text = StringBuilder()
                            if (number <= 0 || size !in 1..MAX_ARTICLE_BYTES.toLong() || ++segmentCount > 100_000) {
                                throw IOException("This NZB contains invalid or excessive segments.")
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                        if (path == listOf("nzb", "file", "groups", "group") || path == listOf("nzb", "file", "segments", "segment")) {
                            val value = parser.text ?: throw IOException("This NZB contains an unsupported XML entity.")
                            text.append(value)
                            if (text.length > 1000) throw IOException("This NZB contains an excessive article identity.")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (path == listOf("nzb", "file", "groups", "group")) {
                            val group = text.toString().trim()
                            if (group.isNotEmpty()) groups += group
                            if (groups.size > 64 || group.any { it.code < 33 || it.code > 126 }) throw IOException("This NZB contains invalid newsgroups.")
                        }
                        if (path == listOf("nzb", "file", "segments", "segment")) {
                            val id = text.toString().trim().removeSurrounding("<", ">")
                            if (!validMessageId(id)) throw IOException("This NZB contains an invalid article identity.")
                            segments += NzbSegment(number, size, id)
                        }
                        if (path == listOf("nzb", "file")) {
                            val ordered = segments.sortedBy { it.number }
                            val bytes = ordered.sumOf { it.bytes }
                            val id = MessageDigest.getInstance("SHA-256").digest(ordered.joinToString("\n") { it.messageId }.toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
                            val file = NzbFile(id, suggestedName(subject), subject, groups.distinct(), ordered, bytes)
                            validateFile(file)
                            if (!identities.add(id)) throw IOException("This NZB repeats the same file.")
                            files += file
                        }
                        if (path.isEmpty() || path.last() != parser.name) throw IOException("This NZB has an invalid structure.")
                        path.removeAt(path.lastIndex)
                    }
                }
            }
            if (!rootSeen || files.isEmpty()) throw IOException("This NZB has no downloadable files.")
            return files
        } catch (error: IOException) { throw error }
        catch (_: Exception) { throw IOException("This NZB is invalid or contains unsupported external definitions.") }
    }

    /** Validate NNTP independently of the existing Easynews web account path. Call on a worker. */
    fun validate(username: String, password: String, cancel: () -> Boolean = { false }) {
        credentials(username, password); cancelled(cancel)
        openArticles(cancel).use { it.authenticate(username, password) }
    }

    /** Only an explicitly chosen new, empty SAF document is written, after the complete file verifies. */
    fun download(context: Context, file: NzbFile, username: String, password: String, destination: Uri,
        cancel: () -> Boolean = { false }, progress: (written: Long, expected: Long) -> Unit = { _, _ -> }) {
        credentials(username, password); validateFile(file); cancelled(cancel)
        require(destination.scheme == "content" && DocumentsContract.isDocumentUri(context, destination)) {
            "Choose a new output document using the Android save picker."
        }
        requireEmptyDocument(context, destination)
        val partial = File.createTempFile("cameo-nzb-", ".part", context.cacheDir)
        var destinationOpened = false
        try {
            val freeSpace = StatFs(context.cacheDir.absolutePath).availableBytes
            partial.outputStream().buffered().use { output ->
                downloadTo(file, username, password, output, { openArticles(cancel) }, cancel) { written, expected ->
                    if (expected > freeSpace - 8 * 1024 * 1024) throw IOException("There is not enough temporary storage to verify this download.")
                    // Reserve 100% for a verified file successfully copied to the chosen document.
                    progress(written.coerceAtMost((expected - 1).coerceAtLeast(0)), expected)
                }
            }
            cancelled(cancel)
            requireEmptyDocument(context, destination)
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                destinationOpened = true
                partial.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        cancelled(cancel)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
            } ?: throw IOException("The selected provider could not create this download.")
            cancelled(cancel)
            progress(partial.length(), partial.length())
        } catch (error: Exception) {
            if (destinationOpened) runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
            if (error is CancellationException || error is IOException || error is IllegalArgumentException) throw error
            throw IOException("The Easynews download was interrupted. No verified completed document was saved.")
        } finally { partial.delete() }
    }

    internal fun downloadTo(file: NzbFile, username: String, password: String, output: OutputStream,
        articles: () -> NzbArticles, cancel: () -> Boolean = { false }, progress: (Long, Long) -> Unit = { _, _ -> }): Long {
        credentials(username, password); validateFile(file); cancelled(cancel)
        var expectedSize: Long? = null; var expectedName: String? = null; var expectedCrc: Long? = null
        var offset = 0L; val checksum = CRC32()
        articles().use { session ->
            session.authenticate(username, password)
            for (segment in file.segments) {
                cancelled(cancel)
                val part = decodeYenc(session.body(segment.messageId), cancel)
                if (part.part != segment.number || part.total != file.segments.size || part.begin != offset ||
                    (expectedSize != null && expectedSize != part.size) || (expectedName != null && expectedName != part.name)) {
                    throw IOException("The articles do not form the same complete file. No finished file was saved.")
                }
                if (expectedSize == null) { expectedSize = part.size; expectedName = part.name; progress(0, part.size) }
                part.fileCrc?.let { crc ->
                    if (expectedCrc != null && expectedCrc != crc) throw IOException("The articles disagree about the file checksum.")
                    expectedCrc = crc
                }
                cancelled(cancel)
                output.write(part.data); checksum.update(part.data); offset = part.end
                progress(offset, part.size)
            }
        }
        cancelled(cancel)
        if (offset != expectedSize || (expectedCrc != null && expectedCrc != checksum.value)) {
            throw IOException("The complete download failed its size or checksum check. No finished file was saved.")
        }
        output.flush()
        return offset
    }

    private fun requireEmptyDocument(context: Context, uri: Uri) {
        val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        if (size != 0L) throw IOException("Choose a new, empty document. Existing or unverified files are not overwritten.")
    }

    internal fun validMessageId(value: String) = value.isNotEmpty() && value.length <= 998 && '@' in value &&
        value.all { it.code in 33..126 && it != '<' && it != '>' }

    private fun validateFile(file: NzbFile) {
        if (file.segments.isEmpty() || file.segments.size > 100_000 || file.estimatedBytes !in 1..MAX_FILE_BYTES ||
            file.segments.withIndex().any { (index, segment) -> segment.number != index + 1 ||
                segment.bytes !in 1..MAX_ARTICLE_BYTES.toLong() || !validMessageId(segment.messageId) } ||
            file.segments.map { it.messageId }.distinct().size != file.segments.size ||
            file.segments.sumOf { it.bytes } != file.estimatedBytes) {
            throw IOException("Choose a complete NZB file without missing or duplicated parts.")
        }
    }

    private fun suggestedName(subject: String): String {
        val quoted = Regex("\"([^\"]+)\"").find(subject)?.groupValues?.get(1) ?: subject
        return quoted.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim().trim('.').take(220).ifBlank { "Usenet download" }
    }

    internal fun credentials(username: String, password: String) {
        if (username.isEmpty() || password.isEmpty() || username.toByteArray(Charsets.UTF_8).size > 200 ||
            password.toByteArray(Charsets.UTF_8).size > 400 || username.any { it.code < 33 || it.code == 127 } ||
            password.any { it.code < 32 || it.code == 127 }) throw IOException("Enter your Easynews username and password for encrypted NNTP access.")
    }

    internal fun cancelled(cancel: () -> Boolean) {
        if (cancel() || Thread.currentThread().isInterrupted) throw CancellationException("The Easynews download was cancelled.")
    }

    internal data class YencPart(val data: ByteArray, val name: String, val size: Long, val begin: Long,
        val end: Long, val part: Int, val total: Int, val fileCrc: Long?)

    internal fun decodeYenc(body: ByteArray, cancel: () -> Boolean = { false }): YencPart {
        if (body.size > MAX_ARTICLE_BYTES) throw IOException("The encoded article exceeds its size limit.")
        var header: Map<String, String>? = null; var partHeader: Map<String, String>? = null; var trailer: Map<String, String>? = null
        var cursor = 0; var preamble = 0
        val decoded = ByteArrayOutputStream()
        while (cursor < body.size) {
            cancelled(cancel)
            var end = cursor
            while (end < body.size && body[end] != 10.toByte()) end++
            var length = end - cursor
            if (length > 0 && body[cursor + length - 1] == 13.toByte()) length--
            val prefix = String(body, cursor, minOf(length, 8), Charsets.US_ASCII)
            when {
                prefix.startsWith("=ybegin ") -> { if (header != null) throw invalidYenc(); header = fields(body, cursor, length, "=ybegin ", true) }
                header == null -> { preamble += length; if (preamble > 8192) throw invalidYenc() }
                prefix.startsWith("=ypart ") -> { if (partHeader != null || decoded.size() > 0 || trailer != null) throw invalidYenc(); partHeader = fields(body, cursor, length, "=ypart ", false) }
                prefix.startsWith("=yend ") -> { if (trailer != null) throw invalidYenc(); trailer = fields(body, cursor, length, "=yend ", false) }
                trailer == null -> {
                    var escaped = false
                    for (index in cursor until cursor + length) {
                        val value = body[index].toInt() and 255
                        if (escaped) { decoded.write((value - 64 - 42) and 255); escaped = false }
                        else if (value == 61) escaped = true else decoded.write((value - 42) and 255)
                    }
                    if (escaped || decoded.size() > MAX_ARTICLE_BYTES) throw invalidYenc()
                }
                length > 0 -> throw IOException("Articles containing multiple encoded files are unsupported.")
            }
            cursor = end + 1
        }
        val start = header ?: throw invalidYenc(); val finish = trailer ?: throw invalidYenc()
        val name = start["name"].orEmpty(); val size = long(start, "size"); val line = long(start, "line")
        if (name.isEmpty() || name.length > 1024 || size !in 1..MAX_FILE_BYTES || line !in 1..4096 || long(finish, "size") != decoded.size().toLong()) throw invalidYenc()
        val part: Int; val total: Int; val begin: Long; val end: Long; val expected: Long
        if ("part" in start || "total" in start) {
            val number = long(start, "part"); val count = long(start, "total")
            val range = partHeader ?: throw invalidYenc()
            if (number < 1 || count < number || count > 100_000) throw invalidYenc()
            part = number.toInt(); total = count.toInt(); begin = long(range, "begin") - 1; end = long(range, "end")
            if (begin < 0 || end <= begin || end > size || end - begin != decoded.size().toLong() ||
                ("part" in finish && long(finish, "part") != number)) throw invalidYenc()
            expected = crc(finish["pcrc32"].orEmpty())
        } else {
            if (partHeader != null || decoded.size().toLong() != size) throw invalidYenc()
            part = 1; total = 1; begin = 0; end = size; expected = crc(finish["crc32"].orEmpty())
        }
        val data = decoded.toByteArray(); val checksum = CRC32().apply { update(data) }
        if (checksum.value != expected) throw IOException("An article failed its yEnc checksum. No finished file was saved.")
        return YencPart(data, name, size, begin, end, part, total, finish["crc32"]?.let(::crc))
    }

    private fun fields(body: ByteArray, offset: Int, length: Int, prefix: String, filename: Boolean): Map<String, String> {
        if (length > 16_384) throw invalidYenc()
        var text = String(body, offset, length, Charsets.ISO_8859_1).removePrefix(prefix)
        val result = linkedMapOf<String, String>()
        if (filename) {
            val position = text.indexOf(" name=")
            if (position < 0) throw invalidYenc()
            result["name"] = text.substring(position + 6); text = text.substring(0, position)
        }
        for (field in text.split(' ').filter { it.isNotEmpty() }) {
            val equals = field.indexOf('=')
            if (equals <= 0 || result.containsKey(field.substring(0, equals))) throw invalidYenc()
            result[field.substring(0, equals)] = field.substring(equals + 1)
        }
        return result
    }
    private fun long(fields: Map<String, String>, key: String) = fields[key]?.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull() ?: -1
    private fun crc(value: String): Long = value.takeIf { it.matches(Regex("[a-fA-F0-9]{8}")) }?.toLongOrNull(16) ?: throw invalidYenc()
    private fun invalidYenc() = IOException("The article contains invalid yEnc data, sizes, offsets or checksums.")

    private fun openArticles(cancel: () -> Boolean): NzbArticles {
        cancelled(cancel)
        val base = Socket()
        try {
            base.connect(InetSocketAddress("news.easynews.com", 563), 15_000)
            cancelled(cancel)
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(base, "news.easynews.com", 563, true) as SSLSocket
            try {
                socket.soTimeout = 30_000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.enabledProtocols = socket.enabledProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                socket.startHandshake(); cancelled(cancel); socket.soTimeout = 1000
                return CameoNntpProtocol(BufferedInputStream(socket.inputStream), BufferedOutputStream(socket.outputStream), cancel) { socket.close() }
            } catch (error: Exception) { socket.close(); throw error }
        } catch (error: Exception) {
            runCatching { base.close() }
            if (error is CancellationException) throw error
            throw IOException("The encrypted Easynews connection failed. Check your account, network and device date.")
        }
    }
}

internal interface NzbArticles : Closeable {
    fun authenticate(username: String, password: String)
    fun body(messageId: String): ByteArray
}

/** Strict bounded NNTP reader, injectable for account-free protocol tests. */
internal class CameoNntpProtocol(private val input: InputStream, private val output: OutputStream,
    private val cancel: () -> Boolean = { false }, private val closer: () -> Unit = {}) : NzbArticles {
    override fun authenticate(username: String, password: String) {
        CameoNzb.credentials(username, password)
        if (status().take(3) !in setOf("200", "201")) throw IOException("Easynews NNTP is unavailable. Try again later.")
        command("AUTHINFO USER $username")
        var response = status()
        if (response.startsWith("381 ")) { command("AUTHINFO PASS $password"); response = status() }
        if (!response.startsWith("281 ")) throw IOException("Easynews did not accept this username and password.")
        command("CAPABILITIES")
        if (!status().startsWith("101 ")) throw IOException("Easynews capabilities could not be verified.")
        val capabilities = String(multiline(65536, 256), Charsets.US_ASCII).lineSequence()
        if (capabilities.none { it == "READER" || it.startsWith("VERSION ") }) throw IOException("Easynews returned unsupported NNTP capabilities.")
    }

    override fun body(messageId: String): ByteArray {
        if (!CameoNzb.validMessageId(messageId)) throw IOException("Invalid NZB article identity.")
        command("BODY <$messageId>")
        val response = status()
        if (response.startsWith("430 ")) throw IOException("An article is unavailable on Easynews. No finished file was saved.")
        if (!response.startsWith("222 ") || "<$messageId>" !in response.split(' ')) throw IOException("Easynews did not return the requested article.")
        return multiline(CameoNzb.MAX_ARTICLE_BYTES, 200_000)
    }

    private fun command(value: String) {
        CameoNzb.cancelled(cancel)
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (value.any { it == '\r' || it == '\n' || it == '\u0000' } || bytes.size > 1022) throw IOException("Invalid NNTP request.")
        output.write(bytes); output.write(byteArrayOf(13, 10)); output.flush()
    }

    private fun status(): String {
        val text = String(line(2048), Charsets.US_ASCII)
        val code = text.take(3).toIntOrNull()
        if (text.length < 3 || code == null || code !in 100..599 || (text.length > 3 && text[3] != ' ')) throw IOException("Easynews returned an invalid NNTP response.")
        return text
    }

    private fun line(maximum: Int): ByteArray {
        val line = ByteArrayOutputStream(); var lastRead = System.nanoTime()
        while (true) {
            CameoNzb.cancelled(cancel)
            val value = try { input.read() } catch (_: SocketTimeoutException) {
                if (System.nanoTime() - lastRead >= 30_000_000_000L) throw IOException("The encrypted Easynews response timed out.")
                continue
            }
            if (value < 0) throw IOException("Easynews closed the connection before the article was complete.")
            lastRead = System.nanoTime()
            if (value == 10) {
                val bytes = line.toByteArray()
                if (bytes.isEmpty() || bytes.last() != 13.toByte()) throw IOException("Easynews returned an invalid line ending.")
                return bytes.copyOf(bytes.size - 1)
            }
            line.write(value)
            if (line.size() > maximum + 1) throw IOException("The NNTP response exceeded its size limit.")
        }
    }

    private fun multiline(maximumBytes: Int, maximumLines: Int): ByteArray {
        val body = ByteArrayOutputStream()
        repeat(maximumLines) {
            val line = line(16_384)
            if (line.size == 1 && line[0] == 46.toByte()) return body.toByteArray()
            val skip = if (line.size >= 2 && line[0] == 46.toByte() && line[1] == 46.toByte()) 1 else 0
            if (body.size().toLong() + line.size - skip + 2 > maximumBytes) throw IOException("The NNTP article exceeded its size limit.")
            body.write(line, skip, line.size - skip); body.write(byteArrayOf(13, 10))
        }
        throw IOException("The NNTP article exceeded its line limit.")
    }

    override fun close() { runCatching { closer() }; runCatching { input.close() }; runCatching { output.close() } }
}
