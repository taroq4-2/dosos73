package com.os73.dosos73

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SharedRuntimeTest {
    @Test fun everySharedFileMatchesSignedManifestAndSecondInstallDoesNoWrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Only the universal transformed APK carries a common runtime", context.assets.list("dosos73")?.contains("python-common.json") == true)
        EngineRuntime.initialize(context)
        val metadata = context.assets.open("dosos73/python-common.json").bufferedReader().use { JSONObject(it.readText()) }
        val root = File(context.noBackupFilesDir, "youtubedl-android/packages/python")
        val files = metadata.getJSONObject("files")
        val timestamps = HashMap<File, Long>()
        files.keys().forEach { name ->
            val file = File(root, name)
            val spec = files.getJSONObject(name)
            assertEquals(name, spec.getLong("bytes"), file.length())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            assertEquals(name, spec.getString("sha256"), digest.digest().joinToString("") { "%02x".format(it) })
            assertEquals(name, spec.getInt("mode"), Os.stat(file.absolutePath).st_mode and 511)
            timestamps[file] = file.lastModified()
        }
        PythonCommonRuntime.install(context)
        timestamps.forEach { (file, before) -> assertEquals(file.path, before, file.lastModified()) }
        // A separate, test-owned root exercises recovery after a missing completion marker.
        val scratch = File(context.cacheDir, "common-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            PythonCommonRuntime.install(context, scratch)
            val marker = File(scratch, ".dosos-common-sha256")
            assertEquals(metadata.getString("sha256"), marker.readText())
            assertTrue(marker.delete())
            File(scratch, files.keys().next()).writeText("interrupted")
            PythonCommonRuntime.install(context, scratch)
            assertEquals(metadata.getString("sha256"), marker.readText())
            assertEquals(files.getJSONObject(files.keys().next()).getLong("bytes"), File(scratch, files.keys().next()).length())
        } finally { scratch.deleteRecursively() }
    }
}
