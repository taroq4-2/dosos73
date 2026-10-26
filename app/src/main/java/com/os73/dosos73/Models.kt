package com.os73.dosos73

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Kind {
    VIDEO,
    AUDIO,
    FILE,
}

data class Choice(
    val id: String,
    val kind: Kind,
    val extension: String,
    val quality: String,
    val expectedHeight: Int? = null,
    val expectsAudio: Boolean = false,
)

data class Analysis(
    val url: String,
    val title: String,
    val choices: List<Choice>,
    val direct: Boolean,
    val sizeBytes: Long? = null,
)

data class DownloadTask(
    val analysis: Analysis,
    val choice: Choice,
    val tree: Uri?,
    val id: String = java.util.UUID.randomUUID().toString(),
)

enum class Phase {
    IDLE,
    PAUSED,
    DOWNLOADING,
    PROCESSING,
    SAVING,
    COMPLETE,
    CANCELLED,
    FAILED,
}

data class TransferState(
    val phase: Phase = Phase.IDLE,
    val title: String = "",
    val bytes: Long = 0,
    val total: Long? = null,
    val percent: Float? = null,
    val bytesPerSecond: Long? = null,
    val uri: Uri? = null,
    val mime: String = "application/octet-stream",
    val error: Int? = null,
    val detail: String = "",
) {
    val active
        get() = phase in setOf(Phase.PAUSED, Phase.DOWNLOADING, Phase.PROCESSING, Phase.SAVING)
}

/** Process-local single-flight ownership. Service owns transfer lifetime, never an Activity. */
object Transfers {
    private val mutable = MutableStateFlow(TransferState())
    val state = mutable.asStateFlow()
    @Volatile
    var currentTask: DownloadTask? = null
        private set

    private var queued: DownloadTask? = null

    @Synchronized
    fun reserve(task: DownloadTask): Boolean {
        if (mutable.value.active || queued != null) return false
        currentTask = task
        queued = task
        mutable.value = TransferState(Phase.DOWNLOADING, task.analysis.title)
        return true
    }

    @Synchronized fun take(): DownloadTask? = queued.also { queued = null }

    fun update(value: TransferState) {
        mutable.value = value
    }

    @Synchronized
    fun failStart() {
        queued = null
        mutable.value = TransferState(phase = Phase.FAILED, error = R.string.error_start)
    }
}
