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
    // ---- Link probe (AUDIT_BASELINE.md P0-1/P0-2/P0-3). Replaces the HEAD-first tests of 1.3.2. ----

    private val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x08, 0x00)
    private fun body(bytes: ByteArray) = okio.Buffer().write(bytes)

    @Test
    fun apkWithAndroidMimeIsADirectVerifiedFileFromOneBoundedRequest() = fixture { server, engine, _ ->
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setHeader("Content-Range", "bytes 0-7/48213504")
                .setBody(body(zip))
        )
        val result = engine.probe("https://example.com/dl/app-release.apk") as DirectProbe.File
        assertTrue(result.verified)
        assertEquals(FileCategory.APP, result.category)
        assertEquals(48213504L, result.analysis.sizeBytes)
        assertEquals("app-release", result.analysis.title)
        assertEquals("apk", result.analysis.choices.single().extension)
        assertEquals(Kind.FILE, result.analysis.choices.single().kind)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("bytes=0-4095", request.getHeader("Range"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("dosos73/"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun apkServedAsOctetStreamOrWithoutMimeIsRecognisedFromNameAndBytes() = fixture { server, engine, _ ->
        for (mime in listOf("application/octet-stream", null, "application/zip", "binary/octet-stream")) {
            server.enqueue(MockResponse().setResponseCode(200).apply {
                if (mime != null) setHeader("Content-Type", mime)
            }.setBody(body(zip)))
            val result = engine.probe("https://example.com/files/tool.apk") as DirectProbe.File
            assertEquals("$mime", "apk", result.analysis.choices.single().extension)
            assertTrue("$mime", result.verified)
        }
    }

    @Test
    fun contentDispositionNamesTheFileIncludingRfc5987Utf8() = fixture { server, engine, _ ->
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/octet-stream")
            .setHeader("Content-Disposition",
                "attachment; filename=\"fallback.apk\"; filename*=UTF-8''%D8%AA%D8%B7%D8%A8%D9%8A%D9%82.apk")
            .setBody(body(zip)))
        val result = engine.probe("https://example.com/download?id=7") as DirectProbe.File
        assertEquals("تطبيق", result.analysis.title)
        assertEquals("apk", result.analysis.choices.single().extension)
    }

    @Test
    fun opaqueFileWithoutNameTakesItsTypeFromTheBytes() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
            .setBody(body("%PDF-1.7\n".toByteArray())))
        val result = engine.probe("https://example.com/get") as DirectProbe.File
        assertEquals("pdf", result.analysis.choices.single().extension)
        assertTrue(result.verified)
    }

    @Test
    fun htmlIsNeverAFileEvenWhenTheUrlEndsInApk() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html>Login</html>"))
        assertTrue(engine.probe("https://example.com/app.apk") is DirectProbe.Page)
        // A page disguised as a binary is caught by its bytes.
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.android.package-archive")
            .setBody("﻿  <!DOCTYPE html><html><body>Please wait</body></html>"))
        assertTrue(engine.probe("https://example.com/app.apk") is DirectProbe.Page)
    }

    @Test
    fun declaredApkWhoseBytesAreAnotherFormatIsRefused() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.android.package-archive")
            .setBody(body("%PDF-1.4".toByteArray())))
        val result = engine.probe("https://example.com/app.apk")
        assertEquals(DirectProbe.Failed(FailureCode.FILE_TYPE_MISMATCH), result)
    }

    @Test
    fun commonProgramArchiveAndDocumentTypesAreDirectFiles() = fixture { server, engine, _ ->
        val rar = "Rar!".toByteArray() + byteArrayOf(0x1A, 0x07, 0x01, 0x00)
        val sevenZip = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4)
        for ((name, mime, bytes, extension) in listOf(
            Quad("a.rar", "application/vnd.rar", rar, "rar"),
            Quad("a.7z", "application/x-7z-compressed", sevenZip, "7z"),
            Quad("setup.exe", "application/x-msdownload", "MZ\u0090\u0000".toByteArray(Charsets.ISO_8859_1), "exe"),
            Quad("report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip, "docx"),
            Quad("game.xapk", "application/octet-stream", zip, "xapk"),
            Quad("bundle.apks", "application/octet-stream", zip, "apks"),
            Quad("book.epub", "application/epub+zip", zip, "epub"),
        )) {
            server.enqueue(MockResponse().setHeader("Content-Type", mime).setBody(body(bytes)))
            val result = engine.probe("https://example.com/$name")
            assertTrue(name, result is DirectProbe.File)
            assertEquals(name, extension, (result as DirectProbe.File).analysis.choices.single().extension)
        }
    }

    private data class Quad(val a: String, val b: String, val c: ByteArray, val d: String)

    @Test
    fun rangeRejectionRetriesOnePlainGetAndStillRejectsHtml() = fixture { server, engine, file ->
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setHeader("Content-Type", "TEXT/HTML").setBody("login"))
        assertTrue(engine.probe("https://example.com/video.mp4") is DirectProbe.Page)
        assertEquals("bytes=0-4095", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
        server.enqueue(MockResponse().setHeader("Content-Type", "TEXT/HTML").setBody("login"))
        assertTrue(
            runCatching { engine.download("https://example.com/video.mp4", file) { _, _, _ -> } }
                .isFailure
        )
    }

    @Test
    fun repeatedRangeFailureDoesNotLoop() = fixture { server, engine, _ ->
        repeat(3) { server.enqueue(MockResponse().setResponseCode(416)) }
        assertTrue(engine.probe("https://example.com/video.mp4") is DirectProbe.Failed)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun unrangedOriginReportsContentLengthAndReadsOnlyTheHeader() = fixture { server, engine, _ ->
        val large = ByteArray(1024 * 1024) { 7 }
        System.arraycopy(zip, 0, large, 0, zip.size)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(body(large)))
        val result = engine.probe("https://example.com/big.zip") as DirectProbe.File
        assertEquals(large.size.toLong(), result.analysis.sizeBytes)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun statusFailuresAreSpecificAndCarryRetryAfter() = fixture { server, engine, _ ->
        for ((status, code) in listOf(
            404 to FailureCode.HTTP_404, 410 to FailureCode.LINK_EXPIRED, 403 to FailureCode.HTTP_403,
            401 to FailureCode.AUTHENTICATION_REQUIRED, 503 to FailureCode.HTTP_SERVER,
        )) {
            server.enqueue(MockResponse().setResponseCode(status))
            assertEquals("$status", code, (engine.probe("https://example.com/f.apk") as DirectProbe.Failed).code)
        }
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        assertEquals(DirectProbe.Failed(FailureCode.HTTP_429, 429, 30), engine.probe("https://example.com/f.apk"))
    }

    @Test
    fun redirectChainToTheRealFileIsFollowedAndRechecked() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/step"))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://example.com/assets/real.apk"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(body(zip)))
        val result = engine.probe("https://example.com/releases/latest/download") as DirectProbe.File
        assertEquals("apk", result.analysis.choices.single().extension)
        assertEquals("https://example.com/releases/latest/download", result.analysis.url)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun plainTextApiResponseIsAPageButAnAttachmentTextIsAFile() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}"))
        assertTrue(engine.probe("https://example.com/api/item") is DirectProbe.Page)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/csv")
            .setHeader("Content-Disposition", "attachment; filename=data.csv").setBody("a,b\n1,2\n"))
        val file = engine.probe("https://example.com/export") as DirectProbe.File
        assertEquals("csv", file.analysis.choices.single().extension)
    }

    @Test
    fun boundedJsonFetchRefusesNonJsonAndMapsRateLimit() = fixture { server, engine, _ ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"a\":1}"))
        assertEquals("{\"a\":1}", engine.fetchJson("https://api.example.com/x"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html/>"))
        assertTrue(runCatching { engine.fetchJson("https://api.example.com/x") }.isFailure)
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"))
        val limited = runCatching { engine.fetchJson("https://api.example.com/x") }.exceptionOrNull()
        assertEquals(FailureCode.HTTP_429, (limited as PipelineException).code)
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
    fun binaryHeadFollowedBySuccessfulErrorBodyCannotReplaceStaging() = fixture { server, engine, file ->
        val url = "https://example.com/manual.pdf"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf"))
        val choice = engine.analyze(url)!!.choices.single()
        assertEquals("pdf", choice.extension)
        file.writeText("existing verified prefix")
        for ((mime, body) in listOf(
            "Application/Problem+JSON; charset=utf-8" to "{\"error\":\"Expired URL\"}",
            "application/xml" to "<Error>Access denied</Error>",
            "text/plain" to "Temporary gateway error",
        )) {
            server.enqueue(MockResponse().setHeader("Content-Type", mime).setBody(body))
            val result = runCatching {
                engine.download(url, file, expectsBinary = DirectFileName.expectsBinary(choice)) { _, _, _ -> }
            }
            assertEquals(mime, "Unexpected response type", result.exceptionOrNull()?.message)
            assertEquals("existing verified prefix", file.readText())
        }
    }

    @Test
    fun structuredErrorInMatchingRangeLeavesDurableCheckpointUntouched() = fixture { server, engine, file ->
        seed(server, file)
        val journal = ResumeJournal(file)
        val before = journal.read()
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Type", "application/problem+xml")
            .setHeader("ETag", "\"v1\"")
            .setHeader("Content-Range", "bytes 4-9/10")
            .setBody("<bad/>"))
        val result = runCatching {
            engine.download("https://example.com/file", file, expectsBinary = true) { _, _, _ -> }
        }
        assertEquals("Unexpected response type", result.exceptionOrNull()?.message)
        assertEquals("0123uncommitted-tail", file.readText())
        assertEquals(before, journal.read())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun errorStatusIsPreservedEvenWhenItsBodyIsHtmlOrCompressed() = fixture { server, engine, file ->
        file.writeText("retained bytes")
        for ((status, code) in listOf(403 to FailureCode.HTTP_403, 404 to FailureCode.HTTP_404,
            429 to FailureCode.HTTP_429, 503 to FailureCode.HTTP_SERVER)) {
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "text/html")
                .setHeader("Content-Encoding", "gzip").setBody("origin error"))
            val error = checkNotNull(runCatching {
                engine.download("https://example.com/file", file, expectsBinary = true) { _, _, _ -> }
            }.exceptionOrNull())
            assertEquals(code, FailureClassifier.classify(Stage.TRANSFER, error).code)
            assertEquals("retained bytes", file.readText())
        }
    }

    @Test
    fun explicitGenericFilesAndOpaqueBinaryResponsesRemainSupported() = fixture { server, engine, file ->
        for ((ext, mime, body) in listOf(
            Triple("json", "application/json", "{\"fixture\":true}"),
            Triple("xml", "application/xml", "<fixture/>"),
            Triple("pdf", "application/octet-stream", "%PDF-fixture"),
        )) {
            val url = "https://example.com/file.$ext"
            // A generic origin can advertise octet-stream metadata, then specify
            // its real content type on the transfer response.
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream"))
            val choice = engine.analyze(url)!!.choices.single()
            assertEquals(ext, choice.extension)
            server.enqueue(MockResponse().setHeader("Content-Type", mime).setBody(body))
            engine.download(url, file, expectsBinary = DirectFileName.expectsBinary(choice)) { _, _, _ -> }
            assertEquals(body, file.readText())
        }
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
