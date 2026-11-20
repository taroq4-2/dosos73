package com.os73.dosos73

import kotlin.math.exp

/**
 * Transfer speed for display. Raw 250 ms windows jump with every network hiccup, which made the
 * "time left" swing (seen on the emulator in Phase D). This keeps a time-weighted exponential
 * average with a [timeConstantNanos] of about three seconds: it follows real changes within a few
 * seconds but ignores short spikes. Purely presentational; byte accounting is untouched.
 */
internal class SpeedMeter(private val timeConstantNanos: Double = 3_000_000_000.0) {
    private var lastBytes = -1L
    private var lastNanos = 0L
    private var average = -1.0

    /** Records the byte count at [nowNanos]; returns the smoothed bytes per second. */
    fun sample(bytes: Long, nowNanos: Long): Long {
        if (lastBytes < 0 || nowNanos <= lastNanos) {
            lastBytes = bytes; lastNanos = nowNanos
            return average.coerceAtLeast(0.0).toLong()
        }
        val elapsed = (nowNanos - lastNanos).toDouble()
        val instant = (bytes - lastBytes).coerceAtLeast(0) * 1_000_000_000.0 / elapsed
        val weight = 1 - exp(-elapsed / timeConstantNanos)
        average = if (average < 0) instant else average + weight * (instant - average)
        lastBytes = bytes
        lastNanos = nowNanos
        return average.toLong()
    }
}
