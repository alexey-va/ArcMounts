package ru.arc.mounts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.util.concurrent.Executors

class MountMenuConfigurationTest : TestBase() {
    @Test
    fun `worker resolves all mount defaults despite host resource collision and preserves operator values`() {
        val menuRoot = Files.createDirectories(dataPath.resolve("host-collision"))
        val seed = Config(menuRoot, MountMenuConfiguration.RESOURCE)
        seed.keys("menus.layouts") shouldBe setOf("host-only")
        seed.setInt("menus.layouts.mount-detail.elements.favorite.slot", 12)
        seed.setString("menus.templates.background.material", "BLACK_STAINED_GLASS_PANE")
        seed.saveStrict()

        val prepared = Executors.newSingleThreadExecutor().use { executor ->
            executor.submit<MountMenuConfiguration.Prepared> {
                val loader = object : ClassLoader(null) {}
                Thread.currentThread().contextClassLoader = loader
                MountMenuConfiguration.prepare(menuRoot).also {
                    assertSame(loader, Thread.currentThread().contextClassLoader)
                }
            }.get()
        }
        val parsed = MountMenuConfiguration.parse(prepared, transferSlot = 38)
        val required = setOf("mount-list", "mount-detail", "mount-progression", "mount-tuning", "mount-skins", "mount-confirm")
        parsed.catalog.layouts.keys.map { it.value }.toSet().containsAll(required) shouldBe true
        parsed.catalog.layouts.keys.any { it.value == "host-only" } shouldBe false
        val disk = Config(menuRoot, MountMenuConfiguration.RESOURCE)
        disk.intOrNull("menus.layouts.host-only.elements.info.slot") shouldBe 4
        disk.stringOrNull("menus.templates.host-info.name") shouldBe "Operator host menu"
        disk.intOrNull("menus.layouts.mount-detail.elements.favorite.slot") shouldBe 12
        disk.stringOrNull("menus.templates.background.material") shouldBe "BLACK_STAINED_GLASS_PANE"
        disk.keys("menus.layouts").containsAll(required + "host-only") shouldBe true
    }

    @Test
    fun `worker restores context loader when saving merged defaults fails`() {
        val menuRoot = Files.createDirectories(dataPath.resolve("failed-default-save"))
        ConfigManager.of(menuRoot, MountMenuConfiguration.RESOURCE)
        val menuFile = menuRoot.resolve(MountMenuConfiguration.RESOURCE)
        Files.delete(menuFile)
        Files.createDirectory(menuFile)
        Files.writeString(menuFile.resolve("operator-file"), "Keep this directory nonempty")

        Executors.newSingleThreadExecutor().use { executor ->
            executor.submit {
                val loader = object : ClassLoader(null) {}
                Thread.currentThread().contextClassLoader = loader
                assertThrows(Exception::class.java) { MountMenuConfiguration.prepare(menuRoot) }
                assertSame(loader, Thread.currentThread().contextClassLoader)
            }.get()
        }
    }

    @Test
    fun `filtered child projection preserves host layouts through migration and reload`() {
        val menuRoot = Files.createDirectories(dataPath.resolve("operator-menu-file"))
        val seed = Config(menuRoot, MountMenuConfiguration.RESOURCE)
        seed.setInt("menus.layouts.mount-detail.elements.glow.slot", 21)
        seed.setString("menus.layouts.mount-detail.elements.glow.template", "background")
        seed.setString("menus.layouts.mount-detail.elements.skins.template", "background")
        seed.setInt("menus.layouts.mount-tuning.rows", 5)
        seed.setInt("menus.layouts.mount-tuning.elements.back.slot", 36)
        seed.setInt("menus.layouts.mount-tuning.elements.rider-view.slot", 40)
        seed.setInt("menus.layouts.contracts-detail.rows", 3)
        seed.setString("menus.layouts.contracts-detail.background.template", "background")
        seed.setInt("menus.layouts.contracts-detail.elements.deposit.slot", 13)
        seed.setString("menus.layouts.contracts-detail.elements.deposit.template", "operator-contract-button")
        seed.setString("menus.templates.operator-contract-button.material", "PAPER")
        seed.setString("menus.templates.operator-contract-button.name", "Operator deposit button")
        seed.saveStrict()

        val first = MountMenuConfiguration.load(menuRoot, transferSlot = 38)
        first.catalog.layouts.keys.any { it.value == "contracts-detail" } shouldBe false

        val cached = ConfigManager.of(menuRoot, MountMenuConfiguration.RESOURCE)
        assertHostMenuAndMigration(cached)
        ConfigManager.reloadAll()

        val second = MountMenuConfiguration.load(menuRoot, transferSlot = 38)
        second.catalog.layouts.keys.any { it.value == "contracts-detail" } shouldBe false
        assertHostMenuAndMigration(ConfigManager.of(menuRoot, MountMenuConfiguration.RESOURCE))
        assertHostMenuAndMigration(Config(menuRoot, MountMenuConfiguration.RESOURCE))
    }

    private fun assertHostMenuAndMigration(config: Config) {
        config.intOrNull("menus.layouts.contracts-detail.rows") shouldBe 3
        config.stringOrNull("menus.layouts.contracts-detail.elements.deposit.template") shouldBe "operator-contract-button"
        config.stringOrNull("menus.templates.operator-contract-button.name") shouldBe "Operator deposit button"

        config.intOrNull("menus.layouts.mount-detail.elements.settings.slot") shouldBe 21
        config.intOrNull("menus.layouts.mount-detail.elements.glow.slot") shouldBe null
        config.intOrNull("menus.layouts.mount-tuning.rows") shouldBe 6
        config.intOrNull("menus.layouts.mount-tuning.elements.back.slot") shouldBe 45
        config.intOrNull("menus.layouts.mount-tuning.elements.rider-view.slot") shouldBe 49
    }
}
