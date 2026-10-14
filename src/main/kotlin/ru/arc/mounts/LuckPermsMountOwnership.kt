package ru.arc.mounts

import net.luckperms.api.LuckPerms
import net.luckperms.api.query.QueryMode
import net.luckperms.api.node.Node
import net.luckperms.api.node.types.PermissionNode
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LuckPermsMountOwnership(private val luckPerms: LuckPerms) : MountOwnership {
    override fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile {
        val user = luckPerms.userManager.getUser(subject.uniqueId)
        val permissionData = user?.let {
            val contextManager = luckPerms.contextManager
            val queryOptions = contextManager
                .queryOptionsBuilder(QueryMode.CONTEXTUAL)
                .context(contextManager.getContext(it).orElse(contextManager.staticContext))
                .build()
            it.cachedData.getPermissionData(queryOptions)
        }
        val owns = { permission: String -> permissionData?.checkPermission(permission)?.asBoolean() == true }
        val level =
            (1..mount.maxLevel)
                .filter { owns(mount.levelPermission(it)) }
                .maxOrNull() ?: 0
        val glowOwned = owns(mount.glowPermission)
        val glowDisabled = hasDirectPositivePermission(subject.uniqueId, mount.glowDisabledPermission)
        val ownedSkinIds = mount.skins.filter { owns(mount.skinPermission(it.id)) }.mapTo(linkedSetOf()) { it.id }
        val activeSkinId =
            mount.skins
                .firstOrNull { hasDirectPositivePermission(subject.uniqueId, mount.activeSkinPermission(it.id)) }
                ?.id
                ?.takeIf { it in ownedSkinIds }
                ?: MountDefinition.DEFAULT_SKIN_ID
        val ownedAbilityIds =
            mount.abilities.upgrades
                .filter { owns(mount.abilityPermission(it.id)) }
                .mapTo(linkedSetOf(), MountAbilityUpgradeDefinition::id)
        val ownedSizeIds =
            mount.sizeOptions
                .filter { it.grantOnly && owns(mount.sizeOwnershipPermission(it.id)) }
                .mapTo(linkedSetOf(), MountSizeOptionDefinition::id)
        val directNodes = luckPerms.userManager.getUser(subject.uniqueId)?.nodes.orEmpty()
        val selectedSpeedPercentage = directPositiveNumericSuffix(directNodes, mount.speedTuningPermissionPrefix)
        val selectedStepHeightHundredths = directPositiveNumericSuffix(directNodes, mount.stepHeightTuningPermissionPrefix)
        val selectedSizeId =
            directPositiveStringSuffix(
                directNodes,
                mount.sizeTuningPermissionPrefix,
                { candidate -> mount.availableSizeOptions(level, ownedSizeIds).any { it.id == candidate } },
            )
        val riderViewAutoHide =
            when (
                directPositiveStringSuffix(
                    directNodes,
                    mount.riderViewTuningPermissionPrefix,
                    { it == "auto-hide" || it == "always-visible" },
                )
            ) {
                "auto-hide" -> true
                "always-visible" -> false
                else -> null
            }
        return MountProfile(
            level = level,
            glowOwned = glowOwned,
            glowDisabled = glowDisabled,
            ownedSkinIds = ownedSkinIds,
            activeSkinId = activeSkinId,
            ownedAbilityIds = ownedAbilityIds,
            ownedSizeIds = ownedSizeIds,
            selectedSpeedPercentage = selectedSpeedPercentage,
            selectedStepHeightHundredths = selectedStepHeightHundredths,
            selectedSizeId = selectedSizeId,
            riderViewAutoHide = riderViewAutoHide,
        )
    }

    override fun favoriteMountId(playerId: UUID): String? =
        directPositiveStringSuffix(
            luckPerms.userManager.getUser(playerId)?.nodes.orEmpty(),
            MOUNT_FAVORITE_PERMISSION_PREFIX,
            MountDefinition::validId,
        )

    override fun setFavoriteMount(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        setExclusiveStatePermission(
            playerId,
            MOUNT_FAVORITE_PERMISSION_PREFIX,
            favoriteMountPermission(mount.id),
        )

    override fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void> {
        require(level in 1..mount.maxLevel) { "Invalid ${mount.id} level: $level" }
        return luckPerms.userManager.modifyUser(playerId) { user ->
            user.data().add(permission(mount.levelPermission(level)))
        }
    }

    override fun revokeLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void> {
        require(level in 1..mount.maxLevel) { "Invalid ${mount.id} level: $level" }
        return luckPerms.userManager.modifyUser(playerId) { user ->
            removePermission(user.data()::remove, user.nodes, mount.levelPermission(level))
        }
    }

    override fun grantAll(playerId: UUID, mounts: Collection<MountDefinition>): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(playerId) { user ->
            mounts.forEach { mount ->
                val permissions = buildList {
                    add(mount.levelPermission(mount.maxLevel))
                    add(mount.glowPermission)
                    mount.skins.forEach { add(mount.skinPermission(it.id)) }
                    mount.abilities.upgrades.forEach { add(mount.abilityPermission(it.id)) }
                    mount.sizeOptions.filter { it.grantOnly }.forEach { add(mount.sizeOwnershipPermission(it.id)) }
                }
                permissions.forEach { user.data().add(permission(it)) }
            }
        }

    override fun revokeAll(playerId: UUID, mounts: Collection<MountDefinition>): CompletableFuture<Int> =
        java.util.concurrent.atomic.AtomicInteger().let { removed ->
            luckPerms.userManager.modifyUser(playerId) { user ->
                val owned = user.nodes.filterIsInstance<PermissionNode>()
                    .filter { it.value && mounts.any { mount -> isMountOwnedPermission(mount, it.permission) } }
                owned.forEach(user.data()::remove)
                removed.set(owned.size)
            }.thenApply { removed.get() }
        }

    override fun grantGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(playerId) { user ->
            user.data().add(permission(mount.glowPermission))
            removePermission(user.data()::remove, user.nodes, mount.glowDisabledPermission)
        }

    override fun revokeGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(playerId) { user ->
            removePermission(user.data()::remove, user.nodes, mount.glowPermission)
            removePermission(user.data()::remove, user.nodes, mount.glowDisabledPermission)
        }

    override fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(playerId) { user ->
            removePermission(user.data()::remove, user.nodes, mount.glowDisabledPermission)
            if (!enabled) user.data().add(permission(mount.glowDisabledPermission))
        }

    override fun grantSkin(
        playerId: UUID,
        mount: MountDefinition,
        skin: MountSkinDefinition,
    ): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(playerId) { user ->
            user.data().add(permission(mount.skinPermission(skin.id)))
        }

    override fun revokeSkin(
        playerId: UUID,
        mount: MountDefinition,
        skin: MountSkinDefinition,
    ): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(playerId) { user ->
            removePermission(user.data()::remove, user.nodes, mount.skinPermission(skin.id))
            removePermission(user.data()::remove, user.nodes, mount.activeSkinPermission(skin.id))
        }

    override fun setActiveSkin(
        playerId: UUID,
        mount: MountDefinition,
        skinId: String,
    ): CompletableFuture<Void> {
        require(skinId == MountDefinition.DEFAULT_SKIN_ID || mount.skin(skinId) != null) {
            "Unknown ${mount.id} skin: $skinId"
        }
        return luckPerms.userManager.modifyUser(playerId) { user ->
            val activePermissions = mount.skins.map { mount.activeSkinPermission(it.id) }.toSet()
            user.nodes
                .filterIsInstance<PermissionNode>()
                .filter { it.permission in activePermissions }
                .forEach(user.data()::remove)
            if (skinId != MountDefinition.DEFAULT_SKIN_ID) {
                user.data().add(permission(mount.activeSkinPermission(skinId)))
            }
        }
    }

    override fun grantAbility(
        playerId: UUID,
        mount: MountDefinition,
        ability: MountAbilityUpgradeDefinition,
    ): CompletableFuture<Void> {
        require(mount.ability(ability.id) == ability) { "Unknown ${mount.id} ability: ${ability.id}" }
        return luckPerms.userManager.modifyUser(playerId) { user ->
            user.data().add(permission(mount.abilityPermission(ability.id)))
        }
    }

    override fun revokeAbility(
        playerId: UUID,
        mount: MountDefinition,
        ability: MountAbilityUpgradeDefinition,
    ): CompletableFuture<Void> {
        require(mount.ability(ability.id) == ability) { "Unknown ${mount.id} ability: ${ability.id}" }
        return luckPerms.userManager.modifyUser(playerId) { user ->
            removePermission(user.data()::remove, user.nodes, mount.abilityPermission(ability.id))
        }
    }

    override fun grantSize(
        playerId: UUID,
        mount: MountDefinition,
        size: MountSizeOptionDefinition,
    ): CompletableFuture<Void> {
        require(size.grantOnly && mount.sizeOptions.any { it == size }) { "Unknown grant-only ${mount.id} size: ${size.id}" }
        return luckPerms.userManager.modifyUser(playerId) { user ->
            user.data().add(permission(mount.sizeOwnershipPermission(size.id)))
        }
    }

    override fun revokeSize(
        playerId: UUID,
        mount: MountDefinition,
        size: MountSizeOptionDefinition,
    ): CompletableFuture<Void> {
        require(size.grantOnly && mount.sizeOptions.any { it == size }) { "Unknown grant-only ${mount.id} size: ${size.id}" }
        return luckPerms.userManager.modifyUser(playerId) { user ->
            removePermission(user.data()::remove, user.nodes, mount.sizeOwnershipPermission(size.id))
            removePermission(user.data()::remove, user.nodes, mount.sizeTuningPermission(size.id))
        }
    }

    override fun setSpeedTuning(
        playerId: UUID,
        mount: MountDefinition,
        percentage: Int,
    ): CompletableFuture<Void> =
        setExclusiveStatePermission(playerId, mount.speedTuningPermissionPrefix, mount.speedTuningPermission(percentage))

    override fun setStepHeightTuning(
        playerId: UUID,
        mount: MountDefinition,
        hundredths: Int,
    ): CompletableFuture<Void> =
        setExclusiveStatePermission(
            playerId,
            mount.stepHeightTuningPermissionPrefix,
            mount.stepHeightTuningPermission(hundredths),
        )

    override fun setSizeTuning(
        playerId: UUID,
        mount: MountDefinition,
        sizeId: String,
    ): CompletableFuture<Void> {
        require(mount.sizeOptions.any { it.id == sizeId }) { "Unknown ${mount.id} size option: $sizeId" }
        return setExclusiveStatePermission(
            playerId,
            mount.sizeTuningPermissionPrefix,
            mount.sizeTuningPermission(sizeId),
        )
    }

    override fun setRiderViewAutoHide(
        playerId: UUID,
        mount: MountDefinition,
        enabled: Boolean,
    ): CompletableFuture<Void> =
        setExclusiveStatePermission(
            playerId,
            mount.riderViewTuningPermissionPrefix,
            mount.riderViewTuningPermission(enabled),
        )

    override fun hasDirectPermission(playerId: UUID, permission: String): CompletableFuture<Boolean> {
        val loaded = luckPerms.userManager.getUser(playerId)
        if (loaded != null) return CompletableFuture.completedFuture(hasDirectPositivePermission(loaded.nodes, permission))
        return luckPerms.userManager.loadUser(playerId).thenApply { hasDirectPositivePermission(it.nodes, permission) }
    }

    override fun resolveUniqueId(playerName: String): CompletableFuture<UUID?> =
        luckPerms.userManager.lookupUniqueId(playerName)

    private fun removePermission(remove: (Node) -> Unit, nodes: Collection<Node>, permission: String) {
        nodes.filterIsInstance<PermissionNode>()
            .filter { it.permission == permission }
            .forEach(remove)
    }

    private fun setExclusiveStatePermission(
        playerId: UUID,
        prefix: String,
        permission: String,
    ): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(playerId) { user ->
            user.nodes
                .filterIsInstance<PermissionNode>()
                .filter { it.permission.startsWith(prefix) }
                .forEach(user.data()::remove)
            user.data().add(permission(permission))
        }

    private fun hasDirectPositivePermission(playerId: UUID, permission: String): Boolean =
        hasDirectPositivePermission(luckPerms.userManager.getUser(playerId)?.nodes.orEmpty(), permission)

    private fun permission(name: String): PermissionNode = PermissionNode.builder(name).value(true).build()

}

internal fun isMountOwnedPermission(mount: MountDefinition, permission: String): Boolean {
    val fixed =
        (1..mount.maxLevel).map(mount::levelPermission) +
            listOf(mount.glowPermission, mount.glowDisabledPermission) +
            mount.skins.flatMap { listOf(mount.skinPermission(it.id), mount.activeSkinPermission(it.id)) } +
            mount.abilities.upgrades.map { mount.abilityPermission(it.id) } +
            mount.sizeOptions.flatMap { listOf(mount.sizeOwnershipPermission(it.id), mount.sizeTuningPermission(it.id)) } +
            listOf(mount.riderViewTuningPermission(true), mount.riderViewTuningPermission(false))
    if (permission in fixed) return true
    if (permission == favoriteMountPermission(mount.id)) return true
    return (permission.startsWith(mount.speedTuningPermissionPrefix) &&
        permission.removePrefix(mount.speedTuningPermissionPrefix).toIntOrNull()?.let { it in 25..100 } == true) ||
        (permission.startsWith(mount.stepHeightTuningPermissionPrefix) &&
            permission.removePrefix(mount.stepHeightTuningPermissionPrefix).toIntOrNull()?.let { it in 60..400 } == true)
}

internal fun hasDirectPositivePermission(nodes: Collection<Node>, permission: String): Boolean =
    nodes.filterIsInstance<PermissionNode>().any { it.permission == permission && it.value }

internal fun directPositiveNumericSuffix(nodes: Collection<Node>, prefix: String): Int? =
    nodes.filterIsInstance<PermissionNode>()
        .asSequence()
        .filter { it.value && it.permission.startsWith(prefix) }
        .mapNotNull { it.permission.removePrefix(prefix).takeIf(String::isNotEmpty)?.toIntOrNull() }
        .filter { it > 0 }
        .minOrNull()

internal fun directPositiveStringSuffix(
    nodes: Collection<Node>,
    prefix: String,
    isValid: (String) -> Boolean,
): String? =
    nodes.filterIsInstance<PermissionNode>()
        .asSequence()
        .filter { it.value && it.permission.startsWith(prefix) }
        .map { it.permission.removePrefix(prefix) }
        .filter(isValid)
        .minOrNull()
