@file:Suppress("DEPRECATION")

package ru.arc.mounts

import org.bukkit.inventory.ItemStack

/** Reads the legacy integer CustomModelData used by the mount GUI fixtures. */
internal val ItemStack.customModelDataOrNull: Int?
    get() = itemMeta?.let { meta ->
        if (meta.hasCustomModelData()) meta.customModelData else null
    }
