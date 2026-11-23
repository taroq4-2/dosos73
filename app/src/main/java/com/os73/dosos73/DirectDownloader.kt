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

/** Result of inspecting a link before anything is saved. */
sealed interface DirectProbe {
    /** The body is a real file; [verified] when its first bytes matched the type's signature. */
    data class File(val analysis: Analysis, val verified: Boolean, val category: FileCategory) : DirectProbe
    /** An HTML/text page: needs a source resolver or the media engine. */
    data class Page(val finalUrl: String) : DirectProbe
    /** The origin refused or failed the request; another engine cannot do better with this link. */
    data class Failed(val code: FailureCode, val status: Int? = null, val retryAfterSeconds: Long? = null) : DirectProbe
}

class DirectDownloader(private val client: OkHttpClient = secureClient()) : LinkInspector {
    @Volatile private var activeCall: okhttp3.Call? = null
    private val cancelled = AtomicBoolean(false)
    @Volatile private var parallel: ParallelDownloader? = null
    /** Link inspection must answer quickly; long transfers keep [client]'s limits. */
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).build()
    }

    override fun cancel() {
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
        range: String? = null,
        quick: Boolean = false,
        accept: String? = null,
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
                        if (range != null) header("Range", range)
                        if (accept != null) header("Accept", accept)
                        if (resume != null) {
                            header("Range", "bytes=${resume.offset}-${rangeEnd ?: ""}")
                            header("If-Range", resume.etag)
                        }
                    }
                    .build()
            Safety.requirePublicLiteral(request.url.host)
            if (cancelled.get()) throw IOException("Cancelled")
            val call = (if (quick) probeClient else client).newCall(request)
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

    /**
     * Fast, bounded inspection of a link. One ranged GET reads at most [FileTypes.HEADER_BYTES]
     * bytes, which proves what the body really is (P0-1/P0-2): headers and URL extensions are hints.
     * Never downloads the body, never follows a redirect without re-checking it (see [request]).
     */
    override suspend fun probe(url: String, onResponse: () -> Unit): DirectProbe =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            try { probeOnce(url, onResponse) }
            catch (error: IOException) {
                // One retry for a transient connection failure only; HTTP statuses are not retried here.
                if (cancelled.get() || (error !is java.net.SocketTimeoutException && error !is java.net.ConnectException))
                    throw error
                currentCoroutineContext().ensureActive()
                kotlinx.coroutines.delay(400)
                probeOnce(url, onResponse)
            }
        }

    private fun probeOnce(url: String, onResponse: () -> Unit): DirectProbe {
        var response = request(url, range = "bytes=0-${FileTypes.HEADER_BYTES - 1}", quick = true)
        onResponse()
        // Some origins reject byte ranges although a normal GET works; retry that once, unranged.
        if (response.code == 416 || response.code == 501) {
            response.close()
            response = request(url, quick = true)
        }
        response.use {
            if (!response.isSuccessful) return statusFailure(response)
            val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val finalUrl = response.request.url.toString()
            if (mime.startsWith("text/html") || mime == "application/xhtml+xml") return DirectProbe.Page(finalUrl)
            val encodings = response.headers.values("Content-Encoding").flatMap { it.split(',') }.map { it.trim() }
            if (encodings.any { it.isNotEmpty() && !it.equals("identity", true) }) return DirectProbe.Page(finalUrl)
            val header = response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(FileTypes.HEADER_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    if (cancelled.get()) throw IOException("Cancelled")
                    val n = input.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                buffer.copyOf(filled)
            } ?: ByteArray(0)
            if (FileTypes.looksLikeHtml(header)) return DirectProbe.Page(finalUrl)
            val disposition = response.header("Content-Disposition")
            val attachment = disposition.orEmpty().trimStart().startsWith("attachment", ignoreCase = true)
            val lastSegment = response.request.url.pathSegments.lastOrNull().orEmpty()
            val named = DirectFileName.resolve(disposition, lastSegment, mime)
            val title = named.first
            var extension = named.second
            // The extension the name alone gives ("bin" when it has none).
            val suffix = DirectFileName.resolve(disposition, lastSegment, "").second
            // Text/JSON/XML bodies are web resources unless the origin or the name says "this is a file".
            val textual = mime.startsWith("text/") || mime.contains("json") || mime.endsWith("xml")
            if (textual && !attachment && FileTypes.category(suffix) != FileCategory.DOCUMENT)
                return DirectProbe.Page(finalUrl)
            val sniffed = FileTypes.sniff(header)
            when (FileTypes.verify(extension, header)) {
                FileTypes.Verdict.WebPage -> return DirectProbe.Page(finalUrl)
                is FileTypes.Verdict.Mismatch -> {
                    // A mislabelled MIME type: trust the name when its signature matches the bytes,
                    // otherwise the bytes of an opaque type. An unexplained conflict is refused.
                    extension = when {
                        sniffed != null && FileTypes.forExtension(suffix)?.signature?.invoke(header) == true -> suffix
                        sniffed != null && mime in FileTypes.opaqueMimes -> sniffed.extension
                        else -> return DirectProbe.Failed(FailureCode.FILE_TYPE_MISMATCH)
                    }
                }
                else -> if (extension == "bin" && sniffed != null) extension = sniffed.extension
            }
            val kind = when {
                mime.startsWith("video/") -> Kind.VIDEO
                mime.startsWith("audio/") -> Kind.AUDIO
                else -> Kind.FILE
            }
            val size =
                if (response.code == 206) HttpRepresentation.parseRange(response.header("Content-Range"))?.total
                else response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            return DirectProbe.File(
                Analysis(url, title, listOf(Choice("direct", kind, extension, "Original")), true, size),
                verified = FileTypes.verify(extension, header) == FileTypes.Verdict.Match,
                category = FileTypes.category(extension),
            )
        }
    }

    private fun statusFailure(response: Response): DirectProbe.Failed {
        val code = when (response.code) {
            401, 407 -> FailureCode.AUTHENTICATION_REQUIRED
            403 -> FailureCode.HTTP_403
            404 -> FailureCode.HTTP_404
            410 -> FailureCode.LINK_EXPIRED
            429 -> FailureCode.HTTP_429
            in 500..599 -> FailureCode.HTTP_SERVER
            else -> FailureCode.DOWNLOAD_FAILED
        }
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it in 0..86_400 }
        return DirectProbe.Failed(code, response.code, retryAfter)
    }

    /**
     * Small JSON documents from documented public APIs (GitHub Releases, F-Droid). Same SSRF,
     * TLS and redirect policy as every other request; bounded size; non-JSON is refused.
     */
    override suspend fun fetchJson(url: String, accept: String): String =
        withContext(Dispatchers.IO) {
            request(url, quick = true, accept = accept).use { response ->
                if (!response.isSuccessful) {
                    val limited = response.code == 403 && response.header("X-RateLimit-Remaining") == "0"
                    throw PipelineException(Stage.EXTRACT, if (limited) FailureCode.HTTP_429 else statusFailure(response).code)
                }
                val mime = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                if (!mime.contains("json")) throw IOException("Unexpected response type")
                val source = response.body?.source() ?: throw IOException("Empty response")
                if (source.request(MAX_JSON_BYTES + 1)) throw IOException("Response too large")
                source.buffer.readUtf8()
            }
        }

    /** Compatibility shim: a verified direct file, or null when the link is a page or failed. */
    suspend fun analyze(url: String): Analysis? = (probe(url) as? DirectProbe.File)?.analysis

    suspend fun download(
        url: String,
        destination: File,
        awaitReady: suspend () -> Unit = {},
        connections: Int = 1,
        expectsBinary: Boolean = false,
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
                    if (accelerator.download(url, destination, awaitReady, expectsBinary, progress))
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
                // Preserve status-specific failures even when the error body is HTML,
                // compressed, or uses headers which cannot describe a successful file.
                if (response.code != 200 && response.code != 206)
                    throw IOException("HTTP ${response.code}")
                HttpRepresentation.requireIdentity(response, expectsBinary)
                val body = response.body ?: throw IOException("Empty response")
                val bodyLength = body.contentLength().takeIf { it >= 0 }
                // The redirect target is identified by scheme, host and path. CDNs (GitHub release
                // assets included) sign each redirect with a new query; binding to it made every
                // resume fail. Same-object proof stays: strong ETag + If-Range + exact length/range.
                val target = response.request.url
                val targetHash = ResumeCheckpoint.hash(target.newBuilder().query(null).fragment(null).build().toString())
                // Checkpoints written by 1.3.2 hashed the full URL; still valid when nothing changed.
                val legacyTargetHash = ResumeCheckpoint.hash(target.toString())
                val etag = ResumeCheckpoint.strongTag(response.header("ETag"))
                val append = response.code == 206
                val total: Long?
                val offset: Long
                if (append) {
                    val prior = checkpoint ?: throw IOException("Unrequested partial response")
                    if (prior.offset <= 0 || prior.targetHash !in setOf(targetHash, legacyTargetHash) || etag != prior.etag)
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
                    if (response.header("Content-Range") != null)
                        throw IOException("HTTP ${response.code}")
                    // Range ignored or If-Range changed: replace, never append the full
                    // representation.
                    offset = 0
                    total = bodyLength
                }
                val canResume = etag != null && total != null && total > 0
                var count = offset
                val meter = SpeedMeter().apply { sample(offset, System.nanoTime()) }
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
                                    progress(count, total, meter.sample(count, now))
                                    last = now
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
        private const val MAX_JSON_BYTES = 2L * 1024 * 1024

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
