package com.os73.dosos73

/**
 * Engine contracts (data layer). Presentation code depends on these, never on OkHttp or the
 * embedded Python/FFmpeg runtime directly. Implementations: [DirectDownloader], [MediaEngine].
 */

/** Bounded, policy-checked HTTP inspection of a link (no body download). */
interface LinkInspector {
    /** What the link really serves; [onResponse] fires once response headers arrive. */
    suspend fun probe(url: String, onResponse: () -> Unit = {}): DirectProbe
    /** A small JSON document from a documented public API. */
    suspend fun fetchJson(url: String, accept: String = "application/json"): String
    /** Abort the in-flight request, from any thread. */
    fun cancel()
}

/** Extraction of media formats from a web page (slow: starts the embedded engine). */
interface MediaExtractor {
    suspend fun analyze(url: String): Analysis
    fun cancel()
}

/** Build the analyzer from engine contracts (production wiring). */
internal fun LinkAnalyzer.Companion.of(inspector: LinkInspector, extractor: MediaExtractor) = LinkAnalyzer(
    probe = { target, onResponse -> inspector.probe(target, onResponse) },
    fetchJson = { endpoint, accept -> inspector.fetchJson(endpoint, accept) },
    media = { target -> extractor.analyze(target) },
)
