package com.os73.dosos73

import java.io.File
import java.nio.file.Files
import java.util.Comparator

internal object NativeStaging {
    /** Keep checkpoint pairs only. Never traverse symlinks while reclaiming staging. */
    fun retainCheckpoints(directory: File) {
        directory.listFiles().orEmpty().forEach { file ->
            val retained = !Files.isSymbolicLink(file.toPath()) && when {
                file.name.endsWith(".part") -> File(file.path + ".resume.json").isFile
                file.name.endsWith(".part.resume.json") -> File(file.path.removeSuffix(".resume.json")).isFile
                else -> false
            }
            if (!retained) Files.walk(file.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
