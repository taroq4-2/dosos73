package com.os73.dosos73

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalReplayTest {
    @Test fun replayReclaimsOnlyItsOwnStagingAndKeepsSelectedSourceUntouched() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val taskId = UUID.randomUUID().toString()
        val neighborId = UUID.randomUUID().toString()
        val sourceId = "offline-replay-$taskId"
        val source = File(context.cacheDir, "$sourceId.mp4").apply {
            writeBytes(ByteArray(4096) { (it % 251).toByte() })
        }
        val sourceHash = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).toList()
        RecoveryTestDocumentsProvider.entries[sourceId] = RecoveryTestDocumentsProvider.Entry(source.name, source)
        val uri = DocumentsContract.buildDocumentUri("com.os73.dosos73.recovery-tests", sourceId)
        val staging = TransferFiles.directory(context, taskId).apply { mkdirs() }
        val neighbor = TransferFiles.directory(context, neighborId).apply { mkdirs() }
        val neighborFile = File(neighbor, "keep-this-partial").apply { writeText("another task's bytes") }
        val originalState = Transfers.state.value
        try {
            // A sparse file models an interrupted large source copy without consuming 512 MiB.
            RandomAccessFile(File(staging, "offline-input"), "rw").use { it.setLength(512L * 1024 * 1024) }
            File(staging, "offline-audio.mp3").writeText("interrupted output")
            File(staging, "offline-convert.py").writeText("stale trusted-script copy")
            DownloadQueue.initialize(context)
            val waitingBefore = DownloadQueue.tasks.value.toList()
            val activeBefore = DownloadQueue.interrupted(context)
            val failuresBefore = FailureJournal.failures.value.toList()
            val task = DownloadTask(
                Analysis(uri.toString(), "Replay fixture", listOf(OfflineAudio.CHOICE), false),
                OfflineAudio.CHOICE,
                tree = null,
                id = taskId,
                localSource = uri,
            )
            var reachedTransfer = false
            val processor = TransferProcessor(
                context,
                publish = {},
                transfer = { replayed, directory ->
                    reachedTransfer = true
                    assertEquals(task, replayed)
                    assertEquals(staging.canonicalFile, directory.canonicalFile)
                    assertTrue(directory.isDirectory)
                    assertTrue("A replay must not retain any abandoned input, output, or script", directory.listFiles().orEmpty().isEmpty())
                    assertEquals("another task's bytes", neighborFile.readText())
                    assertEquals(sourceHash, MessageDigest.getInstance("SHA-256").digest(source.readBytes()).toList())
                    // Stop before native initialization/publication and avoid failure-history writes.
                    throw CancellationException("Replay regression fixture complete")
                },
                isUserCancelled = { false },
                onComplete = { fail("The fixture must not publish an output") },
            )
            assertEquals(Phase.PAUSED, processor.run(task, "MP3 V0"))
            assertTrue("Replay did not reach the conversion boundary", reachedTransfer)
            assertFalse("Cancelled local staging should be reclaimed", staging.exists())
            assertTrue(source.isFile)
            assertEquals(sourceHash, MessageDigest.getInstance("SHA-256").digest(source.readBytes()).toList())
            assertEquals("another task's bytes", neighborFile.readText())
            assertEquals(waitingBefore, DownloadQueue.tasks.value)
            assertEquals(activeBefore, DownloadQueue.interrupted(context))
            assertEquals(failuresBefore, FailureJournal.failures.value)
        } finally {
            // Only UUID paths and the provider entry created by this test are touched.
            staging.deleteRecursively()
            neighbor.deleteRecursively()
            RecoveryTestDocumentsProvider.entries.remove(sourceId)
            source.delete()
            Transfers.update(originalState)
        }
    }
}
