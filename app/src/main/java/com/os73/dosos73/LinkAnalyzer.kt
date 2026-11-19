package com.os73.dosos73

import androidx.annotation.StringRes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Visible analysis progress. One-way: VALIDATING → (RESOLVING) → CONNECTING → PROBING → (EXTRACTING) → READY. */
enum class AnalysisStage { IDLE, VALIDATING, RESOLVING, CONNECTING, PROBING, EXTRACTING, READY, FAILED, CANCELED }

val AnalysisStage.working: Boolean get() = this in AnalysisStage.VALIDATING..AnalysisStage.EXTRACTING

@StringRes
fun AnalysisStage.label(): Int? = when (this) {
    AnalysisStage.VALIDATING -> R.string.stage_validating
    AnalysisStage.RESOLVING -> R.string.stage_resolving
    AnalysisStage.CONNECTING -> R.string.stage_connecting
    AnalysisStage.PROBING -> R.string.stage_probing
    AnalysisStage.EXTRACTING -> R.string.stage_extracting
    AnalysisStage.CANCELED -> R.string.analysis_canceled
    else -> null
}

sealed interface AnalysisOutcome {
    /** [verified]: true when the first bytes matched the type, null for media-engine results. */
    data class Ready(val analysis: Analysis, val verified: Boolean? = null, val category: FileCategory? = null) :
        AnalysisOutcome
    /** A source page with several files; the user picks one, which is then analyzed. */
    data class Choices(val candidates: List<LinkCandidate>) : AnalysisOutcome
}

/** Carries HTTP Retry-After seconds to the UI without exposing the response. */
class RetryAfter(val seconds: Long) : Exception("Retry-After")

/**
 * Link → analysis, in the cheapest order (AUDIT_BASELINE.md P0-3):
 *  1. Recognised source pages are resolved through their documented APIs.
 *  2. One bounded probe proves whether the link is a real file; if so, the result is ready
 *     without starting the media engine.
 *  3. Only web pages that may hold media go to the (slow) media engine.
 * Failures that no engine can fix (404, 410, a wrong file type, a page instead of an APK...)
 * are reported immediately with their specific reason.
 */
internal class LinkAnalyzer(
    private val probe: suspend (String, () -> Unit) -> DirectProbe,
    private val fetchJson: suspend (String, String) -> String,
    private val media: suspend (String) -> Analysis,
    private val probeTimeoutMs: Long = 30_000,
    private val resolveTimeoutMs: Long = 25_000,
) {
    suspend fun analyze(url: String, onStage: (AnalysisStage) -> Unit, pickFirst: Boolean): AnalysisOutcome {
        var target = url
        val source = SourceResolvers.match(url)
        if (source != null) {
            onStage(AnalysisStage.RESOLVING)
            val resolution = withTimeoutOrNull(resolveTimeoutMs) {
                try { SourceResolvers.resolve(source, fetchJson) }
                catch (error: PipelineException) {
                    // A repository or package without a published file.
                    if (error.code == FailureCode.HTTP_404) throw PipelineException(Stage.EXTRACT, FailureCode.SOURCE_NO_FILE, error)
                    throw error
                }
            } ?: throw PipelineException(Stage.EXTRACT, FailureCode.NETWORK_TIMEOUT)
            when (resolution) {
                is SourceResolvers.Resolution.Direct -> target = resolution.url
                is SourceResolvers.Resolution.Choices ->
                    if (pickFirst) target = resolution.candidates.first().url
                    else return AnalysisOutcome.Choices(resolution.candidates)
            }
        }
        onStage(AnalysisStage.CONNECTING)
        val result = try {
            withTimeoutOrNull(probeTimeoutMs) { probe(target) { onStage(AnalysisStage.PROBING) } }
                ?: throw PipelineException(Stage.EXTRACT, FailureCode.NETWORK_TIMEOUT)
        } catch (error: java.io.IOException) {
            // A cancelled analysis closes its socket; that IOException must not fall through to the engine.
            currentCoroutineContext().ensureActive()
            // Unreachable host, refused connection, timeout, TLS: the media engine would fail the same way.
            if (networkFailure(error) || !target.startsWith("https://") || source != null || fileLike(target))
                throw FailureClassifier.classify(Stage.EXTRACT, error)
            null
        }
        when (result) {
            is DirectProbe.File -> return AnalysisOutcome.Ready(result.analysis, result.verified, result.category)
            is DirectProbe.Failed -> if (result.code in terminal || source != null || fileLike(target))
                throw PipelineException(Stage.EXTRACT, result.code, result.retryAfterSeconds?.let(::RetryAfter))
            is DirectProbe.Page -> if (source != null || fileLike(target) || !mediaCandidateHost(target))
                throw PipelineException(Stage.EXTRACT, FailureCode.WEB_PAGE_NOT_FILE)
            null -> Unit
        }
        onStage(AnalysisStage.EXTRACTING)
        return AnalysisOutcome.Ready(media(url))
    }

    private val terminal = setOf(FailureCode.HTTP_404, FailureCode.LINK_EXPIRED, FailureCode.FILE_TYPE_MISMATCH)

    private fun networkFailure(error: java.io.IOException) =
        error is java.net.UnknownHostException || error is java.net.ConnectException ||
            error is java.net.SocketTimeoutException || error is javax.net.ssl.SSLException

    companion object {
        /** A URL whose name says "file" (APK, archive, program, document): a page there is never media. */
        fun fileLike(url: String): Boolean {
            val name = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty().lowercase()
            val extension = name.substringAfterLast('.', "")
            return extension.isNotEmpty() && FileTypes.category(extension) in
                setOf(FileCategory.APP, FileCategory.ARCHIVE, FileCategory.PROGRAM, FileCategory.DOCUMENT)
        }

        /** Hosts that only serve files and pages, never media the engine could extract. */
        private val fileOnlyHosts = setOf(
            "github.com", "objects.githubusercontent.com", "raw.githubusercontent.com", "gitlab.com",
            "f-droid.org", "sourceforge.net", "downloads.sourceforge.net", "codeberg.org",
        )

        fun mediaCandidateHost(url: String): Boolean {
            val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
            return fileOnlyHosts.none { host == it || host.endsWith(".$it") }
        }
    }
}
