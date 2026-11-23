package com.os73.dosos73

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Phase C (P1-10): readable names without weakening exact-name publication recovery. */
@RunWith(AndroidJUnit4::class)
class ReadablePublicationNameTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun displayName(uri: android.net.Uri): String =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }

    @Test
    fun sameTitleTwiceGetsNumberedNamesAndEachStillRecovers() = runBlocking {
        val title = "readable-${UUID.randomUUID().toString().take(8)}"
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val tasks = List(2) { DownloadTask(Analysis("https://example.com/f", title, listOf(choice), true), choice, null) }
        val store = OutputStore(context)
        val uris = mutableListOf<android.net.Uri>()
        val sources = tasks.map { File(context.cacheDir, "readable-${it.id}.bin").apply { writeText("content ${it.id}") } }
        try {
            for ((task, source) in tasks.zip(sources)) uris += store.save(source, task).first
            assertEquals("$title.bin", displayName(uris[0]))
            assertEquals("$title (2).bin", displayName(uris[1]))
            // Recovery still resolves each exact planned name to its own document.
            for ((task, uri) in tasks.zip(uris)) assertEquals(uri, store.recover(task)!!.first)
        } finally {
            uris.forEach { context.contentResolver.delete(it, null, null) }
            tasks.forEach { store.forget(it.id) }
            sources.forEach { it.delete() }
        }
    }

    @Test
    fun anotherUnfinishedPlanReservesItsName() = runBlocking {
        val title = "reserved-${UUID.randomUUID().toString().take(8)}"
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val pending = DownloadTask(Analysis("https://example.com/a", title, listOf(choice), true), choice, null)
        val next = DownloadTask(Analysis("https://example.com/b", title, listOf(choice), true), choice, null)
        val store = OutputStore(context)
        val source = File(context.cacheDir, "reserved-${next.id}.bin").apply { writeText("next") }
        var uri: android.net.Uri? = null
        try {
            // A plan that has not created its document yet (e.g. process death before insert).
            val fingerprint = "pending".byteInputStream().use(StreamIntegrity::fingerprint)
            PublicationReceipt(context, pending.id).prepare(null, "application/octet-stream", fingerprint,
                "$title.bin", "bin", emptySet())
            uri = store.save(source, next).first
            assertEquals("$title (2).bin", displayName(uri))
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            store.forget(pending.id)
            store.forget(next.id)
            source.delete()
        }
    }
}
