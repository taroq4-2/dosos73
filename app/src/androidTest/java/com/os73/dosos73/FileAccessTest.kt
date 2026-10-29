package com.os73.dosos73

import android.content.ContentValues
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileAccessTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun distinguishesDeletedLocalRowFromAnUnavailableProvider() = runBlocking {
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "access-test-${java.util.UUID.randomUUID()}.bin")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/dosos73")
        })!!
        LibraryRepository.remember(context, uri, "application/octet-stream", "access-test", 0)
        val entry = LibraryRepository.files.value.first { it.uri == uri.toString() }
        try {
            assertEquals(FileAvailability.AVAILABLE, FileAccess.inspect(context, uri))
            assertTrue(runCatching { LibraryRepository.forgetMissing(context, entry) }.isFailure)
            assertTrue(LibraryRepository.files.value.any { it.uri == uri.toString() })
        }
        finally { context.contentResolver.delete(uri, null, null) }
        assertEquals(FileAvailability.MISSING, FileAccess.inspect(context, uri))
        LibraryRepository.forgetMissing(context, entry)
        assertFalse(LibraryRepository.files.value.any { it.uri == uri.toString() })
        val missingProvider = android.net.Uri.parse("content://com.os73.dosos73.nonexistent-provider/document/x")
        assertEquals(FileAvailability.UNAVAILABLE, FileAccess.inspect(context, missingProvider))
    }
    @Test fun reportsPermissionFailureAndDoesNotTreatUnknownRemoteAbsenceAsDeletion() = runBlocking {
        RecoveryTestDocumentsProvider.reset()
        val tree = DocumentsContract.buildTreeDocumentUri("com.os73.dosos73.recovery-tests", "root")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val uri = DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", "access.bin")!!
        try {
            assertEquals(FileAvailability.AVAILABLE, FileAccess.inspect(context, uri))
            RecoveryTestDocumentsProvider.denyQuery = true
            assertEquals(FileAvailability.PERMISSION_REQUIRED, FileAccess.inspect(context, uri))
            RecoveryTestDocumentsProvider.denyQuery = false
            RecoveryTestDocumentsProvider.emptyQuery = true
            assertEquals(FileAvailability.UNAVAILABLE, FileAccess.inspect(context, uri))
            RecoveryTestDocumentsProvider.emptyQuery = false
            DocumentsContract.deleteDocument(context.contentResolver, uri)
            // DocumentsProvider may reject the old tree URI once it ceases to be a child.
            assertNotEquals(FileAvailability.MISSING, FileAccess.inspect(context, uri))
        } finally { RecoveryTestDocumentsProvider.reset() }
    }
}
