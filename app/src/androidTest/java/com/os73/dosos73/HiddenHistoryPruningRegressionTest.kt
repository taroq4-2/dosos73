package com.os73.dosos73

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HiddenHistoryPruningRegressionTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolated(block: suspend Fixture.() -> Unit) = runBlocking {
        val prefix = "history-pruning-${UUID.randomUUID()}"
        val folder = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name", mode)
        }
        val fixture = Fixture(context)
        try { fixture.block() }
        finally {
            fixture.uris.forEach { base.contentResolver.delete(it, null, null) }
            base.deleteSharedPreferences("$prefix-library")
            base.deleteSharedPreferences("$prefix-library_controls")
            folder.deleteRecursively()
            LibraryRepository.refresh(base)
        }
    }

    private class Fixture(val context: Context) {
        val uris = mutableListOf<Uri>()
        suspend fun create(): SavedFile {
            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "history-pruning-${UUID.randomUUID()}.bin")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/dosos73/")
            }))
            uris += uri
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write("saved file bytes".toByteArray()) }
            LibraryRepository.remember(context, uri, "application/octet-stream", "fixture.bin", 16)
            return LibraryRepository.files.value.single { it.uri == uri.toString() }
        }

        fun assertContents(item: SavedFile) {
            checkNotNull(context.contentResolver.openInputStream(Uri.parse(item.uri))).use {
                assertEquals("saved file bytes", it.bufferedReader().readText())
            }
        }
    }

    @Test fun deletePrunesOnlyItsTombstoneAcrossRepeatedClearDeleteCycles() = isolated {
        val retained = create()
        LibraryRepository.hideHistory(context, retained)
        repeat(3) {
            val deleted = create()
            LibraryRepository.clearHistory(context)
            assertEquals(setOf(retained.uri, deleted.uri), LibraryRepository.hidden(context))
            LibraryRepository.delete(context, deleted)
            assertEquals(setOf(retained.uri), LibraryRepository.hidden(context))
            assertFalse(LibraryRepository.files.value.any { it.uri == deleted.uri })
            assertContents(retained)
        }
    }

    @Test fun confirmedMissingForgetPrunesOnlyItsTombstone() = isolated {
        val retained = create()
        val missing = create()
        LibraryRepository.clearHistory(context)
        assertTrue(context.contentResolver.delete(Uri.parse(missing.uri), null, null) > 0)
        LibraryRepository.forgetMissing(context, missing)
        assertEquals(setOf(retained.uri), LibraryRepository.hidden(context))
        assertFalse(LibraryRepository.files.value.any { it.uri == missing.uri })
        assertContents(retained)
    }

    @Test fun rejectedForgetOrDeleteKeepsHistoryAndIndexedReference() = isolated {
        val item = create()
        LibraryRepository.hideHistory(context, item)
        assertTrue(runCatching { LibraryRepository.forgetMissing(context, item) }.isFailure)
        assertEquals(setOf(item.uri), LibraryRepository.hidden(context))
        assertContents(item)
        assertTrue(context.contentResolver.delete(Uri.parse(item.uri), null, null) > 0)
        // A failed explicit delete must not masquerade as a successful metadata purge.
        assertTrue(runCatching { LibraryRepository.delete(context, item) }.isFailure)
        assertEquals(setOf(item.uri), LibraryRepository.hidden(context))
        assertTrue(LibraryRepository.files.value.any { it.uri == item.uri })
    }
}
