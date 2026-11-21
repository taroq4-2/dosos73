package com.os73.dosos73

import android.app.ActivityManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Phase G regression (found on the emulator with airplane mode): a transfer deferred for the
 * network kept its lease but its redesigned card offered only Details/Cancel, and "Start queue"
 * stays disabled while the phase is WAITING. 1.3.2 showed Resume for WAITING; so must vNext.
 * Never contacts a public endpoint: the bounded test wait keeps the fixture offline.
 */
class NetworkWaitResumeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity

    @Suppress("DEPRECATION")
    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == DownloadService::class.java.name }

    @Test fun aNetworkDeferredCardOffersResumeAndResumeRestartsTheRetainedLease() = runBlocking {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val task = DownloadTask(Analysis("https://example.com/waiting.bin", "Waiting fixture", listOf(choice), true), choice, null)
        DownloadService.networkWaitMillisForTests = 200
        try {
            DownloadQueue.initialize(context)
            DownloadQueue.enqueue(context, task)
            context.startForegroundService(Intent(context, DownloadService::class.java))
            withTimeout(10000) {
                while (Transfers.state.value.phase != Phase.WAITING || serviceRunning()) delay(20)
            }
            assertEquals(task.id, DownloadQueue.interrupted(context)?.id)

            compose.onNodeWithTag("nav_downloads").performClick()
            compose.waitForIdle()
            assertTrue("The card must say it waits for the network",
                compose.onAllNodes(hasText(context.getString(R.string.waiting_for_network), substring = true))
                    .fetchSemanticsNodes().isNotEmpty())
            val resume = context.getString(R.string.resume)
            assertTrue("A network-deferred card must offer Resume",
                compose.onAllNodes(hasContentDescription(resume)).fetchSemanticsNodes().isNotEmpty())

            compose.onNodeWithContentDescription(resume).performScrollTo().performClick()
            // The toggle continues the same lease in a new foreground worker (it waits again here).
            withTimeout(10000) { while (!serviceRunning()) delay(20) }
            assertEquals(task.id, DownloadQueue.interrupted(context)?.id)
            assertFalse(DownloadQueue.interrupted(context)!!.userPaused)
            assertFalse(FailureJournal.failures.value.any { it.task.id == task.id })
        } finally {
            DownloadService.networkWaitMillisForTests = null
            context.startService(Intent(context, DownloadService::class.java).setAction("cancel"))
            withTimeout(10000) { while (serviceRunning()) delay(20) }
            DeferredTransferScheduler.userStarted(context)
            DownloadQueue.finish(context, task.id)
            DownloadQueue.remove(context, task.id)
            FailureJournal.remove(context, task.id)
            TransferFiles.discard(context, task.id)
            Transfers.take()
            Transfers.update(TransferState())
        }
    }
}
