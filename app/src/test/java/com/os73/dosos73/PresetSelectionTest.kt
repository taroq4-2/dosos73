package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class PresetSelectionTest {
    private val video = Choice("source-720", Kind.VIDEO, "webm", "720p", expectedHeight = 720)

    @Test fun exactHeightAndContainerAreRequiredWithoutFallback() {
        val preset = DownloadPresets.fromChoice("Web video", video, null)
        assertNull(preset.match(listOf(video.copy(extension = "mp4"), video.copy(expectedHeight = 1080))))
        val otherSource = video.copy(id = "other-source", estimatedBytes = 9000)
        assertEquals(otherSource, preset.match(listOf(otherSource)))
    }

    @Test fun noHeightOriginalAndFilesRequireTheirExactQualityAndKind() {
        val source = Choice("original", Kind.VIDEO, "mp4", "Original")
        val preset = DownloadPresets.fromChoice("Original", source, null)
        assertNull(preset.match(listOf(source.copy(quality = "720p"), source.copy(kind = Kind.FILE))))
        assertEquals(source, preset.match(listOf(source)))
        val file = Choice("direct", Kind.FILE, "zip", "Original")
        assertNull(DownloadPresets.fromChoice("Archive", file, null).match(listOf(file.copy(extension = "bin"))))
    }

    @Test fun mp3IsGenericAcrossSourcesBitratesAndLanguages() {
        val original = Choice("a-source", Kind.AUDIO, "mp3", "MP3 V0 · source 128 kbps · ar", language = "ar")
        val preset = DownloadPresets.fromChoice("Audio", original, null)
        assertEquals("MP3 V0", preset.quality)
        val other = original.copy(id = "b-source", quality = "MP3 V0 · source 256 kbps · en", language = "en", bitrateKbps = 256)
        assertEquals(other, preset.match(listOf(other)))
        assertNull(preset.match(listOf(other.copy(extension = "m4a"))))
    }

    @Test fun unsafeAndUnboundedFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { DownloadPresets.fromChoice("x".repeat(65), video, null) }
        assertThrows(IllegalArgumentException::class.java) { DownloadPresets.fromChoice("bad\nname", video, null) }
        assertThrows(IllegalArgumentException::class.java) { DownloadPresets.fromChoice("Name", video, "file:///private") }
        assertThrows(IllegalArgumentException::class.java) { DownloadPresets.fromChoice("Name", video, "content://" + "x".repeat(1024)) }
        assertThrows(IllegalArgumentException::class.java) { DownloadPresets.fromChoice("Name", video.copy(extension = "../mp4"), null) }
    }
}
