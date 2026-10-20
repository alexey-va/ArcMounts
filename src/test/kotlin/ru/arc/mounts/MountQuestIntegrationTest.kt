package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.ranks.api.RankQuestApi
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

class MountQuestIntegrationTest : StringSpec({
    "ownership distinguishes missing cached data, no ownership and an effective owned mount without IO" {
        val mount = mockk<MountDefinition>()
        val ownership = mockk<MountOwnership>()
        val player = UUID.randomUUID()
        every { ownership.profile(any(), mount) } returns MountProfile(0, false, false)
        ownedMountQuestEligibility(null, ownership, true, player) shouldBe Optional.empty()
        ownedMountQuestEligibility(listOf(mount), ownership, false, player) shouldBe Optional.empty()
        verify(exactly = 0) { ownership.profile(any(), any()) }
        ownedMountQuestEligibility(listOf(mount), ownership, true, player) shouldBe Optional.of(false)
        every { ownership.profile(any(), mount) } returns MountProfile(1, false, false)
        ownedMountQuestEligibility(listOf(mount), ownership, true, player) shouldBe Optional.of(true)
        verify(exactly = 0) { ownership.grantLevel(any(), any(), any()) }
    }

    "real 3D movement carries fractions and batches every five seconds and once on dismount" {
        val player = UUID.randomUUID(); val session = UUID.randomUUID(); val world = UUID.randomUUID()
        val ride = MountRideDistance(session, player, 0)
        fun at(x: Double, y: Double = 0.0) = MountRidePoint(world, x, y, 0.0)
        ride.observe(at(0.0), 0) shouldBe null
        ride.observe(at(0.6), 100) shouldBe null
        ride.observe(at(1.2), 200) shouldBe null
        ride.observe(at(1.2, 1.0), 5000) shouldBe MountRideBatch(player, "$session:1", 2)
        ride.observe(at(2.3, 1.0), 5050) shouldBe null
        ride.flush(5100) shouldBe MountRideBatch(player, "$session:2", 1)
        ride.flush(5100) shouldBe null
    }

    "teleports world changes non-finite coordinates and discontinuities never count as ridden distance" {
        val player = UUID.randomUUID(); val session = UUID.randomUUID(); val world = UUID.randomUUID()
        val ride = MountRideDistance(session, player, 0)
        fun at(x: Double, targetWorld: UUID = world) = MountRidePoint(targetWorld, x, 0.0, 0.0)
        ride.observe(at(0.0), 0)
        ride.observe(at(0.75), 1)
        ride.observe(at(100.0), 2)
        ride.observe(at(100.5), 3)
        ride.discontinuity()
        ride.observe(at(102.0), 4)
        ride.observe(at(Double.NaN), 5)
        ride.observe(at(200.0), 6)
        ride.observe(at(201.0, UUID.randomUUID()), 7)
        ride.flush(5000) shouldBe null
    }

    "distance retries keep the same batch ID and amount and stop after three retries" {
        val scheduler = TestTaskScheduler(); val scope = LifecycleTaskScope(scheduler)
        val calls = mutableListOf<List<Any>>()
        val api = RankQuestApi { source, event, player, objective, amount ->
            calls += listOf(source, event, player, objective, amount)
            CompletableFuture.failedFuture(IllegalStateException("database unavailable"))
        }
        val integration = MountQuestIntegration(scope, { api }, Logger.getAnonymousLogger().also { it.level = Level.OFF })
        val batch = MountRideBatch(UUID.randomUUID(), "${UUID.randomUUID()}:1", 7)
        integration.record(batch)
        scheduler.tick(600)
        calls.size shouldBe 4
        calls.distinct() shouldBe listOf(listOf("mounts_ride", batch.eventId, batch.playerId, "mount.travel", 7L))
        scheduler.pendingCount() shouldBe 0
        scope.close()
    }
})
