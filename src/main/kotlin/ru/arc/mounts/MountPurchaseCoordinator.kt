package ru.arc.mounts

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

sealed interface MountPurchaseResult {
    data object Success : MountPurchaseResult
    data object Busy : MountPurchaseResult
    data object AlreadyOwned : MountPurchaseResult
    data object InvalidLevel : MountPurchaseResult
    data object NotUnlocked : MountPurchaseResult
    data object NotForSale : MountPurchaseResult
    data object MerchantRequired : MountPurchaseResult
    data object PurchasesDisabled : MountPurchaseResult
    data object EconomyUnavailable : MountPurchaseResult
    data object InsufficientFunds : MountPurchaseResult
    data object PaymentFailed : MountPurchaseResult
    data object JournalUnavailable : MountPurchaseResult
    data object PersistenceFailed : MountPurchaseResult
    data object PersistenceFailedRefunded : MountPurchaseResult
    data object PersistenceFailedRefundFailed : MountPurchaseResult
    data object ManualReview : MountPurchaseResult
}

class MountPurchaseCoordinator(
    private val ownership: MountOwnership,
    private val wallet: MountWallet,
    private val journal: MountPurchaseJournal,
    private val purchasesEnabled: () -> Boolean,
    private val runSync: (() -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onStateChanged: () -> Unit = {},
    private val externalBusy: (UUID) -> Boolean = { false },
    private val purchaseAllowed: (UUID) -> Boolean = { true },
    private val sharedBusy: (UUID) -> Boolean = { false },
    private val recordActivity: (UUID, String, String?, String?, Map<String, String>) -> Unit = { _, _, _, _, _ -> },
    private val onFailure: (Throwable) -> Unit = {},
    private val recoverStep: (() -> Unit) -> CompletableFuture<Unit> = { action ->
        action(); CompletableFuture.completedFuture(Unit)
    },
) {
    private val activePurchases = ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var closed = false

    fun purchaseLevel(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        level: Int,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        if (level !in 1..mount.maxLevel) return callback(MountPurchaseResult.InvalidLevel)
        val profile = ownership.profile(subject, mount)
        if (profile.level >= level) return callback(MountPurchaseResult.AlreadyOwned)
        if (level != profile.level + 1) return callback(MountPurchaseResult.InvalidLevel)
        val price = mount.price(level) ?: return callback(MountPurchaseResult.NotForSale)
        purchase(
            subject.uniqueId,
            mount,
            MountPurchaseKind.LEVEL,
            level.toString(),
            mount.levelPermission(level),
            price,
            mount.currency,
            callback,
        ) { ownership.grantLevel(subject.uniqueId, mount, level) }
    }

    fun purchaseGlow(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.unlocked) return callback(MountPurchaseResult.NotUnlocked)
        if (profile.glowOwned) return callback(MountPurchaseResult.AlreadyOwned)
        val price = mount.glowPrice ?: return callback(MountPurchaseResult.NotForSale)
        purchase(
            subject.uniqueId,
            mount,
            MountPurchaseKind.GLOW,
            "glow",
            mount.glowPermission,
            price,
            mount.glowCurrency,
            callback,
        ) { ownership.grantGlow(subject.uniqueId, mount) }
    }

    fun purchaseSkin(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        skin: MountSkinDefinition,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.unlocked) return callback(MountPurchaseResult.NotUnlocked)
        if (profile.ownsSkin(skin.id)) return callback(MountPurchaseResult.AlreadyOwned)
        val price = skin.price ?: return callback(MountPurchaseResult.NotForSale)
        purchase(
            subject.uniqueId,
            mount,
            MountPurchaseKind.SKIN,
            skin.id,
            mount.skinPermission(skin.id),
            price,
            skin.currency,
            callback,
        ) { ownership.grantSkin(subject.uniqueId, mount, skin) }
    }

    fun purchaseAbility(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        ability: MountAbilityUpgradeDefinition,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.unlocked) return callback(MountPurchaseResult.NotUnlocked)
        if (mount.ability(ability.id) != ability) return callback(MountPurchaseResult.NotForSale)
        if (profile.ownsAbility(ability.id)) return callback(MountPurchaseResult.AlreadyOwned)
        purchase(
            subject.uniqueId,
            mount,
            MountPurchaseKind.ABILITY,
            ability.id,
            mount.abilityPermission(ability.id),
            ability.price,
            ability.currency,
            callback,
        ) { ownership.grantAbility(subject.uniqueId, mount, ability) }
    }

    fun purchaseSize(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        sizeId: String,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.unlocked) return callback(MountPurchaseResult.NotUnlocked)
        val size = mount.sizeOptions.firstOrNull { it.id == sizeId && it.grantOnly }
            ?: return callback(MountPurchaseResult.NotForSale)
        if (size.minimumLevel > profile.level) return callback(MountPurchaseResult.NotForSale)
        if (profile.ownsSize(size)) return callback(MountPurchaseResult.AlreadyOwned)
        val price = size.price ?: return callback(MountPurchaseResult.NotForSale)
        purchase(
            subject.uniqueId,
            mount,
            MountPurchaseKind.SIZE,
            size.id,
            mount.sizeOwnershipPermission(size.id),
            price,
            size.currency,
            callback,
        ) { ownership.grantSize(subject.uniqueId, mount, size) }
    }

    fun setGlowEnabled(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        enabled: Boolean,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.glowOwned) return callback(MountPurchaseResult.NotUnlocked)
        runSetting(subject.uniqueId, callback) { ownership.setGlowEnabled(subject.uniqueId, mount, enabled) }
    }

    fun setActiveSkin(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        skinId: String,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.ownsSkin(skinId)) return callback(MountPurchaseResult.NotUnlocked)
        if (profile.activeSkinId == skinId) return callback(MountPurchaseResult.AlreadyOwned)
        runSetting(subject.uniqueId, callback) { ownership.setActiveSkin(subject.uniqueId, mount, skinId) }
    }

    fun setSpeedTuning(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        tuning: MountTuningDefinition,
        percentage: Int,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.unlocked) return callback(MountPurchaseResult.NotUnlocked)
        if (percentage !in tuning.speedPercentages) return callback(MountPurchaseResult.NotForSale)
        if (tuning.speedPercentage(profile.selectedSpeedPercentage) == percentage) {
            return callback(MountPurchaseResult.AlreadyOwned)
        }
        runSetting(subject.uniqueId, callback) {
            ownership.setSpeedTuning(subject.uniqueId, mount, percentage)
        }
    }

    fun setStepHeightTuning(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        tuning: MountTuningDefinition,
        hundredths: Int,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.unlocked || mount.movement != MountMovement.WALKING) {
            return callback(MountPurchaseResult.NotUnlocked)
        }
        if (hundredths !in tuning.availableStepHeightsHundredths(profile.level)) {
            return callback(MountPurchaseResult.NotForSale)
        }
        if (tuning.stepHeightHundredths(profile.level, profile.selectedStepHeightHundredths) == hundredths) {
            return callback(MountPurchaseResult.AlreadyOwned)
        }
        runSetting(subject.uniqueId, callback) {
            ownership.setStepHeightTuning(subject.uniqueId, mount, hundredths)
        }
    }

    fun setSizeTuning(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        sizeId: String,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.unlocked) return callback(MountPurchaseResult.NotUnlocked)
        val option = mount.sizeOptions.firstOrNull { it.id == sizeId } ?: return callback(MountPurchaseResult.NotForSale)
        if (option.minimumLevel > profile.level || !profile.ownsSize(option)) return callback(MountPurchaseResult.NotForSale)
        if (mount.effectiveSizeOption(profile.selectedSizeId, profile.level, profile.ownedSizeIds)?.id == option.id) {
            return callback(MountPurchaseResult.AlreadyOwned)
        }
        runSetting(subject.uniqueId, callback) {
            ownership.setSizeTuning(subject.uniqueId, mount, option.id)
        }
    }

    fun setRiderViewAutoHide(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        enabled: Boolean,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.unlocked) return callback(MountPurchaseResult.NotUnlocked)
        if (mount.effectiveRiderViewAutoHide(profile) == enabled) return callback(MountPurchaseResult.AlreadyOwned)
        runSetting(subject.uniqueId, callback) {
            ownership.setRiderViewAutoHide(subject.uniqueId, mount, enabled)
        }
    }

    fun recover(catalog: MountCatalog, onManualReview: (MountPurchaseJournalRecord) -> Unit) {
        onMain { recoverRecords(catalog, onManualReview) }
    }

    private suspend fun recoverRecords(catalog: MountCatalog, onManualReview: (MountPurchaseJournalRecord) -> Unit) {
        journal.records().forEach { record ->
            if (record.status.terminal && record.status != MountPurchaseJournalStatus.MANUAL_REVIEW) return@forEach
            awaitRecoveryStep()
            val mount = catalog[record.mountId]
            if (mount == null) {
                markManualReview(record, "mount_missing")?.let(onManualReview)
                return@forEach
            }
            when (record.status) {
                MountPurchaseJournalStatus.PREPARED -> persist(record.advance(MountPurchaseJournalStatus.CANCELLED, "startup_cancelled"))
                MountPurchaseJournalStatus.WITHDRAWAL_STARTED -> recoverWithdrawal(record, mount, onManualReview)
                MountPurchaseJournalStatus.REFUND_STARTED -> recoverRefund(record, onManualReview)
                MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                MountPurchaseJournalStatus.OWNERSHIP_STARTED,
                -> recoverOwnership(record, mount, onManualReview)
                MountPurchaseJournalStatus.MANUAL_REVIEW -> recoverManualReview(record, mount, onManualReview)
                MountPurchaseJournalStatus.COMPLETED,
                MountPurchaseJournalStatus.CANCELLED,
                MountPurchaseJournalStatus.REFUNDED,
                -> Unit
            }
        }
    }

    fun clear() {
        closed = true
        activePurchases.clear()
    }

    fun isBusy(playerId: UUID): Boolean = isPurchaseBusy(playerId) || sharedBusy(playerId)

    internal fun isPurchaseBusy(playerId: UUID): Boolean = playerId in activePurchases || journal.hasOpenPurchase(playerId)

    private fun purchase(
        playerId: UUID,
        mount: MountDefinition,
        kind: MountPurchaseKind,
        target: String,
        permission: String,
        price: Double,
        currency: String,
        callback: (MountPurchaseResult) -> Unit,
        applyPermission: () -> CompletableFuture<Void>,
    ) {
        onMain { purchaseDurably(playerId, mount, kind, target, permission, price, currency, callback, applyPermission) }
    }

    private suspend fun purchaseDurably(
        playerId: UUID,
        mount: MountDefinition,
        kind: MountPurchaseKind,
        target: String,
        permission: String,
        price: Double,
        currency: String,
        callback: (MountPurchaseResult) -> Unit,
        applyPermission: () -> CompletableFuture<Void>,
    ) {
        if (!purchasesEnabled()) return callback(MountPurchaseResult.PurchasesDisabled)
        if (!purchaseAllowed(playerId)) return callback(MountPurchaseResult.MerchantRequired)
        if (externalBusy(playerId)) return callback(MountPurchaseResult.Busy)
        val purchaseWallet = wallet.walletForCurrency(currency)
        if (purchaseWallet == null || !purchaseWallet.available) return callback(MountPurchaseResult.EconomyUnavailable)
        if (journal.hasOpenPurchase(playerId)) return callback(MountPurchaseResult.ManualReview)
        if (!activePurchases.add(playerId)) return callback(MountPurchaseResult.Busy)

        val now = clock()
        val record =
            MountPurchaseJournalRecord(
                transactionId = UUID.randomUUID().toString(),
                playerId = playerId.toString(),
                mountId = mount.id,
                kind = kind,
                target = target,
                permission = permission,
                priceMinor = price.toExactMinor(),
                currency = currency,
                createdAt = now,
                updatedAt = now,
            )
        if (!persist(record)) return finish(playerId, callback, MountPurchaseResult.JournalUnavailable)
        val balanceBefore = purchaseWallet.balanceMinor(playerId)
        if (balanceBefore == null) {
            val cancelled = persist(record.advance(MountPurchaseJournalStatus.CANCELLED, "balance_unavailable"))
            return finish(
                playerId,
                callback,
                if (cancelled) MountPurchaseResult.EconomyUnavailable else MountPurchaseResult.JournalUnavailable,
            )
        }
        if (balanceBefore < record.priceMinor) {
            persist(record.advance(MountPurchaseJournalStatus.CANCELLED, "insufficient_funds"))
            return finish(playerId, callback, MountPurchaseResult.InsufficientFunds)
        }
        val started =
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = nextTime(record.updatedAt),
                balanceBeforeMinor = balanceBefore,
            )
        if (!persist(started)) return finish(playerId, callback, MountPurchaseResult.JournalUnavailable)
        if (!purchaseAllowed(playerId)) {
            val cancelled = persist(started.advance(MountPurchaseJournalStatus.CANCELLED, "merchant_context_lost"))
            return finish(playerId, callback, if (cancelled) MountPurchaseResult.MerchantRequired else MountPurchaseResult.JournalUnavailable)
        }

        val evidence = purchaseWallet.withdraw(playerId, record.priceMinor, "arc-mount:${record.transactionId}", balanceBefore)
        val expectedAfter = balanceBefore - record.priceMinor
        if (evidence.balanceAfterMinor != expectedAfter) {
            val exactUnchanged = evidence.balanceAfterMinor == balanceBefore && evidence.providerAccepted == false
            val terminal =
                if (exactUnchanged) started.advance(MountPurchaseJournalStatus.CANCELLED, evidence.failureCode ?: "provider_rejected")
                else started.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, evidence.failureCode ?: "ambiguous_withdrawal")
            persist(terminal)
            return finish(
                playerId,
                callback,
                if (exactUnchanged) MountPurchaseResult.PaymentFailed else MountPurchaseResult.ManualReview,
            )
        }
        val withdrawn =
            started.copy(
                status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                updatedAt = nextTime(started.updatedAt),
                balanceAfterMinor = expectedAfter,
                evidence = "exact_balance_delta",
            )
        if (!persist(withdrawn)) return finish(playerId, callback, MountPurchaseResult.ManualReview)
        val ownershipStarted = withdrawn.advance(MountPurchaseJournalStatus.OWNERSHIP_STARTED, "permission_write_started")
        if (!persist(ownershipStarted)) return finish(playerId, callback, MountPurchaseResult.ManualReview)

        val future = runCatching(applyPermission).getOrElse {
            verifyOrRefund(playerId, ownershipStarted, callback)
            return
        }
        future.whenComplete { _, _ -> onMain { verifyOrRefund(playerId, ownershipStarted, callback) } }
    }

    private fun verifyOrRefund(
        playerId: UUID,
        record: MountPurchaseJournalRecord,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        ownership.hasDirectPermission(playerId, record.permission).whenComplete { owned, verificationFailure ->
            onMain {
                when {
                    verificationFailure != null -> {
                        persist(record.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, "permission_verification_failed"))
                        finish(playerId, callback, MountPurchaseResult.ManualReview)
                    }
                    owned == true -> {
                        val completed = record.advance(MountPurchaseJournalStatus.COMPLETED, "permission_verified")
                        val saved = persist(completed)
                        finish(
                            playerId,
                            callback,
                            if (saved) MountPurchaseResult.Success else MountPurchaseResult.ManualReview,
                        )
                    }
                    else -> refund(playerId, record, callback)
                }
            }
        }
    }

    private suspend fun refund(
        playerId: UUID,
        record: MountPurchaseJournalRecord,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val purchaseWallet = wallet.walletForCurrency(record.currency)
        if (purchaseWallet == null || !purchaseWallet.available) {
            persist(record.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, "refund_wallet_unavailable"))
            return finish(playerId, callback, MountPurchaseResult.PersistenceFailedRefundFailed)
        }
        val before = purchaseWallet.balanceMinor(playerId)
        if (before == null) {
            persist(record.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, "refund_balance_unavailable"))
            return finish(playerId, callback, MountPurchaseResult.PersistenceFailedRefundFailed)
        }
        val started =
            record.copy(
                status = MountPurchaseJournalStatus.REFUND_STARTED,
                updatedAt = nextTime(record.updatedAt),
                refundBalanceBeforeMinor = before,
                evidence = "permission_not_applied",
            )
        if (!persist(started)) return finish(playerId, callback, MountPurchaseResult.PersistenceFailedRefundFailed)
        val evidence = purchaseWallet.deposit(playerId, record.priceMinor, "arc-mount-refund:${record.transactionId}", before)
        val expectedAfter = before + record.priceMinor
        if (evidence.balanceAfterMinor == expectedAfter) {
            val refunded =
                started.copy(
                    status = MountPurchaseJournalStatus.REFUNDED,
                    updatedAt = nextTime(started.updatedAt),
                    refundBalanceAfterMinor = expectedAfter,
                    evidence = "exact_refund_balance_delta",
                )
            val saved = persist(refunded)
            finish(
                playerId,
                callback,
                if (saved) MountPurchaseResult.PersistenceFailedRefunded else MountPurchaseResult.ManualReview,
            )
        } else {
            persist(started.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, evidence.failureCode ?: "ambiguous_refund"))
            finish(playerId, callback, MountPurchaseResult.PersistenceFailedRefundFailed)
        }
    }

    private fun recoverOwnership(
        record: MountPurchaseJournalRecord,
        mount: MountDefinition,
        onManualReview: (MountPurchaseJournalRecord) -> Unit,
    ) {
        val playerId = UUID.fromString(record.playerId)
        ownership.hasDirectPermission(playerId, record.permission).whenComplete { owned, lookupFailure ->
            onMain {
                if (lookupFailure != null) {
                    markManualReview(record, "startup_permission_lookup_failed")?.let(onManualReview)
                    return@onMain
                }
                if (owned == true) {
                    if (!persist(record.advance(MountPurchaseJournalStatus.COMPLETED, "startup_permission_verified"))) {
                        onManualReview(record)
                    }
                    return@onMain
                }
                val write = permissionWrite(record, mount)
                if (write == null) {
                    markManualReview(record, "startup_target_missing")?.let(onManualReview)
                    return@onMain
                }
                write.whenComplete { _, _ ->
                    ownership.hasDirectPermission(playerId, record.permission).whenComplete { recovered, verifyFailure ->
                        onMain {
                            if (verifyFailure == null && recovered == true) {
                                if (!persist(record.advance(MountPurchaseJournalStatus.COMPLETED, "startup_permission_recovered"))) {
                                    onManualReview(record)
                                }
                            } else {
                                markManualReview(record, "startup_permission_recovery_failed")?.let(onManualReview)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun recoverManualReview(
        record: MountPurchaseJournalRecord,
        mount: MountDefinition,
        onManualReview: (MountPurchaseJournalRecord) -> Unit,
    ) {
        val playerId = UUID.fromString(record.playerId)
        ownership.hasDirectPermission(playerId, record.permission).whenComplete { owned, lookupFailure ->
            onMain {
                val withdrawalProven =
                    record.balanceBeforeMinor != null &&
                        record.balanceAfterMinor == record.balanceBeforeMinor - record.priceMinor
                when {
                    lookupFailure != null -> onManualReview(record)
                    owned == true && record.refundBalanceBeforeMinor != null -> onManualReview(record)
                    owned == true && withdrawalProven -> {
                        if (!persist(record.advance(MountPurchaseJournalStatus.COMPLETED, "manual_permission_verified"))) {
                            onManualReview(record)
                        }
                    }
                    record.refundBalanceBeforeMinor != null -> recoverRefund(record, onManualReview)
                    withdrawalProven -> recoverOwnership(record, mount, onManualReview)
                    record.balanceBeforeMinor != null -> recoverWithdrawal(record, mount, onManualReview)
                    else -> onManualReview(record)
                }
            }
        }
    }

    private suspend fun recoverWithdrawal(
        record: MountPurchaseJournalRecord,
        mount: MountDefinition,
        onManualReview: (MountPurchaseJournalRecord) -> Unit,
    ) {
        val playerId = UUID.fromString(record.playerId)
        val purchaseWallet = wallet.walletForCurrency(record.currency)
        if (purchaseWallet == null || !purchaseWallet.available) {
            markManualReview(record, "withdrawal_wallet_unavailable")?.let(onManualReview)
            return
        }
        purchaseWallet.findTransaction(
            playerId = playerId,
            amountMinor = -record.priceMinor,
            reason = "arc-mount:${record.transactionId}",
            notBeforeMillis = record.createdAt,
        ).whenComplete { history, failure ->
            onMain {
                if (failure != null || history?.found != true) {
                    val evidence = if (history?.historyAvailable == true) "withdrawal_not_found_in_history" else "withdrawal_history_unavailable"
                    markManualReview(record, evidence)?.let(onManualReview)
                    return@onMain
                }
                val before = requireNotNull(record.balanceBeforeMinor)
                val withdrawn =
                    record.copy(
                        status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                        updatedAt = nextTime(record.updatedAt),
                        balanceAfterMinor = before - record.priceMinor,
                        evidence = "history_withdrawal:${history.transactionId}",
                    )
                if (persist(withdrawn)) recoverOwnership(withdrawn, mount, onManualReview)
                else onManualReview(record)
            }
        }
    }

    private suspend fun recoverRefund(
        record: MountPurchaseJournalRecord,
        onManualReview: (MountPurchaseJournalRecord) -> Unit,
    ) {
        val playerId = UUID.fromString(record.playerId)
        val purchaseWallet = wallet.walletForCurrency(record.currency)
        if (purchaseWallet == null || !purchaseWallet.available) {
            markManualReview(record, "refund_wallet_unavailable")?.let(onManualReview)
            return
        }
        purchaseWallet.findTransaction(
            playerId = playerId,
            amountMinor = record.priceMinor,
            reason = "arc-mount-refund:${record.transactionId}",
            notBeforeMillis = record.createdAt,
        ).whenComplete { history, failure ->
            onMain {
                if (failure != null || history?.found != true) {
                    val evidence = if (history?.historyAvailable == true) "refund_not_found_in_history" else "refund_history_unavailable"
                    markManualReview(record, evidence)?.let(onManualReview)
                    return@onMain
                }
                val before = requireNotNull(record.refundBalanceBeforeMinor)
                val refunded =
                    record.copy(
                        status = MountPurchaseJournalStatus.REFUNDED,
                        updatedAt = nextTime(record.updatedAt),
                        refundBalanceAfterMinor = before + record.priceMinor,
                        evidence = "history_refund:${history.transactionId}",
                    )
                if (!persist(refunded)) onManualReview(record)
            }
        }
    }

    private fun permissionWrite(record: MountPurchaseJournalRecord, mount: MountDefinition): CompletableFuture<Void>? {
        val playerId = UUID.fromString(record.playerId)
        return when (record.kind) {
            MountPurchaseKind.LEVEL -> record.target.toIntOrNull()?.let { ownership.grantLevel(playerId, mount, it) }
            MountPurchaseKind.GLOW -> ownership.grantGlow(playerId, mount)
            MountPurchaseKind.SKIN -> mount.skin(record.target)?.let { ownership.grantSkin(playerId, mount, it) }
            MountPurchaseKind.ABILITY -> mount.ability(record.target)?.let { ownership.grantAbility(playerId, mount, it) }
            MountPurchaseKind.SIZE -> mount.sizeOptions
                .firstOrNull { it.grantOnly && it.id == record.target }
                ?.let { ownership.grantSize(playerId, mount, it) }
        }
    }

    private fun runSetting(
        playerId: UUID,
        callback: (MountPurchaseResult) -> Unit,
        mutation: () -> CompletableFuture<Void>,
    ) {
        if (externalBusy(playerId) || !activePurchases.add(playerId)) return callback(MountPurchaseResult.Busy)
        val future = runCatching(mutation).getOrElse {
            activePurchases.remove(playerId)
            callback(MountPurchaseResult.PersistenceFailed)
            return
        }
        future.whenComplete { _, failure ->
            onMain {
                activePurchases.remove(playerId)
                callback(if (failure == null) MountPurchaseResult.Success else MountPurchaseResult.PersistenceFailed)
            }
        }
    }

    private fun finish(
        playerId: UUID,
        callback: (MountPurchaseResult) -> Unit,
        result: MountPurchaseResult,
    ) {
        activePurchases.remove(playerId)
        onStateChanged()
        callback(result)
    }

    private suspend fun awaitRecoveryStep(): Unit = suspendCoroutine { continuation ->
        recoverStep {}.whenComplete { _, failure ->
            if (failure != null) continuation.resumeWithException(failure)
            else if (closed) continuation.resumeWithException(CancellationException("Mount purchases closed"))
            else continuation.resume(Unit)
        }
    }

    private suspend fun markManualReview(record: MountPurchaseJournalRecord, evidence: String): MountPurchaseJournalRecord? {
        val review = record.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, evidence)
        return if (persist(review)) review else null
    }

    private fun MountPurchaseJournalRecord.advance(
        status: MountPurchaseJournalStatus,
        evidence: String,
    ): MountPurchaseJournalRecord = copy(status = status, updatedAt = nextTime(updatedAt), evidence = evidence)

    private fun nextTime(previous: Long): Long = maxOf(clock(), previous + 1L)

    /** Every resume crosses the server executor and rechecks this runtime's lifetime. */
    private fun onMain(action: suspend () -> Unit) {
        if (closed) return
        runSync {
            if (closed) return@runSync
            action.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    result.exceptionOrNull()?.takeUnless { it is CancellationException }?.let(onFailure)
                }
            })
        }
    }

    private suspend fun awaitWrite(record: MountPurchaseJournalRecord): Boolean = suspendCoroutine { continuation ->
        if (closed) {
            continuation.resumeWithException(CancellationException("Mount purchase runtime closed"))
        } else {
            val future = runCatching { journal.persistAsync(record) }.getOrElse {
                continuation.resume(false)
                return@suspendCoroutine
            }
            future.whenComplete { saved, failure ->
                if (closed) {
                    continuation.resumeWithException(CancellationException("Mount purchase runtime closed"))
                } else try {
                    runSync {
                        if (closed) continuation.resumeWithException(CancellationException("Mount purchase runtime closed"))
                        else continuation.resume(failure == null && saved == true)
                    }
                } catch (rejected: Throwable) {
                    continuation.resumeWithException(rejected)
                }
            }
        }
    }

    private suspend fun persist(record: MountPurchaseJournalRecord): Boolean =
        awaitWrite(record).also { persisted ->
            if (persisted) {
                onStateChanged()
                recordActivity(
                    UUID.fromString(record.playerId),
                    "mount.purchase.${record.status.name.lowercase()}",
                    record.mountId,
                    record.transactionId,
                    mapOf(
                        "kind" to record.kind.name,
                        "target" to record.target,
                        "currency" to record.currency,
                        "amountMinor" to record.priceMinor.toString(),
                        "createdAt" to record.createdAt.toString(),
                        "durationMs" to (record.updatedAt - record.createdAt).toString(),
                    ),
                )
            }
        }
}
