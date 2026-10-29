package com.os73.dosos73

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateJournalTest {
    @Test fun encryptsMigratesAndRejectsTamperingWithoutOverwriting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-private-${java.util.UUID.randomUUID()}.json"
        val file = File(context.filesDir, name)
        try {
            val journal = PrivateJournal(context, name)
            assertNull(journal.readOrNull())
            val plain = "{\"url\":\"https://example.org/file?token=secret\"}"
            file.writeText(plain)
            assertEquals(plain, journal.readOrNull())
            journal.write(plain)
            assertFalse(file.readText().contains("secret"))
            assertEquals(plain, journal.readOrNull())
            val encrypted = file.readBytes()
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            file.writeBytes(encrypted)
            assertThrows(java.io.IOException::class.java) { journal.readOrNull() }
            assertArrayEquals(encrypted, file.readBytes())
        } finally { file.delete(); File(file.path + ".bak").delete() }
    }
}
