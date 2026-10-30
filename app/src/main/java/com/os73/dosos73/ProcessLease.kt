package com.os73.dosos73

import java.io.InterruptedIOException

/** Cancellation is sticky for one invocation, including the start-before-registration window. */
internal class ProcessLease(private val terminate: (Process) -> Unit) {
    private val lock = Any()
    @Volatile private var cancelled = false
    private var process: Process? = null

    fun checkActive() { if (cancelled) throw InterruptedIOException("Cancelled") }

    fun attach(started: Process) {
        val stop = synchronized(lock) { process = started; cancelled }
        if (stop) terminate(started)
        checkActive()
    }

    fun cancel() {
        val active = synchronized(lock) { cancelled = true; process }
        active?.let(terminate)
    }

    fun detach(completed: Process) = synchronized(lock) {
        if (process === completed) process = null
    }
}
