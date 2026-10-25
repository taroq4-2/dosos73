package com.os73.dosos73

object StoragePolicy {
    // A reserve for bookkeeping, not a media file-size limit. Unknown lengths are
    // checked again by filesystem writes; known lengths are compared without overflow.
    fun requireAvailable(available: Long, bytes: Long?, copies: Int = 1) {
        val reserve = 32L * 1024 * 1024
        val usable = (available - reserve).coerceAtLeast(0)
        if (available < reserve || (bytes != null && bytes > usable / copies.coerceAtLeast(1)))
            throw PipelineException(Stage.PUBLISH, FailureCode.STORAGE_FULL)
    }
}
