package ru.arc.mounts

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import java.util.UUID

class MountCareBoostTest : StringSpec({
    "first claim is durable and a duplicate request keeps its original expiry" {
        val store = RedisMountCareBoostStore(InMemoryRedis(), Gson())
        val player = UUID.randomUUID()
        val request = UUID.randomUUID()
        val first = store.claimOrRead(player, request, NOW, BOOST_MILLIS, COOLDOWN_MILLIS, 1.05).join()
        val replay = store.claimOrRead(player, request, NOW + COOLDOWN_MILLIS + 1, BOOST_MILLIS, COOLDOWN_MILLIS, 1.25).join()

        first shouldBe MountCareBoostClaimResult.Granted(
            MountCareBoostRecord(
                requestId = request,
                grantedAtMillis = NOW,
                expiresAtMillis = NOW + BOOST_MILLIS,
                nextClaimAtMillis = NOW + COOLDOWN_MILLIS,
                multiplier = 1.05,
            ),
        )
        replay shouldBe MountCareBoostClaimResult.AlreadyGranted(
            record = (first as MountCareBoostClaimResult.Granted).record,
            sameRequest = true,
        )
    }

    "a different request is rejected during the rolling cooldown and accepted afterwards" {
        val store = RedisMountCareBoostStore(InMemoryRedis(), Gson())
        val player = UUID.randomUUID()
        val firstRequest = UUID.randomUUID()
        val otherRequest = UUID.randomUUID()
        val first = store.claimOrRead(player, firstRequest, NOW, BOOST_MILLIS, COOLDOWN_MILLIS, 1.05).join()

        val duplicate = store.claimOrRead(player, otherRequest, NOW + BOOST_MILLIS, BOOST_MILLIS, COOLDOWN_MILLIS, 1.10).join()
        duplicate shouldBe MountCareBoostClaimResult.AlreadyGranted(
            record = (first as MountCareBoostClaimResult.Granted).record,
            sameRequest = false,
        )

        val next = store.claimOrRead(player, otherRequest, NOW + COOLDOWN_MILLIS, BOOST_MILLIS, COOLDOWN_MILLIS, 1.10).join()
        next shouldBe MountCareBoostClaimResult.Granted(
            MountCareBoostRecord(
                requestId = otherRequest,
                grantedAtMillis = NOW + COOLDOWN_MILLIS,
                expiresAtMillis = NOW + COOLDOWN_MILLIS + BOOST_MILLIS,
                nextClaimAtMillis = NOW + (COOLDOWN_MILLIS * 2),
                multiplier = 1.10,
            ),
        )
    }

    "corrupt durable state fails closed instead of becoming a fresh grant" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        redis.setHash("arc.mount-care-boost.v1", mapOf(player.toString() to "{bad"))
        val store = RedisMountCareBoostStore(redis, Gson())

        runCatching {
            store.claimOrRead(player, UUID.randomUUID(), NOW, BOOST_MILLIS, COOLDOWN_MILLIS, 1.05).join()
        }.isFailure shouldBe true
        redis.getHash("arc.mount-care-boost.v1")[player.toString()] shouldBe "{bad"
    }

    "well-formed but invalid records fail both read and claim without replacement" {
        val malformed = listOf(
            """{"requestId":null,"grantedAtMillis":1700000000000,"expiresAtMillis":1700007200000,"nextClaimAtMillis":1700086400000,"multiplier":1.05}""",
            """{"requestId":"${UUID.randomUUID()}","grantedAtMillis":-1,"expiresAtMillis":1700007200000,"nextClaimAtMillis":1700086400000,"multiplier":1.05}""",
            """{"requestId":"${UUID.randomUUID()}","grantedAtMillis":1700007200000,"expiresAtMillis":1700000000000,"nextClaimAtMillis":1700086400000,"multiplier":1.05}""",
            """{"requestId":"${UUID.randomUUID()}","grantedAtMillis":1700000000000,"expiresAtMillis":1700007200000,"nextClaimAtMillis":1700086400000,"multiplier":999.0}""",
        )
        malformed.forEach { payload ->
            val redis = InMemoryRedis()
            val player = UUID.randomUUID()
            redis.setHash("arc.mount-care-boost.v1", mapOf(player.toString() to payload))
            val store = RedisMountCareBoostStore(redis, Gson())

            runCatching { store.read(player).join() }.isFailure shouldBe true
            runCatching {
                store.claimOrRead(player, UUID.randomUUID(), NOW, BOOST_MILLIS, COOLDOWN_MILLIS, 1.05).join()
            }.isFailure shouldBe true
            redis.getHash("arc.mount-care-boost.v1")[player.toString()] shouldBe payload
        }
    }

    "status is wall-clock based and speed multiplier is applied once from the base speed" {
        val record = MountCareBoostRecord(
            requestId = UUID.randomUUID(),
            grantedAtMillis = NOW,
            expiresAtMillis = NOW + BOOST_MILLIS,
            nextClaimAtMillis = NOW + COOLDOWN_MILLIS,
            multiplier = 1.05,
        )

        mountCareBoostStatus(record, NOW + BOOST_MILLIS - 1).active shouldBe true
        mountCareBoostStatus(record, NOW + BOOST_MILLIS).active shouldBe false
        applyMountCareSpeed(0.4, mountCareBoostStatus(record, NOW + 1)).shouldBeExactly(0.4 * 1.05)
        applyMountCareSpeed(0.4, mountCareBoostStatus(record, NOW + BOOST_MILLIS)).shouldBeExactly(0.4)
    }

    "a delayed stale hydration response cannot erase a newer claim cache" {
        val player = UUID.randomUUID()
        val old = MountCareBoostRecord(
            requestId = UUID.randomUUID(),
            grantedAtMillis = NOW,
            expiresAtMillis = NOW + BOOST_MILLIS,
            nextClaimAtMillis = NOW + COOLDOWN_MILLIS,
            multiplier = 1.05,
        )
        val newer = old.copy(
            requestId = UUID.randomUUID(),
            grantedAtMillis = NOW + 1,
            expiresAtMillis = NOW + 1 + BOOST_MILLIS,
            nextClaimAtMillis = NOW + 1 + COOLDOWN_MILLIS,
            multiplier = 1.10,
        )
        val store = DelayedMountCareBoostStore(newer)
        val service = MountCareBoostService(store, clockMillis = { NOW + 1 })

        val staleRead = service.status(player)
        service.claim(player, newer.requestId).join().record shouldBe newer
        store.readResult.complete(old)
        staleRead.join().record shouldBe newer
        service.cachedStatus(player, NOW + 1).record shouldBe newer
    }

    "closing the service fences an in-flight read and quit eviction drops cache" {
        val player = UUID.randomUUID()
        val record = MountCareBoostRecord(
            requestId = UUID.randomUUID(),
            grantedAtMillis = NOW,
            expiresAtMillis = NOW + BOOST_MILLIS,
            nextClaimAtMillis = NOW + COOLDOWN_MILLIS,
            multiplier = 1.05,
        )
        val store = DelayedMountCareBoostStore(record)
        val service = MountCareBoostService(store, clockMillis = { NOW })

        service.claim(player, record.requestId).join()
        service.cachedStatus(player).record shouldBe record
        service.forget(player)
        service.cachedStatus(player).record shouldBe null

        val staleRead = service.status(player)
        service.close()
        store.readResult.complete(record)
        runCatching { staleRead.join() }.isFailure shouldBe true
        service.cachedStatus(player).record shouldBe null
        runCatching { service.claim(player, UUID.randomUUID()).join() }.isFailure shouldBe true
    }

    "quit eviction fences a pending hydration callback" {
        val player = UUID.randomUUID()
        val record = MountCareBoostRecord(
            requestId = UUID.randomUUID(),
            grantedAtMillis = NOW,
            expiresAtMillis = NOW + BOOST_MILLIS,
            nextClaimAtMillis = NOW + COOLDOWN_MILLIS,
            multiplier = 1.05,
        )
        val store = DelayedMountCareBoostStore(record)
        val service = MountCareBoostService(store, clockMillis = { NOW })
        val pending = service.status(player)

        service.forget(player)
        store.readResult.complete(record)
        runCatching { pending.join() }.isFailure shouldBe true
        service.cachedStatus(player).record shouldBe null
    }
}) {
    private companion object {
        const val NOW = 1_700_000_000_000L
        const val BOOST_MILLIS = 7_200_000L
        const val COOLDOWN_MILLIS = 86_400_000L
    }

    private class DelayedMountCareBoostStore(
        private val newer: MountCareBoostRecord,
    ) : MountCareBoostStore {
        val readResult = java.util.concurrent.CompletableFuture<MountCareBoostRecord?>()

        override fun claimOrRead(
            playerId: UUID,
            requestId: UUID,
            nowMillis: Long,
            boostDurationMillis: Long,
            cooldownMillis: Long,
            speedMultiplier: Double,
        ) = java.util.concurrent.CompletableFuture.completedFuture<MountCareBoostClaimResult>(
            MountCareBoostClaimResult.Granted(newer),
        )

        override fun read(playerId: UUID) = readResult
    }
}
