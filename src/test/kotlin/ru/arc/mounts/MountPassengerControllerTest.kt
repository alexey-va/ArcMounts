package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.entity.Camel
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffectType
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.time.Duration
import java.util.UUID
import java.util.function.Consumer

class MountPassengerControllerTest : StringSpec({
    "existing passenger carriers adopt changed geometry without remounting" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 2, customCarrier = true)
            val first = fixture.player()
            val second = fixture.player()
            fixture.click(first)
            fixture.click(second)
            every { fixture.config.passengerCarrierScale } returns 1.0
            every { fixture.config.passengerCarrierYawOffset } returns 135.0
            fixture.controller.reconcileRide(fixture.mount.uniqueId, false) shouldBe true
            verify { fixture.carriers.first().setRotation(165.0f, 0.0f) }
            verify { fixture.carriers.last().setRotation(-105.0f, 0.0f) }
            fixture.seatRiders shouldBe listOf(listOf(first), listOf(second))
            verify(exactly = 1) { fixture.carriers.first().addPassenger(first) }
            verify(exactly = 1) { fixture.carriers.last().addPassenger(second) }
        }
    }

    "a camel carries one guest and a full ride refuses another without changing the driver" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 1)
            val guest = fixture.player()
            val extra = fixture.player()

            fixture.click(guest)
            fixture.click(extra)

            fixture.riders shouldBe listOf(fixture.driver, guest)
            fixture.feedback.last() shouldBe "passenger-full"
            verify(exactly = 0) { fixture.mount.addPassenger(extra) }
        }
    }

    "a flying group admits two passengers and blocks native boarding outside its controller" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 2, flying = true)
            val first = fixture.player()
            val second = fixture.player()
            fixture.click(first)
            fixture.click(second)
            fixture.riders shouldBe listOf(fixture.driver, first, second)

            val unsolicited = EntityMountEvent(fixture.player(), fixture.mount)
            fixture.controller.onMount(unsolicited)
            unsolicited.isCancelled shouldBe true
        }
    }

    "visible native flight body shares the driver graph and forwards guest boarding" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 2, flying = true, visualBody = true)
            val first = fixture.player()
            val second = fixture.player()
            val extra = fixture.player()
            val body = fixture.body!!
            listOf(first, second, extra).forEach { guest ->
                fixture.controller.onInteract(PlayerInteractEntityEvent(guest, body, EquipmentSlot.HAND))
            }
            fixture.riders shouldBe listOf(fixture.driver, body, first, second)
            fixture.feedback.last() shouldBe "passenger-full"
            val unsolicited = EntityMountEvent(extra, body)
            fixture.controller.onMount(unsolicited)
            unsolicited.isCancelled shouldBe true
            val detach = EntityDismountEvent(body, fixture.mount)
            fixture.controller.onCarrierDismount(detach)
            detach.isCancelled shouldBe true
            fixture.controller.closeRide(fixture.mount.uniqueId)
            fixture.vehicles[first.uniqueId] shouldBe null
            fixture.vehicles[second.uniqueId] shouldBe null
            val staleClick = PlayerInteractEntityEvent(extra, body, EquipmentSlot.HAND)
            fixture.controller.onInteract(staleClick)
            staleClick.isCancelled shouldBe false
            verify(exactly = 0) { body.teleport(any<Location>()) }
            verify(exactly = 0) { body.velocity = any() }
        }
    }

    "custom seats repair the complete driver view once after boarding without moving entities" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 2, customCarrier = true)
            val guest = fixture.player()
            fixture.click(guest)
            fixture.synchronizedGraphs shouldBe emptyList()

            fixture.runDelayed()

            fixture.synchronizedGraphs shouldBe listOf(fixture.driver to (fixture.carriers + fixture.mount))
            fixture.seatRiders.first() shouldBe listOf(guest)
            fixture.riders shouldBe listOf(fixture.driver) + fixture.carriers
            fixture.controller.reconcileRide(fixture.mount.uniqueId, fireProtected = false)
            fixture.runDelayed()
            fixture.synchronizedGraphs.size shouldBe 1
        }
    }

    "native seats do not need graph repair and closed custom rides cannot replay stale links" {
        MockBukkitTestRuntime.open().use { paper ->
            val native = PassengerFixture(paper, seats = 1)
            native.click(native.player())
            native.runDelayed()
            native.synchronizedGraphs shouldBe emptyList()

            val custom = PassengerFixture(paper, seats = 1, customCarrier = true)
            custom.click(custom.player())
            custom.controller.closeRide(custom.mount.uniqueId)
            custom.runDelayed()
            custom.synchronizedGraphs shouldBe emptyList()
        }
    }

    "ordinary mounts reject passengers and native vanilla boarding" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 0)
            val guest = fixture.player()
            fixture.click(guest)
            fixture.feedback.last() shouldBe "passenger-not-supported"
            fixture.riders shouldBe listOf(fixture.driver)
            val native = EntityMountEvent(guest, fixture.mount)
            fixture.controller.onMount(native)
            native.isCancelled shouldBe true
        }
    }

    "offhand duplicate and protected clicks never board and an existing vehicle is preserved" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 1)
            val guest = fixture.player()
            fixture.click(guest, EquipmentSlot.OFF_HAND)
            val cancelled = PlayerInteractEntityEvent(guest, fixture.mount, EquipmentSlot.HAND).also { it.isCancelled = true }
            fixture.controller.onInteract(cancelled)
            fixture.riders shouldBe listOf(fixture.driver)
            fixture.vehicles[guest.uniqueId] = mockk<Entity>(relaxed = true)
            fixture.click(guest)
            fixture.feedback.last() shouldBe "passenger-already-riding"
            verify(exactly = 0) { fixture.mount.addPassenger(guest) }
        }
    }

    "a passenger cannot take over a mount whose driver has left" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 1)
            fixture.riders.clear()
            fixture.click(fixture.player())
            fixture.feedback.last() shouldBe "passenger-driver-required"
            fixture.riders shouldBe emptyList()
        }
    }

    "failed native boarding does not consume a passenger place" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 1)
            fixture.rejectBoarding = true
            fixture.click(fixture.player())
            fixture.feedback.last() shouldBe "passenger-board-failed"
            fixture.rejectBoarding = false
            val guest = fixture.player()
            fixture.click(guest)
            fixture.riders shouldBe listOf(fixture.driver, guest)
        }
    }

    "passenger dismount frees only their seat and flying passengers receive fall protection" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 2, flying = true)
            val guest = fixture.player()
            val remaining = fixture.player()
            fixture.click(guest)
            fixture.click(remaining)
            val dismount = EntityDismountEvent(guest, fixture.mount)
            fixture.controller.onDismount(dismount)
            dismount.isCancelled shouldBe false
            fixture.mount.removePassenger(guest)
            fixture.runDelayed()

            fixture.riders shouldBe listOf(fixture.driver, remaining)
            verify(exactly = 1) { guest.addPotionEffect(match { it.type == PotionEffectType.SLOW_FALLING && it.duration == 160 }) }
            fixture.click(fixture.player())
            fixture.riders.size shouldBe 3
        }
    }

    "stale dismount callback cannot clear a passenger who already boarded again" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 1)
            val guest = fixture.player()
            fixture.click(guest)
            fixture.controller.onDismount(EntityDismountEvent(guest, fixture.mount))
            fixture.mount.removePassenger(guest)
            fixture.controller.reconcileRide(fixture.mount.uniqueId, fireProtected = false)
            fixture.click(guest)
            fixture.runDelayed()
            fixture.riders shouldBe listOf(fixture.driver, guest)
            fixture.controller.closeRide(fixture.mount.uniqueId)
            fixture.riders shouldBe listOf(fixture.driver)
        }
    }

    "closing a flight releases all guests exactly once and leaves no usable seat" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 2, flying = true)
            val first = fixture.player()
            val second = fixture.player()
            fixture.click(first)
            fixture.click(second)
            fixture.controller.closeRide(fixture.mount.uniqueId)
            fixture.controller.closeRide(fixture.mount.uniqueId)

            fixture.riders shouldBe listOf(fixture.driver)
            verify(exactly = 1) { first.addPotionEffect(match { it.type == PotionEffectType.SLOW_FALLING }) }
            verify(exactly = 1) { second.addPotionEffect(match { it.type == PotionEffectType.SLOW_FALLING }) }
            val afterClose = fixture.click(fixture.player())
            afterClose.isCancelled shouldBe false
        }
    }

    "closing an old ride cannot remove a guest who boarded another ride before reconciliation" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 1)
            val guest = fixture.player()
            fixture.click(guest)
            fixture.controller.onDismount(EntityDismountEvent(guest, fixture.mount))
            fixture.mount.removePassenger(guest)

            val otherMount = mockk<LivingEntity>(relaxed = true)
            val otherDriver = fixture.player()
            val otherRiders = mutableListOf<Entity>(otherDriver)
            every { otherMount.uniqueId } returns UUID.randomUUID()
            every { otherMount.isValid } returns true
            every { otherMount.passengers } answers { otherRiders.toList() }
            every { otherMount.addPassenger(any()) } answers {
                val player = firstArg<Entity>()
                val event = EntityMountEvent(player, otherMount)
                fixture.controller.onMount(event)
                if (event.isCancelled) false else {
                    otherRiders.add(player)
                    fixture.vehicles[player.uniqueId] = otherMount
                    true
                }
            }
            every { otherMount.removePassenger(any()) } answers {
                val player = firstArg<Entity>()
                fixture.vehicles.remove(player.uniqueId)
                otherRiders.remove(player)
            }
            fixture.controller.openRide(otherMount, otherDriver, testMount().copy(entityType = "CAMEL", passengerSeats = 1), false)
            fixture.controller.onInteract(PlayerInteractEntityEvent(guest, otherMount, EquipmentSlot.HAND))
            fixture.controller.reconcileRide(fixture.mount.uniqueId, false)
            fixture.controller.closeRide(fixture.mount.uniqueId)
            fixture.runDelayed()

            otherRiders shouldBe listOf(otherDriver, guest)
            fixture.controller.closeRide(otherMount.uniqueId)
            otherRiders shouldBe listOf(otherDriver)
        }
    }

    "heavy damage ejects only the passenger while suffocation and shared fire protection are cancelled" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 1)
            val guest = fixture.player()
            fixture.click(guest)
            val suffocation = mockk<EntityDamageEvent>(relaxed = true)
            every { suffocation.entity } returns guest
            every { suffocation.cause } returns EntityDamageEvent.DamageCause.SUFFOCATION
            fixture.controller.onDamage(suffocation)
            verify { suffocation.isCancelled = true }

            fixture.controller.reconcileRide(fixture.mount.uniqueId, fireProtected = true)
            val fire = mockk<EntityDamageEvent>(relaxed = true)
            every { fire.entity } returns guest
            every { fire.cause } returns EntityDamageEvent.DamageCause.LAVA
            fixture.controller.onDamage(fire)
            verify { fire.isCancelled = true }

            val hit = mockk<EntityDamageEvent>(relaxed = true)
            every { hit.entity } returns guest
            every { hit.finalDamage } returns 6.0
            fixture.controller.onKnockoff(hit)
            fixture.runDelayed()
            fixture.riders shouldBe listOf(fixture.driver)
            fixture.click(fixture.player())
            fixture.riders.size shouldBe 2
        }
    }

    "quit and teleport release the guest without ending the driver's ride" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 2, flying = true)
            val leaving = fixture.player()
            val teleporting = fixture.player()
            fixture.click(leaving)
            fixture.click(teleporting)
            val quit = mockk<PlayerQuitEvent> { every { player } returns leaving }
            fixture.controller.onQuit(quit)
            fixture.riders shouldBe listOf(fixture.driver, teleporting)
            val teleport = mockk<PlayerTeleportEvent> { every { player } returns teleporting }
            fixture.controller.onTeleport(teleport)
            fixture.runDelayed()
            fixture.riders shouldBe listOf(fixture.driver)
            fixture.click(fixture.player())
            fixture.riders.size shouldBe 2
        }
    }

    "a single custom seat stays behind the driver through turns without remounting" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 1, customCarrier = true)
            val guest = fixture.player()
            fixture.click(guest)
            val carrier = fixture.carriers.single()
            verify { carrier.setRotation(210.0f, 0.0f) }

            every { fixture.mount.yaw } returns -90.0f
            fixture.controller.reconcileRide(fixture.mount.uniqueId, fireProtected = false) shouldBe true

            verify { carrier.setRotation(90.0f, 0.0f) }
            fixture.vehicles[guest.uniqueId] shouldBe carrier
            verify(exactly = 1) { carrier.addPassenger(guest) }
            verify(exactly = 0) { carrier.teleport(any<Location>()) }
            verify(exactly = 0) { carrier.removePassenger(guest) }
        }
    }

    "large mounts carry their driver and two guests in one native entity graph without teleports" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 2, customCarrier = true)
            val first = fixture.player()
            val second = fixture.player()
            fixture.click(first)
            fixture.click(second)

            fixture.riders shouldBe listOf(fixture.driver) + fixture.carriers
            fixture.seatRiders[0] shouldBe listOf(first)
            fixture.seatRiders[1] shouldBe listOf(second)
            fixture.vehicles[first.uniqueId] shouldBe fixture.carriers.first()
            fixture.vehicles[fixture.carriers.first().uniqueId] shouldBe fixture.mount
            fixture.click(fixture.player())
            fixture.feedback.last() shouldBe "passenger-full"
            fixture.controller.reconcileRide(fixture.mount.uniqueId, fireProtected = false) shouldBe true
            verify { fixture.carriers.first().isInvisible = true }
            verify { fixture.carriers.first().setPose(Pose.SITTING, true) }
            fixture.carriers.forEach { carrier ->
                verifyOrder {
                    fixture.mount.addPassenger(carrier)
                    carrier.setPose(Pose.SITTING, true)
                }
            }
            verify(exactly = 0) { fixture.carriers.first().isSitting = true }
            verify { fixture.carriers.first().setRotation(120.0f, 0.0f) }
            verify { fixture.carriers.last().setRotation(-60.0f, 0.0f) }
            verify(exactly = 0) { fixture.carriers.first().teleport(any<Location>()) }
            verify(exactly = 0) { first.teleport(any<Location>()) }

            fixture.controller.closeRide(fixture.mount.uniqueId)
            fixture.riders shouldBe listOf(fixture.driver)
            fixture.seatRiders.forEach { it shouldBe emptyList() }
            verify(exactly = 1) { fixture.carriers.first().remove() }
        }
    }

    "one custom passenger leaving never relocates or remounts the other passenger" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 2, customCarrier = true)
            val leaving = fixture.player()
            val staying = fixture.player()
            fixture.click(leaving)
            fixture.click(staying)
            fixture.controller.onDismount(EntityDismountEvent(leaving, fixture.carriers.first()))
            fixture.carriers.first().removePassenger(leaving)
            fixture.runDelayed()
            fixture.controller.reconcileRide(fixture.mount.uniqueId, false)
            val arriving = fixture.player()
            fixture.click(arriving)

            fixture.seatRiders[0] shouldBe listOf(arriving)
            fixture.seatRiders[1] shouldBe listOf(staying)
            verify(exactly = 1) { fixture.carriers.last().addPassenger(staying) }
            verify(exactly = 0) { fixture.carriers.last().removePassenger(staying) }
            verify(exactly = 0) { staying.teleport(any<Location>()) }
        }
    }

    "custom seats wait for their native sitting animation before accepting guests" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 1, customCarrier = true, prepareSeats = false)
            val guest = fixture.player()
            fixture.click(guest)
            fixture.feedback.last() shouldBe "passenger-preparing"
            fixture.seatRiders.first() shouldBe emptyList()
            fixture.runDelayed()
            fixture.click(guest)
            fixture.seatRiders.first() shouldBe listOf(guest)
        }
    }

    "losing a seat invalidates the ride and cleanup removes its passengers" {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = PassengerFixture(paper, seats = 2, customCarrier = true)
            val guest = fixture.player()
            fixture.click(guest)
            fixture.vehicles.remove(fixture.carriers.first().uniqueId)
            fixture.controller.reconcileRide(fixture.mount.uniqueId, fireProtected = false) shouldBe false
            fixture.controller.closeRide(fixture.mount.uniqueId)
            fixture.vehicles[guest.uniqueId] shouldBe null
            verify(exactly = 1) { fixture.carriers.first().remove() }
        }
    }
})

/** Records both sides of native riding; these tests do not simulate client physics or seat geometry. */
private class PassengerFixture(
    paper: MockBukkitTestRuntime,
    seats: Int,
    flying: Boolean = false,
    customCarrier: Boolean = false,
    prepareSeats: Boolean = true,
    visualBody: Boolean = false,
) {
    val riders = mutableListOf<Entity>()
    val vehicles = hashMapOf<UUID, Entity>()
    val feedback = mutableListOf<String>()
    val delayed = mutableListOf<Runnable>()
    val synchronizedGraphs = mutableListOf<Pair<Player, List<LivingEntity>>>()
    var rejectBoarding = false
    val mount = mockk<LivingEntity>(relaxed = true)
    val body = if (visualBody) mockk<LivingEntity>(relaxed = true) else null
    val carriers = List(if (customCarrier) seats else 0) { mockk<Camel>(relaxed = true) }
    val seatRiders = List(carriers.size) { mutableListOf<Entity>() }
    private val scheduler = mockk<TaskScheduler>()
    val config = mockk<MountModuleConfig>()
    val controller: MountPassengerController
    val driver = player()

    init {
        every { mount.uniqueId } returns UUID.randomUUID()
        every { mount.isValid } returns true
        every { mount.passengers } answers { riders.toList() }
        every { mount.yaw } returns 30.0f
        every { scheduler.runLater(any(), any()) } answers {
            delayed.add(secondArg())
            mockk<ScheduledTask>(relaxed = true)
        }
        every { config.message(any(), any()) } answers { feedback.add(firstArg()); secondArg<String>() }
        every { config.postFlightSlowFalling } returns Duration.ofSeconds(8)
        every { config.riderKnockoffDamage } returns 6.0
        every { config.passengerCarrierScale } returns 0.8
        every { config.passengerCarrierYawOffset } returns 90.0
        controller = MountPassengerController(
            paper.createSimplePlugin("PassengerTest"), scheduler, { config },
            synchronizePassengers = { viewer, vehicles -> synchronizedGraphs.add(viewer to vehicles.toList()) },
        )
        if (customCarrier) {
            val world = mockk<World>()
            every { mount.world } returns world
            every { mount.location } returns Location(world, 0.0, 64.0, 0.0)
            val scale = mockk<AttributeInstance>(relaxed = true)
            every { scale.value } returns 1.0
            every { mount.getAttribute(Attribute.SCALE) } returns scale
            carriers.forEachIndexed { index, carrier ->
                every { carrier.uniqueId } returns UUID.randomUUID()
                every { carrier.isValid } returns true
                every { carrier.isInWorld } returns true
                every { carrier.type } returns EntityType.CAMEL
                every { carrier.vehicle } answers { vehicles[carrier.uniqueId] }
                every { carrier.passengers } answers { seatRiders[index].toList() }
                every { carrier.remove() } answers {
                    seatRiders[index].toList().forEach { carrier.removePassenger(it) }
                    mount.removePassenger(carrier)
                    Unit
                }
                every { carrier.addPassenger(any()) } answers {
                    val player = firstArg<Entity>()
                    val event = EntityMountEvent(player, carrier)
                    controller.onMount(event)
                    if (event.isCancelled || rejectBoarding) false else {
                        seatRiders[index].add(player)
                        vehicles[player.uniqueId] = carrier
                        true
                    }
                }
                every { carrier.removePassenger(any()) } answers {
                    val player = firstArg<Entity>()
                    vehicles.remove(player.uniqueId)
                    seatRiders[index].remove(player)
                }
            }
            var spawned = 0
            every {
                world.spawn(any<Location>(), Camel::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM, false, any<Consumer<Camel>>())
            } answers {
                carriers[spawned++].also { arg<Consumer<Camel>>(4).accept(it) }
            }
        }
        every { mount.addPassenger(any()) } answers {
            val player = firstArg<Entity>()
            val event = EntityMountEvent(player, mount)
            controller.onMount(event)
            if (event.isCancelled || rejectBoarding) false else {
                riders.add(player)
                vehicles[player.uniqueId] = mount
                true
            }
        }
        every { mount.removePassenger(any()) } answers {
            val player = firstArg<Entity>()
            vehicles.remove(player.uniqueId)
            riders.remove(player)
        }
        riders.add(driver)
        vehicles[driver.uniqueId] = mount
        body?.let {
            every { it.uniqueId } returns UUID.randomUUID()
            riders.add(it)
            vehicles[it.uniqueId] = mount
        }
        controller.openRide(
            mount, driver,
            testMount().copy(
                entityType = if (customCarrier) "RAVAGER" else if (flying) "HAPPY_GHAST" else "CAMEL",
                passengerSeats = seats,
                movement = if (flying) MountMovement.FLYING else MountMovement.WALKING,
            ),
            fireProtected = false,
            visualBody = body,
        )
        if (prepareSeats) runDelayed()
    }

    fun player(): Player = mockk<Player>(relaxed = true).also { player ->
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.vehicle } answers { vehicles[id] }
    }

    fun click(player: Player, hand: EquipmentSlot = EquipmentSlot.HAND): PlayerInteractEntityEvent =
        PlayerInteractEntityEvent(player, mount, hand).also(controller::onInteract)

    fun runDelayed() {
        val tasks = delayed.toList()
        delayed.clear()
        tasks.forEach(Runnable::run)
    }
}
