package com.os73.dosos73

enum class Stage {
    INITIALIZE,
    EXTRACT,
    TRANSFER,
    MERGE,
    VERIFY,
    PUBLISH,
}

enum class FailureCode {
    AUTHENTICATION_REQUIRED,
    HTTP_403,
    HTTP_404,
    HTTP_SERVER,
    NETWORK_UNAVAILABLE,
    HTTP_429,
    FORMAT_UNAVAILABLE,
    JS_RUNTIME_UNAVAILABLE,
    EJS_COMPONENT_UNAVAILABLE,
    NETWORK_TIMEOUT,
    NETWORK_TLS,
    DOWNLOAD_INTERRUPTED,
    MERGE_FAILED,
    OUTPUT_EMPTY,
    OUTPUT_VERIFICATION_FAILED,
    STORAGE_PERMISSION_DENIED,
    STORAGE_FULL,
    OUTPUT_WRITE_FAILED,
    PUBLICATION_REVIEW_REQUIRED,
    EXTRACTION_FAILED,
    DOWNLOAD_FAILED,
}

class PipelineException(val stage: Stage, val code: FailureCode, cause: Throwable? = null) :
    Exception("${stage.name}:${code.name}", cause)

object FailureClassifier {
    fun classify(stage: Stage, error: Throwable): PipelineException {
        if (error is PipelineException) return error
        val message = error.message.orEmpty().lowercase()
        val code =
            when {
                "enospc" in message || "no space left on device" in message ->
                    FailureCode.STORAGE_FULL
                error is SecurityException -> FailureCode.STORAGE_PERMISSION_DENIED
                "sign in to confirm" in message ||
                    "only works when logged-in" in message ||
                    "login required" in message -> FailureCode.AUTHENTICATION_REQUIRED
                error is javax.net.ssl.SSLException ||
                    "[ssl]" in message ||
                    "certificate verify failed" in message -> FailureCode.NETWORK_TLS
                Regex("(?:http(?: error)?|status(?: code)?)[ :]+403\\b").containsMatchIn(message) ->
                    FailureCode.HTTP_403
                Regex("(?:http(?: error)?|status(?: code)?)[ :]+429\\b").containsMatchIn(message) ->
                    FailureCode.HTTP_429
                "requested format is not available" in message -> FailureCode.FORMAT_UNAVAILABLE
                "no supported javascript runtime" in message -> FailureCode.JS_RUNTIME_UNAVAILABLE
                "ejs" in message && ("not found" in message || "unavailable" in message) ->
                    FailureCode.EJS_COMPONENT_UNAVAILABLE
                error is java.net.SocketTimeoutException || "timed out" in message ->
                    FailureCode.NETWORK_TIMEOUT
                error is java.net.UnknownHostException || error is java.net.ConnectException ->
                    FailureCode.NETWORK_UNAVAILABLE
                Regex("(?:http(?: error)?|status(?: code)?)[ :]+404\\b").containsMatchIn(message) ->
                    FailureCode.HTTP_404
                Regex("(?:http(?: error)?|status(?: code)?)[ :]+5[0-9]{2}\\b")
                    .containsMatchIn(message) -> FailureCode.HTTP_SERVER
                stage == Stage.MERGE -> FailureCode.MERGE_FAILED
                stage == Stage.VERIFY -> FailureCode.OUTPUT_VERIFICATION_FAILED
                stage == Stage.PUBLISH -> FailureCode.OUTPUT_WRITE_FAILED
                stage == Stage.EXTRACT -> FailureCode.EXTRACTION_FAILED
                else -> FailureCode.DOWNLOAD_FAILED
            }
        return PipelineException(stage, code, error)
    }
}

fun PipelineException.messageResource(): Int =
    when (code) {
        FailureCode.PUBLICATION_REVIEW_REQUIRED -> R.string.error_publication_review
        FailureCode.STORAGE_FULL -> R.string.error_disk_full
        FailureCode.NETWORK_TLS -> R.string.error_tls
        FailureCode.AUTHENTICATION_REQUIRED -> R.string.error_auth_required
        FailureCode.HTTP_404 -> R.string.error_http404
        FailureCode.HTTP_SERVER -> R.string.error_server
        FailureCode.NETWORK_UNAVAILABLE -> R.string.error_offline
        FailureCode.HTTP_403 -> R.string.error_http403
        FailureCode.HTTP_429 -> R.string.error_http429
        FailureCode.FORMAT_UNAVAILABLE -> R.string.error_format_unavailable
        FailureCode.JS_RUNTIME_UNAVAILABLE,
        FailureCode.EJS_COMPONENT_UNAVAILABLE -> R.string.error_runtime
        FailureCode.MERGE_FAILED -> R.string.error_merge
        FailureCode.OUTPUT_EMPTY,
        FailureCode.OUTPUT_VERIFICATION_FAILED -> R.string.error_verification
        FailureCode.STORAGE_PERMISSION_DENIED,
        FailureCode.OUTPUT_WRITE_FAILED -> R.string.error_storage
        FailureCode.NETWORK_TIMEOUT -> R.string.error_timeout
        FailureCode.EXTRACTION_FAILED -> R.string.error_analyze
        else -> R.string.error_download
    }

/**
 * Allowlisted diagnostic fields only. Never stores URL, filename, URI, exception message or native
 * stderr.
 */
object Diagnostics {
    fun event(stage: Stage, result: String, bytes: Long? = null, code: FailureCode? = null) {
        if (BuildConfig.DEBUG)
            android.util.Log.i(
                "DososPipeline",
                org.json
                    .JSONObject()
                    .apply {
                        put("timestamp", System.currentTimeMillis())
                        put("stage", stage.name)
                        put("result", result.take(80))
                        put("wrapper", "0.18.1")
                        put("engine", "2026.10.21")
                        bytes?.let { put("bytes", it) }
                        code?.let { put("failure", it.name) }
                    }
                    .toString(),
            )
    }
}
