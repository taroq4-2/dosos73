package com.os73.dosos73

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.content.res.Configuration
import org.junit.Rule
import org.junit.Test

class RotationStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun typedLinkSurvivesRealOrientationChange() {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("url").performTextClearance()
        compose.onNodeWithTag("url").performTextInput("https://example.com/rotation-state")
        try {
            automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithTag("url").assertTextContains("https://example.com/rotation-state")
        } finally { automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) }
    }
}
