package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeCancellationTest {
    @Test fun cancellingOwnedPythonStopsItsHelperAndLeavesNoSessionFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EngineRuntime.initialize(context)
        val folder = File(context.cacheDir, "native-cancel-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val childPid = File(folder, "helper.pid")
        val script = File(folder, "fixture.py").apply { writeText("""
            import subprocess, sys, time
            child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])
            with open(sys.argv[1], 'w') as output: output.write(str(child.pid))
            time.sleep(120)
        """.trimIndent()) }
        val session = NativeProcessSession(context, script)
        try {
            val running = async(Dispatchers.IO) { runCatching { session.execute(listOf(childPid.absolutePath)) } }
            withTimeout(15000) { while (!childPid.isFile || childPid.length() == 0L) delay(20) }
            val pid = childPid.readText().toInt()
            session.cancel()
            assertTrue(withTimeout(5000) { running.await() }.isFailure)
            withTimeout(5000) {
                while (runCatching { Os.kill(pid, 0) }.isSuccess) {
                    // A terminated child may briefly remain as a zombie awaiting init reaping.
                    val state = runCatching { File("/proc/$pid/stat").readText().substringAfterLast(") ").first() }.getOrNull()
                    if (state == 'Z') break
                    delay(20)
                }
            }
            assertFalse(File(context.cacheDir, "native-session-${session.id}.pid").exists())
        } finally { session.close(); folder.deleteRecursively() }
    }
}
