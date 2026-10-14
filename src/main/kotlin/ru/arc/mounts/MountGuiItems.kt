package ru.arc.mounts

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.arc.util.TextUtils
import java.time.Duration
import kotlin.math.roundToInt

internal class MountGuiItems(
    private val configProvider: () -> MountModuleConfig,
    private val quickSummons: MountQuickSummonController,
) {
    fun mountIcon(
        mount: MountDefinition,
        profile: MountProfile,
        detailed: Boolean = false,
        favorite: Boolean = false,
        purpose: MountListPurpose = MountListPurpose.COLLECTION,
    ): ItemStack {
        val rarity = rarity(mount.rarity)
        val typeValue = "${movementColor(mount.movement)}${movementName(mount.movement)}"
        val skin = escape(skinName(mount, profile.activeSkinId))
        val commonValues =
            arrayOf(
                "mount" to escape(mount.displayName),
                "rarity" to rarity,
                "type-value" to typeValue,
                "level" to profile.level.toString(),
                "max-level" to mount.maxLevel.toString(),
                "skin" to skin,
                "acquisition" to escape(mount.acquisition),
            )
        val lore =
            if (!detailed) {
                buildList {
                    if (favorite) {
                        add(
                            copyLines(
                                "detail.mount-state-lore",
                                listOf("<#ffacd5>★ Любимый маунт"),
                                *commonValues,
                            ).firstOrNull() ?: "<#ffacd5>★ Любимый маунт",
                        )
                    }
                    val path = if (profile.unlocked) "list.mount-owned-core" else "list.mount-locked-core"
                    val fallback =
                        when {
                            profile.unlocked ->
                                listOf(
                                    "<#2bba43>✔ Получен",
                                    "<rarity>",
                                    "",
                                    "<#8c8c8c>Тип: <type-value>",
                                    "<#8c8c8c>Уровень: <#e6fff3><level>/<max-level>",
                                    "<#8c8c8c>Облик: <#e6fff3><skin>",
                                )
                            else ->
                                listOf(
                                    "<#c42323>✘ Не получен",
                                    "<rarity>",
                                    "",
                                    "<#8c8c8c>Получение: <#e6fff3><acquisition>",
                                )
                        }
                    addAll(copyLines(path, fallback, *commonValues))
                    if (!profile.unlocked) {
                        mount.price(1)?.let { basePrice ->
                            add(priceLine("Цена первого уровня", basePrice.toExactMinor(), mount.currency))
                        }
                    }
                    addAll(mountFeatureLore(mount))
                    when {
                        profile.unlocked -> {
                            add("")
                            when (purpose) {
                                MountListPurpose.SHOP -> add("<#92bed8>ЛКМ<#e6fff3> — карточка полученного маунта")
                                MountListPurpose.UPGRADES -> {
                                    add(copy("list.mount-upgrades-level-footer", "<#92bed8>ЛКМ<#e6fff3> — повышение уровня"))
                                    add(copy("list.mount-upgrades-ability-footer", "<#ff9f0f>ПКМ<#e6fff3> — покупка улучшений"))
                                }
                                MountListPurpose.TRADE -> add("<#8c8c8c>[<#ff9f0f>▶<#8c8c8c>] <#ff9f0f>ЛКМ<#e6fff3> — передать <#8c8c8c>· <#92bed8>ПКМ<#e6fff3> — открыть")
                                else -> add(copy("list.mount-owned-footer", "<#8c8c8c>[<#92bed8>▶<#8c8c8c>] <#92bed8>ЛКМ<#e6fff3> — призвать <#8c8c8c>· <#92bed8>ПКМ<#e6fff3> — открыть"))
                            }
                        }
                        else -> Unit
                    }
                }
            } else {
                buildList {
                    val state =
                        copyLines(
                            "detail.mount-state-lore",
                            listOf("<#ffacd5>★ Любимый маунт", "<#2bba43>✔ Получен", "<rarity>", ""),
                            *commonValues,
                        )
                    if (favorite) add(state.getOrElse(0) { "<#ffacd5>★ Любимый маунт" })
                    add(
                        if (profile.unlocked) {
                            state.getOrElse(1) { "<#2bba43>✔ Получен" }
                        } else {
                            copy("detail.mount-locked-state", "<#c42323>✘ Не получен")
                        },
                    )
                    add(rarity)
                    if (mount.description.isNotEmpty()) {
                        add("")
                        mount.description.forEach {
                            add(
                                copy(
                                    "detail.description-line",
                                    "<#e6fff3><mount-description>",
                                    "mount-description" to escape(it),
                                ),
                            )
                        }
                    }
                    if (profile.unlocked) {
                        val tuning = configProvider().tuning
                        val stats =
                            copyLines(
                                "detail.mount-stats-core-lore",
                                listOf(
                                    "",
                                    "<#92bed8>Характеристики",
                                    "<#8c8c8c>Тип: <type-value>",
                                    "<#8c8c8c>Уровень: <#e6fff3><level>/<max-level>",
                                    "<#8c8c8c>Скорость: <#e6fff3><speed>",
                                    "<#8c8c8c>Подъём: <#e6fff3><step> блока",
                                    "<#8c8c8c>Облик: <#e6fff3><skin>",
                                ),
                                *commonValues,
                                "speed" to formatSpeed(tuning.speed(mount.speed(profile.level), profile.selectedSpeedPercentage)),
                                "step" to formatHeight(tuning.stepHeight(profile.level, profile.selectedStepHeightHundredths)),
                            )
                        addAll(stats.take(5))
                        if (mount.movement == MountMovement.WALKING) add(stats.getOrElse(5) { "" })
                        add(stats.getOrElse(6) { "<#8c8c8c>Облик: <#e6fff3>$skin" })
                        mount.abilities.highJump?.let { ability ->
                            add(
                                copy(
                                    "detail.innate-ability-line",
                                    "<#8c8c8c>Особенность: <#ffacd5><ability>",
                                    "ability" to escape(ability.displayName),
                                ),
                            )
                            highJumpDescription(ability).forEach { description ->
                                add(
                                    copy(
                                        "detail.innate-description",
                                        "<#8c8c8c><ability-description>",
                                        "ability-description" to escape(description),
                                    ),
                                )
                            }
                        }
                        mount.abilities.passives.forEach { ability ->
                            add(
                                copy(
                                    "detail.innate-ability-line",
                                    "<#8c8c8c>Особенность: <#ffacd5><ability>",
                                    "ability" to escape(ability.displayName),
                                ),
                            )
                            passiveDescription(ability).forEach { description ->
                                add(
                                    copy(
                                        "detail.innate-description",
                                        "<#8c8c8c><ability-description>",
                                        "ability-description" to escape(description),
                                    ),
                                )
                            }
                        }
                        mount.abilities.upgrades.forEach { ability ->
                            add(
                                copy(
                                    if (profile.ownsAbility(ability.id)) "detail.effect-owned-line" else "detail.upgrade-available-line",
                                    if (profile.ownsAbility(ability.id)) {
                                        "<#8c8c8c>Эффект: <#ffacd5><ability>"
                                    } else {
                                        "<#8c8c8c>Улучшение: <#92bed8><ability> <#969696>(не куплено)"
                                    },
                                    "ability" to escape(ability.displayName),
                                ),
                            )
                        }
                        mount.behaviors.forEach { behavior ->
                            add(
                                copy(
                                    "detail.behavior-line",
                                    "<#8c8c8c>Особенность: <#ffacd5><feature>",
                                    "feature" to escape(behavior.displayName),
                                ),
                            )
                            behavior.description.forEach { description ->
                                add(
                                    copy(
                                        "detail.behavior-description",
                                        "<#8c8c8c><behavior-description>",
                                        "behavior-description" to escape(description),
                                    ),
                                )
                            }
                        }
                        if (mount.passengerSeats > 0) {
                            add(
                                copy(
                                    "detail.passenger-feature-line",
                                    "<#8c8c8c>Особенность: <#ffacd5>Пассажирские места: <passengers>",
                                    "passengers" to mount.passengerSeats.toString(),
                                ),
                            )
                            add(
                                copy(
                                    "detail.passenger-feature-action",
                                    "<#8c8c8c><#92bed8>ПКМ по маунту<#e6fff3> — сесть пассажиром",
                                ),
                            )
                        }
                    } else {
                        add("")
                        add(copy("detail.locked-acquisition", "<#8c8c8c>Получение: <#e6fff3><acquisition>", *commonValues))
                        mount.price(1)?.let { basePrice ->
                            add(priceLine("Цена первого уровня", basePrice.toExactMinor(), mount.currency))
                        }
                    }
                }
            }
        return item(
            if (profile.unlocked) Material.matchMaterial(mount.iconMaterial) ?: Material.PAPER else Material.RED_DYE,
            copy(
                if (profile.unlocked) "list.mount-owned-name" else "list.mount-locked-name",
                if (profile.unlocked) "<#ffacd5><mount>" else "<#c42323><mount>",
                "mount" to escape(mount.displayName),
            ),
            lore,
            glint = profile.unlocked,
        )
    }

    fun favoriteItem(profile: MountProfile, selected: Boolean): ItemStack =
        when {
            !profile.unlocked ->
                styledItem(
                    MountGuiItemRole.FAVORITE,
                    Material.GRAY_DYE,
                    copy("detail.favorite-locked-name", "<#969696>Любимый маунт недоступен"),
                    copyLines("detail.favorite-locked-lore", listOf("<#8c8c8c>Сначала получите этого маунта.")),
                )
            selected ->
                styledItem(
                    MountGuiItemRole.FAVORITE,
                    Material.NETHER_STAR,
                    copy("detail.favorite-selected-name", "<#ffacd5>Любимый маунт"),
                    copyLines(
                        "detail.favorite-selected-lore",
                        listOf(
                            "<#2bba43>Выбран для быстрого призыва.",
                            "",
                            "<#8c8c8c>Shift + F или свисток — призвать.",
                        ),
                    ),
                    glint = true,
                )
            else ->
                styledItem(
                    MountGuiItemRole.FAVORITE,
                    Material.NETHER_STAR,
                    copy("detail.favorite-available-name", "<#ffacd5>Выбрать любимым"),
                    actionLore(
                        copyLines(
                            "detail.favorite-available-lore",
                            listOf("<#8c8c8c>Использовать для Shift + F и свистка."),
                        ),
                        "выбрать",
                    ),
                )
        }

    fun whistleMenuItem(player: Player, favoriteMountId: String?): ItemStack {
        val owned = player.inventory.contents.any(quickSummons::isWhistle)
        return when {
            !configProvider().quickSummonWhistle ->
                styledItem(
                    MountGuiItemRole.WHISTLE,
                    Material.GRAY_DYE,
                    copy("detail.whistle-disabled-name", "<#969696>Свисток недоступен"),
                    copyLines("detail.whistle-disabled-lore", listOf("<#8c8c8c>Он отключён на этом сервере.")),
                )
            favoriteMountId == null ->
                styledItem(
                    MountGuiItemRole.WHISTLE,
                    Material.GRAY_DYE,
                    copy("detail.whistle-favorite-required-name", "<#969696>Свисток недоступен"),
                    copyLines(
                        "detail.whistle-favorite-required-lore",
                        listOf("<#8c8c8c>Сначала выберите любимого маунта."),
                    ),
                )
            owned ->
                styledItem(
                    MountGuiItemRole.WHISTLE,
                    Material.GOAT_HORN,
                    copy("detail.whistle-owned-name", "<#2bba43>Свисток уже получен"),
                    copyLines("detail.whistle-owned-lore", listOf("<#8c8c8c>Он лежит у вас в инвентаре.")),
                    glint = true,
                )
            player.inventory.firstEmpty() < 0 ->
                styledItem(
                    MountGuiItemRole.WHISTLE,
                    Material.GRAY_DYE,
                    copy("detail.whistle-full-name", "<#969696>Нет места для свистка"),
                    copyLines("detail.whistle-full-lore", listOf("<#8c8c8c>Освободите слот в инвентаре.")),
                )
            else ->
                styledItem(
                    MountGuiItemRole.WHISTLE,
                    Material.GOAT_HORN,
                    copy("detail.whistle-available-name", "<#ffacd5>Получить свисток"),
                    actionLore(
                        copyLines(
                            "detail.whistle-available-lore",
                            listOf("<#8c8c8c>ПКМ свистком — призвать любимого маунта."),
                        ),
                        "получить",
                    ),
                )
        }
    }

    fun upgradeItem(mount: MountDefinition, profile: MountProfile, merchantAvailable: Boolean = true): ItemStack {
        val values = arrayOf("level" to profile.level.toString(), "max-level" to mount.maxLevel.toString())
        val content =
            if (!profile.unlocked) {
                buildList {
                    addAll(
                        copyLines(
                            "detail.upgrade-locked-lore",
                            listOf("<#8c8c8c>Уровень: <#e6fff3><level>/<max-level>"),
                            *values,
                        ),
                    )
                    mount.price(1)?.let { basePrice ->
                        add(priceLine("Цена первого уровня", basePrice.toExactMinor(), mount.currency))
                    }
                }
            } else {
                copyLines(
                    "settings.levels-lore",
                    listOf(
                        "<#b8b8b8>Уровень: <#e6fff3><level>/<max-level>",
                        "",
                        if (profile.level >= mount.maxLevel) "<#2bba43>Все уровни открыты."
                        else "<#b8b8b8>Новые уровни повышают предел характеристик.",
                    ),
                    *values,
                )
            }
        val purchasable = mount.price(1) != null
        val lore =
            if (profile.unlocked) {
                actionLore(content, "открыть")
            } else if (purchasable && merchantAvailable) {
                actionLore(content, "купить")
            } else {
                content + if (purchasable) listOf(configProvider().guiText("common.purchases-at-spawn", "<#ff9f0f>Покупка: Гектор или Эмма во дворе маунтов на спавне.")) else emptyList()
            }
        return item(
            Material.EXPERIENCE_BOTTLE,
            copy(
                if (profile.unlocked || !purchasable) "settings.levels-name" else if (merchantAvailable) "detail.upgrade-buy-name" else "settings.levels-name",
                if (profile.unlocked || !purchasable) "<#92bed8>Уровни" else if (merchantAvailable) "<#ff9f0f>Купить первый уровень" else "<#92bed8>Уровни",
            ),
            lore,
            glint = profile.level >= mount.maxLevel,
        )
    }

    fun levelUpgradeItem(mount: MountDefinition, profile: MountProfile): ItemStack {
        val next = profile.level + 1
        return when {
            profile.level >= mount.maxLevel ->
                item(
                    Material.NETHER_STAR,
                    copy("progression.max-level-name", "<#2bba43>Максимальный уровень"),
                    copyLines("progression.max-level-lore", listOf("<#8c8c8c>Все пределы характеристик открыты.")),
                    glint = true,
                )
            mount.price(next) == null ->
                item(
                    Material.BARRIER,
                    copy("progression.special-name", "<#969696>Особое развитие"),
                    copyLines(
                        "progression.special-lore",
                        listOf("<#8c8c8c><acquisition>"),
                        "acquisition" to escape(mount.acquisition),
                    ),
                )
            else -> {
                val level = mount.level(next)
                val tuning = configProvider().tuning
                val previousSpeed = if (profile.level > 0) mount.speed(profile.level) else null
                val delta = previousSpeed?.let { (((level.speed / it) - 1.0) * 100.0).roundToInt() }
                val purchasable = configProvider().purchasesEnabled
                item(
                    if (next == mount.maxLevel) Material.NETHER_STAR else Material.EMERALD,
                    copy(
                        if (profile.unlocked) "progression.level-name" else "progression.unlock-name",
                        if (profile.unlocked) "<#92bed8>Уровень <level>" else "<#92bed8>Получить маунта",
                        "level" to next.toString(),
                    ),
                    buildList {
                        if (previousSpeed != null) {
                            add(
                                copy(
                                    "progression.level-speed-upgrade",
                                    "<#8c8c8c>Скорость: <#e6fff3><speed-before> <#8c8c8c>→ <#2bba43><speed-after>",
                                    "speed-before" to formatSpeed(previousSpeed),
                                    "speed-after" to formatSpeed(level.speed),
                                ),
                            )
                        } else {
                            add(
                                copy(
                                    "progression.level-speed-initial",
                                    "<#8c8c8c>Скорость: <#e6fff3><speed>",
                                    "speed" to formatSpeed(level.speed),
                                ),
                            )
                        }
                        if (delta != null) {
                            add(copy("progression.level-increase", "<#8c8c8c>Прирост: <#2bba43>+<increase>%", "increase" to delta.toString()))
                        }
                        add(
                            copy(
                                "progression.level-handling",
                                "<#8c8c8c>Управляемость: <#e6fff3>×<handling>",
                                "handling" to formatMultiplier(level.handlingMultiplier),
                            ),
                        )
                        if (level.sprintMultiplier > 1.0) {
                            add(
                                copy(
                                    "progression.level-sprint",
                                    "<#8c8c8c>Форсаж: <#e6fff3>×<sprint>",
                                    "sprint" to formatMultiplier(level.sprintMultiplier),
                                ),
                            )
                        }
                        if (mount.movement == MountMovement.WALKING) {
                            val previousHeight = if (profile.level > 0) tuning.maximumStepHeightHundredths(profile.level) else null
                            val nextHeight = tuning.maximumStepHeightHundredths(next)
                            if (previousHeight == null) {
                                add(
                                    copy(
                                        "progression.level-step-initial",
                                        "<#8c8c8c>Макс. подъём: <#e6fff3><step> блока",
                                        "step" to formatHeight(nextHeight / 100.0),
                                    ),
                                )
                            } else if (previousHeight != nextHeight) {
                                add(
                                    copy(
                                        "progression.level-step-upgrade",
                                        "<#8c8c8c>Макс. подъём: <#e6fff3><step-before> <#8c8c8c>→ <#2bba43><step-after> блока",
                                        "step-before" to formatHeight(previousHeight / 100.0),
                                        "step-after" to formatHeight(nextHeight / 100.0),
                                    ),
                                )
                            }
                        }
                        add("")
                        add(priceLine("Цена", checkNotNull(level.price).toExactMinor(), mount.currency))
                        if (purchasable) {
                            add("")
                            add(actionFooter("открыть покупку"))
                        } else {
                            add(configProvider().guiText("common.purchases-at-spawn", "<#ff9f0f>Покупка: Гектор или Эмма во дворе маунтов на спавне."))
                        }
                    },
                    glint = next == mount.maxLevel,
                )
            }
        }
    }

    fun levelCardItem(mount: MountDefinition, profile: MountProfile, levelNumber: Int, merchantAvailable: Boolean = true): ItemStack {
        val level = mount.level(levelNumber)
        val owned = profile.level >= levelNumber
        val available = !owned && profile.level + 1 == levelNumber && mount.price(levelNumber) != null
        val statePath = when {
            owned -> "progression.level-card-owned-name"
            available -> "progression.level-card-available-name"
            else -> "progression.level-card-locked-name"
        }
        val stateFallback = when {
            owned -> "<#2bba43>Уровень <level> · открыт"
            available -> "<#92bed8>Уровень <level> · доступен"
            else -> "<#969696>Уровень <level> · закрыт"
        }
        val lore = buildList {
            add(copy("progression.level-speed", "<#8c8c8c>Скорость: <#e6fff3><speed>", "speed" to formatSpeed(level.speed)))
            add(copy("progression.level-handling", "<#8c8c8c>Управляемость: <#e6fff3>×<handling>", "handling" to formatMultiplier(level.handlingMultiplier)))
            if (level.sprintMultiplier > 1.0) {
                add(copy("progression.level-sprint", "<#8c8c8c>Форсаж: <#e6fff3>×<sprint>", "sprint" to formatMultiplier(level.sprintMultiplier)))
            }
            if (mount.movement == MountMovement.WALKING) {
                add(
                    copy(
                        "progression.level-step",
                        "<#8c8c8c>Подъём: <#e6fff3><step> блока",
                        "step" to formatHeight(configProvider().tuning.maximumStepHeightHundredths(levelNumber) / 100.0),
                    ),
                )
            }
            when {
                owned -> add(copy("progression.level-card-owned", "<#2bba43>Уже открыто"))
                available -> {
                    add(priceLine("Цена", checkNotNull(level.price).toExactMinor(), mount.currency))
                    add(
                        if (configProvider().purchasesEnabled && merchantAvailable) actionFooter("купить")
                        else configProvider().guiText("common.purchases-at-spawn", "<#ff9f0f>Покупка: Гектор или Эмма во дворе маунтов на спавне."),
                    )
                }
                level.price == null -> add(copy("progression.level-card-special", "<#ff9f0f><acquisition>", "acquisition" to escape(mount.acquisition)))
                else -> add(copy("progression.level-card-locked", "<#969696>Сначала откройте предыдущий уровень"))
            }
        }
        return item(
            when {
                owned -> Material.LIME_DYE
                else -> Material.RED_DYE
            },
            copy(statePath, stateFallback, "level" to levelNumber.toString()),
            lore,
            glint = available,
        )
    }

    fun tuningButtonItem(profile: MountProfile): ItemStack =
        item(
            Material.COMPARATOR,
            copy("settings.name", "<#92bed8>Настройки"),
            if (profile.unlocked) {
                copyLines(
                    "settings.lore",
                    listOf(
                        "<#b8b8b8>Скорость, подъём, размер и обзор.",
                        "<#b8b8b8>Свечение, облики и способности.",
                        "",
                        "<#92bed8>ЛКМ — открыть настройки",
                    ),
                )
            } else {
                copyLines(
                    "settings.locked-lore",
                    listOf("<#c42323>Сначала получите маунта."),
                )
            },
            glint = profile.unlocked,
        )

    fun progressionInfoItem(
        mount: MountDefinition,
        profile: MountProfile,
        tuning: MountTuningDefinition,
    ): ItemStack =
        item(
            Material.RECOVERY_COMPASS,
            copy("settings.info-name", "<#92bed8>Профиль движения"),
            if (!profile.unlocked) {
                copyLines(
                    "settings.info-locked-lore",
                    listOf(
                        "<#8c8c8c>Уровень открывает максимум характеристик.",
                        "<#8c8c8c>Вы сами выбираете значение внутри предела.",
                        "",
                        "<#c42323>Сначала получите маунта ниже.",
                    ),
                )
            } else {
                val levelSpeed = mount.speed(profile.level)
                copyLines(
                    "settings.info-lore",
                    listOf(
                        "<#8c8c8c>Уровень открывает максимум характеристик.",
                        "<#8c8c8c>Вы сами выбираете значение внутри предела.",
                        "",
                        "<#8c8c8c>Скорость: <#e6fff3><current-speed> <#8c8c8c>/ <maximum-speed>",
                        "<#8c8c8c>Подъём: <#e6fff3><step> <#8c8c8c>/ <maximum-step> блока",
                        "<#8c8c8c>Размер: <#e6fff3><size>",
                        "",
                        "<#b8b8b8>Выбор доступных настроек бесплатный.",
                    ),
                    "current-speed" to formatSpeed(tuning.speed(levelSpeed, profile.selectedSpeedPercentage)),
                    "maximum-speed" to formatSpeed(levelSpeed),
                    "step" to formatHeight(tuning.stepHeight(profile.level, profile.selectedStepHeightHundredths)),
                    "maximum-step" to formatHeight(tuning.maximumStepHeightHundredths(profile.level) / 100.0),
                    "size" to escape(mount.effectiveSizeOption(profile.selectedSizeId, profile.level, profile.ownedSizeIds)?.displayName?.lowercase().orEmpty()),
                ).filterIndexed { index, _ ->
                    (index != 4 || mount.movement == MountMovement.WALKING) &&
                        (index != 5 || mount.sizeOptions.isNotEmpty())
                }
            },
        )

    fun progressionLevelsInfoItem(mount: MountDefinition, profile: MountProfile): ItemStack =
        item(
            Material.EXPERIENCE_BOTTLE,
            copy("progression.levels-info-name", "<#92bed8>Уровни маунта"),
            copyLines(
                "progression.levels-info-lore",
                listOf(
                    "<#8c8c8c>Текущий уровень: <#e6fff3><level>/<max-level>",
                    "",
                    "<#8c8c8c>Выберите карточку доступного уровня,",
                    "<#8c8c8c>чтобы посмотреть цену и открыть покупку.",
                ),
                "level" to profile.level.toString(),
                "max-level" to mount.maxLevel.toString(),
            ),
        )

    fun speedTuningItem(
        mount: MountDefinition,
        profile: MountProfile,
        tuning: MountTuningDefinition,
        percentage: Int,
    ): ItemStack {
        val selected = profile.unlocked && tuning.speedPercentage(profile.selectedSpeedPercentage) == percentage
        val material =
            if (!profile.unlocked) Material.GRAY_DYE
            else SPEED_TUNING_MATERIALS[tuning.speedPercentages.indexOf(percentage).coerceAtLeast(0)]
        return item(
            material,
            copy(
                if (selected) "progression.speed-selected-name" else "progression.speed-name",
                if (selected) "<#2bba43>Скорость: <percentage>%" else "<#92bed8>Скорость: <percentage>%",
                "percentage" to percentage.toString(),
            ),
            buildList {
                if (profile.unlocked) {
                    addAll(
                        copyLines(
                            "progression.speed-lore",
                            listOf(
                                "<#8c8c8c>Фактически: <#e6fff3><actual-speed>",
                                "<#8c8c8c>От максимума уровня: <#e6fff3><percentage>%",
                            ),
                            "actual-speed" to formatSpeed(mount.speed(profile.level) * percentage / 100.0),
                            "percentage" to percentage.toString(),
                        ),
                    )
                    if (selected) add(configProvider().guiText("common.selected", "<#2bba43>Выбрано"))
                    else {
                        add("")
                        add(actionFooter("выбрать"))
                    }
                } else {
                    add(copy("progression.mount-required", "<#c42323>Сначала получите маунта"))
                }
            },
            glint = selected,
        )
    }

    fun stepHeightTuningItem(
        profile: MountProfile,
        tuning: MountTuningDefinition,
        hundredths: Int,
    ): ItemStack {
        val available = profile.unlocked && hundredths <= tuning.maximumStepHeightHundredths(profile.level)
        val selected = available && tuning.stepHeightHundredths(profile.level, profile.selectedStepHeightHundredths) == hundredths
        val requiredLevel =
            tuning.walkingMaxStepHeightByLevelHundredths.indexOfFirst { it >= hundredths }
                .takeIf { it >= 0 }
                ?.plus(1)
        val material =
            if (!available) Material.RED_DYE
            else STEP_TUNING_MATERIALS[tuning.walkingStepHeightsHundredths.indexOf(hundredths).coerceAtLeast(0)]
        return item(
            material,
            copy(
                when {
                    selected -> "progression.step-selected-name"
                    !available -> "progression.step-locked-name"
                    else -> "progression.step-name"
                },
                when {
                    selected -> "<#2bba43>Подъём: <step> блока"
                    !available -> "<#c42323>Подъём: <step> блока"
                    else -> "<#92bed8>Подъём: <step> блока"
                },
                "step" to formatHeight(hundredths / 100.0),
            ),
            buildList {
                addAll(
                    copyLines(
                        "progression.step-lore",
                        listOf(
                            "<#8c8c8c>Маунт автоматически заходит",
                            "<#8c8c8c>на препятствия этой высоты.",
                        ),
                    ),
                )
                if (hundredths >= 300) {
                    add(copy("progression.step-high-warning", "<#ff9f0f>Высокий подъём неудобен под низким потолком."))
                }
                when {
                    !profile.unlocked -> add(copy("progression.mount-required", "<#c42323>Сначала получите маунта"))
                    !available ->
                        add(
                            copy(
                                "progression.unlocks-at-level",
                                "<#ff9f0f>Откроется на уровне <level>",
                                "level" to (requiredLevel?.toString() ?: copy("progression.higher-level", "выше")),
                            ),
                        )
                    selected -> add(configProvider().guiText("common.selected", "<#2bba43>Выбрано"))
                    else -> {
                        add("")
                        add(actionFooter("выбрать"))
                    }
                }
            },
            glint = selected,
        )
    }

    fun sizeTuningItem(
        mount: MountDefinition,
        profile: MountProfile,
        option: MountSizeOptionDefinition,
        merchantAvailable: Boolean = true,
    ): ItemStack {
        val entitled = profile.ownsSize(option)
        val available = profile.unlocked && option.minimumLevel <= profile.level && entitled
        val selected = available && mount.effectiveSizeOption(profile.selectedSizeId, profile.level, profile.ownedSizeIds)?.id == option.id
        val material =
            when (option.id) {
                "compact", "keychain" -> Material.RABBIT_HIDE
                "giant", "massive", "huge" -> Material.HEAVY_CORE
                "absurd", "colossal" -> Material.DRAGON_EGG
                else -> Material.ARMOR_STAND
            }
        return item(
            if (available) material else Material.RED_DYE,
            copy(
                when {
                    selected -> "progression.size-selected-name"
                    !available -> "progression.size-locked-name"
                    else -> "progression.size-name"
                },
                when {
                    selected -> "<#2bba43>Размер: <size>"
                    !available -> "<#c42323>Размер: <size>"
                    else -> "<#92bed8>Размер: <size>"
                },
                "size" to escape(option.displayName.lowercase()),
            ),
            buildList {
                add(
                    configProvider().guiText("progression.size-scale", "<#8c8c8c>Масштаб: <#e6fff3>×<scale>")
                        .replace("<scale>", formatMultiplier(option.multiplier)),
                )
                when {
                    !profile.unlocked -> add(copy("progression.mount-required", "<#c42323>Сначала получите маунта"))
                    option.minimumLevel > profile.level ->
                        add(
                            copy(
                                "progression.unlocks-at-level",
                                "<#ff9f0f>Откроется на уровне <level>",
                                "level" to option.minimumLevel.toString(),
                            ),
                        )
                    option.grantOnly && !entitled -> {
                        if (option.price == null) {
                            add(copy("progression.size-special", "<#ff9f0f>Особый размер"))
                            add(copy("progression.size-special-source", "<#8c8c8c>Награда событий и особых активностей."))
                        } else {
                            add(priceLine("Цена", option.price.toExactMinor(), option.currency))
                            if (configProvider().purchasesEnabled && merchantAvailable) {
                                add("")
                                add(actionFooter("открыть покупку"))
                            } else {
                                add(configProvider().guiText("common.purchases-at-spawn", "<#ff9f0f>Покупка: Гектор или Эмма во дворе маунтов на спавне."))
                            }
                        }
                    }
                    !available ->
                        add(
                            copy(
                                "progression.unlocks-at-level",
                                "<#ff9f0f>Откроется на уровне <level>",
                                "level" to option.minimumLevel.toString(),
                            ),
                        )
                    selected -> add(configProvider().guiText("common.selected", "<#2bba43>Выбрано"))
                    else -> {
                        add("")
                        add(actionFooter("выбрать"))
                    }
                }
            },
            glint = selected,
        )
    }

    fun riderViewTuningItem(mount: MountDefinition, profile: MountProfile): ItemStack {
        val enabled = mount.effectiveRiderViewAutoHide(profile)
        return item(
            if (!profile.unlocked) Material.GRAY_DYE else if (enabled) Material.ENDER_EYE else Material.GLASS,
            copy(
                if (enabled) "progression.rider-view-auto-name" else "progression.rider-view-visible-name",
                if (enabled) "<#2bba43>Корпус: скрывается" else "<#92bed8>Корпус: всегда виден",
            ),
            buildList {
                addAll(
                    copyLines(
                        "progression.rider-view-lore",
                        listOf(
                            "<#8c8c8c>Скрывает маунта только для всадника,",
                            "<#8c8c8c>когда корпус перекрывает обзор.",
                            "<#8c8c8c>У гигантов срабатывает раньше.",
                        ),
                    ),
                )
                if (!profile.unlocked) {
                    add(copy("progression.mount-required", "<#c42323>Сначала получите маунта"))
                } else {
                    add("")
                    add(actionFooter(if (enabled) "оставлять видимым" else "скрывать автоматически"))
                }
            },
            glint = profile.unlocked && enabled,
        )
    }

    fun summonItem(profile: MountProfile, duration: Duration): ItemStack =
        if (profile.unlocked) {
            item(
                Material.SADDLE,
                copy("detail.summon-name", "<#92bed8>Призвать маунта"),
                actionLore(
                    copyLines(
                        "detail.summon-lore",
                        listOf(
                            "<#8c8c8c>Сессия: <#e6fff3><session>",
                            "<#8c8c8c>Исчезнет без движения через <#e6fff3><idle>",
                        ),
                        "session" to formatDuration(duration),
                        "idle" to formatDuration(configProvider().idleTimeout),
                    ),
                    "призвать",
                ),
            )
        } else {
            item(
                Material.BARRIER,
                copy("detail.summon-locked-name", "<#c42323>Маунт недоступен"),
                copyLines("detail.summon-locked-lore", listOf("<#8c8c8c>Сначала получите первый уровень.")),
            )
        }

    fun glowItem(mount: MountDefinition, profile: MountProfile, merchantAvailable: Boolean = true): ItemStack =
        when {
            !profile.unlocked ->
                item(
                    Material.RED_DYE,
                    copy("detail.glow-locked-name", "<#c42323>Свечение недоступно"),
                    copyLines("detail.glow-locked-lore", listOf("<#8c8c8c>Сначала получите маунта.")),
                )
            profile.glowOwned ->
                item(
                    if (profile.glowEnabled) Material.GLOW_INK_SAC else Material.INK_SAC,
                    if (profile.glowEnabled) {
                        copy("detail.glow-enabled-name", "<#2bba43>Свечение: включено")
                    } else {
                        copy("detail.glow-disabled-name", "<#969696>Свечение: выключено")
                    },
                    actionLore(
                        copyLines("detail.glow-enabled-lore", listOf("<#8c8c8c>Свечение куплено навсегда.")),
                        if (profile.glowEnabled) "выключить" else "включить",
                    ),
                    glint = profile.glowEnabled,
                )
            mount.glowPrice != null ->
                item(
                    Material.RED_DYE,
                    copy("detail.glow-buy-name", "<#c42323>Купить свечение"),
                    buildList {
                        add(priceLine("Цена", mount.glowPrice.toExactMinor(), mount.glowCurrency))
                        if (configProvider().purchasesEnabled && merchantAvailable) {
                            add("")
                            add(actionFooter("открыть покупку"))
                        } else {
                            add(configProvider().guiText("common.purchases-at-spawn", "<#ff9f0f>Покупка: Гектор или Эмма во дворе маунтов на спавне."))
                        }
                    },
                )
            else ->
                item(
                    Material.BARRIER,
                    copy("detail.glow-unavailable-name", "<#969696>Свечение недоступно"),
                    copyLines("detail.glow-unavailable-lore", listOf("<#8c8c8c>Это украшение не продаётся.")),
                )
        }

    fun skinsItem(mount: MountDefinition, profile: MountProfile): ItemStack =
        if (!profile.unlocked) {
            item(
                Material.GRAY_DYE,
                copy("detail.skins-locked-name", "<#969696>Облики недоступны"),
                copyLines("detail.skins-locked-lore", listOf("<#8c8c8c>Сначала получите маунта.")),
            )
        } else {
            item(
                Material.LEATHER_HORSE_ARMOR,
                copy("detail.skins-name", "<#ffacd5>Облики и украшения"),
                actionLore(
                    copyLines(
                        "detail.skins-lore",
                        listOf(
                            "<#8c8c8c>Выбран: <#e6fff3><skin>",
                            "<#8c8c8c>Получено: <#e6fff3><owned>/<available>",
                        ),
                        "skin" to escape(skinName(mount, profile.activeSkinId)),
                        "owned" to (profile.ownedSkinIds.size + 1).toString(),
                        "available" to (mount.skins.size + 1).toString(),
                    ),
                    "открыть",
                ),
                glint = profile.activeSkinId != MountDefinition.DEFAULT_SKIN_ID,
            )
        }

    fun abilityItem(
        profile: MountProfile,
        ability: MountAbilityUpgradeDefinition,
        merchantAvailable: Boolean = true,
    ): ItemStack {
        val owned = profile.ownsAbility(ability.id)
        return item(
            if (owned) Material.matchMaterial(ability.iconMaterial) ?: Material.PAPER else Material.RED_DYE,
            copy(
                if (owned) "detail.ability-owned-name" else "detail.ability-available-name",
                if (owned) "<#2bba43><ability>" else "<#c42323><ability>",
                "ability" to escape(ability.displayName),
            ),
            buildList {
                ability.description.forEach {
                    add(
                        copy(
                            "detail.ability-description",
                            "<#8c8c8c><ability-description>",
                            "ability-description" to escape(it),
                        ),
                    )
                }
                if (ability.speedMultiplier > 1.0) {
                    add(
                        copy(
                            "detail.ability-speed",
                            "<#8c8c8c>Скорость маунта: <#2bba43>+<increase>%",
                            "increase" to ((ability.speedMultiplier - 1.0) * 100.0).roundToInt().toString(),
                        ),
                    )
                }
                when {
                    !profile.unlocked -> add(copy("progression.mount-required", "<#c42323>Сначала получите маунта"))
                    owned -> add(copy("detail.ability-owned", "<#2bba43>Куплено навсегда"))
                    else -> {
                        add(priceLine("Цена", ability.price.toExactMinor(), ability.currency))
                        if (configProvider().purchasesEnabled && merchantAvailable) {
                            add("")
                            add(actionFooter("открыть покупку"))
                        } else {
                            add(configProvider().guiText("common.purchases-at-spawn", "<#ff9f0f>Покупка: Гектор или Эмма во дворе маунтов на спавне."))
                        }
                    }
                }
            },
            glint = owned,
        )
    }

    fun skinItem(mount: MountDefinition, profile: MountProfile, skinId: String, merchantAvailable: Boolean = true): ItemStack {
        if (skinId == MountDefinition.DEFAULT_SKIN_ID) {
            val selected = profile.activeSkinId == skinId
            return item(
                mount.appearance.equipment.values.firstOrNull()?.let(Material::matchMaterial) ?: Material.SADDLE,
                if (selected) {
                    copy("skins.classic-selected-name", "<#2bba43>Классический")
                } else {
                    copy("skins.classic-available-name", "<#e6fff3>Классический")
                },
                buildList {
                    add(configProvider().guiText("skins.classic-description", "<#8c8c8c>Базовый облик без следа."))
                    if (selected) add(configProvider().guiText("common.selected", "<#2bba43>Выбрано"))
                    else {
                        add("")
                        add(actionFooter("выбрать"))
                    }
                },
                glint = selected,
            )
        }
        val skin = checkNotNull(mount.skin(skinId))
        val owned = profile.ownsSkin(skinId)
        val selected = profile.activeSkinId == skinId
        return item(
            if (owned || selected) Material.matchMaterial(skin.iconMaterial) ?: Material.LEATHER_HORSE_ARMOR else Material.RED_DYE,
            when {
                selected -> copy("skins.skin-selected-name", "<#2bba43><skin>", "skin" to escape(skin.displayName))
                owned -> copy("skins.skin-owned-name", "<#ffacd5><skin>", "skin" to escape(skin.displayName))
                else -> copy("skins.skin-buy-name", "<#c42323><skin>", "skin" to escape(skin.displayName))
            },
            listOf("") + appearanceDeltaLore(mount, skin) + buildList {
                when {
                    selected -> add(configProvider().guiText("common.selected", "<#2bba43>Выбрано"))
                    owned -> {
                        add("")
                        add(actionFooter("выбрать"))
                    }
                    skin.price != null -> {
                        add(priceLine("Цена", skin.price.toExactMinor(), skin.currency))
                        if (configProvider().purchasesEnabled && merchantAvailable) {
                            add("")
                            add(actionFooter("открыть покупку"))
                        } else {
                            add(configProvider().guiText("common.purchases-at-spawn", "<#ff9f0f>Покупка: Гектор или Эмма во дворе маунтов на спавне."))
                        }
                    }
                    else -> add(copy("skins.special-reward", "<#ff9f0f>Особая награда"))
                }
            },
            glint = selected,
        )
    }

    fun appearanceDeltaLore(mount: MountDefinition, skin: MountSkinDefinition): List<String> =
        buildList {
            val base = mount.appearance
            val appearance = skin.appearance
            val entityType = runCatching { org.bukkit.entity.EntityType.valueOf(mount.entityType) }.getOrNull()
            if (entityType != null && MountAppearanceApplicator.supportsAge(entityType) && appearance.baby != base.baby) {
                add(
                    copy(
                        "skins.age",
                        "<#8c8c8c>Возраст: <#e6fff3><age>",
                        "age" to copy(if (appearance.baby) "skins.age-baby" else "skins.age-adult", if (appearance.baby) "малыш" else "взрослый"),
                    ),
                )
            }
            if (appearance.scale != base.scale) {
                add(
                    copy(
                        "skins.size",
                        "<#8c8c8c>Размер: <#e6fff3>×<scale>",
                        "scale" to formatMultiplier(appearance.scale / base.scale),
                    ),
                )
            }
            if (appearance.variant != base.variant) {
                appearance.variant?.let {
                    add(copy("skins.variant", "<#8c8c8c>Вариант: <#e6fff3><variant>", "variant" to escape(localizedVariant(it))))
                }
            }
            if (appearance.secondaryVariant != base.secondaryVariant) {
                appearance.secondaryVariant?.let {
                    add(copy("skins.pattern", "<#8c8c8c>Узор: <#e6fff3><pattern>", "pattern" to escape(localizedVariant(it))))
                }
            }
            if (appearance.equipment != base.equipment) {
                add(
                    copy(
                        "skins.equipment",
                        "<#8c8c8c>Экипировка: <#e6fff3><equipment>",
                        "equipment" to equipmentSummary(appearance.equipment),
                    ),
                )
            }
            skin.trail?.let {
                add(
                    configProvider().guiText("skins.trail", "<#8c8c8c>След: <#e6fff3><trail>")
                        .replace("<trail>", escape(it.displayName)),
                )
            }
            if (isEmpty()) add(copy("skins.base-variant", "<#8c8c8c>Вариант базового облика без следа."))
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
        return item(style.material ?: fallbackMaterial, display, lore, glint, style.customModelData, hideTooltip)
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
    private fun component(text: String): Component = TextUtils.mm(text, true)
    private fun escape(value: String): String = value.replace("<", "\\<").replace(">", "\\>")
    private fun copy(path: String, fallback: String, vararg values: Pair<String, String>): String =
        fill(configProvider().guiText(path, fallback), *values)

    private fun copyLines(path: String, fallback: List<String>, vararg values: Pair<String, String>): List<String> =
        configProvider().guiLines(path, fallback).map { fill(it, *values) }

    private fun fill(template: String, vararg values: Pair<String, String>): String =
        values.fold(template) { current, (key, value) -> current.replace("<$key>", value) }

    private fun movementColor(movement: MountMovement): String =
        when (movement) {
            MountMovement.WALKING -> copy("list.type-walking-color", "<gray>")
            MountMovement.FLYING -> copy("list.type-flying-color", "<green>")
            MountMovement.SWIMMING -> copy("list.type-swimming-color", "<aqua>")
        }

    private fun movementName(movement: MountMovement): String =
        copy("list.type-${movement.name.lowercase()}-name", movement.displayName)

    private fun rarity(rarity: MountRarity): String =
        copy("list.rarity-${rarity.name.lowercase()}", "${rarity.color}${rarity.displayName}")

    private fun skinName(mount: MountDefinition, skinId: String): String =
        if (skinId == MountDefinition.DEFAULT_SKIN_ID) {
            copy("skins.classic-name", "Классический")
        } else {
            mount.skin(skinId)?.displayName ?: copy("skins.classic-name", "Классический")
        }
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

    private fun priceLine(label: String, amountMinor: Long, currency: String): String {
        val amount = TextUtils.formatAmount(amountMinor.minorToDouble())
        return configProvider().guiText("common.price-$currency", "<#8c8c8c>Цена: <#ffacd5><price> <currency>")
            .replace("<price>", amount).replace("<currency>", currencyLabel(configProvider(), currency))
    }

    private fun localizedVariant(value: String): String {
        val normalized = value.uppercase(java.util.Locale.ROOT)
        val fallback =
            when (normalized) {
                "COLD" -> "холодный"
                "TEMPERATE" -> "умеренный"
                "WARM" -> "тропический"
                "RED" -> "рыжий"
                "SNOW" -> "снежный"
                "BROWN" -> "коричневый"
                "WHITE" -> "белый"
                "BLACK" -> "чёрный"
                "CREAMY" -> "кремовый"
                "CHESTNUT" -> "каштановый"
                "GRAY" -> "серый"
                "DARK_BROWN" -> "тёмно-коричневый"
                else -> value.lowercase(java.util.Locale.ROOT).replace('_', ' ')
            }
        return copy("skins.variants.${normalized.lowercase(java.util.Locale.ROOT).replace('_', '-')}", fallback)
    }

    private fun equipmentSummary(equipment: Map<MountEquipmentSlot, String>): String {
        val key =
            when {
                equipment.isEmpty() -> "none"
                equipment.values.any { it.startsWith("NETHERITE_") } -> "netherite"
                equipment.values.any { it.startsWith("DIAMOND_") } -> "diamond"
                equipment.values.any { it.startsWith("IRON_") } -> "iron"
                equipment.values.any { it.startsWith("GOLDEN_") } -> "golden"
                else -> "special"
            }
        val fallback =
            when (key) {
                "none" -> "без экипировки"
                "netherite" -> "незеритовый комплект"
                "diamond" -> "алмазный комплект"
                "iron" -> "железный комплект"
                "golden" -> "золотой комплект"
                else -> "особый комплект"
            }
        return copy("skins.equipment-$key", fallback)
    }

    private fun formatSpeed(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')

    private fun mountFeatureLore(mount: MountDefinition): List<String> {
        val features =
            buildList<Pair<String, List<String>>> {
                mount.abilities.highJump?.let { add(it.displayName to highJumpDescription(it)) }
                mount.abilities.passives.forEach { add(it.displayName to passiveDescription(it)) }
                mount.behaviors.forEach { add(it.displayName to it.description) }
                if (mount.passengerSeats > 0) {
                    add(
                        copy(
                            "list.passenger-feature-name",
                            "Пассажирские места: <passengers>",
                            "passengers" to mount.passengerSeats.toString(),
                        ) to
                            listOf(
                                copy(
                                    "list.passenger-feature-description",
                                    "Дополнительных мест: <passengers>",
                                    "passengers" to mount.passengerSeats.toString(),
                                ),
                            ),
                    )
                }
            }
        if (features.isEmpty()) return emptyList()
        return buildList {
            add("")
            add(copy("list.features-title", "<#92bed8>Особенности"))
            features.forEach { (name, descriptions) ->
                add(copy("list.feature-name", "<#ffacd5>• <feature>", "feature" to escape(name)))
                descriptions.forEach { description ->
                    add(
                        copy(
                            "list.feature-description",
                            "<#8c8c8c>  <feature-description>",
                            "feature-description" to escape(description),
                        ),
                    )
                }
            }
            if (mount.passengerSeats > 0) {
                add(copy("list.passenger-feature-action", "<#8c8c8c>  <#92bed8>ПКМ по маунту<#e6fff3> — сесть пассажиром"))
            }
        }
    }

    private fun highJumpDescription(ability: MountHighJumpAbility): List<String> =
        ability.description.ifEmpty { listOf("Прыжок усилен в ×${formatMultiplier(ability.multiplier)}.") }

    private fun passiveDescription(ability: MountPassiveAbilityDefinition): List<String> =
        ability.description.ifEmpty {
            listOf(
                when (ability.effect) {
                    MountAbilityEffect.WATER_BREATHING -> "Позволяет всаднику дышать под водой."
                    MountAbilityEffect.NIGHT_VISION -> "Темнота больше не мешает обзору."
                    MountAbilityEffect.FIRE_RESISTANCE -> "Защищает всадника от огня и лавы."
                    MountAbilityEffect.DOLPHINS_GRACE -> "Ускоряет всадника в воде."
                    MountAbilityEffect.RESISTANCE -> "Снижает входящий урон всаднику."
                    MountAbilityEffect.REGENERATION -> "Постепенно восстанавливает здоровье."
                    MountAbilityEffect.SPEED -> "Ускоряет всадника после спешивания."
                    MountAbilityEffect.SLOW_FALLING -> "Смягчает падение всадника."
                    MountAbilityEffect.STRENGTH -> "Усиливает атаки всадника."
                    MountAbilityEffect.HASTE -> "Ускоряет добычу блоков всадником."
                    MountAbilityEffect.LUCK -> "Даёт всаднику эффект удачи."
                },
            )
        }

    private fun formatHeight(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)
    private fun formatMultiplier(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    private fun formatDuration(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(1L)
        return when {
            seconds % 3_600L == 0L ->
                copy("common.duration-hours", "<value> ч", "value" to (seconds / 3_600L).toString())
            seconds % 60L == 0L ->
                copy("common.duration-minutes", "<value> мин", "value" to (seconds / 60L).toString())
            else -> copy("common.duration-seconds", "<value> сек", "value" to seconds.toString())
        }
    }

    private companion object {
        val SPEED_TUNING_MATERIALS =
            listOf(
                Material.LEATHER_BOOTS,
                Material.CHAINMAIL_BOOTS,
                Material.IRON_BOOTS,
                Material.GOLDEN_BOOTS,
                Material.DIAMOND_BOOTS,
            )
        val STEP_TUNING_MATERIALS =
            listOf(
                Material.OAK_SLAB,
                Material.OAK_STAIRS,
                Material.GRASS_BLOCK,
                Material.PISTON,
                Material.GOAT_HORN,
            )
    }
}

internal fun currencyLabel(config: MountModuleConfig, currency: String): String =
    config.guiText("currencies.$currency", if (currency == "tokens") "<#ffacd5>жетонов" else "<white>монет")
