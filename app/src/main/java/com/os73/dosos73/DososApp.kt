package com.os73.dosos73

import android.app.Application
import java.io.File

class DososApp : Application() {
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            LibraryRepository.clearThumbnailCache()
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        LibraryRepository.clearThumbnailCache()
    }

    override fun onCreate() {
        super.onCreate()
        // Widget follows the download state while this process lives, but only if one is placed:
        // checked off the main thread so app startup never pays for it (measured in Phase F).
        WidgetUpdater.startIfPlaced(this)
        // No extractor, timer, network request or clipboard access during startup.
        // Old private temporary data can be removed after a process death.
        Thread {
                cacheDir
                    .listFiles()
                    ?.filter { it.name.startsWith("transfer-") || it.name.startsWith("analysis-") || it.name.startsWith("native-session-") }
                    ?.filter {
                        System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000
                    }
                    ?.forEach(File::deleteRecursively)
            }
            .start()
    }
}
