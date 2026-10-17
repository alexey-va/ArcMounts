package ru.arc.mounts

import io.papermc.paper.event.player.PlayerTrackEntityEvent
import org.bukkit.Input
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Bat
import org.bukkit.entity.Boss
import org.bukkit.entity.Camel
import org.bukkit.entity.Creeper
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Enemy
import org.bukkit.entity.Horse
import org.bukkit.entity.HappyGhast
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Vex
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ExplosionPrimeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import org.bukkit.util.BoundingBox
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtils
import java.util.UUID
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

enum class MountSpawnResult {
    SUCCESS,
    ALREADY_RIDING,
    ALREADY_IN_VEHICLE,
    WORLD_NOT_ALLOWED,
    WATER_REQUIRED,
    COOLDOWN,
    INVALID_ENTITY,
    SPAWN_FAILED,
}

enum class MountRemovalReason {
    DISMOUNTED,
    DOUBLE_SNEAK,
    EXPIRED,
    LEFT_WATER,
    HEIGHT_LIMIT,
    WORLD_BORDER,
    IDLE,
    TELEPORTED,
    CHANGED_WORLD,
    QUIT,
    DIED,
    KNOCKED_OFF,
    RELOAD,
    INVALID,
}

/** Immutable, read-only view of a mount that is currently confirmed in use. */
internal data class ActiveMountSnapshot(
    val mountId: String,
    val mountName: String,
    val rarity: String,
    val entityId: UUID,
)

private data class MountSession(
    val playerId: UUID,
    val entityId: UUID,
    val definition: MountDefinition,
    var settings: MountRuntimeSettings,
    val expiresAtMillis: Long,
    val sneakGesture: DoubleSneakGesture,
    val visualEntityId: UUID? = null,
    var input: MountInputState = MountInputState(),
    var allowDismount: Boolean = false,
    var stopping: Boolean = false,
    var lastHintAtMillis: Long = Long.MIN_VALUE,
    var lastActiveAtMillis: Long = System.currentTimeMillis(),
    var ticks: Long = 0L,
    var riderMountHidden: Boolean = false,
    var motionState: MountMotionState = MountMotionState(),
    var leftWaterAtMillis: Long? = null,
    var aquaticAirPosition: Vector? = null,
    var ramState: MountRamState = MountRamState(),
    var airborneMiningCompensated: Boolean = false,
    var previousBoundingBox: BoundingBox? = null,
    val trampleDamageAtMillis: MutableMap<UUID, Long> = hashMapOf(),
    val startedAtMillis: Long = System.currentTimeMillis(),
)

class MountSessionController internal constructor(
    private val plugin: JavaPlugin,
    private val scheduler: TaskScheduler,
    private val configProvider: () -> MountModuleConfig,
    private val allowedMountIds: Set<String>,
    private val message: (Player, String, String) -> Unit,
    private val onStateChanged: () -> Unit = {},
    private val setRiderMountHidden: (Player, LivingEntity, Boolean) -> Unit = { _, _, _ -> },
    private val synchronizePassengers: (Player, List<LivingEntity>) -> Unit = { _, _ -> },
    private val careBoostStatusProvider: (UUID, Long) -> MountCareBoostStatus = { _, _ ->
        MountCareBoostStatus(record = null, active = false, remainingMillis = 0L, nextClaimInMillis = 0L)
    },
    private val recordProductOutcome: (Player, String) -> Unit = { _, _ -> },
    private val recordActivity: (UUID, String, String?, String?, Map<String, String>) -> Unit = { _, _, _, _, _ -> },
    private val dataPath: Path = plugin.dataFolder.toPath(),
) : Listener {
    private val visualFlight = MountVisualFlight(plugin)
    private val sessionsByPlayer = ConcurrentHashMap<UUID, MountSession>()
    private val playerByEntity = ConcurrentHashMap<UUID, UUID>()
    private val ownerKey = NamespacedKey("arc", "mount_owner")
    private val mountIdKey = NamespacedKey("arc", "mount_id")
    private val spawnTokenKey = NamespacedKey("arc", "mount_spawn_token")
    private val airborneMiningModifier =
        AttributeModifier(
            NamespacedKey("arc", "mount_airborne_mining"),
            airborneMiningCompensationAmount(),
            AttributeModifier.Operation.MULTIPLY_SCALAR_1,
        )
    private val pendingSpawnTokens = ConcurrentHashMap.newKeySet<UUID>()
    private val lastSummonAt = ConcurrentHashMap<UUID, Long>()
    private val passengers = MountPassengerController(plugin, scheduler, configProvider, synchronizePassengers, dataPath)
    private var tickTask: ScheduledTask? = null

    fun start() {
        if (tickTask != null) return
        plugin.server.pluginManager.registerEvents(this, plugin)
        passengers.start()
        plugin.server.onlinePlayers.forEach(visualFlight::recover)
        tickTask = scheduler.runTimer(1L, 1L, Runnable(::tick))
    }

    fun stopAll(reason: MountRemovalReason = MountRemovalReason.RELOAD) {
        sessionsByPlayer.keys.toList().forEach { remove(it, reason) }
        sessionsByPlayer.clear()
        playerByEntity.clear()
        pendingSpawnTokens.clear()
    }

    fun shutdown() {
        stopAll()
        passengers.shutdown()
        tickTask?.cancel()
        tickTask = null
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    fun isRiding(playerId: UUID): Boolean = sessionsByPlayer.containsKey(playerId)

    fun activeSessionCount(): Int = sessionsByPlayer.size

    /**
     * Returns a snapshot only while the controller still owns the session and the rider
     * controls that vehicle or visual flight. Callers must not retain Bukkit objects from this
     * method; the returned value is immutable and contains no mutation capability.
     */
    internal fun activeMountSnapshot(player: Player): ActiveMountSnapshot? {
        val session = sessionsByPlayer[player.uniqueId] ?: return null
        if (session.playerId != player.uniqueId) return null
        val entity = plugin.server.getEntity(session.entityId) as? LivingEntity ?: return null
        if (!entity.isValid || !isMountControlledBy(player, entity, session.definition.control)) return null
        return ActiveMountSnapshot(
            mountId = session.definition.id,
            mountName = session.definition.displayName,
            rarity = session.definition.rarity.displayName,
            entityId = entity.uniqueId,
        )
    }

    fun reconcileSettings(
        playerId: UUID,
        expectedMountId: String,
        settings: MountRuntimeSettings,
    ): MountSessionUpdateResult {
        val session = sessionsByPlayer[playerId] ?: return MountSessionUpdateResult.NO_ACTIVE_SESSION
        if (session.definition.id != expectedMountId) return MountSessionUpdateResult.DIFFERENT_MOUNT
        val entity = plugin.server.getEntity(session.entityId) as? LivingEntity ?: return MountSessionUpdateResult.ENTITY_MISSING
        if (!entity.isValid) return MountSessionUpdateResult.ENTITY_MISSING
        val appearanceEntity = session.visualEntityId?.let {
            plugin.server.getEntity(it) as? LivingEntity ?: return MountSessionUpdateResult.ENTITY_MISSING
        } ?: entity
        require(settings.skin == null || session.definition.skin(settings.skin.id) == settings.skin) {
            "Active mount skin is not configured for ${session.definition.id}"
        }
        require(settings.abilityUpgrades.all { session.definition.ability(it.id) == it }) {
            "Active mount ability is not configured for ${session.definition.id}"
        }
        val previous = session.settings
        val previousAppearance = session.definition.effectiveAppearance(previous.scaleMultiplier, previous.skin)
        val replacementAppearance = session.definition.effectiveAppearance(settings.scaleMultiplier, settings.skin)
        val geometryMayGrow =
            mountAppearanceMayGrow(
                previousAppearance,
                replacementAppearance,
                MountAppearanceApplicator.supportsAge(appearanceEntity.type),
            )
        if (session.visualEntityId == null && session.definition.control != MountControl.PLAYER_FLIGHT &&
            !canApplyScale(entity, previousAppearance.scale, replacementAppearance.scale)) {
            return MountSessionUpdateResult.UNSAFE_APPEARANCE
        }
        val applied =
            runCatching {
                MountAppearanceApplicator.apply(appearanceEntity, replacementAppearance)
                if (session.visualEntityId == null && session.definition.control != MountControl.PLAYER_FLIGHT && geometryMayGrow &&
                    entity.wouldCollideUsing(entity.boundingBox.clone())) {
                    error("Replacement mount appearance collides with the world")
                }
                appearanceEntity.isGlowing = settings.glow
                if (session.definition.movement == MountMovement.WALKING) {
                    configureWalkingStepHeight(entity, settings.walkingStepHeight)
                }
            }.isSuccess
        if (!applied) {
            runCatching {
                MountAppearanceApplicator.apply(appearanceEntity, previousAppearance)
                appearanceEntity.isGlowing = previous.glow
                if (session.definition.movement == MountMovement.WALKING) {
                    configureWalkingStepHeight(entity, previous.walkingStepHeight)
                }
            }.onFailure {
                warn("Unable to roll back active mount appearance for {}: {}", session.definition.id, it.javaClass.simpleName)
            }
            return MountSessionUpdateResult.UNSAFE_APPEARANCE
        }
        session.settings = settings
        passengers.reconcileRide(session.entityId, session.hasRiderFireProtection())
        plugin.server.getPlayer(playerId)?.let {
            refreshAbilityEffects(it, session.definition.abilities.passives, settings.abilityUpgrades)
        }
        return MountSessionUpdateResult.APPLIED
    }

    fun spawn(
        player: Player,
        definition: MountDefinition,
        settings: MountRuntimeSettings,
        durationMillis: Long,
    ): MountSpawnResult {
        val config = configProvider()
        val now = System.currentTimeMillis()
        lastSummonAt.entries.removeIf { now - it.value >= config.summonCooldown.toMillis() }
        if (sessionsByPlayer.containsKey(player.uniqueId)) return MountSpawnResult.ALREADY_RIDING
        if (player.vehicle != null) return MountSpawnResult.ALREADY_IN_VEHICLE
        val lastSummon = lastSummonAt[player.uniqueId]
        if (lastSummon != null && now - lastSummon < config.summonCooldown.toMillis()) return MountSpawnResult.COOLDOWN
        if (config.allowedWorlds.isNotEmpty() && player.world.name.lowercase(java.util.Locale.ROOT) !in config.allowedWorlds) {
            return MountSpawnResult.WORLD_NOT_ALLOWED
        }
        if (
            definition.movement == MountMovement.SWIMMING &&
            !isAquaticEnvironment(
                player.isInWater || player.location.block.type == org.bukkit.Material.BUBBLE_COLUMN,
                player.location.block.isLiquid,
            )
        ) {
            return MountSpawnResult.WATER_REQUIRED
        }
        require(settings.skin == null || definition.skin(settings.skin.id) == settings.skin) {
            "Active mount skin is not configured for ${definition.id}"
        }
        require(settings.abilityUpgrades.all { definition.ability(it.id) == it }) {
            "Active mount ability is not configured for ${definition.id}"
        }

        val entityType = runCatching { org.bukkit.entity.EntityType.valueOf(definition.entityType) }.getOrNull()
            ?: return MountSpawnResult.INVALID_ENTITY
        if (!entityType.isAlive || !entityType.isSpawnable) return MountSpawnResult.INVALID_ENTITY
        val spawned = spawnOwnedEntity(player, definition, entityType) ?: return MountSpawnResult.SPAWN_FAILED
        var visualEntity: LivingEntity? = null
        return try {
            configureEntity(spawned, definition, settings, player)
            if (definition.control != MountControl.PLAYER_FLIGHT && !spawned.addPassenger(player)) {
                spawned.remove()
                return MountSpawnResult.SPAWN_FAILED
            }
            definition.visualEntityType?.let { type ->
                val visualType = org.bukkit.entity.EntityType.valueOf(type)
                val body = checkNotNull(spawnOwnedEntity(player, definition, visualType)) { "Unable to spawn mount visual body" }
                visualEntity = body
                configureEntity(body, definition, settings, player, visualBody = true)
                // Both links are native passengers; the body needs no following teleports.
                check(spawned.addPassenger(body)) { "Unable to attach mount visual body" }
            }
            val session =
                MountSession(
                    playerId = player.uniqueId,
                    entityId = spawned.uniqueId,
                    definition = definition,
                    settings = settings,
                    expiresAtMillis = System.currentTimeMillis() + durationMillis.coerceAtLeast(1L),
                    sneakGesture = DoubleSneakGesture(config.doubleSneakWindow.toMillis()),
                    visualEntityId = visualEntity?.uniqueId,
                    previousBoundingBox = spawned.boundingBox.clone(),
                )
            sessionsByPlayer[player.uniqueId] = session
            playerByEntity[spawned.uniqueId] = player.uniqueId
            visualEntity?.let { playerByEntity[it.uniqueId] = player.uniqueId }
            if (definition.control == MountControl.NATIVE_FLIGHT) {
                configureNativeHappyGhastMotion(spawned as HappyGhast, maximumSpeed(session, now))
            }
            if (definition.control == MountControl.PLAYER_FLIGHT) {
                visualFlight.begin(player, mountPlayerFlySpeed(maximumSpeed(session, now), player.isSprinting))
                // The client moves this passenger with its own predicted player position.
                // A server-teleported follower trails behind and can push its pilot.
                check(player.addPassenger(spawned)) { "Unable to attach flight companion" }
            } else {
                check(passengers.openRide(spawned, player, definition, session.hasRiderFireProtection(), visualEntity)) {
                    "Unable to create passenger seats"
                }
            }
            updateMiningCompensation(player, session)
            if (definition.control == MountControl.PLAYER_FLIGHT || visualEntity != null) {
                replayFlightPassengers(player, session)
            }
            lastSummonAt[player.uniqueId] = now
            onStateChanged()
            player.world.spawnParticle(Particle.END_ROD, player.location, 10, 0.4, 0.4, 0.4, 0.01)
            player.world.playSound(player.location, Sound.ENTITY_HORSE_SADDLE, 1.0f, 1.0f)
            val (controlsKey, controlsFallback) =
                when {
                    definition.control == MountControl.PLAYER_FLIGHT ->
                        "player-flight-controls" to
                            "<gray>WASD — полёт, Space — вверх, Shift — вниз, двойной Shift — завершить"
                    definition.control == MountControl.NATIVE_FLIGHT ->
                        "native-flight-controls" to
                            "<gray>WASD — полёт по взгляду, Space — вверх, двойной Shift — спешиться"
                    spawned is Camel ->
                        "camel-controls" to
                            "<gray>WASD — движение, удерживайте Space и отпустите — рывок, двойной Shift — спешиться"
                    spawned is Horse ->
                        "horse-controls" to
                            "<gray>WASD — движение, удерживайте Space и отпустите — прыжок, двойной Shift — спешиться"
                    definition.movement == MountMovement.WALKING ->
                        "ground-controls" to "<gray>WASD — движение, Space — прыжок, двойной Shift — спешиться"
                    else ->
                        "flight-controls" to
                            "<gray>WASD — движение, Space — вверх, Shift — вниз, двойной Shift — спешиться"
                }
            player.sendActionBar(TextUtils.mm(config.message(controlsKey, controlsFallback), true))
            recordProductOutcome(player, spawned.uniqueId.toString())
            recordActivity(
                player.uniqueId,
                "mount.ride_started",
                definition.id,
                spawned.uniqueId.toString(),
                mapOf("control" to definition.control.name, "movement" to definition.movement.name),
            )
            MountSpawnResult.SUCCESS
        } catch (failure: Throwable) {
            sessionsByPlayer.remove(player.uniqueId)
            playerByEntity.remove(spawned.uniqueId)
            passengers.closeRide(spawned.uniqueId)
            visualEntity?.let {
                playerByEntity.remove(it.uniqueId)
                it.remove()
            }
            if (definition.control == MountControl.PLAYER_FLIGHT) restoreVisualFlight(player)
            setAirborneMiningCompensation(player, airborneMiningModifier, enabled = false)
            spawned.remove()
            warn("Unable to spawn mount {} for {}: {}", definition.id, player.name, failure.javaClass.simpleName, failure)
            MountSpawnResult.SPAWN_FAILED
        }
    }

    fun remove(playerId: UUID, reason: MountRemovalReason) {
        val session = sessionsByPlayer.remove(playerId) ?: return
        if (session.stopping) return
        session.stopping = true
        recordActivity(
            playerId,
            "mount.ride_ended",
            session.definition.id,
            session.entityId.toString(),
            mapOf(
                "reason" to reason.name,
                "durationMs" to (System.currentTimeMillis() - session.startedAtMillis).coerceAtLeast(0).toString(),
            ),
        )
        onStateChanged()
        playerByEntity.remove(session.entityId)
        session.visualEntityId?.let(playerByEntity::remove)
        passengers.closeRide(session.entityId)
        val player = plugin.server.getPlayer(playerId)
        val entity = plugin.server.getEntity(session.entityId) as? LivingEntity
        val visualEntity = session.visualEntityId?.let { plugin.server.getEntity(it) as? LivingEntity }
        val effectLocation = entity?.location ?: player?.location

        if (player != null) {
            if (session.definition.control == MountControl.PLAYER_FLIGHT) restoreVisualFlight(player)
            if (entity != null && session.riderMountHidden) {
                runCatching { setRiderMountHidden(player, visualEntity ?: entity, false) }
            }
            setAirborneMiningCompensation(player, airborneMiningModifier, enabled = false)
        }
        runCatching { visualEntity?.remove() }
        runCatching { entity?.remove() }
        if (effectLocation != null && effectLocation.world != null) {
            effectLocation.world.spawnParticle(Particle.SOUL, effectLocation, 20, 0.6, 0.6, 0.6, 0.02)
            effectLocation.world.playSound(effectLocation, Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f, 1.15f)
        }
        if (player != null && session.definition.movement == MountMovement.FLYING && reason != MountRemovalReason.DIED) {
            val ticks = (configProvider().postFlightSlowFalling.toMillis() / 50L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            if (ticks > 0) player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, ticks, 0, false, false, true))
        }
        if (player != null && reason == MountRemovalReason.EXPIRED) {
            message(player, "expired", "<gray>Время поездки на маунте закончилось.")
        }
    }

    private fun restoreVisualFlight(player: Player) {
        runCatching { visualFlight.restore(player) }
            .onFailure { warn("Unable to restore mount flight for {}: {}", player.name, it.javaClass.simpleName) }
    }

    // Keep the existing Bukkit ground-state predicate for gameplay compatibility.
    @Suppress("DEPRECATION")
    private fun updateMiningCompensation(player: Player, session: MountSession) {
        val enabled = shouldCompensateMountMining(
            session.definition.movement, session.definition.control, player.isOnGround,
            configProvider().compensateAirborneMining,
        )
        if (enabled != session.airborneMiningCompensated) {
            setAirborneMiningCompensation(player, airborneMiningModifier, enabled)
            session.airborneMiningCompensated = enabled
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInput(event: PlayerInputEvent) {
        val session = sessionsByPlayer[event.player.uniqueId] ?: return
        val updated = event.input.toState()
        session.input = updated

        when (session.sneakGesture.update(updated.sneak, System.currentTimeMillis())) {
            SneakGestureResult.DOUBLE_PRESSED -> {
                session.allowDismount = true
                remove(event.player.uniqueId, MountRemovalReason.DOUBLE_SNEAK)
            }
            SneakGestureResult.PRESSED -> {
                val config = configProvider()
                val now = System.currentTimeMillis()
                if (session.lastHintAtMillis == Long.MIN_VALUE || now - session.lastHintAtMillis >= config.descendingHintCooldown.toMillis()) {
                    session.lastHintAtMillis = now
                    val (messageKey, fallback) =
                        if (session.definition.movement == MountMovement.WALKING ||
                            session.definition.control == MountControl.NATIVE_FLIGHT) {
                            "dismount-hint" to "<yellow>Ещё раз Shift — спешиться"
                        } else {
                            "descending" to "<aqua>Снижение… <gray>двойной Shift — спешиться"
                        }
                    event.player.sendActionBar(TextUtils.mm(config.message(messageKey, fallback), true))
                }
            }
            SneakGestureResult.NONE -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        val data = event.entity.persistentDataContainer
        if (
            shouldAllowCancelledMountSpawn(
                cancelled = event.isCancelled,
                reason = event.spawnReason,
                owner = data.get(ownerKey, PersistentDataType.STRING),
                mountId = data.get(mountIdKey, PersistentDataType.STRING),
                spawnToken = data.get(spawnTokenKey, PersistentDataType.STRING),
                expectedMountIds = allowedMountIds,
                pendingSpawnTokens = pendingSpawnTokens,
            )
        ) {
            event.isCancelled = false
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDismount(event: EntityDismountEvent) {
        val player = event.entity as? Player ?: return
        val session = sessionsByPlayer[player.uniqueId] ?: return
        if (event.dismounted.uniqueId != session.entityId || session.stopping) return

        if (shouldCancelUnauthorizedDismount(session.allowDismount, event.isCancellable)) {
            event.isCancelled = true
            return
        }
        scheduler.runLater(1L, Runnable {
            if (sessionsByPlayer[player.uniqueId] === session) remove(player.uniqueId, MountRemovalReason.DISMOUNTED)
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onMountDamage(event: EntityDamageEvent) {
        if (
            playerByEntity.containsKey(event.entity.uniqueId) &&
            shouldCancelMountDamage(MountDamageTarget.MOUNT, event.cause)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onRiderDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val session = sessionsByPlayer[player.uniqueId] ?: return
        if (shouldCancelMountDamage(MountDamageTarget.RIDER, event.cause, session.hasRiderFireProtection())) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRiderKnockoff(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val session = sessionsByPlayer[player.uniqueId] ?: return
        if (!shouldKnockRiderOff(event.finalDamage, configProvider().riderKnockoffDamage)) return

        scheduler.runLater(1L, Runnable {
            if (sessionsByPlayer[player.uniqueId] === session) remove(player.uniqueId, MountRemovalReason.KNOCKED_OFF)
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCombust(event: EntityCombustEvent) {
        val player = event.entity as? Player
        if (player != null) {
            val session = sessionsByPlayer[player.uniqueId] ?: return
            if (session.hasRiderFireProtection()) {
                event.isCancelled = true
                extinguishRiderFire(player, fireProtected = true)
            }
            return
        }
        if (playerByEntity.containsKey(event.entity.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onExplosionPrime(event: ExplosionPrimeEvent) {
        if (playerByEntity.containsKey(event.entity.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (playerByEntity.containsKey(event.entity.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = visualFlight.recover(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (sessionsByPlayer[event.player.uniqueId]?.definition?.control == MountControl.PLAYER_FLIGHT) {
            remove(event.player.uniqueId, MountRemovalReason.INVALID)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVisualMountInteract(event: PlayerInteractEntityEvent) {
        val owner = playerByEntity[event.rightClicked.uniqueId] ?: return
        if (sessionsByPlayer[owner]?.definition?.control == MountControl.PLAYER_FLIGHT) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFlightBodyTracked(event: PlayerTrackEntityEvent) {
        val owner = playerByEntity[event.entity.uniqueId] ?: return
        val session = sessionsByPlayer[owner] ?: return
        if (session.definition.control == MountControl.PLAYER_FLIGHT || session.visualEntityId != null) {
            replayFlightPassengers(event.player, session)
        }
    }

    private fun replayFlightPassengers(viewer: Player, session: MountSession) {
        // SetPassengers received before SpawnEntity drops the unknown passenger.
        // Replay after tracking, including the pilot whose player is not tracked to itself.
        scheduler.runLater(1L, Runnable {
            if (sessionsByPlayer[session.playerId] !== session || !viewer.isOnline) return@Runnable
            val pilot = plugin.server.getPlayer(session.playerId) ?: return@Runnable
            val body = plugin.server.getEntity(session.entityId) as? LivingEntity ?: return@Runnable
            if (!body.isValid || !isMountControlledBy(pilot, body, session.definition.control)) return@Runnable
            synchronizePassengers(viewer, listOf(if (session.definition.control == MountControl.PLAYER_FLIGHT) pilot else body))
            if (viewer.uniqueId == pilot.uniqueId && session.riderMountHidden) {
                val appearance = session.visualEntityId?.let { plugin.server.getEntity(it) as? LivingEntity } ?: body
                setRiderMountHidden(viewer, appearance, true)
            }
        })
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) = remove(event.player.uniqueId, MountRemovalReason.QUIT)
    @EventHandler fun onDeath(event: PlayerDeathEvent) = remove(event.entity.uniqueId, MountRemovalReason.DIED)
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = remove(event.player.uniqueId, MountRemovalReason.CHANGED_WORLD)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val session = sessionsByPlayer[event.player.uniqueId] ?: return
        if (session.definition.control == MountControl.PLAYER_FLIGHT) {
            remove(event.player.uniqueId, MountRemovalReason.TELEPORTED)
            return
        }
        scheduler.runLater(1L, Runnable {
            if (sessionsByPlayer[event.player.uniqueId] === session) remove(event.player.uniqueId, MountRemovalReason.TELEPORTED)
        })
    }

    private fun tick() = tick(System.currentTimeMillis())

    private fun tick(now: Long) {
        sessionsByPlayer.values.toList().forEach { session ->
            val player = plugin.server.getPlayer(session.playerId)
            val entity = plugin.server.getEntity(session.entityId) as? LivingEntity
            val visualEntity = session.visualEntityId?.let { plugin.server.getEntity(it) as? LivingEntity }
            val location = if (session.definition.control == MountControl.PLAYER_FLIGHT) player?.location else entity?.location
            val inWater = session.definition.movement == MountMovement.SWIMMING && entity != null &&
                isAquaticEnvironment(
                    entity.isInWater || entity.location.block.type == org.bukkit.Material.BUBBLE_COLUMN,
                    entity.location.block.isLiquid,
                )
            if (inWater) {
                session.leftWaterAtMillis = null
                session.aquaticAirPosition = null
            }
            when {
                player == null || !player.isOnline -> remove(session.playerId, MountRemovalReason.QUIT)
                entity == null || !entity.isValid || !isMountControlledBy(player, entity, session.definition.control) -> remove(session.playerId, MountRemovalReason.INVALID)
                session.visualEntityId != null &&
                    (visualEntity == null || !visualEntity.isValid || visualEntity.vehicle?.uniqueId != session.entityId) ->
                    remove(session.playerId, MountRemovalReason.INVALID)
                now >= session.expiresAtMillis -> remove(session.playerId, MountRemovalReason.EXPIRED)
                now - session.lastActiveAtMillis >= configProvider().idleTimeout.toMillis() -> {
                    remove(session.playerId, MountRemovalReason.IDLE)
                }
                location != null && location.y > location.world.maxHeight + configProvider().maximumHeightAboveWorld -> {
                    remove(session.playerId, MountRemovalReason.HEIGHT_LIMIT)
                }
                location != null && location.y < location.world.minHeight - configProvider().maximumHeightAboveWorld -> {
                    remove(session.playerId, MountRemovalReason.HEIGHT_LIMIT)
                }
                location != null && !location.world.worldBorder.isInside(location) -> {
                    remove(session.playerId, MountRemovalReason.WORLD_BORDER)
                }
                session.leftWaterAtMillis?.let { now - it >= AQUATIC_DRY_GRACE_MILLIS } == true -> {
                    remove(session.playerId, MountRemovalReason.LEFT_WATER)
                }
                else -> {
                    session.input = player.currentInput.toState()
                    if (session.input.hasMovementIntent) session.lastActiveAtMillis = now
                    session.ticks++
                    extinguishRiderFire(player, session.hasRiderFireProtection())
                    (entity as? Mob)?.let(::maintainMountMobState)
                    if (session.ticks == 1L || session.ticks % ABILITY_REFRESH_TICKS == 0L) {
                        refreshAbilityEffects(player, session.definition.abilities.passives, session.settings.abilityUpgrades)
                    }
                    val appearanceEntity = visualEntity ?: entity
                    visualEntity?.let {
                        (it as? Mob)?.let(::maintainMountMobState)
                        it.setRotation(entity.location.yaw, 0f)
                    }
                    updateRiderMountVisibility(player, appearanceEntity, session)
                    if (session.definition.control == MountControl.PLAYER_FLIGHT) {
                        updateMiningCompensation(player, session)
                        val updated = runCatching {
                            visualFlight.updateSpeed(player, mountPlayerFlySpeed(maximumSpeed(session, now), player.isSprinting))
                            entity.setRotation(player.location.yaw, 0f)
                        }.onFailure { warn("Unable to update visual mount for {}: {}", player.name, it.javaClass.simpleName) }
                        if (updated.isFailure) {
                            remove(session.playerId, MountRemovalReason.INVALID)
                            return@forEach
                        }
                        val velocity = player.velocity
                        session.motionState = MountMotionState(
                            MotionVector(velocity.x, velocity.y, velocity.z).normalized(), velocity.length(),
                        )
                        emitTrail(entity, session)
                        return@forEach
                    }
                    val maximumSpeed = move(player, entity, session, now, inWater)
                    if (!passengers.reconcileRide(session.entityId, session.hasRiderFireProtection())) {
                        remove(session.playerId, MountRemovalReason.INVALID)
                        return@forEach
                    }
                    updateRamBehavior(player, entity, session, maximumSpeed)
                    updateTrampleBehavior(player, entity, session, maximumSpeed, now)
                    emitTrail(appearanceEntity, session)
                }
            }
        }
    }

    private fun updateRiderMountVisibility(player: Player, entity: LivingEntity, session: MountSession) {
        val config = configProvider()
        val appearanceScale =
            session.definition.effectiveAppearance(session.settings.scaleMultiplier, session.settings.skin).scale
        val (hideAtPitch, showAtPitch) =
            riderViewPitchThresholds(
                appearanceScale,
                config.hideFlyingMountPitch.toFloat(),
                config.showFlyingMountPitch.toFloat(),
            )
        val hidden =
            config.hideFlyingMountFromRider &&
                nextRiderMountHidden(
                    session.settings.riderViewAutoHide,
                    session.riderMountHidden,
                    player.location.pitch,
                    hideAtPitch,
                    showAtPitch,
                )
        if (hidden == session.riderMountHidden) return
        session.riderMountHidden = hidden
        runCatching { setRiderMountHidden(player, entity, hidden) }
            .onFailure { warn("Unable to update rider-only mount visibility for {}: {}", player.name, it.javaClass.simpleName) }
    }

    private fun maximumSpeed(session: MountSession, nowMillis: Long): Double {
        val config = configProvider()
        val speedScale =
            when (session.definition.movement) {
                MountMovement.WALKING -> config.walkingSpeedScale
                MountMovement.FLYING -> config.flyingSpeedScale
                MountMovement.SWIMMING -> config.swimmingSpeedScale
            }
        val sprint = if (session.input.sprint) config.sprintMultiplier * session.settings.sprintMultiplier else 1.0
        val abilitySpeed = activeAbilitySpeedMultiplier(session.settings.abilityUpgrades)
        // The persisted entitlement is applied to the immutable configured base
        // exactly once per movement calculation. Expiry is wall-clock based and
        // therefore takes effect on the next tick without any storage I/O.
        val careBoostedSpeed = applyMountCareSpeed(
            session.settings.speed,
            careBoostStatusProvider(session.playerId, nowMillis),
        )
        return (careBoostedSpeed * speedScale * sprint * abilitySpeed)
            .coerceAtMost(config.maximumSpeedBlocksPerTick)
    }

    private fun move(player: Player, entity: LivingEntity, session: MountSession, nowMillis: Long, inWater: Boolean): Double {
        val config = configProvider()
        val maximumSpeed = maximumSpeed(session, nowMillis)
        if (session.definition.movement == MountMovement.SWIMMING && !inWater) {
            moveOutsideWater(entity, session, nowMillis, config.maximumSpeedBlocksPerTick, config.maximumVerticalSpeed)
            return maximumSpeed
        }
        if (session.definition.control == MountControl.NATIVE_FLIGHT) {
            configureNativeHappyGhastMotion(entity as HappyGhast, maximumSpeed)
            val velocity = entity.velocity
            session.motionState = MountMotionState(
                MotionVector(velocity.x, velocity.y, velocity.z).normalized(), velocity.length(),
            )
            entity.fallDistance = 0.0f
            return maximumSpeed
        }
        val planar = MountMotion.planarDirection(player.location.yaw, session.input)
        val timing = session.definition.motion.resolve(config.motionTiming)
        val nativeHorse = nativeWalkingHorse(entity, session.definition.movement)
        if (nativeHorse != null) {
            // Saddled horses and camels are client-controlled. Velocity packets fight
            // their predicted gravity and charged jump/dash, so only tune attributes.
            session.motionState =
                MountMotion.advance(
                    current = session.motionState,
                    targetVelocity = planar * maximumSpeed,
                    timing = timing,
                    handlingMultiplier = session.settings.handlingMultiplier,
                )
            configureNativeHorseMotion(
                horse = nativeHorse,
                maximumSpeedBlocksPerTick = session.motionState.speed,
                jumpVelocity = walkingJumpVelocity(config.jumpVelocity, session.definition.abilities),
                stepHeight = session.settings.walkingStepHeight,
            )
            entity.fallDistance = 0.0f
            return maximumSpeed
        }
        val target =
            when (session.definition.movement) {
                MountMovement.WALKING -> planar * maximumSpeed
                MountMovement.FLYING,
                MountMovement.SWIMMING,
                -> MountMotion.airborneTarget(
                    pitchDegrees = player.location.pitch,
                    input = session.input,
                    planar = planar,
                    maximumSpeed = maximumSpeed,
                    verticalSpeedRatio = config.verticalSpeedRatio,
                    maximumVerticalSpeed = config.maximumVerticalSpeed,
                    pitchInfluence = config.flightPitchInfluence,
                )
            }
        session.motionState =
            MountMotion.advance(
                current = session.motionState,
                targetVelocity = target,
                timing = timing,
                handlingMultiplier = session.settings.handlingMultiplier,
            )
        val controlledVelocity = session.motionState.velocity
        val currentVertical = entity.velocity.y
        val velocity =
            if (session.definition.movement == MountMovement.WALKING) {
                val vertical =
                    if (session.input.jump && entity.isOnGround) {
                        walkingJumpVelocity(config.jumpVelocity, session.definition.abilities)
                    } else {
                        currentVertical
                    }
                MotionVector(controlledVelocity.x, vertical, controlledVelocity.z)
            } else {
                controlledVelocity
            }

        entity.velocity = constrainPhasingVelocity(entity, velocity).toBukkit()
        entity.fallDistance = 0.0f
        val targetYaw = MountMotion.facingYaw(controlledVelocity, entity.yaw)
        entity.setRotation(targetYaw, 0.0f)
        return maximumSpeed
    }

    private fun moveOutsideWater(
        entity: LivingEntity,
        session: MountSession,
        nowMillis: Long,
        speedLimit: Double,
        verticalLimit: Double,
    ) {
        val position = entity.location.toVector()
        val previous = session.motionState.velocity
        val previousPosition = session.aquaticAirPosition
        val velocity = if (session.leftWaterAtMillis == null) {
            session.leftWaterAtMillis = nowMillis
            val current = entity.velocity
            if (!entity.isOnGround && previous.y > 0.0 && current.y > 0.0) {
                // A single breach impulse also clears the surface at the first catalog level.
                val horizontal = previous.normalizedHorizontal() * maxOf(previous.horizontalLength, 0.45)
                MotionVector(horizontal.x, maxOf(current.y, 0.55), horizontal.z)
            } else {
                MotionVector(current.x, minOf(current.y, 0.0), current.z)
            }
        } else {
            val travelled = position.clone().subtract(checkNotNull(previousPosition))
            // Squid clears native X/Z after travel. Actual displacement still exposes collisions.
            fun unclipped(speed: Double, distance: Double): Double =
                if (kotlin.math.abs(distance) + 1.0e-6 < kotlin.math.abs(speed)) 0.0 else speed
            MotionVector(
                unclipped(previous.x, travelled.x) * 0.91,
                (unclipped(previous.y, travelled.y) - 0.08) * 0.98,
                unclipped(previous.z, travelled.z) * 0.91,
            )
        }.let { it.copy(y = it.y.coerceIn(-verticalLimit, verticalLimit)) }
            .let { if (it.length > speedLimit) it.normalized() * speedLimit else it }
        // Keep native gravity disabled: this one curve avoids species-specific drag and double gravity.
        entity.velocity = constrainPhasingVelocity(entity, velocity).toBukkit()
        entity.fallDistance = 0.0f
        entity.setRotation(MountMotion.facingYaw(velocity, entity.yaw), 0.0f)
        session.motionState = MountMotionState(velocity.normalized(), velocity.length)
        session.aquaticAirPosition = position
    }

    private fun emitTrail(entity: LivingEntity, session: MountSession) {
        val trail = session.settings.skin?.trail ?: return
        if (session.ticks % trail.intervalTicks != 0L) return
        val particle = runCatching { Particle.valueOf(trail.particle) }.getOrNull() ?: return
        if (particle.dataType != Void::class.java && particle.dataType != java.lang.Void::class.java) return
        val box = entity.boundingBox
        val points =
            mountTrailPoints(
                MountTrailBounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
                entity.yaw,
                session.motionState.direction,
                trail,
                session.ticks,
            )
        if (trail.pattern == MountTrailPattern.SCATTER) {
            val origin = points.single()
            val location = org.bukkit.Location(entity.world, origin.x, origin.y, origin.z)
            entity.world.spawnParticle(particle, location, trail.count, trail.spread, trail.spread, trail.spread, trail.speed)
        } else {
            val fineSpread = trail.spread * 0.15
            points.forEach { point ->
                val location = org.bukkit.Location(entity.world, point.x, point.y, point.z)
                entity.world.spawnParticle(particle, location, 1, fineSpread, fineSpread, fineSpread, trail.speed)
            }
        }
    }

    private fun updateRamBehavior(
        player: Player,
        entity: LivingEntity,
        session: MountSession,
        maximumSpeed: Double,
    ) {
        val behavior = session.definition.behaviors.filterIsInstance<MountRamBehavior>().firstOrNull()
        val currentBox = entity.boundingBox.clone()
        if (behavior == null) {
            session.previousBoundingBox = currentBox
            return
        }
        val previousBox = session.previousBoundingBox ?: currentBox
        val requestedForward = MountMotion.planarDirection(player.location.yaw, MountInputState(forward = true))
        val speedFraction =
            actualMountForwardSpeedFraction(
                previousBox.toRamBounds(),
                currentBox.toRamBounds(),
                maximumSpeed,
                requestedForward,
            )
        val transition =
            advanceMountRam(
                current = session.ramState,
                behavior = behavior,
                tick = session.ticks,
                input = session.input,
                grounded = entity.isOnGround,
                speedFraction = speedFraction,
            )
        session.ramState = transition.state
        if (transition.activated) {
            val chargeLocation = entity.location.add(0.0, entity.height * 0.45, 0.0)
            entity.world.playSound(chargeLocation, Sound.ENTITY_RAVAGER_ROAR, 0.75f, 1.15f)
            entity.world.spawnParticle(Particle.CLOUD, chargeLocation, 12, 0.45, 0.2, 0.45, 0.04)
        }
        if (session.ramState.phase == MountRamPhase.ACTIVE) {
            val target = selectRamTarget(player, entity, previousBox, currentBox, session.motionState.direction, behavior)
            if (target != null) {
                session.ramState = consumeMountRam(session.ramState)
                target.damage(behavior.damage, player)
                val impactLocation = target.location.add(0.0, target.height * 0.55, 0.0)
                entity.world.playSound(impactLocation, Sound.ENTITY_RAVAGER_ATTACK, 0.9f, 1.0f)
                entity.world.spawnParticle(Particle.CRIT, impactLocation, 16, 0.35, 0.3, 0.35, 0.18)
                entity.world.spawnParticle(Particle.CLOUD, impactLocation, 6, 0.25, 0.2, 0.25, 0.04)
            }
        }
        session.previousBoundingBox = currentBox
    }

    private fun selectRamTarget(
        player: Player,
        mount: LivingEntity,
        previousBox: BoundingBox,
        currentBox: BoundingBox,
        motionDirection: MotionVector,
        behavior: MountRamBehavior,
    ): LivingEntity? {
        val forward =
            motionDirection.normalizedHorizontal().takeUnless { it == MotionVector.ZERO }
                ?: MountMotion.planarDirection(mount.yaw, MountInputState(forward = true))
        val corridor = previousBox.clone().union(currentBox.clone())
        corridor.expandDirectional(forward.x * behavior.reach, 0.0, forward.z * behavior.reach)
        corridor.expand(behavior.lateralPadding, 0.25, behavior.lateralPadding)
        val centerX = currentBox.centerX
        val centerZ = currentBox.centerZ
        return mount.world.getNearbyEntities(corridor)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { candidate ->
                isHostileMountTarget(player, mount, candidate) &&
                    sweptRamIntersects(
                        previousBox.toRamBounds(),
                        currentBox.toRamBounds(),
                        candidate.boundingBox.toRamBounds(),
                        forward,
                        behavior.reach,
                        behavior.lateralPadding,
                    )
            }
            .sortedWith(
                compareBy<LivingEntity> {
                    val box = it.boundingBox
                    ((box.centerX - centerX) * forward.x + (box.centerZ - centerZ) * forward.z)
                }.thenBy { it.uniqueId.toString() },
            )
            .firstOrNull()
    }

    private fun updateTrampleBehavior(
        player: Player,
        mount: LivingEntity,
        session: MountSession,
        maximumSpeed: Double,
        nowMillis: Long,
    ) {
        val behavior = session.definition.behaviors.filterIsInstance<MountTrampleBehavior>().firstOrNull() ?: return
        if (!trampleSpeedEligible(session.motionState.speed, maximumSpeed, behavior.minimumSpeedFraction)) return
        val cooldownMillis = behavior.cooldown.toMillis()
        session.trampleDamageAtMillis.entries.removeIf { nowMillis - it.value > cooldownMillis * 2L }
        val box = mount.boundingBox.clone()
        box.expand(behavior.horizontalPadding, 0.05, behavior.horizontalPadding)
        box.expandDirectional(0.0, -behavior.downwardReach, 0.0)
        val targets =
            mount.world.getNearbyEntities(box)
                .asSequence()
                .filterIsInstance<LivingEntity>()
                .filter { isHostileMountTarget(player, mount, it) }
                .filter {
                    trampleCooldownReady(session.trampleDamageAtMillis[it.uniqueId], nowMillis, cooldownMillis)
                }
                .sortedBy { it.uniqueId.toString() }
                .take(behavior.maximumTargets)
                .toList()
        if (targets.isEmpty()) return
        targets.forEach { target ->
            session.trampleDamageAtMillis[target.uniqueId] = nowMillis
            target.damage(behavior.damage, player)
            val impact = target.location.add(0.0, target.height * 0.3, 0.0)
            mount.world.spawnParticle(Particle.CLOUD, impact, 5, 0.2, 0.08, 0.2, 0.03)
        }
        mount.world.playSound(mount.location, Sound.ENTITY_RAVAGER_STEP, 0.8f, 0.75f)
    }

    private fun isHostileMountTarget(player: Player, mount: LivingEntity, candidate: LivingEntity): Boolean =
        candidate is Enemy &&
            candidate !is Boss &&
            candidate.uniqueId != mount.uniqueId &&
            candidate.uniqueId != player.uniqueId &&
            candidate.isValid &&
            !candidate.isDead &&
            candidate.health > 0.0 &&
            !candidate.isInvulnerable &&
            !playerByEntity.containsKey(candidate.uniqueId) &&
            !candidate.persistentDataContainer.has(mountIdKey, PersistentDataType.STRING) &&
            mount.hasLineOfSight(candidate)

    private fun BoundingBox.toRamBounds(): MountRamBounds = MountRamBounds(minX, minZ, maxX, maxZ)

    private fun refreshAbilityEffects(
        player: Player,
        passives: Collection<MountPassiveAbilityDefinition>,
        upgrades: Collection<MountAbilityUpgradeDefinition>,
    ) {
        passives.forEach { ability ->
            player.addPotionEffect(
                PotionEffect(ability.effect.potionEffectType(), ABILITY_EFFECT_DURATION_TICKS, ability.amplifier, false, false, true),
            )
        }
        upgrades.forEach { ability ->
            player.addPotionEffect(
                PotionEffect(ability.effect.potionEffectType(), ABILITY_EFFECT_DURATION_TICKS, 0, false, false, true),
            )
        }
    }

    private fun configureEntity(
        entity: LivingEntity,
        definition: MountDefinition,
        settings: MountRuntimeSettings,
        player: Player,
        visualBody: Boolean = false,
    ) {
        entity.customName(Component.text(player.name, NamedTextColor.GRAY))
        entity.isCustomNameVisible = false
        entity.isPersistent = false
        entity.isGlowing = settings.glow
        configureMountDurability(entity)
        entity.setGravity(definition.movement == MountMovement.WALKING)
        if (definition.control == MountControl.PLAYER_FLIGHT || visualBody) {
            entity.isCollidable = false
            entity.isInvulnerable = true
            entity.isSilent = true
            entity.setAI(false)
        }
        if (definition.movement == MountMovement.WALKING) {
            configureWalkingStepHeight(entity, settings.walkingStepHeight)
        }
        (entity as? Mob)?.let(::configureMountMob)
        if (definition.control == MountControl.NATIVE_FLIGHT && !visualBody) (entity as HappyGhast).setAware(true)
        nativeWalkingHorse(entity, definition.movement)?.let { configureNativeHorse(it, player) }
        val appearance = if (definition.visualEntityType != null && !visualBody) {
            MountAppearance(scale = 0.1, equipment = mapOf(MountEquipmentSlot.BODY to "WHITE_HARNESS"))
        } else definition.effectiveAppearance(settings.scaleMultiplier, settings.skin)
        MountAppearanceApplicator.apply(entity, appearance)
        if (definition.visualEntityType != null && !visualBody) {
            entity.isInvisible = true
            entity.isGlowing = false
        }
        tagEntity(entity, definition, player)
    }

    private fun spawnOwnedEntity(
        player: Player,
        definition: MountDefinition,
        entityType: org.bukkit.entity.EntityType,
    ): LivingEntity? {
        val spawnToken = UUID.randomUUID()
        pendingSpawnTokens.add(spawnToken)
        val spawnLocation = player.location
        if (definition.control == MountControl.PLAYER_FLIGHT) spawnLocation.add(0.0, player.height, 0.0)
        val entity = try {
            player.world.spawnEntity(spawnLocation, entityType, CreatureSpawnEvent.SpawnReason.CUSTOM) { spawned ->
                (spawned as? LivingEntity)?.let {
                    tagEntity(it, definition, player)
                    it.persistentDataContainer.set(spawnTokenKey, PersistentDataType.STRING, spawnToken.toString())
                }
            } as? LivingEntity
        } catch (_: Throwable) {
            null
        } finally {
            pendingSpawnTokens.remove(spawnToken)
        } ?: return null
        if (!entity.isInWorld || !entity.isValid) {
            entity.remove()
            return null
        }
        return entity
    }

    private fun tagEntity(
        entity: LivingEntity,
        definition: MountDefinition,
        player: Player,
    ) {
        entity.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
        entity.persistentDataContainer.set(mountIdKey, PersistentDataType.STRING, definition.id)
    }

    private fun Input.toState(): MountInputState =
        MountInputState(
            forward = isForward,
            backward = isBackward,
            left = isLeft,
            right = isRight,
            jump = isJump,
            sneak = isSneak,
            sprint = isSprint,
        )

    private val MountInputState.hasMovementIntent: Boolean
        get() = forward || backward || left || right || jump || sneak

    private fun MotionVector.toBukkit() = Vector(x, y, z)

    companion object {
        private const val ABILITY_REFRESH_TICKS = 20L
        private const val ABILITY_EFFECT_DURATION_TICKS = 280
        private const val AQUATIC_DRY_GRACE_MILLIS = 5_000L
    }

    private fun canApplyScale(
        entity: LivingEntity,
        currentScale: Double,
        replacementScale: Double,
    ): Boolean {
        if (replacementScale <= currentScale) return true
        val ratio = replacementScale / currentScale
        val candidate = scaledMountBoundingBox(entity.boundingBox, ratio)
        return runCatching { !entity.wouldCollideUsing(candidate) }.getOrDefault(false)
    }

    private fun MountSession.hasRiderFireProtection(): Boolean =
        definition.abilities.passives.any { it.effect == MountAbilityEffect.FIRE_RESISTANCE } ||
            settings.abilityUpgrades.any { it.effect == MountAbilityEffect.FIRE_RESISTANCE }
}

internal fun MountAbilityEffect.potionEffectType(): PotionEffectType =
    when (this) {
        MountAbilityEffect.WATER_BREATHING -> PotionEffectType.WATER_BREATHING
        MountAbilityEffect.NIGHT_VISION -> PotionEffectType.NIGHT_VISION
        MountAbilityEffect.FIRE_RESISTANCE -> PotionEffectType.FIRE_RESISTANCE
        MountAbilityEffect.DOLPHINS_GRACE -> PotionEffectType.DOLPHINS_GRACE
        MountAbilityEffect.RESISTANCE -> PotionEffectType.RESISTANCE
        MountAbilityEffect.REGENERATION -> PotionEffectType.REGENERATION
        MountAbilityEffect.SPEED -> PotionEffectType.SPEED
        MountAbilityEffect.SLOW_FALLING -> PotionEffectType.SLOW_FALLING
        MountAbilityEffect.STRENGTH -> PotionEffectType.STRENGTH
        MountAbilityEffect.HASTE -> PotionEffectType.HASTE
        MountAbilityEffect.LUCK -> PotionEffectType.LUCK
    }

internal fun trampleSpeedEligible(currentSpeed: Double, maximumSpeed: Double, minimumFraction: Double): Boolean =
    maximumSpeed > 0.0 && currentSpeed >= maximumSpeed * minimumFraction

internal fun trampleCooldownReady(lastDamageAtMillis: Long?, nowMillis: Long, cooldownMillis: Long): Boolean =
    lastDamageAtMillis == null || nowMillis >= lastDamageAtMillis && nowMillis - lastDamageAtMillis >= cooldownMillis

internal fun mountAppearanceMayGrow(
    current: MountAppearance,
    replacement: MountAppearance,
    supportsAge: Boolean,
): Boolean = replacement.scale > current.scale || (supportsAge && current.baby && !replacement.baby)

internal fun scaledMountBoundingBox(current: BoundingBox, ratio: Double): BoundingBox {
    require(ratio.isFinite() && ratio > 0.0) { "Mount bounding-box ratio must be positive and finite" }
    val halfWidth = (current.maxX - current.minX) * ratio / 2.0
    val halfDepth = (current.maxZ - current.minZ) * ratio / 2.0
    val centerX = current.centerX
    val centerZ = current.centerZ
    return BoundingBox(
        centerX - halfWidth,
        current.minY,
        centerZ - halfDepth,
        centerX + halfWidth,
        current.minY + (current.maxY - current.minY) * ratio,
        centerZ + halfDepth,
    )
}

internal fun configureMountMob(mob: Mob) {
    mob.setAware(false)
    mob.canPickupItems = false
    mob.removeWhenFarAway = false
    maintainMountMobState(mob)
}

internal fun configureWalkingStepHeight(entity: LivingEntity, stepHeight: Double) {
    entity.getAttribute(Attribute.STEP_HEIGHT)?.baseValue = stepHeight
}

internal fun nativeWalkingHorse(entity: LivingEntity, movement: MountMovement): AbstractHorse? =
    if (movement == MountMovement.WALKING && (entity is Horse || entity is Camel)) entity else null

internal fun configureNativeHorse(horse: AbstractHorse, player: Player) {
    horse.setAware(true)
    horse.isTamed = true
    horse.owner = player
}

internal fun configureNativeHorseMotion(
    horse: AbstractHorse,
    maximumSpeedBlocksPerTick: Double,
    jumpVelocity: Double,
    stepHeight: Double,
) {
    horse.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = nativeHorseMovementAttribute(maximumSpeedBlocksPerTick)
    horse.jumpStrength = jumpVelocity
    configureWalkingStepHeight(horse, stepHeight)
}

internal fun nativeHorseMovementAttribute(maximumSpeedBlocksPerTick: Double): Double =
    if (maximumSpeedBlocksPerTick <= 0.0) {
        0.0
    } else {
        (maximumSpeedBlocksPerTick / HORSE_ATTRIBUTE_BLOCKS_PER_TICK).coerceIn(0.01, 1.0)
    }

/** Vanilla owns the pilot and body as one vehicle; velocity/rotation packets would fight its prediction. */
internal fun configureNativeHappyGhastMotion(ghast: HappyGhast, maximumSpeedBlocksPerTick: Double) {
    checkNotNull(ghast.getAttribute(Attribute.FLYING_SPEED)) { "Happy Ghast has no flying-speed attribute" }
        .baseValue = nativeHappyGhastFlyingAttribute(maximumSpeedBlocksPerTick)
}

internal fun nativeHappyGhastFlyingAttribute(maximumSpeedBlocksPerTick: Double): Double {
    require(maximumSpeedBlocksPerTick.isFinite() && maximumSpeedBlocksPerTick >= 0.0)
    // Purpur 1.21.11 HappyGhast: ridden input *= 3.9 * attribute,
    // travel acceleration *= 5/3 * attribute, then airborne momentum *= .91.
    return kotlin.math.sqrt(maximumSpeedBlocksPerTick * (1.0 - 0.91) / (0.98 * 3.9 * (5.0 / 3.0)))
}

internal fun maintainMountMobState(mob: Mob) {
    (mob as? Bat)?.setAwake(true)
    (mob as? Creeper)?.apply {
        setIgnited(false)
        setExplosionRadius(0)
    }
    (mob as? EnderDragon)?.phase = EnderDragon.Phase.HOVER
}

internal fun constrainPhasingVelocity(entity: LivingEntity, desired: MotionVector): MotionVector {
    if (entity !is Vex && entity !is EnderDragon && !entity.hasNoPhysics()) return desired
    return constrainVelocity(desired) { delta ->
        entity.wouldCollideUsing(
            entity.boundingBox.clone().expandDirectional(delta.x, delta.y, delta.z),
        )
    }
}

internal fun constrainVelocity(
    desired: MotionVector,
    collidesAlong: (MotionVector) -> Boolean,
): MotionVector {
    if (!collidesAlong(desired)) return desired
    var accepted = MotionVector.ZERO
    listOf(
        MotionVector(desired.x, 0.0, 0.0),
        MotionVector(0.0, desired.y, 0.0),
        MotionVector(0.0, 0.0, desired.z),
    ).filter { it.length > 1.0e-9 }
        .sortedByDescending(MotionVector::length)
        .forEach { axis ->
            val candidate = accepted + axis
            if (!collidesAlong(candidate)) accepted = candidate
        }
    return accepted
}

internal enum class MountDamageTarget {
    MOUNT,
    RIDER,
}

internal fun configureMountDurability(entity: LivingEntity) {
    entity.isInvulnerable = true
}

internal fun shouldCancelMountDamage(
    target: MountDamageTarget,
    cause: EntityDamageEvent.DamageCause,
    riderFireProtected: Boolean = false,
): Boolean =
    target == MountDamageTarget.MOUNT ||
        cause == EntityDamageEvent.DamageCause.SUFFOCATION ||
        target == MountDamageTarget.RIDER && riderFireProtected && isRiderFireDamageCause(cause)

internal fun isRiderFireDamageCause(cause: EntityDamageEvent.DamageCause): Boolean =
    when (cause) {
        EntityDamageEvent.DamageCause.FIRE,
        EntityDamageEvent.DamageCause.FIRE_TICK,
        EntityDamageEvent.DamageCause.LAVA,
        EntityDamageEvent.DamageCause.HOT_FLOOR,
        EntityDamageEvent.DamageCause.CAMPFIRE,
        -> true
        else -> false
    }

internal fun extinguishRiderFire(player: Player, fireProtected: Boolean) {
    if (fireProtected && player.fireTicks > 0) player.fireTicks = 0
}

internal fun shouldCancelUnauthorizedDismount(allowDismount: Boolean, cancellable: Boolean): Boolean =
    !allowDismount && cancellable

internal fun shouldKnockRiderOff(finalDamage: Double, threshold: Double): Boolean =
    finalDamage.isFinite() && threshold.isFinite() && threshold > 0.0 && finalDamage >= threshold

internal fun isAquaticEnvironment(inWaterOrBubbleColumn: Boolean, blockIsLiquid: Boolean): Boolean =
    inWaterOrBubbleColumn || blockIsLiquid

internal fun nextRiderMountHidden(
    autoHide: Boolean,
    currentlyHidden: Boolean,
    pitch: Float,
    hideAtPitch: Float,
    showAtPitch: Float,
): Boolean {
    if (!autoHide || !pitch.isFinite()) return false
    return if (currentlyHidden) pitch > showAtPitch else pitch >= hideAtPitch
}

internal fun riderViewPitchThresholds(
    appearanceScale: Double,
    defaultHideAtPitch: Float,
    defaultShowAtPitch: Float,
): Pair<Float, Float> =
    when {
        appearanceScale >= 5.0 -> -10.0f to -25.0f
        appearanceScale >= LARGE_MOUNT_APPEARANCE_SCALE -> 10.0f to -5.0f
        else -> defaultHideAtPitch to defaultShowAtPitch
    }

internal fun airborneMiningCompensationAmount(): Double = 4.0

internal fun shouldCompensateMountMining(
    movement: MountMovement,
    control: MountControl,
    onGround: Boolean,
    configured: Boolean,
): Boolean = configured && movement == MountMovement.FLYING &&
    (control != MountControl.PLAYER_FLIGHT || !onGround)

internal fun setAirborneMiningCompensation(
    player: Player,
    modifier: AttributeModifier,
    enabled: Boolean,
) {
    player.getAttribute(Attribute.BLOCK_BREAK_SPEED)?.let { attribute ->
        attribute.removeModifier(modifier.key)
        if (enabled) attribute.addTransientModifier(modifier)
    }
}

internal fun shouldAllowCancelledMountSpawn(
    cancelled: Boolean,
    reason: CreatureSpawnEvent.SpawnReason,
    owner: String?,
    mountId: String?,
    spawnToken: String?,
    expectedMountIds: Set<String>,
    pendingSpawnTokens: Set<UUID>,
): Boolean =
    cancelled &&
        reason == CreatureSpawnEvent.SpawnReason.CUSTOM &&
        mountId in expectedMountIds &&
        owner != null &&
        runCatching { UUID.fromString(owner) }.isSuccess &&
        spawnToken != null &&
        runCatching { UUID.fromString(spawnToken) }.getOrNull() in pendingSpawnTokens

private const val HORSE_ATTRIBUTE_BLOCKS_PER_TICK = 2.1


internal fun isMountControlledBy(player: Player, entity: LivingEntity, control: MountControl): Boolean =
    if (control == MountControl.PLAYER_FLIGHT) {
        player.vehicle == null && player.allowFlight && player.world == entity.world && entity.vehicle?.uniqueId == player.uniqueId
    } else if (control == MountControl.NATIVE_FLIGHT) {
        entity.passengers.firstOrNull() == player
    } else {
        entity.passengers.contains(player)
    }

/** Nominal straight-line creative flight speed; the client owns acceleration and collisions. */
internal fun mountPlayerFlySpeed(blocksPerTick: Double, sprinting: Boolean): Float {
    require(blocksPerTick.isFinite() && blocksPerTick >= 0.0)
    // Bukkit sends half this value as abilities.flyingSpeed. Vanilla horizontal
    // input is damped by .98 and airborne momentum by .91; sprint doubles input.
    val nativeSprint = if (sprinting) 2.0 else 1.0
    return (blocksPerTick * 2.0 * (1.0 - 0.91) / (0.98 * nativeSprint)).coerceIn(0.0, 1.0).toFloat()
}
