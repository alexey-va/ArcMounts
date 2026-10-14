package ru.arc.mounts

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.util.TextUtils

enum class MountWhistleGiveOutcome {
    GIVEN,
    ALREADY_OWNED,
    FAVORITE_NOT_SELECTED,
    INVENTORY_FULL,
    DISABLED,
}

class MountQuickSummonController(
    private val plugin: JavaPlugin,
    private val configProvider: () -> MountModuleConfig,
    private val summons: MountSummonService,
) : Listener {
    private val whistleKey = NamespacedKey(plugin, "mount_whistle")
    @Volatile private var active = false

    fun start() {
        if (active) return
        active = true
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun shutdown() {
        active = false
        HandlerList.unregisterAll(this)
    }

    fun summonFavorite(player: Player): Boolean {
        if (!active || !configProvider().quickSummonSneakSwapHands) return false
        if (!player.hasPermission(USE_PERMISSION)) return false
        summons.sendFeedback(player, summons.summonFavorite(player))
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onUseWhistle(event: PlayerInteractEvent) {
        if (!active || !configProvider().quickSummonWhistle) return
        if (event.action !in RIGHT_CLICK_ACTIONS || !isWhistle(event.item)) return
        if (!event.player.hasPermission(USE_PERMISSION)) return

        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        event.isCancelled = true
        summons.sendFeedback(event.player, summons.summonFavorite(event.player))
    }

    fun giveWhistle(player: Player): MountWhistleGiveOutcome {
        val outcome =
            when {
                !configProvider().quickSummonWhistle -> MountWhistleGiveOutcome.DISABLED
                summons.favoriteMountId(player.uniqueId) == null -> MountWhistleGiveOutcome.FAVORITE_NOT_SELECTED
                player.inventory.contents.any(::isWhistle) -> MountWhistleGiveOutcome.ALREADY_OWNED
                player.inventory.firstEmpty() < 0 -> MountWhistleGiveOutcome.INVENTORY_FULL
                else -> {
                    player.inventory.addItem(createWhistle())
                    MountWhistleGiveOutcome.GIVEN
                }
            }
        val (path, fallback) =
            when (outcome) {
                MountWhistleGiveOutcome.GIVEN -> "whistle-given" to "<green>Свисток маунта добавлен в инвентарь."
                MountWhistleGiveOutcome.ALREADY_OWNED -> "whistle-already-owned" to "<yellow>Свисток уже лежит у вас в инвентаре."
                MountWhistleGiveOutcome.FAVORITE_NOT_SELECTED ->
                    "favorite-not-selected" to "<yellow>Сначала выберите любимого маунта в /mount."
                MountWhistleGiveOutcome.INVENTORY_FULL -> "inventory-full" to "<red>Освободите место в инвентаре."
                MountWhistleGiveOutcome.DISABLED -> "whistle-disabled" to "<yellow>Свисток маунта отключён на этом сервере."
            }
        player.sendMessage(component(configProvider().message(path, fallback)))
        player.playSound(
            player.location,
            if (outcome == MountWhistleGiveOutcome.GIVEN) Sound.ENTITY_HORSE_SADDLE else Sound.BLOCK_NOTE_BLOCK_BASS,
            0.8f,
            if (outcome == MountWhistleGiveOutcome.GIVEN) 1.2f else 0.8f,
        )
        return outcome
    }

    fun createWhistle(): ItemStack {
        val style = configProvider().guiStyle(MountGuiItemRole.WHISTLE)
        return ItemStack(style.material ?: Material.GOAT_HORN).also { stack ->
            stack.editMeta { meta ->
                meta.displayName(component("<#ffacd5><bold>Свисток маунта"))
                meta.lore(
                    listOf(
                        component("<#8c8c8c>ПКМ — призвать любимого маунта"),
                        component("<#8c8c8c>Выбор меняется в меню /mount"),
                    ),
                )
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
                @Suppress("DEPRECATION")
                style.customModelData?.let(meta::setCustomModelData)
                meta.persistentDataContainer.set(whistleKey, PersistentDataType.BYTE, 1)
            }
        }
    }

    fun isWhistle(stack: ItemStack?): Boolean =
        stack?.itemMeta?.persistentDataContainer?.get(whistleKey, PersistentDataType.BYTE) == 1.toByte()

    private fun component(text: String): Component = TextUtils.mm(text, true)

    private companion object {
        const val USE_PERMISSION = "arc.mounts.use"
        val RIGHT_CLICK_ACTIONS = setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)
    }
}
