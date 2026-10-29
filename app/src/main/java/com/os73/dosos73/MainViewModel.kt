package com.os73.dosos73

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenState(
    val url: String = "",
    val busy: Boolean = false,
    val analysis: Analysis? = null,
    val selected: Choice? = null,
    val error: Int? = null,
    val pendingShared: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val batchMutable = MutableStateFlow<List<BatchEntry>>(emptyList())
    internal val batch = batchMutable.asStateFlow()
    private val batchBusyMutable = MutableStateFlow(false)
    val batchBusy = batchBusyMutable.asStateFlow()
    private var direct = DirectDownloader()

    private fun directForNetwork(): DirectDownloader {
        val app = getApplication<Application>()
        return DirectDownloader(NetworkSettings.client(app, NetworkSettings.requireNetwork(app)))
            .also { direct = it }
    }

    private val media = MediaEngine(app)

    fun edit(url: String) {
        if (mutable.value.busy || batchBusyMutable.value) return
        mutable.value = ScreenState(url.take(8192))
    }

    fun receive(text: String) {
        if (mutable.value.busy || batchBusyMutable.value) {
            mutable.value = mutable.value.copy(pendingShared = text.take(32768))
            return
        }
        if (Regex("https?://", RegexOption.IGNORE_CASE).findAll(text).take(2).count() > 1) {
            prepareBatch(text)
            return
        }
        try {
            edit(Safety.shared(text))
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
        if (mutable.value.busy || batchBusyMutable.value) return
        val pending = mutable.value.pendingShared ?: return
        mutable.value = mutable.value.copy(pendingShared = null)
        receive(pending)
    }

    fun prepareBatch(text: String) {
        if (mutable.value.busy || batchBusyMutable.value) return
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
    }

    fun selectBatch(index: Int, selected: Boolean) {
        if (batchBusyMutable.value) return
        batchMutable.value =
            batchMutable.value.mapIndexed { i, item ->
                if (i == index && item.url != null) item.copy(selected = selected) else item
            }
    }

    fun chooseBatch(index: Int, choice: Choice) {
        if (batchBusyMutable.value) return
        batchMutable.value = batchMutable.value.mapIndexed { i, item ->
            if (i == index && item.analysis?.choices?.contains(choice) == true) item.copy(choice = choice)
            else item
        }
    }

    fun batchDestination(url: String, destination: String?) {
        if (batchBusyMutable.value) return
        if (destination != null && android.net.Uri.parse(destination).scheme != "content") return
        batchMutable.value = batchMutable.value.map { item ->
            if (item.url == url) item.copy(destination = destination, destinationOverride = true) else item
        }
    }

    fun analyzeBatch() {
        if (batchBusyMutable.value || mutable.value.busy) return
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
                                    withTimeout(120_000) {
                                        val downloader = directForNetwork()
                                        val directResult =
                                            try {
                                                downloader.analyze(entry.url)
                                            } catch (error: java.io.IOException) {
                                                currentCoroutineContext().ensureActive()
                                                if (!entry.url.startsWith("https://")) throw error
                                                null
                                            }
                                        directResult ?: media.analyze(entry.url)
                                    }
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
                    }
                } finally {
                    batchBusyMutable.value = false
                }
            }
    }

    internal fun removeBatch(entry: BatchEntry) {
        batchMutable.value = batchMutable.value.filterNot { it == entry }
    }

    fun clearBatch() {
        if (!batchBusyMutable.value) batchMutable.value = emptyList()
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
        mutable.value = mutable.value.copy(analysis = null, selected = null)
    }

    fun analyze() {
        if (mutable.value.busy || batchBusyMutable.value) return
        val url =
            try {
                Safety.url(mutable.value.url)
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = R.string.error_url)
                return
            }
        mutable.value =
            mutable.value.copy(busy = true, error = null, analysis = null, selected = null)
        job =
            viewModelScope.launch {
                try {
                    val analysis =
                        withTimeout(120_000) {
                            val downloader = directForNetwork()
                            val directResult =
                                try {
                                    downloader.analyze(url)
                                } catch (error: java.io.IOException) {
                                    currentCoroutineContext().ensureActive()
                                    if (!url.startsWith("https://")) throw error
                                    null
                                }
                            directResult ?: media.analyze(url)
                        }
                    mutable.value =
                        ScreenState(
                            url,
                            pendingShared = mutable.value.pendingShared,
                            analysis = analysis,
                            selected =
                                analysis.choices.firstOrNull { it.kind == preferred }
                                    ?: analysis.choices.first(),
                        )
                } catch (_: TimeoutCancellationException) {
                    direct.cancel()
                    media.cancel()
                    mutable.value = mutable.value.copy(busy = false, error = R.string.error_timeout)
                } catch (_: CancellationException) {
                    mutable.value = mutable.value.copy(busy = false)
                } catch (error: Exception) {
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
        direct.cancel()
        media.cancel()
        job?.cancel()
    }

    override fun onCleared() {
        direct.cancel()
        media.cancel()
        super.onCleared()
    }
}
