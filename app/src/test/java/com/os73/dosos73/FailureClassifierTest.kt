package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class FailureClassifierTest {
    @Test fun distinguishesOfflineMissingAndServerErrors() {
        assertEquals(FailureCode.NETWORK_UNAVAILABLE, FailureClassifier.classify(Stage.TRANSFER, java.net.UnknownHostException()).code)
        assertEquals(FailureCode.HTTP_404, FailureClassifier.classify(Stage.TRANSFER, java.io.IOException("HTTP 404")).code)
        assertEquals(FailureCode.HTTP_SERVER, FailureClassifier.classify(Stage.TRANSFER, java.io.IOException("HTTP 503")).code)
        assertEquals(FailureCode.DOWNLOAD_FAILED, FailureClassifier.classify(Stage.TRANSFER, java.io.IOException("file 503 missing")).code)
    }

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

    @Test fun incompletePlayerResponseWithOnlyImagesIsAnInterruptedProbe() {
        val output = """
            WARNING: [youtube] [jsc] Failed to load player for JS challenge (caused by <IncompleteRead: 955014 bytes read, 1126 more expected>)
            WARNING: Only images are available for download. use --list-formats to see them
            ERROR: [youtube] Requested format is not available
        """.trimIndent()
        for (stage in listOf(Stage.EXTRACT, Stage.TRANSFER)) {
            assertEquals(FailureCode.DOWNLOAD_INTERRUPTED,
                FailureClassifier.classify(stage, java.io.IOException(output)).code)
        }
        assertEquals(FailureCode.AUTHENTICATION_REQUIRED, FailureClassifier.classify(
            Stage.EXTRACT, java.io.IOException("$output\nERROR: Sign in to confirm you’re not a bot"),
        ).code)
        assertEquals(FailureCode.FORMAT_UNAVAILABLE, FailureClassifier.classify(
            Stage.EXTRACT, java.io.IOException(output.replace("Only images are available for download", "Format selection")),
        ).code)
        assertEquals(FailureCode.FORMAT_UNAVAILABLE, FailureClassifier.classify(
            Stage.EXTRACT, java.io.IOException("WARNING: HTTP Error 429\n$output"),
        ).code)
    }
}
