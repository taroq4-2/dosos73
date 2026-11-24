package com.os73.dosos73

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Keeps the metadata of a successful media analysis for a short time, so the download that
 * follows can start from it instead of running a second full extraction.
 *
 * App-private cache only. Entries are keyed by a hash of the link (no link in file names),
 * expire after [TTL_MS], and at most [MAX_ENTRIES] are kept. A stale or unusable entry
 * never fails a download: the engine falls back to a fresh extraction.
 */
internal object EngineInfoCache {
    const val TTL_MS = 30L * 60 * 1000
    const val MAX_BYTES = 4L * 1024 * 1024
    private const val MAX_ENTRIES = 8
    private const val SUFFIX = ".info.json"

    fun directory(cacheRoot: File) = File(cacheRoot, "engine-info")

    internal fun key(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun entry(cacheRoot: File, url: String) = File(directory(cacheRoot), key(url) + SUFFIX)

    /** Copies [info] (written by the analysis process) atomically. Oversized files are skipped. */
    @Synchronized
    fun store(cacheRoot: File, url: String, info: File, now: Long = System.currentTimeMillis()) {
        if (info.length() !in 1..MAX_BYTES) return
        val dir = directory(cacheRoot)
        check(dir.mkdirs() || dir.isDirectory)
        val target = entry(cacheRoot, url)
        val temporary = File.createTempFile(".info-", ".installing", dir)
        try {
            info.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            target.setLastModified(now)
        } finally { temporary.delete() }
        prune(cacheRoot, now)
    }

    /** The cached metadata for [url] if it is fresh enough to be reused, otherwise null. */
    @Synchronized
    fun fresh(cacheRoot: File, url: String, now: Long = System.currentTimeMillis()): File? {
        val file = entry(cacheRoot, url)
        if (!file.isFile) return null
        val age = now - file.lastModified()
        if (age !in 0..TTL_MS || file.length() !in 1..MAX_BYTES) {
            file.delete()
            return null
        }
        return file
    }

    @Synchronized
    fun forget(cacheRoot: File, url: String) { entry(cacheRoot, url).delete() }

    /** Removes expired entries and keeps only the newest [MAX_ENTRIES]. */
    @Synchronized
    fun prune(cacheRoot: File, now: Long = System.currentTimeMillis()) {
        val files = directory(cacheRoot).listFiles() ?: return
        val (cached, other) = files.partition { it.isFile && it.name.endsWith(SUFFIX) }
        // Interrupted copies older than a minute are leftovers from a killed process.
        other.filter { it.isFile && now - it.lastModified() > 60_000 }.forEach { it.delete() }
        val (fresh, stale) = cached.partition { now - it.lastModified() in 0..TTL_MS }
        stale.forEach { it.delete() }
        fresh.sortedByDescending { it.lastModified() }.drop(MAX_ENTRIES).forEach { it.delete() }
    }
}
