package com.os73.dosos73

import android.content.ContextWrapper
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryStreamingTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun largeLegacyIndexRoundTripsWithoutTruncatingOrOverwritingCorruption() = runBlocking {
        val folder = File(base.cacheDir, "library-stream-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = folder }
        val index = File(folder, "saved-files.json")
        val count = 10000
        val rows = org.json.JSONArray()
        repeat(count) { n -> rows.put(org.json.JSONObject().put("uri", "content://fixture/$n")
            .put("name", "ملف-$n.bin").put("mime", "application/octet-stream").put("bytes", n.toLong()).put("date", n.toLong())) }
        index.writeText(rows.toString())
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            LibraryRepository.refresh(context)
            assertEquals(count, LibraryRepository.files.value.count { it.uri.startsWith("content://fixture/") })
            assertEquals("ملف-9999.bin", LibraryRepository.files.value.first { it.uri == "content://fixture/9999" }.name)
            println("LIBRARY_INDEX rows=$count elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}; legacy metadata retained")
            val before = index.readBytes()
            LibraryRepository.refresh(context)
            assertArrayEquals("Unchanged refresh must not rewrite metadata", before, index.readBytes())
            index.writeText("[{broken")
            val broken = index.readBytes()
            assertTrue(runCatching { LibraryRepository.refresh(context) }.isFailure)
            assertArrayEquals(broken, index.readBytes())
        } finally { folder.deleteRecursively(); LibraryRepository.refresh(base) }
    }
    @Test fun documentRenameUsesReturnedUriAndRetainsCollectionAndContent() = runBlocking {
        RecoveryTestDocumentsProvider.reset()
        val tree = DocumentsContract.buildTreeDocumentUri("com.os73.dosos73.recovery-tests", "root")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val uri = DocumentsContract.createDocument(base.contentResolver, root, "application/octet-stream", "original.bin")!!
        base.contentResolver.openOutputStream(uri)!!.use { it.write("exact renamed content".toByteArray()) }
        LibraryRepository.remember(base, uri, "application/octet-stream", "original.bin", 21)
        val original = LibraryRepository.files.value.first { it.uri == uri.toString() }
        LibraryPreferences.setCollection(base, original.uri, "Rename test")
        var final: SavedFile? = null
        try {
            LibraryRepository.rename(base, original, "renamed.bin")
            final = LibraryRepository.files.value.first { it.name == "renamed.bin" && it.uri != original.uri }
            assertEquals("Rename test", LibraryPreferences.collection(base, final!!.uri))
            base.contentResolver.openInputStream(android.net.Uri.parse(final!!.uri))!!.use {
                assertEquals("exact renamed content", it.bufferedReader().readText())
            }
        } finally {
            final?.let { LibraryPreferences.setCollection(base, it.uri, ""); LibraryRepository.delete(base, it) }
            RecoveryTestDocumentsProvider.reset()
        }
    }
}
