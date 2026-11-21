package com.os73.dosos73

/**
 * Pure state transitions of the single-link analysis (unidirectional data flow: the ViewModel
 * turns events into one of these, then publishes the result). No coroutines, no Android, so every
 * transition is covered by JVM tests (`AnalysisReducerTest`).
 */
internal object AnalysisReducer {
    fun started(state: ScreenState): ScreenState = state.copy(
        busy = true, error = null, analysis = null, selected = null, stage = AnalysisStage.VALIDATING,
        candidates = emptyList(), verified = null, category = null, retryAfter = null,
    )

    /** Progress only moves forward while the same analysis is still running. */
    fun stage(state: ScreenState, stage: AnalysisStage): ScreenState =
        if (state.busy && stage.working && stage.ordinal > state.stage.ordinal) state.copy(stage = stage) else state

    fun ready(state: ScreenState, url: String, outcome: AnalysisOutcome, preferred: Kind): ScreenState =
        when (outcome) {
            is AnalysisOutcome.Choices -> state.copy(
                url = url, busy = false, error = null, stage = AnalysisStage.READY, candidates = outcome.candidates)
            is AnalysisOutcome.Ready -> {
                val choices = outcome.analysis.choices
                state.copy(
                    url = url, busy = false, error = null, stage = AnalysisStage.READY,
                    verified = outcome.verified, category = outcome.category, analysis = outcome.analysis,
                    selected = choices.firstOrNull { it.kind == preferred } ?: choices.first(),
                )
            }
        }

    fun failed(state: ScreenState, failure: PipelineException): ScreenState = state.copy(
        busy = false, stage = AnalysisStage.FAILED, analysis = null, selected = null,
        retryAfter = (failure.cause as? RetryAfter)?.seconds?.takeIf { it > 0 },
        error = failure.messageResource(),
    )

    fun timedOut(state: ScreenState): ScreenState =
        state.copy(busy = false, stage = AnalysisStage.FAILED, error = R.string.error_timeout)

    fun canceled(state: ScreenState): ScreenState = state.copy(busy = false, stage = AnalysisStage.CANCELED)
}
