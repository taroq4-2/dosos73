package com.os73.dosos73

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the encrypted multi-link draft: restore, strictly ordered persistence, adoption of a
 * draft changed by another ViewModel/process, and the exactly-once handoff to [DownloadQueue].
 *
 * Moved out of MainViewModel in Phase C without changing its protocol: a single actor coroutine
 * serialises every write and handoff under [BatchDraft.protocolLock]; revisions invalidate stale
 * snapshots; a storage failure waits for an explicit [retry].
 *
 * @param onAdopted called after another writer's draft replaced ours; `busy` tells whether a
 *   batch operation was running and must be cancelled by the owner.
 * @param onStorageError called when a write could not even be scheduled.
 */
internal class BatchDraftSession(
    private val app: Application,
    scope: CoroutineScope,
    private val onAdopted: (busy: Boolean) -> Unit,
    private val onStorageError: () -> Unit,
) {
    private val rowsMutable = MutableStateFlow<List<BatchEntry>>(emptyList())
    val rows: StateFlow<List<BatchEntry>> = rowsMutable.asStateFlow()
    private val requestedMutable = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = requestedMutable.asStateFlow()
    private val busyMutable = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyMutable.asStateFlow()
    private val savingMutable = MutableStateFlow(true)
    val saving: StateFlow<Boolean> = savingMutable.asStateFlow()
    private val errorMutable = MutableStateFlow(false)
    val storageError: StateFlow<Boolean> = errorMutable.asStateFlow()

    /** True once the persisted draft has been restored. */
    @Volatile var ready = false
        private set

    /** No restore, write, handoff, failure or batch operation is pending: edits are allowed. */
    val idle: Boolean get() = ready && !busyMutable.value && !savingMutable.value && !errorMutable.value

    private val draft = BatchDraft(app)
    private sealed interface DraftCommand {
        data class Write(val revision: Long, val rows: List<BatchEntry>) : DraftCommand
        data class Queue(val entry: BatchEntry, val task: DownloadTask,
            val done: CompletableDeferred<Unit>) : DraftCommand
    }
    // At most twenty analysis snapshots plus one handoff; UI edits wait for saving to finish.
    private val draftWrites = Channel<DraftCommand>(32)
    private var draftRevision = 0L
    private var invalidThroughRevision = 0L
    private var durableDraftToken: String? = null
    private class DraftChanged(val latest: BatchDraftSnapshot) : IllegalStateException("Batch draft changed")
    private val draftRetries = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                try {
                    // BatchDraft.read commits legacy delivery IDs before they become sendable.
                    val restored = BatchDraft.protocolLock.withLock {
                        val snapshot = withContext(Dispatchers.IO) { draft.readSnapshot() }
                        DownloadQueue.reconcileBatch(app, snapshot.rows.map { it.deliveryId }.toSet())
                        durableDraftToken = snapshot.token
                        snapshot.rows
                    }
                    rowsMutable.value = restored
                    ready = true
                    savingMutable.value = false
                    errorMutable.value = false
                    break
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { awaitDraftRetry() }
            }
            for (command in draftWrites) {
                when (command) {
                    is DraftCommand.Write -> if (command.revision > invalidThroughRevision)
                        saveDraft(command.revision, command.rows)
                    is DraftCommand.Queue -> {
                        try {
                            BatchDraft.protocolLock.withLock {
                                val latest = withContext(Dispatchers.IO) { draft.readSnapshot() }
                                if (latest.token != durableDraftToken) throw DraftChanged(latest)
                                check(rowsMutable.value.any { it == command.entry })
                                DownloadQueue.reconcileBatch(app, latest.rows.map { it.deliveryId }.toSet())
                                DownloadQueue.enqueueBatch(app, command.entry.deliveryId, command.task)
                            }
                            rowsMutable.value = rowsMutable.value.filterNot {
                                it.deliveryId == command.entry.deliveryId
                            }
                            val revision = ++draftRevision
                            savingMutable.value = true
                            saveDraft(revision, rowsMutable.value.toList()) {
                                // Release the caller's submission UI while retaining the receipt.
                                command.done.completeExceptionally(it)
                            }
                            command.done.complete(Unit)
                        } catch (error: CancellationException) {
                            command.done.completeExceptionally(error)
                            throw error
                        } catch (error: DraftChanged) {
                            adoptLatestDraft(error.latest)
                            command.done.completeExceptionally(error)
                        } catch (error: Exception) {
                            command.done.completeExceptionally(error)
                        } finally {
                            busyMutable.value = false
                        }
                    }
                }
            }
        }
    }

    private suspend fun saveDraft(revision: Long, rows: List<BatchEntry>,
        onFailure: (Exception) -> Unit = {}) {
        while (true) {
            try {
                BatchDraft.protocolLock.withLock {
                    val latest = withContext(Dispatchers.IO) { draft.readSnapshot() }
                    if (latest.token != durableDraftToken) throw DraftChanged(latest)
                    val committed = withContext(Dispatchers.IO) { draft.writeSnapshot(rows) }
                    durableDraftToken = committed.token
                    // Never drop an acceptance receipt before the row's removal is durable.
                    DownloadQueue.reconcileBatch(app, rows.map { it.deliveryId }.toSet())
                }
                errorMutable.value = false
                if (revision == draftRevision) savingMutable.value = false
                return
            } catch (error: CancellationException) { throw error }
            catch (error: DraftChanged) {
                adoptLatestDraft(error.latest)
                onFailure(error)
                return
            }
            catch (error: Exception) {
                onFailure(error)
                awaitDraftRetry()
            }
        }
    }

    private fun adoptLatestDraft(snapshot: BatchDraftSnapshot) {
        // Invalidate every buffered snapshot from this actor, including one waiting for Retry.
        invalidThroughRevision = draftRevision
        draftRevision++
        durableDraftToken = snapshot.token
        rowsMutable.value = snapshot.rows
        savingMutable.value = false
        errorMutable.value = false
        onAdopted(busyMutable.value)
    }

    suspend fun queue(entry: BatchEntry, task: DownloadTask) {
        check(idle)
        val done = CompletableDeferred<Unit>()
        busyMutable.value = true
        // The actor owns busy until the handoff ends, even if the screen's caller is cancelled.
        draftWrites.send(DraftCommand.Queue(entry, task, done))
        done.await()
    }

    private suspend fun awaitDraftRetry() {
        // Keep the encrypted file and in-memory review; retry only on explicit user action.
        errorMutable.value = true
        savingMutable.value = false
        draftRetries.receive()
    }

    fun retry() {
        if (!errorMutable.value) return
        savingMutable.value = true
        draftRetries.trySend(Unit)
    }

    /** Replace the rows and persist them in order. Callers check [idle] (or own [busy]) first. */
    fun update(transform: (List<BatchEntry>) -> List<BatchEntry>) {
        rowsMutable.value = transform(rowsMutable.value)
        draftRevision++
        savingMutable.value = true
        if (draftWrites.trySend(DraftCommand.Write(draftRevision, rowsMutable.value.toList())).isFailure)
            onStorageError()
    }

    fun request() { requestedMutable.value = true }
    fun opened() { requestedMutable.value = false }

    /** Batch analysis holds the session busy; the actor clears it after a handoff. */
    fun setBusy(value: Boolean) { busyMutable.value = value }
}
