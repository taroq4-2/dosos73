package com.os73.dosos73

import android.content.Context
import java.io.File
import android.system.Os
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** One initialization barrier for every native invocation and acceptance test. */
internal object EngineRuntime {
    private var ready = false
    private val preparation = RuntimePreparation()
    @Synchronized fun initialize(context: Context) {
        if (ready) return
        // The upstream wrapper identifies extracted Python by ZIP size only. An APK
        // update can replace native files without changing that size. Invalidate only
        // our installed runtime cache once per install; user downloads are elsewhere.
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        check(base.mkdirs() || base.isDirectory)
        val marker = File(base, ".dosos-runtime-install")
        val identity = context.applicationInfo.sourceDir
        preparation.prepare(identity) {
            if (!marker.isFile || marker.readText() != identity) {
                for (name in listOf("python", "ffmpeg")) {
                    val cached = File(base, "packages/$name")
                    require(cached.canonicalPath.startsWith(base.canonicalPath + File.separator))
                    check(!cached.exists() || cached.deleteRecursively())
                }
            }
        }
        // Upstream singletons cannot re-extract after successful init. Retrying a
        // later stage must retain their files even before the durable marker exists.
        BundledEngine.install(context)
        YoutubeDL.getInstance().init(context)
        PythonCommonRuntime.install(context)
        FFmpeg.getInstance().init(context)
        val temporary = File(base, ".dosos-runtime-installing")
        try {
            temporary.outputStream().use { it.write(identity.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Os.rename(temporary.absolutePath, marker.absolutePath)
        } finally { temporary.delete() }
        ready = true
    }
}
