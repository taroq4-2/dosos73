package com.os73.dosos73

/** Monotonic time, immediate phase changes and at most one same-phase notification per second. */
internal class NotificationGate(private val intervalMillis: Long = 1000) {
    private var phase: Phase? = null
    private var at = 0L
    @Synchronized fun accept(next: Phase, now: Long): Boolean {
        if (phase != next || now < at || now - at >= intervalMillis) {
            phase = next; at = now; return true
        }
        return false
    }
}
