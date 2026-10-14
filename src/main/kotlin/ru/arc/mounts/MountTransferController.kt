package ru.arc.mounts

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtils
import java.util.UUID
import java.nio.file.Path

private class MountPackingMenu(val mountId: String, val back: (() -> Unit)? = null) : InventoryHolder {
    lateinit var contents: Inventory
    var accepted = false
    override fun getInventory() = contents
}

class MountTransferController(
    private val plugin: JavaPlugin,
    private val config: MountTransferConfig,
    private val catalog: MountCatalog,
    private val ledger: OneTimeUseLedger,
    ownership: MountTransferOwnership,
    private val sessions: MountSessionController,
    otherBusy: (UUID) -> Boolean,
    private val openDetail: (Player, String) -> Unit,
    private val profile: ((UUID, MountDefinition) -> MountProfile)? = null,
    scheduler: TaskScheduler,
    private val backgroundStyle: () -> MountGuiItemStyle?,
    dataPath: Path,
) : Listener, AutoCloseable {
    private val scope = LifecycleTaskScope(scheduler)
    private val key = NamespacedKey("arc", "mount_certificate")
    private val store = FileMountTransferStore(dataPath)
    private val flow = MountTransferFlow(store, ownership, ledger, { scope.runSync(it) }, otherBusy)
    val detailSlot get() = config.detailSlot

    fun start() {
        require(detailSlot in 0..44 && detailSlot !in setOf(4, 13, 20, 22, 24, 29, 30, 31, 32, 33, 36, 40, 42))
        plugin.server.pluginManager.registerEvents(this, plugin)
        store.records().filter { it.stage in setOf(MountTransferStage.PACKING, MountTransferStage.CLAIMING, MountTransferStage.APPLIED) }
            .forEach { record -> catalog[record.mountId]?.let { mount ->
                flow.recover(record, mount) { outcome -> handle(outcome, record.recipient ?: record.issuer) }
            } }
        plugin.server.onlinePlayers.forEach(::recoverDelivery)
    }

    fun isBusy(playerId: UUID): Boolean = flow.isBusy(playerId)

    internal fun isBusyWithoutOther(playerId: UUID): Boolean = flow.isBusyWithoutOther(playerId)

    fun button(unlocked: Boolean): ItemStack = item(
        if (unlocked) Material.NAME_TAG else Material.GRAY_DYE,
        text("pack-name", "<#92bed8>Передать или продать маунта"),
        if (unlocked) lines("pack-lore", listOf(
            "<#e6fff3>Упаковать маунта вместе с улучшениями",
            "<#e6fff3>в передаваемое свидетельство.", "",
            "<#8c8c8c>После упаковки он покинет коллекцию.", "",
            "<#8c8c8c>[<#92bed8>▶<#8c8c8c>] <#92bed8>ЛКМ<#e6fff3> — открыть упаковку",
        )) else lines("locked-lore", listOf("<#969696>Сначала получите маунта.")),
    )

    fun confirm(player: Player, mountId: String, back: (() -> Unit)? = null) {
        if (!access(player)) return
        val mount = catalog[mountId] ?: return
        val holder = MountPackingMenu(mountId, back)
        val inventory = Bukkit.createInventory(holder, 27, TextUtils.mm(text("confirm-title", "<#20252b>Упаковка маунта")))
        holder.contents = inventory
        val background = backgroundStyle()
        val filler = item(background?.material ?: Material.GRAY_STAINED_GLASS_PANE, " ", emptyList())
        filler.editMeta { meta -> background?.customModelData?.let { meta.setCustomModelData(it) } }
        for (slot in 0 until inventory.size) inventory.setItem(slot, filler)
        val profileSnapshot = profile?.invoke(player.uniqueId, mount)
        val levelLine = if (profileSnapshot != null) {
            text("confirm-level", "<#e6fff3>Текущий уровень: <level>")
                .replace("<level>", profileSnapshot.level.toString())
        } else {
            text("confirm-level", "<#e6fff3>Текущий уровень и улучшения сверяются перед списанием прав.")
        }
        val details = listOf(
            text("confirm-mount", "<#e6fff3>Маунт: <mount> <rarity>")
                .replace("<mount>", mount.displayName)
                .replace("<rarity>", "${mount.rarity.color}${mount.rarity.displayName}"),
            levelLine,
        ) + mount.abilities.upgrades.map { upgrade ->
            val owned = profileSnapshot?.ownsAbility(upgrade.id)
            text("confirm-ability", "<#e6fff3><state> <ability>")
                .replace("<state>", if (owned == true) "<green>✔" else "<gray>○")
                .replace("<ability>", upgrade.displayName)
        }
        inventory.setItem(13, item(Material.NAME_TAG, mount.displayName, details + lines("confirm-lore", listOf(
            "<#e6fff3>В свидетельство попадут только подтверждённые права.", "",
            "<#ff9f0f>Маунт исчезнет из вашей коллекции.",
            "<#e6fff3>После упаковки вы получите именно этот маунт",
            "<#e6fff3>с его текущим уровнем и улучшениями.",
            "<#969696>Активация доступна на спавне.",
        ))))
        inventory.setItem(11, item(Material.RED_CONCRETE, text("cancel-name", "<#c42323>Назад"),
            listOf(text("cancel-action", "<#92bed8>ЛКМ — вернуться к маунту"))))
        inventory.setItem(15, item(Material.ORANGE_CONCRETE, text("accept-name", "<#ff9f0f>Упаковать маунта"),
            listOf(text("accept-action", "<#92bed8>ЛКМ — получить свидетельство"))))
        player.openInventory(inventory)
    }

    @EventHandler fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MountPackingMenu ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.click != ClickType.LEFT || holder.accepted) return
        if (event.rawSlot == 11) {
            holder.back?.invoke() ?: openDetail(player, holder.mountId)
            return
        }
        if (event.rawSlot != 15 || !access(player)) return
        if (player.inventory.firstEmpty() < 0) { send(player, "full", "<#ff9f0f>Освободите один слот для свидетельства."); return }
        val mount = catalog[holder.mountId] ?: return
        holder.accepted = true
        player.closeInventory()
        sessions.remove(player.uniqueId, MountRemovalReason.RELOAD)
        flow.pack(player.uniqueId, mount) { handle(it, player.uniqueId) }
    }

    @EventHandler fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is MountPackingMenu) event.isCancelled = true
    }

    @EventHandler fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? MountPackingMenu ?: return
        if (event.reason != InventoryCloseEvent.Reason.PLAYER || holder.accepted || holder.back == null) return
        val player = event.player as? Player ?: return
        scope.runLater(1) {
            if (!player.isOnline) return@runLater
            val openType = player.openInventory.topInventory?.type
            if (openType == null || openType == org.bukkit.event.inventory.InventoryType.CRAFTING) holder.back.invoke()
        }
    }

    @EventHandler fun onUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) return
        val id = certificateId(event.item) ?: return
        event.isCancelled = true
        val player = event.player
        if (!access(player)) return
        val record = store.get(id)
        if (record?.stage == MountTransferStage.CONSUMED) {
            removeCertificate(player, id)
            send(player, "used", "<#c42323>Это свидетельство уже использовано."); return
        }
        val mount = record?.let { catalog[it.mountId] }
        if (record == null || mount == null) {
            send(player, "invalid", "<#c42323>Свидетельство не найдено в реестре."); return
        }
        flow.redeem(player.uniqueId, id, mount) { handle(it, player.uniqueId) }
    }

    @EventHandler fun onJoin(event: PlayerJoinEvent) {
        scope.runLater(60) { if (event.player.isOnline) recoverDelivery(event.player) }
    }

    fun recoverDelivery(player: Player) {
        store.records().filter {
            it.issuer == player.uniqueId && it.stage == MountTransferStage.PACKING ||
                it.recipient == player.uniqueId && it.stage in setOf(MountTransferStage.CLAIMING, MountTransferStage.APPLIED)
        }.forEach { record -> catalog[record.mountId]?.let { mount ->
            flow.recover(record, mount) { handle(it, player.uniqueId) }
        } }
        store.records().filter { it.issuer == player.uniqueId && it.stage in setOf(MountTransferStage.PACKED, MountTransferStage.DELIVERING) }
            .forEach { deliver(player, it) }
        store.records().filter { it.recipient == player.uniqueId && it.stage == MountTransferStage.CONSUMED }
            .forEach { removeCertificate(player, it.id) }
    }

    private fun handle(outcome: Result<MountTransferRecord>, playerId: UUID) {
        val player = plugin.server.getPlayer(playerId)
        outcome.fold(onSuccess = { record ->
            if (player?.isOnline != true) return@fold
            if (record.stage == MountTransferStage.PACKED) {
                sessions.remove(player.uniqueId, MountRemovalReason.RELOAD)
                deliver(player, record)
            }
            if (record.stage == MountTransferStage.CONSUMED) {
                removeCertificate(player, record.id)
                send(player, "redeemed", "<#2bba43>Маунт и его улучшения добавлены в коллекцию. Откройте /mount.")
            }
        }, onFailure = { failure ->
            warn("Mount transfer pending/rejected for {}: {}", playerId, failure.javaClass.simpleName)
            if (player?.isOnline == true) send(player, "failed", "<#ff9f0f>Передача не завершена. Проверьте, что маунт не получен от группы и ещё отсутствует у получателя. Незавершённая операция сохранена.")
        })
    }

    private fun deliver(player: Player, record: MountTransferRecord) {
        if (player.inventory.contents.any { certificateId(it) == record.id }) {
            store.save(record.copy(stage = MountTransferStage.AVAILABLE)); return
        }
        if (record.stage == MountTransferStage.DELIVERING) {
            send(player, "delivery-review", "<#ff9f0f>Выдача свидетельства требует сверки после перезапуска. Маунт сохранён в реестре; сообщите администрации.")
            return
        }
        val slot = player.inventory.firstEmpty()
        if (slot < 0) { send(player, "full", "<#ff9f0f>Освободите один слот для свидетельства."); return }
        val mount = catalog[record.mountId] ?: return
        val level = record.permissions.mapNotNull { it.removePrefix("arc.mounts.${mount.id}.").toIntOrNull() }.max()
        val extras = buildList {
            add(text("certificate-rarity", "<#e6fff3>Редкость: <rarity>").replace("<rarity>", "${mount.rarity.color}${mount.rarity.displayName}"))
            if (mount.glowPermission in record.permissions) add(text("certificate-glow", "<#e6fff3>Свечение: открыто"))
            mount.abilities.upgrades.filter { mount.abilityPermission(it.id) in record.permissions }.forEach {
                add(text("certificate-ability", "<#e6fff3>Способность: <name>").replace("<name>", it.displayName))
            }
            mount.skins.filter { mount.skinPermission(it.id) in record.permissions }.forEach {
                add(text("certificate-skin", "<#e6fff3>Облик: <name>").replace("<name>", it.displayName))
            }
        }
        val lore = lines("certificate-lore", listOf(
            "<#e6fff3>Маунт: <mount>", "<#e6fff3>Уровень: <level>", "<upgrades>",
            "<#969696>Все упакованные улучшения сохранены.", "",
            "<#e6fff3>Можно передать или продать через /ah.",
            "<#969696>Получатель не должен владеть этим маунтом.", "",
            "<#8c8c8c>[<#92bed8>▶<#8c8c8c>] <#92bed8>ПКМ<#e6fff3> — получить на спавне",
        )).flatMap { if (it == "<upgrades>") extras else listOf(it) }
            .map { it.replace("<mount>", mount.displayName).replace("<level>", level.toString()) }
        val certificate = item(Material.PRISMARINE_SHARD, text("certificate-name", "<#ffacd5>Свидетельство маунта"), lore)
        certificate.editMeta { it.persistentDataContainer.set(key, PersistentDataType.STRING, record.id.toString()) }
        store.save(record.copy(stage = MountTransferStage.DELIVERING))
        player.inventory.setItem(slot, certificate)
        player.saveData()
        store.save(record.copy(stage = MountTransferStage.AVAILABLE))
        player.sendMessage(
            TextUtils.mm(
                text(
                    "packed",
                    "<#2bba43>Упакован маунт <white><mount><green> (<rarity>), уровень <white><level><green>. Свидетельство содержит текущие улучшения.",
                ).replace("<mount>", mount.displayName)
                    .replace("<rarity>", "${mount.rarity.color}${mount.rarity.displayName}")
                    .replace("<level>", level.toString()),
            ),
        )
    }

    private fun removeCertificate(player: Player, id: UUID) {
        player.inventory.contents.forEachIndexed { index, stack ->
            if (certificateId(stack) == id) player.inventory.setItem(index, null)
        }
        player.saveData()
    }

    private fun certificateId(stack: ItemStack?): UUID? = stack?.itemMeta?.persistentDataContainer
        ?.get(key, PersistentDataType.STRING)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun access(player: Player): Boolean {
        if (!player.hasPermission("arc.mounts.use")) return false
        if (!config.enabled) { send(player, "spawn-only", "<#ff9f0f>Упаковка и активация свидетельств доступны на спавне."); return false }
        return true
    }

    private fun text(key: String, fallback: String) = config.text(key, fallback)
    private fun lines(key: String, fallback: List<String>) = config.lines(key, fallback)
    private fun send(player: Player, key: String, fallback: String) = player.sendMessage(TextUtils.mm(text(key, fallback)))
    private fun item(material: Material, name: String, lore: List<String>) = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(TextUtils.mm("<italic:false>$name"))
            meta.lore(lore.map { TextUtils.mm("<italic:false>$it") })
        }
    }

    override fun close() {
        scope.close()
        plugin.server.onlinePlayers.filter { it.openInventory.topInventory.holder is MountPackingMenu }.forEach(Player::closeInventory)
        HandlerList.unregisterAll(this)
        ledger.close()
    }
}
