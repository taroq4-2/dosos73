package com.os73.dosos73

import android.content.Context

internal object LinkPreferences {
    fun autoAnalyzeShared(context: Context) = context.getSharedPreferences("link_privacy", Context.MODE_PRIVATE)
        .getBoolean("auto_analyze_shared", false)

    fun setAutoAnalyzeShared(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences("link_privacy", Context.MODE_PRIVATE).edit()
            .putBoolean("auto_analyze_shared", enabled).commit())
    }
}
