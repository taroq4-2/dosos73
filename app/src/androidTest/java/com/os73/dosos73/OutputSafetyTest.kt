package com.os73.dosos73

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OutputSafetyTest {
    @Test fun rejectsEmptyAndFakeMedia() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "invalid-test.mp4")
        try {
            file.writeBytes(byteArrayOf())
            assertTrue(runCatching { OutputStore(context).validate(file, Kind.FILE) }.isFailure)
            file.writeText("<html>not a video</html>")
            assertTrue(runCatching { OutputStore(context).validate(file, Kind.VIDEO) }.isFailure)
        } finally { file.delete() }
    }
    @Test fun onlyAdvertisesRealMappedFormats() {
        val json = org.json.JSONObject("""{"formats":[
            {"format_id":"18","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac","protocol":"https","url":"https://example.com/v"},
            {"format_id":"bad+selector","ext":"mp4","height":4320,"vcodec":"avc1","acodec":"aac","protocol":"https","url":"https://example.com/bad"}
        ]}""")
        val choices = MediaEngine.mapFormats(json)
        assertEquals(1, choices.size)
        assertEquals("360p", choices.single().quality)
    }
}
