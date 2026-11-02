package com.os73.dosos73

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Provider responses are untrusted even after the rename side effect has occurred. */
class PublicationUriBoundaryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "com.os73.dosos73.recovery-tests"
    private val bytes = "verified URI boundary fixture".toByteArray()

    @Test fun rejectsForeignRenameReturnsAndRecoversTheActualVerifiedDocument() {
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val bad = listOf(
            DocumentsContract.buildDocumentUriUsingTree(
                DocumentsContract.buildTreeDocumentUri("foreign.provider", "root"), "foreign"),
            DocumentsContract.buildDocumentUriUsingTree(
                DocumentsContract.buildTreeDocumentUri(authority, "another-tree"), "foreign"),
            DocumentsContract.buildDocumentUri(authority, "not-tree-scoped"),
            Uri.parse("file:///unrelated.bin"),
            DocumentsContract.buildDocumentUriUsingTree(tree, "foreign").buildUpon().appendQueryParameter("query", "extra").build(),
        )
        for (returned in bad) fixture(tree) { receipt, uri ->
            RecoveryTestDocumentsProvider.renamedUriOverride = returned
            val failure = runCatching { receipt.publishVerified(uri) }.exceptionOrNull()
            assertEquals("Provider return must not become a committed output: $returned",
                FailureCode.PUBLICATION_REVIEW_REQUIRED, (failure as? PipelineException)?.code)
            assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
            assertEquals(1, RecoveryTestDocumentsProvider.renames)
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
            RecoveryTestDocumentsProvider.renamedUriOverride = null
            val recovered = receipt.recover()!!.first
            assertNotEquals(returned, recovered)
            assertEquals(tree.authority, recovered.authority)
            assertEquals("root", DocumentsContract.getTreeDocumentId(recovered))
            context.contentResolver.openInputStream(recovered)!!.use { assertArrayEquals(bytes, it.readBytes()) }
            assertEquals(recovered, receipt.recover()!!.first)
            assertEquals(1, RecoveryTestDocumentsProvider.renames)
        }
    }

    @Test fun acceptsValidNewDocumentIdentityWithoutReopeningAfterCommit() {
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        fixture(tree) { receipt, uri ->
            RecoveryTestDocumentsProvider.afterRename = { RecoveryTestDocumentsProvider.denyRead = true }
            val renamed = receipt.publishVerified(uri).first
            assertNotEquals(uri, renamed)
            assertEquals("root", DocumentsContract.getTreeDocumentId(renamed))
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
        }
    }

    private fun fixture(tree: Uri, block: (PublicationReceipt, Uri) -> Unit) {
        RecoveryTestDocumentsProvider.reset()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        context.grantUriPermission(context.packageName, tree, flags)
        val id = java.util.UUID.randomUUID().toString()
        val receipt = PublicationReceipt(context, id)
        try {
            val fingerprint = bytes.inputStream().use(StreamIntegrity::fingerprint)
            val temporary = receipt.prepare(tree, "application/octet-stream", fingerprint, "fixture.bin", "bin")
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
            val uri = DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", temporary)!!
            receipt.record(uri, "application/octet-stream", fingerprint, false)
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            receipt.markVerified()
            block(receipt, uri)
        } finally {
            RecoveryTestDocumentsProvider.reset()
            receipt.forget()
            context.revokeUriPermission(tree, flags)
        }
    }
}
