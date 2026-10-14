package ru.arc.mounts

import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Location
import org.bukkit.entity.Player

/** Live, server-side check for the two spawn mount merchants. */
internal object MountMerchantGate {
    private const val SPAWN_WORLD = "rc_origin_spawn"
    private const val MERCHANT_RADIUS_SQUARED = 25.0
    private val MERCHANT_IDS = intArrayOf(368, 369)

    fun isAtMerchant(player: Player): Boolean {
        val playerLocation = player.location
        val world = playerLocation.world ?: return false
        if (!world.name.equals(SPAWN_WORLD, ignoreCase = true)) return false
        return MERCHANT_IDS.any { id ->
            val npc = runCatching { CitizensAPI.getNPCRegistry().getById(id) }.getOrNull() ?: return@any false
            if (!npc.isSpawned) return@any false
            val location = npc.entity?.location ?: return@any false
            isWithin(world.name, playerLocation, location)
        }
    }

    internal fun isWithin(worldName: String?, player: Location, merchant: Location, radiusSquared: Double = MERCHANT_RADIUS_SQUARED): Boolean {
        val playerWorld = player.world ?: return false
        val merchantWorld = merchant.world ?: return false
        return worldName.equals(SPAWN_WORLD, ignoreCase = true) &&
            playerWorld.uid == merchantWorld.uid &&
            player.distanceSquared(merchant) <= radiusSquared
    }
}
