package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class FormatSelectorTest {
    @Test fun reservedWordsAreLiteralFormatIdsForEveryCodecKind() {
        // Verified with the bundled yt-dlp, offline: "all" audio and "best" video
        // each select only that ID; their pair returns requested_formats=[best, all].
        assertEquals("all[format_id=all]", MediaEngine.formatSelector("all"))
        assertEquals("all[format_id=best]", MediaEngine.formatSelector("best"))
        assertEquals("all[format_id=worst]", MediaEngine.formatSelector("worst"))
        assertEquals(
            "all[format_id=best]+all[format_id=all]",
            MediaEngine.formatSelector("best+all"),
        )
        assertEquals("all[format_id=-._42]", MediaEngine.formatSelector("-._42"))
    }

    @Test fun selectorRejectsExpressionsAndMalformedPairs() {
        for (id in listOf("", "best[height=720]", "a/b", "a,b", "a+b+c", "+a", "a+", "a++b", "a b", "a\nb", "a".repeat(81))) {
            assertThrows(id, IllegalArgumentException::class.java) { MediaEngine.formatSelector(id) }
        }
        val longest = "a".repeat(80)
        assertEquals("all[format_id=$longest]", MediaEngine.formatSelector(longest))
    }

    @Test fun convertedAudioNeverAdvertisesSourceSizeAsOutputSize() {
        // Both codec-omitted metadata and explicit audio metadata use this mapping.
        for (extension in listOf("m4a", "opus", "webm")) {
            val choice = MediaEngine.audioChoice("all", extension, 128.0, 123456L, "ar")
            assertEquals(Kind.AUDIO, choice.kind)
            assertEquals("mp3", choice.extension)
            assertEquals("MP3 V0 · source 128 kbps · ar", choice.quality)
            assertNull(choice.estimatedBytes)
            assertNull(choice.bitrateKbps)
            assertEquals("ar", choice.language)
        }
    }

    @Test fun originalMp3RetainsKnownOutputMetadataWithoutClaimingV0() {
        val choice = MediaEngine.audioChoice("original", "mp3", 192.0, 123456L, "en")
        assertEquals("Original · 192 kbps · en", choice.quality)
        assertEquals(123456L, choice.estimatedBytes)
        assertEquals(192, choice.bitrateKbps)
        assertFalse(choice.quality.contains("V0"))
    }

    @Test fun omittedBitrateStaysUnknownForOriginalAndConvertedAudio() {
        assertEquals("MP3 V0", MediaEngine.audioChoice("a", "m4a", 0.0, 42L, null).quality)
        val original = MediaEngine.audioChoice("b", "mp3", Double.NaN, null, null)
        assertEquals("Original", original.quality)
        assertNull(original.estimatedBytes)
        assertNull(original.bitrateKbps)
    }
}
