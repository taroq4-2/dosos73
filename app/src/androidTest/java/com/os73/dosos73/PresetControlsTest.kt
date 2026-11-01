package com.os73.dosos73

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PresetControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedNameRestoresAndUnavailablePresetNeverAppliesOrStartsTransfer() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = "-preset-ui-${java.util.UUID.randomUUID()}"
        val directory = File(base.cacheDir, suffix).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(name + suffix, mode)
        }
        val choice = Choice("source-720", Kind.VIDEO, "webm", "720p", expectedHeight = 720)
        val analysis = mutableStateOf(Analysis("https://example.com/video", "Video", listOf(choice), false))
        var applyCount = 0
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalDensity provides Density(1f, 2f)) {
                    MaterialTheme { PresetControls(analysis.value, choice) { _, _ -> applyCount++ } }
                }
            }
            compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("preset_save").assertIsEnabled(); true }.getOrDefault(false) }
            compose.onNodeWithTag("preset_save").performClick()
            compose.onNodeWithTag("preset_name").performTextInput("إعداد الفيديو")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("preset_name").assertTextContains("إعداد الفيديو")
            compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("preset_name").assertIsEnabled(); true }.getOrDefault(false) }
            // Preferences change after composition; saving must capture the latest folder.
            val tree = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
            context.getSharedPreferences("destination", Context.MODE_PRIVATE).edit().putString("tree", tree).commit()
            compose.onNodeWithText(context.getString(R.string.preset_confirm_save)).performClick()
            compose.waitUntil(10000) { runCatching { DownloadPresets(context).read().size == 1 }.getOrDefault(false) }
            compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("preset_list").assertIsEnabled(); true }.getOrDefault(false) }
            assertEquals(tree, DownloadPresets(context).read().single().tree)
            compose.runOnIdle { analysis.value = analysis.value.copy(choices = listOf(choice.copy(extension = "mp4"))) }
            compose.onNodeWithTag("preset_list").performClick()
            compose.onNodeWithText(context.getString(R.string.preset_apply)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.preset_unavailable)).performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, applyCount) }
            compose.onNodeWithText(context.getString(R.string.preset_delete)).performScrollTo().performClick()
            compose.waitUntil(10000) { DownloadPresets(context).read().isEmpty() }
            compose.onNodeWithText(context.getString(R.string.preset_empty)).assertIsDisplayed()
        } finally {
            context.getSharedPreferences("destination", Context.MODE_PRIVATE).edit().clear().commit()
            directory.deleteRecursively()
        }
    }
}
