package com.os73.dosos73

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/** Owns verification/publication and journal acknowledgement, independently of Service callbacks. */
internal class TransferProcessor(
    private val context: Context,
    private val publish: (TransferState) -> Unit,
    private val transfer: suspend (DownloadTask, File) -> File,
    private val isUserCancelled: () -> Boolean,
    private val onComplete: (TransferState) -> Unit,
) {
    suspend fun run(task: DownloadTask, detail: String) = coroutineScope {
        val directory =
            if (task.analysis.direct) TransferFiles.directory(context, task.id)
            else File(context.cacheDir, "transfer-${UUID.randomUUID()}")
        var stage = Stage.TRANSFER
        var terminal = TransferState(Phase.CANCELLED, task.analysis.title)
        var failureCode: FailureCode? = null
        val store = OutputStore(context) { uri, mime ->
            terminal = TransferState(Phase.COMPLETE, task.analysis.title, uri = uri, mime = mime)
        }
        suspend fun trace(stage: Stage, result: TimelineResult, bytes: Long? = null, code: FailureCode? = null) {
            try { TaskTimeline.record(context, TimelineEvent(task.id, System.currentTimeMillis(), stage, result, bytes, code)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { Diagnostics.event(stage, "timeline_unavailable") }
        }
        try {
            trace(Stage.TRANSFER, TimelineResult.STARTED)
            val recovered = withContext(Dispatchers.IO) { store.recover(task) }
            if (recovered != null) {
                trace(Stage.PUBLISH, TimelineResult.RECOVERED)
                terminal =
                    TransferState(
                        Phase.COMPLETE,
                        task.analysis.title,
                        uri = recovered.first,
                        mime = recovered.second,
                    )
                return@coroutineScope Phase.COMPLETE
            }
            withContext(Dispatchers.IO) {
                store.preflight(task.tree)
                check(directory.mkdirs() || directory.isDirectory)
                val partial = File(directory, "file.${task.choice.extension}")
                val checkpoint = if (task.analysis.direct) ResumeJournal(partial).read()?.takeIf {
                    it.sourceHash == ResumeCheckpoint.hash(Safety.url(task.analysis.url)) &&
                        partial.isFile && partial.length() >= it.offset
                } else null
                val expected = checkpoint?.total ?: task.analysis.sizeBytes ?: task.choice.estimatedBytes
                StoragePolicy.requireAvailable(
                    android.os.StatFs(context.cacheDir.absolutePath).availableBytes, expected,
                    if (task.tree == null) 2 else 1, checkpoint?.offset ?: 0,
                )
            }
            val output = transfer(task, directory)
            ensureActive()
            stage = Stage.VERIFY
            Diagnostics.event(stage, "started", output.length())
            trace(stage, TimelineResult.STARTED, output.length())
            publish(TransferState(Phase.PROCESSING, task.analysis.title))
            withContext(Dispatchers.IO) {
                store.validate(
                    output,
                    task.choice.kind,
                    task.choice.expectedHeight,
                    task.choice.expectsAudio,
                )
            }
            Diagnostics.event(stage, "completed", output.length())
            withContext(NonCancellable) { trace(stage, TimelineResult.COMPLETED, output.length()) }
            stage = Stage.PUBLISH
            Diagnostics.event(stage, "started", output.length())
            trace(stage, TimelineResult.STARTED, output.length())
            publish(TransferState(Phase.SAVING, task.analysis.title))
            val (uri, mime) =
                store.save(output, task) { done, total ->
                    publish(
                        TransferState(
                            Phase.SAVING,
                            task.analysis.title,
                            done,
                            total,
                            Safety.progress(done, total),
                        )
                    )
                }
            Diagnostics.event(stage, "completed", output.length())
            withContext(NonCancellable) { trace(stage, TimelineResult.COMPLETED, output.length()) }
            // Publication is the commit point: do not turn a committed file into a cancellation.
            terminal =
                TransferState(
                    Phase.COMPLETE,
                    task.analysis.title,
                    output.length(),
                    output.length(),
                    1f,
                    uri = uri,
                    mime = mime,
                )
        } catch (_: CancellationException) {
            if (terminal.phase != Phase.COMPLETE) {
                withContext(NonCancellable) { trace(stage, TimelineResult.INTERRUPTED) }
                terminal =
                    TransferState(
                        if (isUserCancelled()) Phase.CANCELLED else Phase.PAUSED,
                        task.analysis.title,
                    )
            }
        } catch (e: Exception) {
            if (terminal.phase != Phase.COMPLETE) {
                val failure = FailureClassifier.classify(stage, e)
                failureCode = failure.code
                withContext(NonCancellable) { trace(failure.stage, TimelineResult.FAILED, code = failure.code) }
                Diagnostics.event(failure.stage, "failed", code = failure.code)
                terminal =
                    TransferState(Phase.FAILED, task.analysis.title, error = failure.messageResource())
            }
        } finally {
            // Keep every dispatcher return in finalization inside an active context. A
            // cancelled caller must not interrupt queue acknowledgement after a commit.
            withContext(NonCancellable) {
                val retain = task.analysis.direct && terminal.phase != Phase.COMPLETE && !isUserCancelled()
                withContext(Dispatchers.IO) {
                    if (!retain) directory.deleteRecursively()
                }
                var acknowledged = terminal.phase == Phase.COMPLETE
                if (terminal.phase in setOf(Phase.FAILED, Phase.CANCELLED)) {
                    acknowledged =
                        runCatching {
                                FailureJournal.record(
                                    context,
                                    FailedDownload(task, failureCode, isUserCancelled()),
                                )
                            }
                            .isSuccess
                }
                // A system stop retains its lease; failed journal writes must never discard the task.
                if (acknowledged) {
                    DownloadQueue.finish(context, task.id)
                    if (terminal.phase == Phase.COMPLETE)
                        withContext(Dispatchers.IO) { store.forget(task.id) }
                }
                Transfers.update(terminal.copy(detail = detail))
                if (terminal.phase == Phase.COMPLETE) onComplete(terminal)
            }
        }
        terminal.phase
    }

}
