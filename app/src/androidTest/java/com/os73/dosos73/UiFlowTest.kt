package com.os73.dosos73

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

class UiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder =
            File(instrumentation.targetContext.getExternalFilesDir(null), "evidence").apply {
                mkdirs()
            }
        File(folder, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun sharedTextReturnsToHomeFromFileLibrary() {
        compose.onNodeWithTag("nav_files").performClick()
        compose.runOnIdle {
            compose.activity.startActivity(
                android.content
                    .Intent(compose.activity, MainActivity::class.java)
                    .setAction(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, "not a URL")
            )
        }
        compose.waitUntil(5_000) {
            runCatching { compose.onAllNodesWithTag("url").fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
        compose.onNodeWithTag("url").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.error_url)).assertExists()
    }

    @Test
    fun primaryNavigationKeepsRealEmptyStatesAndInput() {
        compose.runOnIdle { Transfers.update(TransferState()) }
        compose.onNodeWithTag("url").performTextInput("https://example.com/saved-input")
        compose.onNodeWithTag("nav_downloads").performClick()
        compose
            .onNodeWithText(compose.activity.getString(R.string.no_active_downloads))
            .assertExists()
        capture("02-downloads-ar.png")
        compose.onNodeWithTag("nav_files").performClick()
        capture("03-files-ar.png")
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("url").assertTextContains("https://example.com/saved-input")
    }

    @Test
    fun aboutContactAndDraftSurviveNavigation() {
        compose.runOnIdle { Transfers.update(TransferState()) }
        compose.onNodeWithTag("url").assertExists()
        capture("01-main-ar.png")
        compose.onNodeWithTag("nav_more").performClick()
        compose.onNodeWithTag("about").performScrollTo().performClick()
        capture("05-about-ar.png")
        compose.onNodeWithTag("contact").performScrollTo().performClick()
        capture("06-contact-ar.png")
        compose.onNodeWithTag("compose_email").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_invalid)).assertExists()
        compose
            .onNodeWithTag("contact_email")
            .performScrollTo()
            .performTextInput("tester@example.com")
        compose.onNodeWithTag("contact_subject").performScrollTo().performTextInput("Support")
        compose
            .onNodeWithTag("contact_message")
            .performScrollTo()
            .performTextInput("Please help with a download.")
        compose.onNodeWithTag("back").performScrollTo().performClick()
        compose.onNodeWithTag("contact").performScrollTo().performClick()
        compose.onNodeWithTag("contact_email").assertTextContains("tester@example.com")
        compose.onNodeWithTag("contact_message").assertTextContains("Please help with a download.")
        // Never opens a mail composer or sends a message during automated tests.
    }
}
