package com.os73.dosos73

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineInfoCacheTest {
    private val root: File = Files.createTempDirectory("engine-info-test").toFile()
    private val t0 = 1_800_000_000_000L

    private fun info(text: String = """{"id":"x"}"""): File =
        File.createTempFile("metadata", ".info.json", root).apply { writeText(text) }

    @Test
    fun aFreshEntryIsReusedAndTheLinkIsNotInTheFileName() {
        val url = "https://www.example.com/watch?v=secret"
        EngineInfoCache.store(root, url, info(), now = t0)
        val cached = EngineInfoCache.fresh(root, url, now = t0 + 60_000)!!
        assertEquals("""{"id":"x"}""", cached.readText())
        assertFalse(cached.name.contains("example") || cached.name.contains("secret"))
        assertEquals(EngineInfoCache.key(url) + ".info.json", cached.name)
    }

    @Test
    fun anExpiredEntryIsDeletedAndNotReused() {
        val url = "https://www.example.com/a"
        EngineInfoCache.store(root, url, info(), now = t0)
        assertNull(EngineInfoCache.fresh(root, url, now = t0 + EngineInfoCache.TTL_MS + 1))
        assertFalse(File(EngineInfoCache.directory(root), EngineInfoCache.key(url) + ".info.json").exists())
    }

    @Test
    fun aClockMovedBackwardsDoesNotKeepAnEntryForever() {
        val url = "https://www.example.com/b"
        EngineInfoCache.store(root, url, info(), now = t0)
        assertNull(EngineInfoCache.fresh(root, url, now = t0 - 1))
    }

    @Test
    fun emptyOrOversizedMetadataIsNeverCached() {
        EngineInfoCache.store(root, "https://e.com/empty", info(""), now = t0)
        assertNull(EngineInfoCache.fresh(root, "https://e.com/empty", now = t0))
        val big = File(root, "big.info.json")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(EngineInfoCache.MAX_BYTES + 1) }
        EngineInfoCache.store(root, "https://e.com/big", big, now = t0)
        assertNull(EngineInfoCache.fresh(root, "https://e.com/big", now = t0))
    }

    @Test
    fun onlyTheNewestEightEntriesAreKept() {
        for (i in 0 until 10) EngineInfoCache.store(root, "https://e.com/$i", info(), now = t0 + i * 1000L)
        val kept = EngineInfoCache.directory(root).listFiles()!!.filter { it.name.endsWith(".info.json") }
        assertEquals(8, kept.size)
        assertNull(EngineInfoCache.fresh(root, "https://e.com/0", now = t0 + 10_000))
        assertNull(EngineInfoCache.fresh(root, "https://e.com/1", now = t0 + 10_000))
        assertNotNull(EngineInfoCache.fresh(root, "https://e.com/9", now = t0 + 10_000))
    }

    @Test
    fun forgetRemovesTheEntry() {
        val url = "https://e.com/f"
        EngineInfoCache.store(root, url, info(), now = t0)
        EngineInfoCache.forget(root, url)
        assertNull(EngineInfoCache.fresh(root, url, now = t0))
    }

    @Test
    fun onlySingleHttpsItemsAreReusable() {
        assertTrue(MediaEngine.reusableInfo(JSONObject("""{"webpage_url":"https://e.com/v"}""")))
        assertTrue(MediaEngine.reusableInfo(JSONObject("""{"_type":"video","webpage_url":"https://e.com/v"}""")))
        assertFalse(MediaEngine.reusableInfo(JSONObject("""{"webpage_url":"http://e.com/v"}""")))
        assertFalse(MediaEngine.reusableInfo(JSONObject("""{}""")))
        assertFalse(MediaEngine.reusableInfo(JSONObject("""{"_type":"playlist","webpage_url":"https://e.com/p"}""")))
        assertFalse(MediaEngine.reusableInfo(JSONObject("""{"webpage_url":"https://e.com/p","entries":[]}""")))
    }

    @Test
    fun onlyLinkOrFormatFailuresTriggerAFreshExtraction() {
        assertTrue(FailureCode.HTTP_403 in MediaEngine.REEXTRACT_ON)
        assertTrue(FailureCode.LINK_EXPIRED in MediaEngine.REEXTRACT_ON)
        for (code in listOf(FailureCode.NETWORK_UNAVAILABLE, FailureCode.STORAGE_FULL,
            FailureCode.DOWNLOAD_INTERRUPTED, FailureCode.NETWORK_TLS, FailureCode.OUTPUT_VERIFICATION_FAILED))
            assertFalse(code.name, code in MediaEngine.REEXTRACT_ON)
    }
}
