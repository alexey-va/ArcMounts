package ru.arc.mounts

import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

private const val RECOVERY_VERSION = "v1"
private const val RECOVERY_FIELD_COUNT = 6
private const val DEFAULT_FLY_SPEED = 0.1f

private fun isValidMountFlySpeed(value: Float): Boolean = value.isFinite() && value in -1f..1f

/** Owns only the temporary native-flight fields used by a visual flying mount. */
internal class MountVisualFlight(plugin: JavaPlugin) {
    private val recoveryKey = NamespacedKey(plugin, RECOVERY_KEY)
    private val logger = plugin.logger

    fun begin(player: Player, flySpeed: Float) {
        requireValidFlySpeed(flySpeed, "mount fly speed")
        restore(player)

        val previousFlySpeed = player.flySpeed
        requireValidFlySpeed(previousFlySpeed, "player fly speed")
        val snapshot =
            Snapshot(
                allowFlight = player.allowFlight,
                flying = player.isFlying,
                flySpeed = previousFlySpeed,
                gameMode = player.gameMode,
                lastAssignedFlySpeed = flySpeed,
            )
        require(!snapshot.flying || snapshot.allowFlight) { "A player cannot be flying without flight permission" }

        val data = player.persistentDataContainer
        data.set(recoveryKey, PersistentDataType.STRING, snapshot.encode())
        try {
            player.allowFlight = true
            player.flySpeed = flySpeed
            player.isFlying = true
        } catch (failure: Exception) {
            try {
                restore(player)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    fun updateSpeed(player: Player, flySpeed: Float) {
        requireValidFlySpeed(flySpeed, "mount fly speed")
        val data = player.persistentDataContainer
        if (recoveryKey !in data.keys) return
        if (!data.has(recoveryKey, PersistentDataType.STRING)) {
            restore(player)
            return
        }

        val encoded = data.get(recoveryKey, PersistentDataType.STRING)
        val snapshot = encoded?.let(Snapshot::decode)
        if (snapshot == null) {
            restore(player)
            return
        }
        if (player.flySpeed != snapshot.lastAssignedFlySpeed || flySpeed == snapshot.lastAssignedFlySpeed) return

        data.set(
            recoveryKey,
            PersistentDataType.STRING,
            snapshot.copy(lastAssignedFlySpeed = flySpeed).encode(),
        )
        try {
            player.flySpeed = flySpeed
        } catch (failure: Exception) {
            try {
                data.set(recoveryKey, PersistentDataType.STRING, snapshot.encode())
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    fun restore(player: Player) {
        val data = player.persistentDataContainer
        if (recoveryKey !in data.keys) return
        if (!data.has(recoveryKey, PersistentDataType.STRING)) {
            recoverMalformedMarker(player)
            return
        }

        val encoded = data.get(recoveryKey, PersistentDataType.STRING)
        val snapshot = encoded?.let(Snapshot::decode)
        if (snapshot == null) {
            recoverMalformedMarker(player)
            return
        }

        restoreFlightFlags(player, snapshot)
        if (player.flySpeed == snapshot.lastAssignedFlySpeed) {
            player.flySpeed = snapshot.flySpeed
        }
        data.remove(recoveryKey)
    }

    fun recover(player: Player) = restore(player)

    private fun restoreFlightFlags(player: Player, snapshot: Snapshot) {
        val currentMode = player.gameMode
        val currentModeHasNativeFlight = currentMode.hasNativeFlight()
        val snapshotModeHadNativeFlight = snapshot.gameMode.hasNativeFlight()

        if (currentModeHasNativeFlight && currentMode != snapshot.gameMode) return
        if (snapshotModeHadNativeFlight && !currentModeHasNativeFlight) {
            player.isFlying = false
            if (player.allowFlight) player.allowFlight = false
            return
        }

        // Never re-enable flight that another plugin or the player turned off.
        if (player.isFlying && (!snapshot.flying || !player.allowFlight)) {
            player.isFlying = false
        }
        if (player.allowFlight && !snapshot.allowFlight) {
            player.allowFlight = false
        }
    }

    private fun recoverMalformedMarker(player: Player) {
        logger.warning(
            "Malformed mount visual-flight recovery state for player ${player.uniqueId} " +
                "in ${player.gameMode}; clearing temporary flight.",
        )
        if (!player.gameMode.hasNativeFlight()) {
            player.isFlying = false
            if (player.allowFlight) player.allowFlight = false
            player.flySpeed = DEFAULT_FLY_SPEED
        }
        player.persistentDataContainer.remove(recoveryKey)
    }

    private data class Snapshot(
        val allowFlight: Boolean,
        val flying: Boolean,
        val flySpeed: Float,
        val gameMode: GameMode,
        val lastAssignedFlySpeed: Float,
    ) {
        fun encode(): String =
            listOf(
                RECOVERY_VERSION,
                allowFlight.toString(),
                flying.toString(),
                flySpeed.toRawBits().toString(),
                gameMode.name,
                lastAssignedFlySpeed.toRawBits().toString(),
            ).joinToString("|")

        companion object {
            fun decode(encoded: String): Snapshot? {
                val fields = encoded.split('|')
                if (fields.size != RECOVERY_FIELD_COUNT || fields[0] != RECOVERY_VERSION) return null

                val allowFlight = fields[1].toBooleanStrictOrNull() ?: return null
                val flying = fields[2].toBooleanStrictOrNull() ?: return null
                val flySpeed = fields[3].toIntOrNull()?.let { Float.fromBits(it) } ?: return null
                val gameMode = runCatching { GameMode.valueOf(fields[4]) }.getOrNull() ?: return null
                val lastAssignedFlySpeed = fields[5].toIntOrNull()?.let { Float.fromBits(it) } ?: return null
                if (!isValidMountFlySpeed(flySpeed) || !isValidMountFlySpeed(lastAssignedFlySpeed)) return null
                if (flying && !allowFlight) return null

                return Snapshot(allowFlight, flying, flySpeed, gameMode, lastAssignedFlySpeed)
            }
        }
    }

    private fun requireValidFlySpeed(value: Float, label: String) {
        require(isValidMountFlySpeed(value)) { "$label must be finite and between -1 and 1" }
    }

    internal companion object {
        const val RECOVERY_KEY = "mount_visual_flight"
    }
}

private fun GameMode.hasNativeFlight(): Boolean = this == GameMode.CREATIVE || this == GameMode.SPECTATOR
