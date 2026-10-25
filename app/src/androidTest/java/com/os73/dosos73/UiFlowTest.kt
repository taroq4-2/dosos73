package com.os73.dosos73

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class UiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun aboutContactAndDraftSurviveNavigation() {
        compose.runOnIdle { Transfers.update(TransferState()) }
        compose.onNodeWithTag("url").assertExists()
        capture("01-main-ar.png")
        compose.onNodeWithTag("about").performScrollTo().performClick()
        capture("05-about-ar.png")
        compose.onNodeWithTag("contact").performScrollTo().performClick()
        capture("06-contact-ar.png")
        compose.onNodeWithTag("compose_email").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_invalid)).assertExists()
        compose.onNodeWithTag("contact_email").performScrollTo().performTextInput("tester@example.com")
        compose.onNodeWithTag("contact_subject").performScrollTo().performTextInput("Support")
        compose.onNodeWithTag("contact_message").performScrollTo().performTextInput("Please help with a download.")
        compose.onNodeWithTag("back").performScrollTo().performClick()
        compose.onNodeWithTag("contact").performScrollTo().performClick()
        compose.onNodeWithTag("contact_email").assertTextContains("tester@example.com")
        compose.onNodeWithTag("contact_message").assertTextContains("Please help with a download.")
        // Never opens a mail composer or sends a message during automated tests.
    }
}
