package com.os73.dosos73

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickLinkTest {
    @Test fun copiedProsePreservesSignedQueryAndBalancedPath() {
        assertEquals("https://example.com/a(b)?Sig=AbC%2F123",
            QuickLink.parse("Watch (HTTPS://example.com/a(b)?Sig=AbC%2F123)"))
    }

    @Test fun ambiguousOrNonWebClipboardCannotBecomeQuickDownload() {
        for (text in listOf("", "ordinary text", "content://media/images/1", "file:///tmp/a",
            "intent://example.com", "https://example.com/a https://example.com/b",
            "https://user:password@example.com/a", "https://localhost/a")) {
            assertThrows("Reject copied input: $text", IllegalArgumentException::class.java) {
                QuickLink.parse(text)
            }
        }
    }

    @Test fun maximumLengthIsAcceptedButOverflowIsRejectedWithoutTruncation() {
        val prefix = "https://example.com/"
        val boundary = prefix + "a".repeat(8192 - prefix.length)
        assertEquals(boundary, QuickLink.parse(boundary))
        assertThrows(IllegalArgumentException::class.java) { QuickLink.parse(boundary + "a") }
    }
}
