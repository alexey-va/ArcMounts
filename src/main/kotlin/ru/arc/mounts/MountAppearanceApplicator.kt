package ru.arc.mounts

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Ageable
import org.bukkit.entity.Axolotl
import org.bukkit.entity.Fox
import org.bukkit.entity.Frog
import org.bukkit.entity.Horse
import org.bukkit.entity.Hoglin
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Llama
import org.bukkit.entity.MushroomCow
import org.bukkit.entity.Parrot
import org.bukkit.entity.PiglinAbstract
import org.bukkit.entity.Rabbit
import org.bukkit.entity.Shulker
import org.bukkit.entity.Zombie
import org.bukkit.entity.EntityType
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.Locale

internal object MountAppearanceApplicator {
    fun supportsAge(entityType: EntityType): Boolean =
        entityType.entityClass?.let { Ageable::class.java.isAssignableFrom(it) } == true

    fun validate(entityType: EntityType, appearance: MountAppearance, label: String) {
        val entityClass = entityType.entityClass ?: throw IllegalArgumentException("$label has no entity class")
        require(!appearance.baby || supportsAge(entityType)) {
            "$label requests a baby form unsupported by $entityType"
        }
        val primaryValues: Set<String> =
            when (entityType) {
                EntityType.AXOLOTL -> Axolotl.Variant.entries.mapTo(hashSetOf()) { it.name }
                EntityType.FOX -> Fox.Type.entries.mapTo(hashSetOf()) { it.name }
                EntityType.FROG -> setOf("COLD", "TEMPERATE", "WARM")
                EntityType.HORSE -> Horse.Color.entries.mapTo(hashSetOf()) { it.name }
                EntityType.LLAMA,
                EntityType.TRADER_LLAMA,
                -> Llama.Color.entries.mapTo(hashSetOf()) { it.name }
                EntityType.MOOSHROOM -> MushroomCow.Variant.entries.mapTo(hashSetOf()) { it.name }
                EntityType.PARROT -> Parrot.Variant.entries.mapTo(hashSetOf()) { it.name }
                EntityType.RABBIT -> Rabbit.Type.entries.mapTo(hashSetOf()) { it.name }
                else -> emptySet()
            }
        require(appearance.variant == null || appearance.variant in primaryValues) {
            "$label has unsupported variant '${appearance.variant}' for $entityType"
        }
        val secondaryValues = if (entityType == EntityType.HORSE) Horse.Style.entries.mapTo(hashSetOf()) { it.name } else emptySet()
        require(appearance.secondaryVariant == null || appearance.secondaryVariant in secondaryValues) {
            "$label has unsupported secondary variant '${appearance.secondaryVariant}' for $entityType"
        }
        appearance.equipment.forEach { (slot, materialName) ->
            require(Material.matchMaterial(materialName) != null) { "$label has unknown $slot material '$materialName'" }
        }
    }

    fun apply(entity: LivingEntity, appearance: MountAppearance) {
        fixAge(entity, appearance.baby)
        applyScale(entity, appearance.scale)
        applyVariant(entity, appearance.variant, appearance.secondaryVariant)
        applyEquipment(entity, appearance.equipment)
        if (entity is Zombie) {
            entity.setShouldBurnInDay(false)
            entity.setCanBreakDoors(false)
            entity.stopDrowning()
        }
        (entity as? PiglinAbstract)?.setImmuneToZombification(true)
        (entity as? Hoglin)?.setImmuneToZombification(true)
        (entity as? Shulker)?.setPeek(100.0f)
    }

    private fun fixAge(entity: LivingEntity, baby: Boolean) {
        if (entity is Ageable) {
            if (baby) entity.setBaby() else entity.setAdult()
        }
    }

    private fun applyScale(entity: LivingEntity, scale: Double) {
        entity.getAttribute(Attribute.SCALE)?.baseValue = scale
    }

    private fun applyVariant(entity: LivingEntity, variant: String?, secondary: String?) {
        when (entity) {
            is Axolotl -> variant?.enumValueOrNull<Axolotl.Variant>()?.let(entity::setVariant)
            is Fox -> variant?.enumValueOrNull<Fox.Type>()?.let(entity::setFoxType)
            is Frog ->
                variant
                    ?.registryValueOrNull(RegistryAccess.registryAccess().getRegistry(RegistryKey.FROG_VARIANT))
                    ?.let(entity::setVariant)
            is Horse -> {
                variant?.enumValueOrNull<Horse.Color>()?.let(entity::setColor)
                secondary?.enumValueOrNull<Horse.Style>()?.let(entity::setStyle)
            }
            is Llama -> variant?.enumValueOrNull<Llama.Color>()?.let(entity::setColor)
            is MushroomCow -> variant?.enumValueOrNull<MushroomCow.Variant>()?.let(entity::setVariant)
            is Parrot -> variant?.enumValueOrNull<Parrot.Variant>()?.let(entity::setVariant)
            is Rabbit -> variant?.enumValueOrNull<Rabbit.Type>()?.let(entity::setRabbitType)
        }
    }

    private fun applyEquipment(entity: LivingEntity, configured: Map<MountEquipmentSlot, String>) {
        val equipment = entity.equipment ?: return
        equipment.clear()
        configured.forEach { (slot, materialName) ->
            val material = Material.matchMaterial(materialName) ?: return@forEach
            val equipmentSlot =
                when (slot) {
                    MountEquipmentSlot.HEAD -> EquipmentSlot.HEAD
                    MountEquipmentSlot.CHEST -> EquipmentSlot.CHEST
                    MountEquipmentSlot.LEGS -> EquipmentSlot.LEGS
                    MountEquipmentSlot.FEET -> EquipmentSlot.FEET
                    MountEquipmentSlot.MAIN_HAND -> EquipmentSlot.HAND
                    MountEquipmentSlot.OFF_HAND -> EquipmentSlot.OFF_HAND
                    MountEquipmentSlot.BODY -> EquipmentSlot.BODY
                    MountEquipmentSlot.SADDLE -> EquipmentSlot.SADDLE
                }
            if (!entity.canUseEquipmentSlot(equipmentSlot)) return@forEach
            equipment.setItem(equipmentSlot, ItemStack(material), true)
            equipment.setDropChance(equipmentSlot, 0.0f)
        }
    }

    private inline fun <reified T : Enum<T>> String.enumValueOrNull(): T? =
        runCatching { enumValueOf<T>(uppercase(Locale.ROOT)) }.getOrNull()

    private fun <T : org.bukkit.Keyed> String.registryValueOrNull(registry: Registry<T>): T? =
        registry.get(NamespacedKey.minecraft(lowercase(Locale.ROOT)))
}
