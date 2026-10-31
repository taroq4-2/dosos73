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
) {
    private fun publicProxy(): PublicProxy {
        val network = networkProvider()
        return PublicProxy(
            resolve = { host ->
                if (!NetworkSettings.permitted(context, network)) throw java.io.IOException("Network policy unavailable")
                network.getAllByName(host).toList()
            },
            socketFactory = network.socketFactory,
        )
    }

    @Volatile private var session: NativeProcessSession? = null

    fun cancel() { session?.cancel() }

    private suspend fun <T> invocation(block: (NativeProcessSession) -> T): T {
        val active = NativeProcessSession(context)
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

    suspend fun analyze(url: String): Analysis =
        invocation { active ->
            initialize()
            val dir = File(context.cacheDir, "analysis-${active.id}").apply { mkdirs() }
            try {
                publicProxy().use { p ->
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
    ): File =
        invocation { active ->
            initialize()
            publicProxy().use { p ->
                active.attachProxy(p)
                val request =
                    request(task.analysis.url, p).apply {
                        addOption("-f", formatSelector(task.choice.id))
                        addOption("-o", File(directory, "media.%(ext)s").absolutePath)
                        addOption("--no-part")
                        addOption("--no-continue")
                        addOption("--newline")
                        addOption("--progress-delta", 1)
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
                    active.execute(request.buildCommand()) { percent, processing ->
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

    companion object {
        internal fun formatSelector(id: String): String {
            val components = id.split('+')
            require(components.size in 1..2 && components.all { it.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) })
            // Extractor IDs such as "all" or "best" must remain literal field values.
            // "all" also admits an exact audio-only or video-only match.
            return components.joinToString("+") { "all[format_id=$it]" }
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
