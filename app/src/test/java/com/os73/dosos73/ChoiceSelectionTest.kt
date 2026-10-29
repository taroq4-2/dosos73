package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class ChoiceSelectionTest {
    @Test fun qualityMenuPreservesWebmEvenWhenMp4IsFirst() {
        val mp4 = Choice("mp4", Kind.VIDEO, "mp4", "720p")
        val webm = Choice("webm", Kind.VIDEO, "webm", "720p")
        val selected = Choice("high", Kind.VIDEO, "webm", "1080p")
        assertEquals(listOf(webm, selected), qualityChoices(listOf(mp4, webm, selected), selected))
    }
    @Test fun audioMenuDoesNotSwitchLanguageOrInventUnavailableQuality() {
        val ar = Choice("ar", Kind.AUDIO, "m4a", "128", language = "ar")
        val en = ar.copy(id = "en", quality = "256", language = "en")
        assertEquals(listOf(ar), qualityChoices(listOf(ar, en), ar))
        assertTrue(qualityChoices(listOf(ar), null).isEmpty())
    }
}
