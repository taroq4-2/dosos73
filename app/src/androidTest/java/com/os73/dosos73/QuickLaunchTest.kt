package com.os73.dosos73

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Uses policy-blocked HTTP so quick analysis never sends a request to an external server. */
class QuickLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: MainViewModel
    private lateinit var quickPrefs: SharedPreferences
    private lateinit var networkPrefs: SharedPreferences
    private lateinit var entry: ComponentName
    private var savedEnabled: Boolean? = null
    private var savedLauncher: Boolean? = null
    private var savedCleartext: Boolean? = null
    private var savedAliasState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    private var savedClip: ClipData? = null
    private var savedIntent: Intent? = null
    private var initialized = false
    private val firstUrl = "http://example.com/quick-first"

    @Before fun prepareIsolatedFlow() {
        model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.waitUntil(10000) { !model.batchSaving.value && compose.activity.hasWindowFocus() }
        assertFalse("The existing batch draft must load successfully", model.batchStorageError.value)
        compose.runOnIdle {
            val activity = compose.activity
            savedIntent = Intent(activity.intent)
            quickPrefs = activity.getSharedPreferences("quick_launch", Context.MODE_PRIVATE)
            networkPrefs = activity.getSharedPreferences("network", Context.MODE_PRIVATE)
            savedEnabled = quickPrefs.booleanOrAbsent("enabled")
            savedLauncher = quickPrefs.booleanOrAbsent("launcher_paste")
            savedCleartext = networkPrefs.booleanOrAbsent("allow_cleartext")
            entry = ComponentName(activity, "com.os73.dosos73.QuickDownloadEntry")
            savedAliasState = activity.packageManager.getComponentEnabledSetting(entry)
            savedClip = activity.getSystemService(ClipboardManager::class.java).primaryClip
            initialized = true
            QuickLaunch.setEnabled(activity, false)
            QuickLaunch.setLauncherPaste(activity, false)
            NetworkSettings.setAllowCleartext(activity, false)
        }
    }

    @After fun restoreUserState() {
        if (!initialized) return
        compose.runOnIdle {
            model.closeQuick()
            model.cancelAnalysis()
            quickPrefs.restoreBoolean("enabled", savedEnabled)
            quickPrefs.restoreBoolean("launcher_paste", savedLauncher)
            networkPrefs.restoreBoolean("allow_cleartext", savedCleartext)
            compose.activity.packageManager.setComponentEnabledSetting(entry, savedAliasState,
                PackageManager.DONT_KILL_APP)
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            savedClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            savedIntent?.let { compose.activity.intent = it }
        }
    }

    private fun SharedPreferences.booleanOrAbsent(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null

    private fun SharedPreferences.restoreBoolean(key: String, value: Boolean?) {
        val editor = edit()
        if (value == null) editor.remove(key) else editor.putBoolean(key, value)
        check(editor.commit())
    }

    private fun copy(text: String) = compose.runOnIdle {
        compose.activity.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("quick test", text))
    }

    private fun dispatch(intent: Intent) = compose.runOnIdle {
        val activity = compose.activity
        val harnessIntent = Intent(activity.intent)
        try {
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, intent)
        } finally {
            // ActivityScenario matches lifecycle events using the original launch intent.
            activity.intent = harnessIntent
        }
    }

    @Test fun disabledPinnedIntentCannotReadOrAnalyzeAndAliasTracksOptIn() {
        copy(firstUrl)
        compose.runOnIdle {
            // Exercise absent-key defaults without clearing application data or the saved draft.
            check(quickPrefs.edit().remove("enabled").remove("launcher_paste").commit())
            assertFalse(QuickLaunch.enabled(compose.activity))
            assertFalse(QuickLaunch.launcherPaste(compose.activity))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                compose.activity.packageManager.getComponentEnabledSetting(entry))
            QuickLaunch.setEnabled(compose.activity, true)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                compose.activity.packageManager.getComponentEnabledSetting(entry))
            QuickLaunch.setEnabled(compose.activity, false)
        }
        dispatch(QuickLaunch.intent(compose.activity).putExtra("autoStart", true))
        compose.onNodeWithTag("quick_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(model.state.value.quick)
            assertFalse(model.state.value.busy)
            assertEquals("", model.state.value.url)
        }
    }

    @Test fun ordinaryMainAndNotificationIntentsDoNotReadCopiedLink() {
        copy(firstUrl)
        compose.runOnIdle { QuickLaunch.setEnabled(compose.activity, true) }
        dispatch(Intent(compose.activity, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER))
        dispatch(Intent(compose.activity, MainActivity::class.java))
        compose.onNodeWithTag("quick_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("", model.state.value.url)
            assertFalse(model.state.value.quick)
            assertFalse(model.state.value.busy)
        }
    }

    @Test fun explicitQuickLaunchOpensModalAndRotationDoesNotReadNewClipboard() {
        copy(firstUrl)
        compose.runOnIdle { QuickLaunch.setEnabled(compose.activity, true) }
        dispatch(QuickLaunch.intent(compose.activity)
            .putExtra("url", "https://example.com/injected").putExtra("autoStart", true))
        compose.waitUntil(10000) { model.state.value.quick && !model.state.value.busy }
        compose.onNodeWithTag("quick_dialog").assertExists()
        compose.onNodeWithTag("quick_start").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(firstUrl, model.state.value.url)
            assertEquals(R.string.error_cleartext_disabled, model.state.value.error)
            assertNull(model.state.value.analysis)
        }
        copy("http://example.com/must-not-be-read-on-rotation")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("quick_dialog").assertExists()
        val retainedModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle { assertEquals(firstUrl, retainedModel.state.value.url) }
        compose.onNodeWithTag("quick_close").performClick()
        compose.onNodeWithTag("quick_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(retainedModel.state.value.quick)
            assertEquals("", retainedModel.state.value.url)
            assertNull(retainedModel.state.value.analysis)
        }
    }

    @Test fun unsafeClipboardItemsAreRejectedWithoutOpeningAContentProvider() {
        compose.runOnIdle {
            var resolverAccesses = 0
            val guarded = object : ContextWrapper(compose.activity) {
                override fun getContentResolver(): ContentResolver {
                    resolverAccesses++
                    throw AssertionError("Quick launch must never dereference clipboard providers")
                }
            }
            val sensitive = ClipData.newPlainText("sensitive", firstUrl).apply {
                description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            for (clip in listOf(ClipData.newPlainText("empty", ""),
                ClipData.newRawUri("uri", Uri.parse("content://unregistered.quick.test/private")),
                ClipData.newIntent("intent", Intent(Intent.ACTION_VIEW, Uri.parse(firstUrl))),
                sensitive, ClipData.newPlainText("oversize", "a".repeat(8193)))) {
                clipboard.setPrimaryClip(clip)
                assertThrows(RuntimeException::class.java) { QuickLaunch.copiedUrl(guarded) }
            }
            assertEquals(0, resolverAccesses)
            assertFalse(model.state.value.quick)
            assertFalse(model.state.value.busy)
        }
    }

    @Test fun existingEditedInputIsNotReplacedByQuickLaunch() {
        copy(firstUrl)
        compose.runOnIdle {
            QuickLaunch.setEnabled(compose.activity, true)
            model.edit("https://example.com/my-unsubmitted-edit")
        }
        dispatch(QuickLaunch.intent(compose.activity))
        compose.onNodeWithTag("quick_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("https://example.com/my-unsubmitted-edit", model.state.value.url)
            assertFalse(model.state.value.quick)
            assertFalse(model.state.value.busy)
        }
    }
}
