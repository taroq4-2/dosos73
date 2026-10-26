package com.os73.dosos73

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class SafetyTest {
    @Test
    fun nat64CannotTunnelToPrivateIpv4() {
        assertTrue(Safety.publicAddress(InetAddress.getByName("64:ff9b::808:808")))
        for (address in
            listOf(
                "64:ff9b::7f00:1",
                "64:ff9b::a00:1",
                "64:ff9b::c0a8:101",
                "2002:7f00:1::",
                "2001:0:1::",
            )) assertFalse(address, Safety.publicAddress(InetAddress.getByName(address)))
    }

    @Test
    fun rejectsDangerousSchemesAndCredentials() {
        listOf(
                "file:///etc/passwd",
                "javascript:alert(1)",
                "https://user:pass@example.com",
                "https://localhost/a",
                "https://host.local",
                "https://example.com:22/a",
                "https://example.com/\n",
            )
            .forEach { assertTrue(it, runCatching { Safety.url(it) }.isFailure) }
    }

    @Test
    fun requiresExactlyOneSharedLink() {
        assertEquals(
            "https://example.com/watch?v=1",
            Safety.shared("شاهد https://example.com/watch?v=1"),
        )
        assertTrue(runCatching { Safety.shared("https://a.com https://b.com") }.isFailure)
        assertTrue(runCatching { Safety.shared("not a url") }.isFailure)
    }

    @Test
    fun rejectsPrivateSpecialAndReservedAddresses() {
        listOf(
                "127.0.0.1",
                "10.1.2.3",
                "172.16.0.1",
                "192.168.1.1",
                "169.254.169.254",
                "100.64.0.1",
                "0.0.0.0",
                "224.0.0.1",
                "198.18.0.1",
                "::1",
                "fc00::1",
                "fe80::1",
                "2001:db8::1",
            )
            .forEach { assertFalse(it, Safety.publicAddress(InetAddress.getByName(it))) }
        assertTrue(Safety.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(Safety.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun sanitizesTraversalAndBidi() {
        val name = Safety.filename("../../video\u202Eevil\n", "mp4")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\u202E'))
        assertFalse(name.contains('\n'))
        assertTrue(name.endsWith(".mp4"))
        assertTrue(runCatching { Safety.filename("file", "../exe") }.isFailure)
    }

    @Test
    fun unknownAndInvalidTotalsStayIndeterminate() {
        assertNull(Safety.progress(100, null))
        assertNull(Safety.progress(100, 0))
        assertEquals(0.5f, Safety.progress(50, 100))
        assertEquals(1f, Safety.progress(Long.MAX_VALUE, 100))
        assertEquals(0f, Safety.progress(-1, 100))
    }

    @Test
    fun boundsUntrustedInput() {
        assertTrue(runCatching { Safety.url("https://example.com/" + "a".repeat(8192)) }.isFailure)
        assertTrue(Safety.filename("a".repeat(5000), "mp4").length <= 104)
    }
}
