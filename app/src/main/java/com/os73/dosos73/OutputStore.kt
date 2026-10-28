package com.os73.dosos73

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class OutputStore(private val context: Context) {
    suspend fun recover(task: DownloadTask): Pair<Uri, String>? =
        withContext(Dispatchers.IO) {
            PublicationReceipt(context, task.id).recover()?.also { (uri, mime) ->
                val bytes =
                    context.contentResolver.openFileDescriptor(uri, "r")?.use {
                        it.statSize.coerceAtLeast(0)
                    } ?: 0L
                LibraryRepository.remember(context, uri, mime, task.analysis.title, bytes)
            }
        }

    fun forget(id: String) = PublicationReceipt(context, id).forget()

    fun preflight(tree: Uri?) {
        if (tree == null) return
        val folder =
            DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Destination unavailable")
        if (!folder.isDirectory || !folder.canWrite()) throw IOException("Destination is read-only")
        val probe =
            folder.createFile("application/octet-stream", ".dosos73-check-${UUID.randomUUID()}")
                ?: throw IOException("Cannot create output")
        try {
            context.contentResolver.openOutputStream(probe.uri, "w")?.use { it.flush() }
                ?: throw IOException("Cannot write output")
        } finally {
            if (!probe.delete()) throw IOException("Cannot remove destination probe")
        }
    }

    fun validate(
        file: File,
        kind: Kind,
        expectedHeight: Int? = null,
        expectsAudio: Boolean = false,
    ) {
        require(file.isFile && file.length() > 0)
        if (kind != Kind.FILE)
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                require(
                    (retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0) > 0
                )
                if (kind == Kind.VIDEO)
                    require(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) ==
                            "yes"
                    )
                if (kind == Kind.AUDIO || expectsAudio)
                    require(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) ==
                            "yes"
                    )
                expectedHeight?.let {
                    val rotation =
                        retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                            ?.toIntOrNull() ?: 0
                    val heightKey =
                        if (rotation % 180 != 0) MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                        else MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                    require(retriever.extractMetadata(heightKey)?.toIntOrNull() == it)
                }
            }
    }

    suspend fun save(
        file: File,
        task: DownloadTask,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Pair<Uri, String> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val expected = file.inputStream().use(StreamIntegrity::fingerprint)
            val mime =
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(task.choice.extension)
                    ?: "application/octet-stream"
            val name = Safety.filename(task.analysis.title, task.choice.extension)
            if (task.tree == null)
                StoragePolicy.requireAvailable(
                    android.os.StatFs(context.cacheDir.absolutePath).availableBytes,
                    file.length(),
                )
            var document: DocumentFile? = null
            val uri =
                if (task.tree == null) {
                    resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/dosos73")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    ) ?: throw IOException("Cannot create output")
                } else {
                    val folder =
                        DocumentFile.fromTreeUri(context, task.tree)
                            ?: throw IOException("Destination unavailable")
                    document =
                        folder.createFile(mime, ".dosos73-${UUID.randomUUID()}.partial")
                            ?: throw IOException("Cannot create output")
                    document!!.uri
                }
            val receipt = PublicationReceipt(context, task.id)
            var committed = false
            try {
                receipt.record(uri, mime, expected, false)
                resolver.openOutputStream(uri, "w")?.use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var last = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            output.write(buffer, 0, n)
                            copied += n
                            val now = System.nanoTime()
                            if (now - last > 250_000_000L) {
                                progress(copied, file.length())
                                last = now
                            }
                        }
                        progress(copied, file.length())
                    }
                    output.flush()
                } ?: throw IOException("Cannot write output")
                currentCoroutineContext().ensureActive()
                // Verify the staged destination before exposing it as a completed download.
                resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    if (descriptor.statSize >= 0 && descriptor.statSize != file.length())
                        throw IOException("Output size mismatch")
                } ?: throw IOException("Output cannot be opened")
                resolver.openInputStream(uri)?.use { input ->
                    StreamIntegrity.requireSame(expected, StreamIntegrity.fingerprint(input))
                } ?: throw IOException("Output cannot be verified")
                // MediaStore URI is stable: persist verified ownership before making it visible.
                if (document == null) receipt.record(uri, mime, expected, true)
                val finalUri =
                    if (document != null) {
                        // Unique suffix prevents provider-dependent replacement of an existing user
                        // file.
                        val unique =
                            name.substringBeforeLast('.') +
                                "-${UUID.randomUUID().toString().take(8)}." +
                                task.choice.extension
                        if (!document!!.renameTo(unique))
                            throw IOException("Cannot finalize output")
                        document!!.uri
                    } else {
                        if (
                            resolver.update(
                                uri,
                                ContentValues().apply {
                                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                                },
                                null,
                                null,
                            ) != 1
                        )
                            throw IOException("Cannot publish output")
                        uri
                    }
                committed = true
                receipt.record(finalUri, mime, expected, true)
                resolver.openFileDescriptor(finalUri, "r")?.use { descriptor ->
                    if (descriptor.statSize >= 0 && descriptor.statSize != file.length())
                        throw IOException("Output size mismatch")
                } ?: throw IOException("Output cannot be opened")
                runCatching {
                    LibraryRepository.remember(context, finalUri, mime, name, file.length())
                }
                finalUri to mime
            } catch (error: Exception) {
                if (!committed) {
                    runCatching {
                            if (document != null) check(document!!.delete())
                            else check(resolver.delete(uri, null, null) == 1)
                        }
                        .onSuccess { receipt.forget() }
                }
                throw error
            }
        }
}
