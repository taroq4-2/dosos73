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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class OutputStore(
    private val context: Context,
    private val onCommitted: (Uri, String) -> Unit = { _, _ -> },
) {
    suspend fun recover(task: DownloadTask): Pair<Uri, String>? =
        withContext(Dispatchers.IO) {
            val receipt = PublicationReceipt(context, task.id)
            receipt.recover()?.also { (uri, mime) ->
                // Deliver the commit before a cancelled dispatcher return can discard it.
                onCommitted(uri, mime)
                runCatching {
                    val bytes = checkNotNull(receipt.recoveredBytes)
                    LibraryRepository.remember(context, uri, mime, task.analysis.title, bytes)
                }
            }
        }

    fun forget(id: String) = PublicationReceipt(context, id).forget()

    fun preflight(tree: Uri?) {
        if (tree == null) return
        val folder =
            DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Destination unavailable")
        // API29 may deny an explicit URI-grant lookup for a provider owned by this
        // UID even though same-UID provider access is allowed. Retain both ownership
        // and declared directory-create capability; external providers still need a grant.
        val ownsProvider = runCatching {
            context.packageManager.resolveContentProvider(folder.uri.authority!!, 0)
                ?.applicationInfo?.uid == android.os.Process.myUid()
        }.getOrDefault(false)
        val ownedCreate = ownsProvider && runCatching {
            context.contentResolver.query(folder.uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
                ?.use { cursor -> cursor.moveToFirst() &&
                    (cursor.getInt(0) and android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0 }
                ?: false
        }.getOrDefault(false)
        if (!folder.isDirectory || (!folder.canWrite() && !ownedCreate))
            throw PipelineException(Stage.PUBLISH, FailureCode.STORAGE_PERMISSION_DENIED)
        // Actual creation is covered by the durable publication plan in save().
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
            val receipt = PublicationReceipt(context, task.id)
            val temporary = receipt.prepare(task.tree, mime, expected, name, task.choice.extension)
            var document: DocumentFile? = null
            val uri =
                if (task.tree == null) {
                    resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, temporary)
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
                        folder.createFile(mime, temporary)
                            ?: throw IOException("Cannot create output")
                    document!!.uri
                }
            var verified = false
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
                currentCoroutineContext().ensureActive()
                verified = true // Preserve on any ambiguous provider operation from this point.
                receipt.markVerified()
                val finalUri = receipt.publishVerified(uri).first
                // This callback is synchronous: the caller owns the committed URI even if
                // withContext subsequently resumes it with CancellationException.
                onCommitted(finalUri, mime)
                runCatching {
                    LibraryRepository.remember(context, finalUri, mime, name, file.length())
                }
                finalUri to mime
            } catch (error: Exception) {
                if (!verified) {
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
