package com.os73.dosos73

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenState(val url: String = "", val busy: Boolean = false, val analysis: Analysis? = null,
    val selected: Choice? = null, val error: Int? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val direct = DirectDownloader()
    private val media = MediaEngine(app)
    fun edit(url: String) {
        if (mutable.value.busy || Transfers.state.value.active) return
        mutable.value = ScreenState(url.take(8192))
    }
    fun receive(text: String) {
        if (mutable.value.busy || Transfers.state.value.active) return
        try { edit(Safety.shared(text)); analyze() }
        catch (_: Exception) { mutable.value = mutable.value.copy(error = R.string.error_url) }
    }
    fun select(choice: Choice) { if (choice in mutable.value.analysis?.choices.orEmpty()) mutable.value = mutable.value.copy(selected = choice) }
    fun invalidateFormats() { mutable.value = mutable.value.copy(analysis = null, selected = null) }
    fun analyze() {
        if (mutable.value.busy || Transfers.state.value.active) return
        val url = try { Safety.url(mutable.value.url) } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = R.string.error_url); return
        }
        mutable.value = mutable.value.copy(busy = true, error = null, analysis = null, selected = null)
        job = viewModelScope.launch {
            try {
                val analysis = direct.analyze(url) ?: media.analyze(url)
                mutable.value = ScreenState(url, analysis = analysis, selected = analysis.choices.first())
            } catch (_: CancellationException) {
                mutable.value = mutable.value.copy(busy = false)
            } catch (error: Exception) {
                val failure = FailureClassifier.classify(Stage.EXTRACT, error)
                Diagnostics.event(failure.stage, "failed", code = failure.code)
                mutable.value = mutable.value.copy(busy = false, analysis = null, selected = null, error = failure.messageResource())
            }
        }
    }
    fun cancelAnalysis() {
        direct.cancel()
        viewModelScope.launch(Dispatchers.IO) { runCatching { media.cancel() }; job?.cancel() }
    }
    override fun onCleared() { direct.cancel(); super.onCleared() }
}
