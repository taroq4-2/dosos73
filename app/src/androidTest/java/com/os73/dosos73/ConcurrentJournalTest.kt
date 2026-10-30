package com.os73.dosos73

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ConcurrentJournalTest {
    @Test fun separateInstancesNeverExposeOrCommitPartialEncryptedData() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "journal-concurrency-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = folder }
        val payloads = (0..80).map { n -> "{\"revision\":$n,\"text\":\"${"سليم".repeat(2048)}\"}" }.toSet()
        val rows = payloads.toList()
        PrivateJournal(context, "shared.json").write(rows.first())
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 6).map { worker -> pool.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(40) { round ->
                    val independent = PrivateJournal(context, "shared.json")
                    if (worker < 2) independent.write(rows[1 + worker * 40 + round])
                    val actual = independent.readOrNull()
                    assertTrue("A complete authenticated snapshot is required", actual in payloads)
                }
            } }
            start.countDown()
            futures.forEach { it.get(45, TimeUnit.SECONDS) }
            assertTrue(PrivateJournal(context, "shared.json").readOrNull() in payloads)
            assertFalse(File(folder, "shared.json.new").exists())
        } finally { start.countDown(); pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS)); folder.deleteRecursively() }
    }
}
