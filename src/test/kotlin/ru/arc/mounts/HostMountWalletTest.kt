package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.mounts.api.ArcMountEconomy
import java.util.UUID
import java.util.concurrent.CompletableFuture

class HostMountWalletTest : StringSpec({
    "missing host economy fails closed without attempting a provider call" {
        val wallet = HostMountWallet({ null })
        val playerId = UUID.randomUUID()

        wallet.available shouldBe false
        wallet.balanceMinor(playerId) shouldBe null
        wallet.withdraw(playerId, 100L, "arc-mount:${UUID.randomUUID()}", 500L) shouldBe
            MountMoneyEvidence(false, false, null, "provider_unavailable")
        wallet.deposit(playerId, 100L, "arc-mount-refund:${UUID.randomUUID()}", 500L) shouldBe
            MountMoneyEvidence(false, false, null, "provider_unavailable")
        wallet.findTransaction(playerId, 100L, "arc-mount:${UUID.randomUUID()}", 0L).join() shouldBe
            MountProviderTransactionEvidence(null, false)
    }

    "host economy evidence preserves the exact currency and amount contract" {
        val playerId = UUID.randomUUID()
        val calls = mutableListOf<String>()
        val service = object : ArcMountEconomy {
            override fun available(currency: String): Boolean {
                calls += "available:$currency"
                return currency == "coins"
            }

            override fun balanceMinor(currency: String, playerId: UUID): Long? {
                calls += "balance:$currency:$playerId"
                return 12_345L
            }

            override fun withdraw(
                currency: String,
                playerId: UUID,
                amountMinor: Long,
                reason: String,
                expectedBeforeMinor: Long,
            ) = ArcMountEconomy.MoneyEvidence(true, true, 12_245L, null)

            override fun deposit(
                currency: String,
                playerId: UUID,
                amountMinor: Long,
                reason: String,
                expectedBeforeMinor: Long,
            ) = ArcMountEconomy.MoneyEvidence(null, true, 12_445L, "provider_threw")

            override fun findTransaction(
                currency: String,
                playerId: UUID,
                amountMinor: Long,
                reason: String,
                notBeforeMillis: Long,
            ): CompletableFuture<ArcMountEconomy.TransactionEvidence> {
                calls += "history:$currency:$amountMinor:$reason:$notBeforeMillis"
                return CompletableFuture.completedFuture(
                    ArcMountEconomy.TransactionEvidence("tx-7", true),
                )
            }
        }
        val wallet = HostMountWallet({ service }, "vault")
        val currencyWallet = wallet.walletForCurrency("coins") as HostMountWallet
        val reason = "arc-mount:${UUID.randomUUID()}"

        currencyWallet.available shouldBe true
        currencyWallet.balanceMinor(playerId) shouldBe 12_345L
        currencyWallet.withdraw(playerId, 100L, reason, 12_345L) shouldBe
            MountMoneyEvidence(true, true, 12_245L)
        currencyWallet.deposit(playerId, 100L, reason, 12_345L) shouldBe
            MountMoneyEvidence(null, true, 12_445L, "provider_threw")
        currencyWallet.findTransaction(playerId, -100L, reason, 42L).join() shouldBe
            MountProviderTransactionEvidence("tx-7", true)
        calls shouldBe listOf(
            "available:coins",
            "balance:coins:$playerId",
            "history:coins:-100:$reason:42",
        )
    }

    "provider history failure is reported as unavailable rather than a match" {
        val service = object : ArcMountEconomy {
            override fun available(currency: String) = true
            override fun balanceMinor(currency: String, playerId: UUID): Long? = 100L
            override fun withdraw(currency: String, playerId: UUID, amountMinor: Long, reason: String, expectedBeforeMinor: Long) =
                ArcMountEconomy.MoneyEvidence(true, true, 0L, null)
            override fun deposit(currency: String, playerId: UUID, amountMinor: Long, reason: String, expectedBeforeMinor: Long) =
                ArcMountEconomy.MoneyEvidence(true, true, 100L, null)
            override fun findTransaction(currency: String, playerId: UUID, amountMinor: Long, reason: String, notBeforeMillis: Long) =
                CompletableFuture<ArcMountEconomy.TransactionEvidence>().also {
                    it.completeExceptionally(IllegalStateException("history unavailable"))
                }
        }

        HostMountWallet({ service }).findTransaction(
            UUID.randomUUID(),
            100L,
            "arc-mount:${UUID.randomUUID()}",
            0L,
        ).join() shouldBe MountProviderTransactionEvidence(null, false)
    }
})
