package com.os73.dosos73

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FailureRetentionRegressionTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun task(): DownloadTask {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        return DownloadTask(Analysis("https://example.com/fixture.bin", "Fixture", listOf(choice), true), choice, null)
    }

    private fun resetLoaded() {
        DownloadQueue::class.java.getDeclaredField("loaded").apply { isAccessible = true }
            .setBoolean(DownloadQueue, false)
        FailureJournal::class.java.getDeclaredField("loaded").apply { isAccessible = true }
            .setBoolean(FailureJournal, false)
    }

    private fun isolated(block: suspend (Context, List<DownloadTask>) -> Unit) = runBlocking {
        val folder = File(base.cacheDir, "failure-retention-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(folder, "journals").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(folder, "owned").apply { mkdirs() }
        }
        val tasks = List(100) { task() }
        val rows = JSONArray()
        tasks.asReversed().forEach { rows.put(JSONObject()
            .put("task", DownloadQueue.encodeTask(it)).put("code", FailureCode.DOWNLOAD_FAILED.name)
            .put("cancelled", false)) }
        PrivateJournal(context, "failed-downloads.json").write(rows.toString())
        resetLoaded()
        FailureJournal.initialize(context)
        try { block(context, tasks) }
        finally {
            PrivateJournal.beforeWriteForTests = null
            resetLoaded()
            DownloadQueue.initialize(base)
            FailureJournal.initialize(base)
            folder.deleteRecursively()
        }
    }

    private fun partial(context: Context, task: DownloadTask): File =
        File(TransferFiles.directory(context, task.id).apply { check(mkdirs()) }, "file.bin")
            .apply { writeText("resumable partial") }

    @Test fun evictionDeletesOnlyTheDroppedTasksOwnedPartial() = isolated { context, tasks ->
        val evicted = partial(context, tasks[0])
        val retained = partial(context, tasks[1])
        val unrelated = partial(context, task())
        val published = File(context.filesDir, "saved-user-file.bin").apply { writeText("saved bytes") }
        FailureJournal.record(context, FailedDownload(task(), FailureCode.DOWNLOAD_FAILED, false))
        assertFalse(evicted.parentFile!!.exists())
        assertEquals("resumable partial", retained.readText())
        assertEquals("resumable partial", unrelated.readText())
        assertEquals("saved bytes", published.readText())
        assertEquals(100, FailureJournal.failures.value.size)
        assertFalse(PrivateJournal(context, "failed-downloads.json").readOrNull()!!.contains(tasks[0].id))
    }

    @Test fun evictionAndClearRetainWaitingAndPausedLeasedPartials() = isolated { context, tasks ->
        val leased = partial(context, tasks[0])
        val waiting = partial(context, tasks[1])
        val unowned = partial(context, tasks[2])
        DownloadQueue.enqueue(context, tasks[0])
        DownloadQueue.enqueue(context, tasks[1])
        assertEquals(tasks[0].id, DownloadQueue.take(context)!!.id)
        DownloadQueue.setPaused(context, tasks[0].id, true)
        repeat(2) { FailureJournal.record(context, FailedDownload(task(), FailureCode.DOWNLOAD_FAILED, false)) }
        assertFalse(FailureJournal.failures.value.any { it.task.id in setOf(tasks[0].id, tasks[1].id) })
        FailureJournal.clear(context)
        assertEquals("resumable partial", leased.readText())
        assertEquals("resumable partial", waiting.readText())
        assertFalse(unowned.parentFile!!.exists())
        assertEquals(tasks[0].id, DownloadQueue.interrupted(context)!!.id)
        assertEquals(tasks[1].id, DownloadQueue.tasks.value.single().id)
    }

    @Test fun failedEvictionWritePreservesMetadataAndPartial() = isolated { context, tasks ->
        val old = partial(context, tasks[0])
        PrivateJournal.beforeWriteForTests = { file ->
            if (file.name == "failed-downloads.json") throw IOException("Injected eviction write failure")
        }
        assertTrue(runCatching {
            FailureJournal.record(context, FailedDownload(task(), FailureCode.DOWNLOAD_FAILED, false))
        }.isFailure)
        PrivateJournal.beforeWriteForTests = null
        assertTrue(FailureJournal.failures.value.any { it.task.id == tasks[0].id })
        assertTrue(PrivateJournal(context, "failed-downloads.json").readOrNull()!!.contains(tasks[0].id))
        assertEquals("resumable partial", old.readText())
    }

    @Test fun unreadableQueuePreventsEvictionAndPartialDeletion() = isolated { context, tasks ->
        val old = partial(context, tasks[0])
        PrivateJournal(context, "waiting-tasks.json").write("invalid queue")
        assertTrue(runCatching {
            FailureJournal.record(context, FailedDownload(task(), FailureCode.DOWNLOAD_FAILED, false))
        }.isFailure)
        assertTrue(FailureJournal.failures.value.any { it.task.id == tasks[0].id })
        assertTrue(PrivateJournal(context, "failed-downloads.json").readOrNull()!!.contains(tasks[0].id))
        assertEquals("resumable partial", old.readText())
    }

    @Test fun concurrentQueueCommitProtectsTheEvictedPartial() = isolated { context, tasks ->
        val old = partial(context, tasks[0])
        gateQueueWrite(context) {
            coroutineScope {
                val enqueue = async(Dispatchers.Default) { DownloadQueue.enqueue(context, tasks[0]) }
                awaitWrite()
                val started = CompletableDeferred<Unit>()
                val eviction = async(Dispatchers.Default) {
                    started.complete(Unit)
                    FailureJournal.record(context, FailedDownload(task(), FailureCode.DOWNLOAD_FAILED, false))
                }
                started.await()
                delay(100)
                assertFalse("Eviction must wait for queue ownership", eviction.isCompleted)
                release.countDown()
                enqueue.await()
                eviction.await()
            }
        }
        assertEquals("resumable partial", old.readText())
        assertTrue(DownloadQueue.tasks.value.any { it.id == tasks[0].id })
        assertFalse(FailureJournal.failures.value.any { it.task.id == tasks[0].id })
    }

    @Test fun cancellationWaitingForOwnershipPreservesTheFailureReference() = isolated { context, tasks ->
        val old = partial(context, tasks[0])
        gateQueueWrite(context) {
            coroutineScope {
                val enqueue = async(Dispatchers.Default) { DownloadQueue.enqueue(context, task()) }
                awaitWrite()
                val started = CompletableDeferred<Unit>()
                val eviction = async(Dispatchers.Default) {
                    started.complete(Unit)
                    FailureJournal.record(context, FailedDownload(task(), FailureCode.DOWNLOAD_FAILED, false))
                }
                started.await()
                delay(100)
                eviction.cancelAndJoin()
                release.countDown()
                enqueue.await()
            }
        }
        assertTrue(FailureJournal.failures.value.any { it.task.id == tasks[0].id })
        assertTrue(PrivateJournal(context, "failed-downloads.json").readOrNull()!!.contains(tasks[0].id))
        assertEquals("resumable partial", old.readText())
    }

    @Test fun replacingTheSameFailureKeepsItsResumablePartial() = isolated { context, tasks ->
        val old = partial(context, tasks[0])
        FailureJournal.record(context, FailedDownload(tasks[0], FailureCode.DOWNLOAD_FAILED, false))
        assertEquals(100, FailureJournal.failures.value.size)
        assertEquals(1, FailureJournal.failures.value.count { it.task.id == tasks[0].id })
        assertEquals("resumable partial", old.readText())
    }

    private class WriteGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        suspend fun awaitWrite() = withContext(Dispatchers.IO) {
            assertTrue("Queue write did not enter", entered.await(5, TimeUnit.SECONDS))
        }
    }

    private suspend fun gateQueueWrite(context: Context, block: suspend WriteGate.() -> Unit) {
        val gate = WriteGate()
        val target = File(context.filesDir, "waiting-tasks.json").canonicalFile
        PrivateJournal.beforeWriteForTests = { file ->
            if (file.canonicalFile == target) {
                gate.entered.countDown()
                check(gate.release.await(5, TimeUnit.SECONDS)) { "Queue write gate timed out" }
            }
        }
        try { gate.block() }
        finally { gate.release.countDown(); PrivateJournal.beforeWriteForTests = null }
    }
}
