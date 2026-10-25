package com.os73.dosos73

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import okhttp3.mockwebserver.SocketPolicy

class DirectDownloaderTest {
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
    @Test fun productionDnsRejectsLoopback() {
        assertTrue(runCatching { DirectDownloader.secureClient().dns.lookup("127.0.0.1") }.isFailure)
    }
    @Test fun rejectsPrivateRedirectTargetThroughProductionDns() = runBlocking {
        val downloader = DirectDownloader()
        assertTrue(runCatching { downloader.analyze("http://127.0.0.1/file.mp4") }.isFailure)
    }
}
