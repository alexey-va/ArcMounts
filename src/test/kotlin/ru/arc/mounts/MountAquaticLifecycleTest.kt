package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.bukkit.Input
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.config.ConfigManager
import ru.arc.core.TaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Logger

class MountAquaticLifecycleTest : StringSpec({
    "all aquatic catalog mounts retain the summon gate and get five continuous dry seconds" {
        withAquaticConfig { config ->
            val swimming = config.catalog().all.filter { it.movement == MountMovement.SWIMMING }
            swimming.size shouldBe 11
            swimming.forEach { definition ->
                val fixture = AquaticFixture(config, definition)
                fixture.waterOverride = false
                fixture.summon() shouldBe MountSpawnResult.WATER_REQUIRED
                fixture.waterOverride = true
                fixture.summon() shouldBe MountSpawnResult.SUCCESS
                fixture.tick()
                fixture.waterOverride = false
                fixture.tick(0)
                fixture.tick(4_999)
                fixture.controller.activeSessionCount() shouldBe 1
                fixture.waterOverride = true
                fixture.tick(1)
                fixture.waterOverride = false
                fixture.tick(1)
                fixture.tick(4_999)
                fixture.controller.activeSessionCount() shouldBe 1
                fixture.tick(1)
                fixture.controller.activeSessionCount() shouldBe 0
                fixture.removalReasons shouldBe listOf("LEFT_WATER")
                verify(exactly = 1) { fixture.body.remove() }
            }
        }
    }

    "bundled first-level aquatic mounts and all dolphin levels clear the surface and return on one arc" {
        withAquaticConfig { config ->
            val catalog = config.catalog()
            val cases = catalog.all.filter { it.movement == MountMovement.SWIMMING }.map { it to 1 } +
                listOf(checkNotNull(catalog["dolphin"]) to 2, checkNotNull(catalog["dolphin"]) to 3)
            cases.forEach { (definition, level) ->
                val fixture = AquaticFixture(config, definition, level)
                fixture.summon() shouldBe MountSpawnResult.SUCCESS
                fixture.input = MountInputState(forward = true, jump = true)
                fixture.breach()
                val launch = fixture.velocity.clone()
                val start = fixture.position.clone()
                (launch.y >= 0.5 && launch.z >= 0.44) shouldBe true
                // Holding ascent, steering and sprint in the air must not refresh the impulse.
                fixture.input = MountInputState(left = true, jump = true, sprint = true)
                fixture.yaw = 90f
                var peak = fixture.position.y
                var previousVertical = launch.y
                var descended = false
                var airborneTicks = 0
                while (fixture.position.y >= 64.0 && airborneTicks < 40) {
                    fixture.physicsStep()
                    peak = maxOf(peak, fixture.position.y)
                    fixture.tick()
                    if (fixture.position.y >= 64.0) {
                        (fixture.velocity.y < previousVertical) shouldBe true
                        fixture.velocity.x shouldBe (0.0 plusOrMinus 1.0e-9)
                        descended = descended || fixture.velocity.y < 0.0
                        previousVertical = fixture.velocity.y
                    }
                    airborneTicks++
                }
                (peak - start.y in 1.5..3.0) shouldBe true
                (fixture.position.z - start.z > 3.0) shouldBe true
                (airborneTicks in 10..25) shouldBe true
                descended shouldBe true
                fixture.controller.activeSessionCount() shouldBe 1
                // The live block predicate restores control even before cached isInWater updates.
                fixture.input = MountInputState(backward = true)
                fixture.yaw = 0f
                fixture.tick()
                fixture.velocity.z shouldBe (-definition.levels[level - 1].speed * config.swimmingSpeedScale plusOrMinus 1.0e-9)
                fixture.velocity.y shouldBe (0.0 plusOrMinus 1.0e-9)
                println("AQUATIC_ARC mount=${definition.id} level=$level height=${peak - start.y} distance=${fixture.position.z - start.z} ticks=$airborneTicks")
                fixture.controller.stopAll()
            }
        }
    }

    "air collisions stop blocked axes without restoring squid momentum into a wall or ceiling" {
        withAquaticConfig { config ->
            listOf("dolphin", "squid").forEach { id ->
                val fixture = AquaticFixture(config, checkNotNull(config.catalog()[id]))
                fixture.summon() shouldBe MountSpawnResult.SUCCESS
                fixture.input = MountInputState(forward = true, jump = true)
                fixture.breach()
                fixture.wallZ = fixture.position.z + 0.1
                fixture.ceilingY = fixture.position.y + 0.1
                fixture.physicsStep()
                fixture.tick()
                fixture.velocity.z shouldBe (0.0 plusOrMinus 1.0e-9)
                (fixture.velocity.y < 0.0) shouldBe true
                fixture.wallZ = Double.POSITIVE_INFINITY
                fixture.ceilingY = Double.POSITIVE_INFINITY
                fixture.physicsStep()
                fixture.tick()
                fixture.velocity.z shouldBe (0.0 plusOrMinus 1.0e-9)
                (fixture.velocity.y < 0.0) shouldBe true
            }
        }
    }

    "walking onto shore cannot launch or hover even with Space held" {
        withAquaticConfig { config ->
            val fixture = AquaticFixture(config, checkNotNull(config.catalog()["dolphin"]))
            fixture.summon() shouldBe MountSpawnResult.SUCCESS
            fixture.input = MountInputState(forward = true)
            fixture.tick()
            fixture.position.y = 65.0
            fixture.waterOverride = false
            fixture.input = MountInputState(jump = true)
            fixture.floorY = 64.5
            repeat(15) {
                fixture.tick()
                (fixture.velocity.y <= 0.0) shouldBe true
                fixture.physicsStep()
            }
            fixture.position.y shouldBe 64.5
            fixture.controller.activeSessionCount() shouldBe 1
        }
    }

    "kelp and bubble-column re-entry reset the grace and restore immediate controls" {
        withAquaticConfig { config ->
            listOf(Material.KELP, Material.SEAGRASS, Material.BUBBLE_COLUMN).forEach { material ->
                val fixture = AquaticFixture(config, checkNotNull(config.catalog()["dolphin"]))
                fixture.summon() shouldBe MountSpawnResult.SUCCESS
                fixture.waterOverride = false
                fixture.position.y = 65.0
                fixture.tick()
                fixture.tick(4_999)
                fixture.waterOverride = material != Material.BUBBLE_COLUMN
                fixture.materialOverride = material
                fixture.input = MountInputState(forward = true)
                fixture.tick(1)
                fixture.velocity.z shouldBe (0.52 * 0.45 plusOrMinus 1.0e-9)
                fixture.materialOverride = null
                fixture.waterOverride = false
                fixture.tick(1)
                fixture.tick(4_999)
                fixture.controller.activeSessionCount() shouldBe 1
            }
        }
    }

    "breach and falling obey lower operator velocity caps" {
        withAquaticConfig { bundled ->
            val config = spyk(bundled)
            every { config.maximumVerticalSpeed } returns 0.3
            every { config.maximumSpeedBlocksPerTick } returns 0.4
            val fixture = AquaticFixture(config, checkNotNull(config.catalog()["dolphin"]))
            fixture.summon() shouldBe MountSpawnResult.SUCCESS
            fixture.input = MountInputState(forward = true, jump = true)
            fixture.breach()
            fixture.waterOverride = false
            repeat(30) {
                (kotlin.math.abs(fixture.velocity.y) <= 0.300000001) shouldBe true
                (fixture.velocity.length() <= 0.400000001) shouldBe true
                fixture.physicsStep()
                fixture.tick()
            }
        }
    }
})

private fun withAquaticConfig(action: (MountModuleConfig) -> Unit) {
    MockBukkitTestRuntime.open().use {
        val dataPath = Files.createTempDirectory("arc-mounts-aquatic-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        checkNotNull(MountAquaticLifecycleTest::class.java.getResourceAsStream("/modules/mounts.yml")).use { input ->
            Files.newOutputStream(moduleDir.resolve("mounts.yml")).use(input::copyTo)
        }
        try {
            action(MountModuleConfig.load(dataPath))
        } finally {
            ConfigManager.clear()
        }
    }
}

/** Controller-level boundary fixture, not a native-client or full entity-physics implementation.
 * Purpur 1.21.11-2568 f57bd86: scheduler precedes entity travel; water state is cached before
 * movement, air drag follows movement, and Squid.aiStep zeros X/Z after super.aiStep/travel.
 */
private class AquaticFixture(private val config: MountModuleConfig, private val definition: MountDefinition, level: Int = 1) {
    val body = mockk<Mob>(relaxed = true)
    val controller: MountSessionController
    val position = Vector(0.0, 63.6, 0.0)
    var velocity = Vector()
    var input = MountInputState()
    var yaw = 0f
    var waterOverride: Boolean? = null
    var materialOverride: Material? = null
    var wallZ = Double.POSITIVE_INFINITY
    var ceilingY = Double.POSITIVE_INFINITY
    var floorY = Double.NEGATIVE_INFINITY
    val removalReasons = mutableListOf<String>()
    private var cachedInWater = true
    val inWater: Boolean get() = waterOverride ?: cachedInWater
    private var now = System.currentTimeMillis()
    private val player = mockk<Player>(relaxed = true)
    private val world = mockk<World>(relaxed = true)
    private val block = mockk<Block>(relaxed = true)
    private val riders = mutableListOf<Entity>()
    private val settings = definition.levels[level - 1].let {
        MountRuntimeSettings(it.speed, 1.1, it.handlingMultiplier, it.sprintMultiplier, it.scaleMultiplier, null, false, emptyList())
    }
    private val tickMethod = MountSessionController::class.java.getDeclaredMethod("tick", Long::class.javaPrimitiveType).apply {
        isAccessible = true
    }

    init {
        val plugin = mockk<JavaPlugin>(relaxed = true)
        val server = mockk<Server>(relaxed = true)
        val controls = mockk<Input>()
        every { controls.isForward } answers { input.forward }
        every { controls.isBackward } answers { input.backward }
        every { controls.isLeft } answers { input.left }
        every { controls.isRight } answers { input.right }
        every { controls.isJump } answers { input.jump }
        every { controls.isSneak } answers { input.sneak }
        every { controls.isSprint } answers { input.sprint }
        every { plugin.name } returns "AquaticTest"
        every { plugin.logger } returns Logger.getLogger("AquaticTest")
        every { plugin.server } returns server
        every { plugin.dataFolder } returns Files.createTempDirectory("arc-mounts-aquatic-plugin-").toFile()
        every { world.name } returns "aquatic_test"
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.worldBorder.isInside(any()) } returns true
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns block
        every { world.getBlockAt(any<Location>()) } returns block
        every { block.type } answers { materialOverride ?: if (position.y < 64.0 && waterOverride != false) Material.WATER else Material.AIR }
        every { block.isLiquid } answers { block.type == Material.WATER }
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.name } returns "AquaticPilot"
        every { player.isOnline } returns true
        every { player.world } returns world
        every { player.location } answers { Location(world, position.x, position.y, position.z, yaw, 0f) }
        every { player.isInWater } answers { inWater }
        every { player.currentInput } returns controls
        every { player.vehicle } answers { if (riders.isEmpty()) null else body }
        every { body.uniqueId } returns UUID.randomUUID()
        every { body.type } returns EntityType.valueOf(definition.entityType)
        every { body.world } returns world
        every { body.location } answers { Location(world, position.x, position.y, position.z) }
        every { body.boundingBox } answers { BoundingBox(position.x - 0.3, position.y, position.z - 0.3, position.x + 0.3, position.y + 0.6, position.z + 0.3) }
        every { body.isInWater } answers { inWater }
        every { body.isOnGround } answers { position.y <= floorY }
        every { body.isInWorld } returns true
        every { body.isValid } returns true
        every { body.persistentDataContainer } returns mockk<PersistentDataContainer>(relaxed = true)
        every { body.getAttribute(any<Attribute>()) } returns mockk<AttributeInstance>(relaxed = true)
        every { body.passengers } answers { riders.toList() }
        every { body.addPassenger(any()) } answers { riders.add(firstArg()); true }
        every { body.velocity } answers { velocity.clone() }
        every { body.velocity = any() } answers { velocity = firstArg<Vector>().clone() }
        every { server.getPlayer(player.uniqueId) } returns player
        every { server.getEntity(body.uniqueId) } returns body
        every { world.spawnEntity(any<Location>(), any<EntityType>(), any<CreatureSpawnEvent.SpawnReason>(), any<Consumer<Entity>>()) } answers {
            arg<Consumer<Entity>>(3).accept(body)
            body
        }
        controller = MountSessionController(
            plugin, mockk<TaskScheduler>(relaxed = true), { config }, setOf(definition.id), { _, _, _ -> },
            recordActivity = { _, event, _, _, details -> if (event == "mount.ride_ended") removalReasons += checkNotNull(details["reason"]) },
        )
    }

    fun summon(): MountSpawnResult = controller.spawn(player, definition, settings, 60_000)

    fun tick(elapsedMillis: Long = 50) {
        now += elapsedMillis
        tickMethod.invoke(controller, now)
    }

    fun breach() {
        repeat(30) {
            tick()
            if (!inWater && position.y >= 64.0) return
            physicsStep()
        }
        error("${definition.id} failed to breach the surface")
    }

    fun physicsStep() {
        cachedInWater = position.y < 64.0
        position.add(velocity)
        position.z = minOf(position.z, wallZ)
        position.y = position.y.coerceIn(floorY, ceilingY)
        velocity = when {
            definition.entityType in setOf("SQUID", "GLOW_SQUID") && !inWater -> Vector(0.0, velocity.y * 0.98, 0.0)
            inWater -> velocity.multiply(0.9)
            else -> Vector(velocity.x * 0.91, velocity.y * 0.98, velocity.z * 0.91)
        }
    }
}
