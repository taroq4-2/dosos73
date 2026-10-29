package com.os73.dosos73

import android.content.ContentValues
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PublicationRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun task(tree: Uri? = null): DownloadTask {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        return DownloadTask(Analysis("https://example.com/fixture", "recovery-fixture", listOf(choice), true), choice, tree)
    }
    @Test fun findsMediaStoreCreationBeforeUriWasRecorded() {
        val task = task(); val receipt = PublicationReceipt(context, task.id)
        val bytes = "verified creation gap".toByteArray()
        val expected = bytes.inputStream().use(StreamIntegrity::fingerprint)
        val name = receipt.prepare(null, "application/octet-stream", expected, "fixture.bin", "bin")
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/dosos73"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        try {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            // Simulate death after creation/copy but before record(uri).
            val summary = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                it.moveToFirst(); (0 until it.columnCount).map { col -> it.getString(col) }.toString()
            }
            val recovered = PublicationReceipt(context, task.id).recover()
            assertNotNull("Missing planned row: $summary", recovered)
            assertEquals(uri, recovered!!.first)
            assertEquals(uri, PublicationReceipt(context, task.id).recover()!!.first)
        } finally { context.contentResolver.delete(uri, null, null); receipt.forget() }
    }
    private fun saf(block: (Uri, File, DownloadTask) -> Unit) {
        RecoveryTestDocumentsProvider.reset()
        val tree = DocumentsContract.buildTreeDocumentUri("com.os73.dosos73.recovery-tests", "root")
        context.grantUriPermission(context.packageName, tree,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        val file = File(context.cacheDir, "recovery-source-${java.util.UUID.randomUUID()}.bin").apply { writeText("durable verified provider content") }
        val task = task(tree)
        try { block(tree, file, task) }
        finally {
            RecoveryTestDocumentsProvider.reset(); PublicationReceipt(context, task.id).forget(); file.delete()
            context.revokeUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
    @Test fun recoversSafRenameThatChangedUriThenReportedFailure() = kotlinx.coroutines.runBlocking {
        saf { _, file, task ->
            RecoveryTestDocumentsProvider.failAfterRename = true
            val failed = runCatching { kotlinx.coroutines.runBlocking { OutputStore(context).save(file, task) } }
            assertTrue(failed.isFailure)
            assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
            RecoveryTestDocumentsProvider.failAfterRename = false
            val recovered = PublicationReceipt(context, task.id).recover()!!
            assertEquals(1, RecoveryTestDocumentsProvider.renames)
            assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
            context.contentResolver.openInputStream(recovered.first)!!.use { assertArrayEquals(file.readBytes(), it.readBytes()) }
        }
    }
    @Test fun retriesRejectedRenameWithoutDeletingVerifiedDataAndHandlesUnknownSize() = kotlinx.coroutines.runBlocking {
        saf { _, file, task ->
            RecoveryTestDocumentsProvider.rejectRename = true
            RecoveryTestDocumentsProvider.unknownSize = true
            assertTrue(runCatching { kotlinx.coroutines.runBlocking { OutputStore(context).save(file, task) } }.isFailure)
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
            assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
            RecoveryTestDocumentsProvider.rejectRename = false
            assertNotNull(PublicationReceipt(context, task.id).recover())
        }
    }
    @Test fun preservesVerifiedFileIfStorageBecomesUnreadable() = kotlinx.coroutines.runBlocking {
        saf { _, file, task ->
            RecoveryTestDocumentsProvider.rejectRename = true
            runCatching { kotlinx.coroutines.runBlocking { OutputStore(context).save(file, task) } }
            RecoveryTestDocumentsProvider.denyRead = true
            assertTrue(runCatching { PublicationReceipt(context, task.id).recover() }.isFailure)
            assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
        }
    }
    @Test fun discoversSafCreationWithoutRecordedUri() = saf { tree, file, task ->
        val receipt = PublicationReceipt(context, task.id)
        val expected = file.inputStream().use(StreamIntegrity::fingerprint)
        val temporary = receipt.prepare(tree, "application/octet-stream", expected, "fixture.bin", "bin")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val uri = DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", temporary)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(file.readBytes()) }
        assertNotNull(PublicationReceipt(context, task.id).recover())
        assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
        assertEquals(0, RecoveryTestDocumentsProvider.deletes)
    }

    @Test fun removesOnlyIncompleteUnverifiedTaskTemporary() = saf { tree, file, task ->
        val receipt = PublicationReceipt(context, task.id)
        val expected = file.inputStream().use(StreamIntegrity::fingerprint)
        val temporary = receipt.prepare(tree, "application/octet-stream", expected, "fixture.bin", "bin")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", temporary)!!
        assertNull(PublicationReceipt(context, task.id).recover())
        assertEquals(0, RecoveryTestDocumentsProvider.entries.size)
        assertEquals(1, RecoveryTestDocumentsProvider.deletes)
    }

    @Test fun preservesVerifiedDataWhenItsContentsChanged() = kotlinx.coroutines.runBlocking {
        saf { _, file, task ->
            RecoveryTestDocumentsProvider.rejectRename = true
            runCatching { kotlinx.coroutines.runBlocking { OutputStore(context).save(file, task) } }
            RecoveryTestDocumentsProvider.entries.values.single().file.writeText("changed after verification")
            RecoveryTestDocumentsProvider.rejectRename = false
            val error = runCatching { PublicationReceipt(context, task.id).recover() }.exceptionOrNull()
            assertEquals(FailureCode.PUBLICATION_REVIEW_REQUIRED, (error as PipelineException).code)
            assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
        }
    }

}
