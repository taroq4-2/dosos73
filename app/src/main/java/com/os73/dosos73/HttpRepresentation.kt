package com.os73.dosos73

import java.io.IOException
import okhttp3.Response

internal object HttpRepresentation {
    private val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    data class ByteRange(val first: Long, val last: Long, val total: Long)

    fun requireIdentity(response: Response) {
        val encodings = response.headers.values("Content-Encoding").flatMap { it.split(',') }.map { it.trim() }
        if (encodings.any { it.isNotEmpty() && !it.equals("identity", true) })
            throw IOException("Unsupported content encoding")
        // Never let a final header silently override a contradictory representation.
        for (name in listOf("Content-Type", "Content-Length", "Content-Range", "ETag")) {
            val values = response.headers.values(name).map { it.trim() }
            if (values.distinct().size > 1) throw IOException("Conflicting $name headers")
        }
        val mime = response.header("Content-Type").orEmpty().trim()
        if (mime.startsWith("text/html", true) || mime.startsWith("multipart/byteranges", true))
            throw IOException("Unexpected response type")
    }

    fun parseRange(value: String?): ByteRange? {
        val parts = range.matchEntire(value.orEmpty()) ?: return null
        val first = parts.groupValues[1].toLongOrNull() ?: return null
        val last = parts.groupValues[2].toLongOrNull() ?: return null
        val total = parts.groupValues[3].toLongOrNull() ?: return null
        return ByteRange(first, last, total).takeIf { first <= last && last < total }
    }
}
