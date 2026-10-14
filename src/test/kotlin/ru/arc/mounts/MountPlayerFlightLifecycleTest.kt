package ru.arc.mounts

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.papermc.paper.event.player.PlayerTrackEntityEvent
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import java.time.Duration

class MountPlayerFlightLifecycleTest : TestBase() {
    @Test
    fun `player flight attaches and replays its passenger before restoring flight on cleanup`() {
        val pilot = server.addPlayer("FlightCompanion")
        val originalAllowFlight = pilot.allowFlight
        val originalIsFlying = pilot.isFlying
        val originalFlySpeed = pilot.flySpeed
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        val synchronizedGraphs = mutableListOf<List<LivingEntity>>()
        val scheduler = mockk<TaskScheduler>()
        every { scheduler.runLater(any(), any()) } answers {
            scheduled += firstArg<Long>() to secondArg<Runnable>()
            mockk<ScheduledTask>(relaxed = true)
        }

        val config = testConfig()
        val definition = playerFlightDefinition()
        val controller =
            MountSessionController(
                plugin = plugin,
                scheduler = scheduler,
                configProvider = { config },
                allowedMountIds = setOf(definition.id),
                message = { _, _, _ -> },
                synchronizePassengers = { _, graph -> synchronizedGraphs += graph },
            )
        val result =
            controller.spawn(
                pilot,
                definition,
                MountRuntimeSettings(1.1, 1.0, 1.0, 1.0, 1.0, null, false, emptyList()),
                60_000,
            )

        result shouldBe MountSpawnResult.SUCCESS
        pilot.vehicle shouldBe null
        val body = pilot.passengers.single() as LivingEntity
        body.vehicle shouldBe pilot
        isMountControlledBy(pilot, body, MountControl.PLAYER_FLIGHT) shouldBe true
        pilot.allowFlight shouldBe true
        pilot.isFlying shouldBe true
        val recoveryKey = NamespacedKey(plugin, MountVisualFlight.RECOVERY_KEY)
        pilot.persistentDataContainer.keys.contains(recoveryKey) shouldBe true

        scheduled.single().first shouldBe 1L
        scheduled.removeAt(0).second.run()
        synchronizedGraphs shouldBe listOf(listOf(pilot))

        controller.onFlightBodyTracked(PlayerTrackEntityEvent(pilot, body))
        scheduled.single().first shouldBe 1L
        scheduled.removeAt(0).second.run()
        synchronizedGraphs shouldBe listOf(listOf(pilot), listOf(pilot))

        val initialPilotVelocity = Vector(0.13, -0.04, 0.21)
        val initialBodyVelocity = Vector(-0.05, 0.09, 0.17)
        pilot.velocity = initialPilotVelocity
        body.velocity = initialBodyVelocity
        pilot.setRotation(61f, 0f)
        val pilotPosition = pilot.location.toVector()
        val bodyLocation = body.location.clone()
        val tick = MountSessionController::class.java.getDeclaredMethod("tick").apply { isAccessible = true }
        tick.invoke(controller)

        pilot.velocity shouldBe initialPilotVelocity
        body.velocity shouldBe initialBodyVelocity
        pilot.location.toVector() shouldBe pilotPosition
        body.location.toVector() shouldBe bodyLocation.toVector()
        body.yaw shouldBe pilot.location.yaw

        controller.remove(pilot.uniqueId, MountRemovalReason.RELOAD)

        controller.activeSessionCount() shouldBe 0
        body.isValid shouldBe false
        pilot.passengers shouldBe emptyList()
        pilot.allowFlight shouldBe originalAllowFlight
        pilot.isFlying shouldBe originalIsFlying
        pilot.flySpeed shouldBe originalFlySpeed
        pilot.persistentDataContainer.keys.contains(recoveryKey) shouldBe false
    }

    @Test
    fun `a rejected player attachment removes the body and rolls back the recovery marker`() {
        val pilot = spyk(server.addPlayer("RejectedFlight"))
        val originalAllowFlight = pilot.allowFlight
        val originalIsFlying = pilot.isFlying
        val originalFlySpeed = pilot.flySpeed
        val entitiesBefore = pilot.world.entities.mapTo(hashSetOf()) { it.uniqueId }
        every { pilot.addPassenger(any()) } returns false

        val config = testConfig()
        val definition = playerFlightDefinition()
        val controller =
            MountSessionController(
                plugin = plugin,
                scheduler = mockk(relaxed = true),
                configProvider = { config },
                allowedMountIds = setOf(definition.id),
                message = { _, _, _ -> },
            )
        val result =
            controller.spawn(
                pilot,
                definition,
                MountRuntimeSettings(1.1, 1.0, 1.0, 1.0, 1.0, null, false, emptyList()),
                60_000,
            )

        result shouldBe MountSpawnResult.SPAWN_FAILED
        verify(exactly = 1) { pilot.addPassenger(any()) }
        controller.activeSessionCount() shouldBe 0
        pilot.world.entities.mapTo(hashSetOf()) { it.uniqueId } shouldBe entitiesBefore
        pilot.allowFlight shouldBe originalAllowFlight
        pilot.isFlying shouldBe originalIsFlying
        pilot.flySpeed shouldBe originalFlySpeed
        pilot.persistentDataContainer.keys.contains(
            NamespacedKey(plugin, MountVisualFlight.RECOVERY_KEY),
        ) shouldBe false
    }

    private fun playerFlightDefinition() = testMount().copy(control = MountControl.PLAYER_FLIGHT, entityType = "ARMOR_STAND")

    private fun testConfig(): MountModuleConfig = mockk<MountModuleConfig>(relaxed = true).also { config ->
        every { config.summonCooldown } returns Duration.ZERO
        every { config.doubleSneakWindow } returns Duration.ofMillis(500)
        every { config.flyingSpeedScale } returns 0.55
        every { config.maximumSpeedBlocksPerTick } returns 1.05
        every { config.allowedWorlds } returns emptySet()
        every { config.compensateAirborneMining } returns false
        every { config.idleTimeout } returns Duration.ofMinutes(5)
        every { config.maximumHeightAboveWorld } returns 32
        every { config.postFlightSlowFalling } returns Duration.ZERO
        every { config.message(any(), any()) } answers { secondArg() }
    }
}
