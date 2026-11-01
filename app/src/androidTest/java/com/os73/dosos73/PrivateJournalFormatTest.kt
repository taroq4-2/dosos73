package com.os73.dosos73

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PrivateJournalFormatTest {
    private fun isolated(block: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "journal-format-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = folder }
        try { block(context) }
        finally {
            PrivateJournal.beforeWriteForTests = null
            PrivateJournal.beforeFormatWriteForTests = null
            folder.deleteRecursively()
        }
    }

    @Test fun bothHistoricalJournalsMigrateOnceAndFreshInstancesRejectPlaintextReplacement() = isolated { context ->
        for (name in listOf("waiting-tasks.json", "failed-downloads.json")) {
            val file = File(context.filesDir, name)
            val original = "[{\"legacy\":\"retained\"}]"
            file.writeText(original)
            assertEquals(original, PrivateJournal(context, name).readOrNull())
            assertFalse(file.readText().contains("retained"))
            assertEquals(original, PrivateJournal(context, name).readOrNull())
            val marker = File(context.filesDir, "$name.encrypted-format").readBytes()
            for (replacement in listOf("[]", "{\"version\":1,\"events\":[]}", "[{\"forged\":true}]")) {
                file.writeText(replacement)
                assertThrows(IOException::class.java) { PrivateJournal(context, name).readOrNull() }
                assertEquals(replacement, file.readText())
                assertArrayEquals(marker, File(context.filesDir, "$name.encrypted-format").readBytes())
            }
        }
    }

    @Test fun journalsThatNeverHadPlaintextDoNotOpenALegacyMigrationPath() = isolated { context ->
        for (name in listOf("task-timeline.json", "batch-review.json", "publication-${UUID.randomUUID()}.json")) {
            val file = File(context.filesDir, name).apply { writeText("{}") }
            assertThrows(IOException::class.java) { PrivateJournal(context, name).readOrNull() }
            assertEquals("{}", file.readText())
        }
        assertEquals(0, context.filesDir.listFiles()!!.count { it.name.endsWith(".encrypted-format") })
    }

    @Test fun failedMigrationAndPlatformAtomicRollbackKeepTheExactLegacySnapshotRetryable() = isolated { context ->
        val name = "waiting-tasks.json"
        val file = File(context.filesDir, name).apply { writeText("[{\"keep\":1}]") }
        val original = file.readBytes()
        PrivateJournal.beforeWriteForTests = { target ->
            if (target == file) throw IOException("Owned fixture rejects the encrypted write")
        }
        assertThrows(IOException::class.java) { PrivateJournal(context, name).readOrNull() }
        assertArrayEquals(original, file.readBytes())
        assertTrue(File(context.filesDir, "$name.encrypted-format").isFile)
        // Exercise the platform's own .bak/.new transaction strategy, without assuming API-specific paths.
        val atomic = AtomicFile(file)
        val unfinished = atomic.startWrite()
        unfinished.write("incomplete encrypted output".toByteArray())
        atomic.failWrite(unfinished)
        assertArrayEquals(original, file.readBytes())
        PrivateJournal.beforeWriteForTests = null
        assertEquals(original.toString(Charsets.UTF_8), PrivateJournal(context, name).readOrNull())
        assertEquals(original.toString(Charsets.UTF_8), PrivateJournal(context, name).readOrNull())
    }

    @Test fun pendingMigrationRejectsADifferentLegacySnapshotWithoutReplacingIt() = isolated { context ->
        val name = "failed-downloads.json"
        val file = File(context.filesDir, name).apply { writeText("[{\"original\":true}]") }
        PrivateJournal.beforeWriteForTests = { throw IOException("Owned fixture interrupts migration") }
        assertThrows(IOException::class.java) { PrivateJournal(context, name).readOrNull() }
        PrivateJournal.beforeWriteForTests = null
        val forged = "[]"
        file.writeText(forged)
        assertThrows(IOException::class.java) { PrivateJournal(context, name).readOrNull() }
        assertEquals(forged, file.readText())
    }

    @Test fun pendingMarkerWriteFailurePreservesLegacyAndCanBeRetried() = isolated { context ->
        val name = "waiting-tasks.json"
        val file = File(context.filesDir, name).apply { writeText("[]") }
        PrivateJournal.beforeFormatWriteForTests = { _, _ -> throw IOException("Marker storage failed") }
        assertThrows(IOException::class.java) { PrivateJournal(context, name).readOrNull() }
        assertEquals("[]", file.readText())
        PrivateJournal.beforeFormatWriteForTests = null
        assertEquals("[]", PrivateJournal(context, name).readOrNull())
    }

    @Test fun failureAfterEncryptedCommitRecoversWithoutReturningToPlaintext() = isolated { context ->
        val name = "waiting-tasks.json"
        val file = File(context.filesDir, name).apply { writeText("[{\"retained\":1}]") }
        PrivateJournal.beforeFormatWriteForTests = { _, state ->
            if (state == "encrypted-v1") throw IOException("Seal write failed after journal commit")
        }
        assertThrows(IOException::class.java) { PrivateJournal(context, name).readOrNull() }
        assertFalse(file.readText().contains("retained"))
        val committed = file.readBytes()
        val marker = AtomicFile(File(context.filesDir, "$name.encrypted-format"))
        val unfinished = marker.startWrite()
        unfinished.write(byteArrayOf(1, 2, 3))
        marker.failWrite(unfinished)
        PrivateJournal.beforeFormatWriteForTests = null
        assertEquals("[{\"retained\":1}]", PrivateJournal(context, name).readOrNull())
        assertArrayEquals(committed, file.readBytes())
        file.writeText("[]")
        assertThrows(IOException::class.java) { PrivateJournal(context, name).readOrNull() }
        assertEquals("[]", file.readText())
    }

    @Test fun damagedOrWrongJournalMarkerFailsClosedWithoutRewritingData() = isolated { context ->
        val first = "waiting-tasks.json"
        val second = "failed-downloads.json"
        PrivateJournal(context, first).write("[]")
        PrivateJournal(context, second).write("[]")
        val marker = File(context.filesDir, "$first.encrypted-format")
        val journal = File(context.filesDir, first).readBytes()
        marker.writeBytes(File(context.filesDir, "$second.encrypted-format").readBytes())
        val wrong = marker.readBytes()
        assertThrows(IOException::class.java) { PrivateJournal(context, first).readOrNull() }
        assertArrayEquals(journal, File(context.filesDir, first).readBytes())
        assertArrayEquals(wrong, marker.readBytes())
        marker.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) { PrivateJournal(context, first).readOrNull() }
        assertArrayEquals(byteArrayOf(1, 2, 3), marker.readBytes())
        assertArrayEquals(journal, File(context.filesDir, first).readBytes())
    }

    @Test fun unreadableMarkerDoesNotLookLikeANewLegacyInstall() = isolated { context ->
        val name = "waiting-tasks.json"
        val file = File(context.filesDir, name).apply { writeText("[]") }
        val marker = File(context.filesDir, "$name.encrypted-format").apply { check(mkdir()) }
        assertThrows(IOException::class.java) { PrivateJournal(context, name).readOrNull() }
        assertEquals("[]", file.readText())
        assertTrue(marker.isDirectory)
    }

    @Test fun preMarkerEncryptedInstallIsSealedBeforeReturningItsData() = isolated { context ->
        val name = "waiting-tasks.json"
        PrivateJournal(context, name).write("[]")
        // Isolated fixture models an encrypted APK from before format markers existed.
        val marker = AtomicFile(File(context.filesDir, "$name.encrypted-format"))
        marker.delete()
        assertEquals("[]", PrivateJournal(context, name).readOrNull())
        assertTrue(marker.baseFile.isFile)
        File(context.filesDir, name).writeText("[{\"forged\":true}]")
        assertThrows(IOException::class.java) { PrivateJournal(context, name).readOrNull() }
    }

    @Test fun boundedEncodedWritesCannotCreateAnUnreadableOversizedRecord() = isolated { context ->
        val name = "bounded.json"
        val journal = PrivateJournal(context, name, 128)
        val exact = "a".repeat(96) // 96 UTF-8 bytes plus the 32-byte authenticated envelope.
        journal.write(exact)
        val file = File(context.filesDir, name)
        assertEquals(128L, file.length())
        val committed = file.readBytes()
        for (oversized in listOf("a".repeat(97), "س".repeat(49), "😀".repeat(25))) {
            assertThrows(IOException::class.java) { journal.write(oversized) }
            assertArrayEquals(committed, file.readBytes())
            assertEquals(exact, PrivateJournal(context, name, 128).readOrNull())
        }
        file.writeBytes(ByteArray(129))
        assertThrows(IOException::class.java) { journal.readOrNull() }
        assertEquals(129L, file.length())
    }

    @Test fun publicationTrafficDoesNotAccumulateFormatMarkers() = isolated { context ->
        for (name in listOf("waiting-tasks.json", "failed-downloads.json"))
            PrivateJournal(context, name).write("[]")
        repeat(20) {
            val name = "publication-${UUID.randomUUID()}.json"
            PrivateJournal(context, name).write("{}")
            AtomicFile(File(context.filesDir, name)).delete()
        }
        assertEquals(2, context.filesDir.listFiles()!!.count { it.name.endsWith(".encrypted-format") })
    }
}
