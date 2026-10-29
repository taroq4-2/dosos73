package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryQueueTest {
    @Test
    fun activeTaskIsDurableUntilExplicitAcknowledgement() = runBlocking {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val task =
            DownloadTask(
                Analysis("https://example.com/recover", "Recovery fixture", listOf(choice), true),
                choice,
                null,
            )
        try {
            DownloadQueue.enqueue(context, task)
            assertEquals(task, DownloadQueue.take(context))
            assertEquals(task, DownloadQueue.interrupted(context))
            val disk =
                org.json.JSONObject(PrivateJournal(context, "waiting-tasks.json").readOrNull()!!)
            assertEquals(task, DownloadQueue.decodeTask(disk.getJSONObject("active")))
            assertFalse(DownloadQueue.tasks.value.any { it.id == task.id })
            // A new service delivery must recover the leased job, not skip to the next one.
            assertEquals(task, DownloadQueue.take(context))
            DownloadQueue.finish(context, task.id)
            assertNull(DownloadQueue.interrupted(context))
            assertTrue(
                org.json
                    .JSONObject(PrivateJournal(context, "waiting-tasks.json").readOrNull()!!)
                    .isNull("active")
            )
        } finally {
            DownloadQueue.finish(context, task.id)
            DownloadQueue.remove(context, task.id)
        }
    }

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
    fun priorityPauseAndReorderSurviveDurableCodec() = runBlocking {
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val normal =
            DownloadTask(
                Analysis("https://example.com/a", "Normal", listOf(choice), true),
                choice,
                null,
            )
        val high =
            normal.copy(id = java.util.UUID.randomUUID().toString(), priority = TaskPriority.HIGH)
        val other = normal.copy(id = java.util.UUID.randomUUID().toString())
        try {
            DownloadQueue.enqueue(context, normal)
            DownloadQueue.enqueue(context, high)
            DownloadQueue.enqueue(context, other)
            DownloadQueue.setPaused(context, high.id, true)
            DownloadQueue.move(context, other.id, -1)
            DownloadQueue.move(context, other.id, -1)
            assertEquals(other.id, DownloadQueue.take(context)?.id)
            DownloadQueue.finish(context, other.id)
            DownloadQueue.setPaused(context, high.id, false)
            assertEquals(high.id, DownloadQueue.take(context)?.id)
            DownloadQueue.setPaused(context, high.id, true)
            assertNull(DownloadQueue.take(context))
            assertFalse("Paused lease must not trigger a service restart loop", DownloadQueue.hasRunnable())
            val disk =
                org.json.JSONObject(PrivateJournal(context, "waiting-tasks.json").readOrNull()!!)
            assertTrue(DownloadQueue.decodeTask(disk.getJSONObject("active")).userPaused)
            DownloadQueue.finish(context, high.id)
            assertEquals(normal.id, DownloadQueue.take(context)?.id)
        } finally {
            for (task in listOf(normal, high, other)) {
                DownloadQueue.finish(context, task.id)
                DownloadQueue.remove(context, task.id)
            }
        }
    }

    @Test
    fun failedQueueWriteDoesNotPublishPhantomTask() = runBlocking {
        DownloadQueue.initialize(context)
        val blocker = File(context.cacheDir, "journal-write-block-${java.util.UUID.randomUUID()}")
        blocker.writeText("not a directory")
        val failing =
            object : android.content.ContextWrapper(context) {
                override fun getFilesDir(): File = blocker
            }
        val choice = Choice("direct", Kind.FILE, "bin", "Original")
        val task =
            DownloadTask(
                Analysis("https://example.com/disk-full", "Write failure", listOf(choice), true),
                choice,
                null,
            )
        val before = DownloadQueue.tasks.value
        try {
            assertTrue(runCatching { DownloadQueue.enqueue(failing, task) }.isFailure)
            assertEquals(before, DownloadQueue.tasks.value)
            assertFalse(DownloadQueue.tasks.value.any { it.id == task.id })
        } finally {
            blocker.delete()
        }
    }

    @Test
    fun retainedTransferPathsRejectTraversal() {
        assertTrue(runCatching { TransferFiles.directory(context, "../../outside") }.isFailure)
        val id = java.util.UUID.randomUUID().toString()
        assertEquals(
            java.io.File(context.noBackupFilesDir, "transfers/$id").canonicalPath,
            TransferFiles.directory(context, id).canonicalPath,
        )
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
