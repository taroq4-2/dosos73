package com.os73.dosos73

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class ClipboardInputTest {
    @Test
    fun emptyOrImageClipboardDoesNotBecomeUrlError() {
        val model = MainViewModel(ApplicationProvider.getApplicationContext())
        Transfers.update(TransferState())
        for (text in listOf(null, "", "ordinary text", "content://media/images/1")) {
            model.paste(text)
            assertEquals(R.string.nothing_to_paste, model.state.value.error)
            assertFalse(model.state.value.busy)
        }
    }

    @Test
    fun multipleLinksOpenReviewWithoutAutomaticAnalysisOrTransfer() {
        val model = MainViewModel(ApplicationProvider.getApplicationContext())
        Transfers.update(TransferState())
        model.paste("https://example.com/one https://example.com/two")
        assertNull(model.state.value.error)
        assertEquals(2, model.batch.value.size)
        assertTrue(model.batch.value.all { it.analysis == null })
        assertFalse(model.state.value.busy)
    }

    @Test
    fun batchCanonicalDuplicatesAndInvalidLinksRemainReviewable() {
        val model = MainViewModel(ApplicationProvider.getApplicationContext())
        model.prepareBatch("https://example.com/a https://example.com:443/a invalid")
        assertEquals(2, model.batch.value.size)
        assertEquals(1, model.batch.value.count { it.selected })
        assertEquals(R.string.error_url, model.batch.value.last().error)
        assertFalse(model.batchBusy.value)
    }

    @Test
    fun diagnosticExportContainsOnlyAllowlistedFields() {
        val report = SafeReport.text(FailureCode.STORAGE_FULL)
        assertTrue(report.contains("STORAGE_FULL"))
        assertTrue(report.contains(BuildConfig.VERSION_NAME))
        assertFalse(report.contains("https://"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("token="))
    }
}
