package ru.arc.mounts

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class MountRewardRejection {
    MODULE_UNAVAILABLE,
    SHUTDOWN,
    UNKNOWN_MOUNT,
    PLAYER_OFFLINE,
    BUSY,
    ALREADY_PENDING,
    OWNERSHIP_READ_FAILED,
    GRANT_UNCERTAIN,
}

sealed interface MountRewardResult {
    val mountId: String?

    data class Granted(override val mountId: String) : MountRewardResult

    data class AlreadyOwned(
        override val mountId: String,
        val level: Int,
    ) : MountRewardResult

    data class Rejected(
        override val mountId: String?,
        val reason: MountRewardRejection,
    ) : MountRewardResult
}

/**
 * The catalog-facing bridge to native mount ownership.
 *
 * It deliberately grants only level one and returns a preview item rather than a
 * transferable certificate.  The preview is an icon only; ownership is persisted
 * by the configured [MountOwnership] implementation.
 */
internal class MountRewardGrant {
    private data class ActiveState(
        val catalog: MountCatalog,
        val ownership: MountOwnership,
        val busy: (UUID) -> Boolean,
    )

    private data class PendingKey(val playerId: UUID, val mountId: String)

    private data class Pending(
        val state: ActiveState,
        val key: PendingKey,
        val mount: MountDefinition,
        val result: CompletableFuture<MountRewardResult>,
    )

    private val lock = Any()
    private val pending = HashMap<PendingKey, Pending>()
    private var active: ActiveState? = null
    private var closed = false

    internal fun activate(
        catalog: MountCatalog,
        ownership: MountOwnership,
        busy: (UUID) -> Boolean = { false },
    ) {
        val stale = synchronized(lock) {
            val previous = pending.values.toList()
            pending.clear()
            active = ActiveState(catalog, ownership, busy)
            closed = false
            previous
        }
        stale.forEach { it.result.complete(rejected(it.mount.id, MountRewardRejection.SHUTDOWN)) }
    }

    internal fun close() {
        val inFlight = synchronized(lock) {
            active = null
            closed = true
            val previous = pending.values.toList()
            pending.clear()
            previous
        }
        inFlight.forEach { it.result.complete(rejected(it.mount.id, MountRewardRejection.SHUTDOWN)) }
    }

    fun preview(id: String): ItemStack? {
        val mount = synchronized(lock) { active?.catalog?.get(id) } ?: return null
        val material = Material.matchMaterial(mount.iconMaterial) ?: return null
        return ItemStack(material)
    }

    fun grant(player: Player, id: String): CompletableFuture<MountRewardResult> {
        val state = synchronized(lock) { active }
            ?: return CompletableFuture.completedFuture(unavailable())
        val mount = state.catalog[id]
            ?: return CompletableFuture.completedFuture(rejected(id, MountRewardRejection.UNKNOWN_MOUNT))
        if (!player.isOnline) {
            return CompletableFuture.completedFuture(rejected(mount.id, MountRewardRejection.PLAYER_OFFLINE))
        }

        val key = PendingKey(player.uniqueId, mount.id)
        val reservation = Pending(state, key, mount, CompletableFuture())
        val duplicate = synchronized(lock) {
            when {
                active !== state ->
                    rejected(
                        mount.id,
                        if (closed) MountRewardRejection.SHUTDOWN else MountRewardRejection.MODULE_UNAVAILABLE,
                    )
                pending.containsKey(key) -> rejected(mount.id, MountRewardRejection.ALREADY_PENDING)
                else -> {
                    pending[key] = reservation
                    null
                }
            }
        }
        if (duplicate != null) return CompletableFuture.completedFuture(duplicate)

        val isBusy = try {
            state.busy(player.uniqueId)
        } catch (failure: Exception) {
            warn(
                "Mount reward busy check failed: mount={} player={}",
                mount.id,
                player.uniqueId,
                failure,
            )
            complete(reservation, rejected(mount.id, MountRewardRejection.BUSY))
            return reservation.result
        }
        if (isBusy) {
            complete(reservation, rejected(mount.id, MountRewardRejection.BUSY))
            return reservation.result
        }

        val profile = try {
            state.ownership.profile(
                MountPermissionSubject(player.uniqueId, player.name, player::hasPermission),
                mount,
            )
        } catch (failure: Exception) {
            warn(
                "Mount reward ownership read failed: mount={} player={}",
                mount.id,
                player.uniqueId,
                failure,
            )
            complete(reservation, rejected(mount.id, MountRewardRejection.OWNERSHIP_READ_FAILED))
            return reservation.result
        }
        if (profile.level >= 1) {
            complete(reservation, MountRewardResult.AlreadyOwned(mount.id, profile.level))
            return reservation.result
        }
        if (!isCurrent(reservation)) return reservation.result

        val nativeGrant = try {
            state.ownership.grantLevel(player.uniqueId, mount, 1)
        } catch (failure: Exception) {
            warn(
                "Mount reward native write is uncertain: mount={} player={}",
                mount.id,
                player.uniqueId,
                failure,
            )
            complete(reservation, rejected(mount.id, MountRewardRejection.GRANT_UNCERTAIN), release = false)
            return reservation.result
        }
        nativeGrant.whenComplete { _, failure ->
            if (failure == null) {
                complete(reservation, MountRewardResult.Granted(mount.id))
            } else {
                warn(
                    "Mount reward native write is uncertain: mount={} player={}",
                    mount.id,
                    player.uniqueId,
                    failure,
                )
                complete(reservation, rejected(mount.id, MountRewardRejection.GRANT_UNCERTAIN), release = false)
            }
        }
        return reservation.result
    }

    internal fun isBusy(playerId: UUID): Boolean = synchronized(lock) {
        pending.keys.any { it.playerId == playerId }
    }

    private fun complete(reservation: Pending, outcome: MountRewardResult, release: Boolean = true) {
        val accepted = synchronized(lock) {
            if (pending[reservation.key] !== reservation) {
                false
            } else {
                if (release) pending.remove(reservation.key)
                true
            }
        }
        if (accepted) reservation.result.complete(outcome)
    }

    private fun isCurrent(reservation: Pending): Boolean = synchronized(lock) {
        active === reservation.state && pending[reservation.key] === reservation
    }

    private fun unavailable(): MountRewardResult.Rejected = synchronized(lock) {
        rejected(null, if (closed) MountRewardRejection.SHUTDOWN else MountRewardRejection.MODULE_UNAVAILABLE)
    }

    private fun rejected(mountId: String?, reason: MountRewardRejection) =
        MountRewardResult.Rejected(mountId, reason)
}
