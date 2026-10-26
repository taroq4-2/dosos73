package com.os73.dosos73

import android.app.Application
import java.io.File

class DososApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // No extractor, timer, network request or clipboard access during startup.
        // Old private temporary data can be removed after a process death.
        Thread {
                cacheDir
                    .listFiles()
                    ?.filter { it.name.startsWith("transfer-") || it.name.startsWith("analysis-") }
                    ?.filter {
                        System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000
                    }
                    ?.forEach(File::deleteRecursively)
            }
            .start()
    }
}
