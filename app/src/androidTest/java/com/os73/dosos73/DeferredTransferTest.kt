package com.os73.dosos73

import android.app.ActivityManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real service handoff and OS registration; never contacts a public download endpoint. */
class DeferredTransferTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private fun task() = Choice("direct", Kind.FILE, "bin", "Original").let { choice ->
        DownloadTask(Analysis("https://example.com/deferred.bin", "Deferred fixture", listOf(choice), true), choice, null)
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == DownloadService::class.java.name }

    private suspend fun awaitStopped() = withTimeout(10000) {
        while (serviceRunning()) delay(20)
    }

    private suspend fun cleanup(vararg tasks: DownloadTask) {
        DownloadService.networkWaitMillisForTests = null
        context.stopService(Intent(context, DownloadService::class.java))
        awaitStopped()
        DeferredTransferScheduler.userStarted(context)
        tasks.forEach {
            DownloadQueue.finish(context, it.id)
            DownloadQueue.remove(context, it.id)
            FailureJournal.remove(context, it.id)
            TransferFiles.discard(context, it.id)
        }
        DeferredTransferScheduler.userStarted(context)
        Transfers.take()
        Transfers.update(TransferState())
    }

    @Test fun boundedWaitRetainsExactLeaseAndPartialWithoutFailureAndStopsForeground() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val task = task()
        DownloadService.networkWaitMillisForTests = 200
        try {
            DownloadQueue.initialize(context)
            DownloadQueue.enqueue(context, task)
            val partial = File(TransferFiles.directory(context, task.id).apply { mkdirs() }, "file.bin")
            partial.writeText("retained resumable bytes", Charsets.UTF_8)
            context.startForegroundService(Intent(context, DownloadService::class.java))
            withTimeout(10000) {
                while (Transfers.state.value.phase != Phase.WAITING || serviceRunning()) delay(20)
            }
            assertEquals(task, DownloadQueue.interrupted(context))
            assertFalse(DownloadQueue.interrupted(context)!!.userPaused)
            assertFalse(FailureJournal.failures.value.any { it.task.id == task.id })
            assertEquals("retained resumable bytes", partial.readText(Charsets.UTF_8))
            assertNull(Transfers.state.value.error)
            // An already-permitted network can complete the short reminder job before
            // this assertion, including when notification permission is denied.
            scheduler.getPendingJob(DeferredTransferScheduler.JOB_ID)?.let {
                assertEquals(task.id, it.extras.getString(DeferredTransferScheduler.TASK_ID))
            }

            // Cancellation after handoff must acknowledge only the owned lease and
            // remove the reminder; it must not require a still-running worker.
            context.startService(Intent(context, DownloadService::class.java).setAction("cancel"))
            withTimeout(10000) {
                while (Transfers.state.value.phase != Phase.CANCELLED || serviceRunning()) delay(20)
            }
            assertNull(DownloadQueue.interrupted(context))
            assertNull(scheduler.getPendingJob(DeferredTransferScheduler.JOB_ID))
            assertFalse(partial.exists())
        } finally { cleanup(task); scenario.close() }
    }

    @Test fun futureEligibilityUsesOnePersistedConstrainedJobAndPauseRemovalCancelIt() = runBlocking {
        val preferences = context.getSharedPreferences("network", Context.MODE_PRIVATE)
        val wifi = NetworkSettings.wifiOnly(context)
        val unmetered = NetworkSettings.unmeteredOnly(context)
        val task = task().copy(notBefore = System.currentTimeMillis() + 3_600_000)
        try {
            // Direct preference setup keeps this fixture independent of asynchronous
            // policy refresh, which production setters trigger after their commit.
            check(preferences.edit().putBoolean("wifi_only", true).putBoolean("unmetered_only", true).commit())
            DeferredTransferScheduler.userStarted(context)
            DownloadQueue.enqueue(context, task)
            val scheduled = scheduler.getPendingJob(DeferredTransferScheduler.JOB_ID)!!
            assertTrue(scheduled.isPersisted)
            assertTrue(scheduled.minLatencyMillis > 0)
            assertFalse(scheduled.isPeriodic)
            assertTrue(scheduled.requiredNetwork!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            assertTrue(scheduled.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            assertEquals(1, scheduler.allPendingJobs.count { it.id == DeferredTransferScheduler.JOB_ID })
            assertNull("A reminder must not take a transfer lease", DownloadQueue.interrupted(context))

            DownloadQueue.setPaused(context, task.id, true)
            assertNull(scheduler.getPendingJob(DeferredTransferScheduler.JOB_ID))
            assertTrue(DownloadQueue.tasks.value.single { it.id == task.id }.userPaused)
            DownloadQueue.setPaused(context, task.id, false)
            assertNotNull(scheduler.getPendingJob(DeferredTransferScheduler.JOB_ID))
            DownloadQueue.remove(context, task.id)
            assertNull(scheduler.getPendingJob(DeferredTransferScheduler.JOB_ID))
        } finally {
            check(preferences.edit().putBoolean("wifi_only", wifi).putBoolean("unmetered_only", unmetered).commit())
            cleanup(task)
        }
    }

    @Test fun cancelDuringExpiredWaitFinalizationKeepsCancelledAndDoesNotArmNextTask() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val next = task()
        val task = task()
        val interruptedTrace = CountDownLatch(1)
        val finishTrace = CountDownLatch(1)
        val writes = AtomicInteger()
        val marker = context.getSharedPreferences("deferred-transfer", Context.MODE_PRIVATE)
        DownloadService.networkWaitMillisForTests = 100
        PrivateJournal.beforeWriteForTests = { file ->
            // STARTED is first; the second write is INTERRUPTED after timeout has
            // set networkDeferred but before Processor chooses its terminal phase.
            if (file.name == "task-timeline.json" && writes.incrementAndGet() == 2) {
                interruptedTrace.countDown()
                check(finishTrace.await(15, TimeUnit.SECONDS))
            }
        }
        try {
            DownloadQueue.enqueue(context, task)
            DownloadQueue.enqueue(context, next)
            context.startForegroundService(Intent(context, DownloadService::class.java))
            withContext(Dispatchers.IO) { assertTrue(interruptedTrace.await(5, TimeUnit.SECONDS)) }
            // This marker lets the test await the existing Cancel acknowledgement
            // without a new production callback or a timing-dependent sleep.
            check(marker.edit().putBoolean("armed", true).commit())
            context.startService(Intent(context, DownloadService::class.java).setAction("cancel"))
            withTimeout(5000) { while (marker.getBoolean("armed", true)) delay(10) }
            finishTrace.countDown()
            withTimeout(10000) {
                while (Transfers.state.value.phase != Phase.CANCELLED || serviceRunning()) delay(20)
            }
            assertNull(DownloadQueue.interrupted(context))
            assertTrue(DownloadQueue.tasks.value.any { it.id == next.id })
            assertNull(scheduler.getPendingJob(DeferredTransferScheduler.JOB_ID))
            assertFalse(marker.getBoolean("armed", true))
            assertTrue(FailureJournal.failures.value.single { it.task.id == task.id }.cancelled)
        } finally {
            finishTrace.countDown()
            PrivateJournal.beforeWriteForTests = null
            cleanup(task, next)
            scenario.close()
        }
    }

    @Test fun pausedLeaseIsABarrierToOtherFutureWakeups() = runBlocking {
        val paused = task().copy(userPaused = true)
        val future = task().copy(notBefore = System.currentTimeMillis() + 3_600_000)
        try {
            DeferredTransferScheduler.userStarted(context)
            DownloadQueue.rememberActive(context, paused)
            DownloadQueue.enqueue(context, future)
            assertNull(DownloadQueue.deferredCandidate(context))
            assertNull(scheduler.getPendingJob(DeferredTransferScheduler.JOB_ID))
            assertEquals(paused, DownloadQueue.interrupted(context))
            assertTrue(DownloadQueue.tasks.value.any { it.id == future.id })
        } finally { cleanup(paused, future) }
    }
}
