package com.os73.dosos73

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class DirectDownloaderTest {
    @Test
    fun headRejectionUsesBoundedRangeProbeAndRealTotal() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "VIDEO/MP4")
                .setHeader("Content-Range", "bytes 0-0/5000000000")
                .setBody("x")
        )
        val result = engine.analyze("https://example.com/video.mp4")!!
        assertEquals(5000000000L, result.sizeBytes)
        assertEquals(Kind.VIDEO, result.choices.single().kind)
        assertEquals("HEAD", server.takeRequest().method)
        val probe = server.takeRequest()
        assertEquals("bytes=0-0", probe.getHeader("Range"))
        assertTrue(probe.getHeader("User-Agent")!!.startsWith("dosos73/"))
    }

    @Test
    fun rangeRejectionRetriesGetAndStillRejectsHtml() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setHeader("Content-Type", "TEXT/HTML").setBody("login"))
        assertNull(engine.analyze("https://example.com/video.mp4"))
        server.enqueue(MockResponse().setHeader("Content-Type", "TEXT/HTML").setBody("login"))
        assertTrue(
            runCatching { engine.download("https://example.com/video.mp4", file) { _, _, _ -> } }
                .isFailure
        )
    }

    @Test
    fun rangeUnsupportedOriginUsesOnePlainGet() = fixture { server, engine, _ ->
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

    @Test
    fun repeatedRangeFailureDoesNotLoop() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setResponseCode(405))
        repeat(2) { server.enqueue(MockResponse().setResponseCode(416)) }
        assertNull(engine.analyze("https://example.com/video.mp4"))
        assertEquals(3, server.requestCount)
    }

    private fun fixture(block: suspend (MockWebServer, DirectDownloader, File) -> Unit) =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                // Explicit test-only transport routes a public logical URL to a deterministic
                // fixture.
                val client =
                    OkHttpClient.Builder()
                        .followRedirects(false)
                        .retryOnConnectionFailure(false)
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
                val file = File.createTempFile("transfer-test", ".bin")
                try {
                    block(server, DirectDownloader(client), file)
                } finally {
                    file.delete()
                    File(file.parentFile, file.name + ".resume").delete()
                    File(file.parentFile, file.name + ".resume.tmp").delete()
                }
            }
        }

    @Test
    fun preservesExactBytes() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setBody("verified fixture"))
        engine.download("https://example.com/file", file) { _, _, _ -> }
        assertEquals("verified fixture", file.readText())
    }

    @Test
    fun acceptsUnknownLength() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setChunkedBody("chunked fixture", 3))
        engine.download("https://example.com/file", file) { _, _, _ -> }
        assertEquals("chunked fixture", file.readText())
    }

    @Test
    fun sizeAboveTwoGiBProceedsToTransferWithoutFixedCap() = fixture { server, engine, file ->
        server.enqueue(
            MockResponse()
                .setBody("x")
                .setHeader("Content-Length", 2147483649L)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        )
        val error =
            runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                .exceptionOrNull()
        assertTrue(
            "Large size must proceed to transfer, then reject truncation",
            error is java.io.IOException,
        )
        assertEquals(1L, file.length())
    }

    @Test
    fun exactTwoGiBPassesSizeGuardButTruncatedBodyFailsTransfer() =
        fixture { server, engine, file ->
            // Advertise the boundary without allocating or transferring two GiB.
            server.enqueue(
                MockResponse()
                    .setBody("x")
                    .setHeader("Content-Length", 2147483648L)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
            )
            val error =
                runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                    .exceptionOrNull()
            assertTrue(
                "Boundary must proceed to transfer, then reject truncation",
                error is java.io.IOException,
            )
            assertEquals(1L, file.length())
        }

    @Test
    fun rejectsUnrequestedPartialResponse() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setResponseCode(206).setBody("partial"))
        assertTrue(
            runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                .isFailure
        )
    }

    @Test
    fun rejectsEmptyAndHtmlResponses() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setBody(""))
        assertTrue(
            runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                .isFailure
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Login</html>")
        )
        assertTrue(
            runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                .isFailure
        )
    }

    @Test
    fun rejectsHttpsDowngrade() = fixture { server, engine, file ->
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/unsafe")
        )
        assertTrue(
            runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                .isFailure
        )
    }

    @Test
    fun interruptedTransferIsNotSuccess() = fixture { server, engine, file ->
        server.enqueue(
            MockResponse()
                .setBody("a".repeat(10000))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        assertTrue(
            runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                .isFailure
        )
    }

    @Test
    fun cooperativePauseBlocksConsumptionThenResumesExactBytes() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setBody("paused then resumed fixture"))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.coroutineScope {
            val transfer =
                async(kotlinx.coroutines.Dispatchers.IO) {
                    engine.download(
                        "https://example.com/file",
                        file,
                        awaitReady = {
                            entered.complete(Unit)
                            resume.await()
                        },
                    ) { _, _, _ ->
                    }
                }
            entered.await()
            assertEquals(0L, file.length())
            assertFalse(transfer.isCompleted)
            resume.complete(Unit)
            transfer.await()
        }
        assertEquals("paused then resumed fixture", file.readText())
    }

    @Test
    fun productionDnsRejectsLoopback() {
        assertTrue(
            runCatching { DirectDownloader.secureClient().dns.lookup("127.0.0.1") }.isFailure
        )
    }

    @Test
    fun rejectsPrivateRedirectTargetThroughProductionDns() = runBlocking {
        val downloader = DirectDownloader()
        assertTrue(runCatching { downloader.analyze("http://127.0.0.1/file.mp4") }.isFailure)
    }

    private fun seed(
        server: MockWebServer,
        file: File,
        offset: Long = 4,
        total: Long = 10,
        tag: String = "\"v1\"",
    ) {
        file.writeText("0123uncommitted-tail")
        ResumeJournal(file)
            .write(
                ResumeCheckpoint(
                    ResumeCheckpoint.hash("https://example.com/file"),
                    ResumeCheckpoint.hash(server.url("/file").toString()),
                    tag,
                    total,
                    offset,
                )
            )
    }

    @Test
    fun resumesDurableOffsetAndTruncatesUncommittedTail() = fixture { server, engine, file ->
        seed(server, file)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 4-9/10")
                .setBody("456789")
        )
        engine.download("https://example.com/file", file) { _, _, _ -> }
        assertEquals(ResumeCheckpoint.hash("0123456789"), ResumeCheckpoint.hash(file.readText()))
        val request = server.takeRequest()
        assertEquals("bytes=4-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
        assertNull(ResumeJournal(file).read())
    }

    @Test
    fun ignoredRangeOrChangedRepresentationRestartsWithoutAppending() =
        fixture { server, engine, file ->
            seed(server, file)
            server.enqueue(MockResponse().setHeader("ETag", "\"v2\"").setBody("replacement"))
            engine.download("https://example.com/file", file) { _, _, _ -> }
            assertEquals("replacement", file.readText())
        }

    @Test
    fun unsatisfiedRangeRestartsOnceAndNeverTrustsLocalLength() = fixture { server, engine, file ->
        seed(server, file)
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */4"))
        server.enqueue(MockResponse().setBody("new file"))
        engine.download("https://example.com/file", file) { _, _, _ -> }
        assertEquals("new file", file.readText())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun invalidRangesCannotAlterDurablePrefix() = fixture { server, engine, file ->
        for (range in
            listOf(
                "bytes 3-9/10",
                "bytes 4-8/10",
                "bytes 4-9/11",
                "bytes 9-4/10",
                "bytes 4-10/10",
                "bytes 4-9/*",
                "bytes 4-9/99999999999999999999999",
            )) {
            seed(server, file)
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("ETag", "\"v1\"")
                    .setHeader("Content-Range", range)
                    .setBody("456789")
            )
            assertTrue(
                range,
                runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                    .isFailure,
            )
            assertEquals("0123uncommitted-tail", file.readText())
        }
    }

    @Test
    fun changedOrWeakValidatorCannotAppend() = fixture { server, engine, file ->
        for (tag in listOf("\"v2\"", "W/\"v1\"", "")) {
            seed(server, file)
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("ETag", tag)
                    .setHeader("Content-Range", "bytes 4-9/10")
                    .setBody("456789")
            )
            assertTrue(
                runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                    .isFailure
            )
            assertEquals("0123uncommitted-tail", file.readText())
        }
    }

    @Test
    fun wrongLengthAndEncodingAreRejectedBeforeAppend() = fixture { server, engine, file ->
        seed(server, file)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 4-9/10")
                .setBody("short")
        )
        assertTrue(
            runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                .isFailure
        )
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody("encoded"))
        assertTrue(
            runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                .isFailure
        )
        assertEquals("0123uncommitted-tail", file.readText())
    }

    @Test
    fun interruptedTransferResumesAcrossDownloaderInstances() = fixture { server, engine, file ->
        val expected = "abcdefghij".repeat(10000)
        server.enqueue(
            MockResponse()
                .setHeader("ETag", "\"stable\"")
                .setBody(expected)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        assertTrue(
            runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                .isFailure
        )
        val saved = ResumeJournal(file).read()!!
        assertTrue(saved.offset in 1 until expected.length.toLong())
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"stable\"")
                .setHeader(
                    "Content-Range",
                    "bytes ${saved.offset}-${expected.length - 1}/${expected.length}",
                )
                .setBody(expected.substring(saved.offset.toInt()))
        )
        val restarted =
            DirectDownloader(
                OkHttpClient.Builder()
                    .followRedirects(false)
                    .retryOnConnectionFailure(false)
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
            )
        restarted.download("https://example.com/file", file) { _, _, _ -> }
        assertEquals(ResumeCheckpoint.hash(expected), ResumeCheckpoint.hash(file.readText()))
    }

    @Test
    fun urlBindingAndMissingValidatorsRestartSafely() = fixture { server, engine, file ->
        seed(server, file)
        server.enqueue(MockResponse().setBody("different URL"))
        engine.download("https://example.com/another", file) { _, _, _ -> }
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("different URL", file.readText())
        server.enqueue(
            MockResponse()
                .setHeader("ETag", "W/\"weak\"")
                .setBody("x".repeat(10000))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        assertTrue(
            runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }
                .isFailure
        )
        assertNull(ResumeJournal(file).read())
    }

    @Test
    fun productionDoesNotDelegateDnsPolicyToSystemProxy() {
        assertEquals(java.net.Proxy.NO_PROXY, DirectDownloader.secureClient().proxy)
    }
    @Test fun cancellationBeforeStartNeverOpensARequestOrResetsItsToken() = fixture { server, engine, file ->
        file.writeText("preserve existing bytes")
        engine.cancel()
        assertTrue(runCatching { engine.download("https://example.com/file", file) { _, _, _ -> } }.isFailure)
        assertEquals(0, server.requestCount)
        assertEquals("preserve existing bytes", file.readText())
    }

}
