package com.os73.dosos73

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ResumeJournalTest {
    @Test
    fun journalRoundTripContainsNoRawUrlAndRejectsCorruption() {
        val file = File.createTempFile("resume-journal", ".part")
        val sidecar = File(file.parentFile, file.name + ".resume")
        val journal = ResumeJournal(file)
        try {
            val url = "https://example.com/file?token=private-secret"
            val value =
                ResumeCheckpoint(
                    ResumeCheckpoint.hash(url),
                    ResumeCheckpoint.hash(url),
                    "\"v1\"",
                    100,
                    20,
                )
            journal.write(value)
            assertEquals(value, journal.read())
            assertFalse(sidecar.readText().contains("private-secret"))
            sidecar.writeText("malformed")
            assertNull(journal.read())
            journal.clear()
            assertFalse(sidecar.exists())
        } finally {
            file.delete()
            sidecar.delete()
            File(file.parentFile, file.name + ".resume.tmp").delete()
        }
    }

    @Test
    fun acceptsOnlyStrongWellFormedEntityTags() {
        assertEquals("\"v1\"", ResumeCheckpoint.strongTag("\"v1\""))
        for (tag in
            listOf(
                null,
                "W/\"v1\"",
                "unquoted",
                "\"line\nbreak\"",
                "\"embedded\"quote\"",
            )) assertNull(ResumeCheckpoint.strongTag(tag))
    }
}
