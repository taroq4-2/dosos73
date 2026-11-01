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
            withTimeout(5000) { while (File(context.cacheDir, "native-session-${session.id}.pid").exists()) delay(20) }
        } finally { session.close(); folder.deleteRecursively() }
    }

    @Test fun exitedLeaderCannotLeaveAHelperHoldingItsOutputPipe() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EngineRuntime.initialize(context)
        val folder = File(context.cacheDir, "native-orphan-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val childPid = File(folder, "helper.pid")
        val leaderPid = File(folder, "leader.pid")
        val script = File(folder, "fixture.py").apply { writeText("""
            import os, subprocess, sys
            child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(45)'])
            with open(sys.argv[1], 'w') as output: output.write(str(child.pid))
            with open(sys.argv[2], 'w') as output: output.write(str(os.getpid()))
            # The helper inherits stdout; the Java process object now observes an exited leader.
            os._exit(0)
        """.trimIndent()) }
        val session = NativeProcessSession(context, script)
        try {
            val running = async(Dispatchers.IO) {
                runCatching { session.execute(listOf(childPid.absolutePath, leaderPid.absolutePath)) }
            }
            withTimeout(15000) { while (childPid.length() == 0L || leaderPid.length() == 0L) delay(20) }
            val helper = childPid.readText().toInt()
            val leader = leaderPid.readText().toInt()
            withTimeout(5000) { while (processRunning(leader)) delay(20) }
            session.cancel()
            // Normal EOF cleanup may already have reaped the helper before explicit cancel.
            withTimeout(5000) { running.await() }
            withTimeout(5000) { while (processRunning(helper)) delay(20) }
            withTimeout(5000) { while (File(context.cacheDir, "native-session-${session.id}.pid").exists()) delay(20) }
        } finally { session.close(); folder.deleteRecursively() }
    }

    @Test fun mainThreadCancellationNeverWaitsForTheGroupCreationGate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        EngineRuntime.initialize(context)
        val folder = File(context.cacheDir, "native-gate-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ready = File(folder, "gate-held.pid")
        val gate = File(context.noBackupFilesDir, "native-process-groups.lock")
        val script = File(folder, "fixture.py").apply { writeText("""
            import fcntl, subprocess, sys, time
            child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(45)'])
            with open(sys.argv[1], 'a+b') as gate:
                fcntl.lockf(gate, fcntl.LOCK_EX)
                with open(sys.argv[2], 'w') as output: output.write(str(child.pid))
                # Simulate a delayed bootstrap owning the exact cross-process gate.
                time.sleep(0.75)
                fcntl.lockf(gate, fcntl.LOCK_UN)
            time.sleep(45)
        """.trimIndent()) }
        val session = NativeProcessSession(context, script)
        try {
            val running = async(Dispatchers.IO) { runCatching { session.execute(listOf(gate.absolutePath, ready.absolutePath)) } }
            withTimeout(15000) { while (ready.length() == 0L) delay(10) }
            val helper = ready.readText().toInt()
            var elapsed = Long.MAX_VALUE
            instrumentation.runOnMainSync {
                val start = System.nanoTime()
                session.cancel()
                elapsed = System.nanoTime() - start
            }
            assertTrue("Main-thread cancel must not wait for file locks: ${elapsed / 1_000_000}ms", elapsed < 250_000_000L)
            assertTrue(withTimeout(8000) { running.await() }.isFailure)
            withTimeout(5000) { while (processRunning(helper)) delay(20) }
        } finally { session.close(); folder.deleteRecursively() }
    }

    @Test fun aRecordNamingAnotherLiveGroupCannotKillThatSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EngineRuntime.initialize(context)
        val folder = File(context.cacheDir, "native-identity-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val script = File(folder, "fixture.py").apply { writeText("""
            import os, sys, time
            with open(sys.argv[1], 'w') as output: output.write(str(os.getpid()))
            time.sleep(45)
        """.trimIndent()) }
        val first = NativeProcessSession(context, script)
        val other = NativeProcessSession(context, script)
        val firstReady = File(folder, "first.pid")
        val otherReady = File(folder, "other.pid")
        val firstRecord = File(context.cacheDir, "native-session-${first.id}.pid")
        try {
            val a = async(Dispatchers.IO) { runCatching { first.execute(listOf(firstReady.absolutePath)) } }
            val b = async(Dispatchers.IO) { runCatching { other.execute(listOf(otherReady.absolutePath)) } }
            withTimeout(15000) { while (firstReady.length() == 0L || otherReady.length() == 0L) delay(20) }
            val unrelatedPid = otherReady.readText().toInt()
            val otherFields = File(context.cacheDir, "native-session-${other.id}.pid").readText().trim().split(' ')
            firstRecord.writeText("${otherFields[0]} ${otherFields[1]} ${first.id} ${otherFields[3]}")
            first.cancel()
            assertTrue(withTimeout(5000) { a.await() }.isFailure)
            assertFalse("The unrelated session must remain running", b.isCompleted)
            assertTrue(processRunning(unrelatedPid))
            other.cancel()
            assertTrue(withTimeout(5000) { b.await() }.isFailure)
        } finally {
            first.close(); other.close()
            firstRecord.delete() // Only the deliberately forged, test-owned record.
            folder.deleteRecursively()
        }
    }

    private fun processRunning(pid: Int): Boolean {
        if (runCatching { Os.kill(pid, 0) }.isFailure) return false
        return runCatching { File("/proc/$pid/stat").readText().substringAfterLast(") ").first() != 'Z' }
            .getOrDefault(true)
    }

}
