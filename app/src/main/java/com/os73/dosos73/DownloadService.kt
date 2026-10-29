package com.os73.dosos73

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var direct = DirectDownloader()
    @Volatile private var transferNetwork: android.net.Network? = null
    private val media by lazy { MediaEngine(this) { transferNetwork ?: NetworkSettings.requireNetwork(this) } }
    private var lastNotification = 0L
    private var detail = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    "downloads",
                    getString(R.string.downloads),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }

    private val paused = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var cancelled = false
    private var userCancelled = false
    private var latestStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == "cancel") {
            userCancelled = true
            cancelTransfer()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "pause" && flags and START_FLAG_REDELIVERY == 0) {
            if (
                Transfers.currentTask?.analysis?.direct == true &&
                    Transfers.state.value.phase in setOf(Phase.DOWNLOADING, Phase.PAUSED)
            ) {
                val next = !paused.value
                scope.launch {
                    try {
                        Transfers.currentTask?.let {
                            DownloadQueue.setPaused(this@DownloadService, it.id, next)
                        }
                        paused.value = next
                        publish(
                            Transfers.state.value.copy(
                                phase = if (next) Phase.PAUSED else Phase.DOWNLOADING,
                                bytesPerSecond = null,
                            )
                        )
                    } catch (_: Exception) {
                        publish(Transfers.state.value.copy(error = R.string.error_storage))
                    }
                }
            } else if (job?.isActive != true) {
                scope.launch {
                    DownloadQueue.interrupted(this@DownloadService)?.let {
                        DownloadQueue.setPaused(this@DownloadService, it.id, false)
                    }
                    androidx.core.content.ContextCompat.startForegroundService(
                        this@DownloadService,
                        Intent(this@DownloadService, DownloadService::class.java),
                    )
                }
            }
            return if (job?.isActive == true) START_REDELIVER_INTENT else START_NOT_STICKY
        }
        startForeground(
            73,
            notification(Transfers.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive == true) return START_REDELIVER_INTENT
        cancelled = false
        userCancelled = false
        job =
            scope.launch {
                try {
                    var task =
                        Transfers.take()
                            ?: DownloadQueue.take(this@DownloadService)?.also {
                                Transfers.reserve(it)
                                Transfers.take()
                            }
                    while (task != null) {
                        DownloadQueue.rememberActive(this@DownloadService, task)
                        paused.value = false
                        if (processTask(task) == Phase.FAILED) {
                            cancelled =
                                true // Keep waiting tasks until the user explicitly retries or
                            // continues.
                            break
                        }
                        ensureActive()
                        task =
                            DownloadQueue.take(this@DownloadService)?.also {
                                Transfers.reserve(it)
                                Transfers.take()
                            }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    cancelled = true
                    Transfers.update(
                        Transfers.state.value.copy(
                            phase = Phase.FAILED,
                            error = R.string.error_start,
                        )
                    )
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    job = null
                    stopSelfResult(latestStartId)
                    // A newly queued command may have arrived while the last empty take was
                    // returning.
                    if (!cancelled && DownloadQueue.hasRunnable())
                        androidx.core.content.ContextCompat.startForegroundService(
                            this@DownloadService,
                            Intent(this@DownloadService, DownloadService::class.java),
                        )
                }
            }
        return START_REDELIVER_INTENT
    }

    private suspend fun processTask(task: DownloadTask) = coroutineScope {
        detail =
            listOfNotNull(
                    task.choice.expectedHeight?.let { "${it}p" },
                    task.choice.extension.uppercase(),
                )
                .joinToString(" · ")
        val directory =
            if (task.analysis.direct) TransferFiles.directory(this@DownloadService, task.id)
            else File(cacheDir, "transfer-${UUID.randomUUID()}")
        var stage = Stage.TRANSFER
        var terminal = TransferState(Phase.CANCELLED, task.analysis.title)
        var failureCode: FailureCode? = null
        val store = OutputStore(this@DownloadService)
        suspend fun trace(stage: Stage, result: TimelineResult, bytes: Long? = null, code: FailureCode? = null) {
            try { TaskTimeline.record(this@DownloadService, TimelineEvent(task.id, System.currentTimeMillis(), stage, result, bytes, code)) }
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
                StoragePolicy.requireAvailable(
                    android.os.StatFs(cacheDir.absolutePath).availableBytes,
                    task.analysis.sizeBytes,
                    if (task.tree == null) 2 else 1,
                )
            }
            val output =
                withNetworkRecovery(task) {
                    if (task.analysis.direct) {
                        File(directory, "file.${task.choice.extension}").also { file ->
                            direct.download(
                                task.analysis.url,
                                file,
                                connections = if (NetworkSettings.parallelConnections(this@DownloadService)) 2 else 1,
                                progress = { bytes, total, speed ->
                                    publish(
                                        TransferState(
                                            if (paused.value) Phase.PAUSED else Phase.DOWNLOADING,
                                            task.analysis.title,
                                            bytes,
                                            total,
                                            Safety.progress(bytes, total),
                                            speed,
                                        )
                                    )
                                },
                                awaitReady = { paused.first { !it } },
                            )
                        }
                    } else
                        media.download(task, directory) { percent, processing ->
                            publish(
                                TransferState(
                                    if (processing) Phase.PROCESSING else Phase.DOWNLOADING,
                                    task.analysis.title,
                                    percent = if (processing) null else percent,
                                )
                            )
                        }
                }
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
            withContext(NonCancellable) { trace(stage, TimelineResult.INTERRUPTED) }
            terminal =
                TransferState(
                    if (userCancelled) Phase.CANCELLED else Phase.PAUSED,
                    task.analysis.title,
                )
        } catch (e: Exception) {
            val failure = FailureClassifier.classify(stage, e)
            failureCode = failure.code
            withContext(NonCancellable) { trace(failure.stage, TimelineResult.FAILED, code = failure.code) }
            Diagnostics.event(failure.stage, "failed", code = failure.code)
            terminal =
                TransferState(Phase.FAILED, task.analysis.title, error = failure.messageResource())
        } finally {
            val retain = task.analysis.direct && terminal.phase != Phase.COMPLETE && !userCancelled
            withContext(NonCancellable + Dispatchers.IO) {
                if (!retain) directory.deleteRecursively()
            }
            var acknowledged = terminal.phase == Phase.COMPLETE
            if (terminal.phase in setOf(Phase.FAILED, Phase.CANCELLED)) {
                withContext(NonCancellable) {
                    acknowledged =
                        runCatching {
                                FailureJournal.record(
                                    this@DownloadService,
                                    FailedDownload(task, failureCode, userCancelled),
                                )
                            }
                            .isSuccess
                }
            }
            // A system stop retains its lease; failed journal writes must never discard the task.
            if (acknowledged)
                withContext(NonCancellable) {
                    DownloadQueue.finish(this@DownloadService, task.id)
                    if (terminal.phase == Phase.COMPLETE)
                        withContext(Dispatchers.IO) { store.forget(task.id) }
                }
            Transfers.update(terminal.copy(detail = detail))
            if (terminal.phase == Phase.COMPLETE) completionNotification(terminal)
        }
        terminal.phase
    }

    private suspend fun <T> withNetworkRecovery(task: DownloadTask, block: suspend () -> T): T =
        coroutineScope {
            var retries = 0
            while (true) {
                if (!NetworkSettings.permitted(this@DownloadService)) {
                    publish(TransferState(Phase.WAITING, task.analysis.title))
                    NetworkSettings.awaitPermitted(this@DownloadService)
                }
                val network = NetworkSettings.requireNetwork(this@DownloadService)
                transferNetwork = network
                direct = DirectDownloader(NetworkSettings.client(this@DownloadService, network))
                var networkLost = false
                val guard = launch {
                    NetworkSettings.changes(this@DownloadService).collect { permitted ->
                        if (!permitted || !NetworkSettings.permitted(this@DownloadService, network)) {
                            networkLost = true
                            direct.cancel()
                            media.cancel()
                        }
                    }
                }
                try {
                    return@coroutineScope block()
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    // Retry only a connection-policy interruption, not arbitrary server/storage
                    // failures.
                    if (!networkLost || retries >= 2 || userCancelled) throw error
                    retries++
                    publish(TransferState(Phase.WAITING, task.analysis.title))
                    delay(1000L shl retries)
                } finally {
                    guard.cancelAndJoin()
                    transferNetwork = null
                }
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }

    private fun publish(state: TransferState) {
        Transfers.update(state.copy(detail = detail))
        val now = System.currentTimeMillis()
        if (now - lastNotification > 1000) {
            lastNotification = now
            getSystemService(NotificationManager::class.java).notify(73, notification(state))
        }
    }

    private fun notification(state: TransferState): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, DownloadService::class.java).setAction("cancel"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(phaseText(state.phase)))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(state.active)
            .setProgress(100, ((state.percent ?: 0f) * 100).toInt(), state.percent == null)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    private fun completionNotification(state: TransferState) {
        val uri = state.uri ?: return
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, state.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val open =
            PendingIntent.getActivity(
                this,
                74,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(this, "downloads")
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(getString(R.string.complete))
                .setContentText(state.title)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(0, getString(R.string.open), open)
                .build()
        getSystemService(NotificationManager::class.java).notify(74, notification)
    }

    private fun cancelTransfer() {
        cancelled = true
        direct.cancel()
        media.cancel()
        job?.cancel()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelTransfer()
        stopSelf()
    }

    override fun onDestroy() {
        cancelled = true
        direct.cancel()
        media.cancel()
        scope.cancel()
        super.onDestroy()
    }
}

fun phaseText(phase: Phase) =
    when (phase) {
        Phase.IDLE -> R.string.ready
        Phase.PAUSED -> R.string.paused
        Phase.WAITING -> R.string.waiting_for_network
        Phase.DOWNLOADING -> R.string.downloading
        Phase.PROCESSING -> R.string.processing
        Phase.SAVING -> R.string.saving
        Phase.COMPLETE -> R.string.complete
        Phase.CANCELLED -> R.string.cancelled
        Phase.FAILED -> R.string.failed
    }
