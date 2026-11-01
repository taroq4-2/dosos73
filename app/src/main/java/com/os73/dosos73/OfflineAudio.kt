package com.os73.dosos73

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

data class LocalAudioInput(val uri: Uri, val name: String, val bytes: Long?, val durationMs: Long)

class OfflineAudioException(val messageResource: Int, cause: Throwable? = null) : IOException(cause)

/** Reads a user-selected video once; the original is never opened for writing. */
class OfflineAudio(private val context: Context) {
    private class Operation {
        val signal = CancellationSignal()
        val cancelled = AtomicBoolean(false)
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        val process = AtomicReference<NativeProcessSession?>()
        fun checkActive() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        }
        fun cancel() {
            cancelled.set(true)
            runCatching { signal.cancel() }
            runCatching { descriptor.getAndSet(null)?.close() }
            process.get()?.cancel()
        }
    }
    private val operation = AtomicReference<Operation?>()

    fun cancel() { operation.get()?.cancel() }

    private suspend fun <T> invocation(work: (Operation) -> T): T = coroutineScope {
        val active = Operation()
        check(operation.compareAndSet(null, active)) { "Offline converter already active" }
        val guardian = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { active.cancel() }
        }
        try {
            runInterruptible(Dispatchers.IO) { work(active) }
        } finally {
            withContext(NonCancellable) { guardian.cancelAndJoin() }
            active.cancel()
            operation.compareAndSet(active, null)
        }
    }

    private fun open(uri: Uri, active: Operation): ParcelFileDescriptor {
        require(uri.scheme == "content") { "A selected content URI is required" }
        active.checkActive()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r", active.signal)
            ?: throw OfflineAudioException(R.string.offline_error_read)
        active.descriptor.set(descriptor)
        try { active.checkActive() }
        catch (error: Throwable) { active.descriptor.getAndSet(null)?.close(); throw error }
        return descriptor
    }

    private fun nameAndSize(uri: Uri, active: Operation): Pair<String, Long?> {
        var name = context.getString(R.string.offline_video_name)
        var bytes: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null, active.signal)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex).filter { !it.isISOControl() }.take(200).ifBlank { name }
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) bytes = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        active.checkActive()
        return name to bytes
    }

    private fun metadata(descriptor: ParcelFileDescriptor): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(descriptor.fileDescriptor)
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            if (!mime.startsWith("video/") || retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes")
                throw OfflineAudioException(R.string.offline_error_video)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes")
                throw OfflineAudioException(R.string.offline_error_no_audio)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        } catch (error: OfflineAudioException) { throw error }
        catch (error: Exception) { throw OfflineAudioException(R.string.offline_error_video, error) }
        finally { retriever.release() }
    }

    /** Metadata and provider access stay off the main thread and share cancellation ownership. */
    suspend fun inspect(uri: Uri): LocalAudioInput = invocation { active ->
        try {
            val (name, bytes) = nameAndSize(uri, active)
            if (bytes == 0L) throw OfflineAudioException(R.string.offline_error_empty)
            val descriptor = open(uri, active)
            // A cloud provider may return a pipe. Do not consume or block on it for preview;
            // conversion validates the complete private copy before FFmpeg sees any input.
            val duration = try { if (descriptor.statSize < 0) 0L else metadata(descriptor) }
                finally { active.descriptor.compareAndSet(descriptor, null); descriptor.close() }
            active.checkActive()
            LocalAudioInput(uri, name, bytes, duration)
        } catch (error: Exception) {
            active.checkActive()
            if (error is OfflineAudioException || error is CancellationException) throw error
            throw OfflineAudioException(R.string.offline_error_read, error)
        }
    }

    suspend fun convert(source: Uri, directory: File, progress: (Float?, Boolean) -> Unit): File = invocation { active ->
        check(directory.mkdirs() || directory.isDirectory)
        // A private, fixed file name prevents a provider display name from becoming a path or option.
        val input = File(directory, "offline-input")
        val output = File(directory, "offline-audio.mp3")
        var successful = false
        try {
            val (_, expected) = nameAndSize(source, active)
            if (expected == 0L) throw OfflineAudioException(R.string.offline_error_empty)
            var availableAtPoll = StatFs(directory.absolutePath).availableBytes
            var bytesAtPoll = 0L
            var lastSpacePoll = System.nanoTime()
            StoragePolicy.requireAvailable(availableAtPoll, expected)
            progress(0f, false)
            val descriptor = open(source, active)
            var copied = 0L
            var lastCopyProgress = System.nanoTime()
            var reportedCopyBytes = 0L
            try {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    input.outputStream().use { destination ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            active.checkActive()
                            val count = stream.read(buffer)
                            active.checkActive()
                            if (count < 0) break
                            if (count == 0) continue
                            val now = System.nanoTime()
                            if (copied - bytesAtPoll >= 1048576 || now - lastSpacePoll >= 250_000_000L) {
                                availableAtPoll = StatFs(directory.absolutePath).availableBytes
                                bytesAtPoll = copied
                                lastSpacePoll = now
                            }
                            // Charge every write against the last observed space budget so the
                            // 32 MiB reserve survives between filesystem polls. Real write errors
                            // still detect concurrent consumers or a provider/storage failure.
                            StoragePolicy.requireAvailable(
                                (availableAtPoll - (copied - bytesAtPoll)).coerceAtLeast(0), count.toLong(),
                            )
                            destination.write(buffer, 0, count)
                            copied += count
                            // The first positive event keeps cancellation responsive in fixtures;
                            // later UI/state updates are limited to roughly four per second.
                            if (reportedCopyBytes == 0L || now - lastCopyProgress >= 250_000_000L) {
                                progress(expected?.takeIf { it > 0 }?.let { (copied.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }, false)
                                lastCopyProgress = System.nanoTime()
                                reportedCopyBytes = copied
                            }
                        }
                    }
                }
            } finally { active.descriptor.compareAndSet(descriptor, null); runCatching { descriptor.close() } }
            if (copied == 0L) throw OfflineAudioException(R.string.offline_error_empty)
            if (expected != null && copied != expected) throw OfflineAudioException(R.string.offline_error_read)
            active.checkActive()
            progress(1f, false)
            active.checkActive()
            val duration = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { metadata(it) }
            active.checkActive()
            // MP3's maximum 320 kb/s plus room for headers, without trusting provider size.
            val maximumOutput = duration.takeIf { it > 0 && it <= (Long.MAX_VALUE - 1048576) / 40 }
                ?.let { it * 40 + 1048576 }
            StoragePolicy.requireAvailable(StatFs(directory.absolutePath).availableBytes, maximumOutput)
            EngineRuntime.initialize(context)
            active.checkActive()
            // Always refresh from the APK, never execute code supplied by a document provider.
            val script = File(directory, "offline-convert.py")
            context.assets.open("offline_audio.py").use { from -> script.outputStream().use { from.copyTo(it, 65536) } }
            val session = NativeProcessSession(context, script)
            active.process.set(session)
            try {
                active.checkActive()
                progress(null, true)
                session.execute(listOf(
                    File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath,
                    input.absolutePath, output.absolutePath, duration.toString(),
                )) { percent, processing ->
                    active.checkActive()
                    StoragePolicy.requireAvailable(StatFs(directory.absolutePath).availableBytes, null)
                    progress(percent, processing)
                }
            } catch (error: Exception) {
                active.checkActive()
                if (error is CancellationException || error is PipelineException) throw error
                throw OfflineAudioException(R.string.offline_error_convert, error)
            } finally { session.close(); active.process.compareAndSet(session, null); script.delete() }
            active.checkActive()
            if (!output.isFile || output.length() <= 0) throw OfflineAudioException(R.string.offline_error_empty)
            successful = true
            output
        } catch (error: Exception) {
            active.checkActive()
            if (error is OfflineAudioException || error is PipelineException || error is CancellationException) throw error
            throw OfflineAudioException(R.string.offline_error_read, error)
        } finally {
            input.delete()
            if (!successful) output.delete()
        }
    }

    companion object {
        val CHOICE = Choice("local-mp3", Kind.AUDIO, "mp3", "MP3 V0", expectsAudio = true)
    }
}
