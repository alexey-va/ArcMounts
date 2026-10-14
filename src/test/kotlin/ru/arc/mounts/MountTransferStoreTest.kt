package ru.arc.mounts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

class MountTransferStoreTest : StringSpec({
    "persists a validated record and reloads its stage" {
        val root = Files.createTempDirectory("arc-mount-transfer-store-")
        val id = UUID.randomUUID()
        val issuer = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val store = FileMountTransferStore(root)
        val packed = record(id, issuer).copy(stage = MountTransferStage.PACKED)
        store.save(packed)
        val available = packed.copy(stage = MountTransferStage.AVAILABLE)
        store.save(available)
        val claiming = available.copy(stage = MountTransferStage.CLAIMING, recipient = recipient)
        store.save(claiming)

        FileMountTransferStore(root).get(id) shouldBe claiming
        FileMountTransferStore(root).records().map { it.id } shouldContainExactly listOf(id)
    }

    "rejects stage rollback and recipient changes" {
        val root = Files.createTempDirectory("arc-mount-transfer-store-")
        val id = UUID.randomUUID()
        val issuer = UUID.randomUUID()
        val firstRecipient = UUID.randomUUID()
        val store = FileMountTransferStore(root)
        val claiming = record(id, issuer).copy(stage = MountTransferStage.CLAIMING, recipient = firstRecipient)
        store.save(claiming)

        shouldThrow<IllegalArgumentException> {
            store.save(claiming.copy(stage = MountTransferStage.AVAILABLE, recipient = null))
        }
        shouldThrow<IllegalArgumentException> {
            store.save(claiming.copy(recipient = UUID.randomUUID()))
        }
    }

    "rejects a certificate whose permissions contain no purchased level" {
        val record = record(UUID.randomUUID(), UUID.randomUUID()).copy(permissions = listOf("arc.mounts.bee.glow"))
        shouldThrow<IllegalArgumentException> { record.validate() }
    }
})

private fun record(id: UUID, issuer: UUID) =
    MountTransferRecord(
        id = id,
        issuer = issuer,
        mountId = "bee",
        permissions = listOf("arc.mounts.bee.1", "arc.mounts.bee.glow"),
    )
