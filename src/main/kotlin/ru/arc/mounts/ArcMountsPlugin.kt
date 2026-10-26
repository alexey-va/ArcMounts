package ru.arc.mounts

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.core.BoundedAsyncExecutor
import java.util.logging.Level
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.Slf4jLoggingPlatform
import ru.arc.mounts.api.ArcMountConfiguration
import ru.arc.mounts.api.ArcMountService
import ru.arc.mounts.api.ArcMountVisibility
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.runtime.PaperPluginHotload
import ru.arc.paper.sql.PaperSqlServices
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture

/** Standalone Paper lifecycle and host-facing mounts service. */
open class ArcMountsPlugin : JavaPlugin(), ArcMountService {
    @Volatile private var runtime: PaperPluginRuntime? = null
    @Volatile private var module: MountModule? = null
    private var storage: BoundedAsyncExecutor? = null
    @Volatile private var prepared = false
    @Volatile private var hostRedisAvailable = false
    private var shutdownDrain = CompletableFuture.completedFuture(Unit)
    private var lifecycleOpen = false
    private val hotload = PaperPluginHotload(this) {
        stopRuntime()
        mapOf("storage" to shutdownDrain)
    }

    override fun onEnable() {
        check(!lifecycleOpen) { "Plugin lifecycle is already open" }
        try {
            hotload.beginGeneration()
            hotload.register()
            check(shutdownDrain.isDone) { "Previous storage cleanup is still pending" }
            shutdownDrain.getNow(Unit)
            lifecycleOpen = true
            shutdownDrain = CompletableFuture()
            startGeneration()
        } catch (failure: Throwable) { failStartup(failure) }
    }

    protected open fun startGeneration() {
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
            ru.arc.observability.RuntimeHealthContribution(
                state = if (prepared) ru.arc.observability.RuntimeHealthState.UP
                    else ru.arc.observability.RuntimeHealthState.STARTING,
                dependencies = mapOf("redis" to hostRedisAvailable),
            )
        }
        local.reportHealthEvery(20L * 60L)

        val metrics = local.own(MountTechnicalMetrics.open(this))
        local.own(PaperSqlServices.installConsumer(this, shutdownDrain))
        local.own(AutoCloseable { server.servicesManager.unregister(ArcMountService::class.java, this) })
        server.servicesManager.register(ArcMountService::class.java, this, this, ServicePriority.Normal)
        val io = BoundedAsyncExecutor("arc-mounts-storage").also { storage = it }
        val ready = host.ready()
        check(ready.isDone) { "ARC host must be ready before ArcMounts starts" }
        ready.getNow(null)
        hostRedisAvailable = host.sharedRedisAvailable()
        val dataPath = host.dataPath()
        val transfer = MountTransferConfig.load(dataPath)
        val preparedMenus = MountMenuConfiguration.prepare(dataPath)
        val menus = local.own(PaperMenuRuntime(this, scheduler, MountMenuConfiguration.parse(preparedMenus, transfer.detailSlot)))
        val loaded = MountModule(
            plugin = this,
            hostConfiguration = host,
            scheduler = scheduler,
            visibilityProvider = { server.servicesManager.load(ArcMountVisibility::class.java) },
            metrics = metrics,
            telemetry = MountTelemetry(this),
            menuRuntime = menus,
            io = io,
        )
        module = loaded
        local.own(AutoCloseable { loaded.close(); module = null })
        loaded.init()
        prepared = true
        local.ready("mountsEnabled" to loaded.isModuleActive())
        logger.info("ArcMounts enabled with ARC-owned configuration and host services")
    }

    private fun failStartup(failure: Throwable) {
        logger.log(Level.SEVERE, "ArcMounts startup failed closed", failure)
        server.pluginManager.disablePlugin(this)
    }

    override fun onDisable() {
        hotload.prepare()
        hotload.close()
    }

    private fun stopRuntime() {
        if (!lifecycleOpen) return
        lifecycleOpen = false
        try {
            // Accepted writes and SQL advisory-lock cleanup need no further Paper callback.
            closeGeneration().join()
            shutdownDrain.complete(Unit)
        } catch (failure: Throwable) {
            shutdownDrain.completeExceptionally(failure)
            logger.log(Level.SEVERE, "Could not close ArcMounts storage", failure)
        }
    }

    /** Native detach is synchronous; the returned completion owns every accepted terminal IO action. */
    protected open fun closeGeneration(): CompletableFuture<Unit> {
        val retiring = runtime
        val retiringModule = module
        runtime = null
        prepared = false
        var nativeFailure: Throwable? = null
        for (close in listOf<() -> Unit>(
            { retiring?.close() },
            { hotload.retireGameplayRegistrations() },
            { ru.arc.logging.ArcLogging.close() },
        )) {
            try { close() } catch (failure: Throwable) {
                if (nativeFailure == null) nativeFailure = failure else nativeFailure.addSuppressed(failure)
            }
        }
        module = null
        val retiringStorage = storage
        storage = null
        val terminal = retiringStorage?.closeAsync { retiringModule?.closeStorageResources() }
            ?: CompletableFuture.completedFuture(Unit)
        return terminal.handle { _, failure ->
            if (nativeFailure != null) {
                if (failure != null) nativeFailure.addSuppressed(failure)
                throw nativeFailure
            }
            if (failure != null) throw failure
            Unit
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
        prepared = false
        local.reload()
        try {
            module?.reload()
            prepared = true
            local.reportHealthEvery(20L * 60L)
            local.ready("reload" to true)
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "ArcMounts reload failed closed", failure)
            local.health.markDown()
        }
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
