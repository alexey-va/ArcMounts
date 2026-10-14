package ru.arc.mounts

import org.bukkit.Sound
import org.bukkit.entity.Player
import ru.arc.util.TextUtils
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class MountSummonOutcome {
    SUCCESS,
    FAVORITE_NOT_SELECTED,
    FAVORITE_UNAVAILABLE,
    ALREADY_RIDING,
    ALREADY_IN_VEHICLE,
    WORLD_NOT_ALLOWED,
    WATER_REQUIRED,
    COOLDOWN,
    SPAWN_FAILED,
    TRANSFER_PENDING,
}

enum class MountFavoriteSelectionOutcome {
    SUCCESS,
    NOT_UNLOCKED,
    PERSISTENCE_FAILED,
}

class MountSummonService(
    private val configProvider: () -> MountModuleConfig,
    private val catalogProvider: () -> MountCatalog,
    private val ownership: MountOwnership,
    private val sessions: MountSessionController,
    private val isBusy: (UUID) -> Boolean = { false },
) {
    fun favoriteMountId(playerId: UUID): String? = ownership.favoriteMountId(playerId)

    fun selectFavorite(player: Player, mount: MountDefinition): CompletableFuture<MountFavoriteSelectionOutcome> {
        if (isBusy(player.uniqueId)) return CompletableFuture.completedFuture(MountFavoriteSelectionOutcome.PERSISTENCE_FAILED)
        if (!ownership.profile(subject(player), mount).unlocked) {
            return CompletableFuture.completedFuture(MountFavoriteSelectionOutcome.NOT_UNLOCKED)
        }
        return ownership.setFavoriteMount(player.uniqueId, mount).handle { _, failure ->
            if (failure == null) MountFavoriteSelectionOutcome.SUCCESS else MountFavoriteSelectionOutcome.PERSISTENCE_FAILED
        }
    }

    fun summonFavorite(player: Player): MountSummonOutcome {
        val favoriteId = favoriteMountId(player.uniqueId) ?: return MountSummonOutcome.FAVORITE_NOT_SELECTED
        val mount = catalogProvider()[favoriteId] ?: return MountSummonOutcome.FAVORITE_UNAVAILABLE
        return summon(player, mount)
    }

    fun summon(player: Player, mount: MountDefinition): MountSummonOutcome {
        if (isBusy(player.uniqueId)) return MountSummonOutcome.TRANSFER_PENDING
        val profile = ownership.profile(subject(player), mount)
        if (!profile.unlocked) return MountSummonOutcome.FAVORITE_UNAVAILABLE
        val config = configProvider()
        val result =
            sessions.spawn(
                player = player,
                definition = mount,
                settings = runtimeSettings(config, mount, profile),
                durationMillis = config.sessionDuration.toMillis(),
            )
        return result.toSummonOutcome()
    }

    fun refreshActive(player: Player, mount: MountDefinition): MountSessionUpdateResult {
        val profile = ownership.profile(subject(player), mount)
        if (!profile.unlocked) return MountSessionUpdateResult.NO_ACTIVE_SESSION
        return sessions.reconcileSettings(
            playerId = player.uniqueId,
            expectedMountId = mount.id,
            settings = runtimeSettings(configProvider(), mount, profile),
        )
    }

    fun sendFeedback(player: Player, outcome: MountSummonOutcome) {
        val (path, fallback) =
            when (outcome) {
                MountSummonOutcome.SUCCESS -> return
                MountSummonOutcome.TRANSFER_PENDING -> "transfer-pending" to "<yellow>Дождитесь завершения передачи маунта."
                MountSummonOutcome.FAVORITE_NOT_SELECTED ->
                    "favorite-not-selected" to "<yellow>Сначала выберите любимого маунта в /mount."
                MountSummonOutcome.FAVORITE_UNAVAILABLE ->
                    "favorite-unavailable" to "<red>Любимый маунт больше недоступен. Выберите другого в /mount."
                MountSummonOutcome.ALREADY_RIDING -> "already-riding" to "<red>Вы уже используете маунта."
                MountSummonOutcome.ALREADY_IN_VEHICLE ->
                    "already-in-vehicle" to "<red>Сначала покиньте текущее транспортное средство."
                MountSummonOutcome.WORLD_NOT_ALLOWED -> "world-not-allowed" to "<red>В этом мире маунты недоступны."
                MountSummonOutcome.WATER_REQUIRED ->
                    "water-required" to "<aqua>Водного маунта можно призвать только в воде."
                MountSummonOutcome.COOLDOWN -> "summon-cooldown" to "<yellow>Подождите немного перед повторным призывом."
                MountSummonOutcome.SPAWN_FAILED -> "spawn-failed" to "<red>Не удалось призвать маунта."
            }
        player.sendMessage(TextUtils.mm(configProvider().message(path, fallback), true))
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f)
    }

    private fun subject(player: Player) = MountPermissionSubject(player.uniqueId, player.name, player::hasPermission)

    private fun runtimeSettings(
        config: MountModuleConfig,
        mount: MountDefinition,
        profile: MountProfile,
    ): MountRuntimeSettings {
        val level = mount.level(profile.level)
        val sizeMultiplier = mount.effectiveSizeOption(profile.selectedSizeId, profile.level, profile.ownedSizeIds)?.multiplier ?: 1.0
        return MountRuntimeSettings(
            speed = config.tuning.speed(level.speed, profile.selectedSpeedPercentage),
            walkingStepHeight = config.tuning.stepHeight(profile.level, profile.selectedStepHeightHundredths),
            handlingMultiplier = level.handlingMultiplier,
            sprintMultiplier = level.sprintMultiplier,
            scaleMultiplier = level.scaleMultiplier * sizeMultiplier,
            skin = mount.skin(profile.activeSkinId),
            glow = profile.glowEnabled,
            abilityUpgrades = mount.abilities.upgrades.filter { profile.ownsAbility(it.id) },
            riderViewAutoHide = mount.effectiveRiderViewAutoHide(profile),
        )
    }
}

private fun MountSpawnResult.toSummonOutcome(): MountSummonOutcome =
    when (this) {
        MountSpawnResult.SUCCESS -> MountSummonOutcome.SUCCESS
        MountSpawnResult.ALREADY_RIDING -> MountSummonOutcome.ALREADY_RIDING
        MountSpawnResult.ALREADY_IN_VEHICLE -> MountSummonOutcome.ALREADY_IN_VEHICLE
        MountSpawnResult.WORLD_NOT_ALLOWED -> MountSummonOutcome.WORLD_NOT_ALLOWED
        MountSpawnResult.WATER_REQUIRED -> MountSummonOutcome.WATER_REQUIRED
        MountSpawnResult.COOLDOWN -> MountSummonOutcome.COOLDOWN
        MountSpawnResult.INVALID_ENTITY,
        MountSpawnResult.SPAWN_FAILED,
        -> MountSummonOutcome.SPAWN_FAILED
    }
