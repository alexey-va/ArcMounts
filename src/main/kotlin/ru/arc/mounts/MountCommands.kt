package ru.arc.mounts

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import ru.arc.core.TaskScheduler
import ru.arc.util.TextUtils
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MountCommand(
    private val config: () -> MountModuleConfig,
    private val catalog: () -> MountCatalog,
    private val ownership: MountOwnership,
    private val sessions: MountSessionController,
    private val scheduler: TaskScheduler,
    private val openMenu: (Player) -> Unit,
    private val openOwned: (Player) -> Unit = openMenu,
    private val openShop: (Player) -> Unit = openMenu,
    private val openUpgrades: (Player) -> Unit = openOwned,
    private val openTrade: (Player) -> Unit = openOwned,
    private val openDetail: (Player, String) -> Unit = { player, _ -> openMenu(player) },
    private val packMount: (Player, String) -> Unit = { player, _ -> openMenu(player) },
) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender is Player && !sender.hasPermission("arc.mounts.use")) {
            sender.sendMessage(MountText.noPermissions()); return true
        }
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            null, "menu" -> openPlayerMenu(sender)
            "shop" -> if (sender is Player) openShop(sender) else openPlayerMenu(sender)
            "owned" -> if (sender is Player) openOwned(sender) else openPlayerMenu(sender)
            "upgrades" -> if (sender is Player) openUpgrades(sender) else openPlayerMenu(sender)
            "trade" -> if (sender is Player) openTrade(sender) else openPlayerMenu(sender)
            "view", "pack" -> {
                if (sender !is Player) openPlayerMenu(sender)
                else if (args.size == 2 && catalog()[args[1]] != null) {
                    if (args[0].equals("pack", true)) packMount(sender, args[1]) else openDetail(sender, args[1])
                } else openOwned(sender)
            }
            "help" -> sendHelp(sender, label)
            "admin" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    sender.sendMessage(MountText.noPermissions())
                    return true
                }
                handleAdmin(sender, label, args.drop(1))
            }
            else -> sendHelp(sender, label)
        }
        return true
    }

    private fun openPlayerMenu(sender: CommandSender) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(MountText.playerOnly())
            return
        }
        openMenu(player)
    }

    private fun handleAdmin(sender: CommandSender, label: String, args: List<String>) {
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "summon" -> summon(sender, label, args.drop(1))
            "grant-all" -> grantAll(sender, label, args.drop(1))
            "revoke-all" -> revokeAll(sender, label, args.drop(1))
            "grant" -> mutateOwnership(sender, label, args.drop(1), granting = true)
            "revoke" -> mutateOwnership(sender, label, args.drop(1), granting = false)
            else -> sendAdminHelp(sender, label)
        }
    }

    private fun grantAll(sender: CommandSender, label: String, args: List<String>) {
        val playerName = args.singleOrNull()
        if (playerName == null || !PLAYER_NAME.matches(playerName)) {
            sender.sendMessage(TextUtils.mm("<red>Использование: /$label admin grant-all <игрок>", true))
            return
        }
        val mounts = catalog().all
        resolvePlayer(sender, playerName).thenCompose { playerId ->
            if (playerId == null) {
                failedFuture(PlayerNotFoundException(playerName))
            } else {
                ownership.grantAll(playerId, mounts)
            }
        }.whenComplete { _, failure ->
            scheduler.runSync(
                Runnable {
                    val text =
                        when {
                            failure == null ->
                                config().message(
                                    "admin-grant-all-success",
                                    "<green>Все маунты и улучшения выданы игроку <white><player><green>. Маунтов: <white><count><green>.",
                                ).replace("<player>", playerName).replace("<count>", mounts.size.toString())
                            unwrap(failure) is PlayerNotFoundException -> "<red>Игрок <white>$playerName <red>не найден."
                            else -> "<red>Не удалось выдать всех маунтов и улучшения. Часть изменений могла сохраниться."
                        }
                    sender.sendMessage(TextUtils.mm(text, true))
                },
            )
        }
    }

    private fun revokeAll(sender: CommandSender, label: String, args: List<String>) {
        val playerName = when (args.size) {
            0 -> (sender as? Player)?.name
            1 -> args[0]
            else -> null
        }
        if (playerName == null || !PLAYER_NAME.matches(playerName)) {
            sender.sendMessage(TextUtils.mm("<red>Использование: /$label admin revoke-all [игрок]", true))
            return
        }
        resolvePlayer(sender, playerName).thenCompose { playerId ->
            if (playerId == null) failedFuture(PlayerNotFoundException(playerName))
            else ownership.revokeAll(playerId, catalog().all).thenApply { playerId to it }
        }.whenComplete { removed, failure ->
            scheduler.runSync(Runnable {
                when {
                    failure == null -> {
                        sessions.remove(removed.first, MountRemovalReason.INVALID)
                        sender.sendMessage(
                        TextUtils.mm(
                            "<green>Маунты и улучшения отозваны у игрока <white>$playerName<green>. Удалено прав: <white>${removed.second}<green>. <gray>Права из групп сохраняются.",
                            true,
                        ),
                        )
                    }
                    unwrap(failure) is PlayerNotFoundException -> sender.sendMessage(
                        TextUtils.mm("<red>Игрок <white>$playerName <red>не найден.", true),
                    )
                    else -> sender.sendMessage(TextUtils.mm("<red>Не удалось отозвать права маунтов.", true))
                }
            })
        }
    }

    private fun summon(sender: CommandSender, label: String, args: List<String>) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(MountText.playerOnly())
            return
        }
        val mountId = args.getOrNull(0)?.lowercase(Locale.ROOT)
        if (mountId == null || args.size > 3) {
            sender.sendMessage(TextUtils.mm("<red>Использование: /$label admin summon <маунт> [уровень] [облик]", true))
            return
        }
        val mount = catalog()[mountId]
        if (mount == null) {
            sender.sendMessage(TextUtils.mm("<red>Неизвестный маунт: <white>$mountId", true))
            return
        }
        val level = args.getOrNull(1)?.toIntOrNull() ?: mount.maxLevel
        if (level !in 1..mount.maxLevel) {
            sender.sendMessage(TextUtils.mm("<red>Уровень должен быть от 1 до ${mount.maxLevel}.", true))
            return
        }
        val skinId = args.getOrNull(2)?.lowercase(Locale.ROOT) ?: MountDefinition.DEFAULT_SKIN_ID
        val skin = skinId.takeUnless { it == MountDefinition.DEFAULT_SKIN_ID }?.let(mount::skin)
        if (skinId != MountDefinition.DEFAULT_SKIN_ID && skin == null) {
            sender.sendMessage(TextUtils.mm("<red>Неизвестный облик: <white>$skinId", true))
            return
        }
        val configuredLevel = mount.level(level)
        val result =
            sessions.spawn(
                player = player,
                definition = mount,
                settings =
                    MountRuntimeSettings(
                        speed = configuredLevel.speed,
                        walkingStepHeight = config().tuning.stepHeight(level, null),
                        handlingMultiplier = configuredLevel.handlingMultiplier,
                        sprintMultiplier = configuredLevel.sprintMultiplier,
                        scaleMultiplier = configuredLevel.scaleMultiplier,
                        skin = skin,
                        glow = false,
                        abilityUpgrades = mount.abilities.upgrades,
                    ),
                durationMillis = config().adminSessionDuration.toMillis(),
            )
        if (result != MountSpawnResult.SUCCESS) {
            player.sendMessage(TextUtils.mm("<red>Не удалось призвать маунта: <white>${result.name.lowercase()}<red>.", true))
        }
    }

    private fun mutateOwnership(sender: CommandSender, label: String, args: List<String>, granting: Boolean) {
        val action = if (granting) "grant" else "revoke"
        val kind = args.getOrNull(0)?.lowercase(Locale.ROOT)
        val playerName = args.getOrNull(1)
        val mountId = args.getOrNull(2)?.lowercase(Locale.ROOT)
        val expectedArguments = if (kind == "glow") 3 else 4
        if (
            kind !in ADMIN_KINDS ||
            args.size != expectedArguments ||
            playerName == null ||
            mountId == null ||
            !PLAYER_NAME.matches(playerName)
        ) {
            sendMutationHelp(sender, label, action)
            return
        }
        val mount = catalog()[mountId]
        if (mount == null) {
            sender.sendMessage(TextUtils.mm("<red>Неизвестный маунт: <white>$mountId", true))
            return
        }
        val target = mutationTarget(sender, label, action, checkNotNull(kind), mount, args.getOrNull(3)) ?: return
        resolvePlayer(sender, playerName).thenCompose { playerId ->
            if (playerId == null) {
                failedFuture(PlayerNotFoundException(playerName))
            } else {
                target.apply(playerId, mount)
            }
        }.whenComplete { _, failure ->
            scheduler.runSync(Runnable {
                when {
                    failure == null -> sender.sendMessage(
                        TextUtils.mm(
                            "<green>${target.successLabel}: <white>${mount.displayName}<green>, игрок: <white>$playerName<green>.",
                            true,
                        ),
                    )
                    unwrap(failure) is PlayerNotFoundException -> sender.sendMessage(
                        TextUtils.mm("<red>Игрок <white>$playerName <red>не найден.", true),
                    )
                    else -> sender.sendMessage(TextUtils.mm("<red>Не удалось изменить улучшение маунта.", true))
                }
            })
        }
    }

    private fun mutationTarget(
        sender: CommandSender,
        label: String,
        action: String,
        kind: String,
        mount: MountDefinition,
        rawTarget: String?,
    ): MountAdminMutation? =
        when (kind) {
            "level" -> {
                val level = rawTarget?.toIntOrNull()
                if (level == null || level !in 1..mount.maxLevel) {
                    sender.sendMessage(TextUtils.mm("<red>Уровень должен быть от 1 до ${mount.maxLevel}.", true))
                    null
                } else {
                    MountAdminMutation(
                        successLabel = if (action == "grant") "Уровень $level выдан" else "Уровень $level отозван",
                        apply = { playerId, definition ->
                            if (action == "grant") ownership.grantLevel(playerId, definition, level)
                            else ownership.revokeLevel(playerId, definition, level)
                        },
                    )
                }
            }
            "skin" -> {
                val skinId = rawTarget?.lowercase(Locale.ROOT)
                val skin = skinId?.let(mount::skin)
                if (skin == null) {
                    sender.sendMessage(TextUtils.mm("<red>Укажите существующий платный облик.", true))
                    null
                } else {
                    MountAdminMutation(
                        successLabel = if (action == "grant") "Облик ${skin.displayName} выдан" else "Облик ${skin.displayName} отозван",
                        apply = { playerId, definition ->
                            if (action == "grant") ownership.grantSkin(playerId, definition, skin)
                            else ownership.revokeSkin(playerId, definition, skin)
                        },
                    )
                }
            }
            "ability" -> {
                val abilityId = rawTarget?.lowercase(Locale.ROOT)
                val ability = abilityId?.let(mount::ability)
                if (ability == null) {
                    sender.sendMessage(TextUtils.mm("<red>Укажите способность, доступную этому маунту.", true))
                    null
                } else {
                    MountAdminMutation(
                        successLabel =
                            if (action == "grant") "Способность ${ability.displayName} выдана"
                            else "Способность ${ability.displayName} отозвана",
                        apply = { playerId, definition ->
                            if (action == "grant") ownership.grantAbility(playerId, definition, ability)
                            else ownership.revokeAbility(playerId, definition, ability)
                        },
                    )
                }
            }
            "size" -> {
                val sizeId = rawTarget?.lowercase(Locale.ROOT)
                val size = mount.sizeOptions.firstOrNull { it.id == sizeId && it.grantOnly }
                if (size == null) {
                    sender.sendMessage(TextUtils.mm("<red>Укажите особый размер этого маунта.", true))
                    null
                } else {
                    MountAdminMutation(
                        successLabel =
                            if (action == "grant") "Особый размер ${size.displayName} выдан"
                            else "Особый размер ${size.displayName} отозван",
                        apply = { playerId, definition ->
                            if (action == "grant") ownership.grantSize(playerId, definition, size)
                            else ownership.revokeSize(playerId, definition, size)
                        },
                    )
                }
            }
            "glow" -> {
                if (rawTarget != null) {
                    sendMutationHelp(sender, label, action)
                    null
                } else {
                    MountAdminMutation(
                        successLabel = if (action == "grant") "Свечение выдано" else "Свечение отозвано",
                        apply = { playerId, definition ->
                            if (action == "grant") ownership.grantGlow(playerId, definition)
                            else ownership.revokeGlow(playerId, definition)
                        },
                    )
                }
            }
            else -> null
        }

    private fun resolvePlayer(sender: CommandSender, playerName: String): CompletableFuture<UUID?> {
        val onlineId = sender.server.getPlayerExact(playerName)?.uniqueId
        return onlineId?.let { CompletableFuture.completedFuture(it) } ?: ownership.resolveUniqueId(playerName)
    }

    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage(TextUtils.mm("<gold>/$label <gray>— открыть коллекцию маунтов", true))
        sender.sendMessage(TextUtils.mm("<gold>/$label menu <gray>— открыть коллекцию", true))
        sender.sendMessage(TextUtils.mm(config().message("player-command-help", "<#92bed8>/mount shop <#e6fff3>— покупки у торговца на спавне; <#92bed8>/mount upgrades <#e6fff3>— развитие; <#92bed8>/mount trade <#e6fff3>— передача и перепродажа.")))
        if (sender.hasPermission(ADMIN_PERMISSION)) sendAdminHelp(sender, label)
    }

    private fun sendAdminHelp(sender: CommandSender, label: String) {
        sender.sendMessage(TextUtils.mm("<yellow>/$label admin summon <маунт> [уровень] [облик]", true))
        sender.sendMessage(TextUtils.mm("<yellow>/$label admin grant-all <игрок> <gray>— выдать всех маунтов и все улучшения", true))
        sender.sendMessage(TextUtils.mm("<yellow>/$label admin revoke-all [игрок] <gray>— отозвать прямые права маунтов", true))
        sender.sendMessage(TextUtils.mm("<yellow>/$label admin grant <level|skin|glow|ability|size> <игрок> <маунт> [значение]", true))
        sender.sendMessage(TextUtils.mm("<yellow>/$label admin revoke <level|skin|glow|ability|size> <игрок> <маунт> [значение]", true))
    }

    private fun sendMutationHelp(sender: CommandSender, label: String, action: String) {
        sender.sendMessage(TextUtils.mm("<red>Использование: /$label admin $action <level|skin|glow|ability|size> <игрок> <маунт> [значение]", true))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.isEmpty()) return emptyList()
        if (args.size == 1) {
            return buildList {
                add("help")
                add("menu")
                addAll(listOf("shop", "owned", "upgrades", "trade", "view", "pack"))
                if (sender.hasPermission(ADMIN_PERMISSION)) add("admin")
            }.matching(args[0])
        }
        if (args.size == 2 && args[0].lowercase(Locale.ROOT) in listOf("view", "pack")) return catalog().all.map { it.id }.matching(args[1])
        if (!args[0].equals("admin", ignoreCase = true) || !sender.hasPermission(ADMIN_PERMISSION)) return emptyList()
        if (args.size == 2) return listOf("summon", "grant-all", "revoke-all", "grant", "revoke").matching(args[1])
        return when (args[1].lowercase(Locale.ROOT)) {
            "summon" -> completeSummon(args)
            "grant-all" -> if (args.size == 3) sender.server.onlinePlayers.map(Player::getName).matching(args[2]) else emptyList()
            "revoke-all" -> if (args.size == 3) sender.server.onlinePlayers.map(Player::getName).matching(args[2]) else emptyList()
            "grant", "revoke" -> completeMutation(sender, args)
            else -> emptyList()
        }
    }

    private fun completeSummon(args: Array<out String>): List<String> =
        when (args.size) {
            3 -> catalog().all.map(MountDefinition::id).matching(args[2])
            4 -> {
                val mount = catalog()[args[2].lowercase(Locale.ROOT)]
                (1..(mount?.maxLevel ?: 3)).map(Int::toString).matching(args[3])
            }
            5 -> {
                val mount = catalog()[args[2].lowercase(Locale.ROOT)]
                (listOf(MountDefinition.DEFAULT_SKIN_ID) + mount?.skins.orEmpty().map(MountSkinDefinition::id)).matching(args[4])
            }
            else -> emptyList()
        }

    private fun completeMutation(sender: CommandSender, args: Array<out String>): List<String> =
        when (args.size) {
            3 -> ADMIN_KINDS.matching(args[2])
            4 -> sender.server.onlinePlayers.map(Player::getName).matching(args[3])
            5 -> catalog().all.map(MountDefinition::id).matching(args[4])
            6 -> {
                val mount = catalog()[args[4].lowercase(Locale.ROOT)]
                when (args[2].lowercase(Locale.ROOT)) {
                    "level" -> (1..(mount?.maxLevel ?: 3)).map(Int::toString).matching(args[5])
                    "skin" -> mount?.skins.orEmpty().map(MountSkinDefinition::id).matching(args[5])
                    "ability" -> mount?.abilities?.upgrades.orEmpty().map(MountAbilityUpgradeDefinition::id).matching(args[5])
                    "size" ->
                        mount?.sizeOptions.orEmpty()
                            .filter(MountSizeOptionDefinition::grantOnly)
                            .map(MountSizeOptionDefinition::id)
                            .matching(args[5])
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }

    private data class MountAdminMutation(
        val successLabel: String,
        val apply: (UUID, MountDefinition) -> CompletableFuture<Void>,
    )

    private class PlayerNotFoundException(playerName: String) : RuntimeException(playerName)

    companion object {
        private const val ADMIN_PERMISSION = "arc.mounts.admin"
        private val ADMIN_KINDS = listOf("ability", "glow", "level", "size", "skin")
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{3,16}")
    }
}

private fun unwrap(failure: Throwable): Throwable {
    var current = failure
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return current
}

private fun <T> failedFuture(failure: Throwable): CompletableFuture<T> = CompletableFuture<T>().also { it.completeExceptionally(failure) }

private fun Collection<String>.matching(prefix: String): List<String> =
    filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
