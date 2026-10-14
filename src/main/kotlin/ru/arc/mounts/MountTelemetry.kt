package ru.arc.mounts

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional domain telemetry routed through ARC's registered host service. */
internal class MountTelemetry(private val plugin: Plugin) {
    fun recordProductOutcome(player: Player, operationId: String) {
        val telemetry = provider() ?: return
        telemetry.recordActivity(player.uniqueId, "arc", "mechanic.outcome", "mount_ride", null,
            mapOf("entry" to "gameplay", "feature" to "mounts"))
        telemetry.record(player.uniqueId, "arcmounts", "mounts", "mount_ride", null, operationId)
    }

    fun recordActivity(
        playerId: UUID,
        event: String,
        subject: String?,
        operationId: String?,
        attributes: Map<String, String>,
    ) {
        provider()?.recordActivity(playerId, "mounts", event, subject, operationId, attributes)
    }

    private fun provider(): ArcTelemetryProvider? = plugin.server.servicesManager.load(ArcTelemetryProvider::class.java)
}
