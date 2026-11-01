package com.os73.dosos73

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Optional receipts for completed network downloads, never proof based on a filename alone. */
internal object DownloadHistory {
    internal const val JOURNAL_NAME = "download-history.json"
    internal const val MAX_BYTES = 256 * 1024
    internal const val MAX_ENTRIES = 1000
    private const val MAX_URI = 8192
    private val lock = Mutex()
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val digestPattern = Regex("[0-9a-f]{64}")
    private data class Receipt(val key: String, val uri: String, val mime: String, val bytes: Long)

    private fun journal(context: Context) = PrivateJournal(context, JOURNAL_NAME, MAX_BYTES)

    // No cached empty fallback: an unreadable/unknown journal must never be overwritten.
    private fun read(context: Context): List<Receipt> {
        val text = journal(context).readOrNull() ?: return emptyList()
        val root = JSONObject(text)
        if (root.getInt("version") != 1) throw IOException("Unsupported download history")
        val rows = root.getJSONArray("entries")
        if (rows.length() > MAX_ENTRIES) throw IOException("Download history exceeds entry limit")
        val entries = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Receipt(row.getString("key"), row.getString("uri"), row.getString("mime"), row.getLong("bytes")).also {
                if (!digestPattern.matches(it.key) || !validUri(it.uri) ||
                    it.mime.length !in 1..255 || it.bytes < 0) throw IOException("Invalid download receipt")
            }
        }
        if (entries.map { it.key }.distinct().size != entries.size) throw IOException("Duplicate download receipt")
        return entries
    }

    private fun write(context: Context, entries: List<Receipt>) {
        val prefix = "{\"version\":1,\"entries\":["
        val suffix = "]}"
        val text = StringBuilder(prefix)
        var bytes = prefix.length + suffix.length
        var count = 0
        for (entry in entries.take(MAX_ENTRIES)) {
            val row = JSONObject().put("key", entry.key).put("uri", entry.uri)
                .put("mime", entry.mime).put("bytes", entry.bytes).toString()
            val added = row.toByteArray(Charsets.UTF_8).size + if (count == 0) 0 else 1
            // PrivateJournal adds a 32-byte authenticated encryption envelope.
            if (bytes + added > MAX_BYTES - 32) break
            if (count++ > 0) text.append(',')
            text.append(row)
            bytes += added
        }
        journal(context).write(text.append(suffix).toString())
    }

    /** Call only after publication has completed successfully. Local conversions have no URL identity. */
    suspend fun record(context: Context, task: DownloadTask, uri: Uri, mime: String, bytes: Long) =
        withContext(Dispatchers.IO) {
            val key = identity(task) ?: return@withContext
            require(validUri(uri.toString()) && mime.length in 1..255 && bytes >= 0)
            lock.withLock {
                val old = read(context)
                write(context, listOf(Receipt(key, uri.toString(), mime, bytes)) + old.filterNot { it.key == key })
            }
        }

    suspend fun find(context: Context, task: DownloadTask): SavedFile? = withContext(Dispatchers.IO) {
        val key = identity(task) ?: return@withContext null
        val receipt = lock.withLock { read(context).firstOrNull { it.key == key } } ?: return@withContext null
        // Library snapshots and provider I/O are deliberately outside the history lock.
        val file = LibraryRepository.files.value.firstOrNull { it.uri == receipt.uri } ?: return@withContext null
        if (file.bytes != receipt.bytes || file.mime != receipt.mime ||
            file.uri in LibraryRepository.hidden(context) || !LibraryPreferences.visibleHistory(context, file))
            return@withContext null
        if (FileAccess.inspect(context, Uri.parse(file.uri)) != FileAvailability.AVAILABLE) return@withContext null
        // Respect a hide/clear/remove/rename that occurred while the provider was queried.
        val stillRecorded = lock.withLock { read(context).any { it.key == key && it.uri == receipt.uri } }
        val current = LibraryRepository.files.value.firstOrNull { it.uri == file.uri }
        if (!stillRecorded || current == null || current.bytes != receipt.bytes || current.mime != receipt.mime ||
            current.uri in LibraryRepository.hidden(context) || !LibraryPreferences.visibleHistory(context, current)) null
        else current
    }

    suspend fun remove(context: Context, uri: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val old = read(context)
            val next = old.filterNot { it.uri == uri }
            if (next != old) write(context, next)
        }
    }

    suspend fun migrate(context: Context, oldUri: String, newUri: String) = withContext(Dispatchers.IO) {
        require(validUri(newUri))
        lock.withLock {
            val old = read(context)
            val next = old.map { if (it.uri == oldUri) it.copy(uri = newUri) else it }
            if (next != old) write(context, next)
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            read(context) // Preserve unreadable/unsupported journals, including during clear.
            write(context, emptyList())
        }
    }

    private fun validUri(value: String): Boolean = value.length in 1..MAX_URI &&
        runCatching { URI(value).let { it.scheme == "content" && !it.authority.isNullOrBlank() } }.getOrDefault(false)

    /** Exact validated URLs retain signed queries; only known YouTube URL forms share an ID. */
    internal fun identity(task: DownloadTask): String? {
        if (task.localSource != null) return null
        val url = runCatching { Safety.url(task.analysis.url) }.getOrNull() ?: return null
        val source = youtubeId(URI(url))?.let { "youtube:$it" } ?: "url:$url"
        val choice = task.choice
        val fields = listOf(source, choice.kind.name, choice.extension.lowercase(Locale.ROOT),
            choice.expectedHeight?.toString().orEmpty(), choice.id, choice.language.orEmpty(),
            choice.bitrateKbps?.toString().orEmpty(), choice.expectsAudio.toString())
        val encoded = fields.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun youtubeId(uri: URI): String? {
        val host = uri.host.lowercase(Locale.ROOT)
        val path = uri.path
        val candidate = when {
            host in setOf("youtu.be", "www.youtu.be") && path.matches(Regex("/[^/]+/?")) -> path.trim('/')
            host in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com") -> when {
                path == "/watch" -> {
                    val values = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "v" }
                    if (values.size != 1) return null
                    runCatching { URLDecoder.decode(values.single().substringAfter('=', ""), "UTF-8") }.getOrNull()
                }
                path.matches(Regex("/shorts/[^/]+/?")) -> path.removePrefix("/shorts/").trimEnd('/')
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { videoId.matches(it) }
    }
}


