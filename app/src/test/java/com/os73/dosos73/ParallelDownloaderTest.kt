package com.os73.dosos73

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ParallelDownloaderTest {
    private val bytes = ByteArray(4 * 1024 * 1024) { (it * 31).toByte() }

    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)

    private fun fixture(
        mode: String = "valid",
        block: suspend (DirectDownloader, File, MockWebServer) -> Unit,
    ) = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val range = request.getHeader("Range")
                        if (range == null || mode == "ignored")
                            return MockResponse()
                                .setHeader("ETag", "\"v1\"")
                                .setBody(Buffer().write(bytes))
                                .apply { if (mode == "benchmark") throttleBody(256 * 1024, 50, TimeUnit.MILLISECONDS) }
                        val match =
                            Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(range)
                                ?: return MockResponse().setResponseCode(400)
                        val first = match.groupValues[1].toInt()
                        val last = match.groupValues[2].toInt()
                        val probe = first == 0 && last == 0
                        val response =
                            MockResponse()
                                .setResponseCode(206)
                                .setHeader(
                                    "ETag",
                                    if (!probe && mode == "changed") "\"v2\"" else "\"v1\"",
                                )
                                .setHeader(
                                    "Content-Range",
                                    "bytes $first-${if (!probe && mode == "wrong") last - 1 else last}/${bytes.size}",
                                )
                                .setBody(Buffer().write(bytes, first, last - first + 1))
                        if (!probe && mode == "encoding")
                            response.setHeader("Content-Encoding", "gzip")
                        if (!probe && mode == "benchmark")
                            response.throttleBody(256 * 1024, 50, TimeUnit.MILLISECONDS)
                        return response
                    }
                }
            server.start()
            val client =
                OkHttpClient.Builder()
                    .followRedirects(false)
                    .retryOnConnectionFailure(false)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .url(server.url(chain.request().url.encodedPath))
                                .build()
                        )
                    }
                    .build()
            val directory = java.nio.file.Files.createTempDirectory("parallel-test").toFile()
            try {
                block(DirectDownloader(client), File(directory, "out.bin"), server)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun twoRangesProduceExactDigestAndCleanParts() = fixture { engine, file, server ->
        engine.download("https://example.com/file", file, connections = 2) { _, _, _ -> }
        assertArrayEquals(hash(bytes), hash(file.readBytes()))
        assertEquals(3, server.requestCount)
        assertEquals(listOf("out.bin"), file.parentFile!!.list()!!.toList())
        val requests = (0..2).map { server.takeRequest() }
        assertEquals("bytes=0-0", requests.first().getHeader("Range"))
        requests.drop(1).forEach { assertEquals("\"v1\"", it.getHeader("If-Range")) }
    }

    @Test
    fun ignoredRangeFallsBackOnceWithoutDuplicatingBytes() =
        fixture("ignored") { engine, file, server ->
            engine.download("https://example.com/file", file, connections = 2) { _, _, _ -> }
            assertArrayEquals(hash(bytes), hash(file.readBytes()))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun mismatchedRangeAndValidatorAndEncodingFallbackSafely() {
        for (mode in listOf("changed", "wrong", "encoding")) fixture(mode) { engine, file, server ->
            engine.download("https://example.com/file", file, connections = 2) { _, _, _ -> }
            assertArrayEquals(hash(bytes), hash(file.readBytes()))
            assertTrue(server.requestCount in 3..4)
            assertEquals(listOf("out.bin"), file.parentFile!!.list()!!.toList())
        }
    }

    @Test
    fun controlledPerConnectionThrottleComparisonReportsMeasuredTimes() =
        fixture("benchmark") { engine, file, _ ->
            val sequential = mutableListOf<Long>()
            val parallel = mutableListOf<Long>()
            repeat(3) {
                for (connections in listOf(1, 2)) {
                    val started = System.nanoTime()
                    engine.download("https://example.com/file", file, connections = connections) {
                        _,
                        _,
                        _ ->
                    }
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    assertArrayEquals(hash(bytes), hash(file.readBytes()))
                    if (connections == 1) sequential.add(elapsed) else parallel.add(elapsed)
                }
            }
            println(
                "CONTROLLED_BENCHMARK bytes=${bytes.size}; perConnectionThrottle=262144 bytes/50ms; sequentialMs=$sequential; parallelMs=$parallel; eachFinalSHA256=matched"
            )
        }
}
