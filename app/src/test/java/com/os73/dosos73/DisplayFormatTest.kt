package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class DisplayFormatTest {
    @Test fun binaryUnitsPreserveMeaningWithoutHugeKilobyteCounts() {
        assertEquals("2 GiB", bytesLabel(2147483648L, Locale.US))
        assertEquals("1.5 KiB", bytesLabel(1536, Locale.US))
        assertEquals("0 B", bytesLabel(0, Locale.US))
        assertEquals("8 EiB", bytesLabel(Long.MAX_VALUE, Locale.US))
    }
    @Test fun respectsSelectedLocaleForDigitsAndDecimalSeparator() {
        assertEquals("1,5 KiB", bytesLabel(1536, Locale.GERMANY))
        val arabic = Locale.forLanguageTag("ar-EG")
        assertEquals("١٫٥ KiB", bytesLabel(1536, arabic))
        assertTrue(percentLabel(0.62f, arabic).contains("٦٢"))
        assertEquals("62%", percentLabel(0.62f, Locale.US))
    }
}
