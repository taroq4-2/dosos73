package com.os73.dosos73

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Real component launches: a fresh Activity/VM, not a simulated onNewIntent callback. */
class QuickColdLaunchTest {
    @Test fun launcherAliasColdLaunchReadsCopiedLinkAfterDraftInitialization() {
        verifyColdLaunch(useAlias = true)
    }

    @Test fun separatelyEnabledOrdinaryLauncherFallbackWorksFromColdActivity() {
        verifyColdLaunch(useAlias = false)
    }

    private fun verifyColdLaunch(useAlias: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val quick = context.getSharedPreferences("quick_launch", Context.MODE_PRIVATE)
        val network = context.getSharedPreferences("network", Context.MODE_PRIVATE)
        val enabled = quick.booleanOrAbsent("enabled")
        val launcherPaste = quick.booleanOrAbsent("launcher_paste")
        val cleartext = network.booleanOrAbsent("allow_cleartext")
        val alias = ComponentName(context, "com.os73.dosos73.QuickDownloadEntry")
        val aliasState = context.packageManager.getComponentEnabledSetting(alias)
        var savedClipboard: ClipData? = null
        var clipboardCaptured = false
        var originalModel: MainViewModel? = null
        val copiedUrl = "http://example.com/cold-quick-${if (useAlias) "alias" else "launcher"}"
        try {
            QuickLaunch.setEnabled(context, false)
            QuickLaunch.setLauncherPaste(context, false)
            NetworkSettings.setAllowCleartext(context, false)
            // The preparatory launch has no quick action or LAUNCHER category.
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { warm ->
                await(warm, "Preparatory activity must be focused with its existing draft ready") {
                    val model = ViewModelProvider(it)[MainViewModel::class.java]
                    it.hasWindowFocus() && !model.batchSaving.value
                }
                warm.onActivity {
                    originalModel = ViewModelProvider(it)[MainViewModel::class.java]
                    assertFalse(originalModel!!.batchStorageError.value)
                    assertFalse(originalModel!!.state.value.quick)
                    val clipboard = it.getSystemService(ClipboardManager::class.java)
                    savedClipboard = clipboard.primaryClip
                    clipboardCaptured = true
                    clipboard.setPrimaryClip(ClipData.newPlainText("cold quick test", copiedUrl))
                    QuickLaunch.setEnabled(it, true)
                    QuickLaunch.setLauncherPaste(it, !useAlias)
                }
            }
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(if (useAlias) alias else ComponentName(context, MainActivity::class.java))
            ActivityScenario.launch<MainActivity>(launch).use { cold ->
                await(cold, "Cold launcher must open the copied link and finish blocked HTTP analysis") {
                    val state = ViewModelProvider(it)[MainViewModel::class.java].state.value
                    it.lifecycle.currentState == Lifecycle.State.RESUMED && state.quick && !state.busy
                }
                cold.onActivity {
                    val model = ViewModelProvider(it)[MainViewModel::class.java]
                    assertNotSame("Cold launch must not reuse the preparatory ViewModel", originalModel, model)
                    assertEquals("The actual launcher component must reach MainActivity unchanged",
                        launch.component, it.intent.component)
                    assertFalse(model.batchSaving.value)
                    assertFalse(model.batchStorageError.value)
                    assertEquals(copiedUrl, model.state.value.url)
                    assertEquals(R.string.error_cleartext_disabled, model.state.value.error)
                    assertNull(model.state.value.analysis)
                    assertNull(model.state.value.selected)
                    // The modal can own window focus now. Successful clipboard input above exercises
                    // the Activity's foreground focus gate before that modal becomes visible.
                    model.closeQuick()
                    assertFalse(model.state.value.quick)
                    assertEquals("", model.state.value.url)
                }
            }
        } finally {
            // A neutral visible activity is needed to restore the global clipboard on Android 10+.
            // Disable both reads before launching it; never clear application data or the queue.
            QuickLaunch.setEnabled(context, false)
            QuickLaunch.setLauncherPaste(context, false)
            try {
                if (clipboardCaptured) {
                    ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { cleanup ->
                        await(cleanup, "Cleanup activity must have clipboard input focus") { it.hasWindowFocus() }
                        cleanup.onActivity {
                            val clipboard = it.getSystemService(ClipboardManager::class.java)
                            savedClipboard?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
                        }
                    }
                }
            } finally {
                quick.restoreBoolean("enabled", enabled)
                quick.restoreBoolean("launcher_paste", launcherPaste)
                network.restoreBoolean("allow_cleartext", cleartext)
                context.packageManager.setComponentEnabledSetting(alias, aliasState, PackageManager.DONT_KILL_APP)
            }
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, message: String,
        predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        do {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail(message)
    }

    private fun SharedPreferences.booleanOrAbsent(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null

    private fun SharedPreferences.restoreBoolean(key: String, value: Boolean?) {
        val editor = edit()
        if (value == null) editor.remove(key) else editor.putBoolean(key, value)
        check(editor.commit())
    }
}
