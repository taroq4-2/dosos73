package com.os73.dosos73

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class NetworkRecoveryTest {
    @Test fun acquisitionFlapReselectsWithinTheSameBoundedRecovery() = runBlocking {
        var acquisitions = 0
        var waits = 0
        val delays = mutableListOf<Long>()
        val taskIdentity = Any()
        val result = recoverNetwork(
            awaitNetwork = { waits++ },
            acquire = {
                acquisitions++
                if (acquisitions == 1) throw PipelineException(Stage.TRANSFER, FailureCode.NETWORK_UNAVAILABLE)
                "replacement"
            },
            attempt = { network -> assertEquals("replacement", network); taskIdentity },
            waiting = {},
            backoff = { delays += it },
        )
        assertSame(taskIdentity, result)
        assertEquals(2, acquisitions)
        assertEquals(2, waits)
        assertEquals(listOf(2000L), delays)
    }

    @Test fun oldBoundLossRetriesWhileTheNewDefaultRemainsPermitted() = runBlocking {
        withTimeout(5000) {
            val defaultEvent = CompletableDeferred<(Boolean) -> Unit>()
            val boundEvent = CompletableDeferred<(Boolean) -> Unit>()
            val firstTransferStarted = CompletableDeferred<Unit>()
            var acquisitions = 0
            var cancelledTransports = 0
            var cleanup = 0
            val result = async {
                recoverNetwork(
                    awaitNetwork = {},
                    acquire = { ++acquisitions },
                    attempt = { network ->
                        val changes = observeNetworkPolicy(
                            permitted = { true }, // Replacement Wi-Fi stays usable throughout.
                            registrations = listOf(
                                { changed -> defaultEvent.complete(changed); { cleanup++; Unit } },
                                { changed -> boundEvent.complete(changed); { cleanup++; Unit } },
                            ),
                        )
                        guardedNetworkTransfer(changes, { !it }, { cancelledTransports++ }) {
                            if (network == 1) {
                                firstTransferStarted.complete(Unit)
                                awaitCancellation()
                            }
                            "replacement completed"
                        }
                    },
                    waiting = {},
                    backoff = {},
                )
            }
            firstTransferStarted.await()
            defaultEvent.await()(false) // Default-route event: both policies still allow traffic.
            yield()
            assertEquals(1, acquisitions)
            assertFalse(result.isCompleted)
            boundEvent.await()(true) // Old bound cellular disappears after handover.
            defaultEvent.await()(false) // Later allowed-default notification must not erase loss.
            assertEquals("replacement completed", result.await())
            assertEquals(2, acquisitions)
            assertEquals(1, cancelledTransports)
            assertEquals(4, cleanup) // Default and bound registrations from both attempts.
        }
    }

    @Test fun failedSecondRegistrationUnregistersTheFirst() = runBlocking {
        var cleanup = 0
        val failure = SecurityException("registration rejected")
        try {
            observeNetworkPolicy(
                { true },
                listOf(
                    { _ -> { cleanup++; Unit } },
                    { _ -> throw failure },
                ),
            ).first()
            fail("Registration must fail")
        } catch (error: SecurityException) {
            assertEquals(failure.message, error.message)
            assertTrue(generateSequence(error as Throwable) { it.cause }.any { it === failure })
        }
        assertEquals(1, cleanup)
    }

    @Test fun completedWaitUnregistersEveryCallbackEvenIfOneCleanupThrows() = runBlocking {
        var cleanup = 0
        assertTrue(observeNetworkPolicy(
            { true },
            listOf(
                { _ -> { cleanup++; Unit } },
                { _ -> { cleanup++; throw IllegalStateException("unregister failed") } },
            ),
        ).first())
        assertEquals(2, cleanup)
    }

    @Test fun unavailableAcquisitionExhaustsOnlyTwoRetries() = runBlocking {
        var acquisitions = 0
        val delays = mutableListOf<Long>()
        val failure = PipelineException(Stage.TRANSFER, FailureCode.NETWORK_UNAVAILABLE)
        try {
            recoverNetwork<Nothing, Unit>({}, { acquisitions++; throw failure }, { }, {}, backoff = { delays += it })
            fail("Must exhaust recovery")
        } catch (error: PipelineException) {
            assertSame(failure, error)
        }
        assertEquals(3, acquisitions)
        assertEquals(listOf(2000L, 4000L), delays)
    }

    @Test fun unrelatedTransferFailuresAndOrdinaryCancellationNeverRetry() = runBlocking {
        val failures = listOf(
            PipelineException(Stage.TRANSFER, FailureCode.CLEARTEXT_DISABLED),
            PipelineException(Stage.TRANSFER, FailureCode.AUTHENTICATION_REQUIRED),
            PipelineException(Stage.TRANSFER, FailureCode.STORAGE_FULL),
            PipelineException(Stage.TRANSFER, FailureCode.NETWORK_UNAVAILABLE), // Transfer, not acquisition.
            IOException("disk write failed"),
            CancellationException("user paused"),
        )
        for (failure in failures) {
            var acquisitions = 0
            try {
                recoverNetwork({ }, { ++acquisitions }, { throw failure }, {}, backoff = { fail("Unexpected retry") })
                fail("Failure must propagate")
            } catch (error: Exception) {
                assertSame(failure, error)
            }
            assertEquals(1, acquisitions)
        }
    }

    @Test fun transferWaitsForAllRegistrationsBeforeOpeningItsTransport() = runBlocking {
        withTimeout(5000) {
            val registrationStarted = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val opened = java.util.concurrent.atomic.AtomicBoolean(false)
            val changes = observeNetworkPolicy({ true }, listOf(
                { changed ->
                    changed(false) // A real registration can call back before returning.
                    registrationStarted.complete(Unit)
                    check(release.await(4, java.util.concurrent.TimeUnit.SECONDS))
                    val unregister: () -> Unit = { }
                    unregister
                },
            )).flowOn(Dispatchers.IO)
            val result = async { guardedNetworkTransfer(changes, { !it }, {}) { opened.set(true); 42 } }
            try {
                registrationStarted.await()
                delay(100)
                assertFalse("Transport opened before observation was registered", opened.get())
            } finally { release.countDown() }
            assertEquals(42, result.await())
        }
    }

    @Test fun initialUnavailableSamplePreventsTheTransferFromStarting() = runBlocking {
        var opened = false
        try {
            guardedNetworkTransfer(flow { emit(false); awaitCancellation() }, { !it }, {}) { opened = true }
            fail("Unavailable bound network must interrupt")
        } catch (_: NetworkInterruption) { }
        assertFalse(opened)
    }

    @Test fun observerRegistrationFailureEndsTheGuardedTransfer() = runBlocking {
        val failure = SecurityException("callback registration denied")
        try {
            guardedNetworkTransfer(
                changes = flow { throw failure },
                shouldInterrupt = { !it },
                cancelTransport = {},
            ) { awaitCancellation() }
            fail("Observer failure must propagate")
        } catch (error: SecurityException) {
            assertEquals(failure.message, error.message)
            assertTrue(generateSequence(error as Throwable) { it.cause }.any { it === failure })
        }
    }
}