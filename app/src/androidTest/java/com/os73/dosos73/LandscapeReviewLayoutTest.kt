package com.os73.dosos73

import android.app.UiAutomation
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Checks component fit in a real landscape window, not Activity state restoration. */
class LandscapeReviewLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<LandscapeReviewTestActivity>()

    @Test fun transferDetailsCanCloseWithLongContentAtDoubleFontInRealLandscape() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val host = compose.activity
        val resolver = host.contentResolver
        val autoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        val userRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
        var closes = 0
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalDensity provides Density(density.density, 2f)) {
                    MaterialTheme {
                        TransferDetailsDialog(TransferState(Phase.PAUSED,
                            title = "عنوان طويل ".repeat(30), detail = "تفاصيل النقل ".repeat(60))) { closes++ }
                    }
                }
            }
            assertTrue("Could not rotate the fixture window", automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
            compose.waitUntil(15_000) {
                compose.runOnUiThread {
                    val view = host.window.decorView
                    host.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && view.width > view.height
                }
            }
            compose.onNodeWithTag("transfer_details_close").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(1, closes) }
        } finally {
            automation.setRotation(if (autoRotate != 0) UiAutomation.ROTATION_UNFREEZE else userRotation)
        }
    }
}
