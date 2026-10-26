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
}
