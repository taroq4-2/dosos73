package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class NotificationGateTest {
    @Test fun progressIsBoundedButPhaseChangesAreImmediate() {
        val gate = NotificationGate()
        assertTrue(gate.accept(Phase.DOWNLOADING, 1000))
        for (time in 1001L until 2000L) assertFalse(gate.accept(Phase.DOWNLOADING, time))
        assertTrue(gate.accept(Phase.DOWNLOADING, 2000))
        assertTrue(gate.accept(Phase.SAVING, 2001))
        assertFalse(gate.accept(Phase.SAVING, 2002))
        assertTrue(gate.accept(Phase.WAITING, 2003))
    }
}
