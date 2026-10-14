package ru.arc.mounts

import org.bukkit.plugin.Plugin
import ru.arc.mounts.api.ArcMountEconomy
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class MountMoneyEvidence(
    val providerAccepted: Boolean?,
    val providerCallAttempted: Boolean,
    val balanceAfterMinor: Long?,
    val failureCode: String? = null,
)

data class MountProviderTransactionEvidence(
    val transactionId: String?,
    val historyAvailable: Boolean,
) {
    val found: Boolean get() = transactionId != null
}

interface MountWallet {
    val available: Boolean

    /** Returns a wallet for an explicitly named host-provided currency. */
    fun walletForCurrency(currency: String): MountWallet? =
        if (currency == "vault") this else null

    fun balanceMinor(playerId: UUID): Long?

    fun withdraw(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): MountMoneyEvidence

    fun deposit(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): MountMoneyEvidence

    fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<MountProviderTransactionEvidence> =
        CompletableFuture.completedFuture(MountProviderTransactionEvidence(null, false))
}

/** Resolves the host economy API lazily and fails closed when ARC is unavailable. */
class HostMountWallet internal constructor(
    private val economyProvider: () -> ArcMountEconomy?,
    private val currencyName: String = DEFAULT_CURRENCY,
) : MountWallet {
    constructor(plugin: Plugin) : this(
        { plugin.server.servicesManager.load(ArcMountEconomy::class.java) },
        DEFAULT_CURRENCY,
    )

    override val available: Boolean
        get() = runCatching { economyProvider()?.available(currencyName) == true }.getOrDefault(false)

    override fun walletForCurrency(currency: String): MountWallet? =
        HostMountWallet(economyProvider, currency)

    override fun balanceMinor(playerId: UUID): Long? =
        runCatching { economyProvider()?.balanceMinor(currencyName, playerId) }.getOrNull()

    override fun withdraw(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): MountMoneyEvidence = mutate(playerId, amountMinor, reason, expectedBalanceBeforeMinor, deposit = false)

    override fun deposit(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): MountMoneyEvidence = mutate(playerId, amountMinor, reason, expectedBalanceBeforeMinor, deposit = true)

    override fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<MountProviderTransactionEvidence> {
        require(amountMinor != 0L) { "Mount provider history amount cannot be zero" }
        require(REASON_PATTERN.matches(reason)) { "Invalid mount provider history reason" }
        val service = runCatching(economyProvider).getOrNull()
            ?: return unavailableHistory()
        val history = try {
            service.findTransaction(currencyName, playerId, amountMinor, reason, notBeforeMillis)
        } catch (_: Throwable) {
            return unavailableHistory()
        }
        return history.handle { evidence, failure ->
            if (failure != null || evidence == null) {
                MountProviderTransactionEvidence(null, false)
            } else {
                MountProviderTransactionEvidence(evidence.transactionId(), evidence.historyAvailable())
            }
        }
    }

    private fun mutate(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
        deposit: Boolean,
    ): MountMoneyEvidence {
        require(amountMinor > 0L) { "Mount money mutation must be positive" }
        require(REASON_PATTERN.matches(reason)) { "Invalid mount money mutation reason" }
        val service = runCatching(economyProvider).getOrNull()
            ?: return MountMoneyEvidence(false, false, null, "provider_unavailable")
        val evidence = try {
            if (deposit) {
                service.deposit(currencyName, playerId, amountMinor, reason, expectedBalanceBeforeMinor)
            } else {
                service.withdraw(currencyName, playerId, amountMinor, reason, expectedBalanceBeforeMinor)
            }
        } catch (_: Throwable) {
            return MountMoneyEvidence(null, false, null, "provider_unavailable")
        }
        return MountMoneyEvidence(
            evidence.providerAccepted(),
            evidence.providerCallAttempted(),
            evidence.balanceAfterMinor(),
            evidence.failureCode(),
        )
    }

    private fun unavailableHistory(): CompletableFuture<MountProviderTransactionEvidence> =
        CompletableFuture.completedFuture(MountProviderTransactionEvidence(null, false))

    private companion object {
        const val DEFAULT_CURRENCY = "vault"
        private val REASON_PATTERN = Regex("arc-(?:mount(?:-refund)?|reward):[0-9a-f-]{36}")
    }
}

internal fun Double.toExactMinor(): Long =
    BigDecimal.valueOf(this)
        .movePointRight(2)
        .setScale(0, RoundingMode.UNNECESSARY)
        .longValueExact()

internal fun Long.minorToDouble(): Double = BigDecimal.valueOf(this, 2).toDouble()
