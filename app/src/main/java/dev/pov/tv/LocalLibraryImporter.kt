package dev.pov.tv

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException

/** Only the document or directory explicitly selected in Android's picker is scanned. */
class LocalLibraryImporter(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    data class Result(val items: List<SavedMedia>, val unreadableFolders: Int = 0, val truncated: Boolean = false)

    fun documents(uris: List<Uri>): Result = Result(uris.distinct().mapNotNull { uri ->
        require(uri.scheme == "content") { "Choose a video from the file picker." }
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0).orEmpty(); val size = if (cursor.isNull(1)) 0 else cursor.getLong(1)
            if (isVideo(name, resolver.getType(uri).orEmpty())) SavedMedia(name, "local", uri = uri.toString(), size = size) else null
        }
    })

    fun tree(uri: Uri): Result {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Choose a video folder." }
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(DocumentsContract.getTreeDocumentId(uri) to 0)
        val visited = mutableSetOf<String>(); val found = linkedMapOf<String, SavedMedia>(); var skipped = 0; var limited = false
        while (pending.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw IOException("Import canceled.")
            val (id, depth) = pending.removeFirst()
            if (!visited.add(id)) continue
            if (depth > 80 || found.size >= 50_000) { limited = true; continue }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
            try {
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext() && found.size < 50_000) {
                        val child = cursor.getString(0); val name = cursor.getString(1).orEmpty(); val mime = cursor.getString(2).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(child to depth + 1)
                        else if (isVideo(name, mime)) {
                            val address = DocumentsContract.buildDocumentUriUsingTree(uri, child).toString()
                            found[address] = SavedMedia(name, "local", uri = address, size = if (cursor.isNull(3)) 0 else cursor.getLong(3))
                        }
                    }
                } ?: run { skipped++ }
            } catch (_: SecurityException) { skipped++ } catch (_: IOException) { skipped++ }
        }
        return Result(found.values.toList(), skipped, limited)
    }
    companion object {
        private val extensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "ts", "m2ts", "mpg", "mpeg", "flv", "ogv")
        fun isVideo(name: String, mime: String): Boolean = mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in extensions
    }
}
