package ru.arc.mounts

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.TaskScheduler
import ru.arc.logging.ArcLogging.error
import ru.arc.util.TextUtils
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.paper.menu.PaperMenuFrame
import ru.arc.paper.menu.PaperMenuRuntime
import kotlin.math.ceil

private enum class MountScreen { LIST, DETAIL, PROGRESSION, TUNING, SKINS, CONFIRM }

internal enum class MountListPurpose { COLLECTION, SHOP, UPGRADES, TRADE }

private enum class MountFilter(
    val title: String,
    val icon: Material,
    val styleRole: MountGuiItemRole,
) {
    ALL("Все", Material.COMPASS, MountGuiItemRole.CATEGORY_ALL),
    FLYING("Воздушные", Material.FEATHER, MountGuiItemRole.CATEGORY_FLYING),
    WALKING("Наземные", Material.SADDLE, MountGuiItemRole.CATEGORY_WALKING),
    SWIMMING("Водные", Material.HEART_OF_THE_SEA, MountGuiItemRole.CATEGORY_SWIMMING),
    ;

    fun matches(mount: MountDefinition): Boolean =
        this == ALL ||
            this == FLYING && mount.movement == MountMovement.FLYING ||
            this == WALKING && mount.movement == MountMovement.WALKING ||
            this == SWIMMING && mount.movement == MountMovement.SWIMMING

    fun next(): MountFilter = entries[(ordinal + 1) % entries.size]
}

private sealed interface ConfirmAction {
    data class Level(val level: Int) : ConfirmAction
    data object Glow : ConfirmAction
    data class Skin(val skinId: String) : ConfirmAction
    data class Ability(val abilityId: String) : ConfirmAction
    data class Size(val sizeId: String) : ConfirmAction
}

private class MountMenuHolder(
    val screen: MountScreen,
    val menuId: MenuId,
    val mountId: String? = null,
    val page: Int = 0,
    val pageCount: Int = 1,
    val skinPage: Int = 0,
    val skinPageCount: Int = 1,
    val filter: MountFilter = MountFilter.ALL,
    val ownedOnly: Boolean = false,
    val purpose: MountListPurpose = MountListPurpose.COLLECTION,
    val mountsBySlot: Map<Int, String> = emptyMap(),
    val skinsBySlot: Map<Int, String> = emptyMap(),
    val abilitiesBySlot: Map<Int, String> = emptyMap(),
    val levelCardsBySlot: Map<Int, Int> = emptyMap(),
    val speedPercentagesBySlot: Map<Int, Int> = emptyMap(),
    val stepHeightsBySlot: Map<Int, Int> = emptyMap(),
    val sizeOptionsBySlot: Map<Int, String> = emptyMap(),
    val confirmAction: ConfirmAction? = null,
    var confirmEnabled: Boolean = false,
    val directOpen: Boolean = true,
    val parent: MountMenuHolder? = null,
) : InventoryHolder {
    lateinit var backingInventory: Inventory
    lateinit var title: Component
    lateinit var frame: PaperMenuFrame
    var fullBackground: Boolean = false
    var backgroundItem: ItemStack? = null

    override fun getInventory(): Inventory = backingInventory
}

class MountGuiController(
    private val plugin: JavaPlugin,
    private val configProvider: () -> MountModuleConfig,
    private val catalogProvider: () -> MountCatalog,
    private val ownership: MountOwnership,
    private val wallet: MountWallet,
    private val purchases: MountPurchaseCoordinator,
    private val summons: MountSummonService,
    private val quickSummons: MountQuickSummonController,
    private val menuRuntime: PaperMenuRuntime,
    private val transfers: () -> MountTransferController? = { null },
    private val merchantAllowed: (Player) -> Boolean = MountMerchantGate::isAtMerchant,
    private val scheduler: TaskScheduler = BukkitTaskScheduler(plugin),
) : Listener {
    @Volatile private var active = false
    private val holdersByInventory = java.util.IdentityHashMap<Inventory, MountMenuHolder>()
    private val items = MountGuiItems(configProvider, quickSummons)

    fun start() {
        active = true
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun shutdown() {
        active = false
        plugin.server.onlinePlayers
            .filter { player -> menuRuntime.session(player)?.inventory?.let(holdersByInventory::containsKey) == true }
            .forEach(Player::closeInventory)
        holdersByInventory.clear()
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    fun openList(player: Player) = openListPage(player, 0, MountFilter.ALL, false, MountListPurpose.COLLECTION)

    fun openShop(player: Player) {
        if (!configProvider().purchasesEnabled) {
            purchasesDisabled(player)
            return openOwned(player)
        }
        if (!merchantAllowed(player)) {
            send(player, "merchant-required", "<#ff9f0f>Покупки маунтов доступны у Гектора или Эммы во дворе маунтов на спавне.")
            return openOwned(player)
        }
        openListPage(player, 0, MountFilter.ALL, false, MountListPurpose.SHOP)
    }

    fun openUpgrades(player: Player) = openListPage(player, 0, MountFilter.ALL, true, MountListPurpose.UPGRADES)

    fun openTrade(player: Player) {
        val transfer = transfers() ?: run {
            player.sendMessage(component("<#ff9f0f>Упаковка маунтов доступна у Тома на спавне."))
            return
        }
        transfer.recoverDelivery(player)
        openListPage(player, 0, MountFilter.ALL, true, MountListPurpose.TRADE)
    }

    fun openOwned(player: Player) {
        transfers()?.recoverDelivery(player)
        openListPage(player, 0, MountFilter.ALL, true, MountListPurpose.COLLECTION)
    }

    private fun currentPurpose(player: Player) =
        previousHolder(player)?.purpose ?: MountListPurpose.COLLECTION

    private fun returnToList(player: Player, purpose: MountListPurpose) =
        openListPage(player, 0, MountFilter.ALL, purpose in setOf(MountListPurpose.UPGRADES, MountListPurpose.TRADE), purpose)

    private fun openListPage(
        player: Player,
        requestedPage: Int,
        filter: MountFilter,
        ownedOnly: Boolean,
        purpose: MountListPurpose,
    ) {
        val config = configProvider()
        val catalog = catalogProvider()
        val effectiveOwnedOnly = ownedOnly
        val favoriteMountId = summons.favoriteMountId(player.uniqueId)
        val profiles = catalog.all.associateWith { mount -> ownership.profile(subject(player), mount) }
        val matching =
            catalog.all.filter { mount ->
                filter.matches(mount) && (!effectiveOwnedOnly || checkNotNull(profiles[mount]).unlocked) &&
                    (purpose != MountListPurpose.SHOP || mount.price(1) != null)
            }
        val visible = prioritizeUnlockedMounts(matching) { mount -> checkNotNull(profiles[mount]) }
        val pageCount = maxOf(1, ceil(visible.size.toDouble() / layoutRegion(MOUNT_LIST, MOUNT_ENTRIES).size).toInt())
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val baseContentSlots = layoutRegion(MOUNT_LIST, MOUNT_ENTRIES)
        val pageMounts = visible.drop(page * baseContentSlots.size).take(baseContentSlots.size)
        val slots = baseContentSlots.zip(pageMounts.map(MountDefinition::id)).toMap()
        val contentRows = ((slots.keys.maxOrNull() ?: 0) / 9 + 1)
        val compact = purpose == MountListPurpose.UPGRADES || purpose == MountListPurpose.TRADE
        val rows = if (compact) (contentRows + 1).coerceAtMost(6) else layoutRows(MOUNT_LIST) / 9
        val menuId = MountMenuConfiguration.listLayoutId(rows, visible.isEmpty())
        val holder =
            MountMenuHolder(
                MountScreen.LIST,
                menuId,
                page = page,
                pageCount = pageCount,
                filter = filter,
                ownedOnly = effectiveOwnedOnly,
                purpose = purpose,
                mountsBySlot = slots,
                directOpen = false,
                parent = null,
            )
        val title = when (purpose) {
            MountListPurpose.COLLECTION -> config.listTitle
            MountListPurpose.SHOP -> config.guiText("shop-title", "<#20252b><bold>Магазин маунтов</bold>")
            MountListPurpose.UPGRADES -> config.guiText("upgrades-title", "<#20252b><bold>Улучшения маунтов</bold>")
            MountListPurpose.TRADE -> config.guiText("trade-title", "<#20252b><bold>Передача маунтов</bold>")
        }
        val inventory = frame(holder, title, full = false)
        if (visible.isEmpty()) {
            val ownedCount = profiles.values.count(MountProfile::unlocked)
            val filterEmpty = purpose != MountListPurpose.SHOP && ownedCount > 0
            val emptyTitle = when {
                purpose == MountListPurpose.SHOP -> config.guiText("list.empty-shop-title", "<#ffcf70>Магазин пока пуст")
                filterEmpty -> config.guiText("list.empty-filter-title", "<#ffcf70>В этой категории пока пусто")
                else -> config.guiText("list.empty-title", "<#ffcf70>Коллекция пока пуста")
            }
            val lore = when {
                purpose == MountListPurpose.SHOP -> config.guiLines("list.empty-shop-lore", listOf("", "<#b8b8b8>Нет доступных маунтов."))
                filterEmpty -> config.guiLines("list.empty-filter-lore", listOf("", "<#b8b8b8>Смените категорию внизу меню."))
                else -> config.guiLines(
                    "list.empty-lore",
                    listOf("", "<#b8b8b8>Маунтов можно приобрести у Гектора", "<#b8b8b8>во дворе маунтов на спавне."),
                )
            }
            inventory.setItem(layoutSlot(menuId, "info"), styledItem(MountGuiItemRole.INFO, Material.PAPER, emptyTitle, lore))
        }
        slots.forEach { (slot, mountId) ->
            val mount = catalog[mountId] ?: return@forEach
            inventory.setItem(slot, items.mountIcon(mount, checkNotNull(profiles[mount]), favorite = mount.id == favoriteMountId, purpose = purpose))
        }
        if (page > 0) {
            inventory.setItem(
                layoutSlot(menuId, "previous"),
                styledItem(
                    MountGuiItemRole.PREVIOUS,
                    Material.ARROW,
                    config.guiText("list.previous-name", "<#92bed8>Предыдущая страница"),
                    listOf(
                        copy(
                            "list.page",
                            "<#8c8c8c>Страница: <#e6fff3><page>/<pages>",
                            "page" to (page + 1).toString(),
                            "pages" to pageCount.toString(),
                        ),
                        "",
                        config.guiText("list.previous-footer", actionFooter(config.guiText("list.previous-action", "перейти назад"))),
                    ),
                ),
            )
        }
        if (page + 1 < pageCount) {
            inventory.setItem(
                layoutSlot(menuId, "next"),
                styledItem(
                    MountGuiItemRole.NEXT,
                    Material.ARROW,
                    config.guiText("list.next-name", "<#92bed8>Следующая страница"),
                    listOf(
                        copy(
                            "list.page",
                            "<#8c8c8c>Страница: <#e6fff3><page>/<pages>",
                            "page" to (page + 1).toString(),
                            "pages" to pageCount.toString(),
                        ),
                        "",
                        config.guiText("list.next-footer", actionFooter(config.guiText("list.next-action", "перейти вперёд"))),
                    ),
                ),
            )
        }
        inventory.setItem(
            layoutSlot(menuId, "filter"),
            styledItem(
                filter.styleRole,
                filter.icon,
                config.guiText("list.filter-name", "<#92bed8>Фильтр коллекции"),
                copyLines(
                    "list.filter-stats",
                    listOf(
                        "<#8c8c8c>Категория: <#e6fff3><category>",
                        "<#8c8c8c>Показано: <#e6fff3><shown>/<total>",
                    ),
                    "category" to config.guiText("list.filter-category-${filter.name.lowercase()}", filter.title),
                    "shown" to visible.size.toString(),
                    "total" to catalog.all.size.toString(),
                ) +
                    listOf(
                        "",
                        if (purpose != MountListPurpose.COLLECTION) "<#92bed8>ЛКМ<#e6fff3> — сменить категорию" else copy(
                            "list.filter-footer",
                            "<#8c8c8c>[<#92bed8>▶<#8c8c8c>] <#92bed8>ЛКМ<#e6fff3> — сменить <#8c8c8c>· <#92bed8>ПКМ<#e6fff3> — <owned-action>",
                            "owned-action" to
                                if (ownedOnly) {
                                    config.guiText("list.filter-show-all", "показать все")
                                } else {
                                    config.guiText("list.filter-show-owned", "только полученные")
                                },
                        ),
                    ),
                glint = effectiveOwnedOnly,
            ),
        )
        show(player, holder)
        click(player)
    }

    fun openDetail(player: Player, mountId: String) = openDetailFromSource(player, mountId, null, forceDirect = true)

    private fun openDetailFromCurrent(player: Player, mountId: String) =
        openDetailFromSource(player, mountId, previousHolder(player))

    private fun openDetailFromSource(player: Player, mountId: String, source: MountMenuHolder?, forceDirect: Boolean = false) {
        val config = configProvider()
        val mount = catalogProvider()[mountId] ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        if (forceDirect && !profile.unlocked) return openList(player)
        val previous = source ?: previousHolder(player)
        val holder = MountMenuHolder(
            MountScreen.DETAIL,
            MenuId.of("mount-detail"),
            mount.id,
            page = previous?.page ?: 0,
            skinPage = previous?.skinPage ?: 0,
            skinPageCount = previous?.skinPageCount ?: 1,
            filter = previous?.filter ?: MountFilter.ALL,
            ownedOnly = previous?.ownedOnly ?: false,
            purpose = previous?.purpose ?: MountListPurpose.COLLECTION,
            directOpen = forceDirect || (previous?.directOpen ?: true),
            parent = if (forceDirect) null else detailParent(previous),
        )
        val inventory = frame(holder, config.detailTitle.replace("<mount>", escape(mount.displayName)), full = true)
        transfers()?.let { inventory.setItem(layoutSlot(holder.menuId, "transfer"), it.button(profile.unlocked)) }
        val favorite = summons.favoriteMountId(player.uniqueId) == mount.id
        val purchaseAllowed = purchaseContextAllowed(player, holder)
        inventory.setItem(DETAIL_ICON_SLOT, items.mountIcon(mount, profile, detailed = true, favorite = favorite))
        inventory.setItem(DETAIL_FAVORITE_SLOT, items.favoriteItem(profile, favorite))
        inventory.setItem(DETAIL_UPGRADE_SLOT, items.upgradeItem(mount, profile, merchantAvailable = purchaseAllowed))
        inventory.setItem(DETAIL_SUMMON_SLOT, items.summonItem(profile, config.sessionDuration))
        inventory.setItem(DETAIL_SETTINGS_SLOT, items.tuningButtonItem(profile))
        inventory.setItem(DETAIL_WHISTLE_SLOT, items.whistleMenuItem(player, summons.favoriteMountId(player.uniqueId)))
        inventory.setItem(
            DETAIL_BACK_SLOT,
            styledItem(
                MountGuiItemRole.BACK,
                Material.BLUE_STAINED_GLASS_PANE,
                config.guiText("common.back-name", "<#92bed8>Назад"),
                actionLore(listOf(config.guiText("detail.back-description", "<#8c8c8c>Вернуться к коллекции.")), "вернуться"),
            ),
        )
        show(player, holder)
        click(player)
    }

    private fun openProgression(player: Player, mount: MountDefinition, source: MountMenuHolder? = null) {
        val config = configProvider()
        val profile = ownership.profile(subject(player), mount)
        val previous = source ?: previousHolder(player)
        val holder =
            MountMenuHolder(
                MountScreen.PROGRESSION,
                MenuId.of("mount-progression"),
                mount.id,
                purpose = previous?.purpose ?: currentPurpose(player),
                page = previous?.page ?: 0,
                skinPage = previous?.skinPage ?: 0,
                skinPageCount = previous?.skinPageCount ?: 1,
                filter = previous?.filter ?: MountFilter.ALL,
                ownedOnly = previous?.ownedOnly ?: false,
                levelCardsBySlot = LEVEL_CARD_SLOTS.take(mount.maxLevel).mapIndexed { index, slot -> slot to index + 1 }.toMap(),
                directOpen = previous?.directOpen ?: true,
                parent = navigationParent(previous, MountScreen.PROGRESSION),
            )
        val inventory = frame(holder, config.progressionTitle.replace("<mount>", escape(mount.displayName)), full = true)
        inventory.setItem(PROGRESSION_INFO_SLOT, items.progressionLevelsInfoItem(mount, profile))
        val purchaseAllowed = purchaseContextAllowed(player, holder)
        holder.levelCardsBySlot.forEach { (slot, level) ->
            inventory.setItem(slot, items.levelCardItem(mount, profile, level, merchantAvailable = purchaseAllowed))
        }
        inventory.setItem(
            PROGRESSION_BACK_SLOT,
            styledItem(
                MountGuiItemRole.BACK,
                Material.BLUE_STAINED_GLASS_PANE,
                config.guiText("common.back-name", "<#92bed8>Назад"),
                actionLore(listOf(config.guiText("progression.back-description", "<#8c8c8c>Вернуться к маунту.")), "вернуться"),
            ),
        )
        show(player, holder)
        click(player)
    }

    private fun openTuning(player: Player, mount: MountDefinition, source: MountMenuHolder? = null) {
        val config = configProvider()
        val tuning = config.tuning
        val profile = ownership.profile(subject(player), mount)
        val speedSlots = TUNING_SPEED_SLOTS.zip(tuning.speedPercentages).toMap()
        val stepSlots =
            if (mount.movement == MountMovement.WALKING) {
                TUNING_STEP_SLOTS.zip(tuning.walkingStepHeightsHundredths).toMap()
            } else {
                emptyMap()
            }
        val sizeSlots =
            mount.sizeOptions.takeIf { it.size > 1 }
                ?.let { tuningSizeSlots(it.size).zip(it.map(MountSizeOptionDefinition::id)).toMap() }
                .orEmpty()
        val abilitySlots = centeredSlots(TUNING_ABILITY_SLOTS, mount.abilities.upgrades.size)
            .zip(mount.abilities.upgrades.map(MountAbilityUpgradeDefinition::id)).toMap()
        val previous = source ?: previousHolder(player)
        val holder =
            MountMenuHolder(
                MountScreen.TUNING,
                MenuId.of("mount-tuning"),
                mount.id,
                purpose = previous?.purpose ?: currentPurpose(player),
                page = previous?.page ?: 0,
                skinPage = previous?.skinPage ?: 0,
                skinPageCount = previous?.skinPageCount ?: 1,
                filter = previous?.filter ?: MountFilter.ALL,
                ownedOnly = previous?.ownedOnly ?: false,
                speedPercentagesBySlot = speedSlots,
                stepHeightsBySlot = stepSlots,
                sizeOptionsBySlot = sizeSlots,
                abilitiesBySlot = abilitySlots,
                directOpen = previous?.directOpen ?: true,
                parent = navigationParent(previous, MountScreen.TUNING),
            )
        val inventory = frame(
            holder,
            config.guiText("settings.title", "<#20252b><bold>Настройки: <mount></bold>").replace("<mount>", escape(mount.displayName)),
            full = true,
        )
        val purchaseAllowed = purchaseContextAllowed(player, holder)
        inventory.setItem(TUNING_INFO_SLOT, items.progressionInfoItem(mount, profile, tuning))
        inventory.setItem(TUNING_GLOW_SLOT, items.glowItem(mount, profile, merchantAvailable = purchaseAllowed))
        inventory.setItem(TUNING_SKINS_SLOT, items.skinsItem(mount, profile))
        abilitySlots.forEach { (slot, abilityId) ->
            mount.ability(abilityId)?.let { ability ->
                inventory.setItem(slot, items.abilityItem(profile, ability, merchantAvailable = purchaseAllowed))
            }
        }
        speedSlots.forEach { (slot, percentage) ->
            inventory.setItem(slot, items.speedTuningItem(mount, profile, tuning, percentage))
        }
        if (mount.movement == MountMovement.WALKING) {
            stepSlots.forEach { (slot, hundredths) ->
                inventory.setItem(slot, items.stepHeightTuningItem(profile, tuning, hundredths))
            }
        }
        sizeSlots.forEach { (slot, sizeId) ->
            mount.sizeOptions.firstOrNull { it.id == sizeId }?.let { option ->
                inventory.setItem(slot, items.sizeTuningItem(mount, profile, option, merchantAvailable = purchaseAllowed))
            }
        }
        inventory.setItem(TUNING_MENU_RIDER_VIEW_SLOT, items.riderViewTuningItem(mount, profile))
        inventory.setItem(
            TUNING_MENU_BACK_SLOT,
            styledItem(
                MountGuiItemRole.BACK,
                Material.BLUE_STAINED_GLASS_PANE,
                config.guiText("common.back-name", "<#92bed8>Назад"),
                actionLore(listOf(config.guiText("settings.back-description", "<#8c8c8c>Вернуться к маунту.")), "вернуться"),
            ),
        )
        show(player, holder)
        click(player)
    }

    private fun openSkins(
        player: Player,
        mount: MountDefinition,
        source: MountMenuHolder? = null,
        requestedPage: Int? = null,
    ) {
        val config = configProvider()
        val profile = ownership.profile(subject(player), mount)
        val allSkinIds = listOf(MountDefinition.DEFAULT_SKIN_ID) + mount.skins.map(MountSkinDefinition::id)
        val previous = source ?: previousHolder(player)
        val pageCount = maxOf(1, ceil(allSkinIds.size.toDouble() / SKIN_CONTENT_SLOTS.size).toInt())
        val skinPage = (requestedPage ?: previous?.skinPage ?: 0).coerceIn(0, pageCount - 1)
        val pageSkinIds = allSkinIds.drop(skinPage * SKIN_CONTENT_SLOTS.size).take(SKIN_CONTENT_SLOTS.size)
        val slots = SKIN_CONTENT_SLOTS.zip(pageSkinIds).toMap()
        val holder = MountMenuHolder(
            MountScreen.SKINS,
            MenuId.of("mount-skins"),
            mount.id,
            page = previous?.page ?: 0,
            skinPage = skinPage,
            skinPageCount = pageCount,
            filter = previous?.filter ?: MountFilter.ALL,
            ownedOnly = previous?.ownedOnly ?: false,
            purpose = previous?.purpose ?: MountListPurpose.COLLECTION,
            skinsBySlot = slots,
            directOpen = previous?.directOpen ?: true,
            parent = navigationParent(previous, MountScreen.SKINS),
        )
        val inventory = frame(holder, config.skinsTitle.replace("<mount>", escape(mount.displayName)), full = false)
        val purchaseAllowed = purchaseContextAllowed(player, holder)
        slots.forEach { (slot, skinId) ->
            inventory.setItem(slot, items.skinItem(mount, profile, skinId, merchantAvailable = purchaseAllowed))
        }
        if (skinPage > 0) {
            inventory.setItem(
                layoutSlot(holder.menuId, "previous"),
                styledItem(
                    MountGuiItemRole.PREVIOUS,
                    Material.ARROW,
                    config.guiText("list.previous-name", "<#92bed8>Предыдущая страница"),
                    listOf(
                        copy(
                            "list.page",
                            "<#8c8c8c>Страница: <#e6fff3><page>/<pages>",
                            "page" to (skinPage + 1).toString(),
                            "pages" to pageCount.toString(),
                        ),
                    ),
                ),
            )
        }
        inventory.setItem(
            layoutSlot(holder.menuId, "back"),
            styledItem(
                MountGuiItemRole.BACK,
                Material.BLUE_STAINED_GLASS_PANE,
                config.guiText("common.back-name", "<#92bed8>Назад"),
                actionLore(listOf(config.guiText("settings.skins-back-description", "<#8c8c8c>Вернуться к настройкам.")), "вернуться"),
            ),
        )
        if (skinPage + 1 < pageCount) {
            inventory.setItem(
                layoutSlot(holder.menuId, "next"),
                styledItem(
                    MountGuiItemRole.NEXT,
                    Material.ARROW,
                    config.guiText("list.next-name", "<#92bed8>Следующая страница"),
                    listOf(
                        copy(
                            "list.page",
                            "<#8c8c8c>Страница: <#e6fff3><page>/<pages>",
                            "page" to (skinPage + 1).toString(),
                            "pages" to pageCount.toString(),
                        ),
                    ),
                ),
            )
        }
        show(player, holder)
        click(player)
    }

    private fun openConfirm(player: Player, mount: MountDefinition, action: ConfirmAction) {
        val config = configProvider()
        val previous = previousHolder(player)
        val holder = MountMenuHolder(
            MountScreen.CONFIRM,
            MenuId.of("mount-confirm"),
            mount.id,
            page = previous?.page ?: 0,
            skinPage = previous?.skinPage ?: 0,
            skinPageCount = previous?.skinPageCount ?: 1,
            filter = previous?.filter ?: MountFilter.ALL,
            ownedOnly = previous?.ownedOnly ?: false,
            purpose = previous?.purpose ?: MountListPurpose.COLLECTION,
            confirmAction = action,
            directOpen = previous?.directOpen ?: true,
            parent = previous,
        )
        val inventory = frame(holder, config.confirmTitle, full = true, fallbackMaterial = Material.BLACK_STAINED_GLASS_PANE)
        val (name, price, description) = confirmationDetails(mount, action)
        val currency = when (action) {
            is ConfirmAction.Ability -> mount.ability(action.abilityId)?.currency ?: mount.currency
            is ConfirmAction.Skin -> mount.skin(action.skinId)?.currency ?: mount.currency
            is ConfirmAction.Size -> mount.sizeOptions.firstOrNull { it.id == action.sizeId }?.currency ?: mount.currency
            ConfirmAction.Glow -> mount.glowCurrency
            is ConfirmAction.Level -> mount.currency
        }
        val balance = wallet.walletForCurrency(currency)?.balanceMinor(player.uniqueId)
        val priceMinor = price.toExactMinor()
        holder.confirmEnabled = balance != null && balance >= priceMinor
        val economyLore =
            buildList {
                addAll(description)
                add("")
                add(moneyLine("common.price", "price", priceMinor, "Цена", currency = currency))
                if (balance == null) {
                    add(config.guiText("confirm.economy-unavailable-line", "<#c42323>Баланс сейчас недоступен"))
                } else {
                    add(moneyLine("common.balance", "balance", balance, "Баланс", currency = currency))
                    if (balance >= priceMinor) {
                        add(moneyLine("common.remaining", "remaining", balance - priceMinor, "Останется", currency = currency))
                    } else {
                        add(moneyLine("common.missing", "missing", priceMinor - balance, "Не хватает", "<#c42323>", currency = currency))
                    }
                }
            }
        inventory.setItem(CONFIRM_INFO_SLOT, item(Material.SUNFLOWER, name, economyLore))
        inventory.setItem(
            CONFIRM_CANCEL_SLOT,
            styledItem(
                MountGuiItemRole.CANCEL,
                Material.RED_CONCRETE,
                config.guiText("confirm.cancel-name", "<#c42323>Отменить"),
                actionLore(listOf(config.guiText("confirm.cancel-description", "<#8c8c8c>Вернуться без покупки.")), "отменить"),
            ),
        )
        inventory.setItem(
            CONFIRM_ACCEPT_SLOT,
            when {
                balance == null -> styledItem(MountGuiItemRole.CONFIRM, Material.GRAY_CONCRETE, config.guiText("confirm.unavailable-name", "<#c42323>Экономика недоступна"), emptyList())
                balance < priceMinor -> styledItem(MountGuiItemRole.CONFIRM, Material.GRAY_CONCRETE, config.guiText("confirm.insufficient-name", "<#c42323>Недостаточно средств"), emptyList())
                else ->
                    styledItem(
                        MountGuiItemRole.CONFIRM,
                        Material.ORANGE_CONCRETE,
                        config.guiText("confirm.accept-name", "<#ff9f0f>Подтвердить покупку"),
                        actionLore(listOf(moneyLine("common.debit", "price", priceMinor, "Будет списано", currency = currency)), "купить", "<#ff9f0f>"),
                    )
            },
        )
        show(player, holder)
        click(player)
    }

    private fun previousHolder(player: Player): MountMenuHolder? = holdersByInventory[player.openInventory.topInventory]

    private fun detailParent(previous: MountMenuHolder?): MountMenuHolder? = when (previous?.screen) {
        null -> null
        MountScreen.LIST -> previous
        MountScreen.DETAIL -> previous.parent
        else -> previous.parent?.let { parent -> if (parent.screen == MountScreen.DETAIL) parent.parent else parent }
    }

    private fun navigationParent(previous: MountMenuHolder?, target: MountScreen): MountMenuHolder? = when {
        previous == null -> null
        previous.screen == MountScreen.CONFIRM -> previous.parent?.parent
        previous.screen == target -> previous.parent
        else -> previous
    }

    private fun frame(
        holder: MountMenuHolder,
        title: String,
        full: Boolean,
        fallbackMaterial: Material = Material.GRAY_STAINED_GLASS_PANE,
    ): Inventory {
        val layout = menuRuntime.current().catalog.require(holder.menuId)
        val renderedTitle = component(title)
        val inventory = Bukkit.createInventory(holder, layout.rows * 9, renderedTitle)
        holder.backingInventory = inventory
        holder.title = renderedTitle
        holder.fullBackground = full
        fill(inventory, fallbackMaterial, full)
        holder.backgroundItem = inventory.getItem(0)?.clone()?.takeIf { full }
        return inventory
    }

    private fun paperFrame(holder: MountMenuHolder): PaperMenuFrame {
        val layout = menuRuntime.current().catalog.require(holder.menuId)
        val declaredSlots = buildSet {
            layout.elements.values.flatMapTo(this) { element -> element.slots.map { it.index } }
            layout.regions.values.flatMapTo(this) { region -> region.slots.map { it.index } }
        }
        return PaperMenuFrame.physical(layout, holder.title, holder.backgroundItem).also { frame ->
            declaredSlots.forEach { slot -> frame.setItem(slot, holder.backingInventory.getItem(slot)) }
        }
    }

    private fun show(player: Player, holder: MountMenuHolder) {
        menuRuntime.session(player)?.let { holdersByInventory.remove(it.inventory) }
        holder.frame = paperFrame(holder)
        val session = menuRuntime.open(player, holder.menuId) {
            holder.frame.content { _, context -> dispatchClick(context.event, holder) }
        }
        holdersByInventory[session.inventory] = holder
    }

    private fun refresh(player: Player, holder: MountMenuHolder) {
        holder.frame = paperFrame(holder)
        menuRuntime.session(player)?.refresh()
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (!active) return
        val holder = holdersByInventory.remove(event.inventory) ?: return
        if (event.reason != InventoryCloseEvent.Reason.PLAYER) return
        val player = event.player as? Player ?: return
        val parent = holder.parent ?: return
        scheduler.runLater(1) {
            if (!active || !player.isOnline) return@runLater
            val openType = player.openInventory.topInventory.type
            if (openType != org.bukkit.event.inventory.InventoryType.CRAFTING) return@runLater
            when (parent.screen) {
                MountScreen.LIST,
                MountScreen.DETAIL,
                MountScreen.PROGRESSION,
                MountScreen.TUNING,
                MountScreen.SKINS,
                MountScreen.CONFIRM,
                -> reopenHolder(player, parent)
            }
        }
    }

    fun onClick(event: InventoryClickEvent) {
        val holder = holdersByInventory[event.view.topInventory] ?: return
        dispatchClick(event, holder)
    }

    private fun dispatchClick(event: InventoryClickEvent, holder: MountMenuHolder) {
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory !== event.view.topInventory) return
        when (event.click) {
            ClickType.LEFT -> Unit
            ClickType.RIGHT -> if (holder.screen != MountScreen.LIST) return
            else -> return
        }
        when (holder.screen) {
            MountScreen.LIST -> handleListClick(player, holder, event)
            MountScreen.DETAIL -> handleDetailClick(player, holder, event.rawSlot)
            MountScreen.PROGRESSION -> handleProgressionClick(player, holder, event.rawSlot)
            MountScreen.TUNING -> handleTuningClick(player, holder, event.rawSlot)
            MountScreen.SKINS -> handleSkinClick(player, holder, event.rawSlot)
            MountScreen.CONFIRM -> handleConfirmClick(player, holder, event.rawSlot)
        }
    }

    private fun handleListClick(player: Player, holder: MountMenuHolder, event: InventoryClickEvent) {
        when (event.rawSlot) {
            layoutSlot(holder.menuId, "previous") -> if (event.click == ClickType.LEFT && holder.page > 0) {
                openListPage(player, holder.page - 1, holder.filter, holder.ownedOnly, holder.purpose)
            }
            layoutSlot(holder.menuId, "next") -> if (event.click == ClickType.LEFT && holder.page + 1 < holder.pageCount) {
                openListPage(player, holder.page + 1, holder.filter, holder.ownedOnly, holder.purpose)
            }
            layoutSlot(holder.menuId, "filter") -> when (event.click) {
                ClickType.LEFT -> openListPage(player, 0, holder.filter.next(), holder.ownedOnly, holder.purpose)
                else -> Unit
            }
            else -> {
                val mount = holder.mountsBySlot[event.rawSlot]?.let(catalogProvider()::get) ?: return
                val profile = ownership.profile(subject(player), mount)
                when {
                    holder.purpose == MountListPurpose.UPGRADES && profile.unlocked && event.click == ClickType.LEFT -> openProgression(player, mount)
                    holder.purpose == MountListPurpose.UPGRADES && profile.unlocked && event.click == ClickType.RIGHT -> openDetailFromCurrent(player, mount.id)
                    holder.purpose == MountListPurpose.TRADE && profile.unlocked && event.click == ClickType.LEFT -> transfers()?.confirm(player, mount.id) { openListPage(player, holder.page, holder.filter, holder.ownedOnly, holder.purpose) }
                    holder.purpose == MountListPurpose.SHOP && profile.unlocked && event.click == ClickType.LEFT -> openDetailFromCurrent(player, mount.id)
                    profile.unlocked && event.click == ClickType.LEFT -> summon(player, mount)
                    profile.unlocked && event.click == ClickType.RIGHT -> openDetailFromCurrent(player, mount.id)
                    holder.purpose == MountListPurpose.SHOP && !profile.unlocked && mount.price(1) != null && event.click == ClickType.LEFT ->
                        if (requireMerchantForPurchase(player, holder)) openProgression(player, mount)
                }
            }
        }
    }

    private fun handleDetailClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val transfer = transfers()
        if (transfer != null && slot == transfer.detailSlot) {
            val mount = holder.mountId?.let(catalogProvider()::get)
            if (mount != null && ownership.profile(subject(player), mount).unlocked) {
                transfer.confirm(player, mount.id) { openDetailFromSource(player, mount.id, holder) }
            }
            return
        }
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        when (slot) {
            DETAIL_BACK_SLOT -> if (holder.directOpen) player.closeInventory() else {
                openListPage(player, holder.page, holder.filter, holder.ownedOnly, holder.purpose)
            }
            DETAIL_FAVORITE_SLOT -> if (profile.unlocked && summons.favoriteMountId(player.uniqueId) != mount.id) selectFavorite(player, mount)
            DETAIL_SUMMON_SLOT -> if (profile.unlocked) summon(player, mount)
            DETAIL_SETTINGS_SLOT -> if (profile.unlocked) openTuning(player, mount)
            DETAIL_WHISTLE_SLOT -> {
                val hasFavorite = summons.favoriteMountId(player.uniqueId) != null
                val hasWhistle = player.inventory.contents.any(quickSummons::isWhistle)
                if (hasFavorite && !hasWhistle && configProvider().quickSummonWhistle && player.inventory.firstEmpty() >= 0) {
                    quickSummons.giveWhistle(player)
                    openDetailFromCurrent(player, mount.id)
                }
            }
            DETAIL_UPGRADE_SLOT -> {
                when {
                    profile.unlocked -> openProgression(player, mount)
                    mount.price(1) != null && requireMerchantForPurchase(player, holder) -> openProgression(player, mount)
                }
            }
        }
    }

    private fun handleProgressionClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        holder.levelCardsBySlot[slot]?.let { level ->
            if (
                level == profile.level + 1 &&
                mount.price(level) != null &&
                configProvider().purchasesEnabled &&
                requireMerchantForPurchase(player, holder)
            ) {
                openConfirm(player, mount, ConfirmAction.Level(level))
            }
            return
        }
        when (slot) {
            PROGRESSION_BACK_SLOT -> reopenHolder(player, holder.parent)
        }
    }

    private fun handleTuningClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        if (!profile.unlocked) return openDetailFromCurrent(player, mount.id)
        val tuning = configProvider().tuning
        when (slot) {
            TUNING_MENU_BACK_SLOT -> reopenHolder(player, holder.parent)
            TUNING_SKINS_SLOT -> openSkins(player, mount)
            TUNING_GLOW_SLOT -> when {
                profile.glowOwned -> purchases.setGlowEnabled(subject(player), mount, !profile.glowEnabled) {
                    handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.TUNING)
                }
                mount.glowPrice != null && configProvider().purchasesEnabled && requireMerchantForPurchase(player, holder) ->
                    openConfirm(player, mount, ConfirmAction.Glow)
            }
            TUNING_MENU_RIDER_VIEW_SLOT -> purchases.setRiderViewAutoHide(subject(player), mount, !mount.effectiveRiderViewAutoHide(profile)) {
                handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.TUNING)
            }
            else -> {
                holder.abilitiesBySlot[slot]?.let { abilityId ->
                    if (!profile.ownsAbility(abilityId) && configProvider().purchasesEnabled && requireMerchantForPurchase(player, holder)) {
                        openConfirm(player, mount, ConfirmAction.Ability(abilityId))
                    }
                    return
                }
                holder.speedPercentagesBySlot[slot]?.let { percentage ->
                    if (tuning.speedPercentage(profile.selectedSpeedPercentage) == percentage) return
                    purchases.setSpeedTuning(subject(player), mount, tuning, percentage) {
                        handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.TUNING)
                    }
                    return
                }
                holder.stepHeightsBySlot[slot]?.let { hundredths ->
                    if (hundredths !in tuning.availableStepHeightsHundredths(profile.level) ||
                        tuning.stepHeightHundredths(profile.level, profile.selectedStepHeightHundredths) == hundredths
                    ) return
                    purchases.setStepHeightTuning(subject(player), mount, tuning, hundredths) {
                        handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.TUNING)
                    }
                    return
                }
                holder.sizeOptionsBySlot[slot]?.let { sizeId ->
                    val option = mount.sizeOptions.firstOrNull { it.id == sizeId } ?: return
                    if (option.minimumLevel > profile.level) return
                    if (!profile.ownsSize(option)) {
                        if (option.grantOnly && option.price != null && configProvider().purchasesEnabled && requireMerchantForPurchase(player, holder)) {
                            openConfirm(player, mount, ConfirmAction.Size(sizeId))
                        }
                        return
                    }
                    if (mount.effectiveSizeOption(profile.selectedSizeId, profile.level, profile.ownedSizeIds)?.id != sizeId) {
                        purchases.setSizeTuning(subject(player), mount, sizeId) {
                            handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.TUNING)
                        }
                    }
                }
            }
        }
    }

    private fun handleSkinClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        when (slot) {
            SKINS_PREVIOUS_SLOT -> return openSkins(player, mount, requestedPage = holder.skinPage - 1)
            SKINS_NEXT_SLOT -> return openSkins(player, mount, requestedPage = holder.skinPage + 1)
            SKINS_BACK_SLOT -> return reopenHolder(player, holder.parent)
        }
        val skinId = holder.skinsBySlot[slot] ?: return
        val profile = ownership.profile(subject(player), mount)
        if (profile.ownsSkin(skinId)) {
            if (profile.activeSkinId == skinId) return
            purchases.setActiveSkin(subject(player), mount, skinId) {
                handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.SKINS)
            }
            return
        }
        val skin = mount.skin(skinId) ?: return
        if (skin.price == null || !configProvider().purchasesEnabled) return
        if (requireMerchantForPurchase(player, holder)) openConfirm(player, mount, ConfirmAction.Skin(skinId))
    }

    private fun handleConfirmClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val action = holder.confirmAction ?: return openDetailFromCurrent(player, mount.id)
        when (slot) {
            CONFIRM_CANCEL_SLOT -> when (action) {
                is ConfirmAction.Skin -> openSkins(player, mount)
                is ConfirmAction.Level -> openProgression(player, mount)
                is ConfirmAction.Ability -> openTuning(player, mount)
                is ConfirmAction.Size -> openTuning(player, mount)
                ConfirmAction.Glow -> openTuning(player, mount)
            }
            CONFIRM_ACCEPT_SLOT -> {
                if (!requireMerchantForPurchase(player, holder)) {
                    reopen(
                        player,
                        mount,
                        when (action) {
                            is ConfirmAction.Skin -> MountScreen.SKINS
                            is ConfirmAction.Level -> MountScreen.PROGRESSION
                            is ConfirmAction.Ability -> MountScreen.TUNING
                            is ConfirmAction.Size -> MountScreen.TUNING
                            ConfirmAction.Glow -> MountScreen.TUNING
                        },
                    )
                    return
                }
                if (!holder.confirmEnabled) return
                holder.confirmEnabled = false
                holder.backingInventory.setItem(
                    CONFIRM_ACCEPT_SLOT,
                    styledItem(
                        MountGuiItemRole.CONFIRM,
                        Material.GRAY_CONCRETE,
                        configProvider().guiText("confirm.loading-name", "<#969696>Покупка выполняется…"),
                        emptyList(),
                    ),
                )
                refresh(player, holder)
                val callback: (MountPurchaseResult) -> Unit = { result ->
                    handlePurchaseResult(
                        player,
                        mount,
                        result,
                        reopen =
                            when (action) {
                                is ConfirmAction.Skin -> MountScreen.SKINS
                                is ConfirmAction.Level -> MountScreen.PROGRESSION
                                is ConfirmAction.Ability -> MountScreen.TUNING
                                is ConfirmAction.Size -> MountScreen.TUNING
                                ConfirmAction.Glow -> MountScreen.TUNING
                            },
                    )
                }
                when (action) {
                    is ConfirmAction.Level -> purchases.purchaseLevel(subject(player), mount, action.level, callback)
                    ConfirmAction.Glow -> purchases.purchaseGlow(subject(player), mount, callback)
                    is ConfirmAction.Skin -> mount.skin(action.skinId)?.let { purchases.purchaseSkin(subject(player), mount, it, callback) }
                    is ConfirmAction.Ability -> mount.ability(action.abilityId)?.let { purchases.purchaseAbility(subject(player), mount, it, callback) }
                    is ConfirmAction.Size -> purchases.purchaseSize(subject(player), mount, action.sizeId, callback)
                }
            }
        }
    }

    private fun summon(player: Player, mount: MountDefinition) {
        val outcome = summons.summon(player, mount)
        if (outcome == MountSummonOutcome.SUCCESS) {
            player.closeInventory()
            return
        }
        summons.sendFeedback(player, outcome)
    }

    private fun selectFavorite(player: Player, mount: MountDefinition) {
        summons.selectFavorite(player, mount).whenComplete { outcome, failure ->
            scheduler.runSync(
                Runnable {
                    if (!active || !player.isOnline) return@Runnable
                    when {
                        failure != null || outcome == MountFavoriteSelectionOutcome.PERSISTENCE_FAILED -> {
                            error("Unable to save favorite mount for {}: {}", player.name, failure?.javaClass?.simpleName ?: "persistence_failed")
                            send(player, "setting-failed", "<red>Не удалось сохранить настройку.")
                            bass(player)
                        }
                        outcome == MountFavoriteSelectionOutcome.NOT_UNLOCKED -> {
                            send(player, "not-unlocked", "<red>Сначала разблокируйте маунта.")
                            bass(player)
                        }
                        else -> {
                            val configured = configProvider().message(
                                "favorite-saved",
                                "<green>Любимый маунт выбран: <white><mount><green>.",
                            )
                            player.sendMessage(component(configured.replace("<mount>", escape(mount.displayName))))
                            click(player)
                            openDetailFromCurrent(player, mount.id)
                        }
                    }
                },
            )
        }
    }

    private fun handlePurchaseResult(
        player: Player,
        mount: MountDefinition,
        result: MountPurchaseResult,
        purchase: Boolean = true,
        reopen: MountScreen = MountScreen.DETAIL,
    ) {
        if (!active || !player.isOnline) return
        when (result) {
            MountPurchaseResult.Success -> {
                val update = summons.refreshActive(player, mount)
                val (path, fallback) =
                    if (purchase && update == MountSessionUpdateResult.UNSAFE_APPEARANCE) {
                        "purchase-success-next-summon" to
                            "<yellow>Покупка сохранена; новый облик применится при следующем безопасном призыве."
                    } else if (purchase) {
                        "purchase-success" to "<green>Покупка сохранена!"
                    } else if (update == MountSessionUpdateResult.UNSAFE_APPEARANCE) {
                        "setting-next-summon" to "<yellow>Настройка сохранена и применится при следующем призыве."
                    } else if (update == MountSessionUpdateResult.APPLIED) {
                        "setting-applied" to "<green>Настройка сохранена и применена к активному маунту."
                    } else {
                        "setting-saved" to "<green>Настройка сохранена."
                    }
                send(player, path, fallback)
                click(player)
                reopen(player, mount, reopen)
            }
            MountPurchaseResult.Busy -> send(player, "purchase-busy", "<yellow>Предыдущая операция ещё выполняется.")
            MountPurchaseResult.AlreadyOwned -> {
                send(player, "already-owned", "<yellow>Это уже выбрано или куплено.")
                reopen(player, mount, reopen)
            }
            MountPurchaseResult.InvalidLevel -> send(player, "invalid-level", "<red>Сначала купите предыдущий уровень.")
            MountPurchaseResult.NotUnlocked -> send(player, "not-unlocked", "<red>Сначала разблокируйте маунта или облик.")
            MountPurchaseResult.NotForSale -> send(player, "not-for-sale", "<red>Это улучшение нельзя купить.")
            MountPurchaseResult.MerchantRequired -> {
                send(player, "merchant-required", "<yellow>Покупки доступны только рядом с Гектором или Эммой на спавне.")
                reopen(player, mount, reopen)
            }
            MountPurchaseResult.PurchasesDisabled -> purchasesDisabled(player)
            MountPurchaseResult.EconomyUnavailable -> send(player, "economy-unavailable", "<red>Экономика временно недоступна.")
            MountPurchaseResult.InsufficientFunds -> send(player, "not-enough-money", "<red>Недостаточно денег.")
            MountPurchaseResult.PaymentFailed -> send(player, "payment-failed", "<red>Платёж не прошёл. Деньги не списаны.")
            MountPurchaseResult.JournalUnavailable -> send(player, "journal-unavailable", "<red>Покупки временно приостановлены. Деньги не списаны.")
            MountPurchaseResult.PersistenceFailed -> send(player, "setting-failed", "<red>Не удалось сохранить настройку.")
            MountPurchaseResult.PersistenceFailedRefunded -> send(player, "save-failed-refunded", "<red>Покупка не сохранилась. Деньги полностью возвращены.")
            MountPurchaseResult.PersistenceFailedRefundFailed,
            MountPurchaseResult.ManualReview,
            -> {
                error("Mount purchase requires manual review for {} / {}", player.name, mount.id)
                send(player, "purchase-manual-review", "<dark_red>Покупка приостановлена для безопасной проверки. Повторно не покупайте; обратитесь к администрации.")
            }
        }
        if (result != MountPurchaseResult.Success && result != MountPurchaseResult.AlreadyOwned) bass(player)
    }

    private fun reopen(player: Player, mount: MountDefinition, screen: MountScreen) {
        when (screen) {
            MountScreen.PROGRESSION -> openProgression(player, mount)
            MountScreen.TUNING -> openTuning(player, mount)
            MountScreen.SKINS -> openSkins(player, mount)
            else -> openDetailFromCurrent(player, mount.id)
        }
    }

    private fun confirmationDetails(mount: MountDefinition, action: ConfirmAction): Triple<String, Double, List<String>> =
        when (action) {
            is ConfirmAction.Level -> {
                val level = mount.level(action.level)
                Triple(
                    copy(
                        "confirm.level-name",
                        "<#92bed8>Уровень <level>",
                        "level" to action.level.toString(),
                    ),
                    checkNotNull(level.price),
                    buildList {
                        add(copy("confirm.mount-line", "<#8c8c8c>Маунт: <#e6fff3><mount>", "mount" to escape(mount.displayName)))
                        add(
                            copy(
                                "confirm.maximum-speed",
                                "<#8c8c8c>Максимальная скорость: <#e6fff3><speed>",
                                "speed" to formatSpeed(level.speed),
                            ),
                        )
                        if (mount.movement == MountMovement.WALKING) {
                            add(
                                copy(
                                    "confirm.maximum-step",
                                    "<#8c8c8c>Максимальный подъём: <#e6fff3><step> блока",
                                    "step" to formatHeight(configProvider().tuning.maximumStepHeightHundredths(action.level) / 100.0),
                                ),
                            )
                        }
                    },
                )
            }
            ConfirmAction.Glow ->
                Triple(
                    copy("confirm.glow-name", "<#92bed8>Свечение"),
                    checkNotNull(mount.glowPrice),
                    listOf(
                        copy("confirm.mount-line", "<#8c8c8c>Маунт: <#e6fff3><mount>", "mount" to escape(mount.displayName)),
                        copy("confirm.glow-permanent-line", "<#8c8c8c>Косметика покупается навсегда."),
                    ),
                )
            is ConfirmAction.Skin -> {
                val skin = checkNotNull(mount.skin(action.skinId))
                Triple(
                    copy("confirm.info-name", "<#ffacd5><skin>", "skin" to escape(skin.displayName)),
                    checkNotNull(skin.price),
                    listOf(
                        copy("confirm.mount-line", "<#8c8c8c>Маунт: <#e6fff3><mount>", "mount" to escape(mount.displayName)),
                        copy("confirm.permanent-line", "<#8c8c8c>Облик покупается навсегда."),
                    ),
                )
            }
            is ConfirmAction.Ability -> {
                val ability = checkNotNull(mount.ability(action.abilityId))
                Triple(
                    copy(
                        "confirm.ability-name",
                        "<#92bed8><ability>",
                        "ability" to escape(ability.displayName),
                    ),
                    ability.price,
                    listOf(copy("confirm.mount-line", "<#8c8c8c>Маунт: <#e6fff3><mount>", "mount" to escape(mount.displayName))) +
                        ability.description.map {
                            copy(
                                "confirm.ability-description",
                                "<#8c8c8c><ability-description>",
                                "ability-description" to escape(it),
                            )
                        },
                )
            }
            is ConfirmAction.Size -> {
                val size = checkNotNull(mount.sizeOptions.firstOrNull { it.id == action.sizeId && it.grantOnly })
                Triple(
                    copy("progression.size-name", "<#92bed8>Размер: <size>", "size" to escape(size.displayName)),
                    checkNotNull(size.price),
                    listOf(
                        copy("confirm.mount-line", "<#8c8c8c>Маунт: <#e6fff3><mount>", "mount" to escape(mount.displayName)),
                        copy("confirm.permanent-line", "<#8c8c8c>Особый размер приобретается навсегда."),
                    ),
                )
            }
        }

    private fun purchasesDisabled(player: Player) {
        send(player, "purchases-disabled", "<yellow>Покупки маунтов доступны у Гектора или Эммы во дворе маунтов на спавне.")
        bass(player)
    }

    private fun reopenHolder(player: Player, holder: MountMenuHolder?) {
        when (holder?.screen) {
            MountScreen.LIST -> openListPage(player, holder.page, holder.filter, holder.ownedOnly, holder.purpose)
            MountScreen.DETAIL -> holder.mountId?.let { openDetailFromSource(player, it, holder) }
            MountScreen.PROGRESSION -> holder.mountId?.let { catalogProvider()[it]?.let { mount -> openProgression(player, mount, holder) } }
            MountScreen.TUNING -> holder.mountId?.let { catalogProvider()[it]?.let { mount -> openTuning(player, mount, holder) } }
            MountScreen.SKINS -> holder.mountId?.let { catalogProvider()[it]?.let { mount -> openSkins(player, mount, holder) } }
            MountScreen.CONFIRM,
            null,
            -> openList(player)
        }
    }

    private fun purchaseContextAllowed(player: Player, holder: MountMenuHolder? = previousHolder(player)): Boolean =
        holder?.purpose in setOf(MountListPurpose.SHOP, MountListPurpose.UPGRADES) && merchantAllowed(player)

    private fun requireMerchantForPurchase(player: Player, holder: MountMenuHolder? = previousHolder(player)): Boolean {
        if (holder?.purpose !in setOf(MountListPurpose.SHOP, MountListPurpose.UPGRADES)) {
            send(player, "merchant-required", "<#ff9f0f>Покупки доступны только из магазина или улучшений у Гектора или Эммы во дворе маунтов на спавне.")
            bass(player)
            return false
        }
        if (merchantAllowed(player)) return true
        send(player, "merchant-required", "<#ff9f0f>Покупки маунтов доступны у Гектора или Эммы во дворе маунтов на спавне.")
        bass(player)
        return false
    }

    private fun layoutRows(menu: MenuId): Int = menuRuntime.current().catalog.require(menu).rows * 9

    private fun layoutSlot(menu: MenuId, element: String): Int =
        menuRuntime.current().catalog.require(menu).slot(element).index

    private fun layoutRegion(menu: MenuId, region: MenuRegionId): List<Int> =
        menuRuntime.current().catalog.require(menu).region(region).map { it.index }

    private val DETAIL_ICON_SLOT get() = layoutSlot(MOUNT_DETAIL, "icon")
    private val DETAIL_FAVORITE_SLOT get() = layoutSlot(MOUNT_DETAIL, "favorite")
    private val DETAIL_UPGRADE_SLOT get() = layoutSlot(MOUNT_DETAIL, "upgrade")
    private val DETAIL_SUMMON_SLOT get() = layoutSlot(MOUNT_DETAIL, "summon")
    private val DETAIL_SETTINGS_SLOT get() = layoutSlot(MOUNT_DETAIL, "settings")
    private val DETAIL_BACK_SLOT get() = layoutSlot(MOUNT_DETAIL, "back")
    private val DETAIL_WHISTLE_SLOT get() = layoutSlot(MOUNT_DETAIL, "whistle")
    private val LEVEL_CARD_SLOTS get() = layoutRegion(MOUNT_PROGRESSION, LEVELS_REGION)

    private val PROGRESSION_INFO_SLOT get() = layoutSlot(MOUNT_PROGRESSION, "info")
    private val PROGRESSION_BACK_SLOT get() = layoutSlot(MOUNT_PROGRESSION, "back")

    private val TUNING_INFO_SLOT get() = layoutSlot(MOUNT_TUNING, "info")
    private val TUNING_GLOW_SLOT get() = layoutSlot(MOUNT_TUNING, "glow")
    private val TUNING_SKINS_SLOT get() = layoutSlot(MOUNT_TUNING, "skins")
    private val TUNING_ABILITY_SLOTS get() = layoutRegion(MOUNT_TUNING, ABILITIES_REGION)
    private val TUNING_SPEED_SLOTS get() = layoutRegion(MOUNT_TUNING, SPEEDS_REGION)
    private val TUNING_STEP_SLOTS get() = layoutRegion(MOUNT_TUNING, STEPS_REGION)
    private val TUNING_SIZE_SLOTS get() = layoutRegion(MOUNT_TUNING, SIZES_REGION)
    private val TUNING_MENU_BACK_SLOT get() = layoutSlot(MOUNT_TUNING, "back")
    private val TUNING_MENU_RIDER_VIEW_SLOT get() = layoutSlot(MOUNT_TUNING, "rider-view")
    private val SKIN_CONTENT_SLOTS get() = layoutRegion(MOUNT_SKINS, SKINS_REGION)
    private val SKINS_PREVIOUS_SLOT get() = layoutSlot(MOUNT_SKINS, "previous")
    private val SKINS_NEXT_SLOT get() = layoutSlot(MOUNT_SKINS, "next")
    private val SKINS_BACK_SLOT get() = layoutSlot(MOUNT_SKINS, "back")

    private val CONFIRM_INFO_SLOT get() = layoutSlot(MOUNT_CONFIRM, "info")
    private val CONFIRM_ACCEPT_SLOT get() = layoutSlot(MOUNT_CONFIRM, "accept")
    private val CONFIRM_CANCEL_SLOT get() = layoutSlot(MOUNT_CONFIRM, "cancel")

    private fun fill(inventory: Inventory, fallbackMaterial: Material = Material.GRAY_STAINED_GLASS_PANE, full: Boolean = false) {
        val background = styledItem(MountGuiItemRole.BACKGROUND, fallbackMaterial, " ", emptyList(), hideTooltip = true)
        val firstSlot = if (full) 0 else (inventory.size - 9).coerceAtLeast(0)
        for (slot in firstSlot until inventory.size) inventory.setItem(slot, background)
    }

    private fun styledItem(
        role: MountGuiItemRole,
        fallbackMaterial: Material,
        display: String,
        lore: List<String>,
        glint: Boolean = false,
        hideTooltip: Boolean = false,
    ): ItemStack {
        val style = configProvider().guiStyle(role)
        val defaultMaterial =
            when (role) {
                MountGuiItemRole.PREVIOUS,
                MountGuiItemRole.NEXT,
                -> Material.BLUE_STAINED_GLASS_PANE
                else -> fallbackMaterial
            }
        val defaultModelData =
            when (role) {
                MountGuiItemRole.PREVIOUS -> 11013 // arc:left_gray
                MountGuiItemRole.NEXT -> 11012 // arc:right_gray
                else -> null
            }
        return item(
            style.material ?: defaultMaterial,
            display,
            lore,
            glint,
            style.customModelData ?: defaultModelData,
            hideTooltip,
        )
    }

    private fun item(
        material: Material,
        display: String,
        lore: List<String>,
        glint: Boolean = false,
        customModelData: Int? = null,
        hideTooltip: Boolean = false,
    ): ItemStack =
        ItemStack(material).also { stack ->
            stack.editMeta { meta ->
                meta.displayName(component(display))
                meta.lore(lore.map(::component))
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
                meta.setEnchantmentGlintOverride(glint)
                meta.setHideTooltip(hideTooltip)
                @Suppress("DEPRECATION")
                customModelData?.let(meta::setCustomModelData)
            }
        }

    private fun subject(player: Player) = MountPermissionSubject(player.uniqueId, player.name, player::hasPermission)

    private fun tuningSizeSlots(count: Int): List<Int> = centeredSlots(TUNING_SIZE_SLOTS, count)
    private fun send(player: Player, path: String, fallback: String) = player.sendMessage(component(configProvider().message(path, fallback)))
    private fun click(player: Player) = player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.1f)
    private fun bass(player: Player) = player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f)
    private fun component(text: String): Component = TextUtils.mm(text, true)
    private fun escape(value: String): String = value.replace("<", "\\<").replace(">", "\\>")
    private fun copy(path: String, fallback: String, vararg values: Pair<String, String>): String =
        fillTemplate(configProvider().guiText(path, fallback), *values)

    private fun copyLines(path: String, fallback: List<String>, vararg values: Pair<String, String>): List<String> =
        configProvider().guiLines(path, fallback).map { fillTemplate(it, *values) }

    private fun fillTemplate(template: String, vararg values: Pair<String, String>): String =
        values.fold(template) { current, (key, value) -> current.replace("<$key>", value) }

    private fun actionFooter(action: String, accent: String = "<#92bed8>"): String {
        val composedPath =
            when (action) {
                "вернуться" -> "common.footer-back"
                "открыть" -> "common.footer-open"
                "открыть покупку" -> "common.footer-open-purchase"
                "выбрать" -> "common.footer-select"
                "призвать" -> "common.footer-summon"
                "включить" -> "common.footer-enable"
                "выключить" -> "common.footer-disable"
                "получить" -> "common.footer-get"
                "купить" -> "common.footer-buy"
                "отменить" -> "common.footer-cancel"
                else -> null
            }
        composedPath?.let { path ->
            configProvider().guiText(path, "").takeIf(String::isNotEmpty)?.let { return it }
        }
        val path = if (accent == "<#ff9f0f>") "common.action-footer-warning" else "common.action-footer"
        val fallback = "<#8c8c8c>[${accent}▶<#8c8c8c>] ${accent}ЛКМ<#e6fff3> — <action>"
        return configProvider().guiText(path, fallback).replace("<action>", action)
    }

    private fun actionLore(
        content: List<String>,
        action: String,
        accent: String = "<#92bed8>",
    ): List<String> = content.dropLastWhile(String::isEmpty) + "" + actionFooter(action, accent)

    private fun moneyLine(
        path: String,
        placeholder: String,
        amountMinor: Long,
        fallbackLabel: String,
        fallbackColor: String = "<#8c8c8c>",
        currency: String = "vault",
    ): String =
        copy(
            "$path-$currency",
            "$fallbackColor$fallbackLabel: <#ffacd5><$placeholder> ${currencyLabel(configProvider(), currency)}",
            placeholder to TextUtils.formatAmount(amountMinor.minorToDouble()),
            "currency" to currencyLabel(configProvider(), currency),
        )

    private fun formatSpeed(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    private fun formatHeight(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)

}

private val MOUNT_LIST = MenuId.of("mount-list")
private val MOUNT_DETAIL = MenuId.of("mount-detail")
private val MOUNT_PROGRESSION = MenuId.of("mount-progression")
private val MOUNT_TUNING = MenuId.of("mount-tuning")
private val MOUNT_SKINS = MenuId.of("mount-skins")
private val MOUNT_CONFIRM = MenuId.of("mount-confirm")
private val MOUNT_ENTRIES = MenuRegionId.of("mounts")
private val LEVELS_REGION = MenuRegionId.of("levels")
private val SPEEDS_REGION = MenuRegionId.of("speeds")
private val STEPS_REGION = MenuRegionId.of("steps")
private val SIZES_REGION = MenuRegionId.of("sizes")
private val ABILITIES_REGION = MenuRegionId.of("abilities")
private val SKINS_REGION = MenuRegionId.of("skins")

private fun centeredSlots(available: List<Int>, count: Int): List<Int> {
    val requested = count.coerceIn(0, available.size)
    if (requested == 0) return emptyList()
    if (requested == available.size) return available
    if (available.size == 5) return when (requested) {
        1 -> listOf(available[2])
        2 -> listOf(available[1], available[3])
        3 -> listOf(available[0], available[2], available[4])
        4 -> listOf(available[0], available[1], available[3], available[4])
        else -> available
    }
    val pool = if (requested % 2 == 0 && available.size % 2 == 1) {
        available.filterIndexed { index, _ -> index != available.size / 2 }
    } else {
        available
    }
    return pool.drop((pool.size - requested) / 2).take(requested)
}

internal fun prioritizeUnlockedMounts(
    mounts: List<MountDefinition>,
    profile: (MountDefinition) -> MountProfile,
): List<MountDefinition> {
    val (unlocked, locked) = mounts.partition { mount -> profile(mount).unlocked }
    return unlocked + locked
}
