package com.os73.dosos73

import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class OwnedProviderPreflightTest {
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
