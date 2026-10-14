package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.paper.sql.PaperSqlServiceProvider
import ru.arc.paper.sql.PaperSqlServices
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MountTransferSqlIntegrationTest : StringSpec({
    "one-time transfer claim survives consumer and file-store recreation" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_mount_transfer_test")).use { mysql ->
            MockBukkitTestRuntime.open().use { paper ->
                val host = paper.createSimplePlugin("ArcSqlHost")
                val mounts = paper.createSimplePlugin("ArcMounts")
                val provider = PaperSqlServiceProvider(host)
                var binding: AutoCloseable? = null
                var ledger: MySqlOneTimeUseLedger? = null
                try {
                    binding = PaperSqlServices.installConsumer(mounts)
                    val endpoint = mysql.endpoint
                    val sql = SqlConnectionConfig(
                        host = endpoint.host,
                        port = endpoint.port,
                        database = endpoint.database,
                        username = endpoint.username,
                        password = endpoint.password,
                        sslMode = SqlSslMode.DISABLED,
                        minimumIdle = 0,
                        maximumPoolSize = 2,
                    )
                    val dataPath = Files.createTempDirectory("arc-mount-transfer-sql-")
                    val mount = checkNotNull(MountModuleConfig.load(dataPath).catalog()["horse"])
                    val partition = MySqlOneTimeUsePartition("arc.mount-transfer")
                    val applied = AtomicInteger()
                    val firstLedger = openLedger(sql, partition)
                    ledger = firstLedger
                    val store = FileMountTransferStore(dataPath)
                    val issuer = UUID.randomUUID()
                    val recipient = UUID.randomUUID()
                    val record = MountTransferRecord(
                        id = UUID.randomUUID(),
                        issuer = issuer,
                        mountId = mount.id,
                        permissions = listOf(mount.levelPermission(1)),
                        stage = MountTransferStage.AVAILABLE,
                    )
                    store.save(record)
                    val ownership = object : MountTransferOwnership {
                        override fun snapshot(playerId: UUID, mount: MountDefinition) =
                            CompletableFuture.completedFuture(listOf(mount.levelPermission(1)))

                        override fun pack(record: MountTransferRecord, mount: MountDefinition) =
                            CompletableFuture.completedFuture<Void>(null)

                        override fun apply(record: MountTransferRecord, mount: MountDefinition) =
                            CompletableFuture.runAsync { applied.incrementAndGet() }

                        override fun canReceive(playerId: UUID, mount: MountDefinition) =
                            CompletableFuture.completedFuture(true)
                    }
                    val flow = MountTransferFlow(store, ownership, firstLedger, runSync = { it() })
                    val result = CompletableFuture<MountTransferRecord>()
                    flow.redeem(recipient, record.id, mount) { outcome ->
                        outcome.fold(
                            onSuccess = { result.complete(it); Unit },
                            onFailure = { result.completeExceptionally(it); Unit },
                        )
                    }
                    val consumed = result.get(10, TimeUnit.SECONDS)
                    consumed.stage shouldBe MountTransferStage.CONSUMED
                    applied.get() shouldBe 1
                    firstLedger.close()
                    ledger = null

                    val recovered = FileMountTransferStore(dataPath).get(record.id)
                    recovered shouldBe consumed

                    val reopened = openLedger(sql, partition)
                    ledger = reopened
                    reopened.claim(OneTimeUseClaimRequest(record.identity, record.id, recipient))
                        .get(10, TimeUnit.SECONDS) shouldBe OneTimeUseClaimResult.AlreadyConsumed
                } finally {
                    ledger?.close()
                    binding?.close()
                    provider.close()
                }
            }
        }
    }
})

private fun openLedger(
    config: SqlConnectionConfig,
    partition: MySqlOneTimeUsePartition,
): MySqlOneTimeUseLedger = MySqlOneTimeUseLedger.open(
    connectionConfig = config,
    runtimeName = "arc-mount-transfer-test",
    migrationNamespace = "arc_mount_transfer_test_migration",
    migrations = listOf(MySqlOneTimeUseLedger.createTableMigration(1)),
    partition = partition,
)
