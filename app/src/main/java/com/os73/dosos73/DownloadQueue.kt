package com.os73.dosos73

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Commit to disk before publishing mutations. One durable lease prevents duplicate execution. */
internal object DownloadQueue {
    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks = mutable.asStateFlow()
    private var loaded = false
    @Volatile private var active: DownloadTask? = null

    private fun journal(context: Context) = PrivateJournal(context, "waiting-tasks.json")

    private fun load(context: Context) {
        if (loaded) return
        val text = journal(context).readOrNull()
        var leased: DownloadTask? = null
        val waiting =
            if (text == null) emptyList()
            else {
                val array =
                    if (text.trimStart().startsWith("[")) JSONArray(text)
                    else {
                        val obj = JSONObject(text)
                        leased = obj.optJSONObject("active")?.let(::decodeTask)
                        obj.getJSONArray("waiting")
                    }
                (0 until array.length()).map { decodeTask(array.getJSONObject(it)) }
            }
        active = leased
        mutable.value = waiting
        loaded = true
    }

    private fun commit(context: Context, waiting: List<DownloadTask>, leased: DownloadTask?) {
        if (waiting == mutable.value && leased == active) return
        val array = JSONArray()
        waiting.forEach { array.put(encodeTask(it)) }
        journal(context)
            .write(
                JSONObject()
                    .put("waiting", array)
                    .put("active", leased?.let(::encodeTask))
                    .toString()
            )
        active = leased
        mutable.value = waiting
    }

    suspend fun initialize(context: Context) =
        withContext(Dispatchers.IO) { lock.withLock { load(context) } }

    suspend fun enqueue(context: Context, task: DownloadTask) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                if (active?.id != task.id && mutable.value.none { it.id == task.id }) {
                    require(mutable.value.size < 50)
                    commit(context, mutable.value + task, active)
                }
            }
        }

    fun hasRunnable(now: Long = System.currentTimeMillis()) =
        active?.let { !it.userPaused } ?: mutable.value.any { !it.userPaused && it.notBefore <= now }

    suspend fun take(context: Context): DownloadTask? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                active?.let {
                    return@withLock if (it.userPaused) null else it
                }
                val task =
                    mutable.value
                        .filter { !it.userPaused && it.notBefore <= System.currentTimeMillis() }
                        .minByOrNull { it.priority.ordinal }
                if (task != null)
                    commit(context, mutable.value.filterNot { it.id == task.id }, task)
                task
            }
        }

    suspend fun rememberActive(context: Context, task: DownloadTask) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                check(active == null || active?.id == task.id)
                commit(context, mutable.value.filterNot { it.id == task.id }, task)
            }
        }

    suspend fun finish(context: Context, id: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                if (active?.id == id) commit(context, mutable.value, null)
            }
        }

    suspend fun interrupted(context: Context): DownloadTask? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                active
            }
        }

    suspend fun remove(context: Context, id: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                commit(context, mutable.value.filterNot { it.id == id }, active)
            }
        }

    suspend fun updatePriority(context: Context, id: String, priority: TaskPriority) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                commit(
                    context,
                    mutable.value.map { if (it.id == id) it.copy(priority = priority) else it },
                    active,
                )
            }
        }

    suspend fun setPaused(context: Context, id: String, paused: Boolean) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                commit(
                    context,
                    mutable.value.map { if (it.id == id) it.copy(userPaused = paused) else it },
                    active?.let { if (it.id == id) it.copy(userPaused = paused) else it },
                )
            }
        }

    suspend fun move(context: Context, id: String, delta: Int) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                val list = mutable.value.toMutableList()
                val index = list.indexOfFirst { it.id == id }
                if (index >= 0) {
                    val target = (index + delta.coerceIn(-1, 1)).coerceIn(0, list.lastIndex)
                    val task = list.removeAt(index)
                    list.add(target, task)
                    commit(context, list, active)
                }
            }
        }

    internal fun encodeTask(task: DownloadTask): JSONObject =
        JSONObject()
            .put("id", task.id)
            .put("url", task.analysis.url)
            .put("title", task.analysis.title)
            .put("direct", task.analysis.direct)
            .put("size", task.analysis.sizeBytes)
            .put("tree", task.tree?.toString())
            .put("priority", task.priority.name)
            .put("paused", task.userPaused)
            .put("notBefore", task.notBefore)
            .put("choice", encodeChoice(task.choice))

    internal fun encodeChoice(choice: Choice): JSONObject =
        JSONObject()
                    .put("id", choice.id)
                    .put("kind", choice.kind.name)
                    .put("ext", choice.extension)
                    .put("quality", choice.quality)
                    .put("height", choice.expectedHeight)
                    .put("audio", choice.expectsAudio)
                    .put("estimatedBytes", choice.estimatedBytes)
                    .put("bitrateKbps", choice.bitrateKbps)
                    .put("language", choice.language)

    internal fun decodeTask(json: JSONObject): DownloadTask {
        val source = Safety.url(json.getString("url"))
        val c = json.getJSONObject("choice")
        val choice =
            Choice(
                c.getString("id"),
                Kind.valueOf(c.getString("kind")),
                c.getString("ext"),
                c.getString("quality"),
                if (c.isNull("height")) null else c.getInt("height"),
                c.getBoolean("audio"),
                if (c.isNull("estimatedBytes")) null else c.getLong("estimatedBytes"),
                if (c.isNull("bitrateKbps")) null else c.getInt("bitrateKbps"),
                if (c.isNull("language")) null else c.getString("language"),
            )
        require(choice.extension.matches(Regex("[a-zA-Z0-9]{1,8}")))
        require(choice.id.matches(Regex("[a-zA-Z0-9_.-]{1,80}(?:\\+[a-zA-Z0-9_.-]{1,80})?")))
        val tree = if (json.isNull("tree")) null else Uri.parse(json.getString("tree"))
        require(tree == null || tree.scheme == "content")
        val id = json.getString("id")
        require(UUID.fromString(id).toString() == id.lowercase())
        return DownloadTask(
            Analysis(
                source,
                json.getString("title"),
                listOf(choice),
                json.getBoolean("direct"),
                if (json.isNull("size")) null else json.getLong("size"),
            ),
            choice,
            tree,
            id,
            TaskPriority.valueOf(json.optString("priority", "NORMAL")),
            json.optBoolean("paused", false),
            json.optLong("notBefore", 0),
        )
    }
}

internal object TransferFiles {
    fun directory(context: Context, id: String): File {
        require(UUID.fromString(id).toString() == id.lowercase())
        return File(File(context.noBackupFilesDir, "transfers"), id)
    }

    fun discard(context: Context, id: String) {
        directory(context, id).deleteRecursively()
    }
}
