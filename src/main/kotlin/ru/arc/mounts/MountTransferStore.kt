package ru.arc.mounts

import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.persistence.AtomicFileStore
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import ru.arc.core.BoundedAsyncExecutor

enum class MountTransferStage { PACKING, PACKED, DELIVERING, AVAILABLE, CLAIMING, APPLIED, CONSUMED }

/** Mount-specific escrow: exact permanent entitlements, never prices or permissions supplied by an item. */
data class MountTransferRecord(
    val id: UUID,
    val issuer: UUID,
    val mountId: String,
    val permissions: List<String>,
    val stage: MountTransferStage = MountTransferStage.PACKING,
    val recipient: UUID? = null,
) {
    val identity: OneTimeUseIdentity get() = OneTimeUseIdentity(
        id, OneTimeUseFingerprint.sha256Fields(id.toString(), issuer.toString(), mountId, permissions.joinToString("\n")),
    )

    fun validate() {
        require(MountDefinition.validId(mountId))
        require(permissions.size in 1..96 && permissions == permissions.distinct().sorted())
        require(permissions.all { it.startsWith("arc.mounts.$mountId.") && it.matches(Regex("[a-z0-9._-]{1,160}")) })
        require(permissions.any { it.removePrefix("arc.mounts.$mountId.").toIntOrNull() in 1..16 })
        require((stage in setOf(MountTransferStage.CLAIMING, MountTransferStage.APPLIED, MountTransferStage.CONSUMED)) == (recipient != null))
    }
}

interface MountTransferStore {
    fun records(): List<MountTransferRecord>
    fun saveAsync(record: MountTransferRecord): CompletableFuture<Unit> =
        runCatching { save(record) }.fold(CompletableFuture<Unit>::completedFuture, CompletableFuture<Unit>::failedFuture)
    fun recordsFor(playerId: UUID): List<MountTransferRecord> = records().filter { it.issuer == playerId || it.recipient == playerId }
    fun save(record: MountTransferRecord)
    fun get(id: UUID): MountTransferRecord? = records().firstOrNull { it.id == id }
}

class FileMountTransferStore(root: Path, private val writer: BoundedAsyncExecutor? = null) : MountTransferStore {
    private val file = AtomicFileStore(
        root, Path.of("data/mount-transfers.json"), 32L * 1024 * 1024,
        encode = { records: Array<MountTransferRecord> -> MountJson.prettyGson.toJson(records).toByteArray(Charsets.UTF_8) },
        decode = { MountJson.prettyGson.fromJson(it.toString(Charsets.UTF_8), Array<MountTransferRecord>::class.java) },
        validate = { records ->
            require(records.size <= 50_000 && records.map { it.id }.distinct().size == records.size)
            records.forEach(MountTransferRecord::validate)
        },
    )
    private data class View(val byId: Map<UUID, MountTransferRecord>, val records: List<MountTransferRecord>, val byPlayer: Map<UUID, List<MountTransferRecord>>)
    @Volatile private var view = view(file.loadOrDefault { emptyArray() }.toList())
    private fun view(records: List<MountTransferRecord>): View {
        val byPlayer = mutableMapOf<UUID, MutableList<MountTransferRecord>>()
        records.forEach { record ->
            byPlayer.getOrPut(record.issuer, ::mutableListOf).add(record)
            record.recipient?.takeIf { it != record.issuer }?.let { byPlayer.getOrPut(it, ::mutableListOf).add(record) }
        }
        return View(records.associateBy { it.id }, records, byPlayer)
    }
    override fun records(): List<MountTransferRecord> = view.records
    override fun recordsFor(playerId: UUID): List<MountTransferRecord> = view.byPlayer[playerId].orEmpty()
    override fun get(id: UUID): MountTransferRecord? = view.byId[id]
    override fun saveAsync(record: MountTransferRecord): CompletableFuture<Unit> =
        writer?.submit { save(record) } ?: super.saveAsync(record)
    override fun save(record: MountTransferRecord) {
        record.validate()
        val values = view.byId
        val prior = values[record.id]
        require(prior == null || prior.copy(stage = record.stage, recipient = record.recipient) == record)
        require(prior == null || record.stage.ordinal >= prior.stage.ordinal)
        require(prior?.recipient == null || prior.recipient == record.recipient)
        view = view(file.write((values + (record.id to record)).values.toTypedArray()).toList())
    }
}
