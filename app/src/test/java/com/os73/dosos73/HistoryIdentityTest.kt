package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class HistoryIdentityTest {
    private fun task(url: String, choice: Choice = Choice("137+140", Kind.VIDEO, "mp4", "1080p", 1080)) =
        DownloadTask(Analysis(url, "Same filename", listOf(choice), false), choice, null)

    @Test fun validatedYoutubeWatchShortAndShortsLinksShareIdentity() {
        val expected = DownloadHistory.identity(task("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=abc"))
        assertNotNull(expected)
        for (url in listOf("https://youtu.be/dQw4w9WgXcQ?t=30", "https://m.youtube.com/shorts/dQw4w9WgXcQ", "https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
            assertEquals(url, expected, DownloadHistory.identity(task(url)))
    }

    @Test fun signedQueriesAndUnrecognizedYoutubeFormsRemainExact() {
        val pairs = listOf(
            "https://example.org/video?token=first" to "https://example.org/video?token=second",
            "https://example.org/a?x=1&y=2" to "https://example.org/a?y=2&x=1",
            "https://youtu.be/short" to "https://youtube.com/watch?v=short",
            "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ" to "https://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&v=abcdefghijk" to "https://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/embed/dQw4w9WgXcQ" to "https://youtu.be/dQw4w9WgXcQ",
        )
        pairs.forEach { (first, second) -> assertNotEquals(first, DownloadHistory.identity(task(first)), DownloadHistory.identity(task(second))) }
    }

    @Test fun everyQualityVariantHasItsOwnIdentity() {
        val original = task("https://example.org/video")
        val choice = original.choice
        val expected = DownloadHistory.identity(original)
        for (variant in listOf(choice.copy(kind = Kind.AUDIO), choice.copy(extension = "webm"),
            choice.copy(expectedHeight = 720), choice.copy(id = "136+140"), choice.copy(language = "ar"),
            choice.copy(bitrateKbps = 128), choice.copy(expectsAudio = true)))
            assertNotEquals(expected, DownloadHistory.identity(original.copy(choice = variant)))
        assertEquals(expected, DownloadHistory.identity(original.copy(analysis = original.analysis.copy(title = "Renamed"))))
        assertEquals(expected, DownloadHistory.identity(original.copy(choice = choice.copy(extension = "MP4"))))
        assertNull(DownloadHistory.identity(task("file:///private/video.mp4")))
    }
}

