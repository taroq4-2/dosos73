package com.os73.dosos73

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import org.junit.Assert.*
import org.junit.Test

class ProxyLifecycleTest {
    @Test fun tricklingUnauthenticatedHeaderHasAnAbsoluteDeadline() {
        val writerPool = Executors.newSingleThreadExecutor()
        try {
            PublicProxy(headerTimeoutMillis = 500).use { proxy ->
                client(proxy).use { socket ->
                    val sent = AtomicInteger()
                    socket.getOutputStream().write('C'.code)
                    val writer = writerPool.submit {
                        try {
                            while (!Thread.currentThread().isInterrupted) {
                                socket.getOutputStream().write('x'.code)
                                socket.getOutputStream().flush()
                                sent.incrementAndGet()
                                Thread.sleep(30)
                            }
                        } catch (_: Exception) { /* Proxy closure ends the writer. */ }
                    }
                    try {
                        val start = System.nanoTime()
                        assertPeerClosed(socket)
                        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        assertTrue("Header was being trickled", sent.get() >= 2)
                        assertTrue("Whole header must expire despite frequent bytes", elapsedMillis < 1800)
                    } finally {
                        writer.cancel(true)
                    }
                }
                // The timed-out connection must not prevent another client from being served.
                client(proxy).use { next ->
                    next.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\n\r\n".toByteArray())
                    assertTrue(readHeader(next).startsWith("HTTP/1.1 407"))
                }
            }
        } finally {
            writerPool.shutdownNow()
        }
    }

    @Test fun halfClosedClientWithSilentOriginIsReclaimed() {
        val sawClientEof = CountDownLatch(1)
        val releaseOrigin = CountDownLatch(1)
        Origin { peer ->
            assertEquals(0, peer.getInputStream().readBytes().size)
            sawClientEof.countDown()
            assertTrue(releaseOrigin.await(5, TimeUnit.SECONDS))
        }.use { origin ->
            val factory = LoopbackFactory(origin.port)
            PublicProxy(
                resolve = { listOf(publicAddress()) },
                socketFactory = factory,
                originIdleTimeoutMillis = 500,
            ).use { proxy ->
                try {
                    client(proxy).use { socket ->
                        connectTunnel(proxy, socket)
                        socket.shutdownOutput()
                        assertTrue(sawClientEof.await(2, TimeUnit.SECONDS))
                        assertPeerClosed(socket)
                        assertTrue("Origin socket must be closed after idle timeout", factory.closed.await(2, TimeUnit.SECONDS))
                    }
                } finally {
                    releaseOrigin.countDown()
                }
                origin.awaitCompletion()
            }
        }
    }

    @Test fun halfClosedClientStillReceivesOriginResponse() {
        Origin { peer ->
            assertEquals("request", peer.getInputStream().readBytes().toString(Charsets.UTF_8))
            peer.getOutputStream().write("response after EOF".toByteArray())
            peer.getOutputStream().flush()
        }.use { origin ->
            PublicProxy(
                resolve = { listOf(publicAddress()) },
                socketFactory = LoopbackFactory(origin.port),
                originIdleTimeoutMillis = 1000,
            ).use { proxy ->
                client(proxy).use { socket ->
                    connectTunnel(proxy, socket)
                    socket.getOutputStream().write("request".toByteArray())
                    socket.shutdownOutput()
                    assertEquals("response after EOF", socket.getInputStream().readBytes().toString(Charsets.UTF_8))
                }
                origin.awaitCompletion()
            }
        }
    }

    @Test fun silentClientCanKeepReceivingAnActiveOriginStream() {
        val releaseResponse = CountDownLatch(1)
        Origin { peer ->
            assertTrue(releaseResponse.await(3, TimeUnit.SECONDS))
            repeat(6) {
                peer.getOutputStream().write('a'.code)
                peer.getOutputStream().flush()
                Thread.sleep(100)
            }
            peer.soTimeout = 200
            try {
                val value = peer.getInputStream().read()
                fail("Silent client output must remain open; received $value")
            } catch (_: SocketTimeoutException) {
                // The proxy did not impose the expired header deadline on client uploads.
            }
        }.use { origin ->
            PublicProxy(
                resolve = { listOf(publicAddress()) },
                socketFactory = LoopbackFactory(origin.port),
                headerTimeoutMillis = 300,
                originIdleTimeoutMillis = 1000,
            ).use { proxy ->
                client(proxy).use { socket ->
                    connectTunnel(proxy, socket)
                    // No request writes or output shutdown: receiving exceeds the header deadline.
                    releaseResponse.countDown()
                    assertEquals("aaaaaa", socket.getInputStream().readBytes().toString(Charsets.UTF_8))
                }
                origin.awaitCompletion()
            }
        }
    }

    private fun client(proxy: PublicProxy): Socket {
        val uri = URI(proxy.url)
        return Socket(uri.host, uri.port).apply { soTimeout = 2500 }
    }

    private fun connectTunnel(proxy: PublicProxy, socket: Socket) {
        val auth = Base64.getEncoder().encodeToString(URI(proxy.url).userInfo.toByteArray())
        socket.getOutputStream().write(
            "CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic $auth\r\n\r\n".toByteArray()
        )
        assertTrue(readHeader(socket).startsWith("HTTP/1.1 200"))
    }

    private fun readHeader(socket: Socket): String {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val value = socket.getInputStream().read()
            check(value >= 0) { "Proxy closed before its response header" }
            header.append(value.toChar())
            check(header.length <= 8192)
        }
        return header.toString()
    }

    private fun assertPeerClosed(socket: Socket) {
        try {
            assertEquals("Proxy must close the connection", -1, socket.getInputStream().read())
        } catch (error: SocketTimeoutException) {
            throw AssertionError("Proxy did not close the connection within the test deadline", error)
        } catch (_: SocketException) {
            // Closing with unread trickle bytes can send a TCP reset instead of EOF.
        }
    }

    private class Origin(action: (Socket) -> Unit) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newSingleThreadExecutor()
        @Volatile private var peer: Socket? = null
        val port: Int = server.localPort
        private val result = pool.submit {
            server.accept().use { accepted ->
                peer = accepted
                accepted.soTimeout = 3000
                action(accepted)
            }
        }

        fun awaitCompletion() { result.get(4, TimeUnit.SECONDS) }

        override fun close() {
            server.close()
            peer?.close()
            result.cancel(true)
            pool.shutdownNow()
        }
    }

    /** Only this trusted test factory maps the validated public endpoint to our owned server. */
    private class LoopbackFactory(private val originPort: Int) : SocketFactory() {
        val closed = CountDownLatch(1)
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val target = endpoint as InetSocketAddress
                assertEquals(publicAddress(), target.address)
                assertEquals(443, target.port)
                super.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), originPort), timeout)
            }

            override fun close() {
                try { super.close() } finally { closed.countDown() }
            }
        }

        override fun createSocket(host: String, port: Int): Socket = error("Hostname connection forbidden")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unused")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("Unused")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unused")
    }

    companion object {
        private fun publicAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
    }
}