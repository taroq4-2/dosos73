package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeTransportComparisonTest {
    @Test
    fun compareHandlersOnFixedPublicFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("compareTransport") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EngineRuntime.initialize(context)
        val report = StringBuilder()
        val dir = File(context.cacheDir, "transport-comparison").apply { mkdirs() }
        try {
            for (legacy in listOf(false, true)) repeat(2) { pass ->
                PublicProxy().use { proxy ->
                    val output = File(dir, "fixture-$legacy-$pass.mp4")
                    val request =
                        YoutubeDLRequest(
                                "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.mp4"
                            )
                            .apply {
                                addOption("--ignore-config")
                                addOption("--no-playlist")
                                addOption("--no-remote-components")
                                addOption("--socket-timeout", 20)
                                addOption("--retries", 0)
                                addOption("--proxy", proxy.url)
                                addOption("-o", output.absolutePath)
                                if (legacy)
                                    addOption("--compat-options", "prefer-legacy-http-handler")
                            }
                    try {
                        YoutubeDL.execute(request)
                        val digest = MessageDigest.getInstance("SHA-256")
                        output.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                            }
                        }
                        report.appendLine(
                            "legacy=$legacy pass=$pass bytes=${output.length()} sha256=${digest.digest().joinToString("") { "%02x".format(it) }}"
                        )
                    } catch (e: Exception) {
                        report.appendLine(
                            "legacy=$legacy pass=$pass failure=${FailureClassifier.classify(Stage.TRANSFER,e).code}"
                        )
                    }
                }
            }
        } finally {
            val folder = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
            File(folder, "transport-comparison.txt").writeText(report.toString())
            dir.deleteRecursively()
        }
    }
}
