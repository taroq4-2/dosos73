package com.os73.dosos73

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BatchChoiceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewedEntryKeepsItsChosenFormatWithoutStartingDownload() {
        val mp4 = Choice("mp4", Kind.VIDEO, "mp4", "720p")
        val webm = Choice("webm", Kind.VIDEO, "webm", "1080p")
        val analysis = Analysis("https://example.com/video", "Example", listOf(mp4, webm), false)
        val rows = mutableStateOf(listOf(BatchEntry(analysis.url, analysis.url, true, analysis, choice = mp4)))
        var queued = false
        compose.setContent {
            MaterialTheme {
                BatchScreen(rows.value, false, {}, { _, _ -> }, {}, {},
                    { index, choice -> rows.value = rows.value.mapIndexed { i, row -> if (i == index) row.copy(choice = choice) else row } },
                    { _, _ -> },
                    { queued = true })
            }
        }
        compose.onNodeWithText("MP4 · 720p").performClick()
        compose.onNodeWithText("WEBM · 1080p").performClick()
        compose.runOnIdle {
            assertEquals(webm, rows.value.single().choice)
            assertFalse(queued)
        }
    }

    private fun waitForDraft(model: MainViewModel) {
        compose.waitUntil(5000) { !model.batchSaving.value && !model.batchStorageError.value }
    }

    private fun editModel(model: MainViewModel, edit: () -> Unit) {
        compose.runOnIdle(edit)
        waitForDraft(model)
    }

    private fun withModel(block: (MainViewModel) -> Unit) {
        val base = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val folder = java.io.File(base.cacheDir, "batch-model-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val app = object : android.app.Application() {
            init { attachBaseContext(base) }
            override fun getFilesDir() = folder
        }
        val store = androidx.lifecycle.ViewModelStore()
        val model = compose.runOnIdle {
            androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
        }
        try {
            waitForDraft(model)
            block(model)
            waitForDraft(model)
        } finally { compose.runOnIdle { store.clear() }; folder.deleteRecursively() }
    }

    @Test fun destinationOverrideIsPerLinkAndDownloadsIsExplicit() = withModel { model ->
        editModel(model) { model.prepareBatch("https://example.com/a https://example.com/b") }
        val first = model.batch.value.first().url!!
        editModel(model) {
            model.batchDestination(first, "content://com.android.externalstorage.documents/tree/primary%3ADownload")
        }
        assertTrue(model.batch.value.first().destinationOverride)
        assertFalse(model.batch.value.last().destinationOverride)
        editModel(model) { model.batchDestination(first, "file:///private") }
        assertTrue(model.batch.value.first().destination!!.startsWith("content://"))
        editModel(model) { model.batchDestination(first, null) }
        assertTrue(model.batch.value.first().destinationOverride)
        assertNull(model.batch.value.first().destination)
    }

    @Test fun invalidChoicePreservesFailedAndUnselectedRows() = withModel { model ->
        editModel(model) { model.prepareBatch("https://example.com/a invalid https://example.com/b") }
        editModel(model) { model.selectBatch(2, false) }
        assertEquals(3, model.batch.value.size)
        assertEquals(R.string.error_url, model.batch.value[1].error)
        assertFalse(model.batch.value.last().selected)
        editModel(model) { model.chooseBatch(2, Choice("fake", Kind.VIDEO, "mp4", "4K")) }
        assertNull(model.batch.value.last().choice)
    }
    @Test fun storageFailureOffersRetryAndPreventsEnqueue() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val choice = Choice("a", Kind.AUDIO, "mp3", "MP3 V0")
        val analysis = Analysis("https://example.com/a", "Example", listOf(choice), false)
        var retried = false
        compose.setContent { MaterialTheme {
            BatchScreen(listOf(BatchEntry(analysis.url, analysis.url, true, analysis)), false,
                {}, { _, _ -> }, {}, {}, { _, _ -> }, { _, _ -> }, {},
                storageError = true, onRetryStorage = { retried = true })
        } }
        compose.onNodeWithText(context.getString(R.string.batch_queue)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

}

