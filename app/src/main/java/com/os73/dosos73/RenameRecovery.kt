package com.os73.dosos73

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import java.io.IOException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** One serialized write-ahead SAF transition. Never creates, renames or deletes provider files. */
internal class RenameRecovery(private val context: Context) {
    data class Plan(
        val original: SavedFile,
        val requested: String,
        val bytes: Long,
        val hash: String,
        val before: Set<String>,
        val hidden: Boolean,
        val collection: String,
        val returned: String? = null,
    )
    private data class Child(val id: String, val uri: Uri, val name: String)
    private val journal = PrivateJournal(context, "library-rename.json")

    fun pending(): Plan? {
        val root = journal.readOrNull()?.let(::JSONObject) ?: return null
        require(root.getInt("version") == 1)
        if (!root.has("original")) return null
        val original = root.getJSONObject("original")
        val before = root.getJSONArray("before")
        require(before.length() <= MAX_CHILDREN)
        return Plan(
            SavedFile(original.getString("uri"), original.getString("name"), original.getString("mime"),
                original.getLong("bytes"), original.getLong("date")),
            root.getString("requested"), root.getLong("bytes"), root.getString("hash"),
            (0 until before.length()).map { before.getString(it) }.toSet(),
            root.getBoolean("hidden"), root.getString("collection"),
            if (root.isNull("returned")) null else root.getString("returned"),
        ).also { require(it.hash.matches(Regex("[0-9a-f]{64}")) && it.bytes >= 0) }
    }

    suspend fun prepare(original: SavedFile, requested: String, hidden: Boolean, collection: String): Plan {
        check(pending() == null) { "A rename still needs recovery" }
        val uri = Uri.parse(original.uri)
        val children = children(uri)
        check(children.any { it.id == DocumentsContract.getDocumentId(uri) }) { "Document is outside its saved destination" }
        val fingerprint = fingerprint(uri)
        val plan = Plan(original, requested, fingerprint.first, fingerprint.second,
            children.map { it.id }.toSet(), hidden, collection)
        save(plan)
        return plan
    }

    fun validateReturned(plan: Plan, uri: Uri) {
        val original = Uri.parse(plan.original.uri)
        require(uri.scheme == "content" && uri.authority == original.authority &&
            DocumentsContract.isTreeUri(uri) &&
            DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(original))
        val id = DocumentsContract.getDocumentId(uri)
        require(id == DocumentsContract.getDocumentId(original) || id !in plan.before)
    }

    fun recordReturned(plan: Plan, uri: Uri): Plan {
        validateReturned(plan, uri)
        return plan.copy(returned = uri.toString()).also(::save)
    }

    /** A unique new identity plus exact bytes is required when the provider never returned its URI. */
    suspend fun resolve(plan: Plan): SavedFile? {
        val originalUri = Uri.parse(plan.original.uri)
        val originalId = DocumentsContract.getDocumentId(originalUri)
        val current = children(originalUri)
        val returnedId = plan.returned?.let { DocumentsContract.getDocumentId(Uri.parse(it)) }
        if (returnedId != null) {
            require(returnedId == originalId || returnedId !in plan.before)
            val child = current.singleOrNull { it.id == returnedId }
                ?: throw IOException("Renamed document is unavailable in its destination")
            verify(plan, child.uri)
            return plan.original.copy(uri = child.uri.toString(), name = child.name, bytes = plan.bytes)
        }
        // Never adopt a pre-existing sibling, even if its name and bytes are identical.
        val candidates = current.filter {
            it.id == originalId || (it.id !in plan.before && it.name == plan.requested)
        }
        val matching = candidates.filter { child -> matches(plan, child.uri) }
        if (matching.size != 1) throw IOException("Rename identity requires review")
        val child = matching.single()
        if (child.id == originalId && child.name == plan.original.name) return null
        if (child.name != plan.requested ||
            (child.id != originalId && current.any { it.id == originalId }))
            throw IOException("Unrecorded normalized rename requires review")
        return plan.original.copy(uri = child.uri.toString(), name = child.name, bytes = plan.bytes)
    }

    fun forget() = journal.write(JSONObject().put("version", 1).toString())

    private fun save(plan: Plan) {
        journal.write(JSONObject().put("version", 1)
            .put("original", JSONObject().put("uri", plan.original.uri).put("name", plan.original.name)
                .put("mime", plan.original.mime).put("bytes", plan.original.bytes).put("date", plan.original.date))
            .put("requested", plan.requested).put("bytes", plan.bytes).put("hash", plan.hash)
            .put("before", JSONArray(plan.before.toList())).put("hidden", plan.hidden)
            .put("collection", plan.collection).put("returned", plan.returned).toString())
    }

    private suspend fun children(uri: Uri): List<Child> = coroutineScope {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Rename requires its saved destination tree" }
        val signal = CancellationSignal()
        val cleanup = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        try { runInterruptible(Dispatchers.IO) { context.contentResolver.query(childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null, signal)?.use { cursor ->
            val rows = ArrayList<Child>()
            var characters = 0
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                characters += id.length + name.length
                check(rows.size < MAX_CHILDREN && characters <= 1024 * 1024) { "Rename destination exceeds recovery limit" }
                rows += Child(id, DocumentsContract.buildDocumentUriUsingTree(uri, id), name)
            }
            check(rows.map { it.id }.toSet().size == rows.size) { "Duplicate destination identities" }
            rows
        } ?: throw IOException("Rename destination unavailable") } }
        finally { cleanup.cancelAndJoin() }
    }

    private suspend fun fingerprint(uri: Uri): Pair<Long, String> {
        val actual = FileDigest.compute(context, uri)
        return actual.bytes to actual.sha256
    }

    private suspend fun verify(plan: Plan, uri: Uri) {
        if (!matches(plan, uri)) throw IOException("Renamed content changed; review required")
    }

    private suspend fun matches(plan: Plan, uri: Uri): Boolean {
        val actual = fingerprint(uri)
        return actual.first == plan.bytes && actual.second == plan.hash
    }

    companion object { private const val MAX_CHILDREN = 4096 }
}
