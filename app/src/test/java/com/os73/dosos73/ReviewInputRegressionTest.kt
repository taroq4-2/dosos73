package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class ReviewInputRegressionTest {
    @Test fun uppercaseSchemeIsCanonicalWithoutChangingSignedQuery() {
        assertEquals("https://example.com/V?Sig=AbC", Safety.url("HTTPS://example.com/V?Sig=AbC"))
    }
    @Test fun sharedProseDelimitersDoNotBecomeDownloadTokens() {
        assertEquals("https://example.com/a?token=AbC", Safety.shared("(https://example.com/a?token=AbC)"))
        assertEquals("https://example.com/a(b)", Safety.shared("https://example.com/a(b)"))
        assertEquals("https://example.com/a", Safety.shared("https://example.com/a،"))
    }
    @Test fun maximumSharedProseRemovesExcessDelimitersAndRetainsBalancedUrl() {
        val url = "https://example.com/a(b)?token=AbC"
        val text = url + ")".repeat(32768 - url.length)
        assertEquals(url, Safety.shared(text))
        assertThrows(IllegalArgumentException::class.java) { Safety.shared(text + ")") }
    }
    @Test fun terminalFormatErrorIsNotMaskedByAnEarlierRecoveredTlsWarning() {
        val error = java.io.IOException("WARNING: [SSL] record layer failure\nERROR: Requested format is not available")
        assertEquals(FailureCode.FORMAT_UNAVAILABLE, FailureClassifier.classify(Stage.TRANSFER, error).code)
    }
    @Test fun callDeadlineIsTimeoutButOrdinaryInterruptionIsNot() {
        assertEquals(FailureCode.NETWORK_TIMEOUT, FailureClassifier.classify(Stage.TRANSFER,
            java.io.InterruptedIOException("timeout")).code)
        assertEquals(FailureCode.DOWNLOAD_FAILED, FailureClassifier.classify(Stage.TRANSFER,
            java.io.InterruptedIOException("Cancelled")).code)
    }
}
