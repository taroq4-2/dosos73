package com.os73.dosos73

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinkAnalyzerTest {
    private val fileAnalysis = Analysis("https://example.com/a.apk", "a",
        listOf(Choice("direct", Kind.FILE, "apk", "Original")), true, 10)
    private val mediaAnalysis = Analysis("https://video.example/watch", "clip",
        listOf(Choice("18", Kind.VIDEO, "mp4", "360p")), false)

    private class Run(
        val probeResult: (String) -> DirectProbe,
        val json: (String) -> String = { error("no json") },
    ) {
        val stages = mutableListOf<AnalysisStage>()
        val probed = mutableListOf<String>()
        var mediaCalls = 0
        fun analyzer(media: Analysis) = LinkAnalyzer(
            probe = { url, onResponse -> probed += url; onResponse(); probeResult(url) },
            fetchJson = { url, _ -> json(url) },
            media = { mediaCalls++; media },
        )
    }

    private fun run(url: String, run: Run, pickFirst: Boolean = false) = runBlocking {
        runCatching { run.analyzer(mediaAnalysis).analyze(url, { run.stages += it }, pickFirst) }
    }

    @Test
    fun directFileIsReadyWithoutStartingTheMediaEngine() {
        val r = Run({ DirectProbe.File(fileAnalysis, true, FileCategory.APP) })
        val outcome = run("https://example.com/a.apk", r).getOrThrow() as AnalysisOutcome.Ready
        assertEquals(fileAnalysis, outcome.analysis)
        assertEquals(true, outcome.verified)
        assertEquals(0, r.mediaCalls)
        assertEquals(listOf(AnalysisStage.CONNECTING, AnalysisStage.PROBING), r.stages)
    }

    @Test
    fun mediaPageGoesToTheEngineAndReportsExtracting() {
        val r = Run({ DirectProbe.Page(it) })
        val outcome = run("https://video.example/watch?v=1", r).getOrThrow() as AnalysisOutcome.Ready
        assertEquals(mediaAnalysis, outcome.analysis)
        assertNull(outcome.verified)
        assertEquals(1, r.mediaCalls)
        assertEquals(AnalysisStage.EXTRACTING, r.stages.last())
    }

    @Test
    fun pageWhereAFileWasExpectedFailsFastWithAPreciseReason() {
        for (url in listOf("https://example.com/app.apk", "https://github.com/o/r/blob/main/x",
            "https://sourceforge.net/projects/x/files/")) {
            val r = Run({ DirectProbe.Page(it) })
            val error = run(url, r).exceptionOrNull() as PipelineException
            assertEquals(url, FailureCode.WEB_PAGE_NOT_FILE, error.code)
            assertEquals(url, 0, r.mediaCalls)
        }
    }

    @Test
    fun terminalStatusesNeverStartTheEngine() {
        for (code in listOf(FailureCode.HTTP_404, FailureCode.LINK_EXPIRED, FailureCode.FILE_TYPE_MISMATCH)) {
            val r = Run({ DirectProbe.Failed(code) })
            assertEquals(code, (run("https://video.example/watch", r).exceptionOrNull() as PipelineException).code)
            assertEquals(0, r.mediaCalls)
        }
    }

    @Test
    fun refusedMediaPageStillGetsTheEngineButARefusedFileDoesNot() {
        val page = Run({ DirectProbe.Failed(FailureCode.HTTP_403) })
        assertTrue(run("https://video.example/watch", page).isSuccess)
        assertEquals(1, page.mediaCalls)
        val file = Run({ DirectProbe.Failed(FailureCode.HTTP_429, 429, 30) })
        val error = run("https://example.com/a.zip", file).exceptionOrNull() as PipelineException
        assertEquals(FailureCode.HTTP_429, error.code)
        assertEquals(30L, (error.cause as RetryAfter).seconds)
        assertEquals(0, file.mediaCalls)
    }

    @Test
    fun unreachableHostFailsFastAsOffline() {
        val r = Run({ throw java.net.UnknownHostException("example.invalid") })
        val error = run("https://video.example/watch", r).exceptionOrNull() as PipelineException
        assertEquals(FailureCode.NETWORK_UNAVAILABLE, error.code)
        assertEquals(0, r.mediaCalls)
    }

    @Test
    fun slowProbeTimesOutWithItsOwnBudget() = runBlocking {
        val analyzer = LinkAnalyzer(
            probe = { _, _ -> delay(5_000); DirectProbe.Page("") },
            fetchJson = { _, _ -> "" }, media = { mediaAnalysis }, probeTimeoutMs = 50,
        )
        val error = runCatching { analyzer.analyze("https://example.com/a.apk", {}, false) }.exceptionOrNull()
        assertEquals(FailureCode.NETWORK_TIMEOUT, (error as PipelineException).code)
    }

    private val release = """{"assets":[
        {"name":"app-universal.apk","size":5,"browser_download_url":"https://github.com/o/r/releases/download/v1/app-universal.apk"},
        {"name":"app.zip","size":6,"browser_download_url":"https://github.com/o/r/releases/download/v1/app.zip"}]}"""

    @Test
    fun githubReleaseOffersItsFilesOrPicksTheBestOneForBatches() {
        val pick = Run({ DirectProbe.File(fileAnalysis, true, FileCategory.APP) }, { release })
        val choices = run("https://github.com/o/r/releases/latest", pick).getOrThrow() as AnalysisOutcome.Choices
        assertEquals(listOf("app-universal.apk", "app.zip"), choices.candidates.map { it.name })
        assertEquals(listOf(AnalysisStage.RESOLVING), pick.stages)
        assertTrue(pick.probed.isEmpty())

        val batch = Run({ DirectProbe.File(fileAnalysis, true, FileCategory.APP) }, { release })
        assertTrue(run("https://github.com/o/r/releases/latest", batch, pickFirst = true).isSuccess)
        assertEquals(listOf("https://github.com/o/r/releases/download/v1/app-universal.apk"), batch.probed)
    }

    @Test
    fun repositoryWithoutReleasesIsASpecificFailure() {
        val r = Run({ error("unused") }, { throw PipelineException(Stage.EXTRACT, FailureCode.HTTP_404) })
        val error = run("https://github.com/o/r", r).exceptionOrNull() as PipelineException
        assertEquals(FailureCode.SOURCE_NO_FILE, error.code)
    }

    @Test
    fun fdroidPackagePageProbesTheRepositoryApk() {
        val r = Run({ DirectProbe.File(fileAnalysis, true, FileCategory.APP) },
            { """{"suggestedVersionCode":42}""" })
        assertTrue(run("https://f-droid.org/packages/org.example.app/", r).isSuccess)
        assertEquals(listOf("https://f-droid.org/repo/org.example.app_42.apk"), r.probed)
    }

    @Test
    fun fileLikeAndHostRules() {
        assertTrue(LinkAnalyzer.fileLike("https://x.example/a/setup.EXE"))
        assertTrue(LinkAnalyzer.fileLike("https://x.example/a.apk?token=1"))
        assertFalse(LinkAnalyzer.fileLike("https://x.example/clip.mp4"))
        assertFalse(LinkAnalyzer.fileLike("https://x.example/watch"))
        assertFalse(LinkAnalyzer.mediaCandidateHost("https://github.com/o/r/blob/x"))
        assertTrue(LinkAnalyzer.mediaCandidateHost("https://video.example/watch"))
    }
}
