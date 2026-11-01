package com.os73.dosos73

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One OS-owned eligibility reminder. It never starts a transfer from the background. */
internal object DeferredTransferScheduler {
    internal const val JOB_ID = 7301
    internal const val NOTIFICATION_ID = 75
    internal const val TASK_ID = "taskId"
    private const val REQUEST_ID = "requestId"
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun prefs(context: Context) = context.getSharedPreferences("deferred-transfer", Context.MODE_PRIVATE)

    // Lock order is scheduler -> queue. Queue callers invoke refresh only after releasing
    // their own lock. The marker contains no source/destination/title or credentials.
    suspend fun defer(context: Context): Boolean = update(context, arm = true)
    suspend fun refresh(context: Context): Boolean = update(context, arm = false)

    fun policyChanged(context: Context) {
        val application = context.applicationContext
        scope.launch { refresh(application) }
    }

    suspend fun userStarted(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(prefs(context).edit().putBoolean("armed", false).remove("deliveredTaskId").commit())
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    private suspend fun update(context: Context, arm: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            lock.withLock {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                val candidate = DownloadQueue.deferredCandidate(context)
                val now = System.currentTimeMillis()
                val armed = arm || prefs(context).getBoolean("armed", false)
                if (candidate == null || (!armed && candidate.notBefore <= now)) {
                    scheduler.cancel(JOB_ID)
                    if (candidate == null || prefs(context).getString("deliveredTaskId", null) != candidate.id) {
                        check(prefs(context).edit().putBoolean("armed", false).remove("deliveredTaskId").commit())
                        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    }
                    return@withLock true
                }
                val requestId = java.util.UUID.randomUUID().toString()
                check(prefs(context).edit().putBoolean("armed", true).putString(REQUEST_ID, requestId).commit())
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                val request = NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .apply {
                        if (NetworkSettings.wifiOnly(context)) addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        if (NetworkSettings.unmeteredOnly(context)) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }.build()
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, DeferredTransferJobService::class.java))
                    .setRequiredNetwork(request)
                    .setPersisted(true)
                    .setMinimumLatency((candidate.notBefore - now).coerceAtLeast(0))
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setExtras(PersistableBundle().apply {
                        putString(TASK_ID, candidate.id)
                        putString(REQUEST_ID, requestId)
                    })
                    .build()
                // Same ID replaces the earlier request; no periodic work or override deadline.
                scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            Diagnostics.event(Stage.TRANSFER, "deferred_wakeup_unavailable")
            false // Queue/lease remains authoritative and available for explicit in-app resume.
        }
    }

    internal suspend fun notifyReady(context: Context, params: JobParameters): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val candidate = DownloadQueue.deferredCandidate(context) ?: return@withLock false
            // Re-evaluate priority/eligibility now: OS batching may have made a higher
            // priority task eligible since scheduling. Reject only replaced requests.
            if (!prefs(context).getBoolean("armed", false) ||
                prefs(context).getString(REQUEST_ID, null) != params.extras.getString(REQUEST_ID))
                return@withLock false
            if (candidate.notBefore > System.currentTimeMillis() || !NetworkSettings.permitted(context, params.network))
                return@withLock true // System backoff/constraints handle a changed clock/network.
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("downloads", context.getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(context, JOB_ID,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, "downloads")
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(context.getString(R.string.start_queue))
                .setContentText(candidate.analysis.title)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                // Bring the app foreground first, also resetting an exhausted Android35
                // dataSync budget. The visible UI owns starting/resuming the service.
                .addAction(0, context.getString(R.string.resume), open)
                .build()
            // A denied notification permission must neither fail nor consume the queued task.
            try { manager.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { }
            check(prefs(context).edit().putBoolean("armed", false).putString("deliveredTaskId", candidate.id).commit())
            false
        }
    }
}

/** Short disk/policy/notification work only; JobScheduler does not grant an FGS-start exemption. */
class DeferredTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val retry = try { DeferredTransferScheduler.notifyReady(this@DeferredTransferJobService, params) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { false }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true // System constraint/stop backoff; never an application polling loop.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
