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

internal class MountModule(
    private val plugin: JavaPlugin,
    private val hostConfiguration: ArcMountConfiguration,
    private val scheduler: TaskScheduler,
    private val visibilityProvider: () -> ArcMountVisibility?,
    private val metrics: MountTechnicalMetrics,
    private val telemetry: MountTelemetry,
    private val menuRuntime: PaperMenuRuntime,
) : AutoCloseable {

    @Volatile private var config: MountModuleConfig? = null
    @Volatile private var catalog: MountCatalog? = null
    private var ownership: MountOwnership? = null
    private var journal: MountPurchaseJournal? = null
    private var purchases: MountPurchaseCoordinator? = null
    private var sessions: MountSessionController? = null
    private var quickSummons: MountQuickSummonController? = null
    private var gui: MountGuiController? = null
    private var transfers: MountTransferController? = null
    private var careBoosts: MountCareBoostService? = null
    private var careBoostHydration: MountCareBoostHydrationListener? = null
    private var redisClient: PaperRedisClient? = null
    private var redisGeneration = Long.MIN_VALUE
    private val rewardGrant = MountRewardGrant()

    internal fun currentBackgroundStyle(): MountGuiItemStyle? = config?.guiStyle(MountGuiItemRole.BACKGROUND)

    fun init() {
        bindUnavailableCommands()
        start(MountModuleConfig.load(hostConfiguration.dataPath()))
    }

    fun reload() {
        shutdownRuntime()
        val transferConfig = MountTransferConfig.load(hostConfiguration.dataPath())
        menuRuntime.replace(MountMenuConfiguration.load(hostConfiguration.dataPath(), transferConfig.detailSlot))
        start(MountModuleConfig.load(hostConfiguration.dataPath()))
    }

    override fun close() {
        shutdownRuntime()
        config = null
        catalog = null
        redisClient?.close()
        redisClient = null
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

    private fun start(loadedConfig: MountModuleConfig) {
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

        val loadedCatalog = loadedConfig.catalog()
        validatePaperTypes(loadedCatalog)
        val luckPerms = plugin.server.servicesManager.load(LuckPerms::class.java)
        if (luckPerms == null) {
            warn("Mounts module disabled because LuckPerms API is unavailable")
            return
        }

        val loadedOwnership = LuckPermsMountOwnership(luckPerms)
        val wallet = HostMountWallet(plugin)
        val journal = FileMountPurchaseJournal(hostConfiguration.dataPath().resolve("data").resolve("mount-purchases.json"))
        this.journal = journal
        val loadedRedisClient = refreshRedisClient()
        val loadedCareBoosts = MountCareBoostService(
            store = loadedRedisClient?.let(::RedisMountCareBoostStore) ?: UnavailableMountCareBoostStore,
            policy = MountCareBoostPolicy.load(hostConfiguration.dataPath()),
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
                recordActivity = telemetry::recordActivity,
                dataPath = hostConfiguration.dataPath(),
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

        val transferConfig = MountTransferConfig.load(hostConfiguration.dataPath())
        if (transferConfig.enabled && loadedConfig.purchasesEnabled) {
            try {
                val ledger = MySqlOneTimeUseLedger.open(
                    transferConfig.sql(), "ARC-mount-transfers", "arc_mount_transfers",
                    listOf(MySqlOneTimeUseLedger.createTableMigration(1)),
                    MySqlOneTimeUsePartition("arc.mount-transfer"),
                )
                try {
                    transfers = MountTransferController(
                        plugin, transferConfig, loadedCatalog, ledger, LuckPermsMountTransfers(luckPerms),
                        controller, coordinator::isBusy, guiController::openDetail,
                        { playerId, mount -> loadedOwnership.profile(MountPermissionSubject(playerId, "", { false }), mount) },
                        scheduler = scheduler,
                        backgroundStyle = ::currentBackgroundStyle,
                        dataPath = hostConfiguration.dataPath(),
                    )
                } catch (failure: Throwable) { ledger.close(); throw failure }
            } catch (failure: Exception) {
                warn("Mount transfers unavailable: {}", failure.javaClass.simpleName)
            }
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
            Bukkit.getOnlinePlayers().forEach { player ->
                loadedCareBoosts.hydrate(player.uniqueId).whenComplete { _, failure ->
                    if (failure == null) {
                        scheduler.runLater(1L, Runnable {
                            if (careBoosts === loadedCareBoosts && sessions === controller) {
                                refreshCareBoostedMount(player.uniqueId, controller, summonService, loadedCatalog)
                            }
                        })
                    }
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
        rewardGrant.close()
        bindUnavailableCommands()
        careBoostHydration?.close()
        careBoostHydration = null
        careBoosts?.close()
        careBoosts = null
        redisClient?.close()
        redisClient = null
        transfers?.close()
        transfers = null
        gui?.shutdown()
        gui = null
        quickSummons?.shutdown()
        quickSummons = null
        sessions?.shutdown()
        sessions = null
        purchases?.clear()
        purchases = null
        journal = null
        ownership = null
        catalog = null
        publishMetrics()
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
                        MountTransferConfig.load(hostConfiguration.dataPath()).text("spawn-only", "<#ff9f0f>Упаковка и активация свидетельств доступны на спавне.")
                    ))
                },
            )
        plugin.getCommand("mount")?.apply {
            setExecutor(mountCommand)
            tabCompleter = mountCommand
        } ?: warn("Mount command is missing from plugin.yml")
    }

    private fun refreshRedisClient(): PaperRedisClient? {
        val generation = hostConfiguration.sharedRedisGeneration()
        val available = hostConfiguration.sharedRedisAvailable()
        if (redisGeneration == generation && (!available || redisClient != null)) return redisClient
        redisClient?.close()
        redisClient = null
        redisGeneration = generation
        if (!available) return null
        return runCatching {
            PaperRedisClient.openPrimary(plugin).also { it.init() }.also { redisClient = it }
        }.onFailure { failure ->
            warn("Mount care boost Redis lease unavailable: {}", failure.javaClass.simpleName)
        }.getOrNull()
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
