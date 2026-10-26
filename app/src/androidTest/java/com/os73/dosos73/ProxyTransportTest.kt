package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Controlled public fixture only: never retries a YouTube authentication challenge. */
class ProxyTransportTest {
    @Test
    fun encryptedTunnelPreservesLargeResponse() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTransport") == "true")
        val endpoint =
            URL(
                "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.mp4"
            )
        fun fetch(proxy: Proxy, credentials: String? = null): Pair<Long, String> {
            val client =
                okhttp3.OkHttpClient.Builder()
                    .proxy(proxy)
                    .followRedirects(false)
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .proxyAuthenticator { _, response ->
                        if (
                            credentials == null ||
                                response.request.header("Proxy-Authorization") != null
                        )
                            null
                        else
                            response.request
                                .newBuilder()
                                .header(
                                    "Proxy-Authorization",
                                    okhttp3.Credentials.basic(
                                        credentials.substringBefore(':'),
                                        credentials.substringAfter(':'),
                                    ),
                                )
                                .build()
                    }
                    .build()
            try {
                client.newCall(okhttp3.Request.Builder().url(endpoint).build()).execute().use {
                    response ->
                    assertEquals(200, response.code)
                    val hash = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    response.body!!.byteStream().use { input ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            check(bytes <= 32L * 1024 * 1024)
                            hash.update(buffer, 0, count)
                        }
                    }
                    assertTrue(bytes > 1024 * 1024)
                    return bytes to hash.digest().joinToString("") { "%02x".format(it) }
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
        // A fixed public test URL; production native traffic always retains PublicProxy.
        val reference = fetch(Proxy.NO_PROXY)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BundledEngine.install(context)
        com.yausername.youtubedl_android.YoutubeDL.init(context)
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val python = File(base, "packages/python/usr")
        PublicProxy().use { tunnel ->
            val address = URI(tunnel.url)
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(address.host, address.port))
            repeat(3) {
                assertEquals(
                    "TLS tunnel content mismatch, pass $it",
                    reference,
                    fetch(proxy, address.userInfo),
                )
            }
            val code =
                """
                import urllib.request, hashlib, sys, ssl
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({'https': sys.argv[1]}))
                h = hashlib.sha256()
                n = 0
                with opener.open(sys.argv[2], timeout=30) as response:
                    while True:
                        data = response.read(32768)
                        if not data: break
                        n += len(data)
                        assert n <= 32*1024*1024
                        h.update(data)
                print(str(n) + ':' + h.hexdigest())
            """
                    .trimIndent()
            val process =
                ProcessBuilder(
                        "${context.applicationInfo.nativeLibraryDir}/libpython.so",
                        "-c",
                        code,
                        tunnel.url,
                        endpoint.toString(),
                    )
                    .redirectErrorStream(true)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] =
                            "$python/lib:$base/packages/ffmpeg/usr/lib"
                        environment()["PYTHONHOME"] = python.absolutePath
                        environment()["SSL_CERT_FILE"] = "$python/etc/tls/cert.pem"
                    }
                    .start()
            try {
                assertTrue(
                    "Python TLS timeout",
                    process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS),
                )
                val result = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(result, 0, process.exitValue())
                assertEquals("${reference.first}:${reference.second}", result.trim())
            } finally {
                process.destroy()
            }
        }
        val folder = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(folder, "proxy-transport.txt")
            .writeText(
                "Android=${android.os.Build.VERSION.SDK_INT}\nbytes=${reference.first}\nsha256=${reference.second}\nDirect HTTPS, 3 Java CONNECT transfers and bundled Python CONNECT transfer identical; certificate validation enabled.\nThis does not establish YouTube availability or compatibility with its TLS endpoints.\n"
            )
    }
}
