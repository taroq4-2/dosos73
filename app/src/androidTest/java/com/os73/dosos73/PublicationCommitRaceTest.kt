package com.os73.dosos73

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicationCommitRaceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val observedFailure = AtomicReference<String?>()
    private val mime = "application/octet-stream"
    private val bytes = "verified publication survives cancellation".toByteArray()

    private suspend fun saf(block: suspend (Uri, DownloadTask) -> Unit) {
        RecoveryTestDocumentsProvider.reset()
        observedFailure.set(null)
        TransferProcessor.failureObserverForTests = {
            observedFailure.set("${it.stage}:${it.code}:${it.cause?.javaClass?.simpleName}:${it.cause?.message}")
        }
        val tree = DocumentsContract.buildTreeDocumentUri("com.os73.dosos73.recovery-tests", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        context.grantUriPermission(context.packageName, tree, flags)
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val task = DownloadTask(Analysis("https://example.com/commit", "commit-fixture", listOf(choice), true), choice, tree)
        val priorState = Transfers.state.value
        try {
            block(tree, task)
        } finally {
            TransferProcessor.failureObserverForTests = null
            RecoveryTestDocumentsProvider.reset()
            PublicationReceipt(context, task.id).forget()
            DownloadQueue.finish(context, task.id)
            DownloadQueue.remove(context, task.id)
            FailureJournal.remove(context, task.id)
            LibraryRepository.files.value.filter { it.uri.contains("com.os73.dosos73.recovery-tests") }
                .forEach { LibraryRepository.hideHistory(context, it) }
            Transfers.update(priorState)
            context.revokeUriPermission(tree, flags)
        }
    }

    @Test fun cancellationDuringVerificationDoesNotStartPublication() = runBlocking {
        saf { _, task ->
            val source = File(context.cacheDir, "verify-cancel-${task.id}").apply { writeBytes(bytes) }
            val committed = java.util.concurrent.atomic.AtomicBoolean(false)
            lateinit var worker: Job
            var reads = 0
            RecoveryTestDocumentsProvider.onRead = { if (++reads == 2) worker.cancel() }
            try {
                worker = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    OutputStore(context) { _, _ -> committed.set(true) }.save(source, task)
                }
                worker.start(); worker.join()
                assertTrue(worker.isCancelled)
                assertFalse(committed.get())
                assertEquals(0, RecoveryTestDocumentsProvider.renames)
                assertEquals(1, RecoveryTestDocumentsProvider.deletes)
            } finally { source.delete() }
        }
    }

    @Test fun unavailableStorageAfterRenameDoesNotUndoCommittedIdentity() = runBlocking {
        saf { _, task ->
            val source = File(context.cacheDir, "reopen-${task.id}").apply { writeBytes(bytes) }
            val committed = AtomicReference<Uri?>()
            RecoveryTestDocumentsProvider.afterRename = { RecoveryTestDocumentsProvider.denyRead = true }
            try {
                val saved = OutputStore(context) { uri, _ -> committed.set(uri) }.save(source, task)
                assertEquals(saved.first, committed.get())
                assertEquals(1, RecoveryTestDocumentsProvider.renames)
                assertEquals(0, RecoveryTestDocumentsProvider.deletes)
            } finally { source.delete() }
        }
    }

    @Test fun userCancellationAfterRenameKeepsCommittedCompletion() = runBlocking {
        cancellationAfterRename(userCancelled = true)
    }

    @Test fun systemCancellationAfterRenameKeepsCommittedCompletion() = runBlocking {
        cancellationAfterRename(userCancelled = false)
    }

    private suspend fun cancellationAfterRename(userCancelled: Boolean) = kotlinx.coroutines.coroutineScope {
        saf { _, task ->
            val completed = AtomicReference<TransferState?>()
            FailureJournal.initialize(context)
            DownloadQueue.rememberActive(context, task)
            lateinit var worker: Job
            val processor = TransferProcessor(
                context = context,
                publish = {},
                transfer = { _, directory -> File(directory, "fixture.bin").apply { writeBytes(bytes) } },
                isUserCancelled = { userCancelled },
                onComplete = { completed.set(it) },
            )
            worker = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                processor.run(task, "commit cancellation fixture")
            }
            RecoveryTestDocumentsProvider.afterRename = { worker.cancel() }
            worker.start()
            worker.join()
            assertTrue("Expected rename cancellation; phase=${Transfers.state.value.phase}, failure=${observedFailure.get()}", worker.isCancelled)
            val result = completed.get()
            assertNotNull("Committed publication must reach onComplete despite cancellation", result)
            assertEquals(Phase.COMPLETE, result!!.phase)
            assertEquals(Phase.COMPLETE, Transfers.state.value.phase)
            assertEquals(result.uri, Transfers.state.value.uri)
            assertFalse(FailureJournal.failures.value.any { it.task.id == task.id })
            assertNull(DownloadQueue.interrupted(context))
            assertNull(PublicationReceipt(context, task.id).recover())
            assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
            assertEquals(1, RecoveryTestDocumentsProvider.renames)
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
            context.contentResolver.openInputStream(result.uri!!)!!.use { assertArrayEquals(bytes, it.readBytes()) }
        }
    }

    @Test fun userCancellationBeforePublicationDoesNotReportCompletion() = runBlocking {
        saf { _, task ->
            val completed = AtomicReference<TransferState?>()
            DownloadQueue.rememberActive(context, task)
            lateinit var worker: Job
            val processor = TransferProcessor(
                context = context,
                publish = { if (it.phase == Phase.SAVING) worker.cancel() },
                transfer = { _, directory -> File(directory, "fixture.bin").apply { writeBytes(bytes) } },
                isUserCancelled = { true },
                onComplete = { completed.set(it) },
            )
            worker = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { processor.run(task, "before commit fixture") }
            worker.start()
            worker.join()
            assertTrue("Expected pre-commit cancellation; phase=${Transfers.state.value.phase}, failure=${observedFailure.get()}", worker.isCancelled)
            assertNull(completed.get())
            assertEquals(Phase.CANCELLED, Transfers.state.value.phase)
            assertTrue(FailureJournal.failures.value.single { it.task.id == task.id }.cancelled)
            assertNull(DownloadQueue.interrupted(context))
            assertTrue(RecoveryTestDocumentsProvider.entries.isEmpty())
            assertEquals(0, RecoveryTestDocumentsProvider.renames)
            assertNull(PublicationReceipt(context, task.id).recover())
        }
    }

    @Test fun recoversReadyRecordedUriWithNormalizedNameWithoutRenamingAgain() = runBlocking {
        saf { tree, task ->
            val (receipt, uri) = recorded(tree, task)
            val committed = receipt.publishVerified(uri).first
            normalizeName()
            val recovered = receipt.recover()
            assertEquals(committed, recovered!!.first)
            assertEquals("normalized-file.bin", RecoveryTestDocumentsProvider.entries.values.single().name)
            assertEquals(1, RecoveryTestDocumentsProvider.renames)
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
            context.contentResolver.openInputStream(recovered.first)!!.use { assertArrayEquals(bytes, it.readBytes()) }
        }
    }

    @Test fun recoversStagedRecordedUriAfterProviderNormalizesItsName() = runBlocking {
        saf { tree, task ->
            val (receipt, _) = recorded(tree, task)
            normalizeName()
            val recovered = receipt.recover()
            assertNotNull(recovered)
            assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
            assertEquals(1, RecoveryTestDocumentsProvider.renames)
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
            context.contentResolver.openInputStream(recovered!!.first)!!.use { assertArrayEquals(bytes, it.readBytes()) }
        }
    }

    @Test fun changedNormalizedRecordedFileIsPreservedForReview() = runBlocking {
        saf { tree, task ->
            val (receipt, _) = recorded(tree, task)
            normalizeName()
            val changed = bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            RecoveryTestDocumentsProvider.entries.values.single().file.writeBytes(changed)
            val error = runCatching { receipt.recover() }.exceptionOrNull()
            assertEquals(FailureCode.PUBLICATION_REVIEW_REQUIRED, (error as PipelineException).code)
            assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
            assertEquals(0, RecoveryTestDocumentsProvider.renames)
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
            assertArrayEquals(changed, RecoveryTestDocumentsProvider.entries.values.single().file.readBytes())
        }
    }

    @Test fun normalizedRecordedFileAndForeignPlannedNameRemainAmbiguous() = runBlocking {
        saf { tree, task ->
            val (receipt, uri) = recorded(tree, task)
            receipt.publishVerified(uri)
            normalizeName()
            val finalName = "${task.analysis.title}-${task.id}.${task.choice.extension}"
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
            val foreign = DocumentsContract.createDocument(context.contentResolver, root, mime, finalName)!!
            context.contentResolver.openOutputStream(foreign)!!.use { it.write(bytes) }
            val error = runCatching { receipt.recover() }.exceptionOrNull()
            assertEquals(FailureCode.PUBLICATION_REVIEW_REQUIRED, (error as PipelineException).code)
            assertEquals(2, RecoveryTestDocumentsProvider.entries.size)
            assertEquals(1, RecoveryTestDocumentsProvider.renames)
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
        }
    }

    private fun recorded(tree: Uri, task: DownloadTask): Pair<PublicationReceipt, Uri> {
        val receipt = PublicationReceipt(context, task.id)
        val fingerprint = bytes.inputStream().use(StreamIntegrity::fingerprint)
        val temporary = receipt.prepare(tree, mime, fingerprint, "${task.analysis.title}.bin", "bin")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val uri = DocumentsContract.createDocument(context.contentResolver, root, mime, temporary)!!
        receipt.record(uri, mime, fingerprint, false)
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        receipt.markVerified()
        return receipt to uri
    }

    private fun normalizeName() {
        val entry = RecoveryTestDocumentsProvider.entries.entries.single()
        RecoveryTestDocumentsProvider.entries[entry.key] = entry.value.copy(name = "normalized-file.bin")
    }
}
