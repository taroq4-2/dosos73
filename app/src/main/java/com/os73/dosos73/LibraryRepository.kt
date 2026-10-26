package com.os73.dosos73

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.LruCache
import android.util.Size
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Only app-owned MediaStore rows and explicitly published document URIs are indexed. */
data class SavedFile(
    val uri: String,
    val name: String,
    val mime: String,
    val bytes: Long,
    val date: Long,
) {
    val kind
        get() =
            when {
                mime.startsWith("video/") -> Kind.VIDEO
                mime.startsWith("audio/") -> Kind.AUDIO
                else -> Kind.FILE
            }

    val extension
        get() = name.substringAfterLast('.', "").uppercase().take(8)
}

internal object LibraryRepository {
    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<SavedFile>>(emptyList())
    val files = mutable.asStateFlow()
    private val cache =
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

    private fun index(context: Context) = AtomicFile(File(context.filesDir, "saved-files.json"))

    private fun read(context: Context): List<SavedFile> =
        runCatching {
                val array =
                    JSONArray(index(context).openRead().bufferedReader().use { it.readText() })
                (0 until array.length()).map { i ->
                    array.getJSONObject(i).let {
                        SavedFile(
                            it.getString("uri"),
                            it.getString("name"),
                            it.getString("mime"),
                            it.getLong("bytes"),
                            it.getLong("date"),
                        )
                    }
                }
            }
            .getOrDefault(emptyList())

    private fun write(context: Context, files: List<SavedFile>) {
        val array = JSONArray()
        files.take(2000).forEach {
            array.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("name", it.name)
                    .put("mime", it.mime)
                    .put("bytes", it.bytes)
                    .put("date", it.date)
            )
        }
        val atomic = index(context)
        val output = atomic.startWrite()
        try {
            output.write(array.toString().toByteArray())
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    suspend fun refresh(context: Context) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val saved =
                    read(context).filter {
                        runCatching {
                                context.contentResolver
                                    .openFileDescriptor(Uri.parse(it.uri), "r")
                                    ?.use { true } ?: false
                            }
                            .getOrDefault(false)
                    }
                val discovered = mutableListOf<SavedFile>()
                context.contentResolver
                    .query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.MIME_TYPE,
                            MediaStore.MediaColumns.SIZE,
                            MediaStore.MediaColumns.DATE_MODIFIED,
                        ),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                        arrayOf("Download/dosos73/", context.packageName),
                        null,
                    )
                    ?.use { cursor ->
                        while (cursor.moveToNext()) discovered.add(
                            SavedFile(
                                android.content.ContentUris.withAppendedId(
                                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                        cursor.getLong(0),
                                    )
                                    .toString(),
                                cursor.getString(1),
                                cursor.getString(2) ?: "application/octet-stream",
                                cursor.getLong(3),
                                cursor.getLong(4) * 1000,
                            )
                        )
                    }
                mutable.value =
                    (discovered + saved).distinctBy { it.uri }.sortedByDescending { it.date }
                write(context, mutable.value)
            }
        }

    suspend fun remember(context: Context, uri: Uri, mime: String, fallback: String, bytes: Long) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                var name = fallback
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) name = it.getString(0) ?: fallback }
                val entry = SavedFile(uri.toString(), name, mime, bytes, System.currentTimeMillis())
                mutable.value = (listOf(entry) + read(context)).distinctBy { it.uri }
                write(context, mutable.value)
            }
        }

    suspend fun rename(context: Context, item: SavedFile, title: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val clean = title.trim()
                require(
                    clean.isNotEmpty() &&
                        clean.length <= 180 &&
                        clean.none { it == '/' || it == '\\' || it.isISOControl() }
                )
                val uri = Uri.parse(item.uri)
                val newUri =
                    if (uri.authority == MediaStore.AUTHORITY) {
                        check(
                            context.contentResolver.update(
                                uri,
                                ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, clean)
                                },
                                null,
                                null,
                            ) == 1
                        )
                        uri
                    } else {
                        val document =
                            DocumentFile.fromSingleUri(context, uri)
                                ?: error("Document unavailable")
                        check(document.renameTo(clean))
                        document.uri
                    }
                val changed = item.copy(uri = newUri.toString(), name = clean)
                mutable.value = mutable.value.map { if (it.uri == item.uri) changed else it }
                write(context, mutable.value)
                cache.remove(item.uri)
            }
        }

    suspend fun delete(context: Context, item: SavedFile) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val uri = Uri.parse(item.uri)
                check(
                    if (uri.authority == MediaStore.AUTHORITY)
                        context.contentResolver.delete(uri, null, null) > 0
                    else DocumentFile.fromSingleUri(context, uri)?.delete() == true
                )
                mutable.value = mutable.value.filterNot { it.uri == item.uri }
                write(context, mutable.value)
                cache.remove(item.uri)
            }
        }

    fun hidden(context: Context): Set<String> =
        context
            .getSharedPreferences("library", Context.MODE_PRIVATE)
            .getStringSet("hidden_history", emptySet())
            .orEmpty()
            .toSet()

    suspend fun hideHistory(context: Context, item: SavedFile) =
        withContext(Dispatchers.IO) {
            check(
                context
                    .getSharedPreferences("library", Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet("hidden_history", hidden(context) + item.uri)
                    .commit()
            )
        }

    suspend fun thumbnail(context: Context, item: SavedFile): Bitmap? =
        withContext(Dispatchers.IO) {
            cache.get(item.uri)
                ?: runCatching {
                        context.contentResolver
                            .loadThumbnail(Uri.parse(item.uri), Size(192, 192), null)
                            .also { cache.put(item.uri, it) }
                    }
                    .getOrNull()
        }
}

/** Waiting and in-flight ownership share one atomic journal, avoiding a dequeue/crash gap. */
internal object DownloadQueue {
    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks = mutable.asStateFlow()
    private var loaded = false
    private var active: DownloadTask? = null

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "waiting-tasks.json"))

    private fun load(context: Context) {
        if (loaded) return
        mutable.value =
            runCatching {
                    val text = file(context).openRead().bufferedReader().use { it.readText() }
                    val array =
                        if (text.trimStart().startsWith("[")) JSONArray(text)
                        else {
                            val journal = JSONObject(text)
                            active = journal.optJSONObject("active")?.let(::decodeTask)
                            journal.getJSONArray("waiting")
                        }
                    (0 until array.length()).map { decodeTask(array.getJSONObject(it)) }
                }
                .getOrDefault(emptyList())
        loaded = true
    }

    private fun save(context: Context) {
        val array = JSONArray()
        mutable.value.forEach { array.put(encodeTask(it)) }
        val atomic = file(context)
        val output = atomic.startWrite()
        try {
            output.write(
                JSONObject()
                    .put("waiting", array)
                    .put("active", active?.let(::encodeTask))
                    .toString()
                    .toByteArray()
            )
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    suspend fun initialize(context: Context) =
        withContext(Dispatchers.IO) { lock.withLock { load(context) } }

    suspend fun enqueue(context: Context, task: DownloadTask) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                require(mutable.value.size < 50)
                if (active?.id != task.id && mutable.value.none { it.id == task.id }) {
                    mutable.value = mutable.value + task
                    save(context)
                }
            }
        }

    suspend fun take(context: Context): DownloadTask? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                active?.let {
                    return@withLock it
                }
                val task = mutable.value.firstOrNull()
                if (task != null) {
                    active = task
                    mutable.value = mutable.value.drop(1)
                    save(context)
                }
                task
            }
        }

    suspend fun rememberActive(context: Context, task: DownloadTask) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                check(active == null || active?.id == task.id)
                active = task
                mutable.value = mutable.value.filterNot { it.id == task.id }
                save(context)
            }
        }

    suspend fun finish(context: Context, id: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                load(context)
                if (active?.id == id) {
                    active = null
                    save(context)
                }
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
                mutable.value = mutable.value.filterNot { it.id == id }
                save(context)
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
            .put(
                "choice",
                JSONObject()
                    .put("id", task.choice.id)
                    .put("kind", task.choice.kind.name)
                    .put("ext", task.choice.extension)
                    .put("quality", task.choice.quality)
                    .put("height", task.choice.expectedHeight)
                    .put("audio", task.choice.expectsAudio),
            )

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
            )
        return DownloadTask(
            Analysis(
                source,
                json.getString("title"),
                listOf(choice),
                json.getBoolean("direct"),
                if (json.isNull("size")) null else json.getLong("size"),
            ),
            choice,
            if (json.isNull("tree")) null else Uri.parse(json.getString("tree")),
            json.getString("id"),
        )
    }
}
