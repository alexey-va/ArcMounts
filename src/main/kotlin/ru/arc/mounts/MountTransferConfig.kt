package ru.arc.mounts

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import java.nio.file.Path

class MountTransferConfig(private val config: Config) {
    val enabled get() = config.bool("enabled", false)
    val detailSlot get() = config.integer("gui.detail-slot", 38)
    fun text(key: String, fallback: String) = config.string("text.$key", fallback)
    fun lines(key: String, fallback: List<String>) = config.stringList("text.$key").ifEmpty { fallback }
    fun sql() = SqlConnectionConfig(
        host = config.string("mysql.host", "localhost"), port = config.integer("mysql.port", 3306),
        database = config.string("mysql.database", "arc"), username = config.string("mysql.username", "arc"),
        password = config.string("mysql.password", ""),
        sslMode = SqlSslMode.valueOf(config.string("mysql.ssl-mode", "VERIFY_IDENTITY")),
        maximumPoolSize = 2,
    )
    companion object {
        fun load(root: Path) = MountTransferConfig(ConfigManager.of(root, "modules/mount-transfers.yml"))
    }
}
