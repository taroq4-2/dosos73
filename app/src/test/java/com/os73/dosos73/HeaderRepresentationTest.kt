package com.os73.dosos73

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class HeaderRepresentationTest {
    private fun response(vararg headers: Pair<String, String>): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/file").build()).protocol(Protocol.HTTP_1_1)
        .code(206).message("Partial Content").body(ByteArray(0).toResponseBody()).apply {
            headers.forEach { (name, value) -> addHeader(name, value) }
        }.build()
    @Test fun finalIdentityCannotHideAPrecedingContentEncoding() {
        for (values in listOf(listOf("gzip", "identity"), listOf("identity", "gzip"), listOf("gzip, identity"), listOf("br", "identity"))) {
            response(*values.map { "Content-Encoding" to it }.toTypedArray()).use {
                assertThrows(java.io.IOException::class.java) { HttpRepresentation.requireIdentity(it) }
            }
        }
    }
    @Test fun conflictingRepresentationHeadersAreRejectedWithoutSplittingOpaqueEtags() {
        for ((name, first, second) in listOf(Triple("Content-Length", "5", "10"), Triple("Content-Type", "text/html", "video/mp4"),
            Triple("Content-Range", "bytes 0-4/10", "bytes 5-9/10"), Triple("ETag", "\"one\"", "\"two\""))) {
            response(name to first, name to second).use { assertThrows(java.io.IOException::class.java) { HttpRepresentation.requireIdentity(it) } }
        }
        response("ETag" to "\"one,two\"").use { HttpRepresentation.requireIdentity(it) }
    }
    @Test fun validIdentityAndIdenticalRepeatedHeadersRemainUsable() {
        response("Content-Encoding" to "identity, identity", "Content-Encoding" to "IDENTITY",
            "Content-Type" to "video/mp4", "Content-Type" to "video/mp4", "Content-Length" to "5", "Content-Length" to "5")
            .use { HttpRepresentation.requireIdentity(it) }
        response().use { HttpRepresentation.requireIdentity(it) }
    }
}
