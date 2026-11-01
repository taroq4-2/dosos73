package com.os73.dosos73

import android.app.ActivityManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real startService deliveries to an instance with no transfer Job, after paused UI restoration. */
class ColdServiceControlTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(title: String): DownloadTask {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        // Cleartext policy rejects this before network selection, DNS or a socket is opened.
        return DownloadTask(
            Analysis("http://127.0.0.1/blocked.bin", title, listOf(choice), true),
            choice,
            null,
            userPaused = true,
        )
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == DownloadService::class.java.name }

    private suspend fun awaitStopped() = withTimeout(15000) {
        while (serviceRunning()) delay(25)
    }

    private suspend fun restorePaused(task: DownloadTask) {
        context.stopService(Intent(context, DownloadService::class.java))
        awaitStopped()
        Transfers.update(TransferState())
        assertTrue(Transfers.reserve(task))
        assertEquals(task.id, Transfers.take()?.id)
        Transfers.update(TransferState(Phase.PAUSED, task.analysis.title))
        DownloadQueue.rememberActive(context, task)
        assertFalse("Fixture must have no live service/worker", serviceRunning())
    }

    private suspend fun cleanup(vararg tasks: DownloadTask) {
        context.stopService(Intent(context, DownloadService::class.java))
        awaitStopped()
        tasks.forEach {
            DownloadQueue.finish(context, it.id)
            DownloadQueue.remove(context, it.id)
            FailureJournal.remove(context, it.id)
            TransferFiles.discard(context, it.id)
        }
        Transfers.take()
        Transfers.update(TransferState())
    }

    @Test
    fun resumeRestoredPausedLeaseStartsProcessorWithoutPublicNetwork() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val task = fixture("Cold resume fixture")
        val waiting = task.copy(id = UUID.randomUUID().toString(), userPaused = false)
        val allowCleartext = NetworkSettings.allowCleartext(context)
        try {
            DownloadQueue.initialize(context)
            NetworkSettings.setAllowCleartext(context, false)
            restorePaused(task)
            val partial = File(TransferFiles.directory(context, task.id).apply { mkdirs() }, "file.bin")
            partial.writeText("owned resumable bytes", Charsets.UTF_8)
            DownloadQueue.enqueue(context, waiting)
            context.startService(Intent(context, DownloadService::class.java).setAction("pause"))
            withTimeout(15000) {
                while (Transfers.state.value.phase != Phase.FAILED ||
                    FailureJournal.failures.value.none { it.task.id == task.id }) delay(25)
            }
            val failed = FailureJournal.failures.value.first { it.task.id == task.id }
            assertEquals(FailureCode.CLEARTEXT_DISABLED, failed.code)
            assertFalse(failed.cancelled)
            assertFalse("Resume must unpause the durable lease before processing", failed.task.userPaused)
            assertEquals(Phase.FAILED, Transfers.state.value.phase)
            assertNull(DownloadQueue.interrupted(context))
            assertTrue(DownloadQueue.tasks.value.any { it.id == waiting.id })
            assertEquals("owned resumable bytes", partial.readText(Charsets.UTF_8))
            awaitStopped()
        } finally {
            cleanup(task, waiting)
            NetworkSettings.setAllowCleartext(context, allowCleartext)
            scenario.close()
        }
    }

    @Test
    fun cancelRestoredPausedLeaseAcknowledgesOnlyItsOwnTaskAndStopsService() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val task = fixture("Cold cancellation fixture")
        val waiting = task.copy(id = UUID.randomUUID().toString(), userPaused = false)
        try {
            DownloadQueue.initialize(context)
            restorePaused(task)
            val owned = TransferFiles.directory(context, task.id).apply { mkdirs() }
            File(owned, "file.bin").writeText("cancelled bytes", Charsets.UTF_8)
            val other = File(TransferFiles.directory(context, waiting.id).apply { mkdirs() }, "file.bin")
            other.writeText("must survive", Charsets.UTF_8)
            DownloadQueue.enqueue(context, waiting)
            // Back-to-back deliveries must serialize rather than acknowledge/delete a waiting task.
            repeat(2) { context.startService(Intent(context, DownloadService::class.java).setAction("cancel")) }
            withTimeout(15000) { while (Transfers.state.value.phase != Phase.CANCELLED) delay(25) }
            assertNull(DownloadQueue.interrupted(context))
            val cancelled = FailureJournal.failures.value.first { it.task.id == task.id }
            assertTrue(cancelled.cancelled)
            assertEquals(1, FailureJournal.failures.value.count { it.task.id == task.id })
            assertEquals(task.id, cancelled.task.id)
            assertFalse(owned.exists())
            assertEquals("must survive", other.readText(Charsets.UTF_8))
            assertTrue(DownloadQueue.tasks.value.any { it.id == waiting.id })
            awaitStopped()
        } finally {
            cleanup(task, waiting)
            scenario.close()
        }
    }
}
