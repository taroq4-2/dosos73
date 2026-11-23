package com.os73.dosos73

/** Library grouping of a saved file. Independent of [Kind], which drives media validation. */
enum class FileCategory { VIDEO, AUDIO, IMAGE, DOCUMENT, ARCHIVE, APP, PROGRAM, OTHER }

/**
 * One registry for every file type dosos73 recognises as a direct download.
 *
 * Rules (see AUDIT_BASELINE.md P0-1/P0-2):
 *  - The declared MIME type and the URL extension are hints, never proof.
 *  - When a type has a known signature, the first bytes must match it before the file is
 *    treated as that type, and again after the transfer before it is published.
 *  - An HTML document is never a downloadable file, whatever its URL or headers claim.
 */
internal object FileTypes {
    class Type(
        val extension: String,
        val category: FileCategory,
        val mimes: Set<String>,
        /** Null when the format has no reliable leading signature (text, DMG, ISO...). */
        val signature: ((ByteArray) -> Boolean)?,
        /** Mime type published to MediaStore/SAF. */
        val publishMime: String = mimes.firstOrNull() ?: "application/octet-stream",
    )

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean =
        size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

    private val ZIP = { h: ByteArray ->
        h.startsWith(bytes(0x50, 0x4B, 0x03, 0x04)) || h.startsWith(bytes(0x50, 0x4B, 0x05, 0x06)) ||
            h.startsWith(bytes(0x50, 0x4B, 0x07, 0x08))
    }
    private val OLE = { h: ByteArray -> h.startsWith(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) }
    private val PE = { h: ByteArray -> h.startsWith("MZ".toByteArray()) }
    private val ISO_BMFF = { h: ByteArray -> h.startsWith("ftyp".toByteArray(), at = 4) }
    private val EBML = { h: ByteArray -> h.startsWith(bytes(0x1A, 0x45, 0xDF, 0xA3)) }
    private fun riff(form: String) = { h: ByteArray ->
        h.startsWith("RIFF".toByteArray()) && h.startsWith(form.toByteArray(), at = 8)
    }
    private val MP3 = { h: ByteArray ->
        h.startsWith("ID3".toByteArray()) ||
            (h.size >= 2 && (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xE0) == 0xE0)
    }
    private val ADTS = { h: ByteArray ->
        h.size >= 2 && (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xF6) == 0xF0
    }

    private val all: List<Type> = listOf(
        // Android packages. XAPK/APKS/AAB are ZIP containers that Android cannot install directly.
        Type("apk", FileCategory.APP, setOf("application/vnd.android.package-archive",
            "application/x-android-package", "application/x-apk"), ZIP),
        Type("xapk", FileCategory.APP, setOf("application/xapk-package-archive"), ZIP,
            publishMime = "application/octet-stream"),
        Type("apks", FileCategory.APP, setOf("application/x-apks"), ZIP, publishMime = "application/octet-stream"),
        Type("apkm", FileCategory.APP, emptySet(), ZIP, publishMime = "application/octet-stream"),
        Type("aab", FileCategory.APP, emptySet(), ZIP, publishMime = "application/octet-stream"),
        // Archives.
        Type("zip", FileCategory.ARCHIVE, setOf("application/zip", "application/x-zip-compressed"), ZIP),
        Type("rar", FileCategory.ARCHIVE, setOf("application/vnd.rar", "application/x-rar-compressed",
            "application/x-rar"), { it.startsWith("Rar!".toByteArray() + bytes(0x1A, 0x07)) }),
        Type("7z", FileCategory.ARCHIVE, setOf("application/x-7z-compressed"),
            { it.startsWith(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) }),
        Type("gz", FileCategory.ARCHIVE, setOf("application/gzip", "application/x-gzip"),
            { it.startsWith(bytes(0x1F, 0x8B)) }),
        Type("tgz", FileCategory.ARCHIVE, emptySet(), { it.startsWith(bytes(0x1F, 0x8B)) },
            publishMime = "application/gzip"),
        Type("xz", FileCategory.ARCHIVE, setOf("application/x-xz"),
            { it.startsWith(bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) }),
        Type("bz2", FileCategory.ARCHIVE, setOf("application/x-bzip2"), { it.startsWith("BZh".toByteArray()) }),
        Type("zst", FileCategory.ARCHIVE, setOf("application/zstd"),
            { it.startsWith(bytes(0x28, 0xB5, 0x2F, 0xFD)) }),
        Type("tar", FileCategory.ARCHIVE, setOf("application/x-tar"), null),
        // Documents.
        Type("pdf", FileCategory.DOCUMENT, setOf("application/pdf"), { it.startsWith("%PDF-".toByteArray()) }),
        Type("epub", FileCategory.DOCUMENT, setOf("application/epub+zip"), ZIP),
        Type("docx", FileCategory.DOCUMENT,
            setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"), ZIP),
        Type("xlsx", FileCategory.DOCUMENT,
            setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), ZIP),
        Type("pptx", FileCategory.DOCUMENT,
            setOf("application/vnd.openxmlformats-officedocument.presentationml.presentation"), ZIP),
        Type("odt", FileCategory.DOCUMENT, setOf("application/vnd.oasis.opendocument.text"), ZIP),
        Type("ods", FileCategory.DOCUMENT, setOf("application/vnd.oasis.opendocument.spreadsheet"), ZIP),
        Type("doc", FileCategory.DOCUMENT, setOf("application/msword"), OLE),
        Type("xls", FileCategory.DOCUMENT, setOf("application/vnd.ms-excel"), OLE),
        Type("ppt", FileCategory.DOCUMENT, setOf("application/vnd.ms-powerpoint"), OLE),
        Type("txt", FileCategory.DOCUMENT, setOf("text/plain"), null),
        Type("csv", FileCategory.DOCUMENT, setOf("text/csv"), null),
        Type("json", FileCategory.DOCUMENT, setOf("application/json"), null),
        Type("xml", FileCategory.DOCUMENT, setOf("application/xml"), null),
        // Desktop programs and disk images: stored only, never executed by the app.
        Type("exe", FileCategory.PROGRAM, setOf("application/vnd.microsoft.portable-executable",
            "application/x-msdownload", "application/x-msdos-program", "application/x-dosexec"), PE),
        Type("msi", FileCategory.PROGRAM, setOf("application/x-msi", "application/x-ms-installer"), OLE),
        Type("dmg", FileCategory.PROGRAM, setOf("application/x-apple-diskimage"), null),
        Type("pkg", FileCategory.PROGRAM, setOf("application/vnd.apple.installer+xml"), null),
        Type("deb", FileCategory.PROGRAM, setOf("application/vnd.debian.binary-package"),
            { it.startsWith("!<arch>".toByteArray()) }),
        Type("rpm", FileCategory.PROGRAM, setOf("application/x-rpm"),
            { it.startsWith(bytes(0xED, 0xAB, 0xEE, 0xDB)) }),
        Type("appimage", FileCategory.PROGRAM, emptySet(), { it.startsWith(bytes(0x7F, 0x45, 0x4C, 0x46)) },
            publishMime = "application/octet-stream"),
        Type("jar", FileCategory.PROGRAM, setOf("application/java-archive"), ZIP),
        Type("iso", FileCategory.PROGRAM, setOf("application/x-iso9660-image"), null),
        Type("img", FileCategory.PROGRAM, emptySet(), null, publishMime = "application/octet-stream"),
        Type("bin", FileCategory.OTHER, setOf("application/octet-stream"), null),
        // Images.
        Type("jpg", FileCategory.IMAGE, setOf("image/jpeg"), { it.startsWith(bytes(0xFF, 0xD8, 0xFF)) }),
        Type("png", FileCategory.IMAGE, setOf("image/png"),
            { it.startsWith(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) }),
        Type("gif", FileCategory.IMAGE, setOf("image/gif"), { it.startsWith("GIF8".toByteArray()) }),
        Type("webp", FileCategory.IMAGE, setOf("image/webp"), riff("WEBP")),
        Type("heic", FileCategory.IMAGE, setOf("image/heic", "image/heif"), ISO_BMFF),
        Type("avif", FileCategory.IMAGE, setOf("image/avif"), ISO_BMFF),
        Type("bmp", FileCategory.IMAGE, setOf("image/bmp"), { it.startsWith("BM".toByteArray()) }),
        Type("svg", FileCategory.IMAGE, setOf("image/svg+xml"), null),
        // Media.
        Type("mp4", FileCategory.VIDEO, setOf("video/mp4"), ISO_BMFF),
        Type("m4v", FileCategory.VIDEO, setOf("video/x-m4v"), ISO_BMFF),
        Type("mov", FileCategory.VIDEO, setOf("video/quicktime"), ISO_BMFF),
        Type("3gp", FileCategory.VIDEO, setOf("video/3gpp"), ISO_BMFF),
        Type("mkv", FileCategory.VIDEO, setOf("video/x-matroska"), EBML),
        Type("webm", FileCategory.VIDEO, setOf("video/webm", "audio/webm"), EBML),
        Type("avi", FileCategory.VIDEO, setOf("video/x-msvideo"), riff("AVI ")),
        Type("ts", FileCategory.VIDEO, setOf("video/mp2t"), { it.isNotEmpty() && it[0] == 0x47.toByte() }),
        Type("mp3", FileCategory.AUDIO, setOf("audio/mpeg", "audio/mp3"), MP3),
        Type("m4a", FileCategory.AUDIO, setOf("audio/mp4", "audio/x-m4a"), ISO_BMFF),
        Type("aac", FileCategory.AUDIO, setOf("audio/aac"), ADTS),
        Type("ogg", FileCategory.AUDIO, setOf("audio/ogg"), { it.startsWith("OggS".toByteArray()) }),
        Type("opus", FileCategory.AUDIO, setOf("audio/opus"), { it.startsWith("OggS".toByteArray()) }),
        Type("flac", FileCategory.AUDIO, setOf("audio/flac", "audio/x-flac"), { it.startsWith("fLaC".toByteArray()) }),
        Type("wav", FileCategory.AUDIO, setOf("audio/wav", "audio/x-wav", "audio/wave"), riff("WAVE")),
    )

    private val byExtension = all.associateBy { it.extension }
    private val byMime: Map<String, Type> = buildMap {
        for (type in all) for (mime in type.mimes) putIfAbsent(mime, type)
    }

    /** MIME types that say nothing about the content; the URL/filename extension decides. */
    val opaqueMimes = setOf(
        "", "application/octet-stream", "binary/octet-stream", "application/binary", "application/download",
        "application/force-download", "application/x-download", "application/unknown",
    )

    fun forExtension(extension: String): Type? = byExtension[extension.lowercase()]

    fun forMime(mime: String): Type? = byMime[mime.lowercase()]

    /** The extension implied by a MIME type, or null for opaque/unknown types. */
    fun extensionForMime(mime: String): String? =
        mime.lowercase().takeUnless { it in opaqueMimes }?.let { byMime[it]?.extension }

    fun category(extension: String): FileCategory = forExtension(extension)?.category ?: FileCategory.OTHER

    fun publishMime(extension: String): String? = forExtension(extension)?.publishMime

    /** Formats whose content is binary; a text/JSON envelope can never be one of them. */
    fun isBinary(extension: String): Boolean =
        forExtension(extension)?.let { it.signature != null || it.category in BINARY_CATEGORIES } ?: false

    private val BINARY_CATEGORIES =
        setOf(FileCategory.APP, FileCategory.ARCHIVE, FileCategory.PROGRAM, FileCategory.VIDEO, FileCategory.AUDIO)

    /** The registered type whose signature matches [header], if any. ZIP-based types report "zip". */
    fun sniff(header: ByteArray): Type? = when {
        header.isEmpty() -> null
        ZIP(header) -> byExtension.getValue("zip")
        ISO_BMFF(header) -> byExtension.getValue(isoBrand(header))
        else -> all.firstOrNull { it.signature?.invoke(header) == true }
    }

    /** ISO base media files share "ftyp"; the major brand tells MP4, M4A, MOV, 3GP and HEIF/AVIF apart. */
    private fun isoBrand(header: ByteArray): String {
        val brand = if (header.size >= 12) String(header, 8, 4, Charsets.ISO_8859_1).lowercase() else ""
        return when {
            brand in setOf("heic", "heix", "mif1", "msf1", "hevc") -> "heic"
            brand == "avif" || brand == "avis" -> "avif"
            brand == "m4a " || brand == "m4b " -> "m4a"
            brand == "qt  " -> "mov"
            brand.startsWith("3g") -> "3gp"
            else -> "mp4"
        }
    }

    /** True when the leading bytes are an HTML document (optionally after a BOM and whitespace). */
    fun looksLikeHtml(header: ByteArray): Boolean {
        val text = String(header, 0, minOf(header.size, 1024), Charsets.ISO_8859_1)
            .removePrefix("ï»¿").trimStart().lowercase()
        return listOf("<!doctype html", "<html", "<head", "<body", "<script", "<meta", "<title", "<!--")
            .any { text.startsWith(it) }
    }

    sealed interface Verdict {
        data object Match : Verdict
        /** Nothing to compare against (no signature, or no bytes yet). */
        data object Unverifiable : Verdict
        data object WebPage : Verdict
        data class Mismatch(val detected: String?) : Verdict
    }

    /** Compares the leading bytes of a body with the type the user is about to save it as. */
    fun verify(extension: String, header: ByteArray): Verdict {
        if (looksLikeHtml(header)) return Verdict.WebPage
        val signature = forExtension(extension)?.signature ?: return Verdict.Unverifiable
        if (header.isEmpty()) return Verdict.Unverifiable
        return if (signature(header)) Verdict.Match else Verdict.Mismatch(sniff(header)?.extension)
    }

    /** Bytes read from the start of a body for [sniff] and [verify]. */
    const val HEADER_BYTES = 4096
}
