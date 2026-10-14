package ru.arc.mounts

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import ru.arc.paper.api.ArcRedisSettings
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.redis.PaperRedisServiceProvider
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.testing.containers.RedisTestService
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Requires the disposable Redis service supplied by the integration-test task. */
class MountCareBoostIntegrationTest {
    @Test
    fun `concurrent claims and store recreation preserve one record and its expiry`() {
        RedisTestService.start().use { service ->
            val endpoint = service.endpoint
            MockBukkitTestRuntime.open().use { paper ->
                val host = paper.createSimplePlugin("ArcRedisHost")
                val mounts = paper.createSimplePlugin("ArcMounts")
                val provider = PaperRedisServiceProvider(host)
                provider.configurePrimary(ArcRedisSettings(endpoint.host, endpoint.port, null, null), "mount-care-test")
                val redis = PaperRedisClient.openPrimary(mounts).also(PaperRedisClient::init)
                try {
                    val player = UUID.randomUUID()
                    val request = UUID.randomUUID()
                    val store = RedisMountCareBoostStore(redis)
                    val claims = (1..16).map {
                        CompletableFuture.supplyAsync {
                            store.claimOrRead(player, request, NOW, BOOST_MILLIS, COOLDOWN_MILLIS, 1.05).join()
                        }
                    }.map { it.join() }
                    val granted = claims.filterIsInstance<MountCareBoostClaimResult.Granted>()
                    val already = claims.filterIsInstance<MountCareBoostClaimResult.AlreadyGranted>()
                    assertEquals(1, granted.size)
                    assertEquals(15, already.size)
                    assertTrue(already.all { it.sameRequest })
                    val original = granted.single().record

                    store.close()
                    val recreated = RedisMountCareBoostStore(redis)
                    assertEquals(original, recreated.read(player).join())
                    val replay = recreated
                        .claimOrRead(player, request, NOW + 365L * COOLDOWN_MILLIS, BOOST_MILLIS, COOLDOWN_MILLIS, 1.50)
                        .join()
                    assertEquals(original, replay.record)
                    assertTrue(replay is MountCareBoostClaimResult.AlreadyGranted && replay.sameRequest)

                    val next = recreated
                        .claimOrRead(player, UUID.randomUUID(), NOW + COOLDOWN_MILLIS, BOOST_MILLIS, COOLDOWN_MILLIS, 1.10)
                        .join()
                    assertTrue(next is MountCareBoostClaimResult.Granted)
                    assertEquals(NOW + COOLDOWN_MILLIS + BOOST_MILLIS, next.record.expiresAtMillis)
                } finally {
                    redis.close()
                    provider.close()
                }
            }
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val BOOST_MILLIS = 7_200_000L
        const val COOLDOWN_MILLIS = 86_400_000L
    }
}
