package com.os73.dosos73

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LiteralNetworkBoundaryTest {
    @Test fun numericPrivateHostsAreRejectedBeforeAnyDnsOrSocket() {
        val dns = AtomicInteger()
        val sockets = AtomicInteger()
        val factory = object : javax.net.SocketFactory() {
            override fun createSocket(): Socket { sockets.incrementAndGet(); error("No socket may be created") }
            override fun createSocket(h: String, p: Int): Socket = createSocket()
            override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = createSocket()
            override fun createSocket(h: InetAddress, p: Int): Socket = createSocket()
            override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = createSocket()
        }
        val client = DirectDownloader.secureClient { true }.newBuilder().socketFactory(factory)
            .dns(object : okhttp3.Dns { override fun lookup(hostname: String): List<InetAddress> {
                dns.incrementAndGet(); error("Literal bypass must be blocked independently of DNS")
            } }).build()
        try {
            for (host in listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "192.168.1.1", "0.0.0.0",
                "2130706433", "127.1", "127.000.000.001", "127.0.0.1.", "[::1]", "[fc00::1]",
                "[::ffff:127.0.0.1]", "[64:ff9b::a00:1]", "[3fff::1]")) {
                for (scheme in listOf("https", "http")) {
                    val error = runCatching { client.newCall(Request.Builder().url("$scheme://$host/file").build()).execute().close() }.exceptionOrNull()
                    assertNotNull("$scheme://$host", error)
                }
            }
            assertEquals("No DNS fallback for ambiguous literals", 0, dns.get())
            assertEquals("No connection to the blocked literal", 0, sockets.get())
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun directRedirectCannotReachAPrivateLiteralEvenWithATestTransport() {
        var requests = 0
        val client = DirectDownloader.secureClient().newBuilder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://127.0.0.1/secret").body(ByteArray(0).toResponseBody()).build()
        }.build()
        try {
            assertThrows(IllegalArgumentException::class.java) { DirectDownloader(client).request("https://example.com/file").close() }
            assertEquals(1, requests)
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun ordinaryPublicIpLiteralsRemainValidAndHostnameChecksDoNotResolveDns() {
        for (host in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "64:ff9b::808:808", "example.invalid"))
            Safety.requirePublicLiteral(host)
        assertEquals("https://8.8.8.8/file", Safety.url("https://8.8.8.8/file"))
        assertThrows(IllegalArgumentException::class.java) { Safety.requirePublicLiteral("127.0.0.1") }
    }
}
