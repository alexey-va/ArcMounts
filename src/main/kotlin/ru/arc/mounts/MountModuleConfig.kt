package ru.arc.mounts

import net.kyori.adventure.text.Component
import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.text.LocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

enum class MountGuiItemRole(val configKey: String) {
    BACKGROUND("background"),
    BACK("back"),
    PREVIOUS("previous"),
    NEXT("next"),
    CATEGORY_ALL("category-all"),
    CATEGORY_FLYING("category-flying"),
    CATEGORY_WALKING("category-walking"),
    CATEGORY_SWIMMING("category-swimming"),
    INFO("info"),
    BALANCE("balance"),
    FAVORITE("favorite"),
    WHISTLE("whistle"),
    CONFIRM("confirm"),
    CANCEL("cancel"),
}

data class MountGuiItemStyle(
    val material: Material? = null,
    val customModelData: Int? = null,
)

open class MountModuleConfig(private val config: Config) {
    private val fallbackMessages = ConcurrentHashMap<String, String>()
    private val localeCatalog = object : LocaleCatalog {
        override fun scalar(path: String): String? =
            config.stringOrNull(path) ?: fallbackMessages[path.removePrefix("messages.")]

        override fun lines(path: String): List<String>? = config.stringListOrNull(path)
    }
    private val localizedMessages =
        LocalizedMiniMessage(
            catalogs = mapOf("ru" to localeCatalog),
            defaultLocale = { "ru" },
            prefixPath = "messages.prefix",
        )

    open val enabled: Boolean get() = config.bool("enabled", false)
    open val ownershipMigrationComplete: Boolean get() = config.bool("ownership-migration-complete", false)
    open val allowedWorlds: Set<String> get() = config.stringList("allowed-worlds").map { it.lowercase(Locale.ROOT) }.toSet()
    open val sessionDuration: Duration get() = config.duration("session-duration", Duration.ofMinutes(10))
    open val adminSessionDuration: Duration get() = config.duration("admin-session-duration", Duration.ofSeconds(5))
    open val purchasesEnabled: Boolean get() = config.bool("purchases-enabled", false)
    open val quickSummonSneakSwapHands: Boolean get() = config.bool("quick-summon.sneak-swap-hands", true)
    open val quickSummonWhistle: Boolean get() = config.bool("quick-summon.whistle", true)
    open val idleTimeout: Duration get() = config.duration("safety.idle-timeout", Duration.ofMinutes(5))
    open val summonCooldown: Duration get() = config.duration("safety.summon-cooldown", Duration.ofSeconds(2))
    open val riderKnockoffDamage: Double get() = config.double("safety.rider-knockoff-damage", 6.0)
    open val doubleSneakWindow: Duration get() = config.duration("controls.double-sneak-window", Duration.ofMillis(450))
    open val descendingHintCooldown: Duration get() = config.duration("controls.hint-cooldown", Duration.ofSeconds(5))
    open val passengerCarrierScale: Double get() = config.double("passengers.carrier-scale", 0.8)
    open val passengerCarrierYawOffset: Double get() = config.double("passengers.carrier-yaw-offset", 90.0)
    open val hideFlyingMountFromRider: Boolean get() = config.bool("rider-view.hide-flying-mount", true)
    open val hideFlyingMountPitch: Double get() = config.double("rider-view.hide-at-pitch", 35.0)
    open val showFlyingMountPitch: Double get() = config.double("rider-view.show-at-pitch", 20.0)
    open val walkingSpeedScale: Double get() = config.double("movement.walking-speed-scale", 0.16)
    open val flyingSpeedScale: Double get() = config.double("movement.flying-speed-scale", 0.55)
    open val swimmingSpeedScale: Double get() = config.double("movement.swimming-speed-scale", 0.45)
    open val motionTiming: MountMotionTiming
        get() =
            MountMotionTiming(
                accelerationTime = config.duration("movement.acceleration-time", Duration.ZERO),
                decelerationTime = config.duration("movement.deceleration-time", Duration.ZERO),
                turnTime = config.duration("movement.turn-time", Duration.ZERO),
            )
    open val sprintMultiplier: Double get() = config.double("movement.sprint-multiplier", 1.15)
    open val jumpVelocity: Double get() = config.double("movement.jump-velocity", 0.5)
    open val verticalSpeedRatio: Double get() = config.double("movement.vertical-speed-ratio", 0.75)
    open val maximumVerticalSpeed: Double get() = config.double("movement.maximum-vertical-speed", 0.5)
    open val flightPitchInfluence: Double get() = config.double("movement.flight-pitch-influence", 0.65)
    open val maximumSpeedBlocksPerTick: Double get() = config.double("movement.maximum-speed-blocks-per-tick", 1.05)
    open val maximumHeightAboveWorld: Int get() = config.integer("movement.maximum-height-above-world", 32)
    open val compensateAirborneMining: Boolean get() = config.bool("movement.compensate-airborne-mining", true)
    open val postFlightSlowFalling: Duration get() = config.duration("safety.post-flight-slow-falling", Duration.ofSeconds(8))
    open val backCommand: String get() = config.string("gui.back-command", "m").trim().removePrefix("/")
    open val listTitle: String get() = config.string("gui.list-title", "<#20252b><bold>Коллекция маунтов</bold>")
    open val detailTitle: String get() = config.string("gui.detail-title", "<#20252b><bold><mount></bold>")
    open val progressionTitle: String get() = config.string("gui.progression-title", "<#20252b><bold>Развитие маунта</bold>")
    open val skinsTitle: String get() = config.string("gui.skins-title", "<#20252b><bold>Облики маунта</bold>")
    open val confirmTitle: String get() = config.string("gui.confirm-title", "<#20252b><bold>Покупка маунта</bold>")

    open val tuning: MountTuningDefinition
        get() =
            MountTuningDefinition(
                speedPercentages = intList("tuning.speed-percentages", listOf(50, 65, 80, 90, 100)),
                walkingStepHeightsHundredths =
                    decimalHundredthsList("tuning.walking-step-heights", listOf("1.10", "1.50", "2.00", "3.00", "4.00")),
                walkingMaxStepHeightByLevelHundredths =
                    decimalHundredthsList("tuning.walking-max-step-height-by-level", listOf("1.10", "2.00", "4.00")),
            )

    open fun guiStyle(role: MountGuiItemRole): MountGuiItemStyle {
        val path = "gui.items.${role.configKey}"
        val rawMaterial = config.stringOrNull("$path.material")?.trim()?.takeIf(String::isNotEmpty)
        val material =
            rawMaterial?.let {
                Material.matchMaterial(it)
                    ?: Material.matchMaterial(it.uppercase(Locale.ROOT))
                    ?: throw IllegalArgumentException("Mount GUI item '${role.configKey}' has invalid material '$it'")
            }
        val customModelData =
            if (config.exists("$path.customModelData")) {
                config.integer("$path.customModelData", 0).also {
                    require(it > 0) { "Mount GUI item '${role.configKey}' customModelData must be positive" }
                }
            } else {
                null
            }
        return MountGuiItemStyle(material, customModelData)
    }

    open fun catalog(): MountCatalog {
        val abilityUpgrades = abilityUpgrades()
        val definitions =
            config.keys("mounts").map { rawId ->
                val id = rawId.lowercase(Locale.ROOT)
                require(id == rawId) { "Mount id '$rawId' must be normalized lowercase" }
                val root = "mounts.$id"
                val levels = levelList(root, id)
                val appearance = appearance("$root.appearance")
                val skins = skinList(root, id)
                MountDefinition(
                    id = id,
                    movement = strictMovement(config.string("$root.type", "walking"), id),
                    entityType = config.string("$root.entity", id).trim().uppercase(Locale.ROOT),
                    passengerSeats = config.integer("$root.passenger-seats", 0),
                    iconMaterial = config.string("$root.item", "PAPER").trim().uppercase(Locale.ROOT),
                    displayName = config.string("$root.name", id).trim(),
                    description = config.stringList("$root.description").map(String::trim).filter(String::isNotEmpty),
                    acquisition = config.string("$root.acquisition", "Магазин маунтов").trim(),
                    rarity = strictRarity(config.string("$root.rarity", "common"), id),
                    levels = levels,
                    glowPrice = config.doubleOrNull("$root.buy-glow"),
                    abilities = abilities("$root.abilities", abilityUpgrades),
                    appearance = appearance,
                    skins = skins,
                    sizeOptions = sizeOptionList(root, id, levels, appearance, skins),
                    behaviors = behaviorList(root, id),
                    motion = motion("$root.motion"),
                    currency = config.string("$root.currency", "vault").trim(),
                    control = strictControl(config.string("$root.control", "vehicle"), id),
                    visualEntityType = config.string("$root.visual-entity", "").trim().uppercase(Locale.ROOT).ifEmpty { null },
                    glowCurrency = config.string(
                        "$root.buy-glow-currency",
                        config.string("$root.currency", "vault"),
                    ).trim(),
                )
            }
        return MountCatalog(definitions)
    }

    open fun validated(nativeValidation: Boolean = true): MountModuleConfig {
        if (!enabled) return this
        require(!sessionDuration.isZero && !sessionDuration.isNegative) { "Mount session-duration must be positive" }
        require(!adminSessionDuration.isZero && !adminSessionDuration.isNegative) { "Mount admin-session-duration must be positive" }
        require(!idleTimeout.isZero && !idleTimeout.isNegative) { "Mount idle-timeout must be positive" }
        require(!summonCooldown.isNegative) { "Mount summon-cooldown cannot be negative" }
        require(riderKnockoffDamage > 0.0 && riderKnockoffDamage.isFinite()) {
            "Mount rider-knockoff-damage must be positive"
        }
        require(!doubleSneakWindow.isZero && !doubleSneakWindow.isNegative) { "Mount double-sneak-window must be positive" }
        require(passengerCarrierScale.isFinite() && passengerCarrierScale in 0.1..2.0) {
            "Mount passenger carrier-scale must be between 0.1 and 2.0"
        }
        require(passengerCarrierYawOffset.isFinite() && passengerCarrierYawOffset in -180.0..180.0) {
            "Mount passenger carrier-yaw-offset must be between -180 and 180"
        }
        require(hideFlyingMountPitch.isFinite() && hideFlyingMountPitch in -90.0..90.0) {
            "Mount hide-at-pitch must be between -90 and 90"
        }
        require(showFlyingMountPitch.isFinite() && showFlyingMountPitch in -90.0..hideFlyingMountPitch) {
            "Mount show-at-pitch must be between -90 and hide-at-pitch"
        }
        require(walkingSpeedScale > 0.0 && walkingSpeedScale.isFinite()) { "walking-speed-scale must be positive" }
        require(flyingSpeedScale > 0.0 && flyingSpeedScale.isFinite()) { "flying-speed-scale must be positive" }
        require(swimmingSpeedScale > 0.0 && swimmingSpeedScale.isFinite()) { "swimming-speed-scale must be positive" }
        motionTiming
        require(sprintMultiplier >= 1.0 && sprintMultiplier.isFinite()) { "Mount sprint-multiplier must be at least 1" }
        require(jumpVelocity > 0.0 && jumpVelocity.isFinite()) { "Mount jump-velocity must be positive" }
        require(verticalSpeedRatio > 0.0 && verticalSpeedRatio.isFinite()) { "Mount vertical-speed-ratio must be positive" }
        require(maximumVerticalSpeed > 0.0 && maximumVerticalSpeed.isFinite()) {
            "Mount maximum-vertical-speed must be positive"
        }
        require(flightPitchInfluence in 0.0..1.0) { "Mount flight-pitch-influence must be between 0 and 1" }
        require(maximumSpeedBlocksPerTick.isFinite() && maximumSpeedBlocksPerTick in 0.2..2.0) {
            "Mount maximum-speed-blocks-per-tick must be between 0.2 and 2.0"
        }
        require(maximumHeightAboveWorld in 0..256) { "Mount maximum-height-above-world must be between 0 and 256" }
        tuning
        if (nativeValidation) MountGuiItemRole.entries.forEach(::guiStyle)
        require(
            guiLines(
                "detail.mount-state-lore",
                listOf("favorite", "owned", "rarity", ""),
            ).size >= 4,
        ) { "Mount GUI detail.mount-state-lore must contain at least four rows" }
        require(
            guiLines(
                "detail.mount-stats-core-lore",
                listOf("", "heading", "type", "level", "speed", "step", "skin"),
            ).size >= 7,
        ) { "Mount GUI detail.mount-stats-core-lore must contain at least seven rows" }
        catalog()
        return this
    }

    open fun message(path: String, fallback: String): String = config.string("messages.$path", fallback)

    open fun messageComponent(
        path: String,
        fallback: String,
        localeTag: String? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component {
        require(path.isNotBlank()) { "Mount message path must not be blank" }
        fallbackMessages.putIfAbsent(path, fallback)
        return localizedMessages.render("messages.$path", localeTag, values)
    }

    open fun guiText(path: String, fallback: String): String = config.string("gui.copy.$path", fallback)

    open fun guiLines(path: String, fallback: List<String>): List<String> =
        config.stringList("gui.copy.$path", fallback)

    private fun motion(root: String): MountMotionOverride =
        MountMotionOverride(
            accelerationTime = config.durationOrNull("$root.acceleration-time"),
            decelerationTime = config.durationOrNull("$root.deceleration-time"),
            turnTime = config.durationOrNull("$root.turn-time"),
        )

    private fun levelList(root: String, mountId: String): List<MountLevelDefinition> =
        config.list<Map<String, Any?>>("$root.levels").mapIndexed { index, raw ->
            val level = index + 1
            MountLevelDefinition(
                speed = requiredDouble(raw["speed"], "Mount '$mountId' level $level speed"),
                price = nullableDouble(raw["price"], "Mount '$mountId' level $level price"),
                handlingMultiplier = optionalDouble(raw["handling"], 1.0, "Mount '$mountId' level $level handling"),
                sprintMultiplier = optionalDouble(raw["sprint"], 1.0, "Mount '$mountId' level $level sprint"),
                scaleMultiplier = optionalDouble(raw["scale"], 1.0, "Mount '$mountId' level $level scale"),
            )
        }

    private fun skinList(root: String, mountId: String): List<MountSkinDefinition> {
        val baseAppearance = appearance("$root.appearance")
        val skinEntries =
            config.keys("$root.skins").map { rawId -> rawId to "$root.skins.$rawId" } +
                if (config.bool("$root.include-shared-skins", true)) {
                    config.keys("shared-skins").map { rawId -> rawId to "shared-skins.$rawId" }
                } else {
                    emptyList()
                }
        require(skinEntries.map(Pair<String, String>::first).distinct().size == skinEntries.size) {
            "Mount '$mountId' has a skin id that conflicts with a shared skin"
        }
        return skinEntries.map { (rawId, path) ->
            val id = rawId.lowercase(Locale.ROOT)
            require(id == rawId && MountDefinition.validId(id)) { "Mount '$mountId' skin id '$rawId' must be normalized" }
            val presetId = config.stringOrNull("$path.preset")?.trim()?.lowercase(Locale.ROOT)
            if (presetId != null) require(MountDefinition.validId(presetId) && presetId in config.keys("cosmetics")) {
                "Mount '$mountId' skin '$id' has unknown cosmetic preset '$presetId'"
            }
            val presetPath = presetId?.let { "cosmetics.$it" }
            val presetAppearance = presetPath?.let { appearance("$it.appearance", baseAppearance) } ?: baseAppearance
            val currency = config.string("$path.currency", config.string("$root.currency", "vault")).trim()
            MountSkinDefinition(
                id = id,
                displayName =
                    config.stringOrNull("$path.name")?.trim()
                        ?: presetPath?.let { config.string("$it.name", id).trim() }
                        ?: id,
                iconMaterial =
                    (config.stringOrNull("$path.item")
                        ?: presetPath?.let { config.string("$it.item", "LEATHER_HORSE_ARMOR") }
                        ?: "LEATHER_HORSE_ARMOR").trim().uppercase(Locale.ROOT),
                price = config.doubleOrNull("$path.price-$currency") ?: config.doubleOrNull("$path.price"),
                appearance = appearance("$path.appearance", presetAppearance),
                trail = trail("$path.trail") ?: presetPath?.let { trail("$it.trail") },
                currency = currency,
            )
        }
    }

    private fun sizeOptionList(
        root: String,
        mountId: String,
        levels: List<MountLevelDefinition>,
        appearance: MountAppearance,
        skins: List<MountSkinDefinition>,
    ): List<MountSizeOptionDefinition> {
        val authored = config.list<Map<String, Any?>>("$root.size-tuning").mapIndexed { index, raw ->
            val allowedKeys = setOf("id", "name", "multiplier", "minimum-level", "grant-only", "price", "currency")
            val unknown = raw.keys.map(Any?::toString).toSet() - allowedKeys
            require(unknown.isEmpty()) {
                "Mount '$mountId' size option ${index + 1} has unknown fields: ${unknown.sorted()}"
            }
            val rawId = raw["id"]?.toString()?.trim().orEmpty()
            val id = rawId.lowercase(Locale.ROOT)
            require(id == rawId && MountDefinition.validId(id)) {
                "Mount '$mountId' size option ${index + 1} has an invalid normalized id '$rawId'"
            }
            val minimumLevel =
                raw["minimum-level"]?.let { value ->
                    value.toString().toIntOrNull()
                        ?: throw IllegalArgumentException("Mount '$mountId' size option '$id' minimum-level is not an integer")
                } ?: 1
            val grantOnly =
                raw["grant-only"]?.let { value ->
                    value as? Boolean
                        ?: throw IllegalArgumentException("Mount '$mountId' size option '$id' grant-only is not a boolean")
                } ?: false
            val defaultKey = when (id) {
                config.string("size-tuning-defaults.tiny.id", "keychain").trim() -> "tiny"
                config.string("size-tuning-defaults.giant.id", "colossal").trim() -> "giant"
                else -> null
            }
            MountSizeOptionDefinition(
                id = id,
                displayName = raw["name"]?.toString()?.trim().orEmpty(),
                multiplier = requiredDouble(raw["multiplier"], "Mount '$mountId' size option '$id' multiplier"),
                minimumLevel = minimumLevel,
                grantOnly = grantOnly || defaultKey != null,
                price = nullableDouble(raw["price"], "Mount '$mountId' size option '$id' price")
                    ?: defaultKey?.let { config.doubleOrNull("size-tuning-defaults.$it.price") },
                currency = raw["currency"]?.toString()?.trim()
                    ?: defaultKey?.let { config.string("size-tuning-defaults.$it.currency", "tokens").trim() }
                    ?: config.string("$root.currency", "vault").trim(),
            )
        }
        val standard = defaultSizeOption("standard", grantOnly = false)
        val tiny = defaultSizeOption("tiny", grantOnly = true)
        val giantTemplate = defaultSizeOption("giant", grantOnly = true)
        val maximumBaseScale =
            (listOf(appearance) + skins.map(MountSkinDefinition::appearance)).maxOf(MountAppearance::scale) *
                levels.maxOf(MountLevelDefinition::scaleMultiplier)
        val safeGiantMultiplier =
            floor(minOf(giantTemplate.multiplier, MAX_MOUNT_APPEARANCE_SCALE / maximumBaseScale) * 10.0) / 10.0
        val giant =
            giantTemplate.copy(
                displayName =
                    config.string("size-tuning-defaults.giant.name", "Колоссальный ×<scale>").trim()
                        .replace("<scale>", formatSizeMultiplier(safeGiantMultiplier)),
                multiplier = safeGiantMultiplier,
            )
        val normalized =
            authored.map { option ->
                if (option.multiplier == tiny.multiplier || option.multiplier == giantTemplate.multiplier) {
                    option.copy(minimumLevel = 1, grantOnly = true)
                } else {
                    option
                }
            }
        return buildList {
            addAll(normalized)
            if (none { it.multiplier == standard.multiplier }) add(standard)
            if (none { it.multiplier == tiny.multiplier }) add(tiny)
            if (none { it.multiplier == giantTemplate.multiplier || it.multiplier == giant.multiplier }) add(giant)
        }.sortedBy(MountSizeOptionDefinition::multiplier)
    }

    private fun defaultSizeOption(key: String, grantOnly: Boolean): MountSizeOptionDefinition {
        val path = "size-tuning-defaults.$key"
        val multiplier = config.double("$path.multiplier", if (key == "tiny") 0.1 else if (key == "giant") 10.0 else 1.0)
        return MountSizeOptionDefinition(
            id = config.string("$path.id", key).trim(),
            displayName = config.string("$path.name", key).trim().replace("<scale>", formatSizeMultiplier(multiplier)),
            multiplier = multiplier,
            grantOnly = grantOnly,
            price = config.doubleOrNull("$path.price"),
            currency = config.string("$path.currency", "tokens").trim(),
        )
    }

    private fun formatSizeMultiplier(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

    private fun behaviorList(root: String, mountId: String): List<MountBehaviorDefinition> =
        config.keys("$root.behaviors").map { rawId ->
            val id = rawId.lowercase(Locale.ROOT)
            require(id == rawId && MountDefinition.validId(id)) { "Mount '$mountId' behavior id '$rawId' must be normalized" }
            val path = "$root.behaviors.$id"
            val type = config.string("$path.type", "").trim().lowercase(Locale.ROOT)
            when (type) {
                "ram" -> ramBehavior(path, id, mountId)
                "trample" -> trampleBehavior(path, id, mountId)
                else -> throw IllegalArgumentException("Mount '$mountId' behavior '$id' has invalid type '$type'")
            }
        }

    private fun ramBehavior(path: String, id: String, mountId: String): MountRamBehavior {
        val allowedKeys =
            setOf(
                "type",
                "name",
                "description",
                "trigger",
                "cooldown",
                "request-window",
                "active-window-ticks",
                "minimum-speed-fraction",
                "reach",
                "lateral-padding",
                "damage",
            )
        val unknown = config.keys(path) - allowedKeys
        require(unknown.isEmpty()) { "Mount '$mountId' behavior '$id' has unknown fields: ${unknown.sorted()}" }
        val rawTrigger = config.string("$path.trigger", "sprint-forward-press").trim().uppercase(Locale.ROOT).replace('-', '_')
        val trigger = runCatching { MountBehaviorTrigger.valueOf(rawTrigger) }
            .getOrElse { throw IllegalArgumentException("Mount '$mountId' behavior '$id' has invalid trigger '$rawTrigger'") }
        return MountRamBehavior(
            id = id,
            displayName = config.string("$path.name", "Таран").trim(),
            description =
                config.stringList(
                    "$path.description",
                    listOf(
                        "Нажмите спринт при движении вперёд.",
                        "Первый враждебный моб на пути",
                        "получит урон при столкновении.",
                    ),
                ).map(String::trim).filter(String::isNotEmpty),
            trigger = trigger,
            cooldown = config.duration("$path.cooldown", Duration.ofSeconds(4)),
            requestWindow = config.duration("$path.request-window", Duration.ofMillis(1_200)),
            activeWindowTicks = config.integer("$path.active-window-ticks", 8),
            minimumSpeedFraction = config.double("$path.minimum-speed-fraction", 0.65),
            reach = config.double("$path.reach", 1.25),
            lateralPadding = config.double("$path.lateral-padding", 0.35),
            damage = config.double("$path.damage", 4.0),
        )
    }

    private fun trampleBehavior(path: String, id: String, mountId: String): MountTrampleBehavior {
        val allowedKeys =
            setOf(
                "type",
                "name",
                "description",
                "cooldown",
                "minimum-speed-fraction",
                "horizontal-padding",
                "downward-reach",
                "damage",
                "maximum-targets",
            )
        val unknown = config.keys(path) - allowedKeys
        require(unknown.isEmpty()) { "Mount '$mountId' behavior '$id' has unknown fields: ${unknown.sorted()}" }
        return MountTrampleBehavior(
            id = id,
            displayName = config.string("$path.name", "Топот").trim(),
            description =
                config.stringList(
                    "$path.description",
                    listOf(
                        "Враждебные мобы под корпусом",
                        "получают урон во время движения.",
                    ),
                ).map(String::trim).filter(String::isNotEmpty),
            cooldown = config.duration("$path.cooldown", Duration.ofSeconds(1)),
            minimumSpeedFraction = config.double("$path.minimum-speed-fraction", 0.2),
            horizontalPadding = config.double("$path.horizontal-padding", 0.2),
            downwardReach = config.double("$path.downward-reach", 0.6),
            damage = config.double("$path.damage", 2.0),
            maximumTargets = config.integer("$path.maximum-targets", 4),
        )
    }

    private fun appearance(path: String, fallback: MountAppearance = MountAppearance()): MountAppearance {
        val equipment =
            MountEquipmentSlot.entries.mapNotNull { slot ->
                config.stringOrNull("$path.equipment.${slot.configKey}")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.uppercase(Locale.ROOT)
                    ?.let { slot to it }
            }.toMap()
        return MountAppearance(
            baby = config.booleanOrNull("$path.baby") ?: fallback.baby,
            scale = config.doubleOrNull("$path.scale") ?: fallback.scale,
            variant = normalizedAppearanceValue(config.stringOrNull("$path.variant")) ?: fallback.variant,
            secondaryVariant =
                normalizedAppearanceValue(config.stringOrNull("$path.secondary-variant")) ?: fallback.secondaryVariant,
            equipment = if (equipment.isEmpty()) fallback.equipment else equipment,
        )
    }

    private fun abilityUpgrades(): Map<String, MountAbilityUpgradeDefinition> =
        config.keys("ability-upgrades").associate { rawId ->
            val id = rawId.lowercase(Locale.ROOT)
            require(id == rawId && MountDefinition.validId(id)) { "Mount ability id '$rawId' must be normalized" }
            val path = "ability-upgrades.$id"
            val rawEffect = config.string("$path.effect", id).trim().uppercase(Locale.ROOT).replace('-', '_')
            val effect = runCatching { MountAbilityEffect.valueOf(rawEffect) }
                .getOrElse { throw IllegalArgumentException("Mount ability '$id' has invalid effect '$rawEffect'") }
            id to
                MountAbilityUpgradeDefinition(
                    id = id,
                    displayName = config.string("$path.name", id).trim(),
                    description = config.stringList("$path.description").map(String::trim).filter(String::isNotEmpty),
                    iconMaterial = config.string("$path.item", "PAPER").trim().uppercase(Locale.ROOT),
                    price = config.doubleOrNull("$path.price")
                        ?: throw IllegalArgumentException("Mount ability '$id' price is required"),
                    effect = effect,
                    speedMultiplier = config.double("$path.speed-multiplier", 1.0),
                    // Keep historical configs on the original vault currency; token pricing is explicit in catalogs.
                    currency = config.string("$path.currency", "vault").trim(),
                )
        }

    private fun abilities(path: String, availableUpgrades: Map<String, MountAbilityUpgradeDefinition>): MountAbilities {
        val abilityIds = config.keys(path)
        val allowedKeys = setOf("high-jump", "passive", "upgrades")
        require(abilityIds.all { it in allowedKeys }) {
            "Unknown mount abilities: ${abilityIds - allowedKeys}"
        }
        val upgrades = config.stringList("$path.upgrades").map { rawId ->
            val id = rawId.trim().lowercase(Locale.ROOT)
            availableUpgrades[id] ?: throw IllegalArgumentException("Unknown mount ability upgrade '$rawId'")
        }
        val passives = passiveAbilities("$path.passive")
        val highJumpPath = "$path.high-jump"
        if (config.keys(highJumpPath).isEmpty()) return MountAbilities(upgrades = upgrades, passives = passives)
        val displayName = config.stringOrNull("$highJumpPath.name")?.trim().orEmpty()
        val multiplier = config.doubleOrNull("$highJumpPath.multiplier")
            ?: throw IllegalArgumentException("Mount high-jump multiplier is required")
        return MountAbilities(
            MountHighJumpAbility(
                displayName,
                multiplier,
                config.stringList("$highJumpPath.description").map(String::trim).filter(String::isNotEmpty),
            ),
            upgrades,
            passives,
        )
    }

    private fun passiveAbilities(path: String): List<MountPassiveAbilityDefinition> =
        config.keys(path).map { rawId ->
            val id = rawId.lowercase(Locale.ROOT)
            require(id == rawId && MountDefinition.validId(id)) { "Mount passive ability id '$rawId' must be normalized" }
            val passivePath = "$path.$id"
            val allowedKeys = setOf("name", "effect", "amplifier", "description")
            val unknown = config.keys(passivePath) - allowedKeys
            require(unknown.isEmpty()) { "Mount passive ability '$id' has unknown fields: ${unknown.sorted()}" }
            val rawEffect = config.string("$passivePath.effect", id).trim().uppercase(Locale.ROOT).replace('-', '_')
            MountPassiveAbilityDefinition(
                id = id,
                displayName = config.string("$passivePath.name", id).trim(),
                effect = runCatching { MountAbilityEffect.valueOf(rawEffect) }
                    .getOrElse { throw IllegalArgumentException("Mount passive ability '$id' has invalid effect '$rawEffect'") },
                amplifier = config.integer("$passivePath.amplifier", 0),
                description = config.stringList("$passivePath.description").map(String::trim).filter(String::isNotEmpty),
            )
        }

    private fun trail(path: String): MountTrailDefinition? {
        val particle = config.stringOrNull("$path.particle")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return MountTrailDefinition(
            particle = particle.uppercase(Locale.ROOT),
            displayName = config.string("$path.name", localizedTrailName(particle)).trim(),
            pattern =
                runCatching {
                    MountTrailPattern.valueOf(config.string("$path.pattern", "scatter").trim().uppercase(Locale.ROOT).replace('-', '_'))
                }.getOrElse { throw IllegalArgumentException("Mount trail '$path' has an invalid pattern") },
            intervalTicks = config.integer("$path.interval-ticks", 4),
            count = config.integer("$path.count", 1),
            backOffset = config.double("$path.back-offset", 0.2),
            heightRatio = config.double("$path.height-ratio", 0.45),
            spread = config.double("$path.spread", 0.12),
            speed = config.double("$path.speed", 0.0),
        )
    }

    private fun localizedTrailName(particle: String): String =
        when (particle.trim().uppercase(Locale.ROOT)) {
            "END_ROD" -> "Звёздный след"
            "SOUL" -> "След душ"
            "FLAME" -> "Огненный след"
            "SOUL_FIRE_FLAME" -> "Пламя душ"
            "CLOUD" -> "Облачный след"
            "NAUTILUS" -> "Океанический след"
            else -> particle.trim().lowercase(Locale.ROOT).replace('_', ' ')
        }

    private fun normalizedAppearanceValue(raw: String?): String? =
        raw?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)

    private fun intList(path: String, fallback: List<Int>): List<Int> =
        config.stringList(path, fallback.map(Int::toString)).map { raw ->
            raw.trim().toIntOrNull() ?: throw IllegalArgumentException("Mount '$path' contains invalid integer '$raw'")
        }

    private fun decimalHundredthsList(path: String, fallback: List<String>): List<Int> =
        config.stringList(path, fallback).map { raw ->
            val value = raw.trim().toBigDecimalOrNull()
                ?: throw IllegalArgumentException("Mount '$path' contains invalid decimal '$raw'")
            runCatching { value.movePointRight(2).intValueExact() }
                .getOrElse { throw IllegalArgumentException("Mount '$path' value '$raw' must use at most two decimal places") }
        }

    private fun requiredDouble(value: Any?, label: String): Double =
        nullableDouble(value, label) ?: throw IllegalArgumentException("$label is required")

    private fun optionalDouble(value: Any?, fallback: Double, label: String): Double =
        if (value == null) fallback else requiredDouble(value, label)

    private fun nullableDouble(value: Any?, label: String): Double? {
        if (value == null || value.toString().equals("null", ignoreCase = true)) return null
        return value.toString().toDoubleOrNull() ?: throw IllegalArgumentException("$label is not a number")
    }

    private fun strictMovement(raw: String, mountId: String): MountMovement =
        runCatching { MountMovement.valueOf(raw.trim().uppercase(Locale.ROOT)) }
            .getOrElse { throw IllegalArgumentException("Mount '$mountId' has invalid type '$raw'") }

    private fun strictControl(raw: String, mountId: String): MountControl =
        runCatching { MountControl.valueOf(raw.trim().replace('-', '_').uppercase(Locale.ROOT)) }
            .getOrElse { throw IllegalArgumentException("Mount '$mountId' has invalid control '$raw'") }

    private fun strictRarity(raw: String, mountId: String): MountRarity =
        runCatching { MountRarity.valueOf(raw.trim().uppercase(Locale.ROOT)) }
            .getOrElse { throw IllegalArgumentException("Mount '$mountId' has invalid rarity '$raw'") }

    companion object {
        fun load(dataPath: Path, nativeValidation: Boolean = true): MountModuleConfig {
            val source = ConfigManager.ofModule(dataPath, "mounts.yml")
            source.mergeMissingFromBundled("modules/mounts.yml")
            return MountModuleConfig(source).validated(nativeValidation)
        }
    }
}
