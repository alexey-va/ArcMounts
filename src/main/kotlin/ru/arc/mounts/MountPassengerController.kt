package ru.arc.mounts

import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Camel
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.util.TextUtils
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.arc.core.TaskScheduler
import ru.arc.core.ScheduledTask
import java.util.UUID
import java.util.logging.Level
import java.nio.file.Path

/** Owns guests and their native riding graph; the driver's session owns the ride. */
internal class MountPassengerController(
    private val plugin: JavaPlugin,
    private val scheduler: TaskScheduler,
    private val configProvider: () -> MountModuleConfig,
    private val synchronizePassengers: (Player, List<LivingEntity>) -> Unit = { _, _ -> },
    private val dataPath: Path = plugin.dataFolder.toPath(),
) : Listener {
    private class Ride(
        val entity: LivingEntity,
        val driver: Player,
        val definition: MountDefinition,
        var fireProtected: Boolean,
        val visualBody: LivingEntity? = null,
        val passengers: MutableSet<UUID> = linkedSetOf(),
        var boardingPlayerId: UUID? = null,
        var boardingVehicleId: UUID? = null,
        val seats: MutableList<Camel> = mutableListOf(),
        var attachingCarrier: Camel? = null,
        var seatsReady: Boolean = true,
        var prepareSeats: ScheduledTask? = null,
    ) {
        val driverId: UUID get() = driver.uniqueId
    }

    private class Passenger(val player: Player, val ride: Ride, val vehicle: LivingEntity)

    private val rides = hashMapOf<UUID, Ride>()
    private val passengers = hashMapOf<UUID, Passenger>()
    private val carriers = hashMapOf<UUID, Ride>()
    private val visualBodies = hashMapOf<UUID, Ride>()
    private val pendingCarrierSpawns = hashMapOf<UUID, Ride>()
    private var liveSettings: MountPassengerSettings? = null
    private var carrierPoses: MountCarrierPosePackets? = null
    private val ownerKey = NamespacedKey(plugin, "mount_owner")
    private val mountIdKey = NamespacedKey(plugin, "mount_id")
    private val spawnTokenKey = NamespacedKey(plugin, "mount_spawn_token")
    private val seatKey = NamespacedKey(plugin, "mount_passenger_seat")

    fun start() {
        val config = configProvider()
        liveSettings = MountPassengerSettings(
            dataPath,
            MountPassengerSeatSettings(config.passengerCarrierScale, 180.0, config.passengerCarrierYawOffset),
            plugin.logger,
        ).also { it.start(scheduler) }
        if (carrierPoses == null && plugin.server.pluginManager.isPluginEnabled("packetevents")) {
            carrierPoses = MountCarrierPosePackets().also { it.start() }
        }
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun shutdown() {
        liveSettings?.close()
        liveSettings = null
        rides.keys.toList().forEach(::closeRide)
        carrierPoses?.close()
        carrierPoses = null
        HandlerList.unregisterAll(this)
    }

    fun openRide(entity: LivingEntity, driver: Player, definition: MountDefinition, fireProtected: Boolean, visualBody: LivingEntity? = null): Boolean {
        val ride = Ride(entity, driver, definition, fireProtected, visualBody)
        rides[entity.uniqueId] = ride
        visualBody?.let { visualBodies[it.uniqueId] = ride }
        if (definition.passengerSeats == 0 || definition.entityType in NATIVE_SEAT_TYPES) return true
        return try {
            ride.seatsReady = false
            repeat(definition.passengerSeats) { spawnCarrier(ride, it) }
            // Wait out the native 52-tick pose transition before exposing the passenger anchor.
            ride.prepareSeats = scheduler.runLater(CARRIER_SETTLE_TICKS, Runnable {
                if (rides[entity.uniqueId] === ride) ride.seatsReady = true
                ride.prepareSeats = null
            })
            true
        } catch (failure: Exception) {
            closeRide(entity.uniqueId)
            plugin.logger.log(Level.WARNING, "Mount seat creation failed: mount=${definition.id} entity=${entity.uniqueId}", failure)
            false
        }
    }

    fun closeRide(entityId: UUID) {
        val ride = rides.remove(entityId) ?: return
        ride.visualBody?.let { visualBodies.remove(it.uniqueId) }
        ride.prepareSeats?.cancel()
        ride.passengers.toList().forEach { id ->
            passengers[id]?.let { passenger ->
                if (passenger.ride !== ride) return@let
                forget(passenger, protectFall = true)
                if (passenger.player.vehicle?.uniqueId == passenger.vehicle.uniqueId) passenger.vehicle.removePassenger(passenger.player)
            }
        }
        ride.seats.forEach { carrier ->
            carriers.remove(carrier.uniqueId)
            carrierPoses?.unregister(carrier.entityId)
            carrier.remove()
        }
    }

    fun reconcileRide(entityId: UUID, fireProtected: Boolean): Boolean {
        val ride = rides[entityId] ?: return false
        ride.fireProtected = fireProtected
        ride.seats.forEachIndexed { index, carrier ->
            if (!carrier.isValid || carrier.vehicle?.uniqueId != entityId) return false
            updateCarrier(ride, carrier, index)
        }
        ride.passengers.toList().forEach { id ->
            val passenger = passengers[id] ?: return@forEach
            if (passenger.ride !== ride) {
                ride.passengers.remove(id)
                return@forEach
            }
            val player = passenger.player
            if (!player.isOnline || player.isDead || player.vehicle?.uniqueId != passenger.vehicle.uniqueId || !ride.entity.isValid) {
                forget(passenger, protectFall = true)
            } else {
                extinguishRiderFire(player, fireProtected)
            }
        }
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) = interact(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractAt(event: PlayerInteractAtEntityEvent) = interact(event)

    private fun interact(event: PlayerInteractEntityEvent) {
        val ride = rides[event.rightClicked.uniqueId] ?: carriers[event.rightClicked.uniqueId] ?: visualBodies[event.rightClicked.uniqueId] ?: return
        if (event.isCancelled) return
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (player.uniqueId == ride.driverId) return
        passengers[player.uniqueId]?.let { previous ->
            if (player.vehicle?.uniqueId == previous.vehicle.uniqueId && previous.ride === ride) return
            if (player.vehicle?.uniqueId != previous.vehicle.uniqueId) forget(previous, protectFall = true)
        }
        when {
            ride.definition.passengerSeats == 0 ->
                tell(player, "passenger-not-supported", "<gray>У этого маунта нет пассажирских мест.")
            !hasDriver(ride) ->
                tell(player, "passenger-driver-required", "<yellow>Сначала дождитесь всадника маунта.")
            player.vehicle != null ->
                tell(player, "passenger-already-riding", "<yellow>Сначала спешитесь с текущего транспорта.")
            !ride.seatsReady ->
                tell(player, "passenger-preparing", "<yellow>Сиденья готовятся. Попробуйте через пару секунд.")
            isFull(ride) ->
                tell(player, "passenger-full", "<yellow>Все пассажирские места заняты.")
            else -> board(player, ride)
        }
    }

    private fun board(player: Player, ride: Ride) {
        val vehicle = if (ride.seats.isEmpty()) ride.entity else ride.seats.firstOrNull { it.passengers.isEmpty() } ?: return
        ride.boardingPlayerId = player.uniqueId
        ride.boardingVehicleId = vehicle.uniqueId
        val boarded = try {
            vehicle.addPassenger(player)
        } catch (failure: Exception) {
            plugin.logger.log(
                Level.WARNING,
                "Mount passenger boarding failed: mount=${ride.definition.id} entity=${ride.entity.uniqueId} player=${player.uniqueId}",
                failure,
            )
            false
        } finally {
            ride.boardingPlayerId = null
            ride.boardingVehicleId = null
        }
        if (!boarded) {
            tell(player, "passenger-board-failed", "<yellow>Не удалось сесть. Попробуйте ещё раз рядом с маунтом.")
            return
        }
        if (rides[ride.entity.uniqueId] !== ride || !hasDriver(ride)) {
            vehicle.removePassenger(player)
            return
        }
        passengers[player.uniqueId]?.let { forget(it, protectFall = true) }
        val passenger = Passenger(player, ride, vehicle)
        passengers[player.uniqueId] = passenger
        ride.passengers.add(player.uniqueId)
        if (ride.seats.isNotEmpty()) {
            // Passenger entities do not receive normal position updates: the client
            // must know both links to move the guest with the mount.
            scheduler.runLater(1L, Runnable {
                if (rides[ride.entity.uniqueId] !== ride || passengers[player.uniqueId] !== passenger ||
                    !hasDriver(ride) || !ride.driver.isOnline || player.vehicle?.uniqueId != vehicle.uniqueId
                ) return@Runnable
                synchronizePassengers(ride.driver, ride.seats + ride.entity)
            })
        }
        extinguishRiderFire(player, ride.fireProtected)
        tell(player, "passenger-boarded", "<green>Вы заняли пассажирское место. <gray>Shift — спешиться; управляет всадник.")
    }

    /** Native mounting and other plugins must not bypass the configured capacity or replace the driver. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMount(event: EntityMountEvent) {
        val ride = rides[event.mount.uniqueId] ?: carriers[event.mount.uniqueId] ?: visualBodies[event.mount.uniqueId] ?: return
        if (event.entity === ride.attachingCarrier && event.mount.uniqueId == ride.entity.uniqueId) return
        if (
            event.entity !is Player || ride.boardingPlayerId != event.entity.uniqueId ||
            event.mount.uniqueId != ride.boardingVehicleId || !ride.seatsReady || !hasDriver(ride) || isFull(ride)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCarrierDismount(event: EntityDismountEvent) {
        val ride = carriers[event.entity.uniqueId] ?: visualBodies[event.entity.uniqueId] ?: return
        if (event.dismounted.uniqueId == ride.entity.uniqueId && event.isCancellable) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        val passenger = passengers[event.entity.uniqueId] ?: return
        if (event.dismounted.uniqueId != passenger.vehicle.uniqueId) return
        scheduler.runLater(1L, Runnable {
            if (passengers[passenger.player.uniqueId] === passenger &&
                passenger.player.vehicle?.uniqueId != passenger.vehicle.uniqueId
            ) {
                forget(passenger, protectFall = true)
            }
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDamage(event: EntityDamageEvent) {
        if (carriers.containsKey(event.entity.uniqueId)) {
            event.isCancelled = true
            return
        }
        val passenger = passengers[event.entity.uniqueId] ?: return
        if (shouldCancelMountDamage(MountDamageTarget.RIDER, event.cause, passenger.ride.fireProtected)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKnockoff(event: EntityDamageEvent) {
        val passenger = passengers[event.entity.uniqueId] ?: return
        if (!shouldKnockRiderOff(event.finalDamage, configProvider().riderKnockoffDamage)) return
        detachLater(passenger)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCombust(event: EntityCombustEvent) {
        if (carriers.containsKey(event.entity.uniqueId)) {
            event.isCancelled = true
            return
        }
        val passenger = passengers[event.entity.uniqueId] ?: return
        if (passenger.ride.fireProtected) {
            event.isCancelled = true
            extinguishRiderFire(passenger.player, fireProtected = true)
        }
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) = detach(event.player.uniqueId, protectFall = false, terminal = true)
    @EventHandler fun onDeath(event: PlayerDeathEvent) = detach(event.entity.uniqueId, protectFall = false, terminal = true)
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = detach(event.player.uniqueId, protectFall = true, terminal = true)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        passengers[event.player.uniqueId]?.let(::detachLater)
    }

    private fun detachLater(passenger: Passenger) {
        scheduler.runLater(1L, Runnable {
            if (passengers[passenger.player.uniqueId] === passenger) detach(passenger.player.uniqueId, protectFall = true)
        })
    }

    private fun detach(playerId: UUID, protectFall: Boolean, terminal: Boolean = false) {
        val passenger = passengers[playerId] ?: return
        val entity = passenger.vehicle
        if (passenger.player.vehicle?.uniqueId == entity.uniqueId && entity.isValid && !entity.removePassenger(passenger.player) && !terminal) return
        forget(passenger, protectFall)
    }

    private fun forget(passenger: Passenger, protectFall: Boolean) {
        if (!passengers.remove(passenger.player.uniqueId, passenger)) return
        passenger.ride.passengers.remove(passenger.player.uniqueId)
        val player = passenger.player
        if (protectFall && player.isOnline && !player.isDead && passenger.ride.definition.movement == MountMovement.FLYING) {
            val ticks = (configProvider().postFlightSlowFalling.toMillis() / 50L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            if (ticks > 0) player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, ticks, 0, false, false, true))
        }
    }

    private fun hasDriver(ride: Ride): Boolean =
        ride.entity.isValid && ride.entity.passengers.firstOrNull()?.let {
            it.uniqueId == ride.driverId && it is Player && it.isOnline && !it.isDead
        } == true

    private fun isFull(ride: Ride): Boolean = if (ride.seats.isEmpty()) {
        ride.entity.passengers.count { it.uniqueId != ride.driverId && it.uniqueId != ride.visualBody?.uniqueId } >=
            ride.definition.passengerSeats
    } else {
        ride.seats.all { it.passengers.isNotEmpty() }
    }

    private fun spawnCarrier(ride: Ride, index: Int) {
        val token = UUID.randomUUID()
        pendingCarrierSpawns[token] = ride
        val carrier = try {
            ride.entity.world.spawn(ride.entity.location, Camel::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM, false) { camel ->
                camel.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, ride.driverId.toString())
                camel.persistentDataContainer.set(mountIdKey, PersistentDataType.STRING, ride.definition.id)
                camel.persistentDataContainer.set(spawnTokenKey, PersistentDataType.STRING, token.toString())
                camel.persistentDataContainer.set(seatKey, PersistentDataType.STRING, ride.entity.uniqueId.toString())
                camel.isPersistent = false
                camel.isInvulnerable = true
                camel.isSilent = true
                camel.isInvisible = true
                camel.isCollidable = false
                camel.setGravity(false)
                camel.setAI(false)
                camel.setAdult()
                camel.ageLock = true
                camel.removeWhenFarAway = false
                updateCarrier(ride, camel, index)
            }
        } finally {
            pendingCarrierSpawns.remove(token)
        }
        ride.seats.add(carrier)
        check(carrier.isInWorld && carrier.isValid) { "Passenger carrier spawn was rejected" }
        carriers[carrier.uniqueId] = ride
        carrierPoses?.register(carrier.entityId)
        ride.attachingCarrier = carrier
        try {
            check(ride.entity.addPassenger(carrier)) { "Passenger carrier attachment was rejected" }
            // startRiding resets pose; fix it afterwards without Camel's automatic stand-up in water.
            carrier.setPose(Pose.SITTING, true)
        } finally {
            ride.attachingCarrier = null
        }
    }

    private fun updateCarrier(ride: Ride, carrier: Camel, index: Int) {
        val config = configProvider()
        val mountScale = ride.entity.getAttribute(Attribute.SCALE)?.value ?: 1.0
        val settings = liveSettings?.forMount(ride.definition.id)
            ?: MountPassengerSeatSettings(config.passengerCarrierScale, 180.0, config.passengerCarrierYawOffset)
        val scale = (mountScale * settings.scale).coerceIn(0.0625, 16.0)
        carrier.getAttribute(Attribute.SCALE)?.let { if (it.baseValue != scale) it.baseValue = scale }
        // A separate native vehicle per seat keeps the other guest fixed when someone dismounts.
        // The Camel's first seat lies forward of its origin. Turn a lone carrier
        // backwards so its guest sits behind the driver instead of beside them.
        val yawOffset = if (ride.definition.passengerSeats == 1) settings.singleYaw.toFloat()
            else settings.pairedYaw.toFloat() * if (index == 0) 1 else -1
        carrier.setRotation(ride.entity.yaw + yawOffset, 0.0f)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCarrierSpawn(event: CreatureSpawnEvent) {
        if (event.entity.type != EntityType.CAMEL) return
        val data = event.entity.persistentDataContainer
        val token = data.get(spawnTokenKey, PersistentDataType.STRING)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val ride = pendingCarrierSpawns[token] ?: return
        if (event.spawnReason == CreatureSpawnEvent.SpawnReason.CUSTOM &&
            data.get(ownerKey, PersistentDataType.STRING) == ride.driverId.toString() &&
            data.get(mountIdKey, PersistentDataType.STRING) == ride.definition.id &&
            data.get(seatKey, PersistentDataType.STRING) == ride.entity.uniqueId.toString()
        ) {
            event.isCancelled = false
        }
    }

    private fun tell(player: Player, key: String, fallback: String) {
        player.sendMessage(TextUtils.mm(configProvider().message(key, fallback), true))
    }

    private companion object {
        const val CARRIER_SETTLE_TICKS = 53L
        val NATIVE_SEAT_TYPES = setOf("CAMEL", "CAMEL_HUSK", "HAPPY_GHAST")
    }
}
