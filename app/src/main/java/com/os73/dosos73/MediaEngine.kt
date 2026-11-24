package com.os73.dosos73

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import org.json.JSONObject

class MediaEngine(
    private val context: Context,
    private val networkProvider: () -> android.net.Network = { NetworkSettings.requireNetwork(context) },
) : MediaExtractor {
    private fun publicProxy(stage: Stage): PublicProxy {
        val network = try { networkProvider() } catch (error: PipelineException) {
            if (error.code == FailureCode.NETWORK_UNAVAILABLE)
                throw PipelineException(stage, error.code, error)
            throw error
        }
        return PublicProxy(
            resolve = { host ->
                if (!NetworkSettings.permitted(context, network)) throw java.io.IOException("Network policy unavailable")
                network.getAllByName(host).toList()
            },
            socketFactory = network.socketFactory,
        )
    }

    @Volatile private var session: NativeProcessSession? = null

    override fun cancel() { session?.cancel() }

    private suspend fun <T> invocation(entry: File? = null, block: (NativeProcessSession) -> T): T {
        val active = if (entry == null) NativeProcessSession(context) else NativeProcessSession(context, entry)
        check(session == null) { "Media engine already active" }
        session = active
        return try {
            coroutineScope {
                // Suspended while running: cancellation closes the process group even if a pipe
                // read does not react to Thread.interrupt(). No polling or permanent watcher.
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { active.cancel() }
                }
                try { runInterruptible(Dispatchers.IO) { block(active) } }
                finally { cancellation.cancelAndJoin() }
            }
        } finally { active.close(); if (session === active) session = null }
    }

    private fun initialize() {
        try { EngineRuntime.initialize(context) }
        catch (error: LinkageError) {
            throw PipelineException(Stage.INITIALIZE, FailureCode.JS_RUNTIME_UNAVAILABLE, error)
        }
        Diagnostics.event(Stage.INITIALIZE, "ready")
    }

    private fun request(url: String, proxy: PublicProxy): YoutubeDLRequest {
        require(Safety.url(url).startsWith("https://"))
        return YoutubeDLRequest(url).apply {
            addOption("--ignore-config")
            // Prefer the bundled urllib handler; other handlers remain available when needed.
            // Both routes retain origin certificate verification and the checked CONNECT proxy.
            addOption("--compat-options", "prefer-legacy-http-handler")
            addOption("--no-colors")
            addOption("--js-runtimes", "quickjs:${context.applicationInfo.nativeLibraryDir}/libqjs.so")
            addOption("--ffmpeg-location", "${context.applicationInfo.nativeLibraryDir}/libffmpeg.so")
            addOption("--no-playlist")
            // Measured in 1.4.1: an engine cache gave no measurable gain on YouTube analysis
            // (network-bound), so the reviewed no-cache behaviour stays.
            addOption("--no-cache-dir")
            addOption("--socket-timeout", 25)
            addOption("--retries", 2)
            addOption("--fragment-retries", 2)
            addOption("--concurrent-fragments", 1)
            addOption("--proxy", proxy.url)
            addOption("--no-check-formats")
            addOption("--restrict-filenames")
            // EJS is bundled with the pinned official zipapp; never fetch executable components.
            addOption("--no-remote-components")
        }
    }

    override suspend fun analyze(url: String): Analysis = retryInterruptedMediaProbe { analyzeOnce(url) }

    private suspend fun analyzeOnce(url: String): Analysis =
        invocation { active ->
            initialize()
            val dir = File(context.cacheDir, "analysis-${active.id}").apply { mkdirs() }
            try {
                publicProxy(Stage.EXTRACT).use { p ->
                    active.attachProxy(p)
                    val request =
                        request(url, p).apply {
                            addOption("--skip-download")
                            addOption("--write-info-json")
                            addOption("--quiet")
                            addOption("-o", File(dir, "metadata.%(ext)s").absolutePath)
                        }
                    Diagnostics.event(Stage.EXTRACT, "started")
                    try {
                        active.execute(request.buildCommand())
                    } catch (error: Exception) {
                        if (
                            error is InterruptedException ||
                                error is kotlinx.coroutines.CancellationException
                        )
                            throw error
                        throw FailureClassifier.classify(Stage.EXTRACT, error)
                    }
                    val info =
                        dir.listFiles()?.singleOrNull { it.name.endsWith(".info.json") }
                            ?: error("Metadata unavailable")
                    require(info.length() in 1..(4 * 1024 * 1024))
                    val json = JSONObject(info.readText())
                    val choices = mapFormats(json)
                    require(choices.isNotEmpty()) { "No supported formats" }
                    if (reusableInfo(json)) runCatching { EngineInfoCache.store(context.cacheDir, url, info) }
                    Diagnostics.event(Stage.EXTRACT, "completed")
                    Analysis(url, json.optString("title", "download").take(200), choices, false)
                }
            } finally {
                dir.deleteRecursively()
            }
        }

    suspend fun download(
        task: DownloadTask,
        directory: File,
        progress: (Float?, Boolean) -> Unit,
    ): File {
        val url = task.analysis.url
        val cached = withContext(Dispatchers.IO) { EngineInfoCache.fresh(context.cacheDir, url) }
        if (cached != null) {
            // Start from the analysis' metadata: no second extraction before bytes flow.
            try {
                return retryInterruptedMediaProbe(
                    canRetry = { withContext(Dispatchers.IO) { directory.list()?.isEmpty() == true } },
                ) { downloadOnce(task, directory, progress, cached) }
                    .also { withContext(NonCancellable + Dispatchers.IO) { EngineInfoCache.forget(context.cacheDir, url) } }
            } catch (failure: PipelineException) {
                currentCoroutineContext().ensureActive()
                if (failure.code !in REEXTRACT_ON) throw failure
                // Expired or refused stream links: forget them and extract the page again.
                Diagnostics.event(Stage.TRANSFER, "info_reuse_fallback", code = failure.code)
                withContext(Dispatchers.IO) { EngineInfoCache.forget(context.cacheDir, url) }
            }
        }
        return retryInterruptedMediaProbe(
            canRetry = { withContext(Dispatchers.IO) { directory.list()?.isEmpty() == true } },
        ) { downloadOnce(task, directory, progress, null) }
    }

    private suspend fun downloadOnce(
        task: DownloadTask,
        directory: File,
        progress: (Float?, Boolean) -> Unit,
        info: File?,
    ): File {
        val entry = withContext(Dispatchers.IO) {
            // Only validated HTTP checkpoint pairs survive a new extraction. Completed
            // streams, fragment journals and interrupted merges are rebuilt safely.
            NativeStaging.retainCheckpoints(directory)
            for (name in listOf("verified_http.py", "verified_download.py"))
                context.assets.open(name).use { source -> File(directory, name).outputStream().use(source::copyTo) }
            File(directory, "verified_download.py")
        }
        return invocation(entry) { active ->
            initialize()
            publicProxy(Stage.TRANSFER).use { p ->
                active.attachProxy(p)
                val request =
                    request(task.analysis.url, p).apply {
                        addOption("-f", formatSelector(task.choice.id))
                        addOption("-o", File(directory, "media.%(ext)s").absolutePath)
                        addOption("--part")
                        addOption("--continue")
                        addOption("--newline")
                        addOption("--progress-delta", 1)
                        // yt-dlp ignores the positional link when this is present.
                        if (info != null) addOption("--load-info-json", info.absolutePath)
                        if (task.choice.kind == Kind.AUDIO && task.choice.extension == "mp3") {
                            addOption("--extract-audio")
                            addOption("--audio-format", "mp3")
                            addOption("--audio-quality", "0")
                        }
                        if (task.choice.id.contains('+'))
                            addOption("--merge-output-format", task.choice.extension)
                    }
                var stage = Stage.TRANSFER
                Diagnostics.event(stage, "started")
                try {
                    val engine = BundledEngine.unpacked(context)
                    active.execute(listOf(engine.absolutePath) + request.buildCommand()) { percent, processing ->
                        if (processing) stage = Stage.MERGE
                        progress(percent, processing)
                    }
                } catch (error: Exception) {
                    if (
                        error is InterruptedException ||
                            error is kotlinx.coroutines.CancellationException
                    )
                        throw error
                    val failure = FailureClassifier.classify(stage, error)
                    Diagnostics.event(stage, "failed", code = failure.code)
                    throw failure
                }
                Diagnostics.event(stage, "completed")
                directory.listFiles()?.singleOrNull {
                    it.name == "media.${task.choice.extension}" && it.length() > 0
                } ?: error("Output missing")
            }
        }
    }

    companion object {
        /** Failures that a fresh extraction can fix when cached metadata was used. */
        internal val REEXTRACT_ON = setOf(
            FailureCode.HTTP_403, FailureCode.HTTP_404, FailureCode.LINK_EXPIRED,
            FailureCode.FORMAT_UNAVAILABLE, FailureCode.EXTRACTION_FAILED, FailureCode.DOWNLOAD_FAILED,
        )

        /** Single-item, HTTPS-only metadata; yt-dlp's own fallback uses webpage_url. */
        internal fun reusableInfo(json: JSONObject): Boolean =
            json.optString("_type", "video") == "video" &&
                json.optString("webpage_url").startsWith("https://") &&
                !json.has("entries")

        internal fun formatSelector(id: String): String {
            val components = id.split('+')
            require(components.size in 1..2 && components.all { it.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) })
            // Extractor IDs such as "all" or "best" must remain literal field values.
            // "all" also admits an exact audio-only or video-only match.
            return components.joinToString("+") {
                // Numeric selector tokens are literal IDs in yt-dlp. Keep their normal
                // extractor hints; arbitrary/reserved names require a field filter.
                if (it.all { character -> character in '0'..'9' }) it else "all[format_id=$it]"
            }
        }

        internal fun audioChoice(
            id: String,
            sourceExtension: String,
            abr: Double,
            sourceBytes: Long?,
            language: String?,
        ): Choice {
            val converted = sourceExtension != "mp3"
            val bitrate = abr.takeIf { it.isFinite() && it > 0 }?.toInt()?.takeIf { it > 0 }
            return Choice(
                id, Kind.AUDIO, "mp3",
                listOfNotNull(
                    if (converted) "MP3 V0" else "Original",
                    bitrate?.let { if (converted) "source $it kbps" else "$it kbps" },
                    language,
                ).joinToString(" · "),
                // Only unchanged MP3 can retain the source size and bitrate as output estimates.
                estimatedBytes = if (converted) null else sourceBytes,
                bitrateKbps = if (converted) null else bitrate,
                language = language,
            )
        }

        fun mapFormats(json: JSONObject): List<Choice> {
            val formats = json.optJSONArray("formats") ?: org.json.JSONArray().put(json)
            require(formats.length() <= 1000)
            data class Source(
                val id: String,
                val ext: String,
                val height: Int,
                val audio: Boolean,
                val video: Boolean,
                val unspecified: Boolean,
                val abr: Double,
                val tbr: Double,
                val fps: Double,
                val bytes: Long?,
                val language: String?,
            )
            val sources =
                (0 until formats.length()).mapNotNull { i ->
                    val f = formats.getJSONObject(i)
                    val id = f.optString("format_id")
                    val ext = f.optString("ext")
                    val protocol = f.optString("protocol")
                    if (
                        !id.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) ||
                            f.optBoolean("has_drm") ||
                            protocol !in setOf("https", "m3u8_native", "http_dash_segments") ||
                            !f.optString("url").startsWith("https://") ||
                            ext !in setOf("mp4", "webm", "m4a", "opus", "mp3")
                    )
                        null
                    else
                        Source(
                            id,
                            ext,
                            f.optInt("height"),
                            f.optString("acodec", "none") != "none",
                            f.optString("vcodec", "none") != "none",
                            !f.has("acodec") && !f.has("vcodec"),
                            f.optDouble("abr", 0.0).takeIf { it.isFinite() } ?: 0.0,
                            f.optDouble("tbr", 0.0).takeIf { it.isFinite() } ?: 0.0,
                            f.optDouble("fps", 0.0).takeIf { it.isFinite() } ?: 0.0,
                            f.optLong("filesize").takeIf { it > 0 } ?: f.optLong("filesize_approx").takeIf { it > 0 },
                            f.optString("language").takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,35}")) },
                        )
                }
            val audio = sources.filter { it.audio && !it.video }
            return sources
                .sortedWith(
                    compareByDescending<Source> { it.height }
                        .thenByDescending { if (it.video) it.tbr else it.abr }
                        .thenByDescending { it.fps }
                        .thenBy { it.id }
                )
                .mapNotNull { f ->
                    when {
                        f.unspecified ->
                            if (f.ext in setOf("mp4", "webm"))
                                Choice(
                                    f.id, Kind.VIDEO, f.ext, "Original",
                                    estimatedBytes = f.bytes,
                                    language = f.language,
                                )
                            else audioChoice(f.id, f.ext, f.abr, f.bytes, f.language)
                        f.audio && !f.video -> audioChoice(f.id, f.ext, f.abr, f.bytes, f.language)
                        f.video && f.audio ->
                            Choice(
                                f.id,
                                Kind.VIDEO,
                                f.ext,
                                if (f.height > 0) "${f.height}p" else "Original",
                                f.height.takeIf { it > 0 },
                                true,
                                estimatedBytes = f.bytes,
                                language = f.language,
                            )
                        f.video -> {
                            val a =
                                audio
                                    .filter {
                                        if (f.ext == "mp4") it.ext == "m4a"
                                        else it.ext in setOf("webm", "opus")
                                    }
                                    .maxByOrNull { it.abr }
                            a?.let {
                                Choice(
                                    "${f.id}+${it.id}",
                                    Kind.VIDEO,
                                    f.ext,
                                    "${f.height}p",
                                    f.height.takeIf { it > 0 },
                                    true,
                                    estimatedBytes = if (f.bytes != null && it.bytes != null && f.bytes <= Long.MAX_VALUE - it.bytes) f.bytes + it.bytes else null,
                                    language = it.language,
                                )
                            }
                        }
                        else -> null
                    }
                }
                .distinctBy { listOf(it.kind, it.extension, it.quality, it.language) }
        }
    }
}
