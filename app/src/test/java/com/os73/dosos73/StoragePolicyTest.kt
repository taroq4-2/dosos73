package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class StoragePolicyTest {
    @Test
    fun permitsLargeFilesWhenSpaceExists() {
        StoragePolicy.requireAvailable(20L shl 30, 5L shl 30, 2)
    }

    @Test
    fun knownAndUnknownLengthsRespectAvailableSpace() {
        assertEquals(
            FailureCode.STORAGE_FULL,
            (runCatching { StoragePolicy.requireAvailable(100, null) }.exceptionOrNull()
                    as PipelineException)
                .code,
        )
        assertTrue(
            runCatching { StoragePolicy.requireAvailable(4L shl 30, 3L shl 30, 2) }.isFailure
        )
        assertTrue(
            runCatching { StoragePolicy.requireAvailable(Long.MAX_VALUE, Long.MAX_VALUE, 2) }
                .isFailure
        )
    }
}
