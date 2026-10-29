package com.os73.dosos73

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BatchDraftTest {
    private fun isolated(block: (Context) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(base.cacheDir, "draft-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = folder }
        try { block(context) } finally { folder.deleteRecursively() }
    }

    @Test fun freshReaderRestoresAllChoicesSelectionAndDestinationWithoutQueueing() = isolated { context ->
        val first = Choice("a", Kind.VIDEO, "mp4", "720p", 720, true, 1234, language = "ar")
        val chosen = first.copy(id = "b", extension = "webm", quality = "1080p", expectedHeight = 1080)
        val url = "https://example.com/v?token=secret-sentinel"
        val analysis = Analysis(url, "private-title-sentinel", listOf(first, chosen), false, 2000)
        val row = BatchEntry(url, url, true, analysis, choice = chosen,
            destination = "content://provider/tree/folder", destinationOverride = true)
        BatchDraft(context).write(listOf(row, BatchEntry("invalid", null, false, error = R.string.error_url)))
        val bytes = File(context.filesDir, "batch-review.json").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("secret-sentinel"))
        assertFalse(bytes.contains("private-title-sentinel"))
        val restored = BatchDraft(context).read()
        assertEquals(row, restored.first())
        assertEquals(R.string.error_url, restored.last().error)
        BatchDraft(context).write(emptyList())
        assertTrue(BatchDraft(context).read().isEmpty())
    }

    @Test fun corruptDraftIsNotSilentlyReplaced() = isolated { context ->
        BatchDraft(context).write(emptyList())
        val file = File(context.filesDir, "batch-review.json")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(runCatching { BatchDraft(context).read() }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
    }
}
