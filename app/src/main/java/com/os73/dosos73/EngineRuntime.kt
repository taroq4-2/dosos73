package com.os73.dosos73

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** One initialization barrier for every native invocation and acceptance test. */
internal object EngineRuntime {
    private var ready = false
    @Synchronized fun initialize(context: Context) {
        if (ready) return
        BundledEngine.install(context)
        YoutubeDL.getInstance().init(context)
        PythonCommonRuntime.install(context)
        FFmpeg.getInstance().init(context)
        ready = true
    }
}
