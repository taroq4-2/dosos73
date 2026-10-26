package com.os73.dosos73

import java.net.URLDecoder

/** Treat server filenames as untrusted labels, never filesystem paths. */
internal object DirectFileName {
    private val extensions =
        mapOf(
            "application/pdf" to "pdf",
            "application/zip" to "zip",
            "video/mp4" to "mp4",
            "video/webm" to "webm",
            "audio/mp4" to "m4a",
            "audio/mpeg" to "mp3",
            "audio/ogg" to "ogg",
            "audio/opus" to "opus",
            "audio/webm" to "webm",
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "application/gzip" to "gz",
            "application/x-7z-compressed" to "7z",
        )

    fun resolve(disposition: String?, path: String, mime: String): Pair<String, String> {
        val header = disposition.orEmpty().take(8192)
        val encoded =
            Regex("(?:^|;)\\s*filename\\*=UTF-8'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
                .find(header)
                ?.groupValues
                ?.get(1)
        val decoded =
            encoded?.let {
                runCatching { URLDecoder.decode(it.trim().replace("+", "%2B"), "UTF-8") }
                    .getOrNull()
            }
        val ordinary =
            Regex(
                    "(?:^|;)\\s*filename=\"([^\"]*)\"|(?:^|;)\\s*filename=([^;]+)",
                    RegexOption.IGNORE_CASE,
                )
                .find(header)
                ?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
        val label =
            (decoded ?: ordinary ?: path)
                .replace('\\', '/')
                .substringAfterLast('/')
                .filterNot { it.isISOControl() }
                .take(200)
                .ifBlank { "download" }
        val suffix =
            label.substringAfterLast('.', "").lowercase().takeIf {
                it.matches(Regex("[a-z0-9]{1,8}"))
            }
        val extension = extensions[mime] ?: suffix ?: "bin"
        return label.substringBeforeLast('.', label).ifBlank { "download" } to extension
    }
}
