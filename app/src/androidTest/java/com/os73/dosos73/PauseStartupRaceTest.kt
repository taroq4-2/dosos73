package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PauseStartupRaceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun delayedStartupPreservesAcknowledgedPauseAndExplicitResume() = runBlocking {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val task = DownloadTask(
            Analysis("https://example.com/startup-pause.bin", "Startup pause fixture", listOf(choice), true),
            choice,
            null,
        )
        val waiting = task.copy(id = UUID.randomUUID().toString())
        val continueStartup = CompletableDeferred<Unit>()
        var startup: kotlinx.coroutines.Job? = null
        try {
            // Also exercise rememberActive's existing API for establishing a new lease.
            DownloadQueue.rememberActive(context, task)
            val captured = DownloadQueue.take(context)!!
            assertEquals(task, captured)
            DownloadQueue.enqueue(context, waiting)
            val worker = launch {
                // Force the service ordering: take captured an unpaused task, but the
                // startup refresh reaches the queue after Pause has committed.
                continueStartup.await()
                DownloadQueue.rememberActive(context, captured)
            }
            startup = worker
            DownloadQueue.setPaused(context, task.id, true)
            continueStartup.complete(Unit)
            worker.join()

            val paused = task.copy(userPaused = true)
            assertEquals(paused, DownloadQueue.interrupted(context))
            val pausedDisk = JSONObject(PrivateJournal(context, "waiting-tasks.json").readOrNull()!!)
            assertEquals(paused, DownloadQueue.decodeTask(pausedDisk.getJSONObject("active")))
            assertNull("An acknowledged Pause must block another worker", DownloadQueue.take(context))
            assertFalse("Paused startup must not trigger an automatic restart", DownloadQueue.hasRunnable())
            assertTrue("Pause must retain the other queued task", DownloadQueue.tasks.value.any { it.id == waiting.id })

            // A stale paused snapshot must not undo an explicit Resume either.
            DownloadQueue.setPaused(context, task.id, false)
            DownloadQueue.rememberActive(context, paused)
            assertEquals(task, DownloadQueue.interrupted(context))
            val resumedDisk = JSONObject(PrivateJournal(context, "waiting-tasks.json").readOrNull()!!)
            assertEquals(task, DownloadQueue.decodeTask(resumedDisk.getJSONObject("active")))
            assertTrue(DownloadQueue.hasRunnable())
            assertEquals(task, DownloadQueue.take(context))
        } finally {
            continueStartup.complete(Unit)
            startup?.cancelAndJoin()
            DownloadQueue.finish(context, task.id)
            DownloadQueue.remove(context, task.id)
            DownloadQueue.remove(context, waiting.id)
        }
    }
}
