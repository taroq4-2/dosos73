package com.os73.dosos73

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID

/** One child process group, bounded diagnostics and no shell or permanent monitoring thread. */
internal class NativeProcessSession(
    private val context: Context,
    private val entry: File = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp"),
) : Closeable {
    val id = UUID.randomUUID().toString()
    private val groupFile = File(context.cacheDir, "native-session-$id.pid")
    private val lease = ProcessLease(::terminate)
    @Volatile private var proxy: PublicProxy? = null

    fun attachProxy(value: PublicProxy) {
        proxy = value
        try { lease.checkActive() } catch (error: IOException) { value.close(); throw error }
    }

    fun cancel() {
        lease.cancel()
        runCatching { proxy?.close() }
    }

    private fun terminate(process: Process) {
        if (process.isAlive) {
            // The trusted bootstrap records its own group before loading yt-dlp or any helper.
            val group = runCatching {
                if (groupFile.length() !in 1..20) null else groupFile.readText().toIntOrNull()?.takeIf { it > 1 }
            }.getOrNull()
            if (group != null) runCatching { Os.kill(-group, OsConstants.SIGKILL) }
            process.destroyForcibly()
        }
    }

    fun execute(arguments: List<String>, progress: ((Float?, Boolean) -> Unit)? = null) {
        lease.checkActive()
        val native = context.applicationInfo.nativeLibraryDir
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val python = File(base, "packages/python/usr")
        val command = listOf("$native/libpython.so", "-u", "-c", BOOTSTRAP,
            groupFile.absolutePath, entry.absolutePath) + arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment().apply {
                // The explicit authenticated proxy and bundled trust store own this session.
                keys.filter { it.lowercase() in setOf("http_proxy", "https_proxy", "all_proxy", "no_proxy",
                    "requests_ca_bundle", "curl_ca_bundle", "ssl_cert_dir", "pythonpath", "pythonstartup") }
                    .toList().forEach { remove(it) }
                put("LD_LIBRARY_PATH", "$native:$python/lib:$base/packages/ffmpeg/usr/lib")
                put("SSL_CERT_FILE", "$python/etc/tls/cert.pem")
                put("PYTHONHOME", python.absolutePath)
                put("HOME", python.absolutePath)
                put("PATH", System.getenv("PATH").orEmpty() + ":$native")
                put("TMPDIR", context.cacheDir.absolutePath)
            }
        }.start()
        val tail = StringBuilder()
        val line = StringBuilder()
        var processing = false
        var lastProgress = 0L
        val buffer = ByteArray(4096)
        fun accept(text: String) {
            tail.append(text)
            if (tail.length > 16384) tail.delete(0, tail.length - 16384)
            for (character in text) {
                if (character == '\n' || character == '\r') {
                    val current = line.toString()
                    line.setLength(0)
                    val wasProcessing = processing
                    if (current.contains("[Merger]") || current.contains("[ExtractAudio]")) processing = true
                    val percent = PERCENT.find(current)?.groupValues?.get(1)?.toFloatOrNull()
                    val now = System.nanoTime()
                    if (progress != null && (processing != wasProcessing ||
                        (percent != null && now - lastProgress >= 250_000_000L))) {
                        lastProgress = now
                        progress(percent?.takeIf { it in 0f..100f }?.div(100f), processing)
                    }
                } else if (line.length < 8192) line.append(character)
            }
        }
        try {
            lease.attach(process)
            while (true) {
                lease.checkActive()
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                if (count > 0) accept(String(buffer, 0, count, Charsets.UTF_8))
            }
            lease.checkActive()
            if (process.waitFor() != 0) throw IOException(tail.toString().takeLast(16384))
        } finally {
            terminate(process)
            lease.detach(process)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            groupFile.delete()
        }
    }

    override fun close() { cancel(); groupFile.delete() }

    companion object {
        private val PERCENT = Regex("\\[download]\\s+([0-9]+(?:\\.[0-9]+)?)%")
        private val BOOTSTRAP = """
            import os, runpy, sys
            try:
                os.setsid()
            except PermissionError:
                if os.getpgrp() != os.getpid(): raise
            if os.getpgrp() != os.getpid(): raise RuntimeError('Process group unavailable')
            fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                os.write(fd, str(os.getpid()).encode('ascii'))
            finally:
                os.close(fd)
            sys.argv = sys.argv[2:]
            runpy.run_path(sys.argv[0], run_name='__main__')
        """.trimIndent()
    }
}
