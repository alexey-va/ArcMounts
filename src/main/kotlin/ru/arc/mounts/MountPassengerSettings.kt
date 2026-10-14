package ru.arc.mounts

import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

internal data class MountPassengerSeatSettings(
    val scale: Double,
    val singleYaw: Double = 180.0,
    val pairedYaw: Double,
)

/** Only passenger geometry is live; other mount configuration keeps its normal reload lifecycle. */
internal class MountPassengerSettings(
    private val dataPath: Path,
    private val fallback: MountPassengerSeatSettings,
    private val logger: Logger,
) {
    private data class Snapshot(
        val defaults: MountPassengerSeatSettings,
        val mounts: Map<String, MountPassengerSeatSettings> = emptyMap(),
    )

    @Volatile private var snapshot = Snapshot(fallback)
    private var lastContent: String? = null
    private var lastFailure: String? = null
    private var scope: LifecycleTaskScope? = null

    fun forMount(id: String): MountPassengerSeatSettings = snapshot.let { it.mounts[id] ?: it.defaults }

    fun start(scheduler: TaskScheduler) {
        val tasks = LifecycleTaskScope(scheduler, true)
        scope = tasks
        fun poll() {
            refresh()
            tasks.runLaterAsync(40L, ::poll)
        }
        tasks.runAsync(::poll)
    }

    fun close() {
        scope?.close()
        scope = null
    }

    /** Called only off the gameplay thread. Invalid edits retain the entire last good snapshot. */
    internal fun refresh() {
        try {
            val path = ConfigManager.moduleYamlPath(dataPath, "mounts.yml")
            val content = Files.readString(path)
            if (content == lastContent) return
            val yaml = YamlConfiguration().also { it.loadFromString(content) }
            val section = requireNotNull(yaml.getConfigurationSection("passengers")) { "Missing passengers section" }
            fun settings(prefix: String, defaults: MountPassengerSeatSettings): MountPassengerSeatSettings {
                fun number(key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
                    val raw = section.get(prefix + key) ?: return default
                    val value = raw.toString().toDoubleOrNull()
                    require(value != null && value.isFinite() && value in range) { "Invalid passengers.$prefix$key: $raw" }
                    return value
                }
                return MountPassengerSeatSettings(
                    number("carrier-scale", defaults.scale, 0.1..2.0),
                    number("single-carrier-yaw-offset", defaults.singleYaw, -180.0..180.0),
                    number("carrier-yaw-offset", defaults.pairedYaw, -180.0..180.0),
                )
            }
            val defaults = settings("", fallback)
            val overrides = section.getConfigurationSection("mounts")?.getKeys(false).orEmpty().associateWith { id ->
                require(section.isConfigurationSection("mounts.$id")) { "Invalid passengers.mounts.$id" }
                settings("mounts.$id.", defaults)
            }
            val next = Snapshot(defaults, overrides)
            if (next != snapshot || lastFailure != null) {
                snapshot = next
                logger.info("Mount passenger settings applied live: scale=${defaults.scale}, singleYaw=${defaults.singleYaw}, pairedYaw=${defaults.pairedYaw}, overrides=${overrides.keys}")
            }
            lastContent = content
            lastFailure = null
        } catch (failure: Exception) {
            val reason = "${failure.javaClass.name}:${failure.message}"
            if (reason != lastFailure) logger.log(Level.WARNING, "Mount passenger settings rejected; keeping previous seats", failure)
            lastFailure = reason
        }
    }
}
