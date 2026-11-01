package com.os73.dosos73

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PresetStoreTest {
    private fun withStore(block: (Context, DownloadPresets) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "preset-test-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
        try { block(context, DownloadPresets(context)) } finally { directory.deleteRecursively() }
    }

    @Test fun roundTripUpdateDeleteAndEncryption() = withStore { context, store ->
        val choice = Choice("private-source-id", Kind.VIDEO, "mp4", "720p", expectedHeight = 720)
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        val saved = store.save("Personal secret", choice, tree).single()
        assertEquals(listOf(saved), DownloadPresets(context).read())
        assertEquals(tree, saved.tree)
        assertEquals(choice, saved.match(listOf(choice)))
        val bytes = File(context.filesDir, DownloadPresets.FILE_NAME).readBytes()
        assertTrue(bytes.size <= DownloadPresets.MAX_BYTES)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("Personal secret"))
        val encoded = PrivateJournal(context, DownloadPresets.FILE_NAME, DownloadPresets.MAX_BYTES).readOrNull()!!
        assertFalse(encoded.contains("private-source-id"))
        val replacement = store.save("Personal secret", choice.copy(expectedHeight = 1080, quality = "1080p"), null).single()
        assertEquals(saved.id, replacement.id)
        assertEquals(1080, replacement.expectedHeight)
        assertNull(replacement.tree)
        assertTrue(store.delete(saved.id).isEmpty())
        assertTrue(DownloadPresets(context).read().isEmpty())
    }

    @Test fun twentyPresetBoundPreservesCommittedRowsAndAllowsUpdate() = withStore { context, store ->
        val choice = Choice("direct", Kind.FILE, "zip", "Original")
        repeat(20) { store.save("Preset $it", choice, null) }
        val before = File(context.filesDir, DownloadPresets.FILE_NAME).readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save("Too many", choice, null) }
        assertArrayEquals(before, File(context.filesDir, DownloadPresets.FILE_NAME).readBytes())
        assertEquals(20, store.save("Preset 0", choice.copy(extension = "bin"), null).size)
        assertEquals(20, DownloadPresets(context).read().size)
    }

    @Test fun unknownVersionAndOversizedEncryptedRecordFailWithoutOverwrite() = withStore { context, store ->
        val journal = PrivateJournal(context, DownloadPresets.FILE_NAME, DownloadPresets.MAX_BYTES)
        journal.write(JSONObject().put("version", 99).put("presets", org.json.JSONArray()).toString())
        val file = File(context.filesDir, DownloadPresets.FILE_NAME)
        val before = file.readBytes()
        val choice = Choice("a", Kind.FILE, "zip", "Original")
        assertThrows(IllegalArgumentException::class.java) { store.save("New", choice, null) }
        assertArrayEquals(before, file.readBytes())
        file.writeBytes(ByteArray(DownloadPresets.MAX_BYTES + 1))
        assertThrows(java.io.IOException::class.java) { store.read() }
        assertEquals(DownloadPresets.MAX_BYTES + 1L, file.length())
    }

    @Test fun boundedCodecRejectsDuplicateAndOversizedInputs() {
        val choice = Choice("a", Kind.AUDIO, "mp3", "MP3 V0 · source 128 kbps · ar")
        val rows = listOf(DownloadPresets.fromChoice("Audio", choice, null))
        assertEquals(rows, DownloadPresets.decode(DownloadPresets.encode(rows)))
        assertEquals("MP3 V0", rows.single().quality)
        assertThrows(IllegalArgumentException::class.java) { DownloadPresets.encode(List(21) { rows.single() }) }
        assertThrows(IllegalArgumentException::class.java) { DownloadPresets.decode(" ".repeat(DownloadPresets.MAX_BYTES)) }
        assertThrows(IllegalArgumentException::class.java) { DownloadPresets.encode(rows + rows.single().copy(id = java.util.UUID.randomUUID().toString())) }
    }
}
