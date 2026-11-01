package com.os73.dosos73

object StoragePolicy {
    // A reserve for bookkeeping, not a media file-size limit. Unknown lengths are
    // checked again by filesystem writes; known lengths are compared without overflow.
    fun requireAvailable(available: Long, bytes: Long?, copies: Int = 1, retainedBytes: Long = 0) {
        val reserve = 32L * 1024 * 1024
        val usable = (available - reserve).coerceAtLeast(0)
        if (available < reserve) throw PipelineException(Stage.PUBLISH, FailureCode.STORAGE_FULL)
        if (bytes != null) {
            val firstCopy = (bytes - retainedBytes.coerceIn(0, bytes.coerceAtLeast(0))).coerceAtLeast(0)
            val remainingCopies = copies.coerceAtLeast(1) - 1
            if (firstCopy > usable || (remainingCopies > 0 && bytes > (usable - firstCopy) / remainingCopies))
                throw PipelineException(Stage.PUBLISH, FailureCode.STORAGE_FULL)
        }
    }
}
