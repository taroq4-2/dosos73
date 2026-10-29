package com.os73.dosos73

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskTimelineTest {
    private fun isolated(block: suspend (Context) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "timeline-test-${UUID.randomUUID()}"
        val folder = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name", mode)
        }
        try { block(context) } finally { folder.deleteRecursively(); base.deleteSharedPreferences("$prefix-library_controls") }
    }
    @Test fun boundedEncryptedJournalSurvivesFreshReadAndHasOnlyAllowedFields() = isolated { context ->
        val now = System.currentTimeMillis()
        val first = UUID.randomUUID().toString()
        repeat(40) { TaskTimeline.record(context, TimelineEvent(first, now + it, Stage.TRANSFER, TimelineResult.STARTED)) }
        assertEquals(24, TaskTimeline.read(context).size)
        val ids = (0 until 40).map { UUID.randomUUID().toString() }
        ids.forEachIndexed { i, id -> TaskTimeline.record(context, TimelineEvent(id, now + 100 + i, Stage.PUBLISH, TimelineResult.COMPLETED, 1024)) }
        val rows = TaskTimeline.read(context)
        assertEquals(ids.takeLast(32).toSet(), rows.map { it.taskId }.toSet())
        assertEquals(32, rows.size)
        val bytes = File(context.filesDir, "task-timeline.json").readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(ids.last()))
        val json = org.json.JSONObject(PrivateJournal(context, "task-timeline.json").readOrNull()!!)
        val item = json.getJSONArray("events").getJSONObject(0)
        assertTrue(item.keys().asSequence().all { it in setOf("task", "at", "stage", "result", "bytes", "code") })
        assertTrue(bytes.size < 256 * 1024)
    }
    @Test fun retentionRewritesExpiredEventsAndClearDoesNotTouchFiles() = isolated { context ->
        val media = File(context.filesDir, "keep.bin").apply { writeText("user file sentinel") }
        val id = UUID.randomUUID().toString()
        TaskTimeline.record(context, TimelineEvent(id, System.currentTimeMillis() - 8 * 86400000L, Stage.TRANSFER, TimelineResult.STARTED))
        assertEquals(1, TaskTimeline.read(context).size)
        LibraryPreferences.setHistoryDays(context, 7)
        assertTrue(TaskTimeline.read(context).isEmpty())
        assertEquals(0, org.json.JSONObject(PrivateJournal(context, "task-timeline.json").readOrNull()!!).getJSONArray("events").length())
        TaskTimeline.record(context, TimelineEvent(id, System.currentTimeMillis(), Stage.PUBLISH, TimelineResult.FAILED, code = FailureCode.STORAGE_FULL))
        TaskTimeline.clear(context)
        assertTrue(TaskTimeline.read(context).isEmpty())
        assertEquals("user file sentinel", media.readText())
    }
    @Test fun tamperingIsNotReplacedByAWriteOrEmptyHistory() = isolated { context ->
        val id = UUID.randomUUID().toString()
        TaskTimeline.record(context, TimelineEvent(id, System.currentTimeMillis(), Stage.TRANSFER, TimelineResult.STARTED))
        val file = File(context.filesDir, "task-timeline.json")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(runCatching { TaskTimeline.read(context) }.isFailure)
        assertTrue(runCatching { TaskTimeline.record(context, TimelineEvent(id, System.currentTimeMillis(), Stage.PUBLISH, TimelineResult.COMPLETED)) }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
    }
}
