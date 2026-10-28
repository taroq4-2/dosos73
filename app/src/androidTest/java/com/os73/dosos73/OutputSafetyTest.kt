package com.os73.dosos73

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutputSafetyTest {
    @Test
    fun audioChoicesPreserveBitrateLanguageAndKnownSize() {
        val json = org.json.JSONObject("""{"formats":[
          {"format_id":"a1","ext":"m4a","abr":96,"filesize":1234,"language":"en","vcodec":"none","acodec":"aac","protocol":"https","url":"https://example.com/a1"},
          {"format_id":"a2","ext":"m4a","abr":160,"filesize":2345,"language":"ar","vcodec":"none","acodec":"aac","protocol":"https","url":"https://example.com/a2"}
        ]}""")
        val choices = MediaEngine.mapFormats(json)
        assertEquals(2, choices.size)
        assertEquals(160, choices.first().bitrateKbps)
        assertEquals("ar", choices.first().language)
        assertEquals(2345L, choices.first().estimatedBytes)
        assertTrue(choices.map { it.quality }.distinct().size == 2)
    }

    @Test
    fun recoversPublicationWithoutCreatingSecondOutput() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "receipt-test.bin")
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val task = DownloadTask(Analysis("https://example.com/file", "receipt-test", listOf(choice), true), choice, null)
        val store = OutputStore(context)
        var uri: android.net.Uri? = null
        try {
            source.writeText("durable publication")
            uri = store.save(source, task).first
            val recovered = store.recover(task)
            assertEquals(uri, recovered!!.first)
            store.forget(task.id)
            assertNull(store.recover(task))
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            store.forget(task.id)
            source.delete()
        }
    }

    @Test
    fun bestAudioDoesNotDependOnSourceOrder() {
        for (order in listOf(listOf(160, 96), listOf(96, 160))) {
            val formats =
                org.json
                    .JSONArray()
                    .put(
                        org.json.JSONObject(
                            """{"format_id":"v","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none","protocol":"https","url":"https://example.com/v"}"""
                        )
                    )
            order.forEach { bitrate ->
                formats.put(
                    org.json.JSONObject(
                        """{"format_id":"a$bitrate","ext":"m4a","abr":$bitrate,"vcodec":"none","acodec":"aac","protocol":"https","url":"https://example.com/a"}"""
                    )
                )
            }
            val choice =
                MediaEngine.mapFormats(org.json.JSONObject().put("formats", formats)).first {
                    it.kind == Kind.VIDEO
                }
            assertEquals("v+a160", choice.id)
            assertEquals(
                "a160",
                MediaEngine.mapFormats(org.json.JSONObject().put("formats", formats))
                    .first { it.kind == Kind.AUDIO }
                    .id,
            )
        }
    }

    @Test
    fun publishesExactBytesToMediaStore() =
        kotlinx.coroutines.runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "publication-test.bin")
            var uri: android.net.Uri? = null
            try {
                file.writeText("verified publication fixture")
                val choice = Choice("direct", Kind.FILE, "bin", "Original")
                val analysis =
                    Analysis("https://example.com/file", "publication-test", listOf(choice), true)
                val store = OutputStore(context)
                store.validate(file, Kind.FILE)
                uri = store.save(file, DownloadTask(analysis, choice, null)).first
                assertEquals(
                    file.readText(),
                    context.contentResolver.openInputStream(uri!!)!!.bufferedReader().use {
                        it.readText()
                    },
                )
            } finally {
                uri?.let { context.contentResolver.delete(it, null, null) }
                file.delete()
            }
        }

    @Test
    fun doubleTapHasOneOwner() {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val task =
            DownloadTask(
                Analysis("https://example.com/file", "test", listOf(choice), true),
                choice,
                null,
            )
        Transfers.update(TransferState())
        try {
            assertTrue(Transfers.reserve(task))
            assertFalse(Transfers.reserve(task))
            assertEquals(task, Transfers.take())
            assertNull(Transfers.take())
        } finally {
            Transfers.update(TransferState())
        }
    }

    @Test
    fun videoOnlyFormatRequiresCompatibleAudio() {
        val json =
            org.json.JSONObject(
                """{"formats":[
          {"format_id":"137","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none","protocol":"https","url":"https://example.com/v"},
          {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"aac","protocol":"https","url":"https://example.com/a"}
        ]}"""
            )
        val video = MediaEngine.mapFormats(json).first { it.kind == Kind.VIDEO }
        assertEquals("137+140", video.id)
        assertEquals(1080, video.expectedHeight)
        assertTrue(video.expectsAudio)
        json.getJSONArray("formats").remove(1)
        assertTrue(MediaEngine.mapFormats(json).isEmpty())
    }

    @Test
    fun validatesSparseFileAboveFormerSizeCap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "large-sparse-test.bin")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(2147483649L) }
            OutputStore(context).validate(file, Kind.FILE)
            assertEquals(2147483649L, file.length())
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsEmptyAndFakeMedia() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "invalid-test.mp4")
        try {
            file.writeBytes(byteArrayOf())
            assertTrue(runCatching { OutputStore(context).validate(file, Kind.FILE) }.isFailure)
            file.writeText("<html>not a video</html>")
            assertTrue(runCatching { OutputStore(context).validate(file, Kind.VIDEO) }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun onlyAdvertisesRealMappedFormats() {
        val json =
            org.json.JSONObject(
                """{"formats":[
            {"format_id":"18","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac","protocol":"https","url":"https://example.com/v"},
            {"format_id":"bad+selector","ext":"mp4","height":4320,"vcodec":"avc1","acodec":"aac","protocol":"https","url":"https://example.com/bad"}
        ]}"""
            )
        val choices = MediaEngine.mapFormats(json)
        assertEquals(1, choices.size)
        assertEquals("360p", choices.single().quality)
    }

    @Test
    fun genericOriginalFormatDoesNotInventResolution() {
        val json =
            org.json.JSONObject(
                """{"format_id":"mp4","ext":"mp4","protocol":"https","url":"https://example.com/video.mp4"}"""
            )
        val choice = MediaEngine.mapFormats(json).single()
        assertEquals("Original", choice.quality)
        assertEquals(Kind.VIDEO, choice.kind)
    }
}
