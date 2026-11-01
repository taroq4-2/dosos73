package com.os73.dosos73

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class DirectDownloader(private val client: OkHttpClient = secureClient()) {
    @Volatile private var activeCall: okhttp3.Call? = null
    private val cancelled = AtomicBoolean(false)
    @Volatile private var parallel: ParallelDownloader? = null

    fun cancel() {
        cancelled.set(true)
        parallel?.cancel()
        activeCall?.cancel()
    }

    internal fun request(
        url: String,
        head: Boolean = false,
        probe: Boolean = false,
        resume: ResumeCheckpoint? = null,
        rangeEnd: Long? = null,
    ): Response {
        var current = Safety.url(url)
        repeat(6) {
            val request =
                Request.Builder()
                    .url(current)
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "dosos73/${BuildConfig.VERSION_NAME} (Android)")
                    .apply {
                        if (head) head()
                        if (probe) header("Range", "bytes=0-0")
                        if (resume != null) {
                            header("Range", "bytes=${resume.offset}-${rangeEnd ?: ""}")
                            header("If-Range", resume.etag)
                        }
                    }
                    .build()
            Safety.requirePublicLiteral(request.url.host)
            if (cancelled.get()) throw IOException("Cancelled")
            val call = client.newCall(request)
            activeCall = call
            if (cancelled.get()) call.cancel()
            val response = call.execute()
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")?.let { response.request.url.resolve(it) }
                response.close()
                if (next == null || (current.startsWith("https:") && next.scheme != "https"))
                    throw IOException("Invalid redirect")
                current = Safety.url(next.toString())
            } else return response
        }
        throw IOException("Too many redirects")
    }

    suspend fun analyze(url: String): Analysis? =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val head = request(url, true)
            var candidate =
                if (head.code in setOf(403, 405, 501)) {
                    head.close()
                    request(url, probe = true)
                } else head
            // Some origins reject byte ranges although a normal GET is supported.
            // Retry only this probe failure once; close without downloading the body.
            if (candidate.code == 416) {
                candidate.close()
                candidate = request(url)
            }
            candidate.use { response ->
                if (!response.isSuccessful) return@withContext null
                val mime =
                    response
                        .header("Content-Type")
                        ?.substringBefore(';')
                        ?.trim()
                        ?.lowercase()
                        .orEmpty()
                // HTML and unknown responses must go through the extractor, not be saved as media.
                if (mime.startsWith("text/") || mime.contains("json") || mime.isBlank())
                    return@withContext null
                val kind =
                    when {
                        mime.startsWith("video/") -> Kind.VIDEO
                        mime.startsWith("audio/") -> Kind.AUDIO
                        else -> Kind.FILE
                    }
                if (
                    !(mime.startsWith("video/") ||
                        mime.startsWith("audio/") ||
                        mime == "application/octet-stream" ||
                        mime == "application/pdf" ||
                        mime == "application/zip" ||
                        mime in
                            setOf(
                                "image/jpeg",
                                "image/png",
                                "application/gzip",
                                "application/x-7z-compressed",
                            ))
                )
                    return@withContext null
                val (title, safeExt) =
                    DirectFileName.resolve(
                        response.header("Content-Disposition"),
                        response.request.url.pathSegments.lastOrNull().orEmpty(),
                        mime,
                    )
                Analysis(
                    url,
                    title,
                    listOf(Choice("direct", kind, safeExt, "Original")),
                    true,
                    if (response.code == 206)
                        Regex("bytes 0-0/(\\d+)")
                            .matchEntire(response.header("Content-Range").orEmpty())
                            ?.groupValues
                            ?.get(1)
                            ?.toLongOrNull()
                    else response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 },
                )
            }
        }

    suspend fun download(
        url: String,
        destination: File,
        awaitReady: suspend () -> Unit = {},
        connections: Int = 1,
        progress: (Long, Long?, Long) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (
                connections > 1 &&
                    !File(destination.parentFile, destination.name + ".resume").exists()
            ) {
                val accelerator = ParallelDownloader(client)
                parallel = accelerator
                if (cancelled.get()) accelerator.cancel()
                try {
                    if (accelerator.download(url, destination, awaitReady, progress))
                        return@withContext
                } finally {
                    parallel = null
                }
                if (cancelled.get()) throw IOException("Cancelled")
                currentCoroutineContext().ensureActive()
            }
            val sourceHash = ResumeCheckpoint.hash(Safety.url(url))
            val journal = ResumeJournal(destination)
            var checkpoint =
                journal.read()?.takeIf {
                    it.sourceHash == sourceHash &&
                        destination.isFile &&
                        destination.length() >= it.offset
                }
            if (checkpoint == null) journal.clear()
            // A previously durable complete body still needs origin validation: never trust 416
            // alone.
            if (checkpoint != null && checkpoint.offset == checkpoint.total) checkpoint = null
            var response = request(url, resume = checkpoint?.takeIf { it.offset > 0 })
            try {
                if (checkpoint != null && checkpoint.offset > 0 && response.code == 416) {
                    response.close()
                    checkpoint = null
                    journal.clear()
                    response = request(url)
                }
                HttpRepresentation.requireIdentity(response)
                val body = response.body ?: throw IOException("Empty response")
                val bodyLength = body.contentLength().takeIf { it >= 0 }
                val targetHash = ResumeCheckpoint.hash(response.request.url.toString())
                val etag = ResumeCheckpoint.strongTag(response.header("ETag"))
                val append = response.code == 206
                val total: Long?
                val offset: Long
                if (append) {
                    val prior = checkpoint ?: throw IOException("Unrequested partial response")
                    if (prior.offset <= 0 || targetHash != prior.targetHash || etag != prior.etag)
                        throw IOException("Changed representation")
                    val range =
                        HttpRepresentation.parseRange(response.header("Content-Range"))
                            ?: throw IOException("Invalid content range")
                    if (
                        range.first != prior.offset ||
                            range.last != prior.total - 1 ||
                            range.total != prior.total
                    )
                        throw IOException("Invalid content range")
                    if (bodyLength != null && bodyLength != prior.total - prior.offset)
                        throw IOException("Invalid range length")
                    offset = prior.offset
                    total = prior.total
                } else {
                    if (response.code != 200 || response.header("Content-Range") != null)
                        throw IOException("HTTP ${response.code}")
                    // Range ignored or If-Range changed: replace, never append the full
                    // representation.
                    offset = 0
                    total = bodyLength
                }
                val canResume = etag != null && total != null && total > 0
                var count = offset
                var lastBytes = offset
                var last = System.nanoTime()
                var checkpointBytes = offset
                var checkpointAt = last
                RandomAccessFile(destination, "rw").use { output ->
                    output.setLength(offset)
                    output.seek(offset)
                    fun persist() {
                        output.fd.sync()
                        if (canResume) {
                            journal.write(
                                ResumeCheckpoint(sourceHash, targetHash, etag!!, total!!, count)
                            )
                            checkpointBytes = count
                            checkpointAt = System.nanoTime()
                        }
                    }
                    if (canResume) persist() else journal.clear()
                    progress(count, total, 0)
                    try {
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (cancelled.get()) throw IOException("Cancelled")
                                awaitReady()
                                currentCoroutineContext().ensureActive()
                                if (cancelled.get()) throw IOException("Cancelled")
                                val n = input.read(buffer)
                                if (n == -1) break
                                if (total != null && n.toLong() > total - count)
                                    throw IOException("Transfer exceeds expected length")
                                output.write(buffer, 0, n)
                                count += n
                                val now = System.nanoTime()
                                if (
                                    canResume &&
                                        (count - checkpointBytes >= 4L * 1024 * 1024 ||
                                            now - checkpointAt >= 2_000_000_000)
                                )
                                    persist()
                                if (now - last > 250_000_000) {
                                    progress(
                                        count,
                                        total,
                                        ((count - lastBytes).toDouble() * 1_000_000_000 /
                                                (now - last))
                                            .toLong(),
                                    )
                                    last = now
                                    lastBytes = count
                                }
                            }
                        }
                        if (count == 0L || (total != null && count != total))
                            throw IOException("Incomplete transfer")
                        persist()
                    } catch (error: Exception) {
                        // Flush partial progress before propagating failures; never advance the
                        // journal
                        // before the file. If persistence itself fails, the earlier checkpoint is
                        // safe.
                        runCatching { persist() }
                        throw error
                    }
                }
                journal.clear()
                progress(count, total, 0)
            } finally {
                response.close()
                activeCall = null
            }
        }

    companion object {
        fun secureClient(allowCleartext: () -> Boolean = { false }) =
            OkHttpClient.Builder()
                // A system HTTP proxy resolves origins itself and would bypass the public DNS
                // policy.
                .proxy(Proxy.NO_PROXY)
                .addInterceptor { chain ->
                    if (!chain.request().url.isHttps && !allowCleartext())
                        throw PipelineException(Stage.TRANSFER, FailureCode.CLEARTEXT_DISABLED)
                    Safety.requirePublicLiteral(chain.request().url.host)
                    chain.proceed(chain.request())
                }
                .dns(
                    object : okhttp3.Dns {
                        override fun lookup(hostname: String) = Safety.resolve(hostname)
                    }
                )
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.HOURS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
    }
}
