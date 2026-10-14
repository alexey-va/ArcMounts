package ru.arc.mounts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

class MountPurchaseJournalTest : StringSpec({
    "journal survives a process reload with exact evidence" {
        val path = Files.createTempDirectory("arc-mount-journal-reload-").resolve("journal.json")
        val journal = FileMountPurchaseJournal(path)
        val prepared = journalRecord()
        journal.persist(prepared) shouldBe true
        journal.persist(prepared.withdrawalStarted()) shouldBe true
        journal.persist(prepared.fundsWithdrawn()) shouldBe true

        val reloaded = FileMountPurchaseJournal(path)

        reloaded.records().single() shouldBe prepared.fundsWithdrawn()
        reloaded.hasOpenPurchase(UUID.fromString(prepared.playerId)) shouldBe true
    }

    "journal rejects illegal transitions and immutable identity changes" {
        val journal = FileMountPurchaseJournal(Files.createTempDirectory("arc-mount-journal-transition-").resolve("journal.json"))
        val prepared = journalRecord()
        journal.persist(prepared) shouldBe true

        shouldThrow<IllegalArgumentException> {
            journal.persist(prepared.copy(status = MountPurchaseJournalStatus.COMPLETED, updatedAt = 2L))
        }
        shouldThrow<IllegalArgumentException> {
            journal.persist(prepared.copy(priceMinor = prepared.priceMinor + 1L, updatedAt = 2L))
        }
    }

    "manual review remains an unresolved player lock" {
        val journal = FileMountPurchaseJournal(Files.createTempDirectory("arc-mount-journal-review-").resolve("journal.json"))
        val prepared = journalRecord()
        journal.persist(prepared) shouldBe true
        journal.persist(prepared.withdrawalStarted()) shouldBe true
        journal.persist(
            prepared.withdrawalStarted().copy(
                status = MountPurchaseJournalStatus.MANUAL_REVIEW,
                updatedAt = 3L,
                evidence = "ambiguous_withdrawal",
            ),
        ) shouldBe true

        journal.hasOpenPurchase(UUID.fromString(prepared.playerId)) shouldBe true
    }

    "manual review may advance only to a proven recovery state" {
        val journal = FileMountPurchaseJournal(Files.createTempDirectory("arc-mount-journal-recovery-").resolve("journal.json"))
        val prepared = journalRecord()
        val review =
            prepared.withdrawalStarted().copy(
                status = MountPurchaseJournalStatus.MANUAL_REVIEW,
                updatedAt = 3L,
                evidence = "provider_threw",
            )
        journal.persist(prepared) shouldBe true
        journal.persist(prepared.withdrawalStarted()) shouldBe true
        journal.persist(review) shouldBe true

        journal.persist(
            review.copy(
                status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                updatedAt = 4L,
                balanceAfterMinor = 5_000_000L,
                evidence = "history_withdrawal:91234",
            ),
        ) shouldBe true
        shouldThrow<IllegalArgumentException> {
            journal.persist(review.copy(status = MountPurchaseJournalStatus.CANCELLED, updatedAt = 5L))
        }
    }

    "old journal records without currency load as vault" {
        val path = Files.createTempDirectory("arc-mount-journal-legacy-").resolve("journal.json")
        Files.writeString(
            path,
            """{"schemaVersion":1,"records":[{"transactionId":"00000000-0000-0000-0000-000000000001","playerId":"00000000-0000-0000-0000-000000000002","mountId":"bee","kind":"LEVEL","target":"1","permission":"arc.mounts.bee.1","priceMinor":5000000,"status":"PREPARED","createdAt":1,"updatedAt":1}]}""",
        )

        FileMountPurchaseJournal(path).records().single().currency shouldBe "vault"
    }
})

private fun journalRecord() =
    MountPurchaseJournalRecord(
        transactionId = UUID.randomUUID().toString(),
        playerId = UUID.randomUUID().toString(),
        mountId = "bee",
        kind = MountPurchaseKind.LEVEL,
        target = "1",
        permission = "arc.mounts.bee.1",
        priceMinor = 5_000_000L,
        createdAt = 1L,
        updatedAt = 1L,
    )

private fun MountPurchaseJournalRecord.withdrawalStarted() =
    copy(
        status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
        updatedAt = 2L,
        balanceBeforeMinor = 10_000_000L,
    )

private fun MountPurchaseJournalRecord.fundsWithdrawn() =
    withdrawalStarted().copy(
        status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
        updatedAt = 3L,
        balanceAfterMinor = 5_000_000L,
        evidence = "exact_balance_delta",
    )
