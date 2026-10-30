package com.os73.dosos73

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class ClipboardInputTest {
    private val stores = mutableListOf<androidx.lifecycle.ViewModelStore>()
    private fun model(): MainViewModel {
        val store = androidx.lifecycle.ViewModelStore().also { stores.add(it) }
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        lateinit var model: MainViewModel
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            model = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (model.batchSaving.value && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertFalse("Draft must initialize before input mutations", model.batchSaving.value)
        assertFalse(model.batchStorageError.value)
        return model
    }
    @org.junit.After fun releaseModels() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { stores.forEach { it.clear() } }
    }

    @Test
    fun emptyOrImageClipboardDoesNotBecomeUrlError() {
        val model = model()
        Transfers.update(TransferState())
        for (text in listOf(null, "", "ordinary text", "content://media/images/1")) {
            model.paste(text)
            assertEquals(R.string.nothing_to_paste, model.state.value.error)
            assertFalse(model.state.value.busy)
        }
    }

    @Test
    fun multipleLinksOpenReviewWithoutAutomaticAnalysisOrTransfer() {
        val model = model()
        Transfers.update(TransferState())
        model.paste("https://example.com/one https://example.com/two")
        assertNull(model.state.value.error)
        assertEquals(2, model.batch.value.size)
        assertTrue(model.batch.value.all { it.analysis == null })
        assertFalse(model.state.value.busy)
    }

    @Test
    fun batchCanonicalDuplicatesAndInvalidLinksRemainReviewable() {
        val model = model()
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
