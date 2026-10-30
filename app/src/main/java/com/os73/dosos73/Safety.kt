package com.os73.dosos73

import java.net.InetAddress
import java.net.URI
import java.text.Normalizer

object Safety {
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
        if (
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress
        )
            return false
        val bytes = address.address
        fun byte(index: Int) = bytes[index].toInt() and 255
        // RFC6052 translation must apply the IPv4 policy to the embedded destination.
        if (bytes.size == 16 && NAT64.indices.all { bytes[it] == NAT64[it] })
            return publicAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        if (bytes.size == 4) {
            val a = byte(0); val b = byte(1); val c = byte(2); val d = byte(3)
            return !(a == 0 || a == 127 || a >= 224 || a == 10 ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 198 && b in 18..19) ||
                (a == 192 && b == 0 && c == 0 && d !in 9..10) ||
                (a == 192 && b == 0 && c == 2) ||
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113))
        }
        if (bytes.size != 16 || (byte(0) and 0xe0) != 0x20) return false
        if (address is java.net.Inet6Address && address.scopeId != 0) return false
        // IANA special-use/documentation prefixes, plus existing transition exclusions.
        // https://www.iana.org/assignments/iana-ipv6-special-registry/
        return !((byte(0) == 0x20 && byte(1) == 0x01 && byte(2) == 0x0d && byte(3) == 0xb8) ||
            (byte(0) == 0x20 && byte(1) == 0x02) ||
            (byte(0) == 0x20 && byte(1) == 0x01 && byte(2) == 0 && byte(3) == 0) ||
            (byte(0) == 0x20 && byte(1) == 0x01 && byte(2) == 0 && byte(3) == 2 && byte(4) == 0 && byte(5) == 0) ||
            (byte(0) == 0x20 && byte(1) == 0x01 && byte(2) == 0 && (byte(3) and 0xf0) == 0x10) ||
            (byte(0) == 0x3f && byte(1) == 0xff && (byte(2) and 0xf0) == 0))
    }

    private val NAT64 = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)

    fun checkedAddresses(addresses: List<InetAddress>): List<InetAddress> = addresses.also {
        require(it.isNotEmpty() && it.all(::publicAddress)) { "Non-public network address" }
    }

    fun resolve(host: String): List<InetAddress> = checkedAddresses(InetAddress.getAllByName(host).toList())

    fun requireFilename(label: String): String = label.trim().also {
        require(it.isNotEmpty() && it.length <= 180 && it != "." && it != ".." &&
            it.none { c -> c == '/' || c == '\\' || c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() })
    }

    fun filename(title: String, ext: String): String {
        require(ext.matches(Regex("[a-zA-Z0-9]{1,8}")))
        val clean =
            Normalizer.normalize(title, Normalizer.Form.NFC)
                .replace(Regex("[\\p{Cc}\\p{Cf}/\\\\:*?\"<>|]"), "_")
                .trim(' ', '.')
                .take(100)
                .ifBlank { "download" }
        return "$clean.${ext.lowercase()}"
    }

    fun progress(done: Long, total: Long?): Float? =
        total?.takeIf { it > 0 }?.let { (done.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}
