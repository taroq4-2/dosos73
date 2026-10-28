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

/** Retains retryable failures independently of the current transfer; no output is deleted here. */
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
                save(context, next)
            }
        }

    suspend fun remove(context: Context, id: String, discardPartial: Boolean = true) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                save(context, mutable.value.filterNot { it.task.id == id })
                if (
                    discardPartial &&
                        !(Transfers.state.value.active && Transfers.currentTask?.id == id)
                )
                    TransferFiles.discard(context, id)
            }
        }

    suspend fun clear(context: Context) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                val old = mutable.value
                save(context, emptyList())
                old.filterNot {
                        Transfers.state.value.active && Transfers.currentTask?.id == it.task.id
                    }
                    .forEach { TransferFiles.discard(context, it.task.id) }
            }
        }
}
