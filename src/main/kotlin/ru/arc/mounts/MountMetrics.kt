package ru.arc.mounts

import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcMetricGauge
import ru.arc.paper.api.ArcMetricsScope
import ru.arc.paper.metrics.PaperArcMetrics

/** Mount-owned gauges stored in the host's shared registry. */
internal class MountTechnicalMetrics(private val scope: ArcMetricsScope?) : AutoCloseable {
    private val enabled = scope?.gauge("arc_mounts_enabled", "Whether native mounts are enabled", emptyMap())
    private val purchasesEnabled = scope?.gauge("arc_mounts_purchases_enabled", "Whether mount purchases are enabled", emptyMap())
    private val catalogEntries = scope?.gauge("arc_mounts_catalog_entries", "Configured native mounts", emptyMap())
    private val activeSessions = scope?.gauge("arc_mounts_active_sessions", "Active native mount sessions", emptyMap())
    private val unresolvedPurchases = scope?.gauge(
        "arc_mount_purchase_journal_unresolved",
        "Unresolved native mount purchases",
        emptyMap(),
    )
    private val journalRecords: Map<MountPurchaseJournalStatus, ArcMetricGauge> =
        MountPurchaseJournalStatus.entries.associateWith { status ->
            scope?.gauge(
                "arc_mount_purchase_journal_records",
                "Native mount purchase journal records by status",
                mapOf("status" to status.name.lowercase()),
            )
        }.filterValues { it != null }.mapValues { requireNotNull(it.value) }

    fun publish(
        enabled: Boolean,
        purchasesEnabled: Boolean,
        catalogSize: Int,
        activeSessions: Int,
        records: List<MountPurchaseJournalRecord>,
    ) {
        this.enabled?.set(if (enabled) 1.0 else 0.0)
        this.purchasesEnabled?.set(if (purchasesEnabled) 1.0 else 0.0)
        catalogEntries?.set(catalogSize.toDouble())
        this.activeSessions?.set(activeSessions.toDouble())
        unresolvedPurchases?.set(
            records.count { !it.status.terminal || it.status == MountPurchaseJournalStatus.MANUAL_REVIEW }.toDouble(),
        )
        MountPurchaseJournalStatus.entries.forEach { status ->
            journalRecords[status]?.set(records.count { it.status == status }.toDouble())
        }
    }

    override fun close() = scope?.close() ?: Unit

    companion object {
        fun open(plugin: Plugin): MountTechnicalMetrics =
            MountTechnicalMetrics(PaperArcMetrics.open(plugin, "arc-mounts"))
    }
}
