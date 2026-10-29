package com.os73.dosos73

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Bounded-memory verification even when the provider cannot report a size. */
internal object StreamIntegrity {
    data class Fingerprint(val bytes: Long, val sha256: ByteArray)

    fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            bytes = Math.addExact(bytes, count.toLong())
            digest.update(buffer, 0, count)
        }
        return Fingerprint(bytes, digest.digest())
    }

    fun requireSame(expected: Fingerprint, actual: Fingerprint) {
        if (
            expected.bytes != actual.bytes || !MessageDigest.isEqual(expected.sha256, actual.sha256)
        )
            throw IOException("Output integrity mismatch")
    }
}
