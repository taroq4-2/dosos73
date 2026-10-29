package com.os73.dosos73

import java.net.Socket
import java.net.URI
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PublicProxyTest {
    private fun request(
        proxy: PublicProxy,
        auth: String?,
        target: String = "127.0.0.1:443",
    ): String {
        val uri = URI(proxy.url)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 2000
            socket
                .getOutputStream()
                .write(
                    ("CONNECT $target HTTP/1.1\r\nHost: $target\r\n" +
                            (auth?.let { "Proxy-Authorization: Basic $it\r\n" } ?: "") +
                            "\r\n")
                        .toByteArray()
                )
            socket.getInputStream().bufferedReader().readLine().orEmpty()
        }
    }

    @Test
    fun rejectsMissingWrongAndOtherSessionCredentials() {
        PublicProxy().use { first ->
            PublicProxy().use { second ->
                assertTrue(request(first, null).contains("407"))
                assertTrue(request(first, "invalid").contains("407"))
                val other =
                    Base64.getEncoder().encodeToString(URI(second.url).userInfo.toByteArray())
                assertTrue(request(first, other).contains("407"))
            }
        }
    }

    @Test
    fun authenticatedRequestStillRejectsPrivateAddressAndWrongPort() {
        PublicProxy().use { proxy ->
            val auth = Base64.getEncoder().encodeToString(URI(proxy.url).userInfo.toByteArray())
            assertEquals("", request(proxy, auth))
            assertEquals("", request(proxy, auth, "example.com:22"))
        }
    }
    @Test fun closingDuringSocketCreationClosesLateSocketWithoutConnecting() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        val remote = object : Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) { connected.set(true) }
            override fun close() { super.close(); closed.countDown() }
        }
        val factory = object : javax.net.SocketFactory() {
            override fun createSocket(): Socket {
                entered.countDown()
                var done = false
                while (!done) try { done = release.await(5, java.util.concurrent.TimeUnit.SECONDS); check(done) }
                catch (_: InterruptedException) { /* Model a factory that returns after shutdown. */ }
                return remote
            }
            override fun createSocket(h: String, p: Int) = error("unused")
            override fun createSocket(h: String, p: Int, l: java.net.InetAddress, lp: Int) = error("unused")
            override fun createSocket(h: java.net.InetAddress, p: Int) = error("unused")
            override fun createSocket(h: java.net.InetAddress, p: Int, l: java.net.InetAddress, lp: Int) = error("unused")
        }
        val proxy = PublicProxy(resolve = { listOf(java.net.InetAddress.getByAddress(byteArrayOf(8,8,8,8))) }, socketFactory = factory)
        val uri = URI(proxy.url)
        val auth = Base64.getEncoder().encodeToString(uri.userInfo.toByteArray())
        val client = Socket(uri.host, uri.port)
        try {
            client.getOutputStream().write(("CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic $auth\r\n\r\n").toByteArray())
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            proxy.close()
            release.countDown()
            assertTrue(closed.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(connected.get())
        } finally { release.countDown(); proxy.close(); client.close() }
    }

}
