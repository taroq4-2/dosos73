package com.os73.dosos73

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Activity flows with app-owned outputs; analysis is injected and transfers are policy-blocked. */
class MediaToolsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity
    private lateinit var model: MainViewModel
    private lateinit var destination: SharedPreferences
    private lateinit var network: SharedPreferences
    private lateinit var quick: SharedPreferences
    private var oldTree: String? = null
    private var oldCleartext: Boolean? = null
    private var oldQuick: Boolean? = null
    private var oldLauncherPaste: Boolean? = null
    private var oldAlias = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    private var oldClipboard: ClipData? = null
    private var oldIntent: Intent? = null
    private var prepared = false
    private val ownUrls = mutableSetOf<String>()
    private val ownOutputUris = mutableSetOf<String>()
    private val ownPublicationIds = mutableSetOf<String>()
    private val ownPresets = mutableSetOf<String>()
    private val ownCacheFiles = mutableListOf<File>()
    private val entry get() = ComponentName(context, "com.os73.dosos73.QuickDownloadEntry")

    @Before fun prepareIdleHarness() {
        model = ViewModelProvider(context)[MainViewModel::class.java]
        compose.waitUntil(10000) { !model.batchSaving.value && context.hasWindowFocus() }
        assertFalse("Draft restoration must succeed", model.batchStorageError.value)
        runBlocking { DownloadQueue.initialize(context); FailureJournal.initialize(context) }
        assumeTrue("The flow test does not interrupt existing downloads", !Transfers.state.value.active &&
            DownloadQueue.tasks.value.isEmpty() && runBlocking { DownloadQueue.interrupted(context) == null })
        assumeTrue("On API33+, grant notification permission in the test harness first",
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        compose.runOnIdle {
            destination = context.getSharedPreferences("destination", Context.MODE_PRIVATE)
            network = context.getSharedPreferences("network", Context.MODE_PRIVATE)
            quick = context.getSharedPreferences("quick_launch", Context.MODE_PRIVATE)
            oldTree = destination.getString("tree", null)
            oldCleartext = network.booleanOrAbsent("allow_cleartext")
            oldQuick = quick.booleanOrAbsent("enabled")
            oldLauncherPaste = quick.booleanOrAbsent("launcher_paste")
            oldAlias = context.packageManager.getComponentEnabledSetting(entry)
            oldClipboard = context.getSystemService(ClipboardManager::class.java).primaryClip
            oldIntent = Intent(context.intent)
            prepared = true
            check(destination.edit().remove("tree").commit())
            NetworkSettings.setAllowCleartext(context, false)
            QuickLaunch.setLauncherPaste(context, false)
            model.closeQuick()
            model.edit("")
        }
        // Reload the destination remembered by AppScreen, preserving the ActivityScenario identity.
        compose.activityRule.scenario.recreate()
        model = ViewModelProvider(context)[MainViewModel::class.java]
        compose.waitUntil(10000) { !model.batchSaving.value && context.hasWindowFocus() }
    }

    @After fun restoreOnlyOwnedState() {
        MainViewModel.analysisForTests = null
        if (!prepared) return
        compose.runOnIdle {
            model.closeQuick()
            model.cancelAnalysis()
            oldIntent?.let { context.intent = it }
        }
        try {
            runBlocking {
                if (Transfers.currentTask?.analysis?.url in ownUrls && Transfers.state.value.active) {
                    context.stopService(Intent(context, DownloadService::class.java))
                    withTimeout(10000) { while (serviceRunning()) delay(20) }
                }
                val ownedTasks = (DownloadQueue.tasks.value + listOfNotNull(DownloadQueue.interrupted(context)) +
                    FailureJournal.failures.value.map { it.task }).filter { it.analysis.url in ownUrls }.distinctBy { it.id }
                ownedTasks.forEach { task ->
                    DownloadQueue.finish(context, task.id)
                    DownloadQueue.remove(context, task.id)
                    FailureJournal.remove(context, task.id)
                    TransferFiles.discard(context, task.id)
                }
                ownOutputUris.forEach { uri ->
                    DownloadHistory.remove(context, uri)
                    val file = LibraryRepository.files.value.firstOrNull { it.uri == uri }
                    if (file != null) LibraryRepository.delete(context, file)
                    else context.contentResolver.delete(Uri.parse(uri), null, null)
                }
                ownPublicationIds.forEach { OutputStore(context).forget(it) }
            }
            ownPresets.forEach { DownloadPresets(context).delete(it) }
        } finally {
            ownCacheFiles.forEach { it.delete() }
            compose.runOnIdle {
                val editor = destination.edit()
                if (oldTree == null) editor.remove("tree") else editor.putString("tree", oldTree)
                check(editor.commit())
                network.restoreBoolean("allow_cleartext", oldCleartext)
                quick.restoreBoolean("enabled", oldQuick)
                quick.restoreBoolean("launcher_paste", oldLauncherPaste)
                context.packageManager.setComponentEnabledSetting(entry, oldAlias, PackageManager.DONT_KILL_APP)
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                oldClipboard?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
                if (Transfers.currentTask?.analysis?.url in ownUrls) Transfers.update(TransferState())
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == DownloadService::class.java.name }

    private fun SharedPreferences.booleanOrAbsent(key: String) = if (contains(key)) getBoolean(key, false) else null
    private fun SharedPreferences.restoreBoolean(key: String, value: Boolean?) {
        val editor = edit()
        if (value == null) editor.remove(key) else editor.putBoolean(key, value)
        check(editor.commit())
    }

    private fun analysis(vararg choices: Choice): Analysis {
        val id = UUID.randomUUID().toString()
        // Even if a download is explicitly confirmed, cleartext policy rejects it before DNS/network I/O.
        val url = "http://example.com/media-tools-$id"
        ownUrls += url
        return Analysis(url, "Media tools fixture $id", choices.toList(), true)
    }

    private fun publishReceipt(analysis: Analysis, choice: Choice): SavedFile = runBlocking {
        val source = File(context.cacheDir, "media-tools-${UUID.randomUUID()}").also {
            ownCacheFiles += it
            it.writeText("Small app-owned media tools fixture", Charsets.UTF_8)
        }
        val task = DownloadTask(analysis, choice, null)
        ownPublicationIds += task.id
        val output = OutputStore(context, { uri, _ -> ownOutputUris += uri.toString() }).save(source, task)
        DownloadHistory.record(context, task, output.first, output.second, source.length())
        LibraryRepository.refresh(context)
        assertNotNull("The published receipt must resolve before exercising the UI", DownloadHistory.find(context, task))
        LibraryRepository.files.value.single { it.uri == output.first.toString() }
    }

    private fun installAnalysis(analysis: Analysis) {
        MainViewModel.analysisForTests = { url ->
            check(url == analysis.url) { "Unexpected source must not reach a network analyzer" }
            analysis
        }
    }

    private fun openHome(analysis: Analysis) {
        installAnalysis(analysis)
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("url").performTextClearance()
        compose.onNodeWithTag("url").performTextInput(analysis.url)
        compose.onNodeWithTag("analyze").performClick()
        awaitAnalysis(analysis, quick = false)
    }

    private fun openQuick(analysis: Analysis) {
        installAnalysis(analysis)
        compose.runOnIdle {
            model.edit("")
            QuickLaunch.setEnabled(context, true)
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("media tools fixture", analysis.url))
            val harnessIntent = Intent(context.intent)
            try {
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(context, QuickLaunch.intent(context))
            } finally { context.intent = harnessIntent }
        }
        awaitAnalysis(analysis, quick = true)
        compose.onNodeWithTag("quick_dialog").assertIsDisplayed()
    }

    private fun awaitAnalysis(analysis: Analysis, quick: Boolean) {
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.analysis == analysis && model.state.value.quick == quick }
    }

    private fun startAndAwaitDuplicate(quick: Boolean) {
        val start = if (quick) "quick_start" else "download"
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag(start).assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithTag(start).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("history_dialog").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_dialog").assertIsDisplayed()
    }

    private fun assertNoOwnedEnqueue() {
        runBlocking {
            assertFalse(DownloadQueue.tasks.value.any { it.analysis.url in ownUrls })
            assertFalse(DownloadQueue.interrupted(context)?.analysis?.url in ownUrls)
            assertFalse(FailureJournal.failures.value.any { it.task.analysis.url in ownUrls })
        }
    }

    private fun duplicateChoices(quick: Boolean) {
        val choice = Choice("fixture", Kind.FILE, "txt", "Original")
        val analysis = analysis(choice)
        val published = publishReceipt(analysis, choice)
        if (quick) openQuick(analysis) else openHome(analysis)
        startAndAwaitDuplicate(quick)
        compose.onNode(hasText(context.getString(R.string.cancel)) and hasClickAction() and hasAnyAncestor(hasTestTag("history_dialog"))).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("history_dialog").fetchSemanticsNodes().isEmpty() }
        assertNoOwnedEnqueue()

        startAndAwaitDuplicate(quick)
        compose.onNodeWithTag("history_open").performClick()
        compose.onNodeWithText(context.getString(R.string.preview_local)).assertIsDisplayed()
        compose.onNodeWithText(published.name).assertExists()
        assertNoOwnedEnqueue()
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        if (quick) openQuick(analysis)
        startAndAwaitDuplicate(quick)
        compose.onNodeWithTag("history_again").performClick()
        compose.waitUntil(15000) {
            FailureJournal.failures.value.any { it.task.analysis.url == analysis.url } && !Transfers.state.value.active
        }
        val failures = FailureJournal.failures.value.filter { it.task.analysis.url == analysis.url }
        assertEquals("One confirmation must create exactly one task", 1, failures.size)
        assertEquals(FailureCode.CLEARTEXT_DISABLED, failures.single().code)
        assertEquals(choice, failures.single().task.choice)
        assertNull(failures.single().task.tree)
        assertFalse(DownloadQueue.tasks.value.any { it.analysis.url == analysis.url })
        assertNull(runBlocking { DownloadQueue.interrupted(context) })
    }

    @Test fun homeDuplicateCancelAndOpenDoNotEnqueueAndAgainEnqueuesOnce() = duplicateChoices(quick = false)

    @Test fun quickDuplicateCancelAndOpenDoNotEnqueueAndAgainEnqueuesOnce() = duplicateChoices(quick = true)

    @Test fun appliedPresetChangesExactChoiceAndDestinationBeforeHistoryReview() {
        val original = Choice("fixture-txt", Kind.FILE, "txt", "Original")
        val saved = Choice("fixture-bin", Kind.FILE, "bin", "Original")
        val analysis = analysis(original, saved)
        publishReceipt(analysis, saved)
        // This test owns this temporary preference; no provider is opened for the default-folder preset.
        check(destination.edit().putString("tree", "content://unregistered.media.tools/tree/fixture").commit())
        compose.activityRule.scenario.recreate()
        model = ViewModelProvider(context)[MainViewModel::class.java]
        compose.waitUntil(10000) { !model.batchSaving.value && context.hasWindowFocus() }
        val name = "Flow ${UUID.randomUUID()}"
        assumeTrue("The test does not evict existing presets", DownloadPresets(context).read().size < DownloadPresets.MAX_PRESETS)
        val preset = DownloadPresets(context).save(name, saved, null).single { it.name == name }
        ownPresets += preset.id
        openHome(analysis)
        compose.runOnIdle { assertEquals(original, model.state.value.selected) }
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("preset_list").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithTag("preset_list").performScrollTo().performClick()
        compose.onNodeWithText(name).assertExists()
        // New presets append; select only this test-owned final row, preserving existing entries.
        compose.onAllNodesWithText(context.getString(R.string.preset_apply)).onLast().performScrollTo().performClick()
        compose.waitUntil(10000) { model.state.value.selected == saved && !destination.contains("tree") }
        compose.runOnIdle { assertEquals(saved, model.state.value.selected); assertFalse(destination.contains("tree")) }
        startAndAwaitDuplicate(quick = false)
        compose.onNode(hasText(context.getString(R.string.cancel)) and hasClickAction() and hasAnyAncestor(hasTestTag("history_dialog"))).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("history_dialog").fetchSemanticsNodes().isEmpty() }
        assertNoOwnedEnqueue()
    }
}
