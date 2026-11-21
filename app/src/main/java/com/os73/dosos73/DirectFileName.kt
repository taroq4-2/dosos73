package com.os73.dosos73

import java.net.URLDecoder

/** Treat server filenames as untrusted labels, never filesystem paths. */
internal object DirectFileName {
    /** MIME types that only say "some ZIP container"; a ZIP-based URL extension is more precise. */
    private val genericZip = setOf("application/zip", "application/x-zip-compressed")

    fun expectsBinary(choice: Choice): Boolean = choice.kind != Kind.FILE || FileTypes.isBinary(choice.extension)

    fun resolve(disposition: String?, path: String, mime: String): Pair<String, String> {
        val header = disposition.orEmpty().take(8192)
        // RFC 6266 / RFC 5987: filename* takes precedence; UTF-8 and ISO-8859-1 are defined.
        val encoded =
            Regex("(?:^|;)\\s*filename\\*\\s*=\\s*(UTF-8|ISO-8859-1)'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
                .find(header)
        val decoded =
            encoded?.let {
                val charset = if (it.groupValues[1].equals("UTF-8", true)) "UTF-8" else "ISO-8859-1"
                runCatching { URLDecoder.decode(it.groupValues[2].trim().replace("+", "%2B"), charset) }
                    .getOrNull()
            }
        val ordinary =
            Regex(
                    "(?:^|;)\\s*filename\\s*=\\s*\"([^\"]*)\"|(?:^|;)\\s*filename\\s*=\\s*([^;]+)",
                    RegexOption.IGNORE_CASE,
                )
                .find(header)
                ?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
        val label =
            (decoded ?: ordinary ?: path)
                .replace('\\', '/')
                .substringAfterLast('/')
                // Control and bidi/format characters can disguise an extension ("gpj.exe" spoofing).
                .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
                .trim()
                .take(200)
                .ifBlank { "download" }
        val suffix =
            label.substringAfterLast('.', "").lowercase().takeIf {
                it.matches(Regex("[a-z0-9]{1,8}"))
            }
        val normalized = mime.lowercase()
        val fromMime = FileTypes.extensionForMime(normalized)
        val suffixType = suffix?.let(FileTypes::forExtension)
        val extension = when {
            // An APK/EPUB/DOCX served as plain ZIP keeps its specific extension.
            normalized in genericZip && suffixType?.signature?.invoke(ZIP_HEADER) == true -> suffixType.extension
            fromMime != null -> fromMime
            suffix != null -> suffix
            else -> "bin"
        }
        return label.substringBeforeLast('.', label).ifBlank { "download" } to extension
    }

    private val ZIP_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
}
