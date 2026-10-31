package com.os73.dosos73

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Debug-only fault provider. Never included in release APKs. */
class RecoveryTestDocumentsProvider : DocumentsProvider() {
    data class Entry(val name: String, val file: File)
    companion object {
        val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
        var failAfterRename = false
        @Volatile var onRead: (() -> Unit)? = null
        @Volatile var afterRename: (() -> Unit)? = null
        var rootWritable = true
        var rejectRename = false
        var unknownSize = false
        var denyRead = false
        var denyQuery = false
        var emptyQuery = false
        var deletes = 0
        var renames = 0
        fun reset() {
            entries.values.forEach { it.file.delete() }; entries.clear()
            afterRename = null; onRead = null; rootWritable = true
            failAfterRename = false; rejectRename = false; unknownSize = false; denyRead = false; denyQuery = false; emptyQuery = false; deletes = 0; renames = 0
        }
    }
    override fun onCreate() = true
    private val columns get() = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: emptyArray())
    private fun row(cursor: MatrixCursor, id: String) {
        val entry = entries[id]
        if (id != "root" && entry == null) return
        cursor.addRow(cursor.columnNames.map { column -> when (column) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> entry?.name ?: "root"
            Document.COLUMN_MIME_TYPE -> if (id == "root") Document.MIME_TYPE_DIR else "application/octet-stream"
            Document.COLUMN_SIZE -> if (unknownSize) null else entry?.file?.length() ?: 0L
            Document.COLUMN_FLAGS -> if (id == "root") (if (rootWritable) Document.FLAG_DIR_SUPPORTS_CREATE else 0) else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE
            else -> null
        } }.toTypedArray())
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (denyQuery) throw SecurityException("Access revoked")
        return MatrixCursor(projection ?: columns).also { if (!emptyQuery || documentId == "root") row(it, documentId) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: columns).also { cursor -> entries.keys.sorted().forEach { row(cursor, it) } }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "root" && entries.containsKey(documentId)
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(displayName, File(context!!.cacheDir, "recovery-provider-$id").apply { createNewFile() })
        return id
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val entry = entries[documentId] ?: throw FileNotFoundException()
        if (mode == "r") onRead?.invoke()
        if (mode == "r" && denyRead) throw FileNotFoundException("Storage offline")
        if (mode == "r" && unknownSize) {
            val bytes = entry.file.readBytes()
            val pipe = ParcelFileDescriptor.createPipe()
            Thread { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } } }
                .apply { isDaemon = true; start() }
            return pipe[0]
        }
        return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.parseMode(mode))
    }
    override fun renameDocument(documentId: String, displayName: String): String? {
        if (rejectRename) throw FileNotFoundException("Rename denied")
        val entry = entries.remove(documentId) ?: throw FileNotFoundException()
        val next = UUID.randomUUID().toString()
        entries[next] = entry.copy(name = displayName)
        renames++
        if (failAfterRename) throw FileNotFoundException("Interrupted after rename")
        afterRename?.invoke()
        return next
    }
    override fun deleteDocument(documentId: String) {
        val entry = entries.remove(documentId) ?: throw FileNotFoundException()
        entry.file.delete(); deletes++
    }
}
