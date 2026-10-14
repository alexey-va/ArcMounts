package ru.arc

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.plugin.PluginMock
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.mounts.MountMenuConfiguration
import ru.arc.mounts.MountTransferConfig
import java.nio.file.Files
import java.nio.file.Path

/** Mounts-local fixture; the shared core fixture owns MockBukkit's process-global lifecycle. */
abstract class TestBase {
    protected lateinit var paper: MockBukkitTestRuntime
    protected lateinit var server: ServerMock
    protected lateinit var plugin: PluginMock
    protected lateinit var dataPath: Path
    protected lateinit var menuRuntime: PaperMenuRuntime

    @BeforeEach
    open fun setUpBase() {
        ConfigManager.clear()
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        plugin = paper.createSimplePlugin("ArcMountsTests")
        dataPath = plugin.dataFolder.toPath()
        Files.createDirectories(dataPath)
        val transfers = MountTransferConfig.load(dataPath)
        menuRuntime = PaperMenuRuntime(
            plugin,
            BukkitTaskScheduler(plugin),
            MountMenuConfiguration.load(dataPath, transfers.detailSlot),
        )
    }

    @AfterEach
    fun tearDownBase() {
        menuRuntime.close()
        ConfigManager.clear()
        paper.close()
    }
}
