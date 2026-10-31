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

    private fun read(context: Context): List<SavedFile> {
        val input = try { index(context).openRead() }
            catch (_: java.io.FileNotFoundException) { return emptyList() }
        return android.util.JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
            val files = ArrayList<SavedFile>()
            reader.beginArray()
            while (reader.hasNext()) {
                var uri: String? = null; var name: String? = null; var mime: String? = null
                var bytes: Long? = null; var date: Long? = null
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "uri" -> uri = reader.nextString()
                    "name" -> name = reader.nextString()
                    "mime" -> mime = reader.nextString()
                    "bytes" -> bytes = reader.nextLong()
                    "date" -> date = reader.nextLong()
                    else -> reader.skipValue()
                }
                reader.endObject()
                files.add(SavedFile(requireNotNull(uri), requireNotNull(name), requireNotNull(mime),
                    requireNotNull(bytes), requireNotNull(date)))
            }
            reader.endArray()
            require(reader.peek() == android.util.JsonToken.END_DOCUMENT)
            files
        }
    }

    private fun write(context: Context, files: List<SavedFile>) {
        val atomic = index(context)
        val output = atomic.startWrite()
        try {
            val writer = android.util.JsonWriter(output.writer(Charsets.UTF_8))
            writer.beginArray()
            files.forEach {
                writer.beginObject().name("uri").value(it.uri).name("name").value(it.name)
                    .name("mime").value(it.mime).name("bytes").value(it.bytes).name("date").value(it.date).endObject()
            }
            writer.endArray(); writer.flush()
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }

    suspend fun refresh(context: Context) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                // A revoked grant or offline provider must not erase the durable SAF index.
                val saved = read(context)
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
                val next = (discovered + saved).distinctBy { it.uri }.sortedByDescending { it.date }
                if (next != saved) write(context, next)
                mutable.value = next
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
                val next = (listOf(entry) + read(context)).distinctBy { it.uri }
                write(context, next)
                mutable.value = next
            }
        }

    suspend fun rename(context: Context, item: SavedFile, title: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val clean = Safety.requireFilename(title)
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
                        android.provider.DocumentsContract.renameDocument(context.contentResolver, uri, clean)
                            ?: error("Document rename unavailable")
                    }
                // The rename already happened. Optional metadata must not discard its returned URI.
                val actualName = try {
                    context.contentResolver.query(newUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (_: Exception) { null } ?: clean
                val changed = item.copy(uri = newUri.toString(), name = actualName)
                val next = mutable.value.map { if (it.uri == item.uri) changed else it }
                // Keep the usable reference in this session even if the following disk write fails.
                // Provider rename and this private index are not one durable transaction.
                mutable.value = next
                cache.remove(item.uri)
                write(context, next)
                migrateHiddenUri(context, item.uri, newUri.toString())
                LibraryPreferences.migrateUri(context, item.uri, newUri.toString())
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
                val next = mutable.value.filterNot { it.uri == item.uri }
                write(context, next)
                mutable.value = next
                cache.remove(item.uri)
                LibraryPreferences.clearUri(context, item.uri)
            }
        }

    /** Explicitly remove metadata only after a fresh, confident local-missing result. */
    suspend fun forgetMissing(context: Context, item: SavedFile) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(FileAccess.inspect(context, Uri.parse(item.uri)) == FileAvailability.MISSING)
            val next = read(context).filterNot { it.uri == item.uri }
            write(context, next)
            mutable.value = next
            cache.remove(item.uri)
            LibraryPreferences.clearUri(context, item.uri)
        }
    }

    fun hidden(context: Context): Set<String> =
        context
            .getSharedPreferences("library", Context.MODE_PRIVATE)
            .getStringSet("hidden_history", emptySet())
            .orEmpty()
            .toSet()

    // Called only while holding lock, just like hideHistory and clearHistory.
    private fun migrateHiddenUri(context: Context, previous: String, next: String) {
        if (previous == next) return
        val current = hidden(context)
        if (previous !in current) return
        check(
            context.getSharedPreferences("library", Context.MODE_PRIVATE).edit()
                .putStringSet("hidden_history", current - previous + next)
                .commit()
        )
    }

    suspend fun hideHistory(context: Context, item: SavedFile) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                check(
                    context
                        .getSharedPreferences("library", Context.MODE_PRIVATE)
                        .edit()
                        .putStringSet("hidden_history", hidden(context) + item.uri)
                        .commit()
                )
            }
        }

    suspend fun clearHistory(context: Context) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                check(
                    context
                        .getSharedPreferences("library", Context.MODE_PRIVATE)
                        .edit()
                        .putStringSet(
                            "hidden_history",
                            hidden(context) + mutable.value.map { it.uri },
                        )
                        .commit()
                )
            }
        }

    fun clearThumbnailCache() {
        cache.evictAll()
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
