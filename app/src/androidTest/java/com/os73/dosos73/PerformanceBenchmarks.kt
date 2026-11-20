package com.os73.dosos73

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in measurements (Phase F). They contact real public servers or take minutes, so they run
 * only with `-Pandroid.testInstrumentationRunnerArguments.dosos73.benchmark=true`. Results are
 * written to logcat under the tag "DososBenchmark" as JSON; nothing is asserted about speed.
 */
private fun benchmarksEnabled() =
    InstrumentationRegistry.getArguments().getString("dosos73.benchmark") == "true"

private fun report(name: String, millis: List<Long>, extra: JSONObject = JSONObject()) {
    val sorted = millis.sorted()
    fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
    Log.i("DososBenchmark", extra.put("case", name).put("runs", sorted.size)
        .put("p50", pct(0.50)).put("p95", pct(0.95)).put("min", sorted.first()).put("max", sorted.last())
        .put("samples", JSONArray(millis)).toString())
}

@RunWith(AndroidJUnit4::class)
class UiResponseBenchmark {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun reset() { MainViewModel.analysisForTests = null }

    /** Tap "Analyze link" → the stage text is composed. The engine is held open so the stage stays. */
    @Test fun analyzeTapToVisibleStage() {
        assumeTrue(benchmarksEnabled())
        val samples = mutableListOf<Long>()
        repeat(30) { run ->
            val release = CompletableDeferred<Unit>()
            MainViewModel.analysisForTests = { url ->
                release.await()
                Analysis(url, "x", listOf(Choice("direct", Kind.FILE, "bin", "Original")), true)
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("url").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("url").performTextReplacement("https://example.com/file-$run.bin")
            compose.waitForIdle()
            val start = SystemClock.uptimeMillis()
            compose.onNodeWithTag("analyze").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("analysis_stage").fetchSemanticsNodes().isNotEmpty() }
            samples += SystemClock.uptimeMillis() - start
            release.complete(Unit)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("download").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            compose.activityRule.scenario.recreate()
        }
        report("ui_tap_to_stage", samples, JSONObject().put("note", "debug build, emulator; includes test sync"))
    }
}

@RunWith(AndroidJUnit4::class)
class AnalysisLatencyBenchmark {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Production path: policy-bound OkHttp client + LinkAnalyzer + (for media pages) the embedded engine. */
    private suspend fun analyzeOnce(url: String): Pair<Long, String> {
        val network = NetworkSettings.requireNetwork(context, Stage.EXTRACT)
        val inspector = DirectDownloader(NetworkSettings.client(context, network, Stage.EXTRACT))
        val media = MediaEngine(context)
        val start = SystemClock.elapsedRealtime()
        val outcome = runCatching { LinkAnalyzer.of(inspector, media).analyze(url, {}, pickFirst = false) }
        val elapsed = SystemClock.elapsedRealtime() - start
        val result = outcome.fold(
            { if (it is AnalysisOutcome.Choices) "choices:${it.candidates.size}" else "ready" },
            { (it as? PipelineException)?.code?.name ?: it.javaClass.simpleName })
        return elapsed to result
    }

    private fun measure(name: String, url: String, runs: Int) = runBlocking {
        val samples = mutableListOf<Long>()
        val results = mutableSetOf<String>()
        repeat(runs) {
            val (millis, result) = analyzeOnce(url)
            samples += millis
            results += result
        }
        report(name, samples, JSONObject().put("results", JSONArray(results.toList())))
    }

    @Test fun directApkFromGitHubRelease() {
        assumeTrue(benchmarksEnabled())
        measure("direct_apk_github", "https://github.com/taroq4-2/dosos73/releases/download/v1.3.2/dosos73.apk", 20)
    }

    @Test fun fdroidPackagePage() {
        assumeTrue(benchmarksEnabled())
        measure("fdroid_page", "https://f-droid.org/packages/org.fdroid.fdroid/", 20)
    }

    /** Unauthenticated GitHub API allows 60 requests per hour per address: kept to 10 runs. */
    @Test fun githubReleasePage() {
        assumeTrue(benchmarksEnabled())
        measure("github_release_page", "https://github.com/taroq4-2/dosos73/releases/latest", 10)
    }

    @Test fun missingFile404() {
        assumeTrue(benchmarksEnabled())
        measure("missing_404", "https://github.com/taroq4-2/dosos73/releases/download/v1.3.2/no-such-file.apk", 10)
    }

    /** Public Blender Foundation film (CC BY). Exercises the embedded media engine end to end. */
    @Test fun mediaPage() {
        assumeTrue(benchmarksEnabled())
        measure("media_page_youtube", "https://www.youtube.com/watch?v=aqz-KE-bpKQ", 3)
    }
}
