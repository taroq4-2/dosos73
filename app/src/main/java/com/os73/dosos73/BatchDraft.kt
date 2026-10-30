package com.os73.dosos73

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Encrypted review draft only: restoring it never enqueues or starts a transfer. */
internal class BatchDraft(context: Context) {
    private val journal = PrivateJournal(context, "batch-review.json")

    fun read(): List<BatchEntry> {
        val text = journal.readOrNull() ?: return emptyList()
        require(text.length <= 4 * 1024 * 1024)
        val root = JSONObject(text)
        val version = root.getInt("version")
        require(version in 1..2)
        val rows = root.getJSONArray("rows")
        require(rows.length() <= 20)
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val raw = row.getString("raw").also { require(it.length <= 8192) }
            val url = if (row.isNull("url")) null else Safety.url(row.getString("url"))
            val tasks = row.optJSONArray("formats") ?: JSONArray()
            require(tasks.length() <= 1000)
            val decoded = (0 until tasks.length()).map {
                val task = if (version == 1) tasks.getJSONObject(it)
                    else row.getJSONObject("analysis").put("choice", tasks.getJSONObject(it))
                DownloadQueue.decodeTask(task)
            }
            require(decoded.all { it.analysis.url == url })
            val analysis = decoded.firstOrNull()?.analysis?.copy(choices = decoded.map { it.choice })
            val choice = analysis?.choices?.getOrNull(row.optInt("choiceIndex", -1))
            val destination = if (row.isNull("destination")) null else row.getString("destination")
            require(destination == null || android.net.Uri.parse(destination).scheme == "content")
            BatchEntry(raw, url, row.getBoolean("selected") && url != null, analysis,
                error = if (url == null) R.string.error_url else null,
                choice = choice, destination = destination,
                destinationOverride = row.optBoolean("destinationOverride"))
        }
    }

    fun write(rows: List<BatchEntry>) {
        require(rows.size <= 20)
        val array = JSONArray()
        rows.forEach { row ->
            require(row.raw.length <= 8192)
            val formats = JSONArray()
            var header: JSONObject? = null
            row.analysis?.let { analysis ->
                require(analysis.choices.size <= 1000)
                analysis.choices.firstOrNull()?.let { first ->
                    header = DownloadQueue.encodeTask(DownloadTask(analysis, first, null)).apply { remove("choice") }
                }
                analysis.choices.forEach { formats.put(DownloadQueue.encodeChoice(it)) }
            }
            array.put(JSONObject().put("raw", row.raw).put("url", row.url)
                .put("selected", row.selected).put("formats", formats).put("analysis", header)
                .put("choiceIndex", row.analysis?.choices?.indexOf(row.choice) ?: -1)
                .put("destination", row.destination).put("destinationOverride", row.destinationOverride))
        }
        val text = JSONObject().put("version", 2).put("rows", array).toString()
        require(text.length <= 4 * 1024 * 1024)
        journal.write(text)
    }
}
