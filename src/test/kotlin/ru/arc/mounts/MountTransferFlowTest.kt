package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MountTransferFlowTest : StringSpec({
    "preowned recipient is rejected before claiming the ledger" {
        val fixture = FlowFixture(allowedToReceive = false)
        var outcome: Result<MountTransferRecord>? = null

        fixture.flow.redeem(fixture.recipient, fixture.id, fixture.mount) { outcome = it }

        outcome?.isFailure shouldBe true
        fixture.ledger.claimCalls shouldBe 0
        fixture.ownership.applyCalls shouldBe 0
    }

    "a replay of an already committed APPLIED record completes consumption" {
        val fixture = FlowFixture()
        val applied = fixture.available.copy(stage = MountTransferStage.APPLIED, recipient = fixture.recipient)
        fixture.store.save(applied)
        fixture.ledger.committed = true
        var outcome: Result<MountTransferRecord>? = null

        fixture.flow.recover(applied, fixture.mount) { outcome = it }

        outcome?.getOrThrow()?.stage shouldBe MountTransferStage.CONSUMED
        fixture.ownership.applyCalls shouldBe 0
        fixture.store.get(fixture.id)?.stage shouldBe MountTransferStage.CONSUMED
    }

    "PACKING recovery packs once and PACKED recovery does not revoke again" {
        val fixture = FlowFixture(initialStage = MountTransferStage.PACKING)
        val packing = fixture.store.get(fixture.id)!!

        var outcome: Result<MountTransferRecord>? = null
        fixture.flow.recover(packing, fixture.mount) { outcome = it }
        outcome?.isSuccess shouldBe true
        fixture.store.failure shouldBe null
        fixture.ownership.packCalls shouldBe 1
        fixture.store.get(fixture.id)?.stage shouldBe MountTransferStage.PACKED

        fixture.flow.recover(fixture.store.get(fixture.id)!!, fixture.mount) {}
        fixture.ownership.packCalls shouldBe 1
    }

    "an APPLIED persistence failure retains the live ledger claim for recovery" {
        val fixture = FlowFixture(failingStage = MountTransferStage.APPLIED)
        var outcome: Result<MountTransferRecord>? = null

        fixture.flow.redeem(fixture.recipient, fixture.id, fixture.mount) { outcome = it }

        outcome?.isFailure shouldBe true
        fixture.ledger.claimCalls shouldBe 1
        fixture.ledger.abandonCalls shouldBe 1
        fixture.store.get(fixture.id)?.stage shouldBe MountTransferStage.CLAIMING
    }

    "synchronous ownership failure releases the flow lock" {
        val fixture = FlowFixture()
        fixture.ownership.snapshotFailure = IllegalStateException("snapshot failed")
        var outcome: Result<MountTransferRecord>? = null

        fixture.flow.pack(fixture.issuer, fixture.mount) { outcome = it }

        outcome?.isFailure shouldBe true
        fixture.flow.isBusy(fixture.issuer) shouldBe false
    }

    "commit failure abandons the acquired claim and leaves APPLIED recovery evidence" {
        val fixture = FlowFixture()
        fixture.ledger.commitFailure = IllegalStateException("commit unavailable")
        var outcome: Result<MountTransferRecord>? = null

        fixture.flow.redeem(fixture.recipient, fixture.id, fixture.mount) { outcome = it }

        outcome?.isFailure shouldBe true
        fixture.ledger.abandonCalls shouldBe 1
        fixture.store.get(fixture.id)?.stage shouldBe MountTransferStage.APPLIED
    }

    "a rejected commit also abandons the acquired claim" {
        val fixture = FlowFixture()
        fixture.ledger.commitRejected = true
        var outcome: Result<MountTransferRecord>? = null

        fixture.flow.redeem(fixture.recipient, fixture.id, fixture.mount) { outcome = it }

        outcome?.isFailure shouldBe true
        fixture.ledger.abandonCalls shouldBe 1
    }

    "two holders racing after the ownership read cannot both claim the certificate" {
        val fixture = FlowFixture(pendingCanReceive = true)
        val other = UUID.randomUUID()
        var first: Result<MountTransferRecord>? = null
        var second: Result<MountTransferRecord>? = null

        fixture.flow.redeem(fixture.recipient, fixture.id, fixture.mount) { first = it }
        fixture.flow.redeem(other, fixture.id, fixture.mount) { second = it }
        fixture.canReceive.complete(true)

        listOf(first?.isSuccess, second?.isSuccess).count { it == true } shouldBe 1
        fixture.ledger.claimCalls shouldBe 1
    }
})

private class FlowFixture(
    allowedToReceive: Boolean = true,
    pendingCanReceive: Boolean = false,
    initialStage: MountTransferStage = MountTransferStage.AVAILABLE,
    private val failingStage: MountTransferStage? = null,
) {
    val issuer = UUID.randomUUID()
    val recipient = UUID.randomUUID()
    val id = UUID.randomUUID()
    val mount = testMount()
    val record = MountTransferRecord(id, issuer, mount.id, listOf(mount.levelPermission(1)))
    val available = record.copy(stage = MountTransferStage.AVAILABLE)
    val store = TestTransferStore(failingStage)
    val ledger = TestTransferLedger()
    val ownership = TestTransferOwnership()
    val canReceive = CompletableFuture<Boolean>()
    val flow = MountTransferFlow(store, ownership, ledger, { it() })

    init {
        store.save(record.copy(stage = initialStage))
        ownership.allowed = canReceive
        if (!pendingCanReceive) canReceive.complete(allowedToReceive)
    }
}

private class TestTransferStore(private val failingStage: MountTransferStage?) : MountTransferStore {
    private val values = linkedMapOf<UUID, MountTransferRecord>()
    var failure: Throwable? = null
    override fun records(): List<MountTransferRecord> = values.values.toList()
    override fun save(record: MountTransferRecord) {
        if (record.stage == failingStage) {
            failure = IllegalStateException("store failure")
            throw failure!!
        }
        record.validate()
        val previous = values[record.id]
        require(previous == null || record.stage.ordinal >= previous.stage.ordinal)
        values[record.id] = record
    }
}

private class TestTransferLedger : OneTimeUseLedger {
    var claimCalls = 0
    var abandonCalls = 0
    var committed = false
    var commitFailure: Throwable? = null
    var commitRejected = false
    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> {
        claimCalls++
        if (committed) return CompletableFuture.completedFuture(OneTimeUseClaimResult.AlreadyConsumed)
        return CompletableFuture.completedFuture(OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, true)))
    }
    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> {
        commitFailure?.let { return CompletableFuture.failedFuture(it) }
        if (commitRejected) return CompletableFuture.completedFuture(OneTimeUseCommitResult.REJECTED)
        committed = true
        return CompletableFuture.completedFuture(OneTimeUseCommitResult.COMMITTED)
    }
    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> =
        CompletableFuture.completedFuture(OneTimeUseReleaseResult.RELEASED)
    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> {
        abandonCalls++
        return CompletableFuture.completedFuture(OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY)
    }
}

private class TestTransferOwnership : MountTransferOwnership {
    var allowed = CompletableFuture.completedFuture(true)
    var packCalls = 0
    var applyCalls = 0
    var snapshotFailure: Throwable? = null
    override fun snapshot(playerId: UUID, mount: MountDefinition): CompletableFuture<List<String>> {
        snapshotFailure?.let { throw it }
        return CompletableFuture.completedFuture(listOf(mount.levelPermission(1)))
    }
    override fun pack(record: MountTransferRecord, mount: MountDefinition): CompletableFuture<Void> {
        packCalls++
        return CompletableFuture.completedFuture(null)
    }
    override fun apply(record: MountTransferRecord, mount: MountDefinition): CompletableFuture<Void> {
        applyCalls++
        return CompletableFuture.completedFuture(null)
    }
    override fun canReceive(playerId: UUID, mount: MountDefinition) = allowed
}
