package com.os73.dosos73

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock

data class ScreenState(
    val url: String = "",
    val busy: Boolean = false,
    val analysis: Analysis? = null,
    val selected: Choice? = null,
    val error: Int? = null,
    val pendingShared: String? = null,
    val quick: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        @Volatile internal var analysisForTests: (suspend (String) -> Analysis)? = null
    }
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var analysisGeneration = 0L
    private val batchMutable = MutableStateFlow<List<BatchEntry>>(emptyList())
    internal val batch = batchMutable.asStateFlow()
    private val batchRequestedMutable = MutableStateFlow(false)
    val batchRequested = batchRequestedMutable.asStateFlow()
    fun batchOpened() { batchRequestedMutable.value = false }
    private val batchBusyMutable = MutableStateFlow(false)
    val batchBusy = batchBusyMutable.asStateFlow()
    private var direct = DirectDownloader()

    private val draft = BatchDraft(app)
    private sealed interface DraftCommand {
        data class Write(val revision: Long, val rows: List<BatchEntry>) : DraftCommand
        data class Queue(val entry: BatchEntry, val task: DownloadTask,
            val done: CompletableDeferred<Unit>) : DraftCommand
    }
    // At most twenty analysis snapshots plus one handoff; UI edits wait for saving to finish.
    private val draftWrites = kotlinx.coroutines.channels.Channel<DraftCommand>(32)
    private var draftReady = false
    private var draftRevision = 0L
    private var invalidThroughRevision = 0L
    private var durableDraftToken: String? = null
    private class DraftChanged(val latest: BatchDraftSnapshot) : IllegalStateException("Batch draft changed")
    private val savingMutable = MutableStateFlow(true)
    val batchSaving = savingMutable.asStateFlow()

    private val draftRetries = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val draftErrorMutable = MutableStateFlow(false)
    val batchStorageError = draftErrorMutable.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                try {
                    // BatchDraft.read commits legacy delivery IDs before they become sendable.
                    val restored = BatchDraft.protocolLock.withLock {
                        val snapshot = withContext(Dispatchers.IO) { draft.readSnapshot() }
                        DownloadQueue.reconcileBatch(app, snapshot.rows.map { it.deliveryId }.toSet())
                        durableDraftToken = snapshot.token
                        snapshot.rows
                    }
                    batchMutable.value = restored
                    draftReady = true
                    savingMutable.value = false
                    draftErrorMutable.value = false
                    break
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { awaitDraftRetry() }
            }
            for (command in draftWrites) {
                when (command) {
                    is DraftCommand.Write -> if (command.revision > invalidThroughRevision)
                        saveDraft(command.revision, command.rows)
                    is DraftCommand.Queue -> {
                        try {
                            BatchDraft.protocolLock.withLock {
                                val latest = withContext(Dispatchers.IO) { draft.readSnapshot() }
                                if (latest.token != durableDraftToken) throw DraftChanged(latest)
                                check(batchMutable.value.any { it == command.entry })
                                DownloadQueue.reconcileBatch(app, latest.rows.map { it.deliveryId }.toSet())
                                DownloadQueue.enqueueBatch(app, command.entry.deliveryId, command.task)
                            }
                            batchMutable.value = batchMutable.value.filterNot {
                                it.deliveryId == command.entry.deliveryId
                            }
                            val revision = ++draftRevision
                            savingMutable.value = true
                            saveDraft(revision, batchMutable.value.toList()) {
                                // Release the caller's submission UI while retaining the receipt.
                                command.done.completeExceptionally(it)
                            }
                            command.done.complete(Unit)
                        } catch (error: CancellationException) {
                            command.done.completeExceptionally(error)
                            throw error
                        } catch (error: DraftChanged) {
                            adoptLatestDraft(error.latest)
                            command.done.completeExceptionally(error)
                        } catch (error: Exception) {
                            command.done.completeExceptionally(error)
                        } finally {
                            batchBusyMutable.value = false
                        }
                    }
                }
            }
        }
    }

    private suspend fun saveDraft(revision: Long, rows: List<BatchEntry>,
        onFailure: (Exception) -> Unit = {}) {
        while (true) {
            try {
                BatchDraft.protocolLock.withLock {
                    val latest = withContext(Dispatchers.IO) { draft.readSnapshot() }
                    if (latest.token != durableDraftToken) throw DraftChanged(latest)
                    val committed = withContext(Dispatchers.IO) { draft.writeSnapshot(rows) }
                    durableDraftToken = committed.token
                    // Never drop an acceptance receipt before the row's removal is durable.
                    DownloadQueue.reconcileBatch(getApplication<Application>(), rows.map { it.deliveryId }.toSet())
                }
                draftErrorMutable.value = false
                if (revision == draftRevision) savingMutable.value = false
                return
            } catch (error: CancellationException) { throw error }
            catch (error: DraftChanged) {
                adoptLatestDraft(error.latest)
                onFailure(error)
                return
            }
            catch (error: Exception) {
                onFailure(error)
                awaitDraftRetry()
            }
        }
    }

    private fun adoptLatestDraft(snapshot: BatchDraftSnapshot) {
        // Invalidate every buffered snapshot from this actor, including one waiting for Retry.
        invalidThroughRevision = draftRevision
        draftRevision++
        durableDraftToken = snapshot.token
        batchMutable.value = snapshot.rows
        savingMutable.value = false
        draftErrorMutable.value = false
        mutable.value = mutable.value.copy(error = R.string.error_storage)
        if (batchBusyMutable.value) job?.cancel()
    }

    internal suspend fun queueBatch(entry: BatchEntry, task: DownloadTask) {
        check(draftReady && !batchBusyMutable.value && !savingMutable.value && !draftErrorMutable.value)
        val done = CompletableDeferred<Unit>()
        batchBusyMutable.value = true
        // The actor owns busy until the handoff ends, even if the screen's caller is cancelled.
        draftWrites.send(DraftCommand.Queue(entry, task, done))
        done.await()
    }

    private suspend fun awaitDraftRetry() {
        // Keep the encrypted file and in-memory review; retry only on explicit user action.
        draftErrorMutable.value = true
        savingMutable.value = false
        draftRetries.receive()
    }

    fun retryBatchDraft() {
        if (!draftErrorMutable.value) return
        savingMutable.value = true
        draftRetries.trySend(Unit)
    }

    private fun persistBatch() {
        draftRevision++
        savingMutable.value = true
        if (draftWrites.trySend(DraftCommand.Write(draftRevision, batchMutable.value.toList())).isFailure)
            mutable.value = mutable.value.copy(error = R.string.error_storage)
    }

    private fun directForNetwork(): DirectDownloader {
        val app = getApplication<Application>()
        return DirectDownloader(NetworkSettings.client(app, NetworkSettings.requireNetwork(app, Stage.EXTRACT), Stage.EXTRACT))
            .also { direct = it }
    }

    private val media = MediaEngine(app)

    private suspend fun analyzeSource(url: String): Analysis = withTimeout(120_000) {
        if (BuildConfig.DEBUG) analysisForTests?.let { return@withTimeout it(url) }
        val app = getApplication<Application>()
        NetworkSettings.requireSource(app, url, Stage.EXTRACT)
        val downloader = directForNetwork()
        withAnalysisCancellation({ downloader.cancel(); media.cancel() }) {
            val candidate = try { downloader.analyze(url) }
            catch (error: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                if (!url.startsWith("https://")) throw error
                null
            }
            candidate ?: media.analyze(url)
        }
    }

    internal fun restoreInput(url: String?, shared: String?) {
        // A retained ViewModel already owns its active analysis; only restore an empty draft.
        if (mutable.value == ScreenState()) mutable.value = ScreenState(
            url = url.orEmpty().take(8192), pendingShared = shared?.take(32768))
    }

    fun edit(url: String) {
        if (mutable.value.busy || batchBusyMutable.value) return
        // Draft recovery can be visible while a shared link is still staged.
        mutable.value = ScreenState(url.take(8192), pendingShared = mutable.value.pendingShared)
    }

    fun receive(text: String) {
        if (mutable.value.busy || batchBusyMutable.value || !draftReady || savingMutable.value || draftErrorMutable.value) {
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
        if (mutable.value.busy || batchBusyMutable.value || !draftReady || savingMutable.value || draftErrorMutable.value) return
        val pending = mutable.value.pendingShared ?: return
        mutable.value = mutable.value.copy(pendingShared = null)
        receive(pending)
    }

    internal fun discardPending() {
        mutable.value = mutable.value.copy(pendingShared = null)
    }

    fun prepareBatch(text: String) {
        if (mutable.value.busy || batchBusyMutable.value || !draftReady || savingMutable.value || draftErrorMutable.value) {
            mutable.value = mutable.value.copy(pendingShared = text.take(32768))
            return
        }
        batchMutable.value =
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
        batchRequestedMutable.value = true
        persistBatch()
    }

    fun selectBatch(index: Int, selected: Boolean) {
        if (batchBusyMutable.value || !draftReady || savingMutable.value || draftErrorMutable.value) return
        batchMutable.value =
            batchMutable.value.mapIndexed { i, item ->
                if (i == index && item.url != null) item.copy(selected = selected) else item
            }
        persistBatch()
    }

    fun chooseBatch(index: Int, choice: Choice) {
        if (batchBusyMutable.value || !draftReady || savingMutable.value || draftErrorMutable.value) return
        batchMutable.value = batchMutable.value.mapIndexed { i, item ->
            if (i == index && item.analysis?.choices?.contains(choice) == true) item.copy(choice = choice)
            else item
        }
        persistBatch()
    }

    fun batchDestination(url: String, destination: String?) {
        if (batchBusyMutable.value || !draftReady || savingMutable.value || draftErrorMutable.value) return
        if (destination != null && android.net.Uri.parse(destination).scheme != "content") return
        batchMutable.value = batchMutable.value.map { item ->
            if (item.url == url) item.copy(destination = destination, destinationOverride = true) else item
        }
        persistBatch()
    }

    fun analyzeBatch() {
        if (batchBusyMutable.value || mutable.value.busy || job?.isCompleted == false || !draftReady || savingMutable.value || draftErrorMutable.value) return
        batchBusyMutable.value = true
        job =
            viewModelScope.launch {
                try {
                    for (index in batchMutable.value.indices) {
                        val entry = batchMutable.value[index]
                        if (!entry.selected || entry.url == null || entry.analysis != null) continue
                        val result =
                            try {
                                val analysis =
                                    analyzeSource(entry.url)
                                entry.copy(analysis = analysis, error = null, choice = analysis.choices.firstOrNull())
                            } catch (error: TimeoutCancellationException) {
                                direct.cancel()
                                media.cancel()
                                entry.copy(error = R.string.error_timeout)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                entry.copy(
                                    error =
                                        FailureClassifier.classify(Stage.EXTRACT, error)
                                            .messageResource()
                                )
                            }
                        batchMutable.value =
                            batchMutable.value.mapIndexed { i, item ->
                                if (i == index) result else item
                            }
                        persistBatch()
                    }
                } finally {
                    batchBusyMutable.value = false
                }
            }
    }

    fun clearBatch() {
        if (!batchBusyMutable.value && draftReady && !savingMutable.value && !draftErrorMutable.value) {
            batchMutable.value = emptyList()
            persistBatch()
        }
    }

    private var preferred = Kind.VIDEO

    fun prefer(kind: Kind) {
        preferred = kind
        mutable.value.analysis?.choices?.firstOrNull { it.kind == kind }?.let(::select)
    }

    fun select(choice: Choice) {
        if (choice in mutable.value.analysis?.choices.orEmpty())
            mutable.value = mutable.value.copy(selected = choice)
    }

    fun invalidateFormats() {
        // A transfer failure is independent of a new link currently being reviewed.
        if (mutable.value.quick) return
        mutable.value = mutable.value.copy(analysis = null, selected = null)
    }

    internal fun quickInitializing() = !draftReady && !draftErrorMutable.value

    internal fun quickAvailable() = draftReady && !savingMutable.value && !draftErrorMutable.value &&
        !batchBusyMutable.value && job?.isCompleted != false && !mutable.value.busy && !mutable.value.quick &&
        mutable.value.url.isBlank() && mutable.value.pendingShared == null

    internal fun openQuick(url: String) {
        check(quickAvailable())
        mutable.value = ScreenState(url = QuickLink.parse(url), quick = true)
        analyze()
    }

    internal fun closeQuick() {
        if (!mutable.value.quick) return
        cancelAnalysis()
        mutable.value = ScreenState(busy = job?.isCompleted == false, pendingShared = mutable.value.pendingShared)
    }

    fun analyze() {
        if (mutable.value.busy || batchBusyMutable.value || job?.isCompleted == false) return
        val url =
            try {
                Safety.url(mutable.value.url)
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = R.string.error_url)
                return
            }
        mutable.value =
            mutable.value.copy(busy = true, error = null, analysis = null, selected = null)
        val generation = ++analysisGeneration
        job =
            viewModelScope.launch {
                try {
                    val analysis =
                        analyzeSource(url)
                    if (generation != analysisGeneration) return@launch
                    mutable.value =
                        mutable.value.copy(
                            url = url,
                            busy = false,
                            error = null,
                            pendingShared = mutable.value.pendingShared,
                            analysis = analysis,
                            selected =
                                analysis.choices.firstOrNull { it.kind == preferred }
                                    ?: analysis.choices.first(),
                        )
                } catch (_: TimeoutCancellationException) {
                    if (generation != analysisGeneration) return@launch
                    direct.cancel()
                    media.cancel()
                    mutable.value = mutable.value.copy(busy = false, error = R.string.error_timeout)
                } catch (_: CancellationException) {
                    if (generation == analysisGeneration) mutable.value = mutable.value.copy(busy = false)
                } catch (error: Exception) {
                    if (generation != analysisGeneration) return@launch
                    val failure = FailureClassifier.classify(Stage.EXTRACT, error)
                    Diagnostics.event(failure.stage, "failed", code = failure.code)
                    mutable.value =
                        mutable.value.copy(
                            busy = false,
                            analysis = null,
                            selected = null,
                            error = failure.messageResource(),
                        )
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
            if (generation == analysisGeneration) mutable.value = mutable.value.copy(busy = false)
        }
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
