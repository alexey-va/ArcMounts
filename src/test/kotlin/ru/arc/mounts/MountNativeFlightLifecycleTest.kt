package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.HappyGhast
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Phantom
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.EntityEquipment
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.BoundingBox
import ru.arc.core.TaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.time.Duration
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Logger

class MountNativeFlightLifecycleTest : StringSpec({
    "native flight creates one passenger graph and removes both owned entities on reload" {
        MockBukkitTestRuntime.open().use {
            val fixture = NativeFlightFixture()
            fixture.summon() shouldBe MountSpawnResult.SUCCESS
            fixture.riders shouldBe listOf(fixture.pilot, fixture.body)
            verify { fixture.carrier.setAdult() }
            verify { fixture.carrierEquipment.setItem(EquipmentSlot.BODY, match { stack -> stack.type == Material.WHITE_HARNESS }, true) }
            verify { fixture.carrier.isInvisible = true }
            verify { fixture.body.setAI(false) }
            verify { fixture.body.isCollidable = false }
            verify(exactly = 0) { fixture.carrier.teleport(any<Location>()) }
            verify(exactly = 0) { fixture.body.teleport(any<Location>()) }
            verify(exactly = 0) { fixture.carrier.velocity = any() }
            verify(exactly = 0) { fixture.carrier.setRotation(any(), any()) }
            verify(exactly = 0) { fixture.pilot.velocity = any() }
            verify(exactly = 0) { fixture.pilot.teleport(any<Location>()) }
            verify(exactly = 0) { fixture.pilot.allowFlight = any() }
            fixture.controller.stopAll()
            fixture.controller.activeSessionCount() shouldBe 0
            verify(exactly = 1) { fixture.carrier.remove() }
            verify(exactly = 1) { fixture.body.remove() }
        }
    }

    "a failed visible-body attachment cleans up the carrier and body without a live session" {
        MockBukkitTestRuntime.open().use {
            val fixture = NativeFlightFixture(rejectBody = true)
            fixture.summon() shouldBe MountSpawnResult.SPAWN_FAILED
            fixture.controller.activeSessionCount() shouldBe 0
            verify(exactly = 1) { fixture.carrier.remove() }
            verify(exactly = 1) { fixture.body.remove() }
        }
    }
})

private class NativeFlightFixture(rejectBody: Boolean = false) {
    val carrier = mockk<HappyGhast>(relaxed = true)
    val body = mockk<Phantom>(relaxed = true)
    val pilot = mockk<Player>(relaxed = true)
    val carrierEquipment = mockk<EntityEquipment>(relaxed = true)
    val riders = mutableListOf<Entity>()
    private val server = mockk<Server>(relaxed = true)
    private val world = mockk<World>(relaxed = true)
    private val config = mockk<MountModuleConfig>(relaxed = true)
    private val plugin = mockk<JavaPlugin>(relaxed = true)
    private val definition = testMount().copy(
        entityType = "HAPPY_GHAST", control = MountControl.NATIVE_FLIGHT,
        visualEntityType = "PHANTOM", passengerSeats = 2,
    )
    val controller: MountSessionController

    init {
        every { plugin.name } returns "NativeFlightTest"
        every { plugin.logger } returns Logger.getLogger("NativeFlightTest")
        every { plugin.server } returns server
        every { pilot.uniqueId } returns UUID.randomUUID()
        every { pilot.name } returns "NativePilot"
        every { pilot.world } returns world
        every { pilot.location } returns Location(world, 0.0, 64.0, 0.0)
        every { pilot.vehicle } returns null
        every { config.summonCooldown } returns Duration.ofSeconds(2)
        every { config.doubleSneakWindow } returns Duration.ofMillis(500)
        every { config.flyingSpeedScale } returns 0.55
        every { config.maximumSpeedBlocksPerTick } returns 1.05
        every { config.allowedWorlds } returns emptySet()
        every { config.message(any(), any()) } answers { secondArg() }
        listOf(carrier, body).forEach { entity ->
            val id = UUID.randomUUID()
            every { entity.uniqueId } returns id
            every { entity.isInWorld } returns true
            every { entity.isValid } returns true
            every { entity.location } returns Location(world, 0.0, 64.0, 0.0)
            every { entity.boundingBox } returns BoundingBox(-0.2, 64.0, -0.2, 0.2, 64.4, 0.2)
            every { entity.persistentDataContainer } returns mockk<PersistentDataContainer>(relaxed = true)
            every { entity.equipment } returns mockk<EntityEquipment>(relaxed = true)
            every { entity.getAttribute(any<Attribute>()) } returns mockk<AttributeInstance>(relaxed = true)
            every { server.getEntity(id) } returns entity
        }
        every { carrier.passengers } answers { riders.toList() }
        every { carrier.equipment } returns carrierEquipment
        every { carrier.canUseEquipmentSlot(EquipmentSlot.BODY) } returns true
        every { carrier.addPassenger(any()) } answers {
            val passenger = firstArg<Entity>()
            if (passenger == body && rejectBody) false else { riders.add(passenger); true }
        }
        every { world.spawnEntity(any<Location>(), any<EntityType>(), any<CreatureSpawnEvent.SpawnReason>(), any<Consumer<Entity>>()) } answers {
            val entity: LivingEntity = if (secondArg<EntityType>() == EntityType.HAPPY_GHAST) carrier else body
            arg<Consumer<Entity>>(3).accept(entity)
            entity
        }
        every { server.getPlayer(pilot.uniqueId) } returns pilot
        controller = MountSessionController(plugin, mockk<TaskScheduler>(relaxed = true), { config }, setOf(definition.id), { _, _, _ -> })
    }

    fun summon(): MountSpawnResult = controller.spawn(
        pilot, definition, MountRuntimeSettings(1.1, 1.1, 1.0, 1.0, 1.0, null, false, emptyList()), 60_000,
    )
}
