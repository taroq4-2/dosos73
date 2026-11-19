package com.os73.dosos73

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Phase C: a cancelled or replaced analysis can never publish its late result. */
@RunWith(AndroidJUnit4::class)
class StaleAnalysisTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private fun analysis(url: String, title: String) =
        Analysis(url, title, listOf(Choice("direct", Kind.FILE, "bin", "Original")), true)

    @After fun reset() { MainViewModel.analysisForTests = null }

    @Test
    fun lateResultOfACancelledAnalysisIsDropped() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        MainViewModel.analysisForTests = { url ->
            if (url.contains("slow")) {
                slowStarted.complete(Unit)
                // Ignore cancellation on purpose: the result arrives after the user moved on.
                withContext(kotlinx.coroutines.NonCancellable) { releaseSlow.await() }
                analysis(url, "STALE")
            } else analysis(url, "fresh")
        }
        val model = withContext(Dispatchers.Main) { MainViewModel(app) }
        withTimeout(10_000) { model.batchSaving.first { !it } }
        withContext(Dispatchers.Main) { model.edit("https://example.com/slow"); model.analyze() }
        slowStarted.await()
        withContext(Dispatchers.Main) { model.cancelAnalysis() }
        releaseSlow.complete(Unit)
        val idle = withTimeout(10_000) { model.state.first { !it.busy } }
        assertEquals(AnalysisStage.CANCELED, idle.stage)
        assertNull(idle.analysis)
        withContext(Dispatchers.Main) { model.edit("https://example.com/fresh"); model.analyze() }
        val ready = withTimeout(10_000) { model.state.first { it.analysis != null } }
        assertEquals("fresh", ready.analysis!!.title)
        assertEquals(AnalysisStage.READY, ready.stage)
    }

    @Test
    fun editingIsRefusedWhileAnalysisRunsSoNoResultAppliesToAnotherLink() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        MainViewModel.analysisForTests = { url -> gate.await(); analysis(url, "first") }
        val model = withContext(Dispatchers.Main) { MainViewModel(app) }
        withTimeout(10_000) { model.batchSaving.first { !it } }
        withContext(Dispatchers.Main) {
            model.edit("https://example.com/one"); model.analyze()
            model.edit("https://example.com/two")
        }
        assertEquals("https://example.com/one", model.state.value.url)
        gate.complete(Unit)
        val ready = withTimeout(10_000) { model.state.first { it.analysis != null } }
        assertEquals("https://example.com/one", ready.analysis!!.url)
    }
}
