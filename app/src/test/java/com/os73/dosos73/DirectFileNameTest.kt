package com.os73.dosos73

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectFileNameTest {
    @Test
    fun mimeSuppliesMissingExtension() {
        assertEquals(
            "download" to "pdf",
            DirectFileName.resolve(null, "download", "application/pdf"),
        )
        assertEquals("track" to "mp3", DirectFileName.resolve(null, "track", "audio/mpeg"))
    }

    @Test
    fun serverNameAndUtf8AreSupportedWithoutPaths() {
        assertEquals(
            "report" to "pdf",
            DirectFileName.resolve(
                "attachment; filename=\"../../report.pdf\"",
                "get",
                "application/pdf",
            ),
        )
        assertEquals(
            "hello+world" to "mp3",
            DirectFileName.resolve(
                "attachment; filename*=UTF-8''hello%2Bworld.mp3",
                "get",
                "audio/mpeg",
            ),
        )
    }

    @Test
    fun malformedEncodedNameFallsBack() {
        assertEquals(
            "safe" to "zip",
            DirectFileName.resolve(
                "attachment; filename*=UTF-8''%XX; filename=\"safe.zip\"",
                "get",
                "application/zip",
            ),
        )
    }
}
