package com.os73.dosos73

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileNotFoundException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Expands the signed APK's shared, architecture-independent files once, offline.
 * The ABI ZIP retains all native code and symlinks. A missing completion marker
 * causes an interrupted installation to be retried before any Python executes.
 */
internal object PythonCommonRuntime {
    private const val ASSET = "dosos73/python-common"
    internal fun install(context: Context, root: File = File(context.noBackupFilesDir, "youtubedl-android/packages/python")) {
        val metadata = try {
            context.assets.open("$ASSET.json").use { input ->
                val bytes = input.readBytesLimited(512 * 1024)
                JSONObject(bytes.toString(Charsets.UTF_8))
            }
        } catch (_: FileNotFoundException) { return } // Ordinary, untransformed developer APK.
        require(metadata.getInt("version") == 1)
        val expected = metadata.getString("sha256")
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val files = metadata.getJSONObject("files")
        require(files.length() in 1..5000)
        check(root.isDirectory)
        val marker = File(root, ".dosos-common-sha256")
        if (marker.isFile && marker.length() == 64L && marker.readText() == expected) return
        val prefix = root.canonicalPath + File.separator
        val seen = HashSet<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        context.assets.open("$ASSET.zip").use { raw ->
            DigestInputStream(raw, digest).use { checked ->
                val zip = ZipInputStream(checked)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(name.startsWith("usr/") && !name.contains('\\') && !entry.isDirectory && seen.add(name))
                    require(files.has(name))
                    val spec = files.getJSONObject(name)
                    val size = spec.getLong("bytes")
                    require(size in 0..(32L * 1024 * 1024))
                    val target = File(root, name)
                    require(target.canonicalPath.startsWith(prefix))
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    // Recheck after directory creation; never follow a pre-existing symlink outside root.
                    require(target.canonicalPath.startsWith(prefix))
                    val temporary = File.createTempFile(".common-", ".installing", target.parentFile)
                    try {
                        val fileDigest = MessageDigest.getInstance("SHA-256")
                        var copied = 0L
                        temporary.outputStream().use { output ->
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                val count = zip.read(buffer)
                                if (count < 0) break
                                copied += count
                                require(copied <= size)
                                fileDigest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                        check(copied == size && hex(fileDigest.digest()) == spec.getString("sha256"))
                        val mode = spec.getInt("mode")
                        require(mode in 0..511)
                        Os.chmod(temporary.absolutePath, mode)
                        Os.rename(temporary.absolutePath, target.absolutePath)
                    } finally { temporary.delete() }
                    zip.closeEntry()
                }
                // ZIP parsing may stop before the central directory; include it in the asset digest.
                while (checked.read(buffer) >= 0) { }
            }
        }
        check(seen.size == files.length() && hex(digest.digest()) == expected)
        val completion = File(root, ".dosos-common-installing")
        try {
            completion.outputStream().use { output -> output.write(expected.toByteArray(Charsets.US_ASCII)); output.fd.sync() }
            Os.rename(completion.absolutePath, marker.absolutePath)
        } finally { completion.delete() }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(result.size() + count <= limit)
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
