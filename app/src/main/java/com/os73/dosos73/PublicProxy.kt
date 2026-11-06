package com.os73.dosos73

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

/**
 * HTTPS-only proxy: every tunnel resolves and connects to a checked public IP. TLS remains
 * end-to-end between Python and the origin; certificates are never replaced.
 */
class PublicProxy(
    private val resolve: (String) -> List<InetAddress> = Safety::resolve,
    private val socketFactory: javax.net.SocketFactory = javax.net.SocketFactory.getDefault(),
    private val headerTimeoutMillis: Int = 2_000,
    private val originIdleTimeoutMillis: Int = 60_000,
) : Closeable {
    init {
        require(headerTimeoutMillis in 1..60_000)
        require(originIdleTimeoutMillis in 1..60_000)
    }
    private val server = ServerSocket(0, HEADER_CAPACITY, InetAddress.getByName("127.0.0.1"))
    // The acceptor, pending headers, and both directions of every tunnel can run together.
    private val pool = Executors.newFixedThreadPool(2 + HEADER_CAPACITY + 2 * TUNNEL_CAPACITY)
    private val headerSlots = Semaphore(HEADER_CAPACITY)
    private val tunnelSlots = Semaphore(TUNNEL_CAPACITY)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val credentials = "dosos73:${java.util.UUID.randomUUID()}"
    val url = "http://$credentials@127.0.0.1:${server.localPort}"

    init {
        pool.execute {
            while (!server.isClosed) {
                val client =
                    try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                if (!headerSlots.tryAcquire()) {
                    client.close()
                    continue
                }
                val headerDeadline = System.nanoTime() + headerTimeoutMillis * 1_000_000L
                sockets.add(client)
                try {
                    pool.execute {
                        try {
                            val input: InputStream
                            val host = try {
                                if (server.isClosed) return@execute
                                input = client.getInputStream()
                                readAuthorizedHost(client, input, headerDeadline) ?: return@execute
                            } finally {
                                headerSlots.release()
                            }
                            if (!tunnelSlots.tryAcquire()) return@execute
                            try {
                                tunnel(client, input, host)
                            } finally {
                                tunnelSlots.release()
                            }
                        } catch (_: Exception) {
                            /* Reported by extractor as network failure. */
                        } finally {
                            sockets.remove(client)
                            client.close()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    sockets.remove(client)
                    client.close()
                    headerSlots.release()
                }
            }
        }
    }

    private fun readAuthorizedHost(client: Socket, input: InputStream, headerDeadline: Long): String? {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            require(header.length < 8192)
            val remaining = headerDeadline - System.nanoTime()
            if (remaining <= 0) throw java.net.SocketTimeoutException("CONNECT header timed out")
            // Each read gets only the remainder of the absolute header deadline.
            client.soTimeout = ((remaining + 999_999L) / 1_000_000L).toInt()
            val value = input.read()
            if (System.nanoTime() >= headerDeadline)
                throw java.net.SocketTimeoutException("CONNECT header timed out")
            require(value >= 0)
            header.append(value.toChar())
        }
        val parts = header.lineSequence().first().split(' ')
        if (!authorized(header.toString())) {
            client
                .getOutputStream()
                .write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"dosos73\"\r\nContent-Length: 0\r\n\r\n"
                        .toByteArray()
                )
            return null
        }
        require(parts.size == 3 && parts[0] == "CONNECT" && parts[2] in setOf("HTTP/1.0", "HTTP/1.1"))
        val target = java.net.URI("https://${parts[1]}")
        val host = target.host
        require(target.port == 443 && target.userInfo == null && !host.isNullOrBlank() &&
            target.path.isNullOrEmpty() && target.rawQuery == null && target.rawFragment == null)
        return host
    }

    private fun tunnel(client: Socket, input: InputStream, host: String) {
        // Validate the entire DNS answer before attempting any connection.
        val addresses = Safety.checkedAddresses(resolve(host))
        connectPublic(addresses).use { remote ->
            try {
                remote.soTimeout = originIdleTimeoutMillis
                client
                    .getOutputStream()
                    .write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                // Downloads can receive for a long time without any further client writes.
                // Bound only origin read inactivity, including after a client half-close.
                client.soTimeout = 0
                val upstream =
                    pool.submit {
                        try {
                            input.copyTo(remote.getOutputStream(), 32 * 1024)
                        } catch (_: Exception) {} finally {
                            runCatching { remote.shutdownOutput() }
                        }
                    }
                try {
                    remote.getInputStream().copyTo(client.getOutputStream(), 32 * 1024)
                } finally {
                    upstream.cancel(true)
                    client.close()
                }
            } finally {
                sockets.remove(remote)
            }
        }
    }

    private fun connectPublic(addresses: List<InetAddress>): Socket {
        val deadline = System.nanoTime() + 20_000_000_000L
        var failure: java.io.IOException? = null
        for (address in addresses) {
            if (server.isClosed) throw java.io.IOException("Proxy closed")
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remaining <= 0) break
            val remote = socketFactory.createSocket()
            sockets.add(remote)
            try {
                // close() may have run while socketFactory was creating this socket.
                if (server.isClosed) throw java.io.IOException("Proxy closed")
                remote.connect(InetSocketAddress(address, 443), minOf(5000, remaining))
                return remote
            } catch (error: java.io.IOException) {
                failure = error
                sockets.remove(remote)
                remote.close()
            }
        }
        throw failure ?: java.net.SocketTimeoutException("Connection timed out")
    }

    private fun authorized(header: String): Boolean {
        val lines =
            header.lineSequence().filter { it.startsWith("Proxy-Authorization:", true) }.toList()
        if (lines.size != 1) return false
        val value = lines.single().substringAfter(':').trim()
        if (!value.startsWith("Basic ", true)) return false
        val decoded =
            runCatching { java.util.Base64.getDecoder().decode(value.substring(6).trim()) }
                .getOrNull() ?: return false
        return java.security.MessageDigest.isEqual(decoded, credentials.toByteArray())
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private companion object {
        const val HEADER_CAPACITY = 12
        const val TUNNEL_CAPACITY = 8
    }
}
