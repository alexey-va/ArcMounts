package ru.arc.mounts

import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.persistence.AtomicFileStore
import java.nio.file.Path
import java.util.UUID

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
    fun save(record: MountTransferRecord)
    fun get(id: UUID): MountTransferRecord? = records().firstOrNull { it.id == id }
}

class FileMountTransferStore(root: Path) : MountTransferStore {
    private val file = AtomicFileStore(
        root, Path.of("data/mount-transfers.json"), 32L * 1024 * 1024,
        encode = { records: Array<MountTransferRecord> -> MountJson.prettyGson.toJson(records).toByteArray(Charsets.UTF_8) },
        decode = { MountJson.prettyGson.fromJson(it.toString(Charsets.UTF_8), Array<MountTransferRecord>::class.java) },
        validate = { records ->
            require(records.size <= 50_000 && records.map { it.id }.distinct().size == records.size)
            records.forEach(MountTransferRecord::validate)
        },
    )
    private var values = file.loadOrDefault { emptyArray() }.associateBy { it.id }
    override fun records(): List<MountTransferRecord> = values.values.toList()
    override fun save(record: MountTransferRecord) {
        record.validate()
        val prior = values[record.id]
        require(prior == null || prior.copy(stage = record.stage, recipient = record.recipient) == record)
        require(prior == null || record.stage.ordinal >= prior.stage.ordinal)
        require(prior?.recipient == null || prior.recipient == record.recipient)
        values = file.write((values + (record.id to record)).values.toTypedArray()).associateBy { it.id }
    }
}
