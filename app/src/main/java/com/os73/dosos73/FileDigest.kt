package com.os73.dosos73

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

internal object FileDigest {
    data class Result(val bytes: Long, val sha256: String)
    suspend fun compute(context: Context, uri: Uri): Result = coroutineScope {
        require(uri.scheme == "content")
        val signal = CancellationSignal()
        val opened = AtomicReference<InputStream?>()
        val cleanup = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally {
                signal.cancel()
                runCatching { opened.getAndSet(null)?.close() }
            }
        }
        try {
            runInterruptible(Dispatchers.IO) {
                val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                    ?: throw java.io.IOException("File unavailable")
                descriptor.use { asset ->
                    asset.createInputStream().use { input ->
                        opened.set(input)
                        try {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val actual = StreamIntegrity.fingerprint(input)
                            val chars = "0123456789abcdef"
                            val text = CharArray(64)
                            actual.sha256.forEachIndexed { index, byte ->
                                val n = byte.toInt() and 255
                                text[index * 2] = chars[n ushr 4]; text[index * 2 + 1] = chars[n and 15]
                            }
                            Result(actual.bytes, String(text))
                        } finally { opened.compareAndSet(input, null) }
                    }
                }
            }
        } finally { cleanup.cancelAndJoin() }
    }
    fun normalizedExpected(value: String): String? = value.trim().lowercase(java.util.Locale.ROOT)
        .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
}
