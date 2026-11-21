package com.os73.dosos73

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Locale

/** Entry points from outside the app (notifications, widget). Only these page names are honoured. */
internal object AppEntry {
    const val EXTRA_OPEN = "com.os73.dosos73.extra.OPEN"
    const val PAGE_HOME = "home"
    const val PAGE_DOWNLOADS = "downloads"
    private val pages = setOf(PAGE_HOME, PAGE_DOWNLOADS)

    fun page(intent: Intent?): String? = intent?.getStringExtra(EXTRA_OPEN)?.takeIf { it in pages }

    fun pendingIntent(context: Context, page: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java)
                .setAction("com.os73.dosos73.action.OPEN_$page")
                .putExtra(EXTRA_OPEN, page)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** Human text for a transfer: "12 MiB / 48 MiB  •  2 MiB/s  •  0:18 left". Pure, used by notifications. */
internal object TransferText {
    fun progressLine(context: Context, bytes: Long, total: Long?, speed: Long?, locale: Locale): String {
        val parts = mutableListOf<String>()
        parts += if (total != null && total > 0) "${bytesLabel(bytes, locale)} / ${bytesLabel(total, locale)}"
            else bytesLabel(bytes, locale)
        speed?.takeIf { it > 0 }?.let { parts += context.getString(R.string.speed_value, bytesLabel(it, locale)) }
        etaSeconds(bytes, total, speed)?.let { parts += context.getString(R.string.time_left, durationLabel(it)) }
        return isolated(parts, locale)
    }

    /**
     * Mixed Arabic/Latin segments ("MiB/ث", "متبقٍ 0:21") get reordered by the bidi algorithm when
     * simply concatenated. Each segment is isolated so the reading order matches the language.
     */
    fun isolated(parts: List<String>, locale: Locale): String {
        val bidi = androidx.core.text.BidiFormatter.getInstance(locale)
        return parts.joinToString("  •  ") { bidi.unicodeWrap(it) }
    }

    fun etaSeconds(bytes: Long, total: Long?, speed: Long?): Long? {
        val size = total?.takeIf { it > 0 } ?: return null
        val rate = speed?.takeIf { it > 0 } ?: return null
        return ((size - bytes).coerceAtLeast(0) + rate - 1) / rate
    }
}

internal fun durationLabel(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/**
 * All download notifications. Rules:
 *  - "downloads" (low importance, silent) carries the foreground-service progress only while a
 *    transfer runs; "download_results" (default importance) carries completion, failure and paused.
 *  - At most one same-phase progress update per second ([NotificationGate]); never per byte.
 *  - The file name is shown in the shade but never on the lock screen: every notification is
 *    VISIBILITY_PRIVATE with a generic public version.
 *  - Every PendingIntent is explicit and immutable. Actions only pause/resume/cancel through the
 *    same service commands the app uses.
 */
internal object DownloadNotifications {
    const val CHANNEL_PROGRESS = "downloads"
    const val CHANNEL_RESULTS = "download_results"
    // 75 (deferred-start reminder) and 76 (local playback) predate vNext and stay theirs;
    // a shared ID would let one notification replace or cancel the other.
    const val ID_PROGRESS = 73
    const val ID_COMPLETED = 74
    const val ID_PAUSED = 77
    const val ID_FAILED = 78

    fun createChannels(context: Context) {
        val text = DososLocale.wrap(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS,
            text.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
            description = text.getString(R.string.channel_progress_hint)
            setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS,
            text.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = text.getString(R.string.channel_results_hint)
        })
    }

    private fun command(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(context, requestCode,
            Intent(context, DownloadService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun publicVersion(context: Context, channel: String, text: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(context.getString(R.string.brand_name))
            .setContentText(text)
            .build()

    fun progress(service: Context, state: TransferState): Notification {
        val context = DososLocale.wrap(service)
        val locale = DososLocale.locale(service)
        val percent = state.percent?.let { (it * 100).toInt().coerceIn(0, 100) }
        val phase = context.getString(phaseText(state.phase))
        val line = when (state.phase) {
            Phase.DOWNLOADING -> TransferText.progressLine(context, state.bytes, state.total, state.bytesPerSecond, locale)
            else -> phase
        }
        val publicText = if (percent != null) "$phase  ${percentLabel(percent / 100f, locale)}" else phase
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(Transfers.currentTask?.takeIf { it.analysis.title == state.title }
                ?.let { DownloadBoard.displayName(it) } ?: state.title.ifBlank { context.getString(R.string.brand_name) })
            .setContentText(line)
            .setSubText(percent?.let { percentLabel(it / 100f, locale) })
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(AppEntry.pendingIntent(context, AppEntry.PAGE_DOWNLOADS, 70))
            .setOnlyAlertOnce(true)
            .setOngoing(state.active)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CHANNEL_PROGRESS, publicText))
            .apply {
                if (state.phase == Phase.DOWNLOADING)
                    addAction(0, context.getString(R.string.pause), command(service, "pause", 71))
                if (state.active) addAction(0, context.getString(R.string.cancel), command(service, "cancel", 72))
            }
            .build()
    }

    /** Shown after the service has stopped, so a paused task stays one tap from Resume. */
    fun paused(service: Context, state: TransferState) {
        val context = DososLocale.wrap(service)
        val locale = DososLocale.locale(service)
        val percent = state.percent?.let { (it * 100).toInt().coerceIn(0, 100) }
        val line = TransferText.isolated(listOfNotNull(
            context.getString(R.string.status_paused),
            state.total?.let { "${bytesLabel(state.bytes, locale)} / ${bytesLabel(it, locale)}" },
        ), locale)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(state.title.ifBlank { context.getString(R.string.brand_name) })
            .setContentText(line)
            .setProgress(100, percent ?: 0, false)
            .setContentIntent(AppEntry.pendingIntent(context, AppEntry.PAGE_DOWNLOADS, 70))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CHANNEL_RESULTS, context.getString(R.string.status_paused)))
            .addAction(0, context.getString(R.string.resume), command(service, "pause", 73))
            .addAction(0, context.getString(R.string.cancel), command(service, "cancel", 74))
            .build()
        notify(service, ID_PAUSED, notification)
    }

    fun completed(service: Context, state: TransferState) {
        val uri = state.uri ?: return
        val context = DososLocale.wrap(service)
        val openFile = PendingIntent.getActivity(context, 75,
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, state.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(context.getString(R.string.complete))
            .setContentText(state.title)
            .setAutoCancel(true)
            .setContentIntent(openFile)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CHANNEL_RESULTS, context.getString(R.string.complete)))
            .addAction(0, context.getString(R.string.open), openFile)
            .addAction(0, context.getString(R.string.notification_show_downloads),
                AppEntry.pendingIntent(context, AppEntry.PAGE_DOWNLOADS, 76))
            .build()
        cancel(service, ID_PAUSED)
        notify(service, ID_COMPLETED, notification)
    }

    fun failed(service: Context, state: TransferState, reason: Int?) {
        val context = DososLocale.wrap(service)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(context.getString(R.string.failed))
            .setContentText(state.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                listOfNotNull(state.title, reason?.let(context::getString)).joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(AppEntry.pendingIntent(context, AppEntry.PAGE_DOWNLOADS, 77))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CHANNEL_RESULTS, context.getString(R.string.failed)))
            .build()
        cancel(service, ID_PAUSED)
        notify(service, ID_FAILED, notification)
    }

    fun cancel(context: Context, id: Int) = context.getSystemService(NotificationManager::class.java).cancel(id)

    /** No-op when POST_NOTIFICATIONS is denied; downloads never depend on it. */
    private fun notify(context: Context, id: Int, notification: Notification) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }
}
