package ru.arc.mounts

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture

class MountRewardGrantTest : io.kotest.core.spec.style.StringSpec({
    "preview is an inert configured icon and disappears when the module closes" {
        MockBukkitTestRuntime.open().use {
            val grant = MountRewardGrant()
            grant.activate(MountCatalog(listOf(testMount())), mockk(relaxed = true))

            grant.preview("bee")?.type shouldBe Material.BEE_SPAWN_EGG

            grant.close()
            grant.preview("bee").shouldBeNull()
        }
    }

    "owned mount is not granted again" {
        MockBukkitTestRuntime.open().use { paper ->
            val mount = testMount()
            val player = paper.addPlayer("AlreadyRider")
            val ownership = mockk<MountOwnership> {
                every { profile(any(), mount) } returns MountProfile(2, false, false)
            }
            val grant = MountRewardGrant().also { it.activate(MountCatalog(listOf(mount)), ownership) }

            grant.grant(player, mount.id).join() shouldBe MountRewardResult.AlreadyOwned(mount.id, 2)
            verify(exactly = 0) { ownership.grantLevel(any(), any(), any()) }
        }
    }

    "an existing purchase or transfer blocks reward resolution" {
        MockBukkitTestRuntime.open().use { paper ->
            val mount = testMount()
            val player = paper.addPlayer("BusyRider")
            val ownership = mockk<MountOwnership>(relaxed = true)
            val grant = MountRewardGrant().also {
                it.activate(MountCatalog(listOf(mount)), ownership, busy = { true })
            }

            grant.grant(player, mount.id).join() shouldBe
                MountRewardResult.Rejected(mount.id, MountRewardRejection.BUSY)
            verify(exactly = 0) { ownership.profile(any(), any()) }
            verify(exactly = 0) { ownership.grantLevel(any(), any(), any()) }
            grant.isBusy(player.uniqueId) shouldBe false
        }
    }

    "one pending player and mount grant blocks duplicates and allows no late result after close" {
        MockBukkitTestRuntime.open().use { paper ->
            val mount = testMount()
            val player = paper.addPlayer("PendingRider")
            val nativeWrite = CompletableFuture<Void>()
            val ownership = mockk<MountOwnership> {
                every { profile(any(), mount) } returns MountProfile(0, false, false)
                every { grantLevel(player.uniqueId, mount, 1) } returns nativeWrite
            }
            val grant = MountRewardGrant().also { it.activate(MountCatalog(listOf(mount)), ownership) }

            val first = grant.grant(player, mount.id)
            grant.grant(player, mount.id).join() shouldBe
                MountRewardResult.Rejected(mount.id, MountRewardRejection.ALREADY_PENDING)

            grant.close()
            first.join() shouldBe MountRewardResult.Rejected(mount.id, MountRewardRejection.SHUTDOWN)
            nativeWrite.complete(null)
            first.join() shouldBe MountRewardResult.Rejected(mount.id, MountRewardRejection.SHUTDOWN)
            verify(exactly = 1) { ownership.grantLevel(player.uniqueId, mount, 1) }
        }
    }

    "failed native write remains uncertain and blocks a blind retry" {
        MockBukkitTestRuntime.open().use { paper ->
            val mount = testMount()
            val player = paper.addPlayer("RetryRider")
            val writes = ArrayDeque<CompletableFuture<Void>>()
            val ownership = mockk<MountOwnership> {
                every { profile(any(), mount) } returns MountProfile(0, false, false)
                every { grantLevel(player.uniqueId, mount, 1) } answers {
                    CompletableFuture<Void>().also(writes::addLast)
                }
            }
            val grant = MountRewardGrant().also { it.activate(MountCatalog(listOf(mount)), ownership) }

            val first = grant.grant(player, mount.id)
            writes.removeFirst().completeExceptionally(IllegalStateException("test"))
            first.join() shouldBe MountRewardResult.Rejected(mount.id, MountRewardRejection.GRANT_UNCERTAIN)
            grant.isBusy(player.uniqueId) shouldBe true

            val retry = grant.grant(player, mount.id)
            retry.join() shouldBe MountRewardResult.Rejected(mount.id, MountRewardRejection.ALREADY_PENDING)
            verify(exactly = 1) { ownership.grantLevel(player.uniqueId, mount, 1) }
        }
    }
})
