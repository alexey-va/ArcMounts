package ru.arc.mounts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files

class MountMenuConfigurationTest : TestBase() {
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
