package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MountPurchaseCoordinatorTest : StringSpec({
    "successful level purchase charges once and completes the durable journal" {
        val fixture = PurchaseFixture()
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { result = it }

        result shouldBe MountPurchaseResult.Success
        fixture.wallet.balanceMinor shouldBe 5_000_000L
        fixture.ownership.level shouldBe 1
        fixture.wallet.withdrawals shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
    }

    "failed ownership persistence refunds the exact purchase once" {
        val fixture = PurchaseFixture().also { it.ownership.failWrites = true }
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { result = it }

        result shouldBe MountPurchaseResult.PersistenceFailedRefunded
        fixture.wallet.balanceMinor shouldBe 10_000_000L
        fixture.wallet.withdrawals shouldBe 1
        fixture.wallet.deposits shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.REFUNDED
    }

    "non-sequential upgrade is rejected without charging or journaling" {
        val fixture = PurchaseFixture()
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 2) { result = it }

        result shouldBe MountPurchaseResult.InvalidLevel
        fixture.wallet.balanceMinor shouldBe 10_000_000L
        fixture.wallet.withdrawals shouldBe 0
        fixture.journal.records() shouldBe emptyList()
    }

    "stale confirmation is denied before journal or debit when merchant context is lost" {
        val fixture = PurchaseFixture().also { it.purchaseAllowed = false }
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { result = it }

        result shouldBe MountPurchaseResult.MerchantRequired
        fixture.wallet.withdrawals shouldBe 0
        fixture.journal.records() shouldBe emptyList()
    }

    "glow purchase requires an unlocked mount" {
        val fixture = PurchaseFixture()
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseGlow(fixture.subject(), fixture.mount) { result = it }

        result shouldBe MountPurchaseResult.NotUnlocked
        fixture.wallet.withdrawals shouldBe 0
    }

    "ability purchase charges once and persists its exact permission" {
        val fixture = PurchaseFixture().also { it.ownership.level = 1 }
        val ability = checkNotNull(fixture.mount.ability("night-vision"))
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseAbility(fixture.subject(), fixture.mount, ability) { result = it }

        result shouldBe MountPurchaseResult.Success
        fixture.ownership.abilityPermissions shouldBe setOf(fixture.mount.abilityPermission(ability.id))
        fixture.wallet.withdrawals shouldBe 1
        fixture.journal.records().single().kind shouldBe MountPurchaseKind.ABILITY
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
    }

    "ability currency routes independently from the mount currency" {
        val tokenWallet = MutableWallet()
        val ability = checkNotNull(testMount().ability("night-vision")).copy(currency = "tokens")
        val mount = testMount().copy(currency = "vault", abilities = MountAbilities(upgrades = listOf(ability)))
        val fixture = PurchaseFixture(mount).also {
            it.ownership.level = 1
            it.wallet.currencyWallets["tokens"] = tokenWallet
        }
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseAbility(fixture.subject(), mount, ability) { result = it }

        result shouldBe MountPurchaseResult.Success
        fixture.wallet.withdrawals shouldBe 0
        tokenWallet.withdrawals shouldBe 1
        fixture.journal.records().single().currency shouldBe "tokens"
    }

    "glow and skin currencies route independently from the mount currency" {
        val tokenWallet = MutableWallet()
        val mount = testMount().copy(
            currency = "tokens",
            glowCurrency = "vault",
            skins = listOf(testMount().skins.single().copy(currency = "tokens")),
        )
        val fixture = PurchaseFixture(mount).also {
            it.ownership.level = 1
            it.wallet.currencyWallets["tokens"] = tokenWallet
        }
        var glowResult: MountPurchaseResult? = null
        var skinResult: MountPurchaseResult? = null

        fixture.coordinator.purchaseGlow(fixture.subject(), mount) { glowResult = it }
        fixture.coordinator.purchaseSkin(fixture.subject(), mount, mount.skins.single()) { skinResult = it }

        glowResult shouldBe MountPurchaseResult.Success
        skinResult shouldBe MountPurchaseResult.Success
        fixture.wallet.withdrawals shouldBe 1
        tokenWallet.withdrawals shouldBe 1
        fixture.journal.records().associate { it.kind to it.currency } shouldBe
            mapOf(MountPurchaseKind.GLOW to "vault", MountPurchaseKind.SKIN to "tokens")
    }

    "paid special size records currency and becomes an owned size entitlement" {
        val tokenWallet = MutableWallet()
        val mount = testMount().copy(
            currency = "vault",
            sizeOptions = listOf(
                MountSizeOptionDefinition("standard", "Обычный", 1.0),
                MountSizeOptionDefinition("keychain", "Крошечный", 0.1, grantOnly = true, price = 40.0, currency = "tokens"),
            ),
        )
        val fixture = PurchaseFixture(mount).also {
            it.ownership.level = 1
            it.wallet.currencyWallets["tokens"] = tokenWallet
        }
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseSize(fixture.subject(), mount, "keychain") { result = it }

        result shouldBe MountPurchaseResult.Success
        fixture.wallet.withdrawals shouldBe 0
        tokenWallet.withdrawals shouldBe 1
        fixture.ownership.ownedSizeIds shouldBe setOf("keychain")
        fixture.journal.records().single().kind shouldBe MountPurchaseKind.SIZE
        fixture.journal.records().single().currency shouldBe "tokens"
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
    }

    "recovery uses recorded currency and price after catalog repricing" {
        val tokenWallet = MutableWallet()
        val fixture = PurchaseFixture().also { it.wallet.currencyWallets["tokens"] = tokenWallet }
        val record = fixture.preparedRecord().copy(currency = "tokens", priceMinor = 5_000_000L)
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(record.copy(status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED, updatedAt = 2L, balanceBeforeMinor = 10_000_000L)) shouldBe true
        fixture.journal.persist(record.copy(status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN, updatedAt = 3L, balanceBeforeMinor = 10_000_000L, balanceAfterMinor = 5_000_000L, evidence = "exact_balance_delta")) shouldBe true

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount.copy(levels = listOf(MountLevelDefinition(0.4, 99_000_000.0))))), {})

        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
        tokenWallet.withdrawals shouldBe 0
    }

    "speed tuning is free and persists independently from the unlocked level" {
        val fixture = PurchaseFixture().also { it.ownership.level = 2 }
        val tuning = MountTuningDefinition(listOf(50, 65, 80, 90, 100), listOf(110, 150, 200, 300, 400), listOf(110, 200, 400))
        var result: MountPurchaseResult? = null

        fixture.coordinator.setSpeedTuning(fixture.subject(), fixture.mount, tuning, 65) { result = it }

        result shouldBe MountPurchaseResult.Success
        fixture.ownership.selectedSpeedPercentage shouldBe 65
        fixture.wallet.withdrawals shouldBe 0
        fixture.journal.records() shouldBe emptyList()
    }

    "walking step tuning enforces the current level ceiling without charging" {
        val walkingMount = testMount().copy(movement = MountMovement.WALKING)
        val fixture = PurchaseFixture(walkingMount).also { it.ownership.level = 2 }
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 150, 200, 300, 400), listOf(110, 200, 400))
        var lockedResult: MountPurchaseResult? = null
        var selectedResult: MountPurchaseResult? = null

        fixture.coordinator.setStepHeightTuning(fixture.subject(), walkingMount, tuning, 300) { lockedResult = it }
        fixture.coordinator.setStepHeightTuning(fixture.subject(), walkingMount, tuning, 150) { selectedResult = it }

        lockedResult shouldBe MountPurchaseResult.NotForSale
        selectedResult shouldBe MountPurchaseResult.Success
        fixture.ownership.selectedStepHeightHundredths shouldBe 150
        fixture.wallet.withdrawals shouldBe 0
    }

    "authored size tuning respects its level gate and remains free" {
        val mount =
            testMount().copy(
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("standard", "Обычный", 1.0),
                        MountSizeOptionDefinition("massive", "Крупный", 1.15, minimumLevel = 3),
                    ),
            )
        val fixture = PurchaseFixture(mount).also { it.ownership.level = 2 }
        var locked: MountPurchaseResult? = null
        var selected: MountPurchaseResult? = null

        fixture.coordinator.setSizeTuning(fixture.subject(), mount, "massive") { locked = it }
        fixture.ownership.level = 3
        fixture.coordinator.setSizeTuning(fixture.subject(), mount, "massive") { selected = it }

        locked shouldBe MountPurchaseResult.NotForSale
        selected shouldBe MountPurchaseResult.Success
        fixture.ownership.selectedSizeId shouldBe "massive"
        fixture.wallet.withdrawals shouldBe 0
    }

    "grant-only size cannot be selected until its entitlement is issued" {
        val mount =
            testMount().copy(
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("standard", "Обычный", 1.0),
                        MountSizeOptionDefinition("colossal", "Колоссальный", 10.0, grantOnly = true),
                    ),
            )
        val fixture = PurchaseFixture(mount).also { it.ownership.level = 3 }
        var locked: MountPurchaseResult? = null
        var selected: MountPurchaseResult? = null

        fixture.coordinator.setSizeTuning(fixture.subject(), mount, "colossal") { locked = it }
        fixture.ownership.ownedSizeIds += "colossal"
        fixture.coordinator.setSizeTuning(fixture.subject(), mount, "colossal") { selected = it }

        locked shouldBe MountPurchaseResult.NotForSale
        selected shouldBe MountPurchaseResult.Success
        fixture.ownership.selectedSizeId shouldBe "colossal"
        fixture.wallet.withdrawals shouldBe 0
    }

    "unavailable saved size resolves to the effective level default" {
        val mount =
            testMount().copy(
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("standard", "Обычный", 1.0),
                        MountSizeOptionDefinition("massive", "Крупный", 1.15, minimumLevel = 3),
                    ),
            )
        val fixture = PurchaseFixture(mount).also {
            it.ownership.level = 1
            it.ownership.selectedSizeId = "massive"
        }
        var result: MountPurchaseResult? = null

        fixture.coordinator.setSizeTuning(fixture.subject(), mount, "standard") { result = it }

        result shouldBe MountPurchaseResult.AlreadyOwned
        fixture.ownership.selectedSizeId shouldBe "massive"
        fixture.wallet.withdrawals shouldBe 0
    }

    "rider view auto-hide is a free per-mount setting" {
        val fixture = PurchaseFixture().also { it.ownership.level = 1 }
        var result: MountPurchaseResult? = null

        fixture.coordinator.setRiderViewAutoHide(fixture.subject(), fixture.mount, true) { result = it }

        result shouldBe MountPurchaseResult.Success
        fixture.ownership.riderViewAutoHide shouldBe true
        fixture.wallet.withdrawals shouldBe 0
    }

    "unavailable balance cancels the prepared record without blocking future purchases" {
        val fixture = PurchaseFixture().also { it.wallet.balanceAvailable = false }
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { result = it }

        result shouldBe MountPurchaseResult.EconomyUnavailable
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.CANCELLED
        fixture.journal.hasOpenPurchase(fixture.playerId) shouldBe false
    }

    "ambiguous withdrawal is quarantined and cannot be purchased twice" {
        val fixture = PurchaseFixture().also { it.wallet.ambiguousWithdrawal = true }
        var first: MountPurchaseResult? = null
        var second: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { first = it }
        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { second = it }

        first shouldBe MountPurchaseResult.ManualReview
        second shouldBe MountPurchaseResult.ManualReview
        fixture.wallet.withdrawals shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.MANUAL_REVIEW
    }

    "startup recovery reapplies permission after a proven withdrawal" {
        val fixture = PurchaseFixture()
        val record = fixture.preparedRecord()
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000_000L,
            ),
        ) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                updatedAt = 3L,
                balanceBeforeMinor = 10_000_000L,
                balanceAfterMinor = 5_000_000L,
                evidence = "exact_balance_delta",
            ),
        ) shouldBe true
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount)), manual::add)

        fixture.ownership.level shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
        manual shouldBe emptyList()
        fixture.wallet.withdrawals shouldBe 0
    }

    "startup recovery quarantines a paid size removed from the current catalog" {
        val originalSize = MountSizeOptionDefinition("keychain", "Крошечный", 0.1, grantOnly = true, price = 40.0, currency = "tokens")
        val mount = testMount().copy(sizeOptions = listOf(MountSizeOptionDefinition("standard", "Обычный", 1.0), originalSize))
        val fixture = PurchaseFixture(mount)
        val tokenWallet = MutableWallet()
        fixture.wallet.currencyWallets["tokens"] = tokenWallet
        val record = fixture.preparedRecord().copy(
            kind = MountPurchaseKind.SIZE,
            target = originalSize.id,
            permission = mount.sizeOwnershipPermission(originalSize.id),
            priceMinor = 4_000L,
            currency = "tokens",
        )
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000L,
            ),
        ) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                updatedAt = 3L,
                balanceBeforeMinor = 10_000L,
                balanceAfterMinor = 6_000L,
                evidence = "exact_balance_delta",
            ),
        ) shouldBe true
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(
            MountCatalog(listOf(mount.copy(sizeOptions = listOf(MountSizeOptionDefinition("standard", "Обычный", 1.0))))),
            manual::add,
        )

        fixture.journal.records().single().let { recovered ->
            recovered.status shouldBe MountPurchaseJournalStatus.MANUAL_REVIEW
            recovered.evidence shouldBe "startup_target_missing"
        }
        manual.map(MountPurchaseJournalRecord::transactionId) shouldBe listOf(record.transactionId)
        tokenWallet.deposits shouldBe 0
    }

    "startup recovery uses exact provider history after an interrupted withdrawal call" {
        val fixture = PurchaseFixture().also {
            it.wallet.historyTransactionId = "91234"
            it.wallet.historyTransactionAtMillis = 2L
            it.wallet.historyAmountMinor = -5_000_000L
        }
        val record = fixture.preparedRecord()
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000_000L,
            ),
        ) shouldBe true
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount)), manual::add)

        fixture.wallet.historyReasons shouldBe listOf("arc-mount:${record.transactionId}")
        fixture.wallet.historyNotBeforeMillis shouldBe listOf(record.createdAt)
        fixture.ownership.level shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
        manual shouldBe emptyList()
    }

    "manual review history search includes the provider call that preceded quarantine" {
        val fixture = PurchaseFixture().also {
            it.wallet.historyTransactionId = "91234"
            it.wallet.historyTransactionAtMillis = 2L
            it.wallet.historyAmountMinor = -5_000_000L
        }
        val record = fixture.preparedRecord()
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000_000L,
            ),
        ) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.MANUAL_REVIEW,
                updatedAt = 3L,
                balanceBeforeMinor = 10_000_000L,
                evidence = "provider_threw",
            ),
        ) shouldBe true
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount)), manual::add)

        fixture.wallet.historyNotBeforeMillis shouldBe listOf(record.createdAt)
        fixture.ownership.level shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
        manual shouldBe emptyList()
    }

    "manual review refund search includes the provider call that preceded quarantine" {
        val fixture = PurchaseFixture().also {
            it.wallet.historyTransactionId = "92345"
            it.wallet.historyTransactionAtMillis = 5L
            it.wallet.historyAmountMinor = 5_000_000L
        }
        val record = fixture.preparedRecord()
        val withdrawn =
            record.copy(
                status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                updatedAt = 3L,
                balanceBeforeMinor = 10_000_000L,
                balanceAfterMinor = 5_000_000L,
                evidence = "exact_balance_delta",
            )
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000_000L,
            ),
        ) shouldBe true
        fixture.journal.persist(withdrawn) shouldBe true
        fixture.journal.persist(
            withdrawn.copy(
                status = MountPurchaseJournalStatus.OWNERSHIP_STARTED,
                updatedAt = 4L,
                evidence = "permission_write_started",
            ),
        ) shouldBe true
        fixture.journal.persist(
            withdrawn.copy(
                status = MountPurchaseJournalStatus.REFUND_STARTED,
                updatedAt = 5L,
                refundBalanceBeforeMinor = 5_000_000L,
                evidence = "permission_not_applied",
            ),
        ) shouldBe true
        fixture.journal.persist(
            withdrawn.copy(
                status = MountPurchaseJournalStatus.MANUAL_REVIEW,
                updatedAt = 6L,
                refundBalanceBeforeMinor = 5_000_000L,
                evidence = "provider_threw",
            ),
        ) shouldBe true
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount)), manual::add)

        fixture.wallet.historyReasons shouldBe listOf("arc-mount-refund:${record.transactionId}")
        fixture.wallet.historyNotBeforeMillis shouldBe listOf(record.createdAt)
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.REFUNDED
        manual shouldBe emptyList()
    }

    "manual review self-heals when the exact permission is later present" {
        val fixture = PurchaseFixture()
        val record = fixture.preparedRecord()
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000_000L,
            ),
        ) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.MANUAL_REVIEW,
                updatedAt = 3L,
                balanceBeforeMinor = 10_000_000L,
                balanceAfterMinor = 5_000_000L,
                evidence = "permission_verification_failed",
            ),
        ) shouldBe true
        fixture.ownership.addDirect(record.permission)
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount)), manual::add)

        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
        fixture.wallet.withdrawals shouldBe 0
        manual shouldBe emptyList()
    }
})

private class PurchaseFixture(val mount: MountDefinition = testMount()) {
    val playerId: UUID = UUID.randomUUID()
    val ownership = MutableOwnership()
    val wallet = MutableWallet()
    val journal = FileMountPurchaseJournal(Files.createTempDirectory("arc-mount-purchase-").resolve("journal.json"))
    var purchaseAllowed = true
    val coordinator = MountPurchaseCoordinator(ownership, wallet, journal, { true }, { it() }, clock = { 10L }, purchaseAllowed = { purchaseAllowed })

    fun subject() =
        MountPermissionSubject(playerId, "Rider") { permission ->
            permission == mount.levelPermission(ownership.level) && ownership.level > 0 ||
                permission == mount.glowPermission && ownership.glow ||
                permission == mount.glowDisabledPermission && ownership.glowDisabled ||
                permission in ownership.skinPermissions ||
                permission in ownership.activeSkinPermissions ||
                permission in ownership.abilityPermissions
        }

    fun preparedRecord() =
        MountPurchaseJournalRecord(
            transactionId = UUID.randomUUID().toString(),
            playerId = playerId.toString(),
            mountId = mount.id,
            kind = MountPurchaseKind.LEVEL,
            target = "1",
            permission = mount.levelPermission(1),
            priceMinor = 5_000_000L,
            createdAt = 1L,
            updatedAt = 1L,
        )
}

private class MutableOwnership : MountOwnership {
    var level = 0
    var glow = false
    var glowDisabled = false
    var selectedSpeedPercentage: Int? = null
    var selectedStepHeightHundredths: Int? = null
    var selectedSizeId: String? = null
    var riderViewAutoHide: Boolean? = null
    var failWrites = false
    val skinPermissions = hashSetOf<String>()
    val activeSkinPermissions = hashSetOf<String>()
    val abilityPermissions = hashSetOf<String>()
    val ownedSizeIds = hashSetOf<String>()
    private val directPermissions = hashSetOf<String>()

    override fun favoriteMountId(playerId: UUID): String? =
        directPermissions
            .asSequence()
            .filter { it.startsWith(MOUNT_FAVORITE_PERMISSION_PREFIX) }
            .map { it.removePrefix(MOUNT_FAVORITE_PERMISSION_PREFIX) }
            .filter(MountDefinition::validId)
            .minOrNull()

    override fun setFavoriteMount(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        write {
            directPermissions.removeIf { it.startsWith(MOUNT_FAVORITE_PERMISSION_PREFIX) }
            directPermissions += favoriteMountPermission(mount.id)
        }

    fun addDirect(permission: String) {
        directPermissions += permission
    }

    override fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile =
        MountProfile(
            level = level,
            glowOwned = glow,
            glowDisabled = glowDisabled,
            ownedSkinIds = mount.skins.filter { mount.skinPermission(it.id) in skinPermissions }.mapTo(hashSetOf()) { it.id },
            activeSkinId = mount.skins.firstOrNull { mount.activeSkinPermission(it.id) in activeSkinPermissions }?.id
                ?: MountDefinition.DEFAULT_SKIN_ID,
            ownedAbilityIds = mount.abilities.upgrades
                .filter { mount.abilityPermission(it.id) in abilityPermissions }
                .mapTo(hashSetOf(), MountAbilityUpgradeDefinition::id),
            ownedSizeIds = ownedSizeIds,
            selectedSpeedPercentage = selectedSpeedPercentage,
            selectedStepHeightHundredths = selectedStepHeightHundredths,
            selectedSizeId = selectedSizeId,
            riderViewAutoHide = riderViewAutoHide,
        )

    override fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void> =
        write { this.level = level; directPermissions += mount.levelPermission(level) }

    override fun revokeLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void> =
        write {
            directPermissions -= mount.levelPermission(level)
            if (this.level == level) this.level = (level - 1).coerceAtLeast(0)
        }

    override fun grantGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        write { glow = true; glowDisabled = false; directPermissions += mount.glowPermission }

    override fun revokeGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        write { glow = false; glowDisabled = false; directPermissions -= mount.glowPermission }

    override fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean): CompletableFuture<Void> =
        write { glowDisabled = !enabled }

    override fun grantSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition): CompletableFuture<Void> =
        write { skinPermissions += mount.skinPermission(skin.id); directPermissions += mount.skinPermission(skin.id) }

    override fun revokeSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition): CompletableFuture<Void> =
        write {
            skinPermissions -= mount.skinPermission(skin.id)
            directPermissions -= mount.skinPermission(skin.id)
            activeSkinPermissions -= mount.activeSkinPermission(skin.id)
        }

    override fun setActiveSkin(playerId: UUID, mount: MountDefinition, skinId: String): CompletableFuture<Void> =
        write {
            activeSkinPermissions.removeIf { it.startsWith("arc.mounts.${mount.id}.skin.active.") }
            if (skinId != MountDefinition.DEFAULT_SKIN_ID) activeSkinPermissions += mount.activeSkinPermission(skinId)
        }

    override fun grantAbility(
        playerId: UUID,
        mount: MountDefinition,
        ability: MountAbilityUpgradeDefinition,
    ): CompletableFuture<Void> =
        write {
            abilityPermissions += mount.abilityPermission(ability.id)
            directPermissions += mount.abilityPermission(ability.id)
        }

    override fun revokeAbility(
        playerId: UUID,
        mount: MountDefinition,
        ability: MountAbilityUpgradeDefinition,
    ): CompletableFuture<Void> =
        write {
            abilityPermissions -= mount.abilityPermission(ability.id)
            directPermissions -= mount.abilityPermission(ability.id)
        }

    override fun grantSize(
        playerId: UUID,
        mount: MountDefinition,
        size: MountSizeOptionDefinition,
    ): CompletableFuture<Void> =
        write {
            ownedSizeIds += size.id
            directPermissions += mount.sizeOwnershipPermission(size.id)
        }

    override fun revokeSize(
        playerId: UUID,
        mount: MountDefinition,
        size: MountSizeOptionDefinition,
    ): CompletableFuture<Void> =
        write {
            ownedSizeIds -= size.id
            directPermissions -= mount.sizeOwnershipPermission(size.id)
            if (selectedSizeId == size.id) selectedSizeId = null
            directPermissions -= mount.sizeTuningPermission(size.id)
        }

    override fun setSpeedTuning(playerId: UUID, mount: MountDefinition, percentage: Int): CompletableFuture<Void> =
        write {
            selectedSpeedPercentage = percentage
            directPermissions.removeIf { it.startsWith(mount.speedTuningPermissionPrefix) }
            directPermissions += mount.speedTuningPermission(percentage)
        }

    override fun setStepHeightTuning(playerId: UUID, mount: MountDefinition, hundredths: Int): CompletableFuture<Void> =
        write {
            selectedStepHeightHundredths = hundredths
            directPermissions.removeIf { it.startsWith(mount.stepHeightTuningPermissionPrefix) }
            directPermissions += mount.stepHeightTuningPermission(hundredths)
        }

    override fun setSizeTuning(playerId: UUID, mount: MountDefinition, sizeId: String): CompletableFuture<Void> =
        write {
            selectedSizeId = sizeId
            directPermissions.removeIf { it.startsWith(mount.sizeTuningPermissionPrefix) }
            directPermissions += mount.sizeTuningPermission(sizeId)
        }

    override fun setRiderViewAutoHide(
        playerId: UUID,
        mount: MountDefinition,
        enabled: Boolean,
    ): CompletableFuture<Void> =
        write {
            riderViewAutoHide = enabled
            directPermissions.removeIf { it.startsWith(mount.riderViewTuningPermissionPrefix) }
            directPermissions += mount.riderViewTuningPermission(enabled)
        }

    override fun hasDirectPermission(playerId: UUID, permission: String): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(permission in directPermissions)

    override fun resolveUniqueId(playerName: String): CompletableFuture<UUID?> = CompletableFuture.completedFuture(null)

    private fun write(change: () -> Unit): CompletableFuture<Void> {
        if (failWrites) return CompletableFuture.failedFuture(IllegalStateException("write failed"))
        change()
        return CompletableFuture.completedFuture(null)
    }
}

private class MutableWallet : MountWallet {
    var balanceMinor = 10_000_000L
    var balanceAvailable = true
    var ambiguousWithdrawal = false
    var withdrawals = 0
    var deposits = 0
    var historyTransactionId: String? = null
    var historyTransactionAtMillis: Long? = null
    var historyAmountMinor: Long? = null
    val historyReasons = mutableListOf<String>()
    val historyNotBeforeMillis = mutableListOf<Long>()
    val currencyWallets = mutableMapOf<String, MountWallet>()
    override val available = true

    override fun walletForCurrency(currency: String): MountWallet? =
        currencyWallets[currency] ?: super.walletForCurrency(currency)

    override fun balanceMinor(playerId: UUID): Long? = balanceMinor.takeIf { balanceAvailable }

    override fun withdraw(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): MountMoneyEvidence {
        withdrawals++
        if (ambiguousWithdrawal) return MountMoneyEvidence(null, true, null, "provider_threw")
        if (balanceMinor != expectedBalanceBeforeMinor || balanceMinor < amountMinor) {
            return MountMoneyEvidence(false, false, balanceMinor, "provider_rejected")
        }
        balanceMinor -= amountMinor
        return MountMoneyEvidence(true, true, balanceMinor)
    }

    override fun deposit(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): MountMoneyEvidence {
        deposits++
        if (balanceMinor != expectedBalanceBeforeMinor) {
            return MountMoneyEvidence(false, false, balanceMinor, "provider_rejected")
        }
        balanceMinor += amountMinor
        return MountMoneyEvidence(true, true, balanceMinor)
    }

    override fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<MountProviderTransactionEvidence> {
        historyReasons += reason
        historyNotBeforeMillis += notBeforeMillis
        val visibleTransaction =
            historyTransactionId.takeIf {
                amountMinor == historyAmountMinor &&
                    historyTransactionAtMillis?.let { transactionAt -> transactionAt >= notBeforeMillis } == true
            }
        return CompletableFuture.completedFuture(MountProviderTransactionEvidence(visibleTransaction, true))
    }
}
