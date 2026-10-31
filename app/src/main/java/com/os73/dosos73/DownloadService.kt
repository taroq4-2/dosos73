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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var direct = DirectDownloader()
    @Volatile private var transferNetwork: android.net.Network? = null
    private val mediaDelegate = lazy { MediaEngine(this) { transferNetwork ?: NetworkSettings.requireNetwork(this) } }
    private val media by mediaDelegate
    private val notificationGate = NotificationGate()
    private var detail = ""
    private val processor by lazy { TransferProcessor(this, ::publish, ::transferTask, { userCancelled }, ::completionNotification) }

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

    // Control commands are serialized separately from the transfer. In particular, a cold
    // Resume must not be mistaken for a toggle of the new service's local pause flag.
    private var controlJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val action = intent?.action.takeUnless { flags and START_FLAG_REDELIVERY != 0 }
        if (action != "cancel" && (action != "pause" || job?.isActive != true))
            enterForeground()
        val previous = controlJob
        val command = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            try {
                when (action) {
                    "cancel" -> {
                        userCancelled = true
                        cancelTransfer()
                        job?.join()
                        cancelColdLease()
                    }
                    "pause" -> {
                        if (job?.isActive == true && !cancelled) {
                            if (Transfers.currentTask?.analysis?.direct == true &&
                                Transfers.state.value.phase in setOf(Phase.DOWNLOADING, Phase.PAUSED)) {
                                val task = Transfers.currentTask ?: return@launch
                                // Prevent moving to the next task while the journal write suspends.
                                cancelled = true
                                try {
                                    DownloadQueue.setPaused(this@DownloadService, task.id, true)
                                } catch (error: Exception) {
                                    cancelled = false
                                    throw error
                                }
                                paused.value = true
                                cancelTransfer()
                                job?.join()
                                // A publication may have won the race with Pause. Preserve COMPLETE.
                                if (DownloadQueue.interrupted(this@DownloadService)?.id == task.id)
                                    Transfers.update(Transfers.state.value.copy(phase = Phase.PAUSED, bytesPerSecond = null))
                            }
                        } else {
                            job?.join()
                            val task = DownloadQueue.interrupted(this@DownloadService)
                            if (task != null) {
                                DownloadQueue.setPaused(this@DownloadService, task.id, false)
                                enterForeground()
                                startWorker()
                            }
                        }
                    }
                    else -> {
                        if (cancelled) job?.join()
                        if (job?.isActive != true) startWorker()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Failed acknowledgement keeps the durable lease and its owned partial file.
                Transfers.update(Transfers.state.value.copy(error = R.string.error_storage))
            }
        }
        controlJob = command
        command.invokeOnCompletion { stopWhenIdle() }
        command.start()
        // Pause stops itself after acknowledgement. A cold Resume remains redeliverable
        // if the process dies while its transfer is running.
        return if (action == "cancel") START_NOT_STICKY else START_REDELIVER_INTENT
    }

    private fun enterForeground() {
        startForeground(73, notification(Transfers.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private suspend fun cancelColdLease() {
        val task = DownloadQueue.interrupted(this) ?: return
        // A cold service has no processor finally block. Commit retry history before
        // acknowledging the exact lease; never remove another task's temporary data.
        FailureJournal.record(this, FailedDownload(task, null, true))
        DownloadQueue.finish(this, task.id)
        withContext(Dispatchers.IO) { TransferFiles.discard(this@DownloadService, task.id) }
        Transfers.take()
        Transfers.update(TransferState(Phase.CANCELLED, task.analysis.title))
    }

    private fun stopWhenIdle() {
        if (job?.isActive != true && controlJob?.isActive != true) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(latestStartId)
        }
    }

    private fun startWorker() {
        cancelled = false
        userCancelled = false
        paused.value = false
        // LAZY assigns ownership before a fast failure can reach finally on Main.immediate.
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Preserve the existing reservation API, but commit before executing it.
                Transfers.take()?.let { DownloadQueue.enqueue(this@DownloadService, it) }
                var task = DownloadQueue.take(this@DownloadService)
                while (task != null) {
                    Transfers.reserve(task)
                    Transfers.take()
                    DownloadQueue.rememberActive(this@DownloadService, task)
                    paused.value = false
                    detail = listOfNotNull(task.choice.expectedHeight?.let { "${it}p" }, task.choice.extension.uppercase()).joinToString(" · ")
                    if (processor.run(task, detail) == Phase.FAILED) {
                        cancelled = true // Waiting tasks require an explicit retry/continue.
                        break
                    }
                    ensureActive()
                    if (cancelled) break
                    task = DownloadQueue.take(this@DownloadService)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                cancelled = true
                Transfers.update(Transfers.state.value.copy(phase = Phase.FAILED, error = R.string.error_start))
            } finally {
                job = null
                // Cover an enqueue racing the empty take without starting another FGS from
                // the background. Deliberate Pause/Cancel always leaves the service idle.
                if (!cancelled && DownloadQueue.hasRunnable()) startWorker()
                stopWhenIdle()
            }
        }
        job = worker
        worker.start()
    }

    private suspend fun transferTask(task: DownloadTask, directory: File): File =
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

    private suspend fun <T> withNetworkRecovery(task: DownloadTask, block: suspend () -> T): T =
        recoverNetwork(
            awaitNetwork = {
                NetworkSettings.requireSource(this@DownloadService, task.analysis.url)
                if (!NetworkSettings.permitted(this@DownloadService)) {
                    publish(TransferState(Phase.WAITING, task.analysis.title))
                    NetworkSettings.awaitPermitted(this@DownloadService)
                }
            },
            acquire = { NetworkSettings.requireNetwork(this@DownloadService) },
            attempt = { network ->
                transferNetwork = network
                try {
                    direct = DirectDownloader(NetworkSettings.client(this@DownloadService, network))
                    guardedNetworkTransfer(
                        changes = NetworkSettings.changes(this@DownloadService, network),
                        shouldInterrupt = { permitted ->
                            NetworkSettings.requireSource(this@DownloadService, task.analysis.url)
                            !permitted || !NetworkSettings.permitted(this@DownloadService, network)
                        },
                        cancelTransport = {
                            direct.cancel()
                            if (mediaDelegate.isInitialized()) media.cancel()
                        },
                        block = {
                            publish(TransferState(Phase.DOWNLOADING, task.analysis.title))
                            block()
                        },
                    )
                } finally {
                    transferNetwork = null
                }
            },
            waiting = { publish(TransferState(Phase.WAITING, task.analysis.title)) },
            allowRetry = { !userCancelled },
        )

    private fun publish(state: TransferState) {
        Transfers.update(state.copy(detail = detail))
        if (notificationGate.accept(state.phase, android.os.SystemClock.elapsedRealtime())) {
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
        if (mediaDelegate.isInitialized()) media.cancel()
        job?.cancel()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelTransfer()
        stopSelf()
    }

    override fun onDestroy() {
        cancelled = true
        direct.cancel()
        if (mediaDelegate.isInitialized()) media.cancel()
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
