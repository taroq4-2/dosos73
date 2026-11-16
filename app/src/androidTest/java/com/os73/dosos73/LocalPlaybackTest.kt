package com.os73.dosos73

import android.content.ContentValues
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class LocalPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun acceptsOnlyOnDeviceProvidersAndRejectsPickersAndUrls() {
        val examples = listOf(
            "https://example.com/test.mp3",
            "file:///sdcard/Download/test.mp3",
            "content://cloud.example/document/123",
            "content://media/picker/0/com.android.providers.media.photopicker/media/123",
            "content://10@media/external_primary/downloads/123",
            "content://media/external_primary/downloads/123?redirect=true",
        )
        examples.forEach { assertFalse(it, LocalPlayback.allowed(context, Uri.parse(it))) }
        val record = ContentUris.withAppendedId(MediaStore.Downloads.getContentUri("external_primary"), 123)
        assertTrue(LocalPlayback.allowed(context, record))
    }

    @Test fun localAudioKeepsAdvancingAfterActivityStopsAndReleasesOnStop() {
        val resolver = context.contentResolver
        val name = "playback-${UUID.randomUUID()}.wav"
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(wave()) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { assertTrue(LocalPlayback.start(it, SavedFile(uri.toString(), name, "audio/wav", 0, 0))) }
                await { LocalPlayback.state.value?.phase == PlaybackPhase.PLAYING }
                scenario.moveToState(Lifecycle.State.CREATED)
                val start = position()
                await { position() > start + 450 }
                instrumentation.runOnMainSync { LocalPlayback.stop() }
                await { LocalPlayback.state.value == null }
                assertEquals(0, position())
            }
        } finally {
            instrumentation.runOnMainSync { LocalPlayback.stop() }
            resolver.delete(uri, null, null)
        }
    }

    private fun position(): Int {
        var result = 0
        instrumentation.runOnMainSync { result = LocalPlayback.position() }
        return result
    }

    @Test fun idlePauseReleasesServiceAndResumesAtBookmark() {
        val resolver = context.contentResolver
        val name = "bookmark-${UUID.randomUUID()}.wav"
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(wave()) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            LocalPlaybackService.pauseTimeoutMillisForTests = 200
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { assertTrue(LocalPlayback.start(it, SavedFile(uri.toString(), name, "audio/wav", 0, 0))) }
                await { LocalPlayback.state.value?.phase == PlaybackPhase.PLAYING }
                instrumentation.runOnMainSync { LocalPlayback.seek(4_000) }
                await { position() >= 4_000 }
                instrumentation.runOnMainSync { LocalPlayback.pause() }
                await { LocalPlayback.state.value?.phase == PlaybackPhase.SUSPENDED && !LocalPlayback.hasService() }
                val bookmark = position()
                assertTrue(bookmark >= 4_000)
                scenario.moveToState(Lifecycle.State.CREATED)
                // Suspension keeps metadata, not a running player or advancing clock.
                Thread.sleep(300)
                assertEquals(bookmark, position())
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { LocalPlayback.resume(it) }
                await { LocalPlayback.state.value?.phase == PlaybackPhase.PLAYING && position() > bookmark + 250 }
            }
        } finally {
            instrumentation.runOnMainSync { LocalPlayback.stop() }
            LocalPlaybackService.pauseTimeoutMillisForTests = null
            resolver.delete(uri, null, null)
        }
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(15_000) { while (!condition()) delay(100) }
    }

    private fun wave(): ByteArray {
        val samples = 16_000 * 15
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(capacity() - 8).put("WAVEfmt ".toByteArray())
            putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(samples * 2)
        }.array()
    }
}
