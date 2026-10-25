package com.os73.dosos73

import java.net.InetAddress
import java.net.URI
import java.text.Normalizer

object Safety {
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    fun url(input: String): String {
        require(input.length <= 8192 && input.none { it.isISOControl() })
        val u = URI(input.trim())
        require(u.scheme?.lowercase() in setOf("http", "https"))
        require(u.userInfo == null && !u.host.isNullOrBlank())
        require(u.port == -1 || u.port in listOf(80, 443))
        require(!u.host.equals("localhost", true) && !u.host.endsWith(".local", true))
        return u.toASCIIString()
    }
    fun shared(text: String): String {
        require(text.length <= 32768)
        val matches = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE).findAll(text).toList()
        require(matches.size == 1) { "Choose one URL" }
        return url(matches.single().value)
    }
    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        return if (b.size == 4) {
            !(b[0] == 0 || b[0] == 127 || b[0] >= 224 || b[0] == 10 ||
                (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
                (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 198 && b[1] in 18..19) ||
                (b[0] == 192 && b[1] == 0) || (b[0] == 198 && b[1] == 51) ||
                (b[0] == 203 && b[1] == 0 && b[2] == 113))
        } else b.size == 16 && (b[0] and 0xe0) == 0x20 &&
            !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
    }
    fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList().also {
        require(it.isNotEmpty() && it.all(::publicAddress)) { "Non-public network address" }
    }
    fun filename(title: String, ext: String): String {
        require(ext.matches(Regex("[a-zA-Z0-9]{1,8}")))
        val clean = Normalizer.normalize(title, Normalizer.Form.NFC)
            .replace(Regex("[\\p{Cc}\\p{Cf}/\\\\:*?\"<>|]"), "_")
            .trim(' ', '.').take(100).ifBlank { "download" }
        return "$clean.${ext.lowercase()}"
    }
    fun progress(done: Long, total: Long?): Float? = total?.takeIf { it > 0 }
        ?.let { (done.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}
