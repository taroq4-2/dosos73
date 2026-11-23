package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

/** Phase E: widget model, notification text and external entry points. */
class SurfacesPhaseETest {
    private val apk = Choice("direct", Kind.FILE, "apk", "Original")
    private fun task(title: String, paused: Boolean = false) =
        DownloadTask(Analysis("https://example.com/$title", title, listOf(apk), true, 1_000_000), apk, null, userPaused = paused)

    @Test
    fun idleWidgetSaysNothingIsRunning() {
        val model = WidgetModel.from(emptyList(), TransferState(), showNames = false)
        assertEquals(WidgetState.IDLE, model.state)
        assertNull(model.title)
    }

    @Test
    fun activeDownloadDrivesTheWidgetButHidesTheNameByDefault() {
        val running = task("secret-report")
        val board = DownloadBoard.build(TransferState(Phase.DOWNLOADING, "secret-report", 400_000, 1_000_000, 0.4f, 300_000),
            running, emptyList(), emptyList(), emptyList())
        val hidden = WidgetModel.from(board, Transfers.state.value, showNames = false)
        assertEquals(WidgetState.ACTIVE, hidden.state)
        assertNull("file names stay off the home screen unless allowed", hidden.title)
        assertEquals(FileCategory.APP, hidden.category)
        assertEquals(40, hidden.percent)
        assertEquals(400_000L, hidden.bytes)
        assertEquals(1_000_000L, hidden.total)
        assertEquals(262_144L, hidden.bytesPerSecond) // rounded down to 64 KiB steps
        assertEquals(1, hidden.pending)
        val shown = WidgetModel.from(board, TransferState(), showNames = true)
        assertEquals("secret-report.apk", shown.title)
    }

    @Test
    fun smallSpeedChangesDoNotChangeTheModel() {
        val running = task("a")
        fun at(speed: Long) = WidgetModel.from(DownloadBoard.build(
            TransferState(Phase.DOWNLOADING, "a", 10, 1_000_000, 0.4f, speed), running, emptyList(), emptyList(), emptyList()),
            TransferState(), false)
        assertEquals(at(300_000), at(310_000)) // same 64 KiB bucket, no redraw
    }

    @Test
    fun pausedLeaseFromAColdProcessIsStillShown() {
        // No live transfer (fresh process), only the durable lease: the widget must not say "idle".
        val board = DownloadBoard.build(TransferState(), null, emptyList(), emptyList(), emptyList(), leased = task("big", paused = true))
        val model = WidgetModel.from(board, TransferState(), showNames = false)
        assertEquals(WidgetState.PAUSED, model.state)
        assertEquals(1, model.pending)
    }

    @Test
    fun interruptedLeaseThatWasNotPausedShowsAsWaiting() {
        val board = DownloadBoard.build(TransferState(), null, emptyList(), emptyList(), emptyList(), leased = task("x"))
        assertEquals(BoardStatus.QUEUED, board.single().status)
        assertEquals(WidgetState.QUEUED, WidgetModel.from(board, TransferState(), false).state)
    }

    @Test
    fun leaseIsNotDuplicatedWhenTheTransferIsLive() {
        val running = task("live")
        val board = DownloadBoard.build(TransferState(Phase.DOWNLOADING, "live", 1, 2, 0.5f), running,
            emptyList(), emptyList(), emptyList(), leased = running)
        assertEquals(1, board.size)
        assertEquals(BoardStatus.ACTIVE, board.single().status)
    }

    @Test
    fun lastOutcomeIsShownWhenNothingRuns() {
        val done = WidgetModel.from(emptyList(), TransferState(Phase.COMPLETE, "movie", 5, 5, 1f), showNames = false)
        assertEquals(WidgetState.COMPLETED, done.state)
        assertNull(done.title)
        val failed = WidgetModel.from(emptyList(), TransferState(Phase.FAILED, "x", error = R.string.error_http404), true)
        assertEquals(WidgetState.FAILED, failed.state)
        assertEquals(R.string.error_http404, failed.error)
    }

    @Test
    fun aCancelledTransferIsLabelledCancelledNotFailed() {
        val items = DownloadBoard.build(TransferState(), null, emptyList(),
            listOf(FailedDownload(task("stopped"), null, true), FailedDownload(task("broken"), FailureCode.DOWNLOAD_FAILED, false)),
            emptyList())
        val stopped = items.single { it.title == "stopped.apk" }
        val broken = items.single { it.title == "broken.apk" }
        assertEquals(R.string.status_cancelled, DownloadBoard.failureLabel(stopped))
        assertNotEquals(R.string.status_cancelled, DownloadBoard.failureLabel(broken))
        assertNotEquals(R.string.status_failed, DownloadBoard.failureLabel(broken)) // the specific reason wins
    }

    @Test
    fun etaOnlyWithKnownSizeAndRate() {
        assertEquals(6L, TransferText.etaSeconds(400, 1_000, 100))
        assertNull(TransferText.etaSeconds(400, null, 100))
        assertNull(TransferText.etaSeconds(400, 1_000, 0))
        assertEquals("1:05", durationLabel(65))
        assertEquals("1:01:01", durationLabel(3661))
    }

    @Test
    fun onlyKnownPagesCanBeOpenedFromOutside() {
        fun page(value: String?) = AppEntry.page(android.content.Intent().apply { if (value != null) putExtra(AppEntry.EXTRA_OPEN, value) })
        // android.jar stubs return null for Intent extras in JVM tests; the allowlist is the contract.
        for (value in listOf("settings", "privacy", "../x", "")) assertNull(value, page(value))
    }

    @Test
    fun everyNotificationOwnsADistinctId() {
        // A shared ID lets one notification silently replace or cancel another (found in Phase G:
        // the paused notification shared 75 with the deferred-start reminder, and failures shared
        // 76 with local playback).
        val ids = listOf(DownloadNotifications.ID_PROGRESS, DownloadNotifications.ID_COMPLETED,
            DownloadNotifications.ID_PAUSED, DownloadNotifications.ID_FAILED,
            DeferredTransferScheduler.NOTIFICATION_ID, LocalPlaybackService.ID)
        assertEquals(ids.toString(), ids.size, ids.toSet().size)
    }
}
