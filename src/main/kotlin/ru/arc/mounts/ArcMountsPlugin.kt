package ru.arc.mounts

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.Slf4jLoggingPlatform
import ru.arc.mounts.api.ArcMountConfiguration
import ru.arc.mounts.api.ArcMountService
import ru.arc.mounts.api.ArcMountVisibility
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.sql.PaperSqlServices
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionStage

/** Standalone Paper lifecycle and host-facing mounts service. */
class ArcMountsPlugin : JavaPlugin(), ArcMountService {
    @Volatile private var runtime: PaperPluginRuntime? = null
    @Volatile private var module: MountModule? = null

    override fun onEnable() {
        val host = server.servicesManager.load(ArcMountConfiguration::class.java)
            ?: error("Required ARC ArcMountConfiguration is not registered")
        ArcLogging.install(
            Slf4jLoggingPlatform("ArcMounts", logger.name),
            LoggingConfigSource { ConfigManager.empty() },
        )

        PaperArcRuntime.installScheduling(this)
        val scheduler = Tasks.scheduler
        val local = PaperPluginRuntime(this, "arc-mounts", scheduler)
        runtime = local
        local.start("host" to "ARC")
        local.registerHealth("mounts") {
            val activeModule = module
            ru.arc.observability.RuntimeHealthContribution(
                state = if (activeModule == null) ru.arc.observability.RuntimeHealthState.DEGRADED
                    else ru.arc.observability.RuntimeHealthState.UP,
                dependencies = mapOf("redis" to host.sharedRedisAvailable()),
            )
        }
        local.reportHealthEvery(20L * 60L)

        val metrics = local.own(MountTechnicalMetrics.open(this))
        local.own(PaperSqlServices.installConsumer(this))
        local.own(AutoCloseable { server.servicesManager.unregister(ArcMountService::class.java, this) })
        server.servicesManager.register(ArcMountService::class.java, this, this, ServicePriority.Normal)
        val transferConfig = MountTransferConfig.load(host.dataPath())
        val menuRuntime = local.own(
            PaperMenuRuntime(
                this,
                scheduler,
                MountMenuConfiguration.load(host.dataPath(), transferConfig.detailSlot),
            ),
        )

        val loaded = MountModule(
            plugin = this,
            hostConfiguration = host,
            scheduler = scheduler,
            visibilityProvider = { server.servicesManager.load(ArcMountVisibility::class.java) },
            metrics = metrics,
            telemetry = MountTelemetry(this),
            menuRuntime = menuRuntime,
        )
        module = loaded
        local.own(AutoCloseable {
            loaded.close()
            module = null
        })
        try {
            loaded.init()
            local.ready("mountsEnabled" to loaded.isModuleActive())
            logger.info("ArcMounts enabled with ARC-owned configuration and host services")
        } catch (failure: Throwable) {
            local.close()
            runtime = null
            throw failure
        }
    }

    override fun onDisable() {
        try {
            runtime?.close()
        } finally {
            runtime = null
            module = null
        }
    }

    override fun summonFavorite(player: Player): Boolean = module?.summonFavorite(player) ?: false

    override fun rewardPreview(mountId: String): Optional<ItemStack> =
        Optional.ofNullable(module?.rewardPreview(mountId))

    override fun grantReward(player: Player, mountId: String): CompletionStage<ArcMountService.MountRewardOutcome> =
        module?.grantReward(player, mountId)?.thenApply(::toApiOutcome)
            ?: java.util.concurrent.CompletableFuture.completedFuture(
                ArcMountService.MountRewardOutcome(
                    ArcMountService.MountRewardOutcome.Result.REJECTED,
                    mountId,
                    0,
                    ArcMountService.MountRewardOutcome.Rejection.MODULE_UNAVAILABLE,
                ),
            )

    override fun activeMountSnapshot(player: Player): Optional<ArcMountService.ActiveMountSnapshot> =
        Optional.ofNullable(module?.activeMountSnapshot(player)?.let { snapshot ->
            ArcMountService.ActiveMountSnapshot(snapshot.mountId, snapshot.mountName, snapshot.rarity, snapshot.entityId)
        })

    override fun dailyCareBoostStatus(playerId: UUID): CompletionStage<ArcMountService.MountCareBoostStatus> =
        module?.dailyCareBoostStatus(playerId)?.thenApply(::toApiStatus)
            ?: java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("Mount care boost service is unavailable"))

    override fun claimDailyCareBoost(
        playerId: UUID,
        requestId: UUID,
    ): CompletionStage<ArcMountService.MountCareBoostClaimOutcome> =
        module?.claimDailyCareBoost(playerId, requestId)?.thenApply { outcome ->
            val claim = when (outcome) {
                is MountCareBoostClaimResult.Granted -> ArcMountService.MountCareBoostClaimOutcome.Claim.GRANTED
                is MountCareBoostClaimResult.AlreadyGranted -> ArcMountService.MountCareBoostClaimOutcome.Claim.ALREADY_GRANTED
            }
            ArcMountService.MountCareBoostClaimOutcome(claim, toApiRecord(outcome.record),
                outcome is MountCareBoostClaimResult.AlreadyGranted && outcome.sameRequest)
        } ?: java.util.concurrent.CompletableFuture.failedFuture(
            IllegalStateException("Mount care boost service is unavailable"),
        )

    override fun reload() {
        val local = runtime ?: return
        check(org.bukkit.Bukkit.isPrimaryThread()) { "Mounts reload must run on the primary server thread" }
        local.reload()
        ConfigManager.reloadAll()
        module?.reload()
        local.reportHealthEvery(20L * 60L)
        local.ready("reload" to true)
    }

    override fun purchaseJournalSchema(): Int = MountPurchaseJournalSnapshot.CURRENT_SCHEMA

    private fun toApiOutcome(result: MountRewardResult): ArcMountService.MountRewardOutcome = when (result) {
        is MountRewardResult.Granted -> ArcMountService.MountRewardOutcome(
            ArcMountService.MountRewardOutcome.Result.GRANTED,
            result.mountId,
            1,
            null,
        )
        is MountRewardResult.AlreadyOwned -> ArcMountService.MountRewardOutcome(
            ArcMountService.MountRewardOutcome.Result.ALREADY_OWNED,
            result.mountId,
            result.level,
            null,
        )
        is MountRewardResult.Rejected -> ArcMountService.MountRewardOutcome(
            ArcMountService.MountRewardOutcome.Result.REJECTED,
            result.mountId,
            0,
            ArcMountService.MountRewardOutcome.Rejection.valueOf(result.reason.name),
        )
    }

    private fun toApiStatus(status: MountCareBoostStatus): ArcMountService.MountCareBoostStatus =
        ArcMountService.MountCareBoostStatus(
            Optional.ofNullable(status.record?.let(::toApiRecord)),
            status.active,
            status.remainingMillis,
            status.nextClaimInMillis,
        )

    private fun toApiRecord(record: MountCareBoostRecord): ArcMountService.MountCareBoostRecord =
        ArcMountService.MountCareBoostRecord(
            record.requestId,
            record.grantedAtMillis,
            record.expiresAtMillis,
            record.nextClaimAtMillis,
            record.multiplier,
        )

}
