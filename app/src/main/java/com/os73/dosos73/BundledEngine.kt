package com.os73.dosos73

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Installs only the checksum-pinned asset carried by the signed APK. No network updater. */
object BundledEngine {
    const val SHA256 = "e8665ed64d6b9172773e812f891fb707a0cb1ee8d9ef4fc1acb00cabee4c5256"
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val directory =
            File(context.noBackupFilesDir, "youtubedl-android/yt-dlp").apply {
                check(mkdirs() || isDirectory)
            }
        val target = File(directory, "yt-dlp")
        fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!target.isFile || digest(target) != SHA256) {
            val temp = File(directory, "yt-dlp.installing")
            try {
                context.resources.openRawResource(R.raw.ytdlp).use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                check(digest(temp) == SHA256) { "Bundled engine checksum mismatch" }
                android.system.Os.rename(temp.absolutePath, target.absolutePath)
            } finally {
                temp.delete()
            }
        }
        unpack(directory, target)
        installed = true
    }

    /** The pinned engine as a directory, so Python can keep compiled bytecode between runs. */
    fun unpacked(context: Context) =
        File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/unpacked-${SHA256.take(16)}")

    /** Where Python writes bytecode (PYTHONPYCACHEPREFIX); never inside the source trees. */
    fun bytecodeCache(context: Context) = File(context.noBackupFilesDir, "youtubedl-android/pycache")

    internal const val COMPLETE = ".dosos-unpacked-sha256"

    /** Expands the verified zipapp once. A directory rename publishes it atomically. */
    private fun unpack(directory: File, archive: File) {
        val target = File(directory, "unpacked-${SHA256.take(16)}")
        val marker = File(target, COMPLETE)
        if (marker.isFile && marker.readText() == SHA256) return
        // Older engine versions and interrupted expansions.
        directory.listFiles()?.filter { it.isDirectory && it.name.startsWith("unpacked-") && it != target }
            ?.forEach { it.deleteRecursively() }
        if (target.exists()) check(target.deleteRecursively())
        val staging = File(directory, "unpacking.tmp")
        if (staging.exists()) check(staging.deleteRecursively())
        check(staging.mkdirs())
        val prefix = staging.canonicalPath + File.separator
        try {
            var total = 0L
            // Android's ZipFile rejects data before the first entry, so read the entries in
            // order after the zipapp's "#!" line. The archive's SHA-256 was checked first.
            archive.inputStream().buffered().use { raw ->
                if (raw.read() == '#'.code) {
                    var skipped = 1
                    while (true) {
                        val c = raw.read()
                        require(c >= 0 && ++skipped <= 4096) { "Malformed engine header" }
                        if (c == '\n'.code) break
                    }
                } else {
                    throw IllegalStateException("Unexpected engine header")
                }
                val zip = ZipInputStream(raw)
                var count = 0
                val buffer = ByteArray(65536)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 5000)
                    val name = entry.name
                    require(!name.startsWith("/") && !name.contains('\\') && !name.contains('\u0000') &&
                        name.split('/').none { it == ".." })
                    val out = File(staging, name)
                    require(out.canonicalPath.startsWith(prefix) || out.canonicalPath + File.separator == prefix)
                    if (entry.isDirectory) { check(out.mkdirs() || out.isDirectory); continue }
                    check(out.parentFile!!.mkdirs() || out.parentFile!!.isDirectory)
                    check(!out.exists()) { "Duplicate engine entry" }
                    out.outputStream().use { output ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 64L * 1024 * 1024)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                require(count > 0)
            }
            require(File(staging, "__main__.py").isFile && File(staging, "yt_dlp/__init__.py").isFile)
            File(staging, COMPLETE).writeText(SHA256)
            android.system.Os.rename(staging.absolutePath, target.absolutePath)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }
}
