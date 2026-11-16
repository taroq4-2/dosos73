package com.os73.dosos73

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Small atomic additions journal, bound to the exact legacy snapshot it extends.
 * A compacted snapshot commits first; its different digest makes an old journal
 * inert after a crash. Rename/delete still use the existing snapshot transaction.
 * Callers hold LibraryRepository's mutex. No new database or worker is needed.
 */
internal class LibraryAdditions(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "saved-files-additions.json"))

    fun read(snapshot: String): List<SavedFile> {
        val input = try { file.openRead() } catch (_: java.io.FileNotFoundException) { return emptyList() }
        return input.use {
            val buffer = ByteArray(8192)
            val content = java.io.ByteArrayOutputStream()
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                require(content.size() + n <= 1024 * 1024)
                content.write(buffer, 0, n)
            }
            val bytes = content.toByteArray()
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (json.getString("snapshot") != snapshot) return@use emptyList()
            val rows = json.getJSONArray("rows")
            require(rows.length() <= 128)
            (0 until rows.length()).map { n -> rows.getJSONObject(n).let { row ->
                SavedFile(row.getString("uri"), row.getString("name"), row.getString("mime"),
                    row.getLong("bytes"), row.getLong("date"))
            } }
        }
    }

    fun write(snapshot: String, rows: List<SavedFile>) {
        require(rows.size <= 128)
        val json = JSONObject().put("snapshot", snapshot).put("rows", JSONArray().apply {
            rows.forEach { row -> put(JSONObject().put("uri", row.uri).put("name", row.name)
                .put("mime", row.mime).put("bytes", row.bytes).put("date", row.date)) }
        }).toString().toByteArray(Charsets.UTF_8)
        require(json.size <= 1024 * 1024)
        val out = file.startWrite()
        try { out.write(json); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }

    fun clear() = file.delete()
}
