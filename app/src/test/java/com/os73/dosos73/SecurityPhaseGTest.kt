package com.os73.dosos73

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase G security regression tests for the vNext network surfaces (link probe, documented-API
 * resolvers, file naming). Each test names the attack it blocks.
 */
class SecurityPhaseGTest {
    private fun fixture(block: suspend (MockWebServer, DirectDownloader) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Routes public logical URLs to the fixture; the policy checks still see the logical host.
            val client = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val original = chain.request().url
                    chain.proceed(chain.request().newBuilder()
                        .url(server.url(original.encodedPath).newBuilder().encodedQuery(original.encodedQuery).build()).build())
                }.build()
            block(server, DirectDownloader(client))
        }
    }

    @Test
    fun ssrfRedirectFromProbeToPrivateOrLoopbackAddressIsRefused() {
        for (target in listOf("https://10.0.0.5/admin", "https://127.0.0.1/", "https://169.254.169.254/latest/meta-data",
            "https://[::1]/", "https://192.168.1.1/", "https://localhost/")) {
            // A fresh server per target: the redirect is the only response, so any second request
            // (following the redirect) would be visible in the request count.
            fixture { server, engine ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
                server.enqueue(MockResponse().setBody("decoy: must never be requested"))
                assertTrue(target, runCatching { engine.probe("https://example.com/file.apk") }.isFailure)
                assertEquals("$target must not be contacted", 1, server.requestCount)
            }
        }
    }

    @Test
    fun httpsDowngradeDuringProbeIsRefused() = fixture { server, engine ->
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "http://example.com/file.apk"))
        assertTrue(runCatching { engine.probe("https://example.com/file.apk") }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun apiResolverRedirectToPrivateAddressIsRefused() = fixture { server, engine ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://10.1.2.3/repos"))
        assertTrue(runCatching { engine.fetchJson("https://api.github.com/repos/o/r/releases/latest", "application/json") }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun oversizedApiResponseIsRefusedBeforeParsing() = fixture { server, engine ->
        val big = "{\"a\":\"" + "x".repeat(2 * 1024 * 1024 + 10) + "\"}"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(big))
        val error = runCatching { engine.fetchJson("https://api.github.com/x", "application/json") }.exceptionOrNull()
        assertEquals("Response too large", error?.message)
    }

    @Test
    fun releaseAssetsOnlyAcceptHttpsGithubUrlsOfThatRelease() {
        val target = SourceResolvers.Target.GitHubRelease("o", "r", "v1")
        fun asset(url: String) = """{"name":"a.apk","size":1,"browser_download_url":"$url"}"""
        for (url in listOf(
            "http://github.com/o/r/releases/download/v1/a.apk",             // downgrade
            "https://user:pass@github.com/o/r/releases/download/v1/a.apk".let { it }, // credentials in URL
            "https://github.com.evil.example/o/r/releases/download/v1/a.apk", // look-alike host
            "https://github.com/other/repo/releases/download/v1/a.apk",     // another repository
            "https://127.0.0.1/o/r/releases/download/v1/a.apk",             // internal address
        )) {
            val outcome = runCatching { SourceResolvers.githubAssets("""{"assets":[${asset(url)}]}""", target) }
            val accepted = (outcome.getOrNull() as? SourceResolvers.Resolution.Direct)?.url
            if (url.contains("user:pass@")) {
                // OkHttp keeps credentials in the URL; the probe's Safety.url then refuses userinfo.
                assertTrue(url, accepted == null || runCatching { Safety.url(accepted) }.isFailure)
            } else assertNull(url, accepted)
        }
    }

    @Test
    fun serverFileNamesCannotEscapeTheDestinationOrSpoofTheType() {
        for ((disposition, expectedTitle) in listOf(
            "attachment; filename=\"../../../data/data/com.os73.dosos73/shared_prefs/x.xml\"" to "x",
            "attachment; filename=\"..\\\\..\\\\system\\\\evil.apk\"" to "evil",
            "attachment; filename*=UTF-8''%2E%2E%2Fsecret.apk" to "secret",
            "attachment; filename=\"invoice‮gpj.exe\"" to "invoicegpj",
            "attachment; filename=\"bad\u0000name.apk\"" to "badname",
        )) {
            val (title, _) = DirectFileName.resolve(disposition, "get", "application/octet-stream")
            assertEquals(disposition, expectedTitle, title)
            assertFalse(title.contains('/') || title.contains('\\'))
            // The final on-disk name is built by Safety.filename, which also refuses separators.
            assertFalse(Safety.filename(title, "apk").contains('/'))
        }
    }

    @Test
    fun aDisguisedPageIsNeverAcceptedAsAnAndroidPackage() = fixture { server, engine ->
        // HTML served with the APK MIME type and an .apk name, behind a redirect.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://cdn.example.com/a.apk"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.android.package-archive")
            .setBody("<!DOCTYPE html><html><body>Login required</body></html>"))
        assertTrue(engine.probe("https://example.com/app.apk") is DirectProbe.Page)
    }
}
