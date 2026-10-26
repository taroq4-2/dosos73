package com.os73.dosos73

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import okhttp3.mockwebserver.SocketPolicy

class DirectDownloaderTest {
    @Test fun headRejectionUsesBoundedRangeProbeAndRealTotal() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type","VIDEO/MP4")
            .setHeader("Content-Range","bytes 0-0/5000000000").setBody("x"))
        val result=engine.analyze("https://example.com/video.mp4")!!
        assertEquals(5000000000L,result.sizeBytes)
        assertEquals(Kind.VIDEO,result.choices.single().kind)
        assertEquals("HEAD",server.takeRequest().method)
        val probe=server.takeRequest()
        assertEquals("bytes=0-0",probe.getHeader("Range"))
        assertTrue(probe.getHeader("User-Agent")!!.startsWith("dosos73/"))
    }
    @Test fun rangeRejectionRetriesGetAndStillRejectsHtml() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setHeader("Content-Type","TEXT/HTML").setBody("login"))
        assertNull(engine.analyze("https://example.com/video.mp4"))
        server.enqueue(MockResponse().setHeader("Content-Type","TEXT/HTML").setBody("login"))
        assertTrue(runCatching { engine.download("https://example.com/video.mp4",file) { _,_,_ -> } }.isFailure)
    }
    @Test fun rangeUnsupportedOriginUsesOnePlainGet() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setHeader("Content-Type", "VIDEO/MP4").setBody("media"))
        assertEquals(5L, engine.analyze("https://example.com/video.mp4")!!.sizeBytes)
        server.takeRequest()
        assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
        val fallback = server.takeRequest()
        assertEquals("GET", fallback.method)
        assertNull(fallback.getHeader("Range"))
        assertEquals(3, server.requestCount)
    }
    @Test fun repeatedRangeFailureDoesNotLoop() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setResponseCode(405))
        repeat(2) { server.enqueue(MockResponse().setResponseCode(416)) }
        assertNull(engine.analyze("https://example.com/video.mp4"))
        assertEquals(3, server.requestCount)
    }
    private fun fixture(block: suspend (MockWebServer, DirectDownloader, File) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Explicit test-only transport routes a public logical URL to a deterministic fixture.
            val client = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                    .url(server.url(chain.request().url.encodedPath)).build()) }.build()
            val file = File.createTempFile("transfer-test", ".bin")
            try { block(server, DirectDownloader(client), file) } finally { file.delete() }
        }
    }
    @Test fun preservesExactBytes() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setBody("verified fixture"))
        engine.download("https://example.com/file", file) { _, _, _ -> }
        assertEquals("verified fixture", file.readText())
    }
    @Test fun acceptsUnknownLength() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setChunkedBody("chunked fixture", 3))
        engine.download("https://example.com/file", file) { _, _, _ -> }
        assertEquals("chunked fixture", file.readText())
    }
    @Test fun sizeAboveTwoGiBProceedsToTransferWithoutFixedCap() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 2147483649L)
            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        val error = runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }.exceptionOrNull()
        assertTrue("Large size must proceed to transfer, then reject truncation", error is java.io.IOException)
        assertEquals(1L, file.length())
    }
    @Test fun exactTwoGiBPassesSizeGuardButTruncatedBodyFailsTransfer() = fixture { server, engine, file ->
        // Advertise the boundary without allocating or transferring two GiB.
        server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 2147483648L)
            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        val error = runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }.exceptionOrNull()
        assertTrue("Boundary must proceed to transfer, then reject truncation", error is java.io.IOException)
        assertEquals(1L, file.length())
    }
    @Test fun rejectsUnrequestedPartialResponse() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setResponseCode(206).setBody("partial"))
        assertTrue(runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }.isFailure)
    }
    @Test fun rejectsEmptyAndHtmlResponses() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setBody(""))
        assertTrue(runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }.isFailure)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Login</html>"))
        assertTrue(runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }.isFailure)
    }
    @Test fun rejectsHttpsDowngrade() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/unsafe"))
        assertTrue(runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }.isFailure)
    }
    @Test fun interruptedTransferIsNotSuccess() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setBody("a".repeat(10000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertTrue(runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }.isFailure)
    }
    @Test fun cooperativePauseBlocksConsumptionThenResumesExactBytes() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setBody("paused then resumed fixture"))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.coroutineScope {
            val transfer = async(kotlinx.coroutines.Dispatchers.IO) {
                engine.download("https://example.com/file", file, awaitReady = { entered.complete(Unit); resume.await() }) { _, _, _ -> }
            }
            entered.await()
            assertEquals(0L, file.length())
            assertFalse(transfer.isCompleted)
            resume.complete(Unit)
            transfer.await()
        }
        assertEquals("paused then resumed fixture", file.readText())
    }    @Test fun productionDnsRejectsLoopback() {
        assertTrue(runCatching { DirectDownloader.secureClient().dns.lookup("127.0.0.1") }.isFailure)
    }
    @Test fun rejectsPrivateRedirectTargetThroughProductionDns() = runBlocking {
        val downloader = DirectDownloader()
        assertTrue(runCatching { downloader.analyze("http://127.0.0.1/file.mp4") }.isFailure)
    }
}

