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
    fun androidPackagesKeepTheirExtension() {
        assertEquals("app" to "apk", DirectFileName.resolve(null, "app", "application/vnd.android.package-archive"))
        assertEquals("app" to "apk", DirectFileName.resolve(null, "app.apk", "application/octet-stream"))
        // A ZIP-based package served as plain ZIP keeps the precise extension.
        assertEquals("game" to "xapk", DirectFileName.resolve(null, "game.xapk", "application/zip"))
        assertEquals("archive" to "zip", DirectFileName.resolve(null, "archive.zip", "application/zip"))
    }

    @Test
    fun latin1EncodedNameAndBidiSpoofingAreHandled() {
        assertEquals("café" to "pdf",
            DirectFileName.resolve("attachment; filename*=ISO-8859-1''caf%E9.pdf", "get", "application/pdf"))
        // U+202E would render "invoice‮gpj.exe" as "invoiceexe.jpg".
        val (title, extension) = DirectFileName.resolve(
            "attachment; filename=\"invoice‮gpj.exe\"", "get", "application/octet-stream")
        assertEquals("invoicegpj" to "exe", title to extension)
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
