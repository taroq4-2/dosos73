package com.os73.dosos73

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test fun removingQueuedEntryPreservesFailedAndUnselectedRows() {
        val model = MainViewModel(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        model.prepareBatch("https://example.com/a invalid https://example.com/b")
        model.selectBatch(2, false)
        val first = model.batch.value.first()
        model.removeBatch(first)
        assertEquals(2, model.batch.value.size)
        assertEquals(R.string.error_url, model.batch.value.first().error)
        assertFalse(model.batch.value.last().selected)
        model.chooseBatch(1, Choice("fake", Kind.VIDEO, "mp4", "4K"))
        assertNull(model.batch.value.last().choice)
    }
}

