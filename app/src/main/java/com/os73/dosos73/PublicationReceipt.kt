package com.os73.dosos73

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import org.json.JSONObject

/** A receipt is durable before exposing a MediaStore row. SAF rename remains provider dependent. */
internal class PublicationReceipt(private val context: Context, id: String) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
    }

    private val name = "publication-$id.json"
    private val journal = PrivateJournal(context, name)

    fun record(uri: Uri, mime: String, fingerprint: StreamIntegrity.Fingerprint, ready: Boolean) {
        journal.write(
            JSONObject()
                .put("uri", uri.toString())
                .put("mime", mime)
                .put("bytes", fingerprint.bytes)
                .put("hash", fingerprint.sha256.joinToString("") { "%02x".format(it) })
                .put("ready", ready)
                .toString()
        )
    }

    fun recover(): Pair<Uri, String>? {
        val text = journal.readOrNull() ?: return null
        val value = JSONObject(text)
        val uri = Uri.parse(value.getString("uri"))
        require(uri.scheme == "content")
        if (!value.getBoolean("ready")) {
            // Only a receipt for our own pre-publication row may be cleaned here.
            if (uri.authority == MediaStore.AUTHORITY) {
                if (context.contentResolver.delete(uri, null, null) != 1)
                    throw IOException("Cannot clean staged output")
            } else {
                val document =
                    androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                        ?: throw IOException("Staged document unavailable")
                val name = document.name ?: throw IOException("Staged document unavailable")
                // A provider may keep the same URI after rename: never delete a finalized file.
                if (!name.startsWith(".dosos73-") || !name.endsWith(".partial"))
                    throw IOException("Publication needs review")
                if (!document.delete()) throw IOException("Cannot clean staged output")
            }
            forget()
            return null
        }
        val expectedHash = value.getString("hash")
        require(expectedHash.matches(Regex("[0-9a-f]{64}")))
        val expected =
            StreamIntegrity.Fingerprint(
                value.getLong("bytes"),
                expectedHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            )
        context.contentResolver.openInputStream(uri)?.use {
            StreamIntegrity.requireSame(expected, StreamIntegrity.fingerprint(it))
        } ?: throw IOException("Published output unavailable")
        if (uri.authority == MediaStore.AUTHORITY) {
            val values =
                android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (context.contentResolver.update(uri, values, null, null) != 1)
                throw IOException("Cannot recover publication")
        }
        return uri to value.getString("mime")
    }

    fun forget() {
        val base = File(context.filesDir, name)
        android.util.AtomicFile(base).delete()
    }
}
