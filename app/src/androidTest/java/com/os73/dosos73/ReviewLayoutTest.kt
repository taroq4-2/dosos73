package com.os73.dosos73

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises extracted presentation with real semantics/clicks rather than duplicating callbacks. */
class ReviewLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigationRetainsSelectionAndTouchTargetsAtDoubleFontInRtl() {
        val page = mutableStateOf("home")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Column {
                        Spacer(Modifier.weight(1f))
                        AppNavigation(page.value) { page.value = it }
                    }
                }
            }
        }
        for (key in listOf("home", "downloads", "files", "more")) {
            compose.onNodeWithTag("nav_$key").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                .performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(key, page.value) }
        }
    }

    @Test fun duplicateReviewRetainsAllThreeExplicitActionsAtDoubleFont() {
        val answer = mutableStateOf<Int?>(null)
        val item = SavedFile("content://test/download/1", "اسم ملف محلي طويل.mp4", "video/mp4", 1234, 0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { DownloadHistoryReview(item) { answer.value = it } }
            }
        }
        for ((tag, result) in listOf("history_open" to 2, "history_again" to 1, "history_cancel" to 0)) {
            // Open is inside the scrollable body; the explicit buttons are in
            // the dialog's fixed action row and have no scrolling ancestor.
            if (tag == "history_open") compose.onNodeWithTag(tag).performScrollTo()
            compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(result, answer.value) }
        }
    }

    @Test fun pendingShareDoesNotCoverDraftRecovery() {
        val saving = mutableStateOf(true)
        val storageError = mutableStateOf(false)
        var reviewed = false
        compose.setContent {
            MaterialTheme {
                Box {
                    Button({}, Modifier.testTag("draft_retry_fixture")) { Text("Retry draft") }
                    PendingSharedReview(ScreenState(pendingShared = "https://example.com/new"),
                        batchBusy = false, batchSaving = saving.value, batchStorageError = storageError.value,
                        onReview = { reviewed = true }, onDiscard = {})
                }
            }
        }
        compose.onNodeWithTag("pending_share_dialog").assertDoesNotExist()
        compose.runOnIdle { storageError.value = true; saving.value = false }
        compose.onNodeWithTag("pending_share_dialog").assertDoesNotExist()
        compose.onNodeWithTag("draft_retry_fixture").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { storageError.value = false }
        compose.onNodeWithTag("pending_share_review").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, reviewed) }
    }
}
