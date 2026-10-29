package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class BatchInputTest {
    @Test
    fun boundsAndDeduplicatesWithoutDroppingInvalidRows() {
        assertEquals(
            listOf("https://example.com/a", "invalid", "https://example.com/b"),
            BatchInput.parse(
                "https://example.com/a\ninvalid\nhttps://example.com/a https://example.com/b"
            ),
        )
        assertEquals(
            20,
            BatchInput.parse((1..50).joinToString("\n") { "https://example.com/$it" }).size,
        )
        assertTrue(BatchInput.parse("  \n ").isEmpty())
    }

    @Test
    fun reviewLabelsNeverExposeSignedQueryOrPath() {
        assertEquals(
            "example.com",
            safeSourceLabel("https://example.com/private-secret?token=secret#private"),
        )
        assertEquals("—", safeSourceLabel(null))
    }
}
