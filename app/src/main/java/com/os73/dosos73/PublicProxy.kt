package com.os73.dosos73

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * HTTPS-only proxy: every tunnel resolves and connects to a checked public IP. TLS remains
 * end-to-end between Python and the origin; certificates are never replaced.
 */
class PublicProxy : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    // One acceptor plus two directions per tunnel, with one spare worker.
    private val pool = Executors.newFixedThreadPool(18)
    private val slots = Semaphore(8)
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
                if (!slots.tryAcquire()) {
                    client.close()
                    continue
                }
                sockets.add(client)
                pool.execute {
                    try {
                        tunnel(client)
                    } catch (_: Exception) {
                        /* Reported by extractor as network failure. */
                    } finally {
                        sockets.remove(client)
                        client.close()
                        slots.release()
                    }
                }
            }
        }
    }

    private fun tunnel(client: Socket) {
        client.soTimeout = 30_000
        val input = client.getInputStream()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            require(header.length < 8192)
            val value = input.read()
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
            return
        }
        require(parts.size == 3 && parts[0] == "CONNECT")
        val target = java.net.URI("https://${parts[1]}")
        require(target.port == 443 && target.userInfo == null && target.path.isNullOrEmpty())
        val address = Safety.resolve(target.host).first()
        Socket().use { remote ->
            sockets.add(remote)
            try {
                remote.connect(InetSocketAddress(address, 443), 20_000)
                remote.soTimeout = 60_000
                client
                    .getOutputStream()
                    .write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                // The native client's socket timeout owns idle deadlines after CONNECT.
                client.soTimeout = 0
                remote.soTimeout = 0
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
}
