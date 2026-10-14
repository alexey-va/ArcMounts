package ru.arc.mounts

import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val MOUNT_CURRENCY_PATTERN = Regex("[A-Za-z0-9_-]{1,16}")

internal const val MIN_MOUNT_APPEARANCE_SCALE = 0.0625
internal const val MAX_MOUNT_APPEARANCE_SCALE = 16.0
private const val MIN_MOUNT_SIZE_MULTIPLIER = 0.1
private const val MAX_MOUNT_SIZE_MULTIPLIER = 10.0

enum class MountMovement(val displayName: String) {
    WALKING("Пеший"),
    FLYING("Летающий"),
    SWIMMING("Водный"),
}

enum class MountControl {
    VEHICLE,
    PLAYER_FLIGHT,
    NATIVE_FLIGHT,
}

enum class MountRarity(val displayName: String, val color: String) {
    COMMON("Обычный", "<white>"),
    UNCOMMON("Необычный", "<green>"),
    RARE("Редкий", "<aqua>"),
    EPIC("Эпический", "<light_purple>"),
    LEGENDARY("Легендарный", "<gold>"),
}

data class MountLevelDefinition(
    val speed: Double,
    val price: Double?,
    val handlingMultiplier: Double = 1.0,
    val sprintMultiplier: Double = 1.0,
    val scaleMultiplier: Double = 1.0,
) {
    init {
        require(speed.isFinite() && speed > 0.0) { "Mount level speed must be positive and finite" }
        require(price == null || price.isFinite() && price > 0.0) { "Mount level price must be positive and finite" }
        require(handlingMultiplier.isFinite() && handlingMultiplier in 0.5..2.0) {
            "Mount level handling multiplier must be between 0.5 and 2.0"
        }
        require(sprintMultiplier.isFinite() && sprintMultiplier in 1.0..2.0) {
            "Mount level sprint multiplier must be between 1.0 and 2.0"
        }
        require(scaleMultiplier.isFinite() && scaleMultiplier in 0.35..4.0) {
            "Mount level scale multiplier must be between 0.35 and 4.0"
        }
    }
}

data class MountTuningDefinition(
    val speedPercentages: List<Int>,
    val walkingStepHeightsHundredths: List<Int>,
    val walkingMaxStepHeightByLevelHundredths: List<Int>,
) {
    init {
        require(speedPercentages.isNotEmpty()) { "Mount tuning speed percentages cannot be empty" }
        require(speedPercentages == speedPercentages.distinct().sorted()) {
            "Mount tuning speed percentages must be unique and sorted"
        }
        require(speedPercentages.all { it in 25..100 } && 100 in speedPercentages) {
            "Mount tuning speed percentages must be between 25 and 100 and include 100"
        }
        require(speedPercentages.size <= 5) { "Mount tuning supports at most five speed options" }
        require(walkingStepHeightsHundredths.isNotEmpty()) { "Mount tuning step heights cannot be empty" }
        require(walkingStepHeightsHundredths == walkingStepHeightsHundredths.distinct().sorted()) {
            "Mount tuning step heights must be unique and sorted"
        }
        require(walkingStepHeightsHundredths.all { it in 60..400 }) {
            "Mount tuning step heights must be between 0.60 and 4.00 blocks"
        }
        require(walkingStepHeightsHundredths.size <= 5) { "Mount tuning supports at most five step-height options" }
        require(walkingMaxStepHeightByLevelHundredths.isNotEmpty()) {
            "Mount tuning step-height level ceilings cannot be empty"
        }
        require(
            walkingMaxStepHeightByLevelHundredths ==
                walkingMaxStepHeightByLevelHundredths.sorted(),
        ) { "Mount tuning step-height level ceilings must be non-decreasing" }
        require(walkingMaxStepHeightByLevelHundredths.all { it in walkingStepHeightsHundredths }) {
            "Every mount tuning step-height level ceiling must be a configured step height"
        }
        require(walkingMaxStepHeightByLevelHundredths.last() == walkingStepHeightsHundredths.last()) {
            "The final mount tuning level must unlock the highest configured step height"
        }
    }

    fun speedPercentage(selected: Int?): Int = resolveSelection(selected, speedPercentages)

    fun speed(baseSpeed: Double, selected: Int?): Double = baseSpeed * speedPercentage(selected) / 100.0

    fun maximumStepHeightHundredths(level: Int): Int =
        walkingMaxStepHeightByLevelHundredths[(level.coerceAtLeast(1) - 1).coerceAtMost(walkingMaxStepHeightByLevelHundredths.lastIndex)]

    fun availableStepHeightsHundredths(level: Int): List<Int> =
        walkingStepHeightsHundredths.filter { it <= maximumStepHeightHundredths(level) }

    fun stepHeightHundredths(level: Int, selected: Int?): Int =
        resolveSelection(selected, availableStepHeightsHundredths(level))

    fun stepHeight(level: Int, selected: Int?): Double = stepHeightHundredths(level, selected) / 100.0

    private fun resolveSelection(selected: Int?, available: List<Int>): Int {
        if (selected == null) return available.last()
        return available.lastOrNull { it <= selected } ?: available.first()
    }
}

data class MountSizeOptionDefinition(
    val id: String,
    val displayName: String,
    val multiplier: Double,
    val minimumLevel: Int = 1,
    val grantOnly: Boolean = false,
    val price: Double? = null,
    val currency: String = "tokens",
) {
    init {
        require(MountDefinition.validId(id)) { "Invalid mount size option id: $id" }
        require(displayName.isNotBlank() && displayName.length <= 48) { "Mount size option '$id' name is invalid" }
        require(multiplier.isFinite() && multiplier in MIN_MOUNT_SIZE_MULTIPLIER..MAX_MOUNT_SIZE_MULTIPLIER) {
            "Mount size option '$id' multiplier must be between $MIN_MOUNT_SIZE_MULTIPLIER and $MAX_MOUNT_SIZE_MULTIPLIER"
        }
        require(minimumLevel in 1..16) { "Mount size option '$id' minimum level must be between 1 and 16" }
        require(price == null || price.isFinite() && price > 0.0) { "Mount size option '$id' price must be positive and finite" }
        require(MOUNT_CURRENCY_PATTERN.matches(currency)) { "Mount size option '$id' currency is invalid" }
        require(price == null || grantOnly) { "Mount size option '$id' must be grant-only to have a purchase price" }
    }
}

enum class MountEquipmentSlot(val configKey: String) {
    HEAD("head"),
    CHEST("chest"),
    LEGS("legs"),
    FEET("feet"),
    MAIN_HAND("main-hand"),
    OFF_HAND("off-hand"),
    BODY("body"),
    SADDLE("saddle"),
}

data class MountAppearance(
    val baby: Boolean = false,
    val scale: Double = 1.0,
    val variant: String? = null,
    val secondaryVariant: String? = null,
    val equipment: Map<MountEquipmentSlot, String> = emptyMap(),
) {
    init {
        require(scale.isFinite() && scale in MIN_MOUNT_APPEARANCE_SCALE..MAX_MOUNT_APPEARANCE_SCALE) {
            "Mount appearance scale must be between $MIN_MOUNT_APPEARANCE_SCALE and $MAX_MOUNT_APPEARANCE_SCALE"
        }
        require(variant == null || APPEARANCE_VALUE_PATTERN.matches(variant)) { "Invalid mount appearance variant: $variant" }
        require(secondaryVariant == null || APPEARANCE_VALUE_PATTERN.matches(secondaryVariant)) {
            "Invalid mount appearance secondary variant: $secondaryVariant"
        }
        require(equipment.values.all { MATERIAL_PATTERN.matches(it) }) { "Invalid mount appearance equipment material" }
    }

    companion object {
        private val APPEARANCE_VALUE_PATTERN = Regex("[A-Z0-9_]{1,48}")
        private val MATERIAL_PATTERN = Regex("[A-Z0-9_]{2,64}")
    }

    fun scaledBy(multiplier: Double): MountAppearance {
        require(multiplier.isFinite() && multiplier > 0.0) { "Mount appearance scale multiplier must be positive and finite" }
        return copy(scale = scale * multiplier)
    }
}

enum class MountTrailPattern {
    SCATTER,
    SPIRAL,
    RING,
    DOUBLE_HELIX,
    ORBIT,
    PULSE,
    WAVE,
    RIBBON,
    COMET,
    HEART,
}

data class MountTrailDefinition(
    val particle: String,
    val displayName: String,
    val pattern: MountTrailPattern = MountTrailPattern.SCATTER,
    val intervalTicks: Int = 4,
    val count: Int = 1,
    val backOffset: Double = 0.2,
    val heightRatio: Double = 0.45,
    val spread: Double = 0.12,
    val speed: Double = 0.0,
) {
    init {
        require(PARTICLE_PATTERN.matches(particle)) { "Invalid mount trail particle: $particle" }
        require(displayName.isNotBlank() && displayName.length <= 64) { "Mount trail display name is invalid" }
        require(intervalTicks in 2..40) { "Mount trail interval must be between 2 and 40 ticks" }
        require(count in 1..8) { "Mount trail count must be between 1 and 8" }
        require(backOffset.isFinite() && backOffset in 0.0..2.0) { "Mount trail back offset must be between 0 and 2" }
        require(heightRatio.isFinite() && heightRatio in 0.0..1.0) { "Mount trail height ratio must be between 0 and 1" }
        require(spread.isFinite() && spread in 0.0..1.0) { "Mount trail spread must be between 0 and 1" }
        require(speed.isFinite() && speed in 0.0..1.0) { "Mount trail speed must be between 0 and 1" }
    }

    companion object {
        private val PARTICLE_PATTERN = Regex("[A-Z0-9_]{2,64}")
    }
}

enum class MountBehaviorTrigger {
    SPRINT_FORWARD_PRESS,
}

sealed interface MountBehaviorDefinition {
    val id: String
    val displayName: String
    val description: List<String>
}

data class MountRamBehavior(
    override val id: String,
    override val displayName: String,
    override val description: List<String> =
        listOf(
            "Нажмите спринт при движении вперёд.",
            "Первый враждебный моб на пути",
            "получит урон при столкновении.",
        ),
    val trigger: MountBehaviorTrigger = MountBehaviorTrigger.SPRINT_FORWARD_PRESS,
    val cooldown: Duration = Duration.ofSeconds(4),
    val requestWindow: Duration = Duration.ofMillis(1_200),
    val activeWindowTicks: Int = 8,
    val minimumSpeedFraction: Double = 0.65,
    val reach: Double = 1.25,
    val lateralPadding: Double = 0.35,
    val damage: Double = 4.0,
) : MountBehaviorDefinition {
    init {
        require(MountDefinition.validId(id)) { "Invalid mount behavior id: $id" }
        require(displayName.isNotBlank() && displayName.length <= 64) { "Mount ram behavior '$id' name is invalid" }
        require(description.isNotEmpty() && description.size <= 4) { "Mount ram behavior '$id' description must contain 1..4 rows" }
        require(description.all { it.isNotBlank() && it.length <= 64 }) {
            "Mount ram behavior '$id' description rows must contain 1..64 characters"
        }
        require(cooldown in Duration.ofSeconds(2)..Duration.ofSeconds(30)) {
            "Mount ram behavior '$id' cooldown must be between 2s and 30s"
        }
        require(requestWindow in Duration.ofMillis(100)..Duration.ofSeconds(2)) {
            "Mount ram behavior '$id' request window must be between 100ms and 2s"
        }
        require(activeWindowTicks in 1..12) { "Mount ram behavior '$id' active window must be between 1 and 12 ticks" }
        require(minimumSpeedFraction.isFinite() && minimumSpeedFraction in 0.4..1.0) {
            "Mount ram behavior '$id' minimum speed fraction must be between 0.4 and 1"
        }
        require(reach.isFinite() && reach in 0.25..2.0) { "Mount ram behavior '$id' reach must be between 0.25 and 2" }
        require(lateralPadding.isFinite() && lateralPadding in 0.0..1.0) {
            "Mount ram behavior '$id' lateral padding must be between 0 and 1"
        }
        require(damage.isFinite() && damage in 0.5..6.0) { "Mount ram behavior '$id' damage must be between 0.5 and 6" }
    }
}

data class MountTrampleBehavior(
    override val id: String,
    override val displayName: String,
    override val description: List<String> =
        listOf(
            "Враждебные мобы под корпусом",
            "получают урон во время движения.",
        ),
    val cooldown: Duration = Duration.ofSeconds(1),
    val minimumSpeedFraction: Double = 0.2,
    val horizontalPadding: Double = 0.2,
    val downwardReach: Double = 0.6,
    val damage: Double = 2.0,
    val maximumTargets: Int = 4,
) : MountBehaviorDefinition {
    init {
        require(MountDefinition.validId(id)) { "Invalid mount trample behavior id: $id" }
        require(displayName.isNotBlank() && displayName.length <= 64) { "Mount trample behavior '$id' name is invalid" }
        require(description.isNotEmpty() && description.size <= 4) {
            "Mount trample behavior '$id' description must contain 1..4 rows"
        }
        require(description.all { it.isNotBlank() && it.length <= 64 }) {
            "Mount trample behavior '$id' description rows must contain 1..64 characters"
        }
        require(cooldown in Duration.ofMillis(500)..Duration.ofSeconds(5)) {
            "Mount trample behavior '$id' cooldown must be between 500ms and 5s"
        }
        require(minimumSpeedFraction.isFinite() && minimumSpeedFraction in 0.1..1.0) {
            "Mount trample behavior '$id' minimum speed fraction must be between 0.1 and 1"
        }
        require(horizontalPadding.isFinite() && horizontalPadding in 0.0..1.0) {
            "Mount trample behavior '$id' horizontal padding must be between 0 and 1"
        }
        require(downwardReach.isFinite() && downwardReach in 0.1..2.0) {
            "Mount trample behavior '$id' downward reach must be between 0.1 and 2"
        }
        require(damage.isFinite() && damage in 0.5..6.0) { "Mount trample behavior '$id' damage must be between 0.5 and 6" }
        require(maximumTargets in 1..8) { "Mount trample behavior '$id' maximum targets must be between 1 and 8" }
    }
}

data class MountHighJumpAbility(
    val displayName: String,
    val multiplier: Double,
    val description: List<String> = emptyList(),
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 64) { "Mount high-jump display name is invalid" }
        require(multiplier.isFinite() && multiplier in 1.05..3.0) {
            "Mount high-jump multiplier must be between 1.05 and 3.0"
        }
        require(description.size <= 3 && description.all { it.isNotBlank() && it.length <= 64 }) {
            "Mount high-jump description is invalid"
        }
    }
}

enum class MountAbilityEffect {
    WATER_BREATHING,
    NIGHT_VISION,
    FIRE_RESISTANCE,
    DOLPHINS_GRACE,
    RESISTANCE,
    REGENERATION,
    SPEED,
    SLOW_FALLING,
    STRENGTH,
    HASTE,
    LUCK,
}

data class MountPassiveAbilityDefinition(
    val id: String,
    val displayName: String,
    val effect: MountAbilityEffect,
    val amplifier: Int = 0,
    val description: List<String> = emptyList(),
) {
    init {
        require(MountDefinition.validId(id)) { "Invalid mount passive ability id: $id" }
        require(displayName.isNotBlank() && displayName.length <= 64) { "Mount passive ability '$id' name is invalid" }
        require(amplifier in 0..2) { "Mount passive ability '$id' amplifier must be between 0 and 2" }
        require(description.size <= 3 && description.all { it.isNotBlank() && it.length <= 64 }) {
            "Mount passive ability '$id' description is invalid"
        }
    }
}

data class MountAbilityUpgradeDefinition(
    val id: String,
    val displayName: String,
    val description: List<String>,
    val iconMaterial: String,
    val price: Double,
    val effect: MountAbilityEffect,
    val speedMultiplier: Double = 1.0,
    val currency: String = "vault",
) {
    init {
        require(MountDefinition.validId(id)) { "Invalid mount ability id: $id" }
        require(displayName.isNotBlank() && displayName.length <= 64) { "Mount ability '$id' name is invalid" }
        require(description.size <= 4 && description.all { it.isNotBlank() && it.length <= 120 }) {
            "Mount ability '$id' description is invalid"
        }
        require(iconMaterial.isNotBlank()) { "Mount ability '$id' icon material is blank" }
        require(price.isFinite() && price > 0.0) { "Mount ability '$id' price must be positive and finite" }
        require(MOUNT_CURRENCY_PATTERN.matches(currency)) { "Mount ability '$id' currency is invalid" }
        require(speedMultiplier.isFinite() && speedMultiplier in 1.0..1.5) {
            "Mount ability '$id' speed multiplier must be between 1.0 and 1.5"
        }
    }
}

data class MountAbilities(
    val highJump: MountHighJumpAbility? = null,
    val upgrades: List<MountAbilityUpgradeDefinition> = emptyList(),
    val passives: List<MountPassiveAbilityDefinition> = emptyList(),
) {
    val displayNames: List<String>
        get() =
            listOfNotNull(highJump?.displayName) +
                passives.map(MountPassiveAbilityDefinition::displayName) +
                upgrades.map(MountAbilityUpgradeDefinition::displayName)
}

data class MountSkinDefinition(
    val id: String,
    val displayName: String,
    val iconMaterial: String,
    val price: Double?,
    val appearance: MountAppearance,
    val trail: MountTrailDefinition? = null,
    val currency: String = "vault",
) {
    init {
        require(MountDefinition.validId(id)) { "Invalid mount skin id: $id" }
        require(displayName.isNotBlank()) { "Mount skin '$id' display name is blank" }
        require(iconMaterial.isNotBlank()) { "Mount skin '$id' icon material is blank" }
        require(price == null || price.isFinite() && price > 0.0) { "Mount skin '$id' price must be positive and finite" }
        require(MOUNT_CURRENCY_PATTERN.matches(currency)) { "Mount skin '$id' currency is invalid" }
    }
}

data class MountDefinition(
    val id: String,
    val movement: MountMovement,
    val entityType: String,
    val passengerSeats: Int = 0,
    val iconMaterial: String,
    val displayName: String,
    val description: List<String>,
    val acquisition: String,
    val rarity: MountRarity,
    val levels: List<MountLevelDefinition>,
    val glowPrice: Double?,
    val abilities: MountAbilities = MountAbilities(),
    val appearance: MountAppearance = MountAppearance(),
    val skins: List<MountSkinDefinition> = emptyList(),
    val sizeOptions: List<MountSizeOptionDefinition> = emptyList(),
    val behaviors: List<MountBehaviorDefinition> = emptyList(),
    val motion: MountMotionOverride = MountMotionOverride(),
    val currency: String = "vault",
    val control: MountControl = MountControl.VEHICLE,
    val visualEntityType: String? = null,
    val glowCurrency: String = currency,
) {
    init {
        require(validId(id)) { "Invalid mount id: $id" }
        require(entityType.isNotBlank()) { "Mount '$id' entity type is blank" }
        require(control != MountControl.PLAYER_FLIGHT || (movement == MountMovement.FLYING && passengerSeats == 0)) {
            "Mount '$id' player-flight control requires a flying mount without passenger seats"
        }
        if (control == MountControl.NATIVE_FLIGHT) {
            require(movement == MountMovement.FLYING && entityType == "HAPPY_GHAST") {
                "Mount '$id' native-flight control requires a flying Happy Ghast"
            }
            require(visualEntityType != null || (listOf(appearance) + skins.map(MountSkinDefinition::appearance)).all {
                !it.baby && it.equipment[MountEquipmentSlot.BODY]?.endsWith("_HARNESS") == true
            }) { "Mount '$id' native-flight appearances must be adult and wear a harness" }
        }
        require(visualEntityType == null || control == MountControl.NATIVE_FLIGHT) {
            "Mount '$id' visual-entity requires native-flight control"
        }
        require(visualEntityType == null || passengerSeats <= 2) {
            "Mount '$id' visual body reserves one Happy Ghast passenger seat"
        }
        require(passengerSeats in 0..passengerSeatLimit(entityType)) {
            "Mount '$id' passenger-seats must be between 0 and ${passengerSeatLimit(entityType)} for $entityType"
        }
        require(iconMaterial.isNotBlank()) { "Mount '$id' icon material is blank" }
        require(displayName.isNotBlank()) { "Mount '$id' display name is blank" }
        require(displayName.length <= 64) { "Mount '$id' display name is too long" }
        require(description.size <= 6 && description.all { it.length <= 120 }) { "Mount '$id' description is invalid" }
        require(acquisition.isNotBlank() && acquisition.length <= 120) { "Mount '$id' acquisition text is invalid" }
        require(MOUNT_CURRENCY_PATTERN.matches(currency)) { "Mount '$id' currency is invalid" }
        require(MOUNT_CURRENCY_PATTERN.matches(glowCurrency)) { "Mount '$id' glow currency is invalid" }
        require(levels.isNotEmpty()) { "Mount '$id' must have at least one level" }
        require(levels.size <= MAX_LEVELS) { "Mount '$id' has more than $MAX_LEVELS levels" }
        require(glowPrice == null || glowPrice.isFinite() && glowPrice > 0.0) {
            "Mount '$id' glow price must be positive and finite"
        }
        require(abilities.highJump == null || movement == MountMovement.WALKING) {
            "Mount '$id' high-jump ability requires walking movement"
        }
        require(abilities.upgrades.map(MountAbilityUpgradeDefinition::id).toSet().size == abilities.upgrades.size) {
            "Mount '$id' ability upgrade ids must be unique"
        }
        require(abilities.passives.size <= 3) { "Mount '$id' supports at most three passive abilities" }
        require(abilities.passives.map(MountPassiveAbilityDefinition::id).toSet().size == abilities.passives.size) {
            "Mount '$id' passive ability ids must be unique"
        }
        require(activeAbilitySpeedMultiplier(abilities.upgrades) <= 1.5) {
            "Mount '$id' combined ability speed multiplier exceeds 1.5"
        }
        require(
            movement == MountMovement.SWIMMING ||
                (abilities.upgrades.map(MountAbilityUpgradeDefinition::effect) +
                    abilities.passives.map(MountPassiveAbilityDefinition::effect)).none {
                    it == MountAbilityEffect.WATER_BREATHING || it == MountAbilityEffect.DOLPHINS_GRACE
                },
        ) { "Mount '$id' aquatic abilities require swimming movement" }
        require(skins.size <= MAX_SKINS) { "Mount '$id' has more than $MAX_SKINS skins" }
        require(skins.map(MountSkinDefinition::id).toSet().size == skins.size) { "Mount '$id' skin ids must be unique" }
        require(skins.none { it.id == DEFAULT_SKIN_ID }) { "Mount '$id' cannot redefine the default skin" }
        require(sizeOptions.size <= MAX_SIZE_OPTIONS) { "Mount '$id' has more than $MAX_SIZE_OPTIONS size options" }
        require(sizeOptions.map(MountSizeOptionDefinition::id).toSet().size == sizeOptions.size) {
            "Mount '$id' size option ids must be unique"
        }
        require(sizeOptions.isEmpty() || sizeOptions.count { it.multiplier == 1.0 } == 1) {
            "Mount '$id' size options must define exactly one standard multiplier"
        }
        require(defaultSizeOption == null || defaultSizeOption?.minimumLevel == 1) {
            "Mount '$id' standard size option must be available at level 1"
        }
        require(sizeOptions.all { it.minimumLevel <= maxLevel }) { "Mount '$id' size option requires an unavailable level" }
        require(sizeOptions.filter { it.multiplier == 1.0 }.all { !it.grantOnly && it.price == null }) {
            "Mount '$id' standard size option must remain free and level-unlocked"
        }
        require(behaviors.map(MountBehaviorDefinition::id).toSet().size == behaviors.size) {
            "Mount '$id' behavior ids must be unique"
        }
        require(behaviors.filterIsInstance<MountRamBehavior>().size <= 1) { "Mount '$id' supports at most one ram behavior" }
        require(behaviors.filterIsInstance<MountTrampleBehavior>().size <= 1) { "Mount '$id' supports at most one trample behavior" }
        val appearances = listOf(appearance) + skins.map(MountSkinDefinition::appearance)
        val sizeMultipliers = sizeOptions.map(MountSizeOptionDefinition::multiplier).ifEmpty { listOf(1.0) }
        require(
            levels.all { level ->
                appearances.all { base ->
                    sizeMultipliers.all { size ->
                        base.scale * level.scaleMultiplier * size in MIN_MOUNT_APPEARANCE_SCALE..MAX_MOUNT_APPEARANCE_SCALE
                    }
                }
            },
        ) {
            "Mount '$id' level scale produces an appearance outside $MIN_MOUNT_APPEARANCE_SCALE..$MAX_MOUNT_APPEARANCE_SCALE"
        }
    }

    val maxLevel: Int get() = levels.size

    fun level(level: Int): MountLevelDefinition = levels[(level.coerceIn(1, maxLevel)) - 1]

    fun speed(level: Int): Double = level(level).speed

    fun price(level: Int): Double? = levels.getOrNull(level - 1)?.price

    fun effectiveAppearance(scaleMultiplier: Double, skin: MountSkinDefinition?): MountAppearance =
        (skin?.appearance ?: appearance).scaledBy(scaleMultiplier)

    val defaultSizeOption: MountSizeOptionDefinition? get() = sizeOptions.firstOrNull { it.multiplier == 1.0 }

    fun sizeOption(id: String?): MountSizeOptionDefinition? = sizeOptions.firstOrNull { it.id == id } ?: defaultSizeOption

    fun availableSizeOptions(level: Int, ownedSizeIds: Set<String> = emptySet()): List<MountSizeOptionDefinition> =
        sizeOptions.filter { it.minimumLevel <= level && (!it.grantOnly || it.id in ownedSizeIds) }

    fun effectiveSizeOption(id: String?, level: Int, ownedSizeIds: Set<String> = emptySet()): MountSizeOptionDefinition? {
        val available = availableSizeOptions(level, ownedSizeIds)
        return available.firstOrNull { it.id == id }
            ?: available.firstOrNull { it.multiplier == 1.0 }
            ?: available.firstOrNull()
    }

    fun effectiveRiderViewAutoHide(profile: MountProfile): Boolean {
        profile.riderViewAutoHide?.let { return it }
        val size = effectiveSizeOption(profile.selectedSizeId, profile.level, profile.ownedSizeIds)?.multiplier ?: 1.0
        val scale = level(profile.level).scaleMultiplier * size
        return effectiveAppearance(scale, skin(profile.activeSkinId)).scale >= LARGE_MOUNT_APPEARANCE_SCALE
    }

    fun levelPermission(level: Int): String = "arc.mounts.$id.$level"

    val speedTuningPermissionPrefix: String get() = "arc.mounts.$id.tuning.speed."
    val stepHeightTuningPermissionPrefix: String get() = "arc.mounts.$id.tuning.step-height."
    val sizeTuningPermissionPrefix: String get() = "arc.mounts.$id.tuning.size."
    val sizeOwnershipPermissionPrefix: String get() = "arc.mounts.$id.size."
    val riderViewTuningPermissionPrefix: String get() = "arc.mounts.$id.tuning.rider-view."

    fun speedTuningPermission(percentage: Int): String = "$speedTuningPermissionPrefix$percentage"

    fun stepHeightTuningPermission(hundredths: Int): String = "$stepHeightTuningPermissionPrefix$hundredths"

    fun sizeTuningPermission(sizeId: String): String = "$sizeTuningPermissionPrefix$sizeId"

    fun sizeOwnershipPermission(sizeId: String): String = "$sizeOwnershipPermissionPrefix$sizeId"

    fun riderViewTuningPermission(autoHide: Boolean): String =
        "$riderViewTuningPermissionPrefix${if (autoHide) "auto-hide" else "always-visible"}"

    val glowPermission: String get() = "arc.mounts.$id.glow"
    val glowDisabledPermission: String get() = "arc.mounts.$id.glow.disabled"

    fun skin(id: String?): MountSkinDefinition? = skins.firstOrNull { it.id == id }

    fun skinPermission(skinId: String): String = "arc.mounts.$id.skin.$skinId"

    fun activeSkinPermission(skinId: String): String = "arc.mounts.$id.skin.active.$skinId"

    fun ability(id: String): MountAbilityUpgradeDefinition? = abilities.upgrades.firstOrNull { it.id == id }

    fun abilityPermission(abilityId: String): String = "arc.mounts.$id.ability.$abilityId"

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{1,31}")
        private const val MAX_LEVELS = 16
        private const val MAX_SKINS = 16
        private const val MAX_SIZE_OPTIONS = 5
        const val DEFAULT_SKIN_ID = "default"

        private fun passengerSeatLimit(entityType: String): Int =
            when (entityType.uppercase(Locale.ROOT)) {
                "CAMEL", "CAMEL_HUSK" -> 1
                "HAPPY_GHAST" -> 3
                else -> 2
            }

        fun validId(value: String): Boolean = ID_PATTERN.matches(value)
    }
}

class MountCatalog(definitions: Collection<MountDefinition>) {
    val all: List<MountDefinition> = definitions.toList()
    private val byId = all.associateBy(MountDefinition::id)

    init {
        require(all.isNotEmpty()) { "Mount catalog cannot be empty" }
        require(byId.size == all.size) { "Mount ids must be unique" }
    }

    operator fun get(id: String): MountDefinition? = byId[id.lowercase(Locale.ROOT)]
}

internal const val MOUNT_FAVORITE_PERMISSION_PREFIX = "arc.mounts.favorite."

internal fun favoriteMountPermission(mountId: String): String {
    require(MountDefinition.validId(mountId)) { "Invalid favorite mount id: $mountId" }
    return "$MOUNT_FAVORITE_PERMISSION_PREFIX$mountId"
}

data class MountPermissionSubject(
    val uniqueId: UUID,
    val name: String,
    val hasPermission: (String) -> Boolean,
)

data class MountProfile(
    val level: Int,
    val glowOwned: Boolean,
    val glowDisabled: Boolean,
    val ownedSkinIds: Set<String> = emptySet(),
    val activeSkinId: String = MountDefinition.DEFAULT_SKIN_ID,
    val ownedAbilityIds: Set<String> = emptySet(),
    val ownedSizeIds: Set<String> = emptySet(),
    val selectedSpeedPercentage: Int? = null,
    val selectedStepHeightHundredths: Int? = null,
    val selectedSizeId: String? = null,
    val riderViewAutoHide: Boolean? = null,
) {
    val unlocked: Boolean get() = level > 0
    val glowEnabled: Boolean get() = glowOwned && !glowDisabled

    fun ownsSkin(skinId: String): Boolean = skinId == MountDefinition.DEFAULT_SKIN_ID || skinId in ownedSkinIds

    fun ownsAbility(abilityId: String): Boolean = abilityId in ownedAbilityIds

    fun ownsSize(option: MountSizeOptionDefinition): Boolean = !option.grantOnly || option.id in ownedSizeIds
}

data class MountRuntimeSettings(
    val speed: Double,
    val walkingStepHeight: Double,
    val handlingMultiplier: Double,
    val sprintMultiplier: Double,
    val scaleMultiplier: Double,
    val skin: MountSkinDefinition?,
    val glow: Boolean,
    val abilityUpgrades: List<MountAbilityUpgradeDefinition>,
    val riderViewAutoHide: Boolean = false,
) {
    init {
        require(speed.isFinite() && speed > 0.0) { "Mount runtime speed must be positive and finite" }
        require(walkingStepHeight.isFinite() && walkingStepHeight in 0.6..4.0) {
            "Walking mount step height must be between 0.60 and 4.00 blocks"
        }
        require(handlingMultiplier.isFinite() && handlingMultiplier in 0.5..2.0) {
            "Mount runtime handling multiplier must be between 0.5 and 2.0"
        }
        require(sprintMultiplier.isFinite() && sprintMultiplier in 1.0..2.0) {
            "Mount runtime sprint multiplier must be between 1.0 and 2.0"
        }
        require(scaleMultiplier.isFinite() && scaleMultiplier in MIN_MOUNT_SIZE_MULTIPLIER..MAX_MOUNT_SIZE_MULTIPLIER) {
            "Mount runtime scale multiplier must be between $MIN_MOUNT_SIZE_MULTIPLIER and $MAX_MOUNT_SIZE_MULTIPLIER"
        }
    }
}

enum class MountSessionUpdateResult {
    APPLIED,
    NO_ACTIVE_SESSION,
    DIFFERENT_MOUNT,
    ENTITY_MISSING,
    UNSAFE_APPEARANCE,
}

interface MountOwnership {
    fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile

    fun favoriteMountId(playerId: UUID): String?

    fun setFavoriteMount(playerId: UUID, mount: MountDefinition): CompletableFuture<Void>

    fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void>

    fun revokeLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void>

    fun grantAll(playerId: UUID, mounts: Collection<MountDefinition>): CompletableFuture<Void> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Bulk mount ownership persistence is unavailable"))

    fun revokeAll(playerId: UUID, mounts: Collection<MountDefinition>): CompletableFuture<Int> =
        CompletableFuture.completedFuture(0)

    fun grantGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void>

    fun revokeGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void>

    fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean): CompletableFuture<Void>

    fun grantSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition): CompletableFuture<Void>

    fun revokeSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition): CompletableFuture<Void>

    fun setActiveSkin(playerId: UUID, mount: MountDefinition, skinId: String): CompletableFuture<Void>

    fun grantAbility(playerId: UUID, mount: MountDefinition, ability: MountAbilityUpgradeDefinition): CompletableFuture<Void>

    fun revokeAbility(playerId: UUID, mount: MountDefinition, ability: MountAbilityUpgradeDefinition): CompletableFuture<Void>

    fun grantSize(playerId: UUID, mount: MountDefinition, size: MountSizeOptionDefinition): CompletableFuture<Void> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Mount size ownership persistence is unavailable"))

    fun revokeSize(playerId: UUID, mount: MountDefinition, size: MountSizeOptionDefinition): CompletableFuture<Void> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Mount size ownership persistence is unavailable"))

    fun setSpeedTuning(playerId: UUID, mount: MountDefinition, percentage: Int): CompletableFuture<Void>

    fun setStepHeightTuning(playerId: UUID, mount: MountDefinition, hundredths: Int): CompletableFuture<Void>

    fun setSizeTuning(playerId: UUID, mount: MountDefinition, sizeId: String): CompletableFuture<Void> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Mount size tuning persistence is unavailable"))

    fun setRiderViewAutoHide(playerId: UUID, mount: MountDefinition, enabled: Boolean): CompletableFuture<Void> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Mount rider-view tuning persistence is unavailable"))

    fun hasDirectPermission(playerId: UUID, permission: String): CompletableFuture<Boolean>

    fun resolveUniqueId(playerName: String): CompletableFuture<UUID?>
}

data class MountInputState(
    val forward: Boolean = false,
    val backward: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
    val jump: Boolean = false,
    val sneak: Boolean = false,
    val sprint: Boolean = false,
) {
    val forwardAxis: Double get() = (if (forward) 1.0 else 0.0) - (if (backward) 1.0 else 0.0)
    val strafeAxis: Double get() = (if (right) 1.0 else 0.0) - (if (left) 1.0 else 0.0)
}

data class MotionVector(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    val horizontalLength: Double get() = sqrt(x * x + z * z)
    val length: Double get() = sqrt(x * x + y * y + z * z)

    fun normalized(): MotionVector {
        val magnitude = length
        return if (magnitude <= EPSILON) ZERO else MotionVector(x / magnitude, y / magnitude, z / magnitude)
    }

    fun normalizedHorizontal(): MotionVector {
        val magnitude = horizontalLength
        return if (magnitude <= EPSILON) ZERO else MotionVector(x / magnitude, 0.0, z / magnitude)
    }

    operator fun plus(other: MotionVector) = MotionVector(x + other.x, y + other.y, z + other.z)
    operator fun times(scale: Double) = MotionVector(x * scale, y * scale, z * scale)

    companion object {
        val ZERO = MotionVector(0.0, 0.0, 0.0)
        private const val EPSILON = 1.0e-9
    }
}

data class MountMotionTiming(
    val accelerationTime: Duration,
    val decelerationTime: Duration,
    val turnTime: Duration,
) {
    init {
        validateMotionDuration(accelerationTime, "acceleration time")
        validateMotionDuration(decelerationTime, "deceleration time")
        validateMotionDuration(turnTime, "turn time")
    }
}

data class MountMotionOverride(
    val accelerationTime: Duration? = null,
    val decelerationTime: Duration? = null,
    val turnTime: Duration? = null,
) {
    init {
        accelerationTime?.let { validateMotionDuration(it, "acceleration time override") }
        decelerationTime?.let { validateMotionDuration(it, "deceleration time override") }
        turnTime?.let { validateMotionDuration(it, "turn time override") }
    }

    fun resolve(defaults: MountMotionTiming): MountMotionTiming =
        MountMotionTiming(
            accelerationTime = accelerationTime ?: defaults.accelerationTime,
            decelerationTime = decelerationTime ?: defaults.decelerationTime,
            turnTime = turnTime ?: defaults.turnTime,
        )
}

data class MountMotionState(
    val direction: MotionVector = MotionVector.ZERO,
    val speed: Double = 0.0,
) {
    init {
        require(speed.isFinite() && speed >= 0.0) { "Mount motion speed must be finite and non-negative" }
    }

    val velocity: MotionVector
        get() = direction.normalized() * speed
}

object MountMotion {
    fun planarDirection(yawDegrees: Float, input: MountInputState): MotionVector {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val forward = MotionVector(-sin(yaw), 0.0, cos(yaw))
        val right = MotionVector(-cos(yaw), 0.0, -sin(yaw))
        return (forward * input.forwardAxis + right * input.strafeAxis).normalizedHorizontal()
    }

    fun advance(
        current: MountMotionState,
        targetVelocity: MotionVector,
        timing: MountMotionTiming,
        handlingMultiplier: Double,
    ): MountMotionState {
        require(handlingMultiplier.isFinite() && handlingMultiplier > 0.0) {
            "Mount handling multiplier must be positive and finite"
        }
        val targetSpeed = targetVelocity.length
        val targetDirection = targetVelocity.normalized()
        if (timing.accelerationTime.isZero && timing.decelerationTime.isZero && timing.turnTime.isZero) {
            return MountMotionState(targetDirection, targetSpeed)
        }
        val currentDirection = current.direction.normalized()
        val reversing =
            current.speed > MOTION_EPSILON &&
                targetSpeed > MOTION_EPSILON &&
                currentDirection.dot(targetDirection) <= REVERSE_DOT_THRESHOLD
        if (reversing) {
            val speedFactor = responseFactor(timing.decelerationTime, handlingMultiplier)
            val nextSpeed = current.speed * (1.0 - speedFactor)
            val reverseThreshold = (targetSpeed * RESPONSE_REMAINDER).coerceIn(MOTION_EPSILON, STOP_SPEED_THRESHOLD)
            return if (nextSpeed <= reverseThreshold) {
                MountMotionState(direction = targetDirection, speed = 0.0)
            } else {
                MountMotionState(direction = currentDirection, speed = nextSpeed)
            }
        }
        val speedDuration = if (targetSpeed > current.speed) timing.accelerationTime else timing.decelerationTime
        val speedFactor = responseFactor(speedDuration, handlingMultiplier)
        val smoothedSpeed = current.speed + (targetSpeed - current.speed) * speedFactor
        val nextSpeed = if (targetSpeed <= MOTION_EPSILON && smoothedSpeed <= STOP_SPEED_THRESHOLD) 0.0 else smoothedSpeed
        val nextDirection =
            if (current.speed <= MOTION_EPSILON || current.direction.length <= MOTION_EPSILON) {
                targetDirection
            } else if (targetSpeed <= MOTION_EPSILON) {
                currentDirection
            } else {
                val turnFactor = responseFactor(timing.turnTime, handlingMultiplier)
                (currentDirection * (1.0 - turnFactor) + targetDirection * turnFactor).normalized()
            }
        return MountMotionState(nextDirection, nextSpeed)
    }

    fun facingYaw(motion: MotionVector, fallback: Float): Float {
        if (abs(motion.x) < 1.0e-6 && abs(motion.z) < 1.0e-6) return fallback
        return Math.toDegrees(kotlin.math.atan2(-motion.x, motion.z)).toFloat()
    }

    fun airborneTarget(
        pitchDegrees: Float,
        input: MountInputState,
        planar: MotionVector,
        maximumSpeed: Double,
        verticalSpeedRatio: Double,
        maximumVerticalSpeed: Double,
        pitchInfluence: Double,
    ): MotionVector {
        require(maximumSpeed >= 0.0 && maximumSpeed.isFinite()) { "Maximum speed must be finite and non-negative" }
        val verticalLimit = min(maximumSpeed * verticalSpeedRatio, maximumVerticalSpeed)
        val manualVertical = (if (input.jump) 1.0 else 0.0) - (if (input.sneak) 1.0 else 0.0)
        val pitchRadians = Math.toRadians(pitchDegrees.toDouble())
        val pitchVertical = -sin(pitchRadians) * input.forwardAxis * maximumSpeed * pitchInfluence
        val vertical = (manualVertical * verticalLimit + pitchVertical).coerceIn(-verticalLimit, verticalLimit)
        val raw = MotionVector(planar.x * maximumSpeed, vertical, planar.z * maximumSpeed)
        return if (raw.length > maximumSpeed && maximumSpeed > 0.0) raw.normalized() * maximumSpeed else raw
    }

    private fun responseFactor(duration: Duration, handlingMultiplier: Double): Double {
        if (duration.isZero) return 1.0
        val ticks = duration.toNanos() / TICK_NANOS.toDouble()
        if (ticks <= 0.0) return 1.0
        return 1.0 - RESPONSE_REMAINDER.pow(handlingMultiplier / ticks)
    }

    private const val TICK_NANOS = 50_000_000L
    private const val RESPONSE_REMAINDER = 0.05
    private const val MOTION_EPSILON = 1.0e-9
    private const val STOP_SPEED_THRESHOLD = 0.01
    private const val REVERSE_DOT_THRESHOLD = -0.5
}

private fun MotionVector.dot(other: MotionVector): Double = x * other.x + y * other.y + z * other.z

private fun validateMotionDuration(duration: Duration, name: String) {
    require(!duration.isNegative && duration <= MAX_MOTION_RESPONSE_TIME) {
        "Mount $name must be between 0s and ${MAX_MOTION_RESPONSE_TIME.seconds}s"
    }
}

private val MAX_MOTION_RESPONSE_TIME: Duration = Duration.ofSeconds(10)

enum class SneakGestureResult {
    NONE,
    PRESSED,
    DOUBLE_PRESSED,
}

class DoubleSneakGesture(private val doublePressWindowMillis: Long) {
    private var previousSneak = false
    private var lastPressAt = Long.MIN_VALUE

    init {
        require(doublePressWindowMillis > 0L) { "Double-sneak window must be positive" }
    }

    fun update(sneaking: Boolean, nowMillis: Long): SneakGestureResult {
        val risingEdge = sneaking && !previousSneak
        previousSneak = sneaking
        if (!risingEdge) return SneakGestureResult.NONE

        val isDouble = lastPressAt != Long.MIN_VALUE && nowMillis - lastPressAt in 0..doublePressWindowMillis
        lastPressAt = if (isDouble) Long.MIN_VALUE else nowMillis
        return if (isDouble) SneakGestureResult.DOUBLE_PRESSED else SneakGestureResult.PRESSED
    }
}

internal fun walkingJumpVelocity(baseVelocity: Double, abilities: MountAbilities): Double {
    require(baseVelocity.isFinite() && baseVelocity > 0.0) { "Walking jump velocity must be positive and finite" }
    return baseVelocity * (abilities.highJump?.multiplier ?: 1.0)
}

internal fun activeAbilitySpeedMultiplier(abilities: Collection<MountAbilityUpgradeDefinition>): Double =
    abilities.fold(1.0) { total, ability -> total * ability.speedMultiplier }

internal const val LARGE_MOUNT_APPEARANCE_SCALE = 2.0
