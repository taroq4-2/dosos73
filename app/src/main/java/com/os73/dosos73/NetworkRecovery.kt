package com.os73.dosos73

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

// Registrations return their own cleanup so partial registration failure cannot leak a callback.
internal typealias NetworkChangeRegistration = ((Boolean) -> Unit) -> (() -> Unit)

internal fun observeNetworkPolicy(
    permitted: () -> Boolean,
    registrations: List<NetworkChangeRegistration>,
): Flow<Boolean> = callbackFlow {
    val cleanup = mutableListOf<() -> Unit>()
    try {
        val signalLock = Any()
        var registered = false
        var unavailableSeen = false
        val changed: (Boolean) -> Unit = { unavailable ->
            synchronized(signalLock) {
                // A bound-network loss cannot be undone by a later notification
                // that the replacement default is allowed. A new attempt gets a new flow.
                unavailableSeen = unavailableSeen || unavailable
                if (registered) trySend(!unavailableSeen && permitted())
            }
            Unit
        }
        registrations.forEach { cleanup += it(changed) }
        synchronized(signalLock) {
            // Registration callbacks may fire synchronously. Expose no readiness
            // sample until every registration has succeeded.
            registered = true
            trySend(!unavailableSeen && permitted())
        }
        awaitClose { }
    } finally {
        cleanup.asReversed().forEach { runCatching { it() } }
    }
}.buffer(Channel.CONFLATED)

// Only the network guard creates this cause; ordinary/user cancellation is never retried.
internal class NetworkInterruption : CancellationException("Transfer network became unavailable")

internal suspend fun <T> guardedNetworkTransfer(
    changes: Flow<Boolean>,
    shouldInterrupt: (Boolean) -> Boolean,
    cancelTransport: () -> Unit,
    block: suspend () -> T,
): T = supervisorScope {
    val ready = CompletableDeferred<Unit>()
    val transfer = async { ready.await(); block() }
    val guard = async(start = CoroutineStart.UNDISPATCHED) {
        var sampled = false
        changes.collect { permitted ->
            if (shouldInterrupt(permitted)) {
                val interruption = NetworkInterruption()
                transfer.cancel(interruption)
                cancelTransport()
                throw interruption
            } else if (!sampled) {
                ready.complete(Unit)
            }
            sampled = true
        }
    }
    try {
        // Observer registration failures must also end the attempt, not leave it unguarded.
        select {
            transfer.onAwait { it }
            guard.onAwait { error("Network observation ended") }
        }
    } finally {
        guard.cancel()
        if (transfer.isActive) {
            transfer.cancel()
            cancelTransport()
        }
        withContext(NonCancellable) {
            guard.join()
            transfer.join()
        }
    }
}

internal suspend fun <N, T> recoverNetwork(
    awaitNetwork: suspend () -> Unit,
    acquire: () -> N,
    attempt: suspend (N) -> T,
    waiting: () -> Unit,
    allowRetry: () -> Boolean = { true },
    backoff: suspend (Long) -> Unit = { delay(it) },
): T {
    var retries = 0
    while (true) {
        var acquiring = true
        try {
            awaitNetwork()
            val network = acquire()
            acquiring = false
            return attempt(network)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val unavailable = acquiring && error is PipelineException &&
                error.code == FailureCode.NETWORK_UNAVAILABLE
            if ((error !is NetworkInterruption && !unavailable) || retries >= 2 || !allowRetry())
                throw error
            retries++
            waiting()
            backoff(1000L shl retries)
        }
    }
}