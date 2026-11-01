package com.os73.dosos73

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real platform-generated video + bundled FFmpeg/LAME, with no server or network-policy mutation. */
class OfflineAudioTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Sample(val bytes: ByteArray, val info: MediaCodec.BufferInfo)
    private data class Track(val format: MediaFormat, val samples: List<Sample>)

    private fun encode(video: Boolean): Track {
        val mime = if (video) "video/avc" else "audio/mp4a-latm"
        val format = if (video) MediaFormat.createVideoFormat(mime, 64, 64).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 10)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        } else MediaFormat.createAudioFormat(mime, 44100, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96000)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        val samples = mutableListOf<Sample>()
        var outputFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val pcm = ByteBuffer.allocate(44100 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(44100) { i -> putShort((10000 * kotlin.math.sin(2 * Math.PI * 440 * i / 44100)).toInt().toShort()) }
            }.array()
            var frame = 0
            var offset = 0
            var ended = false
            val deadline = System.nanoTime() + 30_000_000_000L
            val info = MediaCodec.BufferInfo()
            while (true) {
                check(System.nanoTime() < deadline) { "Fixture encoder timed out" }
                if (!ended) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index)).apply { clear() }
                        if ((video && frame == 10) || (!video && offset == pcm.size)) {
                            codec.queueInputBuffer(index, 0, 0, 1_000_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            ended = true
                        } else if (video) {
                            buffer.put(ByteArray(4096) { 16 }).put(ByteArray(2048) { 128.toByte() })
                            codec.queueInputBuffer(index, 0, 6144, frame++ * 100_000L, 0)
                        } else {
                            val size = minOf(buffer.remaining(), pcm.size - offset).let { it - it % 2 }
                            buffer.put(pcm, offset, size)
                            codec.queueInputBuffer(index, 0, size, offset / 2 * 1_000_000L / 44100, 0)
                            offset += size
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    else -> if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val bytes = ByteArray(info.size)
                            checkNotNull(codec.getOutputBuffer(index)).apply { position(info.offset); limit(info.offset + info.size); get(bytes) }
                            samples += Sample(bytes, MediaCodec.BufferInfo().apply { set(0, bytes.size, info.presentationTimeUs, info.flags) })
                        }
                        val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (done) break
                    }
                }
            }
            return Track(checkNotNull(outputFormat), samples)
        } finally { runCatching { codec.stop() }; codec.release() }
    }

    private fun fixture(folder: File, audio: Boolean = true): File {
        val video = encode(true)
        val sound = if (audio) encode(false) else null
        val file = File(folder, "source.mp4")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoIndex = muxer.addTrack(video.format)
            val audioIndex = sound?.let { muxer.addTrack(it.format) }
            muxer.start()
            video.samples.forEach { muxer.writeSampleData(videoIndex, ByteBuffer.wrap(it.bytes), it.info) }
            sound?.samples?.forEach { muxer.writeSampleData(checkNotNull(audioIndex), ByteBuffer.wrap(it.bytes), it.info) }
            muxer.stop()
        } finally { muxer.release() }
        return file
    }

    private fun publishInput(file: File): Uri {
        val id = "offline-${UUID.randomUUID()}"
        RecoveryTestDocumentsProvider.entries[id] = RecoveryTestDocumentsProvider.Entry(file.name, file)
        return DocumentsContract.buildDocumentUri("com.os73.dosos73.recovery-tests", id)
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    @Test fun convertsRealVideoToPlayableMp3AndKeepsOriginalBytes() = runBlocking {
        val folder = File(context.cacheDir, "offline-audio-test-${UUID.randomUUID()}").apply { mkdirs() }
        RecoveryTestDocumentsProvider.reset()
        try {
            val input = fixture(folder)
            val original = hash(input)
            val uri = publishInput(input)
            val offlineContext = object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any? {
                    check(name != Context.CONNECTIVITY_SERVICE) { "Offline conversion requested network access" }
                    return super.getSystemService(name)
                }
            }
            val converter = OfflineAudio(offlineContext)
            val metadata = converter.inspect(uri)
            assertEquals("source.mp4", metadata.name)
            assertEquals(input.length(), metadata.bytes)
            assertTrue(metadata.durationMs >= 800)
            var processing = false
            val result = converter.convert(uri, File(folder, "conversion")) { _, value -> processing = processing || value }
            assertTrue(processing)
            assertEquals(original, hash(input))
            assertEquals(0, RecoveryTestDocumentsProvider.deletes)
            assertEquals(0, RecoveryTestDocumentsProvider.renames)
            assertTrue(result.length() > 1000)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(result.absolutePath)
                assertEquals("audio/mpeg", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
                assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertTrue(checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() >= 800)
            } finally { retriever.release() }
            assertFalse(File(result.parentFile, "offline-input").exists())
            // An unknown-length SAF pipe cannot be probed by seeking, but still converts offline.
            RecoveryTestDocumentsProvider.unknownSize = true
            val streamedMetadata = converter.inspect(uri)
            assertNull(streamedMetadata.bytes)
            assertEquals(0L, streamedMetadata.durationMs)
            val streamed = converter.convert(uri, File(folder, "streamed-conversion")) { _, _ -> }
            assertTrue(streamed.length() > 1000)
            assertEquals(original, hash(input))
        } finally { RecoveryTestDocumentsProvider.reset(); folder.deleteRecursively() }
    }

    @Test fun rejectsEmptyUnsupportedAndSilentVideoWithoutChangingInputs() = runBlocking {
        val folder = File(context.cacheDir, "offline-invalid-${UUID.randomUUID()}").apply { mkdirs() }
        RecoveryTestDocumentsProvider.reset()
        try {
            val inputs = listOf(
                File(folder, "empty.mp4").apply { writeBytes(byteArrayOf()) } to R.string.offline_error_empty,
                File(folder, "wrong.mp4").apply { writeText("not a video") } to R.string.offline_error_video,
                fixture(folder, audio = false) to R.string.offline_error_no_audio,
            )
            for ((file, expectedError) in inputs) {
                val original = hash(file)
                val uri = publishInput(file)
                val failure = try {
                    OfflineAudio(context).convert(uri, File(folder, "convert-${UUID.randomUUID()}")) { _, _ -> }
                    error("Invalid input was accepted")
                } catch (error: OfflineAudioException) { error }
                assertEquals(expectedError, failure.messageResource)
                assertEquals(original, hash(file))
            }
        } finally { RecoveryTestDocumentsProvider.reset(); folder.deleteRecursively() }
    }

    @Test fun cancellingDuringCopyKeepsOriginalAndRemovesPartialFiles() = runBlocking {
        val folder = File(context.cacheDir, "offline-cancel-${UUID.randomUUID()}").apply { mkdirs() }
        RecoveryTestDocumentsProvider.reset()
        try {
            // Cancellation is deterministic in copy progress and does not require native startup.
            val file = File(folder, "source.mp4").apply { writeBytes(ByteArray(256 * 1024) { 42 }) }
            val original = hash(file)
            val converter = OfflineAudio(context)
            val outputFolder = File(folder, "conversion")
            val uri = publishInput(file)
            var cancelled = false
            try {
                converter.convert(uri, outputFolder) { percent, processing ->
                    if (!processing && percent != null && percent > 0) converter.cancel()
                }
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(original, hash(file))
            assertFalse(File(outputFolder, "offline-input").exists())
            assertFalse(File(outputFolder, "offline-audio.mp3").exists())
            val coroutineFolder = File(folder, "coroutine-conversion")
            lateinit var job: Job
            job = launch(start = CoroutineStart.LAZY) {
                converter.convert(uri, coroutineFolder) { percent, processing ->
                    if (!processing && percent != null && percent > 0) job.cancel()
                }
            }
            job.start()
            withTimeout(5000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(original, hash(file))
            assertFalse(File(coroutineFolder, "offline-input").exists())
            assertFalse(File(coroutineFolder, "offline-audio.mp3").exists())
        } finally { RecoveryTestDocumentsProvider.reset(); folder.deleteRecursively() }
    }
}
