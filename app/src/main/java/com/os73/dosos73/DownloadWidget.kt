package com.os73.dosos73

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.util.Locale
import androidx.core.text.layoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class WidgetState { IDLE, ACTIVE, PAUSED, QUEUED, COMPLETED, FAILED }

/**
 * Everything the widget shows, derived from the same [DownloadBoard] as the app. [title] is null
 * unless the user allowed file names on the home screen (off by default): a home screen is
 * visible to anyone holding the phone.
 */
internal data class WidgetModel(
    val state: WidgetState,
    val title: String?,
    val category: FileCategory = FileCategory.OTHER,
    val bytes: Long? = null,
    val total: Long? = null,
    /** Whole percent 0..100, or null when the size is unknown. */
    val percent: Int? = null,
    /** Rounded to 64 KiB/s so tiny speed jitter does not redraw the widget. */
    val bytesPerSecond: Long? = null,
    val error: Int? = null,
    /** Running + waiting + paused tasks. */
    val pending: Int = 0,
) {
    companion object {
        fun from(items: List<BoardItem>, last: TransferState, showNames: Boolean): WidgetModel {
            val pending = items.count { it.status in setOf(BoardStatus.ACTIVE, BoardStatus.QUEUED, BoardStatus.PAUSED) }
            val focus = DownloadBoard.summarize(items).focus
                ?: items.firstOrNull { it.status == BoardStatus.PAUSED }
            if (focus != null) {
                val state = when (focus.status) {
                    BoardStatus.ACTIVE -> WidgetState.ACTIVE
                    BoardStatus.PAUSED -> WidgetState.PAUSED
                    else -> WidgetState.QUEUED
                }
                val total = focus.total
                val percent = focus.percent?.let { (it * 100).toInt().coerceIn(0, 100) }
                    ?: if (focus.bytes != null && total != null && total > 0) ((focus.bytes * 100) / total).toInt() else null
                return WidgetModel(
                    state, focus.title.takeIf { showNames }, focus.category, focus.bytes, total, percent,
                    focus.bytesPerSecond?.let { (it / 65_536) * 65_536 }?.takeIf { state == WidgetState.ACTIVE },
                    pending = pending,
                )
            }
            // Nothing running: report the outcome of the last transfer this process saw.
            return when (last.phase) {
                Phase.COMPLETE -> WidgetModel(WidgetState.COMPLETED, last.title.takeIf { showNames && it.isNotBlank() },
                    FileTypes.forMime(last.mime)?.category ?: FileCategory.OTHER, last.total, last.total, 100)
                Phase.FAILED -> WidgetModel(WidgetState.FAILED, last.title.takeIf { showNames && it.isNotBlank() },
                    error = last.error)
                else -> WidgetModel(WidgetState.IDLE, null)
            }
        }
    }
}

/** Home-screen widget (reference: right phone). No polling: it is redrawn by [WidgetUpdater]. */
class DownloadWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Added, resized, restored after reboot or launcher restart: the process may be cold, so
        // load the durable queue/failure state first (bounded), then draw from the real board.
        val pending = goAsync()
        WidgetUpdater.scope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    runCatching { DownloadQueue.initialize(context) }
                    runCatching { FailureJournal.initialize(context) }
                }
                WidgetUpdater.render(context, WidgetUpdater.snapshot(context), ids)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetUpdater.render(context, WidgetUpdater.snapshot(context), intArrayOf(id))
    }
}

internal object WidgetUpdater {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    @Volatile private var started = false

    /** Called once per process from [DososApp]. Observes; never wakes the device on its own. */
    fun start(app: Context) {
        if (started) return
        started = true
        scope.launch {
            combine(DownloadBoard.observe { TransferFiles.checkpoint(app, it) }, Transfers.state,
                requests.onStart { emit(Unit) }) { items, last, _ ->
                WidgetModel.from(items, last, SurfacePrivacy.widgetNames(app))
            }.distinctUntilChanged().conflate().collect { model ->
                render(app, model)
                // Coalesce progress: at most one redraw every 1.5 s while a transfer runs.
                if (model.state == WidgetState.ACTIVE) delay(1_500)
            }
        }
    }

    fun requestUpdate(context: Context) {
        if (!started) start(context.applicationContext)
        requests.tryEmit(Unit)
    }

    fun snapshot(context: Context): WidgetModel = WidgetModel.from(
        DownloadBoard.build(Transfers.state.value, Transfers.currentTask, DownloadQueue.tasks.value,
            FailureJournal.failures.value, LibraryRepository.files.value, DownloadQueue.leased.value,
            DownloadQueue.leased.value?.let { TransferFiles.checkpoint(context, it) }),
        Transfers.state.value, SurfacePrivacy.widgetNames(context),
    )

    fun render(context: Context, model: WidgetModel, only: IntArray? = null) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = only ?: manager.getAppWidgetIds(ComponentName(context, DownloadWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val localized = DososLocale.wrap(context)
        val locale = DososLocale.locale(context)
        for (id in ids) {
            val views = if (Build.VERSION.SDK_INT >= 31)
                RemoteViews(mapOf(
                    SizeF(110f, 40f) to compact(localized, model, locale),
                    SizeF(240f, 100f) to full(localized, model, locale),
                ))
            else {
                val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                if (width < 240) compact(localized, model, locale) else full(localized, model, locale)
            }
            runCatching { manager.updateAppWidget(id, views) }
        }
    }

    private fun statusText(context: Context, model: WidgetModel): String = context.getString(when (model.state) {
        WidgetState.IDLE -> R.string.widget_idle
        WidgetState.ACTIVE -> R.string.widget_active
        WidgetState.PAUSED -> R.string.status_paused
        WidgetState.QUEUED -> R.string.status_queued
        WidgetState.COMPLETED -> R.string.status_completed
        WidgetState.FAILED -> R.string.status_failed
    })

    /** The name if allowed, otherwise a neutral description such as "Android package". */
    private fun label(context: Context, model: WidgetModel): String = model.title ?: context.getString(when (model.category) {
        FileCategory.VIDEO -> R.string.widget_kind_video
        FileCategory.AUDIO -> R.string.widget_kind_audio
        FileCategory.APP -> R.string.widget_kind_app
        FileCategory.ARCHIVE -> R.string.widget_kind_archive
        FileCategory.DOCUMENT -> R.string.widget_kind_document
        FileCategory.IMAGE -> R.string.widget_kind_image
        FileCategory.PROGRAM -> R.string.widget_kind_program
        FileCategory.OTHER -> R.string.widget_kind_file
    })

    private fun tile(category: FileCategory) = when (category) {
        FileCategory.VIDEO -> R.drawable.widget_tile_video
        FileCategory.AUDIO -> R.drawable.widget_tile_audio
        FileCategory.APP -> R.drawable.widget_tile_app
        FileCategory.ARCHIVE -> R.drawable.widget_tile_archive
        FileCategory.DOCUMENT -> R.drawable.widget_tile_document
        else -> R.drawable.widget_tile_other
    }

    private fun direction(locale: Locale) =
        if (locale.layoutDirection == View.LAYOUT_DIRECTION_RTL) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

    internal fun full(context: Context, model: WidgetModel, locale: Locale): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_download).apply {
            setInt(R.id.widget_root, "setLayoutDirection", direction(locale))
            setTextViewText(R.id.widget_status, statusText(context, model))
            // Layout text would resolve in the launcher's language; use the app's language instead.
            setTextViewText(R.id.widget_new, context.getString(R.string.new_download))
            setTextViewText(R.id.widget_list, context.getString(R.string.widget_view_downloads))
            setOnClickPendingIntent(R.id.widget_root, AppEntry.pendingIntent(context, AppEntry.PAGE_DOWNLOADS, 80))
            setOnClickPendingIntent(R.id.widget_new, AppEntry.pendingIntent(context, AppEntry.PAGE_HOME, 81))
            setOnClickPendingIntent(R.id.widget_list, AppEntry.pendingIntent(context, AppEntry.PAGE_DOWNLOADS, 82))
            val hasFile = model.state != WidgetState.IDLE
            setViewVisibility(R.id.widget_file_row, if (hasFile) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_empty, if (hasFile) View.GONE else View.VISIBLE)
            if (!hasFile) {
                setTextViewText(R.id.widget_empty, context.getString(R.string.widget_idle_hint))
                setViewVisibility(R.id.widget_progress_row, View.GONE)
                setViewVisibility(R.id.widget_meta_row, View.GONE)
                return@apply
            }
            setTextViewText(R.id.widget_name, label(context, model))
            setImageViewResource(R.id.widget_tile, tile(model.category))
            val showProgress = model.state in setOf(WidgetState.ACTIVE, WidgetState.PAUSED, WidgetState.QUEUED)
            setViewVisibility(R.id.widget_progress_row, if (showProgress) View.VISIBLE else View.GONE)
            if (showProgress) {
                setProgressBar(R.id.widget_progress, 100, model.percent ?: 0,
                    model.percent == null && model.state == WidgetState.ACTIVE)
                setTextViewText(R.id.widget_percent, model.percent?.let { percentLabel(it / 100f, locale) } ?: "")
            }
            val sizes = when {
                model.bytes != null && model.total != null && model.state != WidgetState.COMPLETED ->
                    "${bytesLabel(model.bytes, locale)} / ${bytesLabel(model.total, locale)}"
                model.total != null -> bytesLabel(model.total, locale)
                else -> null
            }
            val detail = when (model.state) {
                WidgetState.FAILED -> model.error?.let(context::getString)
                else -> model.bytesPerSecond?.let { context.getString(R.string.speed_value, bytesLabel(it, locale)) }
            }
            setViewVisibility(R.id.widget_meta_row, if (sizes != null || detail != null) View.VISIBLE else View.GONE)
            val bidi = androidx.core.text.BidiFormatter.getInstance(locale)
            setTextViewText(R.id.widget_sizes, sizes?.let { bidi.unicodeWrap(it) } ?: "")
            setTextViewText(R.id.widget_speed, detail?.let { bidi.unicodeWrap(it) } ?: "")
        }

    internal fun compact(context: Context, model: WidgetModel, locale: Locale): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_download_small).apply {
            setInt(R.id.widget_root, "setLayoutDirection", direction(locale))
            setOnClickPendingIntent(R.id.widget_root, AppEntry.pendingIntent(context, AppEntry.PAGE_DOWNLOADS, 83))
            val percent = model.percent?.takeIf { model.state in setOf(WidgetState.ACTIVE, WidgetState.PAUSED) }
            setTextViewText(R.id.widget_status, statusText(context, model))
            setTextViewText(R.id.widget_percent, percent?.let { percentLabel(it / 100f, locale) } ?: "")
            setViewVisibility(R.id.widget_progress, if (model.state in setOf(WidgetState.ACTIVE, WidgetState.PAUSED)) View.VISIBLE else View.GONE)
            setProgressBar(R.id.widget_progress, 100, percent ?: 0, percent == null && model.state == WidgetState.ACTIVE)
        }
}
