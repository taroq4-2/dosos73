package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

/** Phase C: pure presentation reducer, unified downloads board, readable publication names. */
class ArchitecturePhaseCTest {
    private val fileChoice = Choice("direct", Kind.FILE, "apk", "Original")
    private val video = Choice("18", Kind.VIDEO, "mp4", "360p")
    private val audio = Choice("140", Kind.AUDIO, "m4a", "128k")
    private fun analysis(vararg choices: Choice) =
        Analysis("https://example.com/x", "x", choices.toList(), direct = choices.single() == fileChoice, sizeBytes = 100)

    // ---- AnalysisReducer ----

    @Test
    fun startClearsEverySliceOfThePreviousResult() {
        val dirty = ScreenState(url = "u", analysis = analysis(fileChoice), selected = fileChoice, error = 1,
            stage = AnalysisStage.FAILED, candidates = listOf(LinkCandidate("a", "b", null)), verified = true,
            category = FileCategory.APP, retryAfter = 5, pendingShared = "keep")
        val started = AnalysisReducer.started(dirty)
        assertEquals(ScreenState(url = "u", busy = true, stage = AnalysisStage.VALIDATING, pendingShared = "keep"), started)
    }

    @Test
    fun stagesOnlyMoveForwardWhileBusy() {
        var s = AnalysisReducer.started(ScreenState(url = "u"))
        s = AnalysisReducer.stage(s, AnalysisStage.CONNECTING)
        s = AnalysisReducer.stage(s, AnalysisStage.PROBING)
        assertEquals(AnalysisStage.PROBING, s.stage)
        // A retried probe reports CONNECTING again: never shown as going backwards.
        assertEquals(AnalysisStage.PROBING, AnalysisReducer.stage(s, AnalysisStage.CONNECTING).stage)
        // Terminal stages are not progress, and an idle screen never shows progress.
        assertEquals(AnalysisStage.PROBING, AnalysisReducer.stage(s, AnalysisStage.READY).stage)
        val idle = ScreenState(url = "u")
        assertSame(idle, AnalysisReducer.stage(idle, AnalysisStage.EXTRACTING))
    }

    @Test
    fun readySelectsThePreferredKindOrTheFirstChoice() {
        val busy = AnalysisReducer.started(ScreenState(url = "typed"))
        val media = AnalysisOutcome.Ready(Analysis("u", "t", listOf(video, audio), false))
        assertEquals(audio, AnalysisReducer.ready(busy, "canonical", media, Kind.AUDIO).selected)
        val ready = AnalysisReducer.ready(busy, "canonical", media, Kind.FILE)
        assertEquals(video, ready.selected)
        assertEquals("canonical", ready.url)
        assertFalse(ready.busy)
        assertEquals(AnalysisStage.READY, ready.stage)
        val file = AnalysisReducer.ready(busy, "u", AnalysisOutcome.Ready(analysis(fileChoice), true, FileCategory.APP), Kind.VIDEO)
        assertEquals(true, file.verified)
        assertEquals(FileCategory.APP, file.category)
    }

    @Test
    fun choicesAndFailuresAreTerminalAndSpecific() {
        val busy = AnalysisReducer.started(ScreenState(url = "u"))
        val candidates = listOf(LinkCandidate("a.apk", "https://x/a.apk", 1))
        val choices = AnalysisReducer.ready(busy, "u", AnalysisOutcome.Choices(candidates), Kind.VIDEO)
        assertEquals(candidates, choices.candidates)
        assertNull(choices.analysis)
        val limited = AnalysisReducer.failed(busy, PipelineException(Stage.EXTRACT, FailureCode.HTTP_429, RetryAfter(30)))
        assertEquals(R.string.error_http429, limited.error)
        assertEquals(30L, limited.retryAfter)
        assertEquals(AnalysisStage.FAILED, limited.stage)
        assertFalse(limited.busy)
        assertEquals(R.string.error_web_page,
            AnalysisReducer.failed(busy, PipelineException(Stage.EXTRACT, FailureCode.WEB_PAGE_NOT_FILE)).error)
        assertEquals(AnalysisStage.CANCELED, AnalysisReducer.canceled(busy).stage)
        assertEquals(R.string.error_timeout, AnalysisReducer.timedOut(busy).error)
    }

    // ---- DownloadBoard ----

    private fun task(title: String, paused: Boolean = false, choice: Choice = fileChoice) =
        DownloadTask(Analysis("https://example.com/$title", title, listOf(choice), true, 1000), choice, null, userPaused = paused)

    @Test
    fun boardMergesTheFourSourcesWithoutDuplicates() {
        val active = task("running")
        val waiting = task("waiting")
        val held = task("held", paused = true)
        val failed = FailedDownload(task("broken"), FailureCode.HTTP_404, false)
        val retried = FailedDownload(waiting, FailureCode.NETWORK_TIMEOUT, false) // retried: now queued
        val older = SavedFile("content://1", "old.pdf", "application/pdf", 10, date = 1)
        val newer = SavedFile("content://2", "clip.mp4", "video/mp4", 20, date = 2)
        val transfer = TransferState(Phase.DOWNLOADING, "running", 250, 1000, 0.25f, 50)
        val board = DownloadBoard.build(transfer, active, listOf(active, waiting, held), listOf(failed, retried), listOf(older, newer))
        assertEquals(listOf("running.apk", "waiting.apk", "held.apk", "broken.apk", "clip.mp4", "old.pdf"), board.map { it.title })
        assertEquals(listOf(BoardStatus.ACTIVE, BoardStatus.QUEUED, BoardStatus.PAUSED, BoardStatus.FAILED,
            BoardStatus.COMPLETED, BoardStatus.COMPLETED), board.map { it.status })
        assertEquals(R.string.error_http404, board[3].error)
        assertEquals(FileCategory.APP, board[0].category)
        assertEquals(FileCategory.VIDEO, board[4].category)
        assertEquals(FileCategory.DOCUMENT, board[5].category)
        assertEquals(15L, board[0].secondsLeft) // 750 bytes at 50 B/s
    }

    @Test
    fun honestEtaAndPausedActiveTransfer() {
        val active = task("a")
        val paused = DownloadBoard.build(TransferState(Phase.PAUSED, "a", 10, null, null, 99), active, emptyList(), emptyList(), emptyList())
        assertEquals(BoardStatus.PAUSED, paused.single().status)
        assertNull(paused.single().bytesPerSecond) // no speed while paused
        assertNull(paused.single().secondsLeft)
        // Total unknown from the transfer: fall back to the analysed size, still no rate -> no ETA.
        assertEquals(1000L, paused.single().total)
        val idle = DownloadBoard.build(TransferState(Phase.COMPLETE, "a"), active, emptyList(), emptyList(), emptyList())
        assertTrue(idle.isEmpty()) // a completed transfer is represented by its saved file
    }

    @Test
    fun summaryFocusesTheRunningDownload() {
        val active = task("run")
        val board = DownloadBoard.build(TransferState(Phase.DOWNLOADING, "run", 1, 2, .5f), active,
            listOf(task("q")), emptyList(), listOf(SavedFile("content://f", "f.zip", "application/zip", 1, 1)))
        val summary = DownloadBoard.summarize(board)
        assertEquals("run.apk", summary.focus!!.title)
        assertEquals(1, summary.count(BoardStatus.ACTIVE))
        assertEquals(1, summary.count(BoardStatus.QUEUED))
        assertEquals(1, summary.count(BoardStatus.COMPLETED))
        assertEquals(0, summary.count(BoardStatus.FAILED))
        assertEquals(3, summary.total)
        assertNull(DownloadBoard.summarize(emptyList()).focus)
    }

    // ---- PublicationNames ----

    @Test
    fun readableNamesAvoidEveryTakenNameCaseInsensitively() {
        assertEquals("Report.pdf", PublicationNames.choose("Report", "pdf", "id1", emptySet()))
        assertEquals("Report (2).pdf", PublicationNames.choose("Report", "pdf", "id1", setOf("report.PDF")))
        assertEquals("Report (3).pdf", PublicationNames.choose("Report", "pdf", "id1", setOf("Report.pdf", "Report (2).pdf")))
        assertEquals("download.apk", PublicationNames.choose("", "apk", "id1", emptySet()))
        val crowded = (listOf("x.bin") + (2..50).map { "x ($it).bin" }).toSet()
        assertEquals("x-id1.bin", PublicationNames.choose("x", "bin", "id1", crowded))
    }
}
