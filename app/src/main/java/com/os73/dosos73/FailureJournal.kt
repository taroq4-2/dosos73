package com.os73.dosos73

import android.content.Context
import android.util.AtomicFile
import java.io.File
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

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "failed-downloads.json"))

    private fun load(context: Context) {
        if (loaded) return
        mutable.value =
            runCatching {
                    val array =
                        JSONArray(file(context).openRead().bufferedReader().use { it.readText() })
                    (0 until array.length()).map {
                        array.getJSONObject(it).let { json ->
                            FailedDownload(
                                DownloadQueue.decodeTask(json.getJSONObject("task")),
                                if (json.isNull("code")) null
                                else
                                    runCatching { FailureCode.valueOf(json.getString("code")) }
                                        .getOrDefault(FailureCode.DOWNLOAD_FAILED),
                                json.getBoolean("cancelled"),
                            )
                        }
                    }
                }
                .getOrDefault(emptyList())
        loaded = true
    }

    private fun save(context: Context) {
        val array = JSONArray()
        mutable.value.forEach {
            array.put(
                JSONObject()
                    .put("task", DownloadQueue.encodeTask(it.task))
                    .put("code", it.code?.name)
                    .put("cancelled", it.cancelled)
            )
        }
        val atomic = file(context)
        val stream = atomic.startWrite()
        try {
            stream.write(array.toString().toByteArray())
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    suspend fun initialize(context: Context) =
        withContext(Dispatchers.IO) { lock.withLock { load(context) } }

    suspend fun record(context: Context, entry: FailedDownload) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                mutable.value =
                    (listOf(entry) + mutable.value.filterNot { it.task.id == entry.task.id }).take(
                        100
                    )
                save(context)
            }
        }

    suspend fun remove(context: Context, id: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                mutable.value = mutable.value.filterNot { it.task.id == id }
                save(context)
            }
        }
}
