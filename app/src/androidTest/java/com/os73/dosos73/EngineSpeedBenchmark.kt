package com.os73.dosos73

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in 1.4.1 measurements (`dosos73.benchmark=true`): each case compares the same build with
 * one improvement switched off and on. Results go to logcat tag "DososBenchmark"; nothing about
 * speed is asserted. The media cases contact YouTube.
 */
@RunWith(AndroidJUnit4::class)
class EngineSpeedBenchmark {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val url = InstrumentationRegistry.getArguments().getString("mediaUrl")
        ?: "https://www.youtube.com/watch?v=aqz-KE-bpKQ"

    private fun enabled() = InstrumentationRegistry.getArguments().getString("dosos73.benchmark") == "true"

    private fun report(name: String, millis: List<Long>, extra: JSONObject = JSONObject()) {
        val sorted = millis.sorted()
        Log.i("DososBenchmark", extra.put("case", name).put("runs", sorted.size)
            .put("p50", sorted[(sorted.size - 1) / 2]).put("min", sorted.first()).put("max", sorted.last())
            .put("samples", JSONArray(millis)).toString())
    }

    private fun runEngine(entry: File, vararg arguments: String): Long {
        val start = SystemClock.elapsedRealtime()
        NativeProcessSession(context, entry).use { it.execute(arguments.toList()) }
        return SystemClock.elapsedRealtime() - start
    }

    /** Interpreter start + full yt-dlp import: 1.4.0 zipapp (source compiled every run) vs unpacked + bytecode. */
    @Test fun engineStartupZipappVersusBytecode() {
        assumeTrue(enabled())
        EngineRuntime.initialize(context)
        val zipapp = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        val unpacked = BundledEngine.unpacked(context)
        runEngine(unpacked, "--version") // populate the bytecode cache once
        val legacy = List(5) { runEngine(zipapp, "--version") }
        val current = List(5) { runEngine(unpacked, "--version") }
        report("engine_start_zipapp", legacy)
        report("engine_start_unpacked_bytecode", current)
    }

    /** First analysis without the yt-dlp cache (1.4.0 behaviour) vs repeated analyses with it. */
    @Test fun mediaAnalysisWithoutAndWithEngineCache() = runBlocking {
        assumeTrue(enabled())
        val cold = List(2) {
            MediaEngine.engineCacheDirectory(context.cacheDir).deleteRecursively()
            val start = SystemClock.elapsedRealtime()
            MediaEngine(context).analyze(url)
            SystemClock.elapsedRealtime() - start
        }
        val warm = List(3) {
            val start = SystemClock.elapsedRealtime()
            MediaEngine(context).analyze(url)
            SystemClock.elapsedRealtime() - start
        }
        report("media_analysis_no_engine_cache", cold)
        report("media_analysis_engine_cache", warm)
    }

    /** Download call → first progress line, with a second extraction (1.4.0) vs from the analysis' metadata. */
    @Test fun downloadStartWithoutAndWithAnalysisMetadata() = runBlocking {
        assumeTrue(enabled())
        val engine = MediaEngine(context)
        suspend fun firstProgress(reuse: Boolean): Long {
            val analysis = engine.analyze(url)
            if (!reuse) EngineInfoCache.forget(context.cacheDir, url)
            val choice = analysis.choices.filter { it.kind == Kind.AUDIO }.minByOrNull { it.bitrateKbps ?: Int.MAX_VALUE }
                ?: analysis.choices.last()
            val directory = File(context.cacheDir, "benchmark-start").apply { deleteRecursively(); mkdirs() }
            val started = CompletableDeferred<Long>()
            val begin = SystemClock.elapsedRealtime()
            val job = async(Dispatchers.Default) {
                engine.download(DownloadTask(analysis, choice, null), directory) { percent, _ ->
                    if (percent != null) started.complete(SystemClock.elapsedRealtime() - begin)
                }
            }
            try { return withTimeout(120_000) { started.await() } }
            finally { job.cancelAndJoin(); directory.deleteRecursively() }
        }
        val legacy = List(2) { firstProgress(reuse = false) }
        val current = List(2) { firstProgress(reuse = true) }
        report("download_start_second_extraction", legacy)
        report("download_start_analysis_metadata", current)
    }
}
