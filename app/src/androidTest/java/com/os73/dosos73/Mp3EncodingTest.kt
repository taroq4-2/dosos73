package com.os73.dosos73

import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Mp3EncodingTest {
    @Test fun encodesPlayableMp3FromPcmAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.yausername.ffmpeg.FFmpeg.getInstance().init(context)
        val folder = File(context.cacheDir, "mp3-test").apply { mkdirs() }
        try {
            val sampleRate = 44100
            val pcm = ByteBuffer.allocate(sampleRate * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(sampleRate) { i -> pcm.putShort((12000 * kotlin.math.sin(2 * Math.PI * 440 * i / sampleRate)).toInt().toShort()) }
            val input = File(folder, "input.pcm").apply { writeBytes(pcm.array()) }
            val output = File(folder, "output.mp3")
            val native = context.applicationInfo.nativeLibraryDir
            val base = File(context.noBackupFilesDir, "youtubedl-android")
            val log = File(folder, "log.txt")
            fun execute(vararg args: String): String {
                val process = ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(log).apply {
                    environment()["LD_LIBRARY_PATH"] = "$base/packages/python/usr/lib:$base/packages/ffmpeg/usr/lib"
                }.start()
                if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Encoder timed out") }
                val text = log.readText()
                assertEquals(text, 0, process.exitValue())
                return text
            }
            execute("$native/libffmpeg.so", "-y", "-f", "s16le", "-ar", "$sampleRate", "-ac", "1", "-i", input.absolutePath,
                "-c:a", "libmp3lame", "-q:a", "0", output.absolutePath)
            val probe = execute("$native/libffprobe.so", "-v", "error", "-show_entries", "stream=codec_name", "-of", "csv=p=0", output.absolutePath)
            assertEquals("mp3", probe.trim())
            assertTrue(output.length() > 1000)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.absolutePath)
                assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 900..1200)
            } finally { retriever.release() }
        } finally { folder.deleteRecursively() }
    }
}
