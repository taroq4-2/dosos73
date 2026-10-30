package com.os73.dosos73

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class SecurityReviewTest {
    @Test fun defaultCleartextPolicyBlocksBeforeAnyDnsOrSocket() {
        var lookedUp = false
        val client = DirectDownloader.secureClient().newBuilder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookedUp = true; return listOf(InetAddress.getByName("8.8.8.8"))
            }
        }).build()
        val error = runCatching { client.newCall(okhttp3.Request.Builder().url("http://example.com/file").build()).execute() }.exceptionOrNull()
        assertEquals(FailureCode.CLEARTEXT_DISABLED, (error as PipelineException).code)
        assertFalse(lookedUp)
    }
    @Test fun everyDnsAnswerIsValidatedAgainIncludingRebindingAndMixedSets() {
        val public = InetAddress.getByName("8.8.8.8")
        val local = InetAddress.getByName("127.0.0.1")
        assertEquals(listOf(public), Safety.checkedAddresses(listOf(public)))
        assertThrows(IllegalArgumentException::class.java) { Safety.checkedAddresses(listOf(local)) }
        assertThrows(IllegalArgumentException::class.java) { Safety.checkedAddresses(listOf(public, local)) }
        assertThrows(IllegalArgumentException::class.java) { Safety.checkedAddresses(emptyList()) }
    }
    @Test fun privateIpv6TranslationsAndDocumentationDoNotReachSockets() {
        for (address in listOf("::", "::1", "fc00::1", "fdff::1", "fe80::1", "ff02::1",
            "::ffff:127.0.0.1", "::ffff:192.168.0.1", "64:ff9b::a00:1", "64:ff9b:1::1",
            "2001:db8::1", "2001:2::1", "2001:10::1", "2002:7f00:1::", "3fff::1", "3fff:fff::1")) {
            assertFalse(address, Safety.publicAddress(InetAddress.getByName(address)))
        }
        for (address in listOf("2606:4700:4700::1111", "2001:4860:4860::8888", "64:ff9b::808:808"))
            assertTrue(address, Safety.publicAddress(InetAddress.getByName(address)))
        // Adjacent ordinary public ranges must not be denied by an overly broad /16 rule.
        assertTrue(Safety.publicAddress(InetAddress.getByName("198.51.101.1")))
        assertTrue(Safety.publicAddress(InetAddress.getByName("192.0.3.1")))
    }
    @Test fun dangerousRenameLabelsAndExtensionsCannotBecomePaths() {
        for (name in listOf(".", "..", "../file", "a/b", "a\\b", "line\nfile", "ab\u202Ecod.exe"))
            assertThrows(name, IllegalArgumentException::class.java) { Safety.requireFilename(name) }
        assertEquals("تسجيل.mp3", Safety.requireFilename(" تسجيل.mp3 "))
        for (ext in listOf("../bin", "a/b", "bin\n", "long-extension"))
            assertThrows(IllegalArgumentException::class.java) { Safety.filename("safe", ext) }
    }
}
