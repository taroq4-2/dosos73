package com.os73.dosos73

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import org.json.JSONObject

/** Write-ahead publication plan. Never delete a verified or ambiguously renamed document. */
internal class PublicationReceipt(private val context: Context, private val id: String) {
    init { require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) }
    private val name = "publication-$id.json"
    private val journal = PrivateJournal(context, name)
    private fun review(cause: Throwable? = null): Nothing =
        throw PipelineException(Stage.PUBLISH, FailureCode.PUBLICATION_REVIEW_REQUIRED, cause)

    fun prepare(tree: Uri?, mime: String, expected: StreamIntegrity.Fingerprint, title: String, extension: String): String {
        if (journal.readOrNull() != null) review()
        val temporary = "dosos73-$id.partial.$extension"
        val final = title.substringBeforeLast('.') + "-$id.$extension"
        journal.write(JSONObject().put("plan", 1).put("tree", tree?.toString())
            .put("temporary", temporary).put("final", final).put("mime", mime)
            .put("bytes", expected.bytes).put("hash", hex(expected))
            .put("verified", false).put("ready", false).toString())
        return temporary
    }

    fun record(uri: Uri, mime: String, fingerprint: StreamIntegrity.Fingerprint, ready: Boolean) {
        val value = journal.readOrNull()?.let(::JSONObject) ?: JSONObject()
        value.put("uri", uri.toString()).put("mime", mime).put("bytes", fingerprint.bytes)
            .put("hash", hex(fingerprint)).put("ready", ready)
        journal.write(value.toString())
    }

    fun markVerified() {
        val value = JSONObject(journal.readOrNull() ?: error("Missing publication plan"))
        value.put("verified", true)
        journal.write(value.toString())
    }

    /** Used both after a normal copy and after process death. All mutations are retryable. */
    fun recover(): Pair<Uri, String>? {
        val value = journal.readOrNull()?.let(::JSONObject) ?: return null
        return if (value.optInt("plan") == 1) recoverPlan(value) else recoverLegacy(value)
    }

    private fun recoverPlan(value: JSONObject): Pair<Uri, String>? {
        val resolver = context.contentResolver
        val tree = if (value.isNull("tree")) null else Uri.parse(value.getString("tree"))
        val temporary = value.getString("temporary")
        val final = value.getString("final")
        require(temporary.startsWith("dosos73-$id.partial."))
        val candidates = mutableListOf<Pair<Uri, String>>()
        if (tree == null) {
            resolver.query(MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} IN (?, ?)",
                arrayOf("Download/dosos73/", context.packageName, temporary, final), null)?.use { cursor ->
                while (cursor.moveToNext()) candidates += android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)) to cursor.getString(1)
            } ?: review()
        } else {
            val folder = DocumentFile.fromTreeUri(context, tree) ?: review()
            if (!folder.isDirectory) review()
            // The actual queries enforce read permission, including owner-provided documents on API29.
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            resolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val actual = cursor.getString(1)
                    if (actual == temporary || actual == final) candidates +=
                        android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)) to actual
                }
            } ?: review()
        }
        if (candidates.size > 1) review()
        if (candidates.isEmpty()) {
            // A recorded URI can survive a provider-normalized name. It is still receipt-bound.
            if (!value.isNull("uri")) review()
            forget() // Plan persisted but creation did not produce a discoverable document.
            return null
        }
        val (uri, actualName) = candidates.single()
        val expected = expected(value)
        val matches = resolver.openInputStream(uri)?.use {
            StreamIntegrity.fingerprint(it).let { actual -> actual.bytes == expected.bytes && actual.sha256.contentEquals(expected.sha256) }
        } ?: review()
        if (!matches) {
            if (value.optBoolean("verified") || value.optBoolean("ready") || actualName != temporary) review()
            // Exact task-specific temporary name; no verified/published file is removed here.
            val deleted = if (tree == null) resolver.delete(uri, null, null) == 1
                else DocumentFile.fromSingleUri(context, uri)?.delete() == true
            if (!deleted) review()
            forget()
            return null
        }
        value.put("uri", uri.toString()).put("verified", true)
        journal.write(value.toString()) // Durable before any provider rename/update.
        return publish(value, uri, actualName)
    }

    fun publishVerified(uri: Uri): Pair<Uri, String> {
        val value = JSONObject(journal.readOrNull() ?: error("Missing publication plan"))
        require(value.optBoolean("verified") && value.getString("uri") == uri.toString())
        return publish(value, uri, value.getString("temporary"))
    }

    private fun publish(value: JSONObject, uri: Uri, actualName: String): Pair<Uri, String> {
        val resolver = context.contentResolver
        val tree = if (value.isNull("tree")) null else Uri.parse(value.getString("tree"))
        val final = value.getString("final")
        val result = if (tree == null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, final)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, values, null, null) != 1) review()
            uri
        } else {
            if (actualName == final) uri else try {
                android.provider.DocumentsContract.renameDocument(resolver, uri, final) ?: review()
            } catch (error: Exception) { review(error) }
        }
        value.put("uri", result.toString()).put("ready", true)
        journal.write(value.toString())
        return result to value.getString("mime")
    }

    private fun expected(value: JSONObject): StreamIntegrity.Fingerprint {
        val hash = value.getString("hash")
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return StreamIntegrity.Fingerprint(value.getLong("bytes"), hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }

    private fun recoverLegacy(value: JSONObject): Pair<Uri, String>? {
        val uri = Uri.parse(value.getString("uri"))
        require(uri.scheme == "content")
        if (!value.getBoolean("ready")) {
            if (uri.authority == MediaStore.AUTHORITY) {
                if (context.contentResolver.delete(uri, null, null) != 1) review()
            } else {
                val document = DocumentFile.fromSingleUri(context, uri) ?: review()
                val actualName = document.name ?: review()
                if (!actualName.startsWith(".dosos73-") || !actualName.endsWith(".partial")) review()
                if (!document.delete()) review()
            }
            forget()
            return null
        }
        context.contentResolver.openInputStream(uri)?.use {
            StreamIntegrity.requireSame(expected(value), StreamIntegrity.fingerprint(it))
        } ?: review()
        if (uri.authority == MediaStore.AUTHORITY) {
            if (context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1) review()
        }
        return uri to value.getString("mime")
    }

    fun forget() { android.util.AtomicFile(File(context.filesDir, name)).delete() }
    private fun hex(value: StreamIntegrity.Fingerprint) = value.sha256.joinToString("") { "%02x".format(it) }
}
