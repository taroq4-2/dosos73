package com.os73.dosos73

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.*

internal enum class FileAvailability { AVAILABLE, MISSING, PERMISSION_REQUIRED, UNAVAILABLE }

/** Visible rows only. No content download, permanent scanner or index deletion. */
internal object FileAccess {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)
    suspend fun inspect(context: Context, uri: Uri): FileAvailability = coroutineScope {
        val signal = CancellationSignal()
        val operation = async(dispatcher) { inspectBlocking(context, uri, signal) }
        try { withTimeoutOrNull(3000) { operation.await() } ?: FileAvailability.UNAVAILABLE }
        finally { signal.cancel(); operation.cancel() }
    }
    private fun inspectBlocking(context: Context, uri: Uri, signal: CancellationSignal): FileAvailability {
        if (uri.scheme != "content") return FileAvailability.UNAVAILABLE
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use {
                if (it.moveToFirst()) FileAvailability.AVAILABLE
                else if (knownLocalStorageMounted(uri)) FileAvailability.MISSING else FileAvailability.UNAVAILABLE
            } ?: FileAvailability.UNAVAILABLE
        } catch (_: SecurityException) { FileAvailability.PERMISSION_REQUIRED }
        catch (_: java.io.FileNotFoundException) {
            if (knownLocalStorageMounted(uri)) FileAvailability.MISSING else FileAvailability.UNAVAILABLE
        } catch (_: Exception) { FileAvailability.UNAVAILABLE }
    }
    private fun knownLocalStorageMounted(uri: Uri): Boolean {
        if (android.os.Environment.getExternalStorageState() != android.os.Environment.MEDIA_MOUNTED) return false
        if (uri.authority == MediaStore.AUTHORITY) return true
        return uri.authority == "com.android.externalstorage.documents" &&
            runCatching { DocumentsContract.getDocumentId(uri).substringBefore(':') == "primary" }.getOrDefault(false)
    }
}
