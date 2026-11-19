package com.os73.dosos73

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/**
 * GitHub release assets (and most CDNs) redirect every request to a URL with a fresh, time-limited
 * signature in its query. Found on the emulator in Phase D: a paused GitHub download could never
 * resume and every retry failed with "Changed representation", because the checkpoint was bound to
 * the full signed URL. Identity must come from host + path + strong ETag + exact length.
 */
class SignedRedirectResumeTest {
    private val size = 20_000
    private val body = (0 until size).map { 'a' + it % 26 }.joinToString("")

    private fun fixture(block: suspend (MockWebServer, DirectDownloader, File) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Unlike the main fixture, keep the query: signatures live there.
            val client = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val original = chain.request().url
                    val target = server.url(original.encodedPath).newBuilder().encodedQuery(original.encodedQuery).build()
                    chain.proceed(chain.request().newBuilder().url(target).build())
                }.build()
            val file = File.createTempFile("signed-resume", ".bin")
            try { block(server, DirectDownloader(client), file) }
            finally { file.delete(); File(file.parentFile, file.name + ".resume").delete() }
        }
    }

    /** First attempt through the real code path: redirect, strong ETag, connection drops mid-body. */
    private suspend fun interrupted(server: MockWebServer, engine: DirectDownloader, file: File, location: String): Long {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
        server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody(body)
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertTrue(runCatching { engine.download("https://example.com/releases/download/v1/a.zip", file) { _, _, _ -> } }.isFailure)
        repeat(2) { server.takeRequest() }
        val checkpoint = ResumeJournal(file).read()
        assertNotNull("a durable prefix must exist after the interruption", checkpoint)
        return checkpoint!!.offset.also { assertTrue(it in 1 until size.toLong()) }
    }

    @Test
    fun resumesWhenOnlyTheSignedQueryOfTheRedirectChanged() = fixture { server, engine, file ->
        val offset = interrupted(server, engine, file, "https://cdn.example.com/asset/42?sig=first&se=1")
        server.enqueue(MockResponse().setResponseCode(302)
            .setHeader("Location", "https://cdn.example.com/asset/42?sig=second&se=2"))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"v1\"")
            .setHeader("Content-Range", "bytes $offset-${size - 1}/$size").setBody(body.substring(offset.toInt())))
        engine.download("https://example.com/releases/download/v1/a.zip", file) { _, _, _ -> }
        server.takeRequest()
        val ranged = server.takeRequest()
        assertEquals("bytes=$offset-", ranged.getHeader("Range"))
        assertEquals("\"v1\"", ranged.getHeader("If-Range"))
        assertEquals(body, file.readText())
        assertNull(ResumeJournal(file).read())
    }

    @Test
    fun aDifferentObjectPathIsStillADifferentFile() = fixture { server, engine, file ->
        val offset = interrupted(server, engine, file, "https://cdn.example.com/asset/42?sig=a")
        val before = file.readText().take(offset.toInt())
        // Same ETag text and length, but another object: must never be appended.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://cdn.example.com/asset/99?sig=b"))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"v1\"")
            .setHeader("Content-Range", "bytes $offset-${size - 1}/$size").setBody("X".repeat(size - offset.toInt())))
        assertTrue(runCatching { engine.download("https://example.com/releases/download/v1/a.zip", file) { _, _, _ -> } }.isFailure)
        assertFalse(file.readText().contains("X"))
        assertEquals(before, file.readText().take(offset.toInt()))
    }
}
