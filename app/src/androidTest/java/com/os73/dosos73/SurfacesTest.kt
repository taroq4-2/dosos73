package com.os73.dosos73

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Phase E on a real device: widget RemoteViews, notification content/privacy, entry points. */
@RunWith(AndroidJUnit4::class)
class SurfacesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun inflate(views: android.widget.RemoteViews): View =
        views.apply(context, FrameLayout(context))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun activeWidgetShowsRealProgressAndHidesTheName() {
        val model = WidgetModel(WidgetState.ACTIVE, null, FileCategory.APP, 400L * 1024 * 1024, 1000L * 1024 * 1024, 40,
            2L * 1024 * 1024, pending = 1)
        val view = inflate(WidgetUpdater.full(DososLocale.wrap(context), model, Locale.ENGLISH))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_file_row).visibility)
        // Privacy default: the type, not the file name.
        assertEquals(DososLocale.wrap(context).getString(R.string.widget_kind_app), view.text(R.id.widget_name))
        assertEquals(40, view.findViewById<ProgressBar>(R.id.widget_progress).progress)
        assertTrue(view.text(R.id.widget_percent).contains("40"))
        assertTrue(view.text(R.id.widget_sizes).contains("/"))
        assertTrue(view.text(R.id.widget_speed).isNotEmpty())
    }

    @Test
    fun idleWidgetHasNoFakeProgressAndBothButtonsOpenTheApp() {
        val view = inflate(WidgetUpdater.full(context, WidgetModel(WidgetState.IDLE, null), Locale.ENGLISH))
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_file_row).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_progress_row).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_empty).visibility)
        assertTrue(view.findViewById<View>(R.id.widget_new).hasOnClickListeners())
        assertTrue(view.findViewById<View>(R.id.widget_list).hasOnClickListeners())
        assertTrue(view.findViewById<View>(R.id.widget_root).hasOnClickListeners())
    }

    @Test
    fun widgetFollowsTheAppLanguageDirection() {
        val arabic = Locale.forLanguageTag("ar")
        val rtl = inflate(WidgetUpdater.full(context, WidgetModel(WidgetState.PAUSED, "x.apk", FileCategory.APP,
            1, 2, 50), arabic))
        assertEquals(View.LAYOUT_DIRECTION_RTL, rtl.findViewById<View>(R.id.widget_root).layoutDirection)
        val ltr = inflate(WidgetUpdater.compact(context, WidgetModel(WidgetState.ACTIVE, null, percent = 10), Locale.ENGLISH))
        assertEquals(View.LAYOUT_DIRECTION_LTR, ltr.findViewById<View>(R.id.widget_root).layoutDirection)
        assertEquals(10, ltr.findViewById<ProgressBar>(R.id.widget_progress).progress)
    }

    @Test
    fun progressNotificationHasRealContentActionsAndALockScreenSafeVersion() {
        DownloadNotifications.createChannels(context)
        val state = TransferState(Phase.DOWNLOADING, "private-name.apk", 5L * 1024 * 1024, 10L * 1024 * 1024, 0.5f,
            1024L * 1024)
        val n = DownloadNotifications.progress(context, state)
        assertEquals(DownloadNotifications.CHANNEL_PROGRESS, n.channelId)
        assertEquals("private-name.apk", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.contains("/") && text.contains("•"))
        assertEquals(50, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(2, n.actions.size) // Pause + Cancel
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(n.actions.all { it.actionIntent.isImmutable })
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion!!
        assertFalse("lock screen must not show the file name",
            public.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("private-name"))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun pausedAndFailedNotificationsArePostedAndReplaceEachOther() {
        DownloadNotifications.createChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        // Notifications are a no-op without permission; the test only asserts content when allowed.
        org.junit.Assume.assumeTrue(manager.areNotificationsEnabled())
        DownloadNotifications.paused(context, TransferState(Phase.PAUSED, "p.zip", 3, 10, 0.3f))
        val paused = manager.activeNotifications.single { it.id == DownloadNotifications.ID_PAUSED }.notification
        val text = DososLocale.wrap(context)
        assertEquals(listOf(text.getString(R.string.resume), text.getString(R.string.cancel)),
            paused.actions.map { it.title.toString() })
        DownloadNotifications.failed(context, TransferState(Phase.FAILED, "p.zip", error = R.string.error_http404),
            R.string.error_http404)
        assertTrue(manager.activeNotifications.none { it.id == DownloadNotifications.ID_PAUSED })
        assertTrue(manager.activeNotifications.any { it.id == DownloadNotifications.ID_FAILED })
        manager.cancel(DownloadNotifications.ID_FAILED)
    }

    @Test
    fun onlyKnownPagesAreAcceptedFromOutside() {
        fun page(value: String) = AppEntry.page(Intent().putExtra(AppEntry.EXTRA_OPEN, value))
        assertEquals("downloads", page("downloads"))
        assertEquals("home", page("home"))
        for (value in listOf("settings", "privacy", "batch", "../home", "")) assertNull(value, page(value))
        assertNull(AppEntry.page(Intent()))
    }
}
