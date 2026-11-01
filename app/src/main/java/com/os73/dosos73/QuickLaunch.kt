package com.os73.dosos73

import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings

/** Foreground handoff only: the OEM detects gestures; this app never monitors sensors. */
internal object QuickLaunch {
    const val ACTION = "com.os73.dosos73.action.COPIED_LINK"
    private const val ENTRY = "com.os73.dosos73.QuickDownloadEntry"
    private fun prefs(context: Context) = context.getSharedPreferences("quick_launch", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun launcherPaste(context: Context) = enabled(context) && prefs(context).getBoolean("launcher_paste", false)
    fun setLauncherPaste(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean("launcher_paste", value).commit())
    }
    fun setEnabled(context: Context, value: Boolean) {
        val previous = enabled(context)
        check(prefs(context).edit().putBoolean("enabled", value).commit())
        try {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, ENTRY),
                if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        } catch (error: RuntimeException) {
            prefs(context).edit().putBoolean("enabled", previous).commit()
            throw error
        }
    }
    fun intent(context: Context) = Intent(context, MainActivity::class.java).setAction(ACTION)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    fun requested(context: Context, intent: Intent?): Boolean = intent != null && (
        intent.action == ACTION || intent.component?.className == ENTRY ||
        (launcherPaste(context) && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)))

    /** One bounded text snapshot. Never coerce URI/Intent items or consult a content provider. */
    fun copiedUrl(context: Context): String {
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            ?: throw IllegalArgumentException("No copied text")
        check(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) != true)
        require(clip.itemCount == 1)
        val item = clip.getItemAt(0)
        require(item.uri == null && item.intent == null)
        val text = item.text ?: throw IllegalArgumentException("No copied text")
        require(text.length in 1..8192)
        return QuickLink.parse(text.toString())
    }
    fun pin(context: Context): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) return false
        return manager.requestPinShortcut(ShortcutInfo.Builder(context, "copied-link")
            .setShortLabel(context.getString(R.string.quick_entry_label))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_quick_download))
            .setIntent(intent(context)).build(), null)
    }
    fun setup(context: Context) {
        // RegiStar is Samsung's official gesture module. No private activity names/settings writes.
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val module = if (samsung) context.packageManager.getLaunchIntentForPackage("com.samsung.android.app.galaxyregistry") else null
        context.startActivity(module ?: Intent(Settings.ACTION_SETTINGS))
    }
}

internal object QuickLink {
    fun parse(text: String): String {
        require(text.length in 1..8192)
        return Safety.shared(text)
    }
}
