package com.os73.dosos73

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import org.json.JSONObject

/**
 * Readable final names for published files ("Report.pdf", then "Report (2).pdf"...).
 *
 * Recovery ([PublicationReceipt.recover]) finds a half-published file by its exact planned
 * name and refuses to guess when two files match, so the planned name must not collide with
 * a file already in the destination nor with another unfinished publication. When either set
 * cannot be read, callers keep the collision-free `name-<taskId>.ext` scheme of 1.3.2.
 */
internal object PublicationNames {
    private const val MAX_NUMBERED = 50

    /** Pure choice; [taken] is compared case-insensitively (shared storage is case-insensitive). */
    fun choose(base: String, extension: String, id: String, taken: Set<String>): String {
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        val stem = base.ifBlank { "download" }
        val first = "$stem.$extension"
        if (first.lowercase() !in used) return first
        for (n in 2..MAX_NUMBERED) {
            val numbered = "$stem ($n).$extension"
            if (numbered.lowercase() !in used) return numbered
        }
        return "$stem-$id.$extension"
    }

    /** Display names currently in the destination, or null when they cannot be listed. */
    fun existing(context: Context, tree: Uri?): Set<String>? = runCatching {
        val names = HashSet<String>()
        val resolver = context.contentResolver
        if (tree == null) {
            resolver.query(MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("Download/dosos73/"), null)?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
            } ?: return@runCatching null
        } else {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor -> while (cursor.moveToNext()) cursor.getString(0)?.let(names::add) }
                ?: return@runCatching null
        }
        names
    }.getOrNull()

    /** Final names planned by other unfinished publications, or null when a receipt is unreadable. */
    fun reserved(context: Context, exceptId: String): Set<String>? = runCatching {
        val names = HashSet<String>()
        val receipts = context.filesDir.listFiles { file: File ->
            file.name.startsWith("publication-") && file.name.endsWith(".json")
        }.orEmpty()
        for (file in receipts) {
            if (file.name == "publication-$exceptId.json") continue
            val text = PrivateJournal(context, file.name).readOrNull() ?: continue
            JSONObject(text).optString("final").takeIf { it.isNotEmpty() }?.let(names::add)
        }
        names
    }.getOrNull()
}
