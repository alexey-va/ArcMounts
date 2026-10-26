package ru.arc.mounts

import net.luckperms.api.LuckPerms
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.TaskScheduler
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BudgetedTaskQueue
import ru.arc.config.ConfigManager
import ru.arc.paper.api.ArcRedisService
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.mounts.api.ArcMountConfiguration
import ru.arc.mounts.api.ArcMountVisibility
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.arc.util.TextUtils
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal class MountModule(
    private val plugin: JavaPlugin,
    private val hostConfiguration: ArcMountConfiguration,
    private val scheduler: TaskScheduler,
    private val visibilityProvider: () -> ArcMountVisibility?,
    private val metrics: MountTechnicalMetrics,
    private val telemetry: MountTelemetry,
    private val menuRuntime: PaperMenuRuntime,
    private val io: BoundedAsyncExecutor,
) : AutoCloseable {

    @Volatile private var config: MountModuleConfig? = null
    @Volatile private var catalog: MountCatalog? = null
    private var ownership: MountOwnership? = null
    private var journal: MountPurchaseJournal? = null
    private var purchases: MountPurchaseCoordinator? = null
    private var sessions: MountSessionController? = null
    private var questEligibility: AutoCloseable? = null
    private var quickSummons: MountQuickSummonController? = null
    private var gui: MountGuiController? = null
    private var transfers: MountTransferController? = null
    private var careBoosts: MountCareBoostService? = null
    private var careBoostHydration: MountCareBoostHydrationListener? = null
    private var redisClient: PaperRedisClient? = null
    private val dataPath = hostConfiguration.dataPath()
    private val nativeScope = LifecycleTaskScope(scheduler)
    private var nativeQueue = BudgetedTaskQueue(nativeScope)
    @Volatile private var closed = false
    private var transferConfig: MountTransferConfig? = null
    // Shared leases outlive every accepted gameplay write and close during the terminal drain.
    private val storageResources = ConcurrentHashMap.newKeySet<AutoCloseable>()
    // Worker-confined; a failed retired lease remains part of terminal readiness.
    private var storageRetirementFailure: Throwable? = null
    private val rewardGrant = MountRewardGrant()

    internal fun currentBackgroundStyle(): MountGuiItemStyle? = config?.guiStyle(MountGuiItemRole.BACKGROUND)

    fun init() {
        bindUnavailableCommands()
        prepareAndStart(reload = false)
    }

    fun reload() {
        shutdownRuntime()
        nativeScope.restart()
        nativeQueue = BudgetedTaskQueue(nativeScope)
        prepareAndStart(reload = true)
    }

    private fun prepareAndStart(reload: Boolean) {
        check(!closed)
        val ready = hostConfiguration.ready()
        check(ready.isDone) { "ARC host must be ready before Mounts preparation" }
        ready.getNow(null)
        val redisService = if (hostConfiguration.sharedRedisAvailable()) {
            plugin.server.servicesManager.load(ArcRedisService::class.java)
        } else null
        val ownerName = plugin.name
        if (reload) ConfigManager.reloadAll()
        val config = MountModuleConfig.load(dataPath, nativeValidation = false)
        val transfer = MountTransferConfig.load(dataPath)
        val menus = MountMenuConfiguration.prepare(dataPath)
        val policy = MountCareBoostPolicy.load(dataPath)
        val catalog = config.catalog()
        val enabled = config.enabled && config.ownershipMigrationComplete
        val journal = if (enabled) FileMountPurchaseJournal(dataPath.resolve("data/mount-purchases.json"), writer = io) else null
        val redis = if (enabled && redisService != null) runCatching {
            PaperRedisClient.openPrimary(redisService, ownerName).also { storageResources += it; it.retainDuringShutdown(); it.init() }
        }.onFailure { warn("Mount care boost Redis lease unavailable: {}", it.javaClass.simpleName) }.getOrNull() else null
        var ledger: OneTimeUseLedger? = null
        var transfers: FileMountTransferStore? = null
        if (enabled && transfer.enabled && config.purchasesEnabled) {
            runCatching {
                transfers = FileMountTransferStore(dataPath, io)
                ledger = MySqlOneTimeUseLedger.open(
                    transfer.sql(), "ARC-mount-transfers", "arc_mount_transfers",
                    listOf(MySqlOneTimeUseLedger.createTableMigration(1)), MySqlOneTimeUsePartition("arc.mount-transfer"),
                ).also { storageResources += it }
            }.onFailure { warn("Mount transfers unavailable: {}", it.javaClass.simpleName) }
        }
        menuRuntime.replace(MountMenuConfiguration.parse(menus, transfer.detailSlot))
        start(Prepared(config, catalog, transfer, policy, journal, redis, ledger, transfers))
    }

    private data class Prepared(
        val config: MountModuleConfig,
        val catalog: MountCatalog,
        val transfer: MountTransferConfig,
        val carePolicy: MountCareBoostPolicy,
        val journal: FileMountPurchaseJournal?,
        val redis: PaperRedisClient?,
        val ledger: OneTimeUseLedger?,
        val transfers: FileMountTransferStore?,
    )

    internal fun retireStorage(resource: AutoCloseable): CompletableFuture<Unit> = io.submit {
        try { closeStorageResource(resource) } catch (problem: Throwable) {
            val previous = storageRetirementFailure
            if (previous == null) storageRetirementFailure = problem
            else if (previous !== problem) previous.addSuppressed(problem)
            throw problem
        } finally { storageResources.remove(resource) }
        // If shutdown sealed admission, closeStorageResources owns the retained resource.
    }

    /** Called only on the owned worker; borrowed SQL leases stay held through advisory-lock cleanup. */
    private fun closeStorageResource(resource: AutoCloseable) {
        if (resource is MySqlOneTimeUseLedger) resource.closeAsync().join() else resource.close()
    }

    /** Worker terminal finalizer, after all accepted prepares and durable writes. */
    internal fun closeStorageResources() {
        var failure: Throwable? = storageRetirementFailure
        storageResources.forEach { resource ->
            try { closeStorageResource(resource) } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            } finally { storageResources.remove(resource) }
        }
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { shutdownRuntime() } finally {
            config = null
            catalog = null
        }
    }

    fun summonFavorite(player: org.bukkit.entity.Player): Boolean = quickSummons?.summonFavorite(player) ?: false

    internal fun isModuleActive(): Boolean = catalog != null

    fun rewardPreview(id: String): ItemStack? = rewardGrant.preview(id)

    fun grantReward(player: Player, id: String): CompletableFuture<MountRewardResult> =
        rewardGrant.grant(player, id)

    internal fun claimDailyCareBoost(
        playerId: UUID,
        requestId: UUID,
    ): CompletableFuture<MountCareBoostClaimResult> =
        careBoosts?.claim(playerId, requestId)
            ?: CompletableFuture.failedFuture(IllegalStateException("Mount care boost service is unavailable"))

    internal fun dailyCareBoostStatus(playerId: UUID): CompletableFuture<MountCareBoostStatus> =
        careBoosts?.status(playerId)
            ?: CompletableFuture.failedFuture(IllegalStateException("Mount care boost service is unavailable"))

    internal fun activeMountSnapshot(player: org.bukkit.entity.Player): ActiveMountSnapshot? =
        sessions?.activeMountSnapshot(player)

    private fun start(prepared: Prepared) {
        val loadedConfig = prepared.config
        transferConfig = prepared.transfer
        config = loadedConfig
        if (!loadedConfig.enabled) {
            info("Mounts module disabled by configuration")
            return
        }
        if (!loadedConfig.ownershipMigrationComplete) {
            warn("Mounts module inactive until the ownership permission migration is complete")
            return
        }
        val visibility = visibilityProvider()?.takeIf(ArcMountVisibility::available)
        if (loadedConfig.hideFlyingMountFromRider && visibility == null) {
            warn("Rider-only mount visibility is unavailable because PacketEvents is not active")
        }

        val loadedCatalog = prepared.catalog
        MountGuiItemRole.entries.forEach(loadedConfig::guiStyle)
        validatePaperTypes(loadedCatalog)
        val luckPerms = plugin.server.servicesManager.load(LuckPerms::class.java)
        if (luckPerms == null) {
            warn("Mounts module disabled because LuckPerms API is unavailable")
            prepared.ledger?.let(::retireStorage)
            prepared.redis?.let(::retireStorage)
            return
        }

        val loadedOwnership = LuckPermsMountOwnership(luckPerms)
        val quests = if (plugin.server.pluginManager.isPluginEnabled("ArcRanks")) {
            questEligibility = MountQuestIntegration.registerEligibility(plugin) { playerId ->
                ownedMountQuestEligibility(catalog?.all, ownership, loadedOwnership.isUserCached(playerId), playerId)
            }
            MountQuestIntegration(nativeScope, {
                plugin.server.servicesManager.load(ru.ruscrafting.ranks.api.RankQuestApi::class.java)
            }, plugin.logger)
        } else null
        val wallet = HostMountWallet(plugin)
        val journal = requireNotNull(prepared.journal)
        this.journal = journal
        val loadedRedisClient = prepared.redis
        redisClient = loadedRedisClient
        val loadedCareBoosts = MountCareBoostService(
            store = loadedRedisClient?.let(::RedisMountCareBoostStore) ?: UnavailableMountCareBoostStore,
            policy = prepared.carePolicy,
        )
        careBoosts = loadedCareBoosts
        val controller =
            MountSessionController(
                plugin = plugin,
                scheduler = scheduler,
                configProvider = ::requiredConfig,
                allowedMountIds = loadedCatalog.all.mapTo(hashSetOf(), MountDefinition::id),
                message = { player, path, fallback ->
                    player.sendMessage(requiredConfig().messageComponent(path, fallback, player.locale().toLanguageTag()))
                },
                onStateChanged = ::publishMetrics,
                setRiderMountHidden = { player, entity, hidden -> visibility?.setEntityInvisibleFor(entity, player, hidden) },
                synchronizePassengers = { player, vehicles -> visibility?.synchronizePassengersFor(player, vehicles) },
                careBoostStatusProvider = { playerId, nowMillis -> loadedCareBoosts.cachedStatus(playerId, nowMillis) },
                recordProductOutcome = telemetry::recordProductOutcome,
                questProgress = quests,
                recordActivity = telemetry::recordActivity,
                dataPath = dataPath,
            )
        val coordinator =
            MountPurchaseCoordinator(
                ownership = loadedOwnership,
                wallet = wallet,
                journal = journal,
                purchasesEnabled = { requiredConfig().purchasesEnabled },
                externalBusy = { transfers?.isBusyWithoutOther(it) == true || rewardGrant.isBusy(it) },
                runSync = { task -> scheduler.runSync(Runnable(task)) },
                onStateChanged = ::publishMetrics,
                purchaseAllowed = { playerId -> Bukkit.getPlayer(playerId)?.let(MountMerchantGate::isAtMerchant) == true },
                sharedBusy = rewardGrant::isBusy,
                recordActivity = telemetry::recordActivity,
                onFailure = { failure -> plugin.logger.log(java.util.logging.Level.SEVERE, "Mount purchase continuation failed", failure) },
                recoverStep = { action -> nativeQueue.call({ !closed }, action) },
            )
        val summonService =
            MountSummonService(
                configProvider = ::requiredConfig,
                catalogProvider = ::requiredCatalog,
                ownership = loadedOwnership,
                sessions = controller,
                isBusy = { transfers?.isBusy(it) == true },
            )
        val quickSummonController =
            MountQuickSummonController(
                plugin = plugin,
                configProvider = ::requiredConfig,
                summons = summonService,
            )
        coordinator.recover(loadedCatalog) { record ->
            warn("Mount purchase {} requires manual review at stage {}", record.transactionId, record.status.name.lowercase(Locale.ROOT))
        }
        val guiController =
            MountGuiController(
                plugin = plugin,
                configProvider = ::requiredConfig,
                catalogProvider = ::requiredCatalog,
                ownership = loadedOwnership,
                wallet = wallet,
                purchases = coordinator,
                summons = summonService,
                quickSummons = quickSummonController,
                menuRuntime = menuRuntime,
                transfers = { transfers },
                scheduler = scheduler,
            )

        val transferConfig = prepared.transfer
        prepared.ledger?.let { ledger ->
            transfers = MountTransferController(
                plugin, transferConfig, loadedCatalog, ledger, LuckPermsMountTransfers(luckPerms),
                controller, coordinator::isBusy, guiController::openDetail,
                { playerId, mount -> loadedOwnership.profile(MountPermissionSubject(playerId, "", { false }), mount) },
                scheduler = scheduler,
                backgroundStyle = ::currentBackgroundStyle,
                dataPath = dataPath,
                store = requireNotNull(prepared.transfers),
                closeLedger = { retireStorage(ledger) },
                queue = nativeQueue,
            )
        }

        catalog = loadedCatalog
        ownership = loadedOwnership
        purchases = coordinator
        rewardGrant.activate(
            loadedCatalog,
            loadedOwnership,
            busy = { playerId ->
                purchases?.isPurchaseBusy(playerId) == true || transfers?.isBusyWithoutOther(playerId) == true
            },
        )
        sessions = controller
        quickSummons = quickSummonController
        gui = guiController
        val hydration = MountCareBoostHydrationListener(
            plugin = plugin,
            scheduler = scheduler,
            service = loadedCareBoosts,
            isCurrent = { careBoosts === loadedCareBoosts && sessions === controller },
            onHydrated = { playerId -> refreshCareBoostedMount(playerId, controller, summonService, loadedCatalog) },
        )
        careBoostHydration = hydration
        try {
            controller.start()
            quickSummonController.start()
            guiController.start()
            transfers?.start()
            bindCommands(guiController, loadedOwnership, controller)
            hydration.start()
            val hydrationToken = nativeScope.token()
            val players = Bukkit.getOnlinePlayers().toList().iterator()
            nativeQueue.submit({ !closed && careBoosts === loadedCareBoosts && sessions === controller }) {
                if (!players.hasNext()) true else {
                    val playerId = players.next().uniqueId
                    loadedCareBoosts.hydrate(playerId).whenComplete { _, failure ->
                        if (failure == null) nativeScope.callSync(hydrationToken) {
                            if (careBoosts === loadedCareBoosts && sessions === controller) {
                                refreshCareBoostedMount(playerId, controller, summonService, loadedCatalog)
                            }
                        }
                    }
                    !players.hasNext()
                }
            }
        } catch (failure: Throwable) {
            shutdownRuntime()
            throw failure
        }
        publishMetrics()
        info("Mounts module initialized with {} mount(s)", loadedCatalog.all.size)
    }

    private fun shutdownRuntime() {
        var failure: Throwable? = null
        fun attempt(close: () -> Unit) {
            try { close() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        attempt { sessions?.flushQuestProgress() }
        attempt { questEligibility?.close() }
        questEligibility = null
        attempt { nativeQueue.close() }
        attempt { nativeScope.cancelAll() }
        attempt { rewardGrant.close() }
        attempt { bindUnavailableCommands() }
        attempt { careBoostHydration?.close() }
        careBoostHydration = null
        attempt { careBoosts?.close() }
        careBoosts = null
        attempt { redisClient?.let(::retireStorage) }
        redisClient = null
        attempt { transfers?.close() }
        transfers = null
        attempt { gui?.shutdown() }
        gui = null
        attempt { quickSummons?.shutdown() }
        quickSummons = null
        attempt { sessions?.shutdown() }
        sessions = null
        attempt { purchases?.clear() }
        purchases = null
        journal = null
        ownership = null
        catalog = null
        attempt { publishMetrics() }
        failure?.let { throw it }
    }

    private fun refreshCareBoostedMount(
        playerId: UUID,
        controller: MountSessionController,
        summonService: MountSummonService,
        loadedCatalog: MountCatalog,
    ) {
        val player = Bukkit.getPlayer(playerId) ?: return
        val snapshot = controller.activeMountSnapshot(player) ?: return
        val mount = loadedCatalog[snapshot.mountId] ?: return
        summonService.refreshActive(player, mount)
    }

    private fun bindCommands(
        guiController: MountGuiController,
        loadedOwnership: MountOwnership,
        controller: MountSessionController,
    ) {
        val mountCommand =
            MountCommand(
                config = ::requiredConfig,
                catalog = ::requiredCatalog,
                ownership = loadedOwnership,
                sessions = controller,
                scheduler = scheduler,
                openMenu = guiController::openList,
                openOwned = guiController::openOwned,
                openShop = guiController::openShop,
                openUpgrades = guiController::openUpgrades,
                openTrade = guiController::openTrade,
                openDetail = guiController::openDetail,
                packMount = { player, id ->
                    transfers?.confirm(player, id) ?: player.sendMessage(TextUtils.mm(
                        requireNotNull(transferConfig).text("spawn-only", "<#ff9f0f>Упаковка и активация свидетельств доступны на спавне.")
                    ))
                },
            )
        plugin.getCommand("mount")?.apply {
            setExecutor(mountCommand)
            tabCompleter = mountCommand
        } ?: warn("Mount command is missing from plugin.yml")
    }

    private fun bindUnavailableCommands() {
        plugin.getCommand("mount")?.apply {
            setExecutor(UnavailableMountCommand)
            tabCompleter = UnavailableMountCommand
        }
    }

    internal fun publishMetrics() {
        val loadedConfig = config
        val loadedCatalog = catalog
        val records = journal?.records().orEmpty()
        metrics.publish(
            enabled = loadedConfig?.enabled == true,
            purchasesEnabled = loadedConfig?.purchasesEnabled == true,
            catalogSize = loadedCatalog?.all?.size ?: 0,
            activeSessions = sessions?.activeSessionCount() ?: 0,
            records = records,
        )
    }

    private fun requiredConfig(): MountModuleConfig = checkNotNull(config) { "Mounts config is unavailable" }
    private fun requiredCatalog(): MountCatalog = checkNotNull(catalog) { "Mounts catalog is unavailable" }

    companion object {
        internal fun validatePaperTypes(loadedCatalog: MountCatalog) {
            loadedCatalog.all.forEach { definition ->
                require(Material.matchMaterial(definition.iconMaterial) != null) {
                    "Mount '${definition.id}' has unknown material '${definition.iconMaterial}'"
                }
                val entityType = runCatching { EntityType.valueOf(definition.entityType.uppercase(Locale.ROOT)) }.getOrNull()
                require(entityType != null && entityType.isAlive && entityType.isSpawnable) {
                    "Mount '${definition.id}' has invalid entity type '${definition.entityType}'"
                }
                val appearanceType = definition.visualEntityType?.let { raw ->
                    val visualType = runCatching { EntityType.valueOf(raw) }.getOrNull()
                    require(visualType != null && visualType.isAlive && visualType.isSpawnable) {
                        "Mount '${definition.id}' has invalid visual entity '$raw'"
                    }
                    visualType
                } ?: entityType
                MountAppearanceApplicator.validate(appearanceType, definition.appearance, "Mount '${definition.id}' appearance")
                definition.levels.forEach { level -> level.price?.toExactMinor() }
                definition.glowPrice?.toExactMinor()
                definition.abilities.upgrades.forEach { ability ->
                    require(Material.matchMaterial(ability.iconMaterial) != null) {
                        "Mount '${definition.id}' ability '${ability.id}' has unknown material '${ability.iconMaterial}'"
                    }
                    ability.price.toExactMinor()
                }
                definition.skins.forEach { skin ->
                    require(Material.matchMaterial(skin.iconMaterial) != null) {
                        "Mount '${definition.id}' skin '${skin.id}' has unknown material '${skin.iconMaterial}'"
                    }
                    skin.price?.toExactMinor()
                    MountAppearanceApplicator.validate(appearanceType, skin.appearance, "Mount '${definition.id}' skin '${skin.id}'")
                    skin.trail?.let { trail ->
                        val particle = runCatching { org.bukkit.Particle.valueOf(trail.particle) }.getOrNull()
                        require(particle != null && particle.dataType == Void::class.java) {
                            "Mount '${definition.id}' skin '${skin.id}' has unsupported particle '${trail.particle}'"
                        }
                    }
                }
                definition.sizeOptions.forEach { size -> size.price?.toExactMinor() }
            }
        }
    }
}

private object UnavailableMountCommand : TabExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        sender.sendMessage(TextUtils.mm("<red>Маунты сейчас недоступны.", true))
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = emptyList()
}
