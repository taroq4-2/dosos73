package com.os73.dosos73

import android.app.LocaleManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in network/device acceptance test; never disguises unavailable network as success. */
@RunWith(AndroidJUnit4::class)
class LiveDownloadTest {
    @Test fun directDownloadAndRealScreenshots() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("ar")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(2500)
        fun screenshot(name: String) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val folder = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        screenshot("01-main-ar.png")
        val url = "https://media.w3.org/2010/05/sintel/trailer.mp4"
        val analysis = DirectDownloader().analyze(url)
        assertNotNull("Direct media HEAD analysis", analysis)
        context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(5000)
        screenshot("02-analyzed-ar.png")
        val task = DownloadTask(analysis!!, analysis.choices.first(), null)
        assertTrue(Transfers.reserve(task))
        context.startForegroundService(Intent(context, DownloadService::class.java))
        kotlinx.coroutines.withTimeout(45_000) {
            while (Transfers.state.value.active && Transfers.state.value.bytes == 0L) delay(40)
        }
        delay(100) // Present a real byte-progress update; never inject a simulated percentage.
        screenshot("03-download-ar.png")
        val deadline = System.currentTimeMillis() + 180_000
        while (Transfers.state.value.active && System.currentTimeMillis() < deadline) delay(200)
        val result = Transfers.state.value
        assertEquals("Download must commit a verified file", Phase.COMPLETE, result.phase)
        assertNotNull(result.uri)
        context.contentResolver.openFileDescriptor(result.uri!!, "r")!!.use { assertTrue(it.statSize > 0) }
        delay(1000) // Let Compose present the committed state before capturing evidence.
        screenshot("04-complete-ar.png")
        val player = android.media.MediaPlayer()
        val surface = android.view.SurfaceView(context)
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first { it is MainActivity }
            activity.addContentView(surface, android.view.ViewGroup.LayoutParams(-1, -1))
        }
        try {
            kotlinx.coroutines.withTimeout(15000) { while (!surface.holder.surface.isValid) delay(100) }
            player.setDisplay(surface.holder)
            player.setDataSource(context, result.uri!!)
            player.prepare()
            assertTrue(player.duration > 0)
            assertTrue(player.videoWidth > 0 && player.videoHeight > 0)
            player.start()
            kotlinx.coroutines.withTimeout(15000) { while (player.currentPosition <= 0) delay(100) }
            assertTrue("Android media playback must advance", player.currentPosition > 0)
            val folder = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
            File(folder, "direct-playback.txt").writeText("Saved MediaStore video: durationMs=${player.duration}, dimensions=${player.videoWidth}x${player.videoHeight}, playedPositionMs=${player.currentPosition}\nAndroid MediaPlayer playback passed; this is not a YouTube download or an external player UI test.\n")
        } finally {
            player.release()
            instrumentation.runOnMainSync { (surface.parent as? android.view.ViewGroup)?.removeView(surface) }
        }
    }

    @Test fun mediaExtractorAndNativeDownload() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMedia") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = InstrumentationRegistry.getArguments().getString("mediaUrl")
            ?: "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.mp4"
        val engine = MediaEngine(context)
        val analysis = engine.analyze(url)
        assertTrue(analysis.choices.isNotEmpty())
        val choice = analysis.choices.first { it.kind == Kind.VIDEO }
        val directory = File(context.cacheDir, "live-media-test").apply { mkdirs() }
        try {
            val output = engine.download(DownloadTask(analysis, choice, null), directory) { _, _ -> }
            OutputStore(context).validate(output, choice.kind, choice.expectedHeight, choice.expectsAudio)
            assertTrue(output.length() > 0)
        } finally { directory.deleteRecursively() }
    }
}
