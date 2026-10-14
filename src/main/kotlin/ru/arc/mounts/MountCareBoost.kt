package ru.arc.mounts

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.TaskScheduler
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable, cross-server record of one daily mount-care entitlement. */
internal data class MountCareBoostRecord(
    val requestId: UUID,
    val grantedAtMillis: Long,
    val expiresAtMillis: Long,
    val nextClaimAtMillis: Long,
    val multiplier: Double,
) {
    init {
        validate()
    }

    fun validate() {
        requireNotNull(requestId) { "Mount care boost request id is missing" }
        require(grantedAtMillis >= 0L) { "Mount care boost grant time must not be negative" }
        require(expiresAtMillis > grantedAtMillis) { "Mount care boost expiry must follow grant time" }
        require(nextClaimAtMillis >= expiresAtMillis) { "Mount care boost cooldown must follow expiry" }
        require(multiplier.isFinite() && multiplier >= 1.0 && multiplier <= 2.0) {
            "Mount care boost multiplier must be finite and between 1.0 and 2.0"
        }
    }
}

internal sealed interface MountCareBoostClaimResult {
    val record: MountCareBoostRecord

    data class Granted(override val record: MountCareBoostRecord) : MountCareBoostClaimResult

    data class AlreadyGranted(
        override val record: MountCareBoostRecord,
        val sameRequest: Boolean,
    ) : MountCareBoostClaimResult
}

internal data class MountCareBoostStatus(
    val record: MountCareBoostRecord?,
    val active: Boolean,
    val remainingMillis: Long,
    val nextClaimInMillis: Long,
)

/** Durable atomic boundary. Implementations must write the claim and effect record together. */
internal interface MountCareBoostStore : AutoCloseable {
    fun claimOrRead(
        playerId: UUID,
        requestId: UUID,
        nowMillis: Long,
        boostDurationMillis: Long,
        cooldownMillis: Long,
        speedMultiplier: Double,
    ): CompletableFuture<MountCareBoostClaimResult>

    fun read(playerId: UUID): CompletableFuture<MountCareBoostRecord?>

    override fun close() = Unit
}

/** Fail-closed store used when cross-server storage is unavailable. */
internal object UnavailableMountCareBoostStore : MountCareBoostStore {
    private fun <T> unavailable(): CompletableFuture<T> =
        CompletableFuture.failedFuture(IllegalStateException("Mount care boost storage is unavailable"))

    override fun claimOrRead(
        playerId: UUID,
        requestId: UUID,
        nowMillis: Long,
        boostDurationMillis: Long,
        cooldownMillis: Long,
        speedMultiplier: Double,
    ): CompletableFuture<MountCareBoostClaimResult> = unavailable()

    override fun read(playerId: UUID): CompletableFuture<MountCareBoostRecord?> = unavailable()
}

/** Redis hash implementation using the shared bounded CAS primitive. */
internal class RedisMountCareBoostStore(
    private val redis: RedisOperations,
    gson: Gson = MountJson.gson,
) : MountCareBoostStore {
    private val codec =
        BoundedJsonCodec(
            gson,
            MountCareBoostRecord::class.java,
            JsonObjectContract(
                allowedFields = setOf(
                    "requestId",
                    "grantedAtMillis",
                    "expiresAtMillis",
                    "nextClaimAtMillis",
                    "multiplier",
                ),
                fieldContracts = mapOf(
                    "requestId" to UuidJsonContract,
                    "grantedAtMillis" to LongJsonContract,
                    "expiresAtMillis" to LongJsonContract,
                    "nextClaimAtMillis" to LongJsonContract,
                    "multiplier" to FiniteNumberJsonContract,
                ),
            ),
            JsonResourceBounds(
                maxCharacters = 768,
                maxDepth = 2,
                maxContainerEntries = 8,
                maxTotalNodes = 16,
                maxStringCharacters = 64,
            ),
        ) { it.validate() }

    private val updater = RedisHashUpdater(redis, STORAGE_KEY, codec)

    override fun claimOrRead(
        playerId: UUID,
        requestId: UUID,
        nowMillis: Long,
        boostDurationMillis: Long,
        cooldownMillis: Long,
        speedMultiplier: Double,
    ): CompletableFuture<MountCareBoostClaimResult> {
        require(nowMillis >= 0L) { "Mount care boost time must not be negative" }
        require(boostDurationMillis > 0L) { "Mount care boost duration must be positive" }
        require(cooldownMillis >= boostDurationMillis) {
            "Mount care boost cooldown must not end before the active entitlement"
        }
        require(speedMultiplier.isFinite() && speedMultiplier >= 1.0 && speedMultiplier <= 2.0) {
            "Mount care boost multiplier must be finite and between 1.0 and 2.0"
        }
        val expiresAt = Math.addExact(nowMillis, boostDurationMillis)
        val nextClaimAt = Math.addExact(nowMillis, cooldownMillis)
        return updater.update(playerId.toString()) { current ->
            when {
                // A retry of the same quest session is idempotent even after the
                // rolling window has elapsed: never rewrite its expiry.
                current != null && current.requestId == requestId -> RedisHashDecision.Reject
                current != null && nowMillis < current.nextClaimAtMillis -> RedisHashDecision.Reject
                else ->
                    RedisHashDecision.Write(
                        MountCareBoostRecord(
                            requestId = requestId,
                            grantedAtMillis = nowMillis,
                            expiresAtMillis = expiresAt,
                            nextClaimAtMillis = nextClaimAt,
                            multiplier = speedMultiplier,
                        ),
                    )
            }
        }.thenApply { result ->
            when (result) {
                is RedisHashUpdateResult.Changed ->
                    MountCareBoostClaimResult.Granted(requireNotNull(result.after))
                is RedisHashUpdateResult.Rejected -> {
                    val current = requireNotNull(result.current) { "Mount care boost rejection lost its current record" }
                    MountCareBoostClaimResult.AlreadyGranted(current, current.requestId == requestId)
                }
                is RedisHashUpdateResult.Unchanged ->
                    MountCareBoostClaimResult.AlreadyGranted(result.current, result.current.requestId == requestId)
                is RedisHashUpdateResult.Contended ->
                    error("Mount care boost update contended after ${result.attempts} attempts")
            }
        }
    }

    override fun read(playerId: UUID): CompletableFuture<MountCareBoostRecord?> =
        redis.loadMapEntries(STORAGE_KEY, playerId.toString()).thenApply { values ->
            require(values.size == 1) { "Unexpected mount care boost lookup result" }
            values.single()?.let(codec::decode)
        }

    private companion object {
        const val STORAGE_KEY = "arc.mount-care-boost.v1"
    }
}

internal data class MountCareBoostPolicy(
    val speedMultiplier: Double = 1.05,
    val durationMillis: Long = 2L * 60L * 60L * 1_000L,
    val cooldownMillis: Long = 24L * 60L * 60L * 1_000L,
) {
    init {
        require(speedMultiplier.isFinite() && speedMultiplier >= 1.0 && speedMultiplier <= 2.0)
        require(durationMillis > 0L && cooldownMillis >= durationMillis)
    }

    companion object {
        fun load(dataPath: Path): MountCareBoostPolicy {
            val config = ConfigManager.of(dataPath, "modules/mount-care-boost.yml")
            return MountCareBoostPolicy(
                speedMultiplier = config.double("speed-multiplier", 1.05),
                durationMillis = config.duration("duration", Duration.ofHours(2)).toMillis(),
                cooldownMillis = config.duration("cooldown", Duration.ofHours(24)).toMillis(),
            )
        }
    }
}

/**
 * Module-owned cache and API. Redis is read on claim/status calls, never from
 * movement ticks; cached records are enough for wall-clock expiry checks.
 */
internal class MountCareBoostService(
    private val store: MountCareBoostStore,
    private val policy: MountCareBoostPolicy = MountCareBoostPolicy(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val cache = ConcurrentHashMap<UUID, MountCareBoostRecord>()
    private val playerTokens = ConcurrentHashMap<UUID, CacheToken>()
    private val closed = AtomicBoolean(false)
    private val cacheLock = Any()

    fun claim(playerId: UUID, requestId: UUID): CompletableFuture<MountCareBoostClaimResult> {
        val token = try {
            tokenFor(playerId)
        } catch (failure: IllegalStateException) {
            return CompletableFuture.failedFuture(failure)
        }
        val now = clockMillis()
        return store
            .claimOrRead(
                playerId = playerId,
                requestId = requestId,
                nowMillis = now,
                boostDurationMillis = policy.durationMillis,
                cooldownMillis = policy.cooldownMillis,
                speedMultiplier = policy.speedMultiplier,
            ).thenApply { result ->
                mergeCacheIfUsable(playerId, token, result.record, "claiming")
                result
            }
    }

    fun status(playerId: UUID): CompletableFuture<MountCareBoostStatus> {
        val token = try {
            tokenFor(playerId)
        } catch (failure: IllegalStateException) {
            return CompletableFuture.failedFuture(failure)
        }
        return store.read(playerId).thenApply { record ->
            if (record != null) mergeCacheIfUsable(playerId, token, record, "reading")
            else synchronized(cacheLock) { checkUsable(playerId, token, "reading") }
            mountCareBoostStatus(cache[playerId], clockMillis())
        }
    }

    fun cachedStatus(playerId: UUID, nowMillis: Long = clockMillis()): MountCareBoostStatus =
        mountCareBoostStatus(cache[playerId], nowMillis)

    /** Hydrate one online player after start/join; movement only reads [cachedStatus]. */
    fun hydrate(playerId: UUID): CompletableFuture<MountCareBoostStatus> = status(playerId)

    fun forget(playerId: UUID) {
        synchronized(cacheLock) {
            cache.remove(playerId)
            playerTokens.remove(playerId)?.invalidated?.set(true)
        }
    }

    private fun tokenFor(playerId: UUID): CacheToken = synchronized(cacheLock) {
        check(!closed.get()) { "Mount care boost service is closed" }
        playerTokens[playerId] ?: run {
            while (playerTokens.size >= MAX_TRACKED_PLAYERS) {
                val victim = playerTokens.entries.firstOrNull { it.key != playerId } ?: break
                victim.value.invalidated.set(true)
                playerTokens.remove(victim.key, victim.value)
                cache.remove(victim.key)
            }
            CacheToken().also { playerTokens[playerId] = it }
        }
    }

    private fun checkUsable(playerId: UUID, token: CacheToken, operation: String) {
        check(!closed.get() && !token.invalidated.get() && playerTokens[playerId] === token) {
            "Mount care boost service was closed or player left while $operation"
        }
    }

    private fun mergeCacheIfUsable(
        playerId: UUID,
        token: CacheToken,
        incoming: MountCareBoostRecord,
        operation: String,
    ) = synchronized(cacheLock) {
        checkUsable(playerId, token, operation)
        val current = cache[playerId]
        if (current == null || incoming.grantedAtMillis >= current.grantedAtMillis) {
            cache[playerId] = incoming
        }
        while (cache.size > MAX_CACHED_PLAYERS) {
            val oldest = cache.entries.minByOrNull { it.value.grantedAtMillis } ?: break
            cache.remove(oldest.key, oldest.value)
        }
    }

    override fun close() {
        synchronized(cacheLock) {
            if (!closed.compareAndSet(false, true)) return
            playerTokens.values.forEach { it.invalidated.set(true) }
            playerTokens.clear()
            cache.clear()
        }
        store.close()
    }

    private class CacheToken {
        val invalidated = AtomicBoolean(false)
    }
}

/** Hydrates after join without allowing a stale module callback to touch a new runtime. */
internal class MountCareBoostHydrationListener(
    private val plugin: JavaPlugin,
    private val scheduler: TaskScheduler,
    private val service: MountCareBoostService,
    private val isCurrent: () -> Boolean,
    private val onHydrated: (UUID) -> Unit,
) : Listener {
    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val playerId = event.player.uniqueId
        service.hydrate(playerId).whenComplete { _, failure ->
            if (failure != null) return@whenComplete
            scheduler.runLater(1L, Runnable {
                if (isCurrent()) onHydrated(playerId)
            })
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        service.forget(event.player.uniqueId)
    }

    fun close() {
        HandlerList.unregisterAll(this)
    }
}

internal fun mountCareBoostStatus(record: MountCareBoostRecord?, nowMillis: Long): MountCareBoostStatus {
    if (record == null) return MountCareBoostStatus(null, active = false, remainingMillis = 0L, nextClaimInMillis = 0L)
    return MountCareBoostStatus(
        record = record,
        active = nowMillis < record.expiresAtMillis,
        remainingMillis = (record.expiresAtMillis - nowMillis).coerceAtLeast(0L),
        nextClaimInMillis = (record.nextClaimAtMillis - nowMillis).coerceAtLeast(0L),
    )
}

internal fun applyMountCareSpeed(baseSpeed: Double, status: MountCareBoostStatus): Double {
    require(baseSpeed.isFinite() && baseSpeed > 0.0) { "Mount base speed must be positive and finite" }
    return if (status.active) baseSpeed * checkNotNull(status.record).multiplier else baseSpeed
}

private object UuidJsonContract : ru.arc.redis.safety.JsonRootContract {
    override fun validate(value: JsonElement) {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Mount care boost request id must be a string" }
        require(runCatching { UUID.fromString(value.asString) }.isSuccess) {
            "Mount care boost request id must be a UUID"
        }
    }
}

private object LongJsonContract : ru.arc.redis.safety.JsonRootContract {
    override fun validate(value: JsonElement) {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Mount care boost time must be an integer" }
        val raw = value.asString
        require(raw.matches(Regex("0|[1-9][0-9]{0,18}"))) { "Mount care boost time must be a non-negative integer" }
        require(runCatching { raw.toLong() }.isSuccess) { "Mount care boost time is out of range" }
    }
}

private object FiniteNumberJsonContract : ru.arc.redis.safety.JsonRootContract {
    override fun validate(value: JsonElement) {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Mount care boost multiplier must be numeric" }
        require(value.asDouble.isFinite()) { "Mount care boost multiplier must be finite" }
    }
}

private const val MAX_CACHED_PLAYERS = 10_000
private const val MAX_TRACKED_PLAYERS = 10_000
