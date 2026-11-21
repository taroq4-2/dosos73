package com.os73.dosos73

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language the user chose in dosos73, for surfaces outside the Activity (notifications,
 * home-screen widget). Android 13+ applies per-app locales everywhere; on Android 10–12 only the
 * Activity was localised, so a widget or notification would otherwise use the system language.
 */
internal object DososLocale {
    fun locale(context: Context): Locale {
        if (Build.VERSION.SDK_INT >= 33) {
            val app = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (!app.isEmpty) return app[0]
        }
        val stored = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "ar")!!
        return Locale.forLanguageTag(stored)
    }

    fun wrap(context: Context): Context {
        val locale = locale(context)
        val config = android.content.res.Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(config)
    }
}

/** What dosos73 may show outside the app. Defaults protect the user's privacy. */
internal object SurfacePrivacy {
    private fun prefs(context: Context) = context.getSharedPreferences("privacy_surfaces", Context.MODE_PRIVATE)

    /** File names on the home-screen widget (visible to anyone who sees the phone). Off by default. */
    fun widgetNames(context: Context) = prefs(context).getBoolean("widget_names", false)

    fun setWidgetNames(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean("widget_names", value).commit())
        WidgetUpdater.requestUpdate(context)
    }
}
