package com.os73.dosos73

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class TimelineResult { STARTED, COMPLETED, RECOVERED, INTERRUPTED, FAILED }
internal data class TimelineEvent(val taskId: String, val at: Long, val stage: Stage,
    val result: TimelineResult, val bytes: Long? = null, val code: FailureCode? = null)

/** No source strings accepted. One encrypted, bounded journal; no timer/background watcher. */
internal object TaskTimeline {
    private val lock = Mutex()
    private fun journal(context: Context) = PrivateJournal(context, "task-timeline.json")
    private fun load(context: Context): List<TimelineEvent> {
        val text = journal(context).readOrNull() ?: return emptyList()
        require(text.length <= 1024 * 1024)
        val root = JSONObject(text)
        require(root.getInt("version") == 1)
        val rows = root.getJSONArray("events")
        require(rows.length() <= 768)
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val id = row.getString("task")
            require(UUID.fromString(id).toString() == id)
            TimelineEvent(id, row.getLong("at"), Stage.valueOf(row.getString("stage")),
                TimelineResult.valueOf(row.getString("result")),
                if (row.isNull("bytes")) null else row.getLong("bytes"),
                if (row.isNull("code")) null else FailureCode.valueOf(row.getString("code")))
        }
    }
    private fun bounded(context: Context, events: List<TimelineEvent>, now: Long): List<TimelineEvent> {
        val days = LibraryPreferences.historyDays(context).coerceIn(0, 365)
        val recent = events.filter { days == 0 || it.at >= now - days * 86400000L }.sortedBy { it.at }
        val tasks = recent.asReversed().map { it.taskId }.distinct().take(32).toSet()
        return recent.filter { it.taskId in tasks }.groupBy { it.taskId }.values
            .flatMap { it.takeLast(24) }.sortedBy { it.at }
    }
    private fun save(context: Context, events: List<TimelineEvent>) {
        val array = JSONArray()
        events.forEach { array.put(JSONObject().put("task", it.taskId).put("at", it.at)
            .put("stage", it.stage.name).put("result", it.result.name).put("bytes", it.bytes).put("code", it.code?.name)) }
        journal(context).write(JSONObject().put("version", 1).put("events", array).toString())
    }
    suspend fun record(context: Context, event: TimelineEvent) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(UUID.fromString(event.taskId).toString() == event.taskId && (event.bytes == null || event.bytes >= 0))
            val events = bounded(context, load(context) + event, System.currentTimeMillis())
            save(context, events)
        }
    }
    suspend fun read(context: Context): List<TimelineEvent> = withContext(Dispatchers.IO) {
        lock.withLock {
            val all = load(context)
            val retained = bounded(context, all, System.currentTimeMillis())
            if (retained != all) save(context, retained)
            retained
        }
    }
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) { lock.withLock { save(context, emptyList()) } }
}
