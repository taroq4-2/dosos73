package com.os73.dosos73

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Installs only the checksum-pinned asset carried by the signed APK. No network updater. */
object BundledEngine {
    const val SHA256 = "b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14"
    private var installed = false
    @Synchronized fun install(context: Context) {
        if (installed) return
        val directory = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp").apply { check(mkdirs() || isDirectory) }
        val target = File(directory, "yt-dlp")
        fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!target.isFile || digest(target) != SHA256) {
            val temp = File(directory, "yt-dlp.installing")
            try {
                context.resources.openRawResource(R.raw.ytdlp).use { input -> temp.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
                check(digest(temp) == SHA256) { "Bundled engine checksum mismatch" }
                android.system.Os.rename(temp.absolutePath, target.absolutePath)
            } finally { temp.delete() }
        }
        installed = true
    }
}
