package dev.pov.tv

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/** Android's Storage Access Framework covers device, USB and installed network document providers. */
internal object CameoMediaImporter {
    fun name(context: Context, uri: Uri): String = context.contentResolver.query(uri,
        arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf(String::isNotBlank) ?: "Imported video"

    fun videos(context: Context, tree: Uri, cancellation:android.os.CancellationSignal?=null,
        progress:(Int)->Unit={}): List<Pair<Uri, String>> {
        cancellation?.throwIfCanceled()
        require(DocumentsContract.isTreeUri(tree)) { "Choose a folder using Android's folder picker." }
        val found = mutableListOf<Pair<Uri, String>>()
        val pending = ArrayDeque<Pair<String, Int>>()
        val visited = mutableSetOf<String>()
        pending.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        while (pending.isNotEmpty() && found.size < 2000 && visited.size < 4096) {
            cancellation?.throwIfCanceled()
            val (parent, depth) = pending.removeFirst()
            if (!visited.add(parent) || depth > 12) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,cancellation)?.use { cursor ->
                while (cursor.moveToNext() && found.size < 2000) {
                    cancellation?.throwIfCanceled()
                    val id = cursor.getString(0); val name = cursor.getString(1).orEmpty(); val mime = cursor.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR && pending.size < 4096) pending.add(id to depth + 1)
                    else if (mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in extensions) {
                        found += DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                        if(found.size==1||found.size%25==0)progress(found.size)
                    }
                }
            }
        }
        cancellation?.throwIfCanceled();progress(found.size)
        return found
    }
    private val extensions = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "ogv", "3gp")
}
