package com.os73.dosos73

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FileAccessUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun missingBadgeAndRecordOnlyActionAppearAfterMetadataRead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "missing-ui-${java.util.UUID.randomUUID()}.bin")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/dosos73")
        })!!
        context.contentResolver.delete(uri, null, null)
        val item = SavedFile(uri.toString(), "Missing fixture.bin", "application/octet-stream", 100, System.currentTimeMillis())
        var action: String? = null
        compose.setContent { MaterialTheme { SavedFileRow(item, { _, type -> action = type }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText(context.getString(R.string.file_missing)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("file_row").performClick()
        compose.runOnIdle { assertEquals("missing", action) }
        compose.onNodeWithContentDescription(context.getString(R.string.file_actions)).performClick()
        compose.onNodeWithText(context.getString(R.string.forget_missing)).performClick()
        compose.runOnIdle { assertEquals("forget", action) }
    }
}
