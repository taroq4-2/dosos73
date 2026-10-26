package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class FailureClassifierTest {
    @Test
    fun classifiesDiskAndTlsWithoutHidingAuthentication() {
        assertEquals(
            FailureCode.STORAGE_FULL,
            FailureClassifier.classify(
                    Stage.TRANSFER,
                    java.io.IOException("write failed: ENOSPC (No space left on device)"),
                )
                .code,
        )
        assertEquals(
            FailureCode.NETWORK_TLS,
            FailureClassifier.classify(
                    Stage.TRANSFER,
                    Exception("[SSL] record layer failure (_ssl.c:2580)"),
                )
                .code,
        )
        assertEquals(
            FailureCode.AUTHENTICATION_REQUIRED,
            FailureClassifier.classify(
                    Stage.EXTRACT,
                    Exception(
                        "WARNING: [SSL] record layer failure\nERROR: Sign in to confirm you’re not a bot"
                    ),
                )
                .code,
        )
    }

    @Test
    fun transfer403IsNotStorageFailure() {
        val failure =
            FailureClassifier.classify(
                Stage.TRANSFER,
                Exception("unable to download video data: HTTP Error 403: Forbidden"),
            )
        assertEquals(Stage.TRANSFER, failure.stage)
        assertEquals(FailureCode.HTTP_403, failure.code)
        assertFalse(failure.message.orEmpty().contains("Forbidden"))
    }

    @Test
    fun userTitleContaining403DoesNotInventHttpStatus() {
        assertEquals(
            FailureCode.DOWNLOAD_FAILED,
            FailureClassifier.classify(Stage.TRANSFER, Exception("video 403 missing")).code,
        )
    }

    @Test
    fun preservesStageAndClassifiesActualMessages() {
        assertEquals(
            FailureCode.AUTHENTICATION_REQUIRED,
            FailureClassifier.classify(
                    Stage.EXTRACT,
                    Exception("Sign in to confirm you’re not a bot"),
                )
                .code,
        )
        assertEquals(
            FailureCode.HTTP_429,
            FailureClassifier.classify(
                    Stage.TRANSFER,
                    Exception("HTTP Error 429: Too Many Requests"),
                )
                .code,
        )
        assertEquals(
            FailureCode.FORMAT_UNAVAILABLE,
            FailureClassifier.classify(
                    Stage.TRANSFER,
                    Exception("Requested format is not available"),
                )
                .code,
        )
        assertEquals(
            FailureCode.MERGE_FAILED,
            FailureClassifier.classify(Stage.MERGE, Exception("exit 1")).code,
        )
        assertEquals(
            FailureCode.OUTPUT_WRITE_FAILED,
            FailureClassifier.classify(Stage.PUBLISH, java.io.IOException("disk full")).code,
        )
        assertEquals(
            FailureCode.OUTPUT_VERIFICATION_FAILED,
            FailureClassifier.classify(Stage.VERIFY, IllegalArgumentException()).code,
        )
    }

    @Test
    fun keepsTypedNativeFailureAcrossServiceBoundary() {
        val failure = PipelineException(Stage.MERGE, FailureCode.MERGE_FAILED)
        assertSame(failure, FailureClassifier.classify(Stage.TRANSFER, failure))
    }
}
