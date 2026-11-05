package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RuntimeInventoryTest {
    @Test
    fun probeDrainsBeyondPipeCapacityWithoutRetainingUnboundedOutput() {
        val process = ProcessBuilder("/system/bin/toybox", "dd", "if=/dev/zero", "bs=4096", "count=256")
            .redirectErrorStream(true).start()
        val output = captureProbe(process, 15, TimeUnit.SECONDS)
        assertEquals(4096, output.length)
        assertTrue(output.all { it == '\u0000' })
        assertFalse(process.isAlive)
    }

    @Test
    fun timedOutProbeKillsChildBeforeReturning() {
        val process = ProcessBuilder("/system/bin/toybox", "sleep", "30")
            .redirectErrorStream(true).start()
        val error = assertThrows(AssertionError::class.java) {
            captureProbe(process, 200, TimeUnit.MILLISECONDS)
        }
        assertEquals("Runtime timeout", error.message)
        assertFalse(process.isAlive)
    }

    private fun captureProbe(process: Process, timeout: Long, unit: TimeUnit): String {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "runtime-inventory-drain").apply { isDaemon = true }
        }
        val drain = worker.submit<String> {
            process.inputStream.use { input ->
                val prefix = ByteArrayOutputStream(4096)
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val keep = minOf(count, 4096 - prefix.size())
                    if (keep > 0) prefix.write(buffer, 0, keep)
                }
                String(prefix.toByteArray(), Charsets.UTF_8)
            }
        }
        try {
            assertTrue("Runtime timeout", process.waitFor(
                (deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
            val output = try {
                drain.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                throw AssertionError("Runtime output drain timeout")
            }
            assertEquals(output, 0, process.exitValue())
            return output
        } finally {
            try {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                }
            } finally {
                drain.cancel(true)
                worker.shutdownNow()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
        }
    }

    @Test
    fun bundledComponentsExecute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initializationStart = android.os.SystemClock.elapsedRealtime()
        EngineRuntime.initialize(context)
        val initializationMs = android.os.SystemClock.elapsedRealtime() - initializationStart
        val native = context.applicationInfo.nativeLibraryDir
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val python = File(base, "packages/python/usr")
        val lib = "${python}/lib:${base}/packages/ffmpeg/usr/lib"
        fun run(vararg arguments: String): String {
            val process =
                ProcessBuilder(*arguments)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] = lib
                        environment()["PYTHONHOME"] = python.absolutePath
                    }
                    .start()
            return captureProbe(process, 15, TimeUnit.SECONDS)
        }
        val code =
            "import sys; sys.path.insert(0,sys.argv[1]); import yt_dlp.version,yt_dlp_ejs._version; import readline,dbm.gnu,mutagen; print(readline._READLINE_LIBRARY_VERSION); print(mutagen.version_string); print(sys.version.split()[0]); print(yt_dlp.version.__version__); print(yt_dlp_ejs._version.__version__)"
        val versions =
            run("$native/libpython.so", "-c", code, File(base, "yt-dlp/yt-dlp").absolutePath)
        assertTrue(versions.contains("2026.10.21"))
        assertTrue(versions.contains("8.3"))
        assertTrue(versions.contains("1.47.0"))
        val qjs = run("$native/libqjs.so", "-e", "console.log('quickjs-execution-ok')")
        assertTrue(qjs.contains("quickjs-execution-ok"))
        val sourceUpdates = if (InstrumentationRegistry.getArguments().getString("requireSourceUpdates") == "true") {
            val crypto = run("$native/libpython.so", "-c",
                "import Cryptodome; from Cryptodome.Cipher import AES; " +
                    "assert Cryptodome.__version__ == '3.24.1'; " +
                    "assert AES.new(bytes(16), AES.MODE_ECB).encrypt(bytes(16)).hex() == '66e94bd4ef8a2c3b884cfa59ca342b2e'; " +
                    "print('Cryptodome 3.24.1 AES verified')")
            assertTrue(crypto.contains("Cryptodome 3.24.1 AES verified"))
            val restricted = run("$native/libqjs.so", "-e",
                "if (typeof __loadScript !== 'undefined') throw Error('file loader exposed'); " +
                    "Promise.all([import('std'), import('os')].map(p => p.then(" +
                    "() => { throw Error('native module exposed'); }, () => true)))" +
                    ".then(() => console.log('QuickJS host access blocked'));")
            assertTrue(restricted.contains("QuickJS host access blocked"))
            "$crypto\n$restricted\n"
        } else ""
        val ffmpeg = run("$native/libffmpeg.so", "-version").lineSequence().first()
        val report =
            "ABI=${android.os.Build.SUPPORTED_ABIS.first()}\nAndroid=${android.os.Build.VERSION.SDK_INT}\nInitializationMs=$initializationMs (existing installed engine/runtime files)\nPython, yt-dlp, EJS:\n$versions\n$qjs\n$sourceUpdates$ffmpeg\n"
        val folder = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(folder, "runtime-inventory.txt").writeText(report)
    }
}
