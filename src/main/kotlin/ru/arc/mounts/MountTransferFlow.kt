package ru.arc.mounts

import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Spawn-owned mount escrow. Call from the main thread; continuations use the owning lifecycle scope.
 * A permission marker is written with the entitlement mutation, so crash recovery cannot grant twice.
 * The shared ledger owns redemption replay protection; this journal owns only mount payload/recovery.
 */
class MountTransferFlow(
    val store: MountTransferStore,
    private val ownership: MountTransferOwnership,
    private val ledger: OneTimeUseLedger,
    private val runSync: (() -> Unit) -> Unit,
    private val otherBusy: (UUID) -> Boolean = { false },
) {
    private val executing = mutableSetOf<UUID>()

    internal fun isBusyWithoutOther(playerId: UUID): Boolean = playerId in executing || store.records().any {
        it.issuer == playerId && it.stage in setOf(MountTransferStage.PACKING, MountTransferStage.DELIVERING) ||
            it.recipient == playerId && it.stage in setOf(MountTransferStage.CLAIMING, MountTransferStage.APPLIED)
    }

    fun isBusy(playerId: UUID): Boolean = isBusyWithoutOther(playerId) || otherBusy(playerId)

    fun pack(playerId: UUID, mount: MountDefinition, done: (Result<MountTransferRecord>) -> Unit) {
        if (!ledger.available || isBusy(playerId) || otherBusy(playerId)) {
            done(Result.failure(IllegalStateException("Transfer unavailable or busy"))); return
        }
        executing += playerId
        continueSyncCall(playerId, done, { ownership.snapshot(playerId, mount) }) { permissions ->
            val record = MountTransferRecord(UUID.randomUUID(), playerId, mount.id, permissions)
            store.save(record)
            finishPacking(record, mount, done)
        }
    }

    fun recover(record: MountTransferRecord, mount: MountDefinition, done: (Result<MountTransferRecord>) -> Unit) {
        val playerId = record.recipient ?: record.issuer
        if (!ledger.available || playerId in executing || otherBusy(playerId)) return
        executing += playerId
        when (record.stage) {
            MountTransferStage.PACKING -> finishPacking(record, mount, done)
            MountTransferStage.CLAIMING, MountTransferStage.APPLIED -> claim(record, mount, done)
            else -> finish(playerId, done, Result.success(record))
        }
    }

    private fun finishPacking(record: MountTransferRecord, mount: MountDefinition, done: (Result<MountTransferRecord>) -> Unit) {
        continueSyncVoidCall(record.issuer, done, { ownership.pack(record, mount) }) {
            val packed = record.copy(stage = MountTransferStage.PACKED)
            store.save(packed)
            finish(record.issuer, done, Result.success(packed))
        }
    }

    fun redeem(playerId: UUID, id: UUID, mount: MountDefinition, done: (Result<MountTransferRecord>) -> Unit) {
        val record = store.get(id)
        if (record == null || record.mountId != mount.id || record.stage != MountTransferStage.AVAILABLE ||
            !ledger.available || isBusy(playerId) || otherBusy(playerId)
        ) { done(Result.failure(IllegalStateException("Certificate unavailable"))); return }
        executing += playerId
        continueSyncCall(playerId, done, { ownership.canReceive(playerId, mount) }) { allowed ->
            check(allowed) { "Recipient already owns this mount" }
            // Another holder may have started while the permission read was in flight.
            check(store.get(id) == record) { "Certificate already claimed" }
            val claiming = record.copy(stage = MountTransferStage.CLAIMING, recipient = playerId)
            store.save(claiming)
            claim(claiming, mount, done)
        }
    }

    private fun claim(record: MountTransferRecord, mount: MountDefinition, done: (Result<MountTransferRecord>) -> Unit) {
        val playerId = requireNotNull(record.recipient)
        val request = OneTimeUseClaimRequest(record.identity, record.id, playerId)
        continueSyncCall(playerId, done, { ledger.claim(request) }) claimContinuation@{ result ->
            if (result == OneTimeUseClaimResult.AlreadyConsumed && record.stage == MountTransferStage.APPLIED) {
                complete(record, done); return@claimContinuation
            }
            check(result is OneTimeUseClaimResult.Acquired) { "Certificate ledger refused claim" }
            val applicationAttempt = runCatching {
                if (record.stage == MountTransferStage.APPLIED) CompletableFuture.completedFuture(null)
                else ownership.apply(record, mount)
            }
            if (applicationAttempt.isFailure) {
                abandonAndFinish(result.claim, playerId, done, applicationAttempt.exceptionOrNull()!!)
            } else applicationAttempt.getOrThrow().whenComplete { _, failure ->
                runSync {
                    if (failure != null) {
                        abandonAndFinish(result.claim, playerId, done, failure)
                    } else {
                        val applied = record.copy(stage = MountTransferStage.APPLIED)
                        try { store.save(applied) } catch (failure: Throwable) {
                            abandonAndFinish(result.claim, playerId, done, failure)
                            return@runSync
                        }
                        val commit =
                            try {
                                ledger.commit(result.claim)
                            } catch (commitFailure: Throwable) {
                                abandonAndFinish(result.claim, playerId, done, commitFailure)
                                return@runSync
                            }
                        commit.whenComplete { committed, commitFailure ->
                            runSync {
                                if (commitFailure != null) {
                                    abandonAndFinish(result.claim, playerId, done, commitFailure)
                                } else {
                                    if (committed !in setOf(OneTimeUseCommitResult.COMMITTED, OneTimeUseCommitResult.ALREADY_COMMITTED)) {
                                        abandonAndFinish(
                                            result.claim,
                                            playerId,
                                            done,
                                            IllegalStateException("Certificate ledger rejected commit"),
                                        )
                                    } else {
                                        try { complete(applied, done) } catch (failure: Throwable) {
                                            finish(playerId, done, Result.failure(failure))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun complete(record: MountTransferRecord, done: (Result<MountTransferRecord>) -> Unit) {
        val consumed = record.copy(stage = MountTransferStage.CONSUMED)
        store.save(consumed)
        finish(requireNotNull(record.recipient), done, Result.success(consumed))
    }

    private fun <T> continueSync(
        future: CompletableFuture<T>, playerId: UUID, done: (Result<MountTransferRecord>) -> Unit, next: (T) -> Unit,
    ) {
        future.whenComplete { value, failure -> runSync {
            if (failure != null) finish(playerId, done, Result.failure(failure))
            else try { next(value) } catch (failure: Throwable) { finish(playerId, done, Result.failure(failure)) }
        } }
    }

    private fun <T> continueSyncCall(
        playerId: UUID,
        done: (Result<MountTransferRecord>) -> Unit,
        call: () -> CompletableFuture<T>,
        next: (T) -> Unit,
    ) {
        val future = try { call() } catch (failure: Throwable) {
            finish(playerId, done, Result.failure(failure)); return
        }
        continueSync(future, playerId, done, next)
    }

    private fun continueSyncVoidCall(
        playerId: UUID,
        done: (Result<MountTransferRecord>) -> Unit,
        call: () -> CompletableFuture<Void>,
        next: () -> Unit,
    ) {
        val future = try { call() } catch (failure: Throwable) {
            finish(playerId, done, Result.failure(failure)); return
        }
        future.whenComplete { _, failure ->
            runSync {
                if (failure != null) finish(playerId, done, Result.failure(failure))
                else try { next() } catch (nextFailure: Throwable) { finish(playerId, done, Result.failure(nextFailure)) }
            }
        }
    }

    private fun abandonAndFinish(
        claim: OneTimeUseClaim,
        playerId: UUID,
        done: (Result<MountTransferRecord>) -> Unit,
        failure: Throwable,
    ) {
        val abandon = try { ledger.abandon(claim) } catch (_: Throwable) {
            finish(playerId, done, Result.failure(failure)); return
        }
        abandon.whenComplete { _, _ -> runSync { finish(playerId, done, Result.failure(failure)) } }
    }

    private fun finish(playerId: UUID, done: (Result<MountTransferRecord>) -> Unit, result: Result<MountTransferRecord>) {
        executing -= playerId
        done(result)
    }
}
