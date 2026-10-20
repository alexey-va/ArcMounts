package ru.arc.mounts

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.api.MountQuestEligibility
import ru.ruscrafting.ranks.api.RankQuestApi
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.floor
import kotlin.math.sqrt

internal data class MountRidePoint(val worldId: UUID, val x: Double, val y: Double, val z: Double) {
    fun valid(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

internal data class MountRideBatch(val playerId: UUID, val eventId: String, val blocks: Long)

/** Session-local 3D movement; discontinuities never become distance and partial blocks carry. */
internal class MountRideDistance(private val sessionId: UUID, private val playerId: UUID, startedAtMillis: Long) {
    private var previous: MountRidePoint? = null
    private var residual = 0.0
    private var pendingBlocks = 0L
    private var sequence = 0L
    private var lastEmissionAtMillis = startedAtMillis

    fun observe(point: MountRidePoint, nowMillis: Long): MountRideBatch? {
        val from = previous
        previous = point.takeIf(MountRidePoint::valid)
        if (from != null && previous != null && from.worldId == point.worldId) {
            val dx = point.x - from.x; val dy = point.y - from.y; val dz = point.z - from.z
            val step = sqrt(dx * dx + dy * dy + dz * dz)
            if (step.isFinite() && step <= 16.0) {
                val total = residual + step
                val whole = floor(total).toLong()
                residual = total - whole
                pendingBlocks += whole
            } else residual = 0.0
        } else residual = 0.0
        return if (nowMillis - lastEmissionAtMillis >= 5_000L) flush(nowMillis) else null
    }

    fun flush(nowMillis: Long): MountRideBatch? {
        lastEmissionAtMillis = nowMillis
        if (pendingBlocks == 0L) return null
        return MountRideBatch(playerId, "$sessionId:${++sequence}", pendingBlocks).also { pendingBlocks = 0L }
    }

    fun discontinuity() { previous = null; residual = 0.0 }
}

/** A missing cached LP user is unknown, never proof that the account owns no mounts. */
internal fun ownedMountQuestEligibility(
    mounts: Collection<MountDefinition>?, ownership: MountOwnership?, userCached: Boolean, playerId: UUID,
): Optional<Boolean> {
    if (mounts == null || ownership == null || !userCached) return Optional.empty()
    return runCatching {
        val subject = MountPermissionSubject(playerId, "") { false }
        Optional.of(mounts.any { ownership.profile(subject, it).unlocked })
    }.getOrElse { Optional.empty() }
}

/** Optional ArcRanks contract, registered only while its runtime owner is enabled. */
internal class MountQuestIntegration(
    private val tasks: LifecycleTaskScope,
    private val provider: () -> RankQuestApi?,
    private val logger: Logger,
) {
    fun record(batch: MountRideBatch) {
        val token = runCatching(tasks::token).getOrNull() ?: return
        fun send(attempt: Int) {
            if (!tasks.isCurrent(token)) return
            val write = runCatching {
                val service = provider()
                if (service == null && attempt == 0) return
                checkNotNull(service) { "ArcRanks quest service became unavailable" }
                    .record("mounts_ride", batch.eventId, batch.playerId, "mount.travel", batch.blocks)
            }.getOrElse { CompletableFuture.failedFuture(it) }
            write.whenComplete { _, failure ->
                if (failure != null) {
                    val retry = if (attempt < 3) runCatching {
                        tasks.runLater(token, 100L * (attempt + 1)) { send(attempt + 1) }
                    }.getOrNull() else null
                    if (retry == null) logger.log(Level.WARNING,
                        "Could not retain mount quest event event=${batch.eventId} blocks=${batch.blocks}", failure)
                }
            }
        }
        send(0)
    }

    companion object {
        fun registerEligibility(plugin: Plugin, lookup: (UUID) -> Optional<Boolean>): AutoCloseable {
            val provider = MountQuestEligibility { playerId ->
                if (Bukkit.isPrimaryThread()) lookup(playerId) else Optional.empty()
            }
            plugin.server.servicesManager.register(MountQuestEligibility::class.java, provider, plugin, ServicePriority.Normal)
            return AutoCloseable { plugin.server.servicesManager.unregister(MountQuestEligibility::class.java, provider) }
        }
    }
}
