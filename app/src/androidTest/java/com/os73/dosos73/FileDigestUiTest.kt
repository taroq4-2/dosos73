package com.os73.dosos73

import android.content.Intent
import android.provider.DocumentsContract
import android.text.SpannableString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FileDigestUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun savedFileDigestAndExpectedComparisonUseRealBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RecoveryTestDocumentsProvider.reset()
        val tree = DocumentsContract.buildTreeDocumentUri("com.os73.dosos73.recovery-tests", "root")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val uri = DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", "digest.bin")!!
        try {
            context.contentResolver.openOutputStream(uri)!!.use { it.write("abc".toByteArray()) }
            val item = SavedFile(uri.toString(), "digest.bin", "application/octet-stream", 3L, 0L)
            compose.setContent { DososTheme { FileDetailsContent(item) } }
            compose.onNodeWithTag("calculate_hash").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("file_sha256").fetchSemanticsNodes().isNotEmpty() }
            val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            compose.onNodeWithTag("file_sha256").assertTextContains(sha)
            compose.onNodeWithTag("expected_hash").performScrollTo().performTextInput(sha.uppercase())
            compose.onNodeWithText(context.getString(R.string.sha256_matches)).assertExists()
            compose.onNodeWithTag("expected_hash").performTextClearance()
            compose.onNodeWithTag("expected_hash").performTextInput("0".repeat(64))
            compose.onNodeWithText(context.getString(R.string.sha256_differs)).assertExists()
            compose.onNodeWithTag("expected_hash").performTextClearance()
            compose.onNodeWithTag("expected_hash").performTextInput("bad")
            compose.onNodeWithText(context.getString(R.string.sha256_invalid)).assertExists()
        } finally { RecoveryTestDocumentsProvider.reset() }
    }
    @Test fun charSequenceShareIsDeliveredInsteadOfSilentlyDiscarded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, SpannableString("not a URL") as CharSequence)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val error = java.util.concurrent.atomic.AtomicReference<Int?>()
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            do {
                scenario.onActivity { activity ->
                    val vm = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
                    // A share arriving during cold draft restoration is intentionally
                    // retained for explicit acceptance rather than interrupting the draft.
                    if (!vm.batchSaving.value && !vm.batchStorageError.value) vm.acceptPending()
                    error.set(vm.state.value.error)
                    assertFalse(activity.intent.hasExtra(Intent.EXTRA_TEXT))
                }
                if (error.get() == R.string.error_url) break
                Thread.sleep(20)
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            assertEquals(R.string.error_url, error.get())
        }
    }
    @Test fun oversizedPrivateJournalFailsWithoutOverwritingIt() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = java.io.File(base.cacheDir, "bounded-journal-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) { override fun getFilesDir() = folder }
        val file = java.io.File(folder, "oversized.json")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
            assertThrows(java.io.IOException::class.java) { PrivateJournal(context, file.name).readOrNull() }
            assertEquals(16L * 1024 * 1024 + 1, file.length())
        } finally { folder.deleteRecursively() }
    }
}
