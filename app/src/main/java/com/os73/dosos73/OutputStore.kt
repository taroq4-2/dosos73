package com.os73.dosos73

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class OutputStore(private val context: Context) {
    fun validate(file: File, kind: Kind, expectedHeight: Int? = null, expectsAudio: Boolean = false) {
        require(file.isFile && file.length() > 0)
        if (kind != Kind.FILE) MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
            if (kind == Kind.VIDEO) require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
            if (kind == Kind.AUDIO || expectsAudio) require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
            expectedHeight?.let { require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() == it) }
        }
    }
    suspend fun save(file: File, task: DownloadTask): Pair<Uri, String> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(task.choice.extension) ?: "application/octet-stream"
        val name = Safety.filename(task.analysis.title, task.choice.extension)
        if (task.tree == null) StoragePolicy.requireAvailable(android.os.StatFs(context.cacheDir.absolutePath).availableBytes, file.length())
        var document: DocumentFile? = null
        val uri = if (task.tree == null) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/dosos73")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw IOException("Cannot create output")
        } else {
            val folder = DocumentFile.fromTreeUri(context, task.tree) ?: throw IOException("Destination unavailable")
            document = folder.createFile(mime, ".dosos73-${UUID.randomUUID()}.partial") ?: throw IOException("Cannot create output")
            document!!.uri
        }
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                    }
                }
                output.flush()
            } ?: throw IOException("Cannot write output")
            currentCoroutineContext().ensureActive()
            // Verify the staged destination before exposing it as a completed download.
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize >= 0 && descriptor.statSize != file.length()) throw IOException("Output size mismatch")
            } ?: throw IOException("Output cannot be opened")
            val finalUri = if (document != null) {
                // Unique suffix prevents provider-dependent replacement of an existing user file.
                val unique = name.substringBeforeLast('.') + "-${UUID.randomUUID().toString().take(8)}." + task.choice.extension
                if (!document!!.renameTo(unique)) throw IOException("Cannot finalize output")
                document!!.uri
            } else {
                if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1)
                    throw IOException("Cannot publish output")
                uri
            }
            resolver.openFileDescriptor(finalUri, "r")?.use { descriptor ->
                if (descriptor.statSize >= 0 && descriptor.statSize != file.length()) throw IOException("Output size mismatch")
            } ?: throw IOException("Output cannot be opened")
            runCatching { LibraryRepository.remember(context, finalUri, mime, name, file.length()) }
            finalUri to mime
        } catch (error: Exception) {
            runCatching { if (document != null) document!!.delete() else resolver.delete(uri, null, null) }
            throw error
        }
    }
}

