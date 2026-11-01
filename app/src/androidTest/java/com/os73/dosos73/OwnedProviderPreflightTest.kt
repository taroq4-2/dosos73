package com.os73.dosos73

import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class OwnedProviderPreflightTest {
    @Test fun writeGrantWithoutDirectoryCreateCapabilityIsRejectedBeforeCreatingAnything() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val granted = object : ContextWrapper(base) {
            override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int) = PackageManager.PERMISSION_GRANTED
        }
        val tree = DocumentsContract.buildTreeDocumentUri("com.os73.dosos73.recovery-tests", "root")
        val folder = checkNotNull(androidx.documentfile.provider.DocumentFile.fromTreeUri(granted, tree))
        RecoveryTestDocumentsProvider.reset()
        try {
            // DocumentFile considers either flag writable; neither promises child creation.
            for (flags in listOf(DocumentsContract.Document.FLAG_SUPPORTS_DELETE,
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE)) {
                RecoveryTestDocumentsProvider.rootFlagsOverride = flags
                assertTrue(folder.canWrite())
                val error = assertThrows(PipelineException::class.java) {
                    OutputStore(granted).preflight(tree)
                }
                assertEquals(FailureCode.STORAGE_PERMISSION_DENIED, error.code)
                assertTrue(RecoveryTestDocumentsProvider.entries.isEmpty())
            }
            RecoveryTestDocumentsProvider.rootFlagsOverride = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            OutputStore(granted).preflight(tree)
            assertTrue(RecoveryTestDocumentsProvider.entries.isEmpty())
        } finally { RecoveryTestDocumentsProvider.reset() }
    }

    @Test fun sameUidDirectoryCreationDoesNotDependOnAnExplicitGrantRecord() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int) = PackageManager.PERMISSION_DENIED
        }
        val tree = DocumentsContract.buildTreeDocumentUri("com.os73.dosos73.recovery-tests", "root")
        RecoveryTestDocumentsProvider.reset()
        try {
            assertEquals(android.os.Process.myUid(), base.packageManager.resolveContentProvider(tree.authority!!, 0)!!.applicationInfo.uid)
            OutputStore(context).preflight(tree)
            assertTrue(RecoveryTestDocumentsProvider.entries.isEmpty())
            RecoveryTestDocumentsProvider.rootWritable = false
            val error = assertThrows(PipelineException::class.java) { OutputStore(context).preflight(tree) }
            assertEquals(FailureCode.STORAGE_PERMISSION_DENIED, error.code)
            assertTrue(RecoveryTestDocumentsProvider.entries.isEmpty())
        } finally { RecoveryTestDocumentsProvider.reset() }
    }
}
