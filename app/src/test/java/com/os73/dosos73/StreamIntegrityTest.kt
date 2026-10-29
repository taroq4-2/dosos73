package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class StreamIntegrityTest {
    @Test fun rejectsSameLengthCorruptionAndTruncation() {
        val expected = StreamIntegrity.fingerprint("trusted bytes".byteInputStream())
        StreamIntegrity.requireSame(expected, StreamIntegrity.fingerprint("trusted bytes".byteInputStream()))
        for (bad in listOf("changed bytes", "trusted")) {
            assertThrows(java.io.IOException::class.java) {
                StreamIntegrity.requireSame(expected, StreamIntegrity.fingerprint(bad.byteInputStream()))
            }
        }
    }
}
