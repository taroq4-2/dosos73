package com.os73.dosos73

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ProcessLeaseTest {
    private class Child : Process() {
        val stops = AtomicInteger()
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { stops.incrementAndGet() }
    }
    @Test fun cancellationBeforeRegistrationClosesTheLateProcess() {
        val lease = ProcessLease { it.destroy() }
        val child = Child()
        lease.cancel()
        assertThrows(java.io.InterruptedIOException::class.java) { lease.attach(child) }
        assertEquals(1, child.stops.get())
    }
    @Test fun cancellationAndRegistrationCannotLeaveAnUnownedProcess() {
        val pool = Executors.newFixedThreadPool(2)
        try { repeat(200) {
            val child = Child()
            val lease = ProcessLease { it.destroy() }
            val start = CountDownLatch(1)
            val attach = pool.submit { start.await(); runCatching { lease.attach(child) } }
            val cancel = pool.submit { start.await(); lease.cancel() }
            start.countDown(); attach.get(2, TimeUnit.SECONDS); cancel.get(2, TimeUnit.SECONDS)
            assertTrue(child.stops.get() >= 1)
            assertThrows(java.io.InterruptedIOException::class.java) { lease.checkActive() }
        } } finally { pool.shutdownNow() }
    }
    @Test fun detachedCompletionAndANewInvocationDoNotShareCancellation() {
        val first = Child(); val next = Child()
        val oldLease = ProcessLease { it.destroy() }
        oldLease.attach(first); oldLease.detach(first); oldLease.cancel()
        val fresh = ProcessLease { it.destroy() }
        fresh.attach(next); fresh.checkActive()
        assertEquals(0, first.stops.get()); assertEquals(0, next.stops.get())
        fresh.cancel(); assertEquals(1, next.stops.get())
    }
}
