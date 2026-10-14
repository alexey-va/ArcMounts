package ru.arc.mounts

import net.luckperms.api.LuckPerms
import net.luckperms.api.model.user.User
import net.luckperms.api.node.types.PermissionNode
import net.luckperms.api.query.QueryOptions
import java.util.UUID
import java.util.concurrent.CompletableFuture

interface MountTransferOwnership {
    fun snapshot(playerId: UUID, mount: MountDefinition): CompletableFuture<List<String>>
    fun pack(record: MountTransferRecord, mount: MountDefinition): CompletableFuture<Void>
    fun apply(record: MountTransferRecord, mount: MountDefinition): CompletableFuture<Void>
    fun canReceive(playerId: UUID, mount: MountDefinition): CompletableFuture<Boolean>
}

/** Only direct, permanent, context-free ownership can leave an account. Group rewards remain with their group. */
class LuckPermsMountTransfers(private val luckPerms: LuckPerms) : MountTransferOwnership {
    override fun canReceive(playerId: UUID, mount: MountDefinition): CompletableFuture<Boolean> =
        luckPerms.userManager.loadUser(playerId).thenApply { user ->
            user.nodes.none { it.key.startsWith("arc.mounts.${mount.id}.") } &&
                (1..mount.maxLevel).none { user.cachedData.getPermissionData(QueryOptions.nonContextual())
                    .checkPermission(mount.levelPermission(it)).asBoolean() }
        }
    override fun snapshot(playerId: UUID, mount: MountDefinition): CompletableFuture<List<String>> =
        luckPerms.userManager.loadUser(playerId).thenApply { user -> snapshot(user, mount) }

    private fun snapshot(user: User, mount: MountDefinition): List<String> {
        val nodes = user.nodes.filterIsInstance<PermissionNode>().filter { it.permission.startsWith("arc.mounts.${mount.id}.") }
        require(nodes.isNotEmpty() && nodes.all { it.value && !it.hasExpiry() && it.contexts.isEmpty }) {
            "Only permanent direct mount ownership is transferable"
        }
        val result = nodes.map { it.permission }.distinct().sorted()
        require(result.any { it in (1..mount.maxLevel).map(mount::levelPermission) }) { "Mount is not owned" }
        val query = QueryOptions.nonContextual()
        val entitlements = (1..mount.maxLevel).map(mount::levelPermission) + mount.glowPermission +
            mount.skins.map { mount.skinPermission(it.id) } + mount.abilities.upgrades.map { mount.abilityPermission(it.id) } +
            mount.sizeOptions.filter { it.grantOnly }.map { mount.sizeOwnershipPermission(it.id) }
        require(user.getInheritedGroups(query).none { group ->
            entitlements.any { group.cachedData.getPermissionData(query).checkPermission(it).asBoolean() }
        }) { "Inherited mount ownership cannot be transferred" }
        require(result.all { allowed(mount, it) }) { "Unsupported mount entitlement" }
        return result
    }

    override fun pack(record: MountTransferRecord, mount: MountDefinition): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(record.issuer) { user ->
            val marker = "arc.mounts.transfer.issued.${record.id}"
            if (user.nodes.any { it.key == marker && it.value }) return@modifyUser
            require(snapshot(user, mount) == record.permissions) { "Mount changed before packing" }
            user.nodes.filterIsInstance<PermissionNode>()
                .filter { it.permission in record.permissions || it.permission == favoriteMountPermission(mount.id) }
                .forEach(user.data()::remove)
            user.data().add(PermissionNode.builder(marker).build())
        }.thenCompose { verify(record.issuer, "arc.mounts.transfer.issued.${record.id}", record, false) }

    override fun apply(record: MountTransferRecord, mount: MountDefinition): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(requireNotNull(record.recipient)) { user ->
            val marker = "arc.mounts.transfer.applied.${record.id}"
            if (user.nodes.any { it.key == marker && it.value }) return@modifyUser
            require(record.permissions.all { allowed(mount, it) }) { "Mount catalog no longer supports this certificate" }
            val query = QueryOptions.nonContextual()
            require((1..mount.maxLevel).none { user.cachedData.getPermissionData(query).checkPermission(mount.levelPermission(it)).asBoolean() }) {
                "Recipient already owns this mount"
            }
            // Existing disconnected cosmetics/settings must not be silently merged or overwritten.
            require(user.nodes.none { it.key.startsWith("arc.mounts.${mount.id}.") }) { "Recipient already has mount entitlements" }
            record.permissions.forEach { user.data().add(PermissionNode.builder(it).build()) }
            user.data().add(PermissionNode.builder(marker).build())
        }.thenCompose { verify(requireNotNull(record.recipient), "arc.mounts.transfer.applied.${record.id}", record, true) }

    private fun verify(playerId: UUID, marker: String, record: MountTransferRecord, applied: Boolean): CompletableFuture<Void> =
        luckPerms.userManager.loadUser(playerId).thenApply { user ->
            check(user.nodes.any { it.key == marker && it.value }) { "Transfer permission write is unproven" }
            val permanent = user.nodes.filterIsInstance<PermissionNode>()
                .filter { it.value && !it.hasExpiry() && it.contexts.isEmpty }.map { it.permission }.toSet()
            check(if (applied) permanent.containsAll(record.permissions) else permanent.none { it in record.permissions }) {
                "Transfer entitlement verification failed"
            }
            null
        }

    private fun allowed(mount: MountDefinition, permission: String): Boolean {
        val fixed = (1..mount.maxLevel).map(mount::levelPermission) + listOf(mount.glowPermission, mount.glowDisabledPermission) +
            mount.skins.flatMap { listOf(mount.skinPermission(it.id), mount.activeSkinPermission(it.id)) } +
            mount.abilities.upgrades.map { mount.abilityPermission(it.id) } +
            mount.sizeOptions.flatMap { listOf(mount.sizeOwnershipPermission(it.id), mount.sizeTuningPermission(it.id)) } +
            listOf(mount.riderViewTuningPermission(true), mount.riderViewTuningPermission(false))
        if (permission in fixed) return true
        return permission.removePrefix(mount.speedTuningPermissionPrefix).toIntOrNull()?.let { it in 25..100 } == true ||
            permission.removePrefix(mount.stepHeightTuningPermissionPrefix).toIntOrNull()?.let { it in 60..400 } == true
    }
}
