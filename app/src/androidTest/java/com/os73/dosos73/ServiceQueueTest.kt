package com.os73.dosos73

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ServiceQueueTest {
    @Test
    fun failedTaskRemainsRetryableAndDoesNotConsumeWaitingTask() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        // Production DNS blocks these before a connection: no public network fixture is used.
        val first =
            DownloadTask(
                Analysis("https://127.0.0.1/blocked.bin", "Blocked fixture", listOf(choice), true),
                choice,
                null,
            )
        val second =
            first.copy(
                id = java.util.UUID.randomUUID().toString(),
                analysis = first.analysis.copy(title = "Waiting fixture"),
            )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            Transfers.update(TransferState())
            DownloadQueue.enqueue(context, first)
            DownloadQueue.enqueue(context, second)
            context.startForegroundService(Intent(context, DownloadService::class.java))
            withTimeout(15000) { while (Transfers.state.value.phase != Phase.FAILED) delay(50) }
            assertTrue(DownloadQueue.tasks.value.any { it.id == second.id })
            val failed = FailureJournal.failures.value.first { it.task.id == first.id }
            assertFalse(failed.cancelled)
            assertNotNull(failed.code)
            assertEquals(first, failed.task)
            assertTrue(
                PrivateJournal(context, "failed-downloads.json").readOrNull()!!.contains(first.id)
            )
            // Removing a failed history entry must not discard the waiting job.
            FailureJournal.remove(context, first.id)
            assertTrue(DownloadQueue.tasks.value.any { it.id == second.id })
        } finally {
            context.stopService(Intent(context, DownloadService::class.java))
            DownloadQueue.remove(context, first.id)
            DownloadQueue.remove(context, second.id)
            FailureJournal.remove(context, first.id)
            FailureJournal.remove(context, second.id)
            Transfers.update(TransferState())
            scenario.close()
        }
    }
}
