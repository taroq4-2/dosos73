package com.os73.dosos73

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * After an install or update, the first media analysis used to pay for expanding and
 * verifying the bundled runtime (about 44 MB). This prepares it in the background shortly
 * after the first screen appears, at background priority, and only when it is still needed.
 * No network, no extractor run, no user data. A failure here is retried by the analysis.
 */
internal object EnginePrewarm {
    private val started = AtomicBoolean(false)

    fun scheduleIfNeeded(context: Context, delayMs: Long = 2_500) {
        val app = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                if (!needed(app)) return@Thread
                Thread.sleep(delayMs)
                EngineRuntime.initialize(app)
                Diagnostics.event(Stage.INITIALIZE, "prewarmed")
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
                Diagnostics.event(Stage.INITIALIZE, "prewarm_failed")
            } catch (_: LinkageError) {
                Diagnostics.event(Stage.INITIALIZE, "prewarm_failed")
            }
        }, "dosos-engine-prewarm").start()
    }

    /** True until this APK's runtime and the unpacked engine have been installed once. */
    internal fun needed(context: Context): Boolean {
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val marker = File(base, ".dosos-runtime-install")
        val installed = marker.isFile && runCatching { marker.readText() }.getOrNull() == context.applicationInfo.sourceDir
        return !installed || !File(BundledEngine.unpacked(context), BundledEngine.COMPLETE).isFile
    }
}
