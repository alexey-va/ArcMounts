package ru.arc.mounts

import com.google.gson.Gson
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.persistence.AtomicFileStore

enum class MountPurchaseKind {
    LEVEL,
    GLOW,
    SKIN,
    ABILITY,
    SIZE,
}

enum class MountPurchaseJournalStatus {
    PREPARED,
    WITHDRAWAL_STARTED,
    FUNDS_WITHDRAWN,
    OWNERSHIP_STARTED,
    COMPLETED,
    CANCELLED,
    REFUND_STARTED,
    REFUNDED,
    MANUAL_REVIEW,
    ;

    val terminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == REFUNDED || this == MANUAL_REVIEW
}

data class MountPurchaseJournalRecord(
    val transactionId: String,
    val playerId: String,
    val mountId: String,
    val kind: MountPurchaseKind,
    val target: String,
    val permission: String,
    val priceMinor: Long,
    val currency: String = "vault",
    val status: MountPurchaseJournalStatus = MountPurchaseJournalStatus.PREPARED,
    val createdAt: Long,
    val updatedAt: Long,
    val balanceBeforeMinor: Long? = null,
    val balanceAfterMinor: Long? = null,
    val refundBalanceBeforeMinor: Long? = null,
    val refundBalanceAfterMinor: Long? = null,
    val evidence: String? = null,
) {
    fun validated(): MountPurchaseJournalRecord {
        require(runCatching { UUID.fromString(transactionId) }.getOrNull()?.toString() == transactionId) {
            "Invalid mount purchase transaction id"
        }
        require(runCatching { UUID.fromString(playerId) }.getOrNull()?.toString() == playerId) {
            "Invalid mount purchase player id"
        }
        require(MountDefinition.validId(mountId)) { "Invalid mount purchase mount id" }
        require(TARGET_PATTERN.matches(target)) { "Invalid mount purchase target" }
        require(PERMISSION_PATTERN.matches(permission) && permission.startsWith("arc.mounts.$mountId.")) {
            "Invalid mount purchase permission"
        }
        require(priceMinor > 0L) { "Mount purchase price must be positive" }
        require(CURRENCY_PATTERN.matches(currency)) { "Invalid mount purchase currency" }
        require(createdAt > 0L && updatedAt >= createdAt) { "Invalid mount purchase timestamps" }
        require(evidence == null || EVIDENCE_PATTERN.matches(evidence)) { "Invalid mount purchase evidence" }
        if (status == MountPurchaseJournalStatus.FUNDS_WITHDRAWN ||
            status == MountPurchaseJournalStatus.OWNERSHIP_STARTED ||
            status == MountPurchaseJournalStatus.COMPLETED ||
            status == MountPurchaseJournalStatus.REFUND_STARTED ||
            status == MountPurchaseJournalStatus.REFUNDED
        ) {
            require(balanceBeforeMinor != null && balanceAfterMinor == balanceBeforeMinor - priceMinor) {
                "Withdrawn mount purchase lacks exact balance evidence"
            }
        }
        if (status == MountPurchaseJournalStatus.REFUNDED) {
            require(refundBalanceBeforeMinor != null && refundBalanceAfterMinor == refundBalanceBeforeMinor + priceMinor) {
                "Refunded mount purchase lacks exact balance evidence"
            }
        }
        return this
    }

    companion object {
        private val TARGET_PATTERN = Regex("[a-z0-9_-]{1,48}")
        private val PERMISSION_PATTERN = Regex("[a-z0-9._-]{4,160}")
        private val EVIDENCE_PATTERN = Regex("[a-z0-9_:-]{1,160}")
        private val CURRENCY_PATTERN = Regex("[A-Za-z0-9_-]{1,16}")
    }
}

/** Gson can bypass Kotlin's non-null field contract when reading older snapshots. */
private fun persistedCurrencyOrDefault(value: Any?): String {
    if (value == null) return "vault"
    return value as? String ?: error("Invalid mount purchase currency value")
}

data class MountPurchaseJournalSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val records: List<MountPurchaseJournalRecord> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

interface MountPurchaseJournal {
    fun records(): List<MountPurchaseJournalRecord>

    fun persist(record: MountPurchaseJournalRecord): Boolean

    /** Acknowledges the durable write; production journals dispatch to their owned writer. */
    fun persistAsync(record: MountPurchaseJournalRecord): CompletableFuture<Boolean> =
        runCatching { persist(record) }.fold(CompletableFuture<Boolean>::completedFuture, CompletableFuture<Boolean>::failedFuture)

    fun hasOpenPurchase(playerId: UUID): Boolean =
        records().any {
            it.playerId == playerId.toString() &&
                (!it.status.terminal || it.status == MountPurchaseJournalStatus.MANUAL_REVIEW)
        }
}

class FileMountPurchaseJournal(
    private val path: Path,
    private val gson: Gson = MountJson.prettyGson,
    private val writer: BoundedAsyncExecutor? = null,
) : MountPurchaseJournal {
    private val recordsById = linkedMapOf<String, MountPurchaseJournalRecord>()
    @Volatile private var published = JournalView(emptyList(), emptySet())
    private val file = AtomicFileStore(
        root = requireNotNull(path.toAbsolutePath().normalize().parent),
        relativePath = Path.of(path.fileName.toString()),
        maxBytes = MAX_FILE_BYTES,
        encode = { snapshot: MountPurchaseJournalSnapshot -> gson.toJson(snapshot).toByteArray(Charsets.UTF_8) },
        decode = { bytes ->
            requireNotNull(gson.fromJson(bytes.toString(Charsets.UTF_8), MountPurchaseJournalSnapshot::class.java)) {
                "Mount purchase journal is empty"
            }
        },
        validate = ::validateSnapshot,
    )

    init {
        load()
        publish()
    }

    override fun records(): List<MountPurchaseJournalRecord> = published.records

    override fun hasOpenPurchase(playerId: UUID): Boolean = playerId.toString() in published.pendingPlayers

    override fun persistAsync(record: MountPurchaseJournalRecord): CompletableFuture<Boolean> =
        writer?.submit { persist(record) } ?: super.persistAsync(record)

    // The production owner invokes this only on its serial writer. Readers use the immutable view.
    override fun persist(record: MountPurchaseJournalRecord): Boolean {
        val valid = record.validated()
        val current = recordsById[valid.transactionId]
        if (current != null) {
            require(
                current.playerId == valid.playerId &&
                    current.mountId == valid.mountId &&
                    current.kind == valid.kind &&
                    current.target == valid.target &&
                    current.permission == valid.permission &&
                    current.priceMinor == valid.priceMinor &&
                    current.currency == valid.currency &&
                    current.createdAt == valid.createdAt
            ) {
                "Mount purchase journal identity changed"
            }
            require(valid.updatedAt >= current.updatedAt) { "Mount purchase journal moved backwards in time" }
            require(valid.status == current.status || valid.status in allowedTransitions(current.status)) {
                "Illegal mount purchase journal transition ${current.status} -> ${valid.status}"
            }
        } else {
            require(valid.status == MountPurchaseJournalStatus.PREPARED) {
                "New mount purchase journal record must start PREPARED"
            }
        }
        val next = LinkedHashMap(recordsById)
        next[valid.transactionId] = valid
        val compacted = compact(next.values)
        return runCatching {
            val snapshot = file.write(MountPurchaseJournalSnapshot(records = compacted))
            recordsById.clear()
            snapshot.records.forEach { recordsById[it.transactionId] = it }
            publish()
        }.isSuccess
    }

    private data class JournalView(val records: List<MountPurchaseJournalRecord>, val pendingPlayers: Set<String>)

    private fun publish() {
        val snapshot = recordsById.values.toList()
        published = JournalView(snapshot, snapshot.asSequence()
            .filter { !it.status.terminal || it.status == MountPurchaseJournalStatus.MANUAL_REVIEW }
            .map(MountPurchaseJournalRecord::playerId).toSet())
    }

    private fun load() {
        val snapshot = runCatching { file.loadOrNull() }
            .getOrElse { throw IllegalStateException("Unable to read mount purchase journal", it) }
            ?: return
        snapshot.records
            // Gson bypasses Kotlin constructor defaults when a historical field is absent.
            .map { record -> record.copy(currency = persistedCurrencyOrDefault(record.currency)) }
            .map(MountPurchaseJournalRecord::validated)
            .forEach { record ->
            require(recordsById.put(record.transactionId, record) == null) { "Duplicate mount purchase transaction id" }
            }
    }

    private fun compact(records: Collection<MountPurchaseJournalRecord>): List<MountPurchaseJournalRecord> {
        val unresolved = records.filter { !it.status.terminal || it.status == MountPurchaseJournalStatus.MANUAL_REVIEW }
        val resolved = records.filter { it.status.terminal && it.status != MountPurchaseJournalStatus.MANUAL_REVIEW }
            .sortedByDescending(MountPurchaseJournalRecord::updatedAt)
        require(unresolved.size <= MAX_NON_TERMINAL) { "Too many unresolved mount purchase records" }
        return (unresolved + resolved.take(MAX_RECORDS - unresolved.size)).sortedBy(MountPurchaseJournalRecord::createdAt)
    }

    private fun validateSnapshot(snapshot: MountPurchaseJournalSnapshot) {
        require(snapshot.schemaVersion == MountPurchaseJournalSnapshot.CURRENT_SCHEMA) {
            "Unsupported mount purchase journal schema ${snapshot.schemaVersion}"
        }
        require(snapshot.records.size <= MAX_RECORDS) { "Mount purchase journal exceeds hard record limit" }
        val ids = HashSet<String>()
        snapshot.records.forEach { record ->
            record.copy(currency = persistedCurrencyOrDefault(record.currency)).validated()
            require(ids.add(record.transactionId)) { "Duplicate mount purchase transaction id" }
        }
    }

    private fun allowedTransitions(status: MountPurchaseJournalStatus): Set<MountPurchaseJournalStatus> =
        when (status) {
            MountPurchaseJournalStatus.PREPARED ->
                setOf(MountPurchaseJournalStatus.WITHDRAWAL_STARTED, MountPurchaseJournalStatus.CANCELLED)
            MountPurchaseJournalStatus.WITHDRAWAL_STARTED ->
                setOf(
                    MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                    MountPurchaseJournalStatus.CANCELLED,
                    MountPurchaseJournalStatus.MANUAL_REVIEW,
                )
            MountPurchaseJournalStatus.FUNDS_WITHDRAWN ->
                setOf(
                    MountPurchaseJournalStatus.OWNERSHIP_STARTED,
                    MountPurchaseJournalStatus.COMPLETED,
                    MountPurchaseJournalStatus.MANUAL_REVIEW,
                )
            MountPurchaseJournalStatus.OWNERSHIP_STARTED ->
                setOf(
                    MountPurchaseJournalStatus.COMPLETED,
                    MountPurchaseJournalStatus.REFUND_STARTED,
                    MountPurchaseJournalStatus.MANUAL_REVIEW,
                )
            MountPurchaseJournalStatus.REFUND_STARTED ->
                setOf(MountPurchaseJournalStatus.REFUNDED, MountPurchaseJournalStatus.MANUAL_REVIEW)
            MountPurchaseJournalStatus.COMPLETED,
            MountPurchaseJournalStatus.CANCELLED,
            MountPurchaseJournalStatus.REFUNDED,
            -> emptySet()
            MountPurchaseJournalStatus.MANUAL_REVIEW ->
                setOf(
                    MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                    MountPurchaseJournalStatus.COMPLETED,
                    MountPurchaseJournalStatus.REFUNDED,
                )
        }

    companion object {
        private const val MAX_RECORDS = 4_096
        private const val MAX_NON_TERMINAL = 512
        private const val MAX_FILE_BYTES = 8L * 1024L * 1024L
    }
}
