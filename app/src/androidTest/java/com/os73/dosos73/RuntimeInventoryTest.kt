package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RuntimeInventoryTest {
    @Test
    fun bundledComponentsExecute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initializationStart = android.os.SystemClock.elapsedRealtime()
        BundledEngine.install(context)
        com.yausername.youtubedl_android.YoutubeDL.init(context)
        com.yausername.ffmpeg.FFmpeg.getInstance().init(context)
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
            assertTrue("Runtime timeout", process.waitFor(15, TimeUnit.SECONDS))
            val result = process.inputStream.bufferedReader().use { it.readText().take(4096) }
            assertEquals(result, 0, process.exitValue())
            return result
        }
        val code =
            "import sys; sys.path.insert(0,sys.argv[1]); import yt_dlp.version,yt_dlp_ejs._version; print(sys.version.split()[0]); print(yt_dlp.version.__version__); print(yt_dlp_ejs._version.__version__)"
        val versions =
            run("$native/libpython.so", "-c", code, File(base, "yt-dlp/yt-dlp").absolutePath)
        assertTrue(versions.contains("2026.10.21"))
        val qjs = run("$native/libqjs.so", "-e", "console.log('quickjs-execution-ok')")
        assertTrue(qjs.contains("quickjs-execution-ok"))
        val ffmpeg = run("$native/libffmpeg.so", "-version").lineSequence().first()
        val report =
            "ABI=${android.os.Build.SUPPORTED_ABIS.first()}\nAndroid=${android.os.Build.VERSION.SDK_INT}\nInitializationMs=$initializationMs (existing installed engine/runtime files)\nPython, yt-dlp, EJS:\n$versions\n$qjs\n$ffmpeg\n"
        val folder = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(folder, "runtime-inventory.txt").writeText(report)
    }
}
