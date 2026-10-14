package ru.arc.mounts

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import java.nio.file.Files

class MountGuiItemsCurrencyTest : TestBase() {
    @Test
    fun `token price lore renders runtime premium glyph through currency placeholder`() {
        val bundled = bundledMountConfigForCurrency()
        val mount = checkNotNull(bundled.catalog().all.first { it.currency == "tokens" && it.price(1) != null })
        val glyph = "\uE516"
        val runtimeConfig = mockk<MountModuleConfig> {
            every { guiText(any(), any()) } answers {
                when (firstArg<String>()) {
                    "common.price-tokens" -> "<#8c8c8c>Цена: <#ffacd5><price> <currency>"
                    "currencies.tokens" -> "<white><bold:false>$glyph</bold></white>"
                    else -> secondArg()
                }
            }
            every { guiLines(any(), any()) } answers { secondArg() }
            every { guiStyle(any()) } returns MountGuiItemStyle()
        }

        val item = MountGuiItems({ runtimeConfig }, mockk(relaxed = true))
            .mountIcon(mount, MountProfile(level = 0, glowOwned = false, glowDisabled = false))
        val lore = checkNotNull(item.itemMeta?.lore())
            .map(PlainTextComponentSerializer.plainText()::serialize)

        lore.any { it.startsWith("Цена:") && glyph in it } shouldBe true
        lore.none { "жетонов" in it } shouldBe true
    }

    @Test
    fun `bundled currency remains portable fallback and exposes separate upgrade footer rows`() {
        val config = bundledMountConfigForCurrency()

        config.guiText("common.price-tokens", "") shouldBe
            "<color:#8c8c8c>Цена: <color:#ffacd5><price> <currency>"
        config.guiText("currencies.tokens", "<missing>") shouldBe "<color:#ffacd5>жетонов"
        config.guiText("list.mount-upgrades-level-footer", "") shouldBe
            "<color:#92bed8>ЛКМ<color:#e6fff3> — повышение уровня"
        config.guiText("list.mount-upgrades-ability-footer", "") shouldBe
            "<color:#ff9f0f>ПКМ<color:#e6fff3> — покупка улучшений"
    }

    @Test
    fun `passenger mounts show capacity and the boarding action in list and detail lore`() {
        val config = bundledMountConfigForCurrency()
        val mount = checkNotNull(config.catalog()["happy_ghast"])
        val items = MountGuiItems({ config }, mockk(relaxed = true))
        val profile = MountProfile(level = 1, glowOwned = false, glowDisabled = false)

        val listLore =
            checkNotNull(items.mountIcon(mount, profile).itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
        val detailLore =
            checkNotNull(items.mountIcon(mount, profile, detailed = true).itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)

        listLore.any { "Пассажирские места: 2" in it } shouldBe true
        listLore.any { "ПКМ по маунту — сесть пассажиром" in it } shouldBe true
        detailLore.any { "Особенность: Пассажирские места: 2" in it } shouldBe true
        detailLore.any { "ПКМ по маунту — сесть пассажиром" in it } shouldBe true
    }

    @Test
    fun `upgrade lore keeps coin and token prices separate and locks paid sizes by level`() {
        val base = testMount()
        val skin = base.skins.first().copy(id = "premium", price = 150.0, currency = "tokens")
        val paidSize = MountSizeOptionDefinition(
            id = "keychain",
            displayName = "Крошечный",
            multiplier = 0.1,
            minimumLevel = 1,
            grantOnly = true,
            price = 40.0,
            currency = "tokens",
        )
        val lockedSize = paidSize.copy(id = "colossal", minimumLevel = 3, price = 100.0)
        val mount = base.copy(
            currency = "tokens",
            glowPrice = 5_000.0,
            glowCurrency = "vault",
            skins = listOf(skin),
            sizeOptions = listOf(MountSizeOptionDefinition("standard", "Обычный", 1.0), paidSize, lockedSize),
        )
        val runtimeConfig = mockk<MountModuleConfig> {
            every { purchasesEnabled } returns true
            every { guiText(any(), any()) } answers {
                when (firstArg<String>()) {
                    "common.price-vault" -> "COIN <price> <currency>"
                    "common.price-tokens" -> "TOKEN <price> <currency>"
                    "currencies.vault" -> "монет"
                    "currencies.tokens" -> "жетонов"
                    else -> secondArg()
                }
            }
            every { guiLines(any(), any()) } answers { secondArg() }
            every { guiStyle(any()) } returns MountGuiItemStyle()
        }
        val items = MountGuiItems({ runtimeConfig }, mockk(relaxed = true))
        val profile = MountProfile(level = 1, glowOwned = false, glowDisabled = false)
        fun lore(item: org.bukkit.inventory.ItemStack) =
            checkNotNull(item.itemMeta?.lore()).map(PlainTextComponentSerializer.plainText()::serialize)

        lore(items.glowItem(mount, profile)).any { "COIN" in it && "монет" in it } shouldBe true
        lore(items.skinItem(mount, profile, skin.id)).any { "TOKEN" in it && "жетонов" in it } shouldBe true
        lore(items.sizeTuningItem(mount, profile, paidSize)).any { "TOKEN" in it && "жетонов" in it } shouldBe true

        val lockedLore = lore(items.sizeTuningItem(mount, profile, lockedSize))
        lockedLore.any { "Откроется на уровне 3" in it } shouldBe true
        lockedLore.none { "TOKEN" in it || "открыть покупку" in it } shouldBe true
    }
}

private fun bundledMountConfigForCurrency(): MountModuleConfig {
    val dataPath = Files.createTempDirectory("arc-mounts-currency-")
    val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
    val resource = checkNotNull(MountGuiItemsCurrencyTest::class.java.getResourceAsStream("/modules/mounts.yml"))
    resource.use { input -> Files.newOutputStream(moduleDir.resolve("mounts.yml")).use(input::copyTo) }
    return MountModuleConfig.load(dataPath)
}
