package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class SpeedMeterTest {
    private val second = 1_000_000_000L

    @Test
    fun steadyTransferReportsItsRealRate() {
        val meter = SpeedMeter()
        meter.sample(0, 0)
        var bytes = 0L
        var speed = 0L
        for (i in 1..40) { bytes += 250_000; speed = meter.sample(bytes, i * second / 4) } // 1 MB/s
        assertEquals(1_000_000.0, speed.toDouble(), 1_000.0)
    }

    @Test
    fun aShortStallDoesNotCollapseTheEstimate() {
        val meter = SpeedMeter()
        meter.sample(0, 0)
        var bytes = 0L
        var t = 0L
        repeat(20) { bytes += 250_000; t += second / 4; meter.sample(bytes, t) } // 1 MB/s for 5 s
        t += second / 4
        val afterStall = meter.sample(bytes, t) // one empty 250 ms window
        assertTrue("raw window says 0 B/s; smoothed must stay near 1 MB/s, was $afterStall", afterStall > 900_000)
    }

    @Test
    fun aRealSlowdownIsFollowedWithinSeconds() {
        val meter = SpeedMeter()
        meter.sample(0, 0)
        var bytes = 0L
        var t = 0L
        repeat(20) { bytes += 250_000; t += second / 4; meter.sample(bytes, t) }
        var speed = 0L
        repeat(40) { bytes += 25_000; t += second / 4; speed = meter.sample(bytes, t) } // 100 KB/s for 10 s
        assertTrue("should approach 100 KB/s, was $speed", speed < 150_000)
    }

    @Test
    fun noTimeOrBackwardsClockNeverDividesByZero() {
        val meter = SpeedMeter()
        assertEquals(0L, meter.sample(10, 5))
        assertEquals(0L, meter.sample(20, 5))
        assertEquals(0L, meter.sample(30, 1))
    }
}
