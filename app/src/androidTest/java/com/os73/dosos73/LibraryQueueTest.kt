package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryQueueTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun queueCodecRetainsSelectedMediaAndDestination() {
        val choice = Choice("137+140", Kind.VIDEO, "mp4", "1080p", 1080, true)
        val task =
            DownloadTask(
                Analysis(
                    "https://example.com/video",
                    "Fixture",
                    listOf(choice),
                    false,
                    3000000000L,
                ),
                choice,
                android.net.Uri.parse("content://example/tree/test"),
            )
        assertEquals(task, DownloadQueue.decodeTask(DownloadQueue.encodeTask(task)))
    }

    @Test
    fun waitingQueueRemovesOnlyTheSelectedTaskAndPreservesOrder() = runBlocking {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val first =
            DownloadTask(
                Analysis("https://example.com/a", "Queue A", listOf(choice), true),
                choice,
                null,
            )
        val second =
            first.copy(
                id = java.util.UUID.randomUUID().toString(),
                analysis = first.analysis.copy(title = "Queue B"),
            )
        try {
            DownloadQueue.enqueue(context, first)
            DownloadQueue.enqueue(context, first)
            DownloadQueue.enqueue(context, second)
            assertEquals(1, DownloadQueue.tasks.value.count { it.id == first.id })
            assertTrue(
                DownloadQueue.tasks.value.indexOfFirst { it.id == first.id } <
                    DownloadQueue.tasks.value.indexOfFirst { it.id == second.id }
            )
            DownloadQueue.remove(context, first.id)
            assertFalse(DownloadQueue.tasks.value.any { it.id == first.id })
            assertTrue(DownloadQueue.tasks.value.any { it.id == second.id })
        } finally {
            DownloadQueue.remove(context, first.id)
            DownloadQueue.remove(context, second.id)
        }
    }

    @Test
    fun renameHistoryRemovalAndDeleteHaveDistinctEffects() = runBlocking {
        val file =
            File(context.cacheDir, "library-fixture.bin").apply {
                writeText("library fixture exact bytes")
            }
        var item: SavedFile? = null
        try {
            val choice = Choice("direct", Kind.FILE, "bin", "Original")
            val task =
                DownloadTask(
                    Analysis("https://example.com/a", "library-fixture", listOf(choice), true),
                    choice,
                    null,
                )
            val uri = OutputStore(context).save(file, task).first
            item = LibraryRepository.files.value.first { it.uri == uri.toString() }
            LibraryRepository.rename(context, item!!, "renamed-fixture.bin")
            item = LibraryRepository.files.value.first { it.uri == uri.toString() }
            val saved = item!!
            assertEquals("renamed-fixture.bin", saved.name)
            assertTrue(
                runCatching { LibraryRepository.rename(context, saved, "../invalid") }.isFailure
            )
            LibraryRepository.hideHistory(context, saved)
            assertTrue(saved.uri in LibraryRepository.hidden(context))
            assertEquals(
                file.readText(),
                context.contentResolver.openInputStream(uri)!!.bufferedReader().use {
                    it.readText()
                },
            )
            LibraryRepository.refresh(context)
            assertTrue(LibraryRepository.files.value.any { it.uri == saved.uri })
            LibraryRepository.delete(context, saved)
            assertFalse(LibraryRepository.files.value.any { it.uri == saved.uri })
            item = null
        } finally {
            item?.let { runCatching { LibraryRepository.delete(context, it) } }
            file.delete()
        }
    }
}
