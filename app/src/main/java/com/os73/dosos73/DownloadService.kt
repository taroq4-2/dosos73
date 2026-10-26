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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.util.UUID

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val direct = DirectDownloader()
    private val media by lazy { MediaEngine(this) }
    private var lastNotification = 0L
    private var detail = ""
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads", getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW))
    }
    private val paused = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var cancelled = false
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { cancelTransfer(); return START_NOT_STICKY }
        if (intent?.action == "pause") {
            if (Transfers.currentTask?.analysis?.direct == true && Transfers.state.value.phase in setOf(Phase.DOWNLOADING, Phase.PAUSED)) {
                paused.value = !paused.value
                publish(Transfers.state.value.copy(phase = if(paused.value) Phase.PAUSED else Phase.DOWNLOADING, bytesPerSecond = null))
            }
            return START_NOT_STICKY
        }
        startForeground(73, notification(Transfers.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive == true) return START_NOT_STICKY
        cancelled = false
        job = scope.launch {
            try {
                var task = Transfers.take() ?: DownloadQueue.take(this@DownloadService)?.also { Transfers.reserve(it); Transfers.take() }
                while (task != null) {
                    paused.value = false
                    processTask(task)
                    ensureActive()
                    task = DownloadQueue.take(this@DownloadService)?.also { Transfers.reserve(it); Transfers.take() }
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                job = null
                stopSelfResult(startId)
                // A newly queued command may have arrived while the last empty take was returning.
                if (!cancelled && DownloadQueue.tasks.value.isNotEmpty())
                    androidx.core.content.ContextCompat.startForegroundService(this@DownloadService, Intent(this@DownloadService, DownloadService::class.java))
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun processTask(task: DownloadTask) = coroutineScope {
        detail = listOfNotNull(task.choice.expectedHeight?.let { "${it}p" }, task.choice.extension.uppercase()).joinToString(" · ")
            val directory = File(cacheDir, "transfer-${UUID.randomUUID()}")
            var stage = Stage.TRANSFER
            var terminal = TransferState(Phase.CANCELLED, task.analysis.title)
            try {
                withContext(Dispatchers.IO) { check(directory.mkdirs()) }
                StoragePolicy.requireAvailable(android.os.StatFs(cacheDir.absolutePath).availableBytes,
                    task.analysis.sizeBytes, if (task.tree == null) 2 else 1)
                val output = if (task.analysis.direct) {
                    File(directory, "file.${task.choice.extension}").also { file ->
                        direct.download(task.analysis.url, file, progress = { bytes, total, speed ->
                            publish(TransferState(if(paused.value) Phase.PAUSED else Phase.DOWNLOADING, task.analysis.title, bytes, total,
                                Safety.progress(bytes, total), speed))
                        }, awaitReady = { paused.first { !it } })
                    }
                } else media.download(task, directory) { percent, processing ->
                    publish(TransferState(if (processing) Phase.PROCESSING else Phase.DOWNLOADING,
                        task.analysis.title, percent = if(processing) null else percent))
                }
                ensureActive()
                stage = Stage.VERIFY
                Diagnostics.event(stage, "started", output.length())
                publish(TransferState(Phase.PROCESSING, task.analysis.title))
                val store = OutputStore(this@DownloadService)
                withContext(Dispatchers.IO) { store.validate(output, task.choice.kind, task.choice.expectedHeight, task.choice.expectsAudio) }
                Diagnostics.event(stage, "completed", output.length())
                stage = Stage.PUBLISH
                Diagnostics.event(stage, "started", output.length())
                publish(TransferState(Phase.SAVING, task.analysis.title))
                val (uri, mime) = store.save(output, task)
                Diagnostics.event(stage, "completed", output.length())
                // Publication is the commit point: do not turn a committed file into a cancellation.
                terminal = TransferState(Phase.COMPLETE, task.analysis.title, output.length(), output.length(), 1f, uri = uri, mime = mime)
            } catch (_: CancellationException) {
                terminal = TransferState(Phase.CANCELLED, task.analysis.title)
            } catch (e: Exception) {
                val failure = FailureClassifier.classify(stage, e)
                Diagnostics.event(failure.stage, "failed", code = failure.code)
                terminal = TransferState(Phase.FAILED, task.analysis.title, error = failure.messageResource())
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
                // Ownership remains active through cleanup before publishing terminal state.
                Transfers.update(terminal.copy(detail = detail))
                if (terminal.phase == Phase.COMPLETE) completionNotification(terminal)
            }
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
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "downloads").setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(phaseText(state.phase)))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(state.active)
            .setProgress(100, ((state.percent ?: 0f) * 100).toInt(), state.percent == null)
            .addAction(0, getString(R.string.cancel), cancel).build()
    }
    private fun completionNotification(state: TransferState) {
        val uri = state.uri ?: return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, state.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val open = PendingIntent.getActivity(this, 74, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "downloads").setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.complete)).setContentText(state.title).setAutoCancel(true)
            .setContentIntent(open).addAction(0, getString(R.string.open), open).build()
        getSystemService(NotificationManager::class.java).notify(74, notification)
    }
    private fun cancelTransfer() {
        cancelled = true
        direct.cancel(); media.cancel(); job?.cancel()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancelTransfer(); stopSelf() }
    override fun onDestroy() { direct.cancel(); media.cancel(); scope.cancel(); super.onDestroy() }
}

fun phaseText(phase: Phase) = when (phase) {
    Phase.IDLE -> R.string.ready
    Phase.PAUSED -> R.string.paused
    Phase.DOWNLOADING -> R.string.downloading
    Phase.PROCESSING -> R.string.processing
    Phase.SAVING -> R.string.saving
    Phase.COMPLETE -> R.string.complete
    Phase.CANCELLED -> R.string.cancelled
    Phase.FAILED -> R.string.failed
}

