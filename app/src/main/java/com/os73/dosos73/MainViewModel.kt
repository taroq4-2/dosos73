package com.os73.dosos73

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScreenState(
    val url: String = "",
    val busy: Boolean = false,
    val analysis: Analysis? = null,
    val selected: Choice? = null,
    val error: Int? = null,
    val pendingShared: String? = null,
    val quick: Boolean = false,
    val quickNeedsConfirmation: Boolean = false,
    /** Visible analysis progress; [busy] stays the single input gate for existing callers. */
    val stage: AnalysisStage = AnalysisStage.IDLE,
    /** Files offered by a recognised source page, best match first. */
    val candidates: List<LinkCandidate> = emptyList(),
    /** True when the probe matched the file signature; null for media-engine results. */
    val verified: Boolean? = null,
    val category: FileCategory? = null,
    /** Seconds from an HTTP Retry-After header, shown with [error]. */
    val retryAfter: Long? = null,
)

/**
 * Presentation state for the home/link flow. Events in, [ScreenState] out (unidirectional):
 *  - state transitions are pure ([AnalysisReducer]);
 *  - engines are reached through contracts ([LinkInspector], [MediaExtractor]) via [LinkAnalyzer];
 *  - the encrypted multi-link draft lives in [BatchDraftSession].
 * One analysis job at a time (single link or batch); a generation counter makes every late result
 * of a cancelled/replaced analysis a no-op.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        @Volatile internal var analysisForTests: (suspend (String) -> Analysis)? = null
    }
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var analysisGeneration = 0L

    private val drafts = BatchDraftSession(
        app, viewModelScope,
        onAdopted = { busy ->
            mutable.value = mutable.value.copy(error = R.string.error_storage)
            if (busy) job?.cancel()
        },
        onStorageError = { mutable.value = mutable.value.copy(error = R.string.error_storage) },
    )
    internal val batch: StateFlow<List<BatchEntry>> = drafts.rows
    val batchRequested: StateFlow<Boolean> = drafts.requested
    fun batchOpened() = drafts.opened()
    val batchBusy: StateFlow<Boolean> = drafts.busy
    val batchSaving: StateFlow<Boolean> = drafts.saving
    val batchStorageError: StateFlow<Boolean> = drafts.storageError

    internal suspend fun queueBatch(entry: BatchEntry, task: DownloadTask) = drafts.queue(entry, task)

    fun retryBatchDraft() = drafts.retry()

    private var direct: LinkInspector = DirectDownloader()
    private val media: MediaExtractor = MediaEngine(app)

    private fun inspectorForNetwork(): LinkInspector {
        val app = getApplication<Application>()
        return DirectDownloader(NetworkSettings.client(app, NetworkSettings.requireNetwork(app, Stage.EXTRACT), Stage.EXTRACT))
            .also { direct = it }
    }

    private suspend fun analyzeSource(
        url: String,
        onStage: (AnalysisStage) -> Unit = {},
        pickFirst: Boolean = true,
    ): AnalysisOutcome = withTimeout(120_000) {
        if (BuildConfig.DEBUG) analysisForTests?.let { return@withTimeout AnalysisOutcome.Ready(it(url)) }
        onStage(AnalysisStage.VALIDATING)
        NetworkSettings.requireSource(getApplication(), url, Stage.EXTRACT)
        val inspector = inspectorForNetwork()
        withAnalysisCancellation({ inspector.cancel(); media.cancel() }) {
            LinkAnalyzer.of(inspector, media).analyze(url, onStage, pickFirst)
        }
    }

    internal fun restoreInput(url: String?, shared: String?) {
        // A retained ViewModel already owns its active analysis; only restore an empty draft.
        if (mutable.value == ScreenState()) mutable.value = ScreenState(
            url = url.orEmpty().take(8192), pendingShared = shared?.take(32768))
    }

    fun edit(url: String) {
        if (mutable.value.busy || drafts.busy.value) return
        // Draft recovery can be visible while a shared link is still staged.
        mutable.value = ScreenState(url.take(8192), pendingShared = mutable.value.pendingShared)
    }

    internal fun receiveShared(text: String) {
        if (!Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            mutable.value = mutable.value.copy(error = R.string.error_url)
            return
        }
        if (LinkPreferences.autoAnalyzeShared(getApplication())) receive(text)
        else mutable.value = mutable.value.copy(pendingShared = text.take(32768))
    }

    fun receive(text: String) {
        if (mutable.value.busy || !drafts.idle) {
            mutable.value = mutable.value.copy(pendingShared = text.take(32768))
            return
        }
        if (Regex("https?://", RegexOption.IGNORE_CASE).findAll(text).take(2).count() > 1) {
            prepareBatch(text)
            return
        }
        try {
            mutable.value = ScreenState(Safety.shared(text).take(8192))
            analyze()
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = R.string.error_url)
        }
    }

    fun paste(text: String?) {
        if (
            text.isNullOrBlank() ||
                !Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) {
            mutable.value = mutable.value.copy(error = R.string.nothing_to_paste)
        } else receive(text)
    }

    fun acceptPending() {
        if (mutable.value.busy || !drafts.idle) return
        val pending = mutable.value.pendingShared ?: return
        mutable.value = mutable.value.copy(pendingShared = null)
        receive(pending)
    }

    internal fun discardPending() {
        mutable.value = mutable.value.copy(pendingShared = null)
    }

    fun prepareBatch(text: String) {
        if (mutable.value.busy || !drafts.idle) {
            mutable.value = mutable.value.copy(pendingShared = text.take(32768))
            return
        }
        drafts.update {
            BatchInput.parse(text)
                .map { raw ->
                    val url = runCatching { Safety.url(raw).toHttpUrl().toString() }.getOrNull()
                    BatchEntry(
                        raw,
                        url,
                        selected = url != null,
                        error = if (url == null) R.string.error_url else null,
                    )
                }
                .distinctBy { it.url ?: it.raw }
        }
        drafts.request()
    }

    fun selectBatch(index: Int, selected: Boolean) {
        if (!drafts.idle) return
        drafts.update { rows ->
            rows.mapIndexed { i, item -> if (i == index && item.url != null) item.copy(selected = selected) else item }
        }
    }

    fun chooseBatch(index: Int, choice: Choice) {
        if (!drafts.idle) return
        drafts.update { rows ->
            rows.mapIndexed { i, item ->
                if (i == index && item.analysis?.choices?.contains(choice) == true) item.copy(choice = choice) else item
            }
        }
    }

    fun batchDestination(url: String, destination: String?) {
        if (!drafts.idle) return
        if (destination != null && android.net.Uri.parse(destination).scheme != "content") return
        drafts.update { rows ->
            rows.map { item -> if (item.url == url) item.copy(destination = destination, destinationOverride = true) else item }
        }
    }

    fun analyzeBatch() {
        if (!drafts.idle || mutable.value.busy || job?.isCompleted == false) return
        drafts.setBusy(true)
        job =
            viewModelScope.launch {
                try {
                    for (index in batch.value.indices) {
                        val entry = batch.value[index]
                        if (!entry.selected || entry.url == null || entry.analysis != null) continue
                        val result =
                            try {
                                // Batches take the best file of a release page; no per-row picker.
                                val analysis = (analyzeSource(entry.url) as AnalysisOutcome.Ready).analysis
                                entry.copy(analysis = analysis, error = null, choice = analysis.choices.firstOrNull())
                            } catch (error: TimeoutCancellationException) {
                                direct.cancel()
                                media.cancel()
                                entry.copy(error = R.string.error_timeout)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                entry.copy(error = FailureClassifier.classify(Stage.EXTRACT, error).messageResource())
                            }
                        drafts.update { rows -> rows.mapIndexed { i, item -> if (i == index) result else item } }
                    }
                } finally {
                    drafts.setBusy(false)
                }
            }
    }

    fun clearBatch() {
        if (drafts.idle) drafts.update { emptyList() }
    }

    private var preferred = Kind.VIDEO
    private val preferredMutable = MutableStateFlow(Kind.VIDEO)
    /** Media kind the home shortcuts select; drives which analysed format is preselected. */
    val preferredKind: StateFlow<Kind> = preferredMutable.asStateFlow()

    fun prefer(kind: Kind) {
        preferred = kind
        preferredMutable.value = kind
        mutable.value.analysis?.choices?.firstOrNull { it.kind == kind }?.let(::select)
    }

    fun select(choice: Choice) {
        if (choice in mutable.value.analysis?.choices.orEmpty())
            mutable.value = mutable.value.copy(selected = choice)
    }

    internal fun quickInitializing() = !drafts.ready && !drafts.storageError.value

    internal fun quickAvailable() = drafts.idle && job?.isCompleted != false && !mutable.value.busy &&
        !mutable.value.quick && mutable.value.url.isBlank() && mutable.value.pendingShared == null

    internal fun openQuick(url: String) {
        check(quickAvailable())
        mutable.value = ScreenState(url = QuickLink.parse(url), quick = true, quickNeedsConfirmation = true)
    }

    internal fun confirmQuickAnalysis() {
        val screen = mutable.value
        if (!screen.quick || !screen.quickNeedsConfirmation) return
        mutable.value = screen.copy(quickNeedsConfirmation = false)
        analyze()
    }

    internal fun closeQuick() {
        if (!mutable.value.quick) return
        cancelAnalysis()
        mutable.value = ScreenState(busy = job?.isCompleted == false, pendingShared = mutable.value.pendingShared)
    }

    fun analyze() {
        if (mutable.value.quickNeedsConfirmation || mutable.value.busy ||
            drafts.busy.value || job?.isCompleted == false) return
        val url =
            try {
                Safety.url(mutable.value.url)
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = R.string.error_url)
                return
            }
        mutable.value = AnalysisReducer.started(mutable.value)
        val generation = ++analysisGeneration
        fun current() = generation == analysisGeneration
        // Stage callbacks arrive from network threads; a stale analysis can never overwrite a newer one.
        val onStage: (AnalysisStage) -> Unit = { stage ->
            mutable.update { if (current()) AnalysisReducer.stage(it, stage) else it }
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        job =
            viewModelScope.launch {
                try {
                    val outcome = analyzeSource(url, onStage, pickFirst = false)
                    if (!current()) return@launch
                    Diagnostics.event(Stage.EXTRACT, "ready", millis = android.os.SystemClock.elapsedRealtime() - startedAt)
                    mutable.value = AnalysisReducer.ready(mutable.value, url, outcome, preferred)
                } catch (_: TimeoutCancellationException) {
                    if (!current()) return@launch
                    direct.cancel()
                    media.cancel()
                    mutable.value = AnalysisReducer.timedOut(mutable.value)
                } catch (_: CancellationException) {
                    if (current()) mutable.value = mutable.value.copy(busy = false)
                } catch (error: Exception) {
                    if (!current()) return@launch
                    val failure = FailureClassifier.classify(Stage.EXTRACT, error)
                    Diagnostics.event(failure.stage, "failed", code = failure.code)
                    mutable.value = AnalysisReducer.failed(mutable.value, failure)
                }
            }
    }

    fun cancelAnalysis() {
        val cancelled = job
        val generation = ++analysisGeneration
        direct.cancel()
        media.cancel()
        cancelled?.cancel()
        // Engines are shared by single/batch analysis; keep the gate closed until cleanup ends.
        viewModelScope.launch {
            cancelled?.join()
            if (generation == analysisGeneration) mutable.value = AnalysisReducer.canceled(mutable.value)
        }
    }

    /** Analyze one file offered by a source page (e.g. a GitHub release asset). */
    fun pickCandidate(candidate: LinkCandidate) {
        val screen = mutable.value
        if (screen.busy || candidate !in screen.candidates) return
        mutable.value = ScreenState(url = candidate.url, pendingShared = screen.pendingShared)
        analyze()
    }

    override fun onCleared() {
        direct.cancel()
        media.cancel()
        super.onCleared()
    }
}

/** Close the underlying blocking operation as soon as timeout or scope cancellation begins. */
internal suspend fun <T> withAnalysisCancellation(cancelOperation: () -> Unit, work: suspend () -> T): T =
    coroutineScope {
        val guardian = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { cancelOperation() }
        }
        try { work() }
        finally { withContext(NonCancellable) { guardian.cancelAndJoin() } }
    }
