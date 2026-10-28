package com.os73.dosos73

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import okhttp3.OkHttpClient
import okhttp3.Response

/** At most two streams, enabled only after an exact range probe and a strong validator. */
internal class ParallelDownloader(
    private val client: OkHttpClient,
    private val minimumBytes: Long = 4L * 1024 * 1024,
) {
    private val cancelled = AtomicBoolean(false)
    private val workers = arrayOf(DirectDownloader(client), DirectDownloader(client))

    fun cancel() {
        cancelled.set(true)
        workers.forEach { it.cancel() }
    }

    suspend fun download(
        url: String,
        destination: File,
        awaitReady: suspend () -> Unit,
        progress: (Long, Long?, Long) -> Unit,
    ): Boolean {
        val parts = (0..1).map { File(destination.parentFile, destination.name + ".parallel-$it") }
        val merged = File(destination.parentFile, destination.name + ".parallel-merge")
        try {
            awaitReady()
            currentCoroutineContext().ensureActive()
            val probe = workers[0].request(url, probe = true)
            val total: Long
            val tag: String
            val target: String
            probe.use { response ->
                identity(response)
                val match =
                    Regex("bytes 0-0/([0-9]+)")
                        .matchEntire(response.header("Content-Range").orEmpty())
                total = match?.groupValues?.get(1)?.toLongOrNull() ?: return false
                tag = ResumeCheckpoint.strongTag(response.header("ETag")) ?: return false
                if (
                    response.code != 206 ||
                        total < minimumBytes ||
                        response.body?.contentLength() != 1L
                )
                    return false
                response.body!!.byteStream().use {
                    if (it.read() < 0 || it.read() != -1) return false
                }
                target = ResumeCheckpoint.hash(response.request.url.toString())
            }
            val split = total / 2
            val starts = longArrayOf(0, split)
            val ends = longArrayOf(split - 1, total - 1)
            val counts = longArrayOf(0, 0)
            val monitor = Any()
            var last = System.nanoTime()
            var lastBytes = 0L
            supervisorScope {
                val jobs =
                    (0..1).map { index ->
                        async(Dispatchers.IO) {
                            val start = starts[index]
                            val end = ends[index]
                            val checkpoint =
                                ResumeCheckpoint(
                                    ResumeCheckpoint.hash(Safety.url(url)),
                                    target,
                                    tag,
                                    total,
                                    start,
                                )
                            workers[index].request(url, resume = checkpoint, rangeEnd = end).use {
                                response ->
                                identity(response)
                                if (
                                    response.code != 206 ||
                                        response.header("ETag") != tag ||
                                        ResumeCheckpoint.hash(response.request.url.toString()) !=
                                            target ||
                                        response.header("Content-Range") !=
                                            "bytes $start-$end/$total"
                                )
                                    throw IOException("Incompatible parallel range")
                                val body =
                                    response.body ?: throw IOException("Empty parallel response")
                                val expected = end - start + 1
                                if (body.contentLength() >= 0 && body.contentLength() != expected)
                                    throw IOException("Invalid parallel length")
                                body.byteStream().use { input ->
                                    parts[index].outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        var count = 0L
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            if (cancelled.get()) throw IOException("Cancelled")
                                            awaitReady()
                                            val n = input.read(buffer)
                                            if (n == -1) break
                                            if (n > expected - count)
                                                throw IOException("Parallel range overflow")
                                            output.write(buffer, 0, n)
                                            count += n
                                            synchronized(monitor) {
                                                counts[index] = count
                                                val now = System.nanoTime()
                                                if (now - last >= 250_000_000) {
                                                    val done = counts.sum()
                                                    progress(
                                                        done,
                                                        total,
                                                        ((done - lastBytes).toDouble() *
                                                                1_000_000_000 / (now - last))
                                                            .toLong(),
                                                    )
                                                    last = now
                                                    lastBytes = done
                                                }
                                            }
                                        }
                                        if (count != expected)
                                            throw IOException("Truncated parallel range")
                                        output.fd.sync()
                                    }
                                }
                            }
                        }
                    }
                try {
                    jobs.awaitAll()
                } catch (error: Throwable) {
                    workers.forEach { it.cancel() }
                    jobs.forEach { it.cancel() }
                    throw error
                }
            }
            currentCoroutineContext().ensureActive()
            merged.outputStream().use { output ->
                for (part in parts) part.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (cancelled.get()) throw IOException("Cancelled")
                        awaitReady()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
                output.fd.sync()
            }
            if (merged.length() != total) throw IOException("Parallel merge mismatch")
            java.nio.file.Files.move(
                merged.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            progress(total, total, 0)
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) throw error
            return false // One sequential fallback, never mix unvalidated pieces.
        } finally {
            workers.forEach { it.cancel() }
            parts.forEach { it.delete() }
            merged.delete()
        }
    }

    private fun identity(response: Response) {
        val encoding = response.header("Content-Encoding")
        if (!encoding.isNullOrBlank() && !encoding.equals("identity", true))
            throw IOException("Unsupported parallel encoding")
        val mime = response.header("Content-Type").orEmpty().trim()
        if (mime.startsWith("text/html", true) || mime.startsWith("multipart/byteranges", true))
            throw IOException("Unexpected parallel content")
    }
}
