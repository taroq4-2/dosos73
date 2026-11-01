package com.os73.dosos73

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BatchHandoffTest {
    private fun isolated(block: suspend (Context, File) -> Unit) = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(base.cacheDir, "handoff-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = folder }
        coldQueue(context)
        try { block(context, folder) }
        finally {
            PrivateJournal.beforeWriteForTests = null
            // Reset before returning to tests which use the application's ordinary journal.
            DownloadQueue::class.java.getDeclaredField("loaded").apply { isAccessible = true }
                .setBoolean(DownloadQueue, false)
            folder.deleteRecursively()
        }
    }

    /** Emulates a fresh process: the next queue operation must decode its durable journal. */
    private suspend fun coldQueue(context: Context) {
        DownloadQueue::class.java.getDeclaredField("loaded").apply { isAccessible = true }
            .setBoolean(DownloadQueue, false)
        DownloadQueue.initialize(context)
    }

    private fun row(path: String = "one"): BatchEntry {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val url = "https://example.com/$path"
        val analysis = Analysis(url, path, listOf(choice), true)
        return BatchEntry(url, url, true, analysis, choice = choice)
    }

    private fun task(row: BatchEntry) = DownloadTask(row.analysis!!, row.choice!!, null)

    private fun receipts(context: Context): JSONArray =
        JSONObject(PrivateJournal(context, "waiting-tasks.json").readOrNull()!!)
            .getJSONArray("acceptedBatch")

    private fun blockWrite(folder: File, name: String): File =
        File(folder, ".fault-$name").apply {
            check(mkdir())
            val marker = this
            val target = File(folder, name).canonicalPath
            // Platform AtomicFile uses .bak on API29 and .new on newer versions.
            // Inject the same disk failure at the journal boundary on every API.
            PrivateJournal.beforeWriteForTests = { file ->
                if (marker.exists() && file.canonicalPath == target) throw IOException("Injected journal write failure")
            }
        }
    private fun legacy(context: Context, row: BatchEntry, version: Int) {
        BatchDraft(context).write(listOf(row))
        val journal = PrivateJournal(context, "batch-review.json")
        val json = JSONObject(journal.readOrNull()!!).put("version", version)
        val encodedRow = json.getJSONArray("rows").getJSONObject(0)
        encodedRow.remove("deliveryId")
        if (version == 1) {
            encodedRow.put("formats", JSONArray().put(DownloadQueue.encodeTask(task(row))))
            encodedRow.remove("analysis")
        }
        journal.write(json.toString())
    }

    @Test fun legacyVersionsCommitIdentityBeforeReturningRows() = isolated { context, _ ->
        for (version in 1..2) {
            legacy(context, row("v$version"), version)
            val restored = BatchDraft(context).read().single()
            assertEquals(restored.deliveryId, BatchDraft(context).read().single().deliveryId)
            assertEquals(3, JSONObject(PrivateJournal(context, "batch-review.json").readOrNull()!!)
                .getInt("version"))
            assertNotNull(restored.analysis)
            assertEquals(restored.analysis!!.choices.single(), restored.choice)
        }
    }

    @Test fun failedLegacyMigrationNeverReturnsAnUncommittedIdentity() = isolated { context, folder ->
        legacy(context, row(), 2)
        val blocked = blockWrite(folder, "batch-review.json")
        assertTrue(runCatching { BatchDraft(context).read() }.isFailure)
        assertEquals(2, JSONObject(PrivateJournal(context, "batch-review.json").readOrNull()!!)
            .getInt("version"))
        assertTrue(blocked.deleteRecursively())
        val restored = BatchDraft(context).read().single()
        assertEquals(restored.deliveryId, BatchDraft(context).read().single().deliveryId)
    }

    @Test fun completedOrRemovedTaskCannotBeReacceptedFromTheOldDraft() = isolated { context, _ ->
        for (finish in listOf(true, false)) {
            val original = row(if (finish) "finished" else "failed-or-removed")
            BatchDraft(context).write(listOf(original))
            DownloadQueue.reconcileBatch(context, setOf(original.deliveryId))
            DownloadQueue.enqueueBatch(context, original.deliveryId, task(original))
            assertEquals(original.deliveryId, DownloadQueue.tasks.value.single().id)
            if (finish) {
                assertEquals(original.deliveryId, DownloadQueue.take(context)!!.id)
                DownloadQueue.finish(context, original.deliveryId)
            } else DownloadQueue.remove(context, original.deliveryId)
            coldQueue(context)
            val restored = BatchDraft(context).read().single()
            DownloadQueue.reconcileBatch(context, setOf(restored.deliveryId))
            DownloadQueue.enqueueBatch(context, restored.deliveryId, task(restored))
            assertTrue(DownloadQueue.tasks.value.isEmpty())
            assertNull(DownloadQueue.interrupted(context))
            assertEquals(1, receipts(context).length())
            BatchDraft(context).write(emptyList())
            DownloadQueue.reconcileBatch(context, emptySet())
            assertEquals(0, receipts(context).length())
        }
    }

    @Test fun failedDraftRemovalRetainsReceiptAcrossCompletionAndColdRead() = isolated { context, folder ->
        val original = row()
        BatchDraft(context).write(listOf(original))
        DownloadQueue.enqueueBatch(context, original.deliveryId, task(original))
        val blocked = blockWrite(folder, "batch-review.json")
        assertTrue(runCatching { BatchDraft(context).write(emptyList()) }.isFailure)
        assertEquals(original.deliveryId, DownloadQueue.take(context)!!.id)
        DownloadQueue.finish(context, original.deliveryId)
        coldQueue(context)
        val restored = BatchDraft(context).read().single()
        DownloadQueue.enqueueBatch(context, restored.deliveryId, task(restored))
        assertTrue(DownloadQueue.tasks.value.isEmpty())
        assertEquals(1, receipts(context).length())
        assertTrue(blocked.deleteRecursively())
        BatchDraft(context).write(emptyList())
        // Death between the draft commit and cleanup is safe: startup sees no live identities.
        coldQueue(context)
        DownloadQueue.reconcileBatch(context, BatchDraft(context).read().map { it.deliveryId }.toSet())
        assertEquals(0, receipts(context).length())
    }

    @Test fun failedAcceptancePublishesNeitherReceiptNorTaskAndCanBeRetried() = isolated { context, folder ->
        val original = row()
        BatchDraft(context).write(listOf(original))
        val blocked = blockWrite(folder, "waiting-tasks.json")
        assertTrue(runCatching {
            DownloadQueue.enqueueBatch(context, original.deliveryId, task(original))
        }.isFailure)
        assertTrue(DownloadQueue.tasks.value.isEmpty())
        assertNull(PrivateJournal(context, "waiting-tasks.json").readOrNull())
        assertEquals(original, BatchDraft(context).read().single())
        assertTrue(blocked.deleteRecursively())
        coldQueue(context)
        DownloadQueue.enqueueBatch(context, original.deliveryId, task(original))
        coldQueue(context)
        assertEquals(original.deliveryId, DownloadQueue.tasks.value.single().id)
        assertEquals(original.deliveryId, receipts(context).getString(0))
    }

    @Test fun partialBatchFailurePreservesTheUnacceptedRow() = isolated { context, _ ->
        val first = row("first")
        val second = row("second")
        val waiting = JSONArray()
        repeat(49) { waiting.put(DownloadQueue.encodeTask(task(row("waiting-$it")))) }
        PrivateJournal(context, "waiting-tasks.json").write(JSONObject().put("waiting", waiting).toString())
        coldQueue(context)
        BatchDraft(context).write(listOf(first, second))
        DownloadQueue.enqueueBatch(context, first.deliveryId, task(first))
        BatchDraft(context).write(listOf(second))
        DownloadQueue.reconcileBatch(context, setOf(second.deliveryId))
        assertTrue(runCatching { DownloadQueue.enqueueBatch(context, second.deliveryId, task(second)) }.isFailure)
        coldQueue(context)
        assertEquals(1, DownloadQueue.tasks.value.count { it.id == first.deliveryId })
        assertFalse(DownloadQueue.tasks.value.any { it.id == second.deliveryId })
        assertEquals(second, BatchDraft(context).read().single())
    }

    @Test fun receiptsAreReclaimedOnlyAfterDurableDraftRemoval() = isolated { context, _ ->
        repeat(25) { index ->
            val original = row("round-$index")
            BatchDraft(context).write(listOf(original))
            DownloadQueue.enqueueBatch(context, original.deliveryId, task(original))
            DownloadQueue.remove(context, original.deliveryId)
            assertEquals(1, receipts(context).length())
            BatchDraft(context).write(emptyList())
            DownloadQueue.reconcileBatch(context, emptySet())
            assertEquals(0, receipts(context).length())
        }
    }

    private fun application(context: Context, folder: File) = object : Application() {
        init { attachBaseContext(context) }
        override fun getFilesDir() = folder
    }

    private suspend fun ready(model: MainViewModel) {
        withTimeout(5000) { while (model.batchSaving.value || model.batchStorageError.value) delay(10) }
    }

    private suspend fun close(model: MainViewModel) {
        withContext(Dispatchers.Main) {
            model.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        }
    }

    @Test fun actorRecoversFailedRemovalAfterTaskFinishesAndViewModelDies() = isolated { context, folder ->
        val original = row()
        BatchDraft(context).write(listOf(original))
        val app = application(context, folder)
        val first = withContext(Dispatchers.Main) { MainViewModel(app) }
        try {
            ready(first)
            val blocked = blockWrite(folder, "batch-review.json")
            assertTrue(runCatching {
                withContext(Dispatchers.Main) { first.queueBatch(original, task(original)) }
            }.isFailure)
            assertTrue(first.batchStorageError.value)
            assertEquals(original.deliveryId, DownloadQueue.take(context)!!.id)
            DownloadQueue.finish(context, original.deliveryId)
            close(first)
            assertEquals(original, BatchDraft(context).read().single())
            assertEquals(1, receipts(context).length())
            assertTrue(blocked.deleteRecursively())
            coldQueue(context)
            val second = withContext(Dispatchers.Main) { MainViewModel(app) }
            try {
                ready(second)
                val restored = second.batch.value.single()
                withContext(Dispatchers.Main) { second.queueBatch(restored, task(restored)) }
                assertTrue(DownloadQueue.tasks.value.isEmpty())
                assertNull(DownloadQueue.interrupted(context))
                assertTrue(BatchDraft(context).read().isEmpty())
                assertEquals(0, receipts(context).length())
            } finally { close(second) }
        } finally { close(first) }
    }

    @Test fun secondViewModelRejectsAStaleHandoffAfterReceiptWasReclaimed() = isolated { context, folder ->
        val original = row()
        BatchDraft(context).write(listOf(original))
        val app = application(context, folder)
        val first = withContext(Dispatchers.Main) { MainViewModel(app) }
        val second = withContext(Dispatchers.Main) { MainViewModel(app) }
        try {
            ready(first)
            ready(second)
            withContext(Dispatchers.Main) { second.queueBatch(second.batch.value.single(), task(original)) }
            assertEquals(original.deliveryId, DownloadQueue.take(context)!!.id)
            DownloadQueue.finish(context, original.deliveryId)
            assertEquals(0, receipts(context).length())
            assertTrue(runCatching {
                withContext(Dispatchers.Main) { first.queueBatch(original, task(original)) }
            }.isFailure)
            assertTrue(first.batch.value.isEmpty())
            assertFalse(first.batchStorageError.value)
            assertTrue(BatchDraft(context).read().isEmpty())
            assertTrue(DownloadQueue.tasks.value.isEmpty())
            withContext(Dispatchers.Main) { first.retryBatchDraft() }
            assertTrue(DownloadQueue.tasks.value.isEmpty())
        } finally { close(first); close(second) }
    }

    @Test fun staleWriterWaitingForRetryCannotResurrectAnotherActorsRemovedRow() = isolated { context, folder ->
        val original = row()
        BatchDraft(context).write(listOf(original))
        val app = application(context, folder)
        val first = withContext(Dispatchers.Main) { MainViewModel(app) }
        val second = withContext(Dispatchers.Main) { MainViewModel(app) }
        try {
            ready(first)
            ready(second)
            val blocked = blockWrite(folder, "batch-review.json")
            withContext(Dispatchers.Main) { first.selectBatch(0, false) }
            withTimeout(5000) { while (!first.batchStorageError.value) delay(10) }
            assertTrue(blocked.deleteRecursively())
            withContext(Dispatchers.Main) { second.queueBatch(second.batch.value.single(), task(original)) }
            assertEquals(original.deliveryId, DownloadQueue.take(context)!!.id)
            DownloadQueue.finish(context, original.deliveryId)
            withContext(Dispatchers.Main) { first.retryBatchDraft() }
            ready(first)
            assertTrue(first.batch.value.isEmpty())
            assertTrue(BatchDraft(context).read().isEmpty())
            assertTrue(DownloadQueue.tasks.value.isEmpty())
            assertEquals(0, receipts(context).length())
        } finally { close(first); close(second) }
    }

    @Test fun snapshotTokenRejectsAStaleEditEvenWhenDeliveryIdsMatch() = isolated { context, folder ->
        val original = row()
        BatchDraft(context).write(listOf(original))
        val app = application(context, folder)
        val first = withContext(Dispatchers.Main) { MainViewModel(app) }
        val second = withContext(Dispatchers.Main) { MainViewModel(app) }
        try {
            ready(first)
            ready(second)
            withContext(Dispatchers.Main) { second.selectBatch(0, false) }
            ready(second)
            val latestToken = BatchDraft(context).readSnapshot().token
            withContext(Dispatchers.Main) { first.selectBatch(0, true) }
            ready(first)
            assertFalse(first.batch.value.single().selected)
            assertFalse(BatchDraft(context).read().single().selected)
            assertEquals(latestToken, BatchDraft(context).readSnapshot().token)
        } finally { close(first); close(second) }
    }

    private fun stalledDirect(entered: CountDownLatch, cancelled: AtomicBoolean): DirectDownloader =
        DirectDownloader(OkHttpClient.Builder().addInterceptor { chain ->
            // No network is contacted: hold the real OkHttp call until DirectDownloader cancels it.
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
            cancelled.set(chain.call().isCanceled())
            throw IOException("Stalled analysis ended")
        }.build())

    @Test fun analysisGuardianCancelsDirectCallOnScopeCancellation() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val downloader = stalledDirect(entered, cancelled)
        val worker = async {
            withAnalysisCancellation(downloader::cancel) {
                try { downloader.analyze("https://example.com/media") }
                catch (error: IOException) { currentCoroutineContext().ensureActive(); throw error }
            }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            val start = System.nanoTime()
            worker.cancelAndJoin()
            assertTrue(cancelled.get())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
        } finally { downloader.cancel(); worker.cancelAndJoin() }
    }

    @Test fun analysisGuardianCancelsDirectCallOnTimeout() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val downloader = stalledDirect(entered, cancelled)
        val start = System.nanoTime()
        try {
            val failure = runCatching {
                withTimeout(1000) {
                    withAnalysisCancellation(downloader::cancel) {
                        try { downloader.analyze("https://example.com/media") }
                        catch (error: IOException) { currentCoroutineContext().ensureActive(); throw error }
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            assertEquals(0L, entered.count)
            assertTrue(cancelled.get())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000)
        } finally { downloader.cancel() }
    }

    @Test fun failedDraftReadKeepsIncomingShareUntilExplicitAcceptanceAfterRetry() = isolated { context, folder ->
        // A readable journal whose schema is invalid exercises the ViewModel storage-error gate.
        PrivateJournal(context, "batch-review.json").write("{}")
        val application = application(context, folder)
        val model = withContext(Dispatchers.Main) { MainViewModel(application) }
        try {
            withTimeout(5000) { while (!model.batchStorageError.value) delay(10) }
            val incoming = "https://example.com/one https://example.com/two"
            withContext(Dispatchers.Main) {
                model.receive(incoming)
                model.acceptPending()
                assertEquals(incoming, model.state.value.pendingShared)
                assertTrue(model.batch.value.isEmpty())
            }
            BatchDraft(context).write(emptyList())
            withContext(Dispatchers.Main) { model.retryBatchDraft() }
            withTimeout(5000) { while (model.batchSaving.value || model.batchStorageError.value) delay(10) }
            withContext(Dispatchers.Main) {
                assertEquals(incoming, model.state.value.pendingShared)
                model.acceptPending()
                assertNull(model.state.value.pendingShared)
                assertEquals(2, model.batch.value.size)
            }
        } finally { close(model) }
    }
}
