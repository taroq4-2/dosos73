package com.os73.dosos73
import org.junit.Assert.*
import org.junit.Test
class ResumeSpaceTest {
    @Test fun retainedBytesReduceOnlyTheIncomingCopyAndKeepPublicationReserve() {
        val gib=1024L*1024*1024
        StoragePolicy.requireAvailable(1400L*1024*1024, gib, copies=2, retainedBytes=768L*1024*1024)
        assertThrows(PipelineException::class.java) { StoragePolicy.requireAvailable(1200L*1024*1024,gib,2,768L*1024*1024) }
        assertThrows(PipelineException::class.java) { StoragePolicy.requireAvailable(1400L*1024*1024,gib,2,0) }
    }
    @Test fun creditsAreBoundedAndDoNotSkipTheRemainingPublicationCopy() {
        val reserve=32L*1024*1024
        assertThrows(PipelineException::class.java) { StoragePolicy.requireAvailable(reserve+99,100,2,Long.MAX_VALUE) }
        StoragePolicy.requireAvailable(reserve+100,100,2,Long.MAX_VALUE)
        assertThrows(PipelineException::class.java) { StoragePolicy.requireAvailable(Long.MAX_VALUE,Long.MAX_VALUE,2,0) }
    }
}
