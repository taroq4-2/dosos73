package com.os73.dosos73

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real Android URI/JSON implementations used by encrypted queue journals. */
class LocalTaskJournalTest {
    private fun localTask(): DownloadTask {
        val source = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMovies%2Fsample.mp4")
        val choice = OfflineAudio.CHOICE
        return DownloadTask(
            Analysis(source.toString(), "Selected video", listOf(choice), false, 12345),
            choice,
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"),
            id = "43780243-e0c5-4b57-9fc2-99db2c0d3a7d",
            priority = TaskPriority.LOW,
            userPaused = true,
            notBefore = 123456789,
            localSource = source,
        )
    }

    private fun encodedLocal() = DownloadQueue.encodeTask(localTask())

    @Test fun contentSourceAndAllQueueFieldsSurviveSerialization() {
        val original = localTask()
        // Serialize to text and parse again: no in-memory JSONObject or Uri identity shortcuts.
        val encoded = JSONObject(DownloadQueue.encodeTask(original).toString())
        assertEquals(original.localSource.toString(), encoded.getString("localSource"))
        assertEquals(original, DownloadQueue.decodeTask(encoded))
    }

    @Test fun rejectsFileAndNetworkLocalSourcesEvenWhenAnalysisUrlMatches() {
        for (source in listOf("file:///sdcard/Movies/sample.mp4", "http://example.org/sample.mp4",
            "https://example.org/sample.mp4", "content:/missing-authority")) {
            val encoded = encodedLocal().put("localSource", source).put("url", source)
            assertTrue(source, runCatching { DownloadQueue.decodeTask(encoded) }.isFailure)
        }
    }

    @Test fun rejectsMismatchedAnalysisUrlChoiceAndDirectMode() {
        assertTrue(runCatching {
            DownloadQueue.decodeTask(encodedLocal().put("url", "https://example.org/sample.mp4"))
        }.isFailure)
        assertTrue(runCatching {
            DownloadQueue.decodeTask(encodedLocal().put("url", "content://different.provider/video"))
        }.isFailure)
        for (wrongChoice in listOf(
            OfflineAudio.CHOICE.copy(id = "other-format"),
            OfflineAudio.CHOICE.copy(extension = "aac"),
            OfflineAudio.CHOICE.copy(kind = Kind.VIDEO),
            OfflineAudio.CHOICE.copy(bitrateKbps = 64),
        )) {
            val encoded = encodedLocal().put("choice", DownloadQueue.encodeChoice(wrongChoice))
            assertTrue(wrongChoice.toString(), runCatching { DownloadQueue.decodeTask(encoded) }.isFailure)
        }
        assertTrue(runCatching { DownloadQueue.decodeTask(encodedLocal().put("direct", true)) }.isFailure)
    }

    @Test fun oldHttpTaskWithoutLocalSourceRetainsItsOriginalMeaning() {
        val choice = Choice("direct", Kind.FILE, "zip", "Original")
        val original = DownloadTask(
            Analysis("https://example.org/archive.zip?signature=original", "Archive", listOf(choice), true, 4096),
            choice,
            null,
            id = "43780243-e0c5-4b57-9fc2-99db2c0d3a7d",
        )
        val legacy = DownloadQueue.encodeTask(original).apply {
            remove("localSource")
            remove("priority")
            remove("paused")
            remove("notBefore")
        }
        assertFalse(legacy.has("localSource"))
        val decoded = DownloadQueue.decodeTask(JSONObject(legacy.toString()))
        assertEquals(original, decoded)
        assertNull(decoded.localSource)
    }
}
