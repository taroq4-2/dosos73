package com.os73.dosos73

import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Landscape component fit, not an Activity state-restoration test. */
class LandscapeReviewLayoutTest {
    private val compose = createComposeRule()

    // Orientation must settle before the Compose rule launches its Activity.
    // Rotating afterwards can destroy that rule's cached ComponentActivity.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(LandscapeWindowRule()).around(compose)

    @Test fun transferDetailsCanCloseWithLongContentAtDoubleFontInRealLandscape() {
        var closes = 0
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
        compose.onNodeWithTag("transfer_details_close").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }
}

private class LandscapeWindowRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val automation = instrumentation.uiAutomation
            val oldAutoRotate = Settings.System.getInt(context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION, 1)
            val oldUserRotation = Settings.System.getInt(context.contentResolver,
                Settings.System.USER_ROTATION, UiAutomation.ROTATION_FREEZE_0)
            val changed = CountDownLatch(1)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                        changed.countDown()
                }
            }
            ContextCompat.registerReceiver(context, receiver,
                IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                assertTrue("Could not set the test window to landscape",
                    automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
                if (context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE)
                    assertTrue("Landscape configuration was not delivered before Activity launch",
                        changed.await(10, TimeUnit.SECONDS))
                assertEquals(Configuration.ORIENTATION_LANDSCAPE, context.resources.configuration.orientation)
                base.evaluate()
            } finally {
                context.unregisterReceiver(receiver)
                // RuleChain has already closed the Compose Activity here.
                automation.setRotation(if (oldAutoRotate != 0) UiAutomation.ROTATION_UNFREEZE else oldUserRotation)
            }
        }
    }
}
