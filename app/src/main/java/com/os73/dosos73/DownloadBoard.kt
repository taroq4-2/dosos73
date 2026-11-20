package com.os73.dosos73

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Status of one row in the downloads screen, the widget and notifications. */
enum class BoardStatus { ACTIVE, QUEUED, PAUSED, COMPLETED, FAILED }

/**
 * One download as every surface sees it. Derived, never stored: the sources of truth stay
 * [Transfers] (the running task), [DownloadQueue] (waiting/paused), [FailureJournal] (failed or
 * cancelled) and [LibraryRepository] (published files).
 */
internal data class BoardItem(
    val key: String,
    val title: String,
    val status: BoardStatus,
    val category: FileCategory,
    val bytes: Long? = null,
    val total: Long? = null,
    val percent: Float? = null,
    val bytesPerSecond: Long? = null,
    val phase: Phase? = null,
    val error: Int? = null,
    val date: Long? = null,
    val task: DownloadTask? = null,
    val file: SavedFile? = null,
    val failure: FailedDownload? = null,
) {
    /** Seconds left, only when it can be estimated honestly (known total and a current rate). */
    val secondsLeft: Long?
        get() {
            val size = total ?: return null
            val done = bytes ?: return null
            val rate = bytesPerSecond?.takeIf { it > 0 } ?: return null
            return ((size - done).coerceAtLeast(0) + rate - 1) / rate
        }
}

/** Counts per status plus the item a compact surface (widget, notification) should show. */
internal data class BoardSummary(val counts: Map<BoardStatus, Int>, val focus: BoardItem?) {
    fun count(status: BoardStatus) = counts[status] ?: 0
    val total get() = counts.values.sum()
}

internal object DownloadBoard {
    /** Task titles carry no extension (it is a separate choice); show "name.ext" like saved files. */
    fun displayName(task: DownloadTask, title: String = task.analysis.title): String {
        val extension = task.choice.extension.lowercase()
        return if (extension.isEmpty() || title.lowercase().endsWith(".$extension")) title else "$title.$extension"
    }

    /** Pure merge of the four sources; deterministic order: active, queued/paused, failed, completed (newest first). */
    fun build(
        transfer: TransferState,
        current: DownloadTask?,
        queue: List<DownloadTask>,
        failures: List<FailedDownload>,
        files: List<SavedFile>,
        leased: DownloadTask? = null,
        /** Committed bytes/total of [leased] from its resume checkpoint, when known. */
        leasedProgress: Pair<Long, Long>? = null,
    ): List<BoardItem> {
        val items = mutableListOf<BoardItem>()
        val activeId = current?.id?.takeIf { transfer.active }
        // A durable lease with no live transfer: paused by the user, or interrupted by process
        // death. Shown so a cold process (widget, relaunch) never hides unfinished work.
        if (leased != null && activeId == null && queue.none { it.id == leased.id }) {
            items += BoardItem(
                key = "task:${leased.id}", title = displayName(leased),
                status = if (leased.userPaused) BoardStatus.PAUSED else BoardStatus.QUEUED,
                category = categoryOf(leased.choice), bytes = leasedProgress?.first,
                total = leasedProgress?.second ?: leased.analysis.sizeBytes,
                percent = leasedProgress?.let { (done, size) -> (done.toDouble() / size).toFloat().coerceIn(0f, 1f) },
                task = leased,
            )
        }
        if (activeId != null) {
            items += BoardItem(
                key = "task:$activeId",
                title = displayName(current, transfer.title.ifBlank { current.analysis.title }),
                status = if (transfer.phase == Phase.PAUSED) BoardStatus.PAUSED else BoardStatus.ACTIVE,
                category = categoryOf(current.choice),
                bytes = transfer.bytes.takeIf { it > 0 },
                total = transfer.total ?: current.analysis.sizeBytes,
                percent = transfer.percent,
                bytesPerSecond = transfer.bytesPerSecond?.takeIf { transfer.phase == Phase.DOWNLOADING },
                phase = transfer.phase,
                error = transfer.error,
                task = current,
            )
        }
        for (task in queue) {
            if (task.id == activeId) continue
            items += BoardItem(
                key = "task:${task.id}", title = displayName(task),
                status = if (task.userPaused) BoardStatus.PAUSED else BoardStatus.QUEUED,
                category = categoryOf(task.choice), total = task.analysis.sizeBytes, task = task,
            )
        }
        for (failure in failures) {
            if (failure.task.id == activeId || failure.task.id == leased?.id || queue.any { it.id == failure.task.id }) continue
            items += BoardItem(
                key = "failed:${failure.task.id}", title = displayName(failure.task), status = BoardStatus.FAILED,
                category = categoryOf(failure.task.choice), total = failure.task.analysis.sizeBytes,
                error = failure.code?.let { PipelineException(Stage.TRANSFER, it).messageResource() },
                task = failure.task, failure = failure,
            )
        }
        for (file in files.sortedByDescending { it.date }) {
            items += BoardItem(
                key = "file:${file.uri}", title = file.name, status = BoardStatus.COMPLETED,
                category = categoryOf(file), bytes = file.bytes, total = file.bytes, percent = 1f,
                date = file.date, file = file,
            )
        }
        return items
    }

    fun summarize(items: List<BoardItem>): BoardSummary = BoardSummary(
        counts = items.groupingBy { it.status }.eachCount(),
        focus = items.firstOrNull { it.status == BoardStatus.ACTIVE }
            ?: items.firstOrNull { it.status == BoardStatus.PAUSED && it.phase != null }
            ?: items.firstOrNull { it.status == BoardStatus.QUEUED },
    )

    fun categoryOf(choice: Choice): FileCategory = when (choice.kind) {
        Kind.VIDEO -> FileCategory.VIDEO
        Kind.AUDIO -> FileCategory.AUDIO
        Kind.FILE -> FileTypes.category(choice.extension)
    }

    fun categoryOf(file: SavedFile): FileCategory {
        val byName = FileTypes.category(file.name.substringAfterLast('.', "").lowercase())
        if (byName != FileCategory.OTHER) return byName
        return when {
            file.mime.startsWith("video/") -> FileCategory.VIDEO
            file.mime.startsWith("audio/") -> FileCategory.AUDIO
            file.mime.startsWith("image/") -> FileCategory.IMAGE
            else -> FileTypes.forMime(file.mime)?.category ?: FileCategory.OTHER
        }
    }

    /** Live board for the app UI, widget and notifications. */
    fun observe(progress: (DownloadTask) -> Pair<Long, Long>? = { null }): Flow<List<BoardItem>> = combine(
        Transfers.state, DownloadQueue.tasks, FailureJournal.failures, LibraryRepository.files, DownloadQueue.leased,
    ) { transfer, queue, failures, files, leased ->
        build(transfer, Transfers.currentTask, queue, failures, files, leased, leased?.let(progress))
    }.distinctUntilChanged()
}
