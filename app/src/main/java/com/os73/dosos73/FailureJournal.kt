package com.os73.dosos73

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class FailedDownload(
    val task: DownloadTask,
    val code: FailureCode?,
    val cancelled: Boolean,
)

/** Retains retryable failures; cleanup only touches unowned private partials, never saved outputs. */
internal object FailureJournal {
    private val lock = Mutex()
    private var loaded = false
    private val mutable = MutableStateFlow<List<FailedDownload>>(emptyList())
    val failures = mutable.asStateFlow()

    private fun file(context: Context) = PrivateJournal(context, "failed-downloads.json")

    private fun load(context: Context) {
        if (loaded) return
        val text = file(context).readOrNull()
        val array = if (text == null) JSONArray() else JSONArray(text)
        mutable.value =
            (0 until array.length()).map {
                val json = array.getJSONObject(it)
                FailedDownload(
                    DownloadQueue.decodeTask(json.getJSONObject("task")),
                    if (json.isNull("code")) null
                    else
                        runCatching { FailureCode.valueOf(json.getString("code")) }
                            .getOrDefault(FailureCode.DOWNLOAD_FAILED),
                    json.getBoolean("cancelled"),
                )
            }
        loaded = true
    }

    private fun save(context: Context, entries: List<FailedDownload>) {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("task", DownloadQueue.encodeTask(it.task))
                    .put("code", it.code?.name)
                    .put("cancelled", it.cancelled)
            )
        }
        file(context).write(array.toString())
        mutable.value = entries
    }

    // Failure lock -> queue lock; queue operations never acquire the failure lock.
    // Load queue ownership before committing eviction so an unreadable queue cannot
    // remove the only recoverable reference. No suspension separates commit/cleanup.
    private suspend fun saveAndDiscard(
        context: Context,
        entries: List<FailedDownload>,
        removed: Set<String>,
    ) {
        if (removed.isEmpty()) save(context, entries)
        else DownloadQueue.commitAndDiscardUnownedPartials(context, removed) {
            save(context, entries)
        }
    }

    suspend fun initialize(context: Context) =
        withContext(Dispatchers.IO) { lock.withLock { load(context) } }

    suspend fun record(context: Context, entry: FailedDownload) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                val next =
                    (listOf(entry) + mutable.value.filterNot { it.task.id == entry.task.id }).take(
                        100
                    )
                val retained = next.map { it.task.id }.toSet()
                val removed = mutable.value.map { it.task.id }.toSet() - retained
                saveAndDiscard(context, next, removed)
            }
        }

    suspend fun remove(context: Context, id: String, discardPartial: Boolean = true) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                val next = mutable.value.filterNot { it.task.id == id }
                saveAndDiscard(context, next, if (discardPartial) setOf(id) else emptySet())
            }
        }

    suspend fun clear(context: Context) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                val old = mutable.value
                saveAndDiscard(context, emptyList(), old.map { it.task.id }.toSet())
            }
        }
}
