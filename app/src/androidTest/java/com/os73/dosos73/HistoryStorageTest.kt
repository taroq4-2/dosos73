package com.os73.dosos73

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoryStorageTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun task(url: String = "https://example.org/video?token=secret") : DownloadTask {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        return DownloadTask(Analysis(url, "history.bin", listOf(choice), true), choice, null)
    }

    private fun isolated(block: suspend Fixture.() -> Unit) = runBlocking {
        val prefix = "download-history-${UUID.randomUUID()}"
        val folder = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name", mode)
        }
        val fixture = Fixture(context)
        try { fixture.block() }
        finally {
            PrivateJournal.beforeWriteForTests = null
            fixture.uris.forEach { base.contentResolver.delete(it, null, null) }
            base.deleteSharedPreferences("$prefix-library")
            base.deleteSharedPreferences("$prefix-library_controls")
            folder.deleteRecursively()
            LibraryRepository.refresh(base)
        }
    }

    private class Fixture(val context: Context) {
        val uris = mutableListOf<Uri>()
        val disk get() = File(context.filesDir, DownloadHistory.JOURNAL_NAME)
        val journal get() = PrivateJournal(context, DownloadHistory.JOURNAL_NAME, DownloadHistory.MAX_BYTES)
        suspend fun create(): SavedFile {
            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "history-${UUID.randomUUID()}.bin")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/dosos73/")
            }))
            uris += uri
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(byteArrayOf(1, 2, 3)) }
            LibraryRepository.remember(context, uri, "application/octet-stream", "history.bin", 3)
            return LibraryRepository.files.value.single { it.uri == uri.toString() }
        }
        fun rows() = JSONObject(checkNotNull(journal.readOrNull())).getJSONArray("entries")
    }

    @Test fun encryptedVersionedStorageAndClearLeaveSavedFilesIntact() = isolated {
        val saved = create()
        DownloadHistory.record(context, task(), Uri.parse(saved.uri), saved.mime, saved.bytes)
        assertEquals(saved, DownloadHistory.find(context, task()))
        assertEquals(1, JSONObject(checkNotNull(journal.readOrNull())).getInt("version"))
        assertFalse(disk.readText().contains("secret"))
        assertFalse(checkNotNull(journal.readOrNull()).contains("token=secret"))
        DownloadHistory.clear(context)
        assertNull(DownloadHistory.find(context, task()))
        checkNotNull(context.contentResolver.openInputStream(Uri.parse(saved.uri))).use { assertEquals(1, it.read()) }
    }

    @Test fun hiddenMissingUnindexedAndRemovedFilesNeverWarn() = isolated {
        val saved = create()
        DownloadHistory.record(context, task(), Uri.parse(saved.uri), saved.mime, saved.bytes)
        LibraryRepository.hideHistory(context, saved)
        assertNull(DownloadHistory.find(context, task()))
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        assertNull(DownloadHistory.find(context, task())) // Hiding also forgets the source receipt.
        DownloadHistory.record(context, task(), Uri.parse(saved.uri), saved.mime, saved.bytes)
        assertNotNull(DownloadHistory.find(context, task()))
        DownloadHistory.remove(context, saved.uri)
        assertNull(DownloadHistory.find(context, task()))
        DownloadHistory.record(context, task(), Uri.parse(saved.uri), saved.mime, saved.bytes)
        check(context.contentResolver.delete(Uri.parse(saved.uri), null, null) > 0)
        assertNull(DownloadHistory.find(context, task()))
        DownloadHistory.record(context, task(), Uri.parse("content://unindexed.example/file"), saved.mime, 3)
        assertNull(DownloadHistory.find(context, task()))
    }

    @Test fun changedLibrarySizeOrMimeDoesNotMatchTheReceipt() = isolated {
        val saved = create()
        val uri = Uri.parse(saved.uri)
        DownloadHistory.record(context, task(), uri, saved.mime, saved.bytes)
        LibraryRepository.remember(context, uri, saved.mime, saved.name, saved.bytes + 1)
        assertNull(DownloadHistory.find(context, task()))
        LibraryRepository.remember(context, uri, "audio/mpeg", saved.name, saved.bytes)
        assertNull(DownloadHistory.find(context, task()))
        LibraryRepository.remember(context, uri, saved.mime, saved.name, saved.bytes)
        assertNotNull(DownloadHistory.find(context, task()))
    }

    @Test fun retentionAndUriMigrationUseCurrentLibraryMetadata() = isolated {
        val old = create()
        val next = create()
        DownloadHistory.record(context, task(), Uri.parse(old.uri), old.mime, old.bytes)
        DownloadHistory.migrate(context, old.uri, next.uri)
        assertEquals(next, DownloadHistory.find(context, task()))
        // A future cutoff excludes every current row, exercising the preference gate.
        context.getSharedPreferences("library_controls", Context.MODE_PRIVATE).edit().putInt("history_days", -1).commit()
        assertNull(DownloadHistory.find(context, task()))
        context.getSharedPreferences("library_controls", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadHistory.remove(context, old.uri)
        assertEquals(next, DownloadHistory.find(context, task()))
        DownloadHistory.remove(context, next.uri)
        assertNull(DownloadHistory.find(context, task()))
    }

    @Test fun countAndEncryptedByteBoundsEvictOldestReceipts() = isolated {
        val seeded = JSONArray()
        repeat(1000) { index -> seeded.put(JSONObject().put("key", "%064x".format(index))
            .put("uri", "content://history.example/$index").put("mime", "application/octet-stream").put("bytes", 1)) }
        journal.write(JSONObject().put("version", 1).put("entries", seeded).toString())
        DownloadHistory.record(context, task(), Uri.parse("content://history.example/new"), "application/octet-stream", 1)
        assertEquals(1000, rows().length())
        assertEquals("content://history.example/new", rows().getJSONObject(0).getString("uri"))
        DownloadHistory.clear(context)
        repeat(45) { index ->
            val largeUri = Uri.parse("content://history.example/$index/" + "س".repeat(3900))
            DownloadHistory.record(context, task("https://example.org/$index"), largeUri, "application/octet-stream", 1)
            assertTrue(disk.length() <= DownloadHistory.MAX_BYTES)
        }
        assertTrue(rows().length() in 1..44)
        assertTrue(rows().getJSONObject(0).getString("uri").startsWith("content://history.example/44/"))
    }

    @Test fun corruptionUnknownVersionsAndWriteFailuresPreservePriorJournal() = isolated {
        DownloadHistory.record(context, task(), Uri.parse("content://history.example/one"), "application/octet-stream", 1)
        val before = disk.readBytes()
        PrivateJournal.beforeWriteForTests = { file -> if (file.name == DownloadHistory.JOURNAL_NAME) throw IOException("injected") }
        assertTrue(runCatching { DownloadHistory.record(context, task("https://example.org/other"), Uri.parse("content://history.example/two"), "application/octet-stream", 1) }.isFailure)
        assertArrayEquals(before, disk.readBytes())
        PrivateJournal.beforeWriteForTests = null
        journal.write("{\"version\":999,\"entries\":[]}")
        val future = disk.readBytes()
        assertTrue(runCatching { DownloadHistory.clear(context) }.isFailure)
        assertArrayEquals(future, disk.readBytes())
        val corrupt = future.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        disk.writeBytes(corrupt)
        assertTrue(runCatching { DownloadHistory.record(context, task(), Uri.parse("content://history.example/three"), "application/octet-stream", 1) }.isFailure)
        assertArrayEquals(corrupt, disk.readBytes())
    }

    @Test fun localConversionsAreExcluded() = isolated {
        val conversion = task().copy(localSource = Uri.parse("content://history.example/source"))
        DownloadHistory.record(context, conversion, Uri.parse("content://history.example/output"), "audio/mpeg", 3)
        assertNull(DownloadHistory.find(context, conversion))
        assertFalse(disk.exists())
    }
}


