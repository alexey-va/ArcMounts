package ru.arc.mounts

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import java.time.Duration
import java.util.concurrent.CompletableFuture

class MountGuiControllerTest : TestBase() {
    @Test
    fun `escape from nested screen restores actual list parent`() {
        val mounts = (0 until 60).map { index -> testMount().copy(id = "escape-$index", displayName = "Маунт $index") }
        val ownership = mockk<MountOwnership> {
            every { profile(any(), any()) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(mounts) },
            ownership = ownership,
            wallet = mockk(relaxed = true),
            purchases = mockk(relaxed = true),
            sessions = mockk(relaxed = true),
        )
        val player = server.addPlayer("EscapeRider")
        controller.start()
        try {
            controller.openList(player)
            controller.onClick(clickEvent(player.openInventory, 53))
            controller.onClick(clickEvent(player.openInventory, 0, ClickType.RIGHT))
            controller.onClick(clickEvent(player.openInventory, 24))
            player.openInventory.topInventory.size shouldBe 54
            server.scheduler.performOneTick()
            player.closeInventory(InventoryCloseEvent.Reason.PLAYER)
            server.scheduler.performOneTick()
            plainName(player.openInventory.topInventory.getItem(24)) shouldBe "Настройки"
            server.scheduler.performOneTick()
            player.closeInventory(InventoryCloseEvent.Reason.PLAYER)
            server.scheduler.performOneTick()
            plainName(player.openInventory.topInventory.getItem(0)) shouldBe "Маунт 45"
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `locked mount cannot open transfer packing from detail`() {
        val mount = testMount()
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(0, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val transfer = mockk<MountTransferController>(relaxed = true) {
            every { detailSlot } returns 38
        }
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(listOf(mount)) },
            ownership = ownership,
            wallet = mockk(relaxed = true),
            purchases = mockk(relaxed = true),
            sessions = mockk(relaxed = true),
            transfers = { transfer },
        )
        val player = server.addPlayer("LockedTransferRider")
        controller.start()
        try {
            controller.openDetail(player, mount.id)
            controller.onClick(clickEvent(player.openInventory, 38))
            verify(exactly = 0) { transfer.confirm(any(), any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `detail menu selects a favorite and recovers one reusable whistle`() {
        val mount = testMount()
        var favoriteMountId: String? = null
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } answers { favoriteMountId }
            every { setFavoriteMount(any(), mount) } answers {
                favoriteMountId = mount.id
                CompletableFuture.completedFuture(null)
            }
        }
        val config = mockk<MountModuleConfig> {
            every { detailTitle } returns "Маунт: <mount>"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { quickSummonSneakSwapHands } returns true
            every { quickSummonWhistle } returns true
            every { tuning } returns
                MountTuningDefinition(
                    speedPercentages = listOf(50, 65, 80, 90, 100),
                    walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                    walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
                )
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 1_000_000L },
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("FavoriteChooser")

        controller.start()
        try {
            controller.openDetail(player, mount.id)
            plainName(player.openInventory.topInventory.getItem(36)) shouldBe "Назад"
            plainName(player.openInventory.topInventory.getItem(38)) shouldBe " "
            plainName(player.openInventory.topInventory.getItem(13)) shouldBe "Выбрать любимым"
            plainName(player.openInventory.topInventory.getItem(42)) shouldBe "Свисток недоступен"

            controller.onClick(clickEvent(player.openInventory, 13))
            server.scheduler.performOneTick()
            favoriteMountId shouldBe mount.id
            plainName(player.openInventory.topInventory.getItem(13)) shouldBe "Любимый маунт"
            plainName(player.openInventory.topInventory.getItem(42)) shouldBe "Получить свисток"

            controller.onClick(clickEvent(player.openInventory, 42))
            player.inventory.contents.count { it?.type == Material.GOAT_HORN } shouldBe 1
            controller.onClick(clickEvent(player.openInventory, 42))
            player.inventory.contents.count { it?.type == Material.GOAT_HORN } shouldBe 1
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `collection prioritizes unlocked mounts while preserving catalog order inside each group`() {
        val firstLocked = testMount().copy(id = "locked-first", displayName = "Первый закрытый")
        val firstOwned = testMount().copy(id = "owned-first", displayName = "Первый полученный")
        val secondLocked = testMount().copy(id = "locked-second", displayName = "Второй закрытый")
        val secondOwned = testMount().copy(id = "owned-second", displayName = "Второй полученный")
        val profiles = mapOf(
            firstLocked.id to MountProfile(0, false, false),
            firstOwned.id to MountProfile(1, false, false),
            secondLocked.id to MountProfile(0, false, false),
            secondOwned.id to MountProfile(2, false, false),
        )

        prioritizeUnlockedMounts(listOf(firstLocked, firstOwned, secondLocked, secondOwned)) {
            checkNotNull(profiles[it.id])
        }.map(MountDefinition::id).shouldContainExactly(
            "owned-first",
            "owned-second",
            "locked-first",
            "locked-second",
        )
    }

    @Test
    fun `collection renders the available mount first with calm separated lore`() {
        val locked = testMount().copy(id = "locked", displayName = "Закрытый")
        val owned = testMount().copy(id = "owned", displayName = "Полученный")
        val tuning =
            MountTuningDefinition(
                speedPercentages = listOf(50, 65, 80, 90, 100),
                walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
            )
        val ownership = mockk<MountOwnership> {
            every { profile(any(), locked) } returns MountProfile(0, false, false)
            every { profile(any(), owned) } returns MountProfile(2, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val config = mockk<MountModuleConfig> {
            every { listTitle } returns "Коллекция маунтов"
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(locked, owned)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 1_000_000L },
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("LoreRider")

        controller.start()
        try {
            controller.openList(player)

            val first = checkNotNull(player.openInventory.topInventory.getItem(0))
            plainName(first) shouldBe "Полученный"
            val lore = checkNotNull(first.itemMeta.lore()).map(PlainTextComponentSerializer.plainText()::serialize)
            lore.none { "●" in it } shouldBe true
            lore.count(String::isEmpty).shouldBeGreaterThanOrEqual(2)
            lore.filter(String::isNotEmpty).first() shouldBe "✔ Получен"
            player.openInventory.topInventory.getItem(4) shouldBe null
            plainName(player.openInventory.topInventory.getItem(53)) shouldBe " "
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `collection card explains innate abilities and active behaviors`() {
        val base = testMount()
        val mount =
            base.copy(
                abilities =
                    MountAbilities(
                        passives =
                            listOf(
                                MountPassiveAbilityDefinition(
                                    "armor",
                                    "Живая броня",
                                    MountAbilityEffect.RESISTANCE,
                                    description = listOf("Снижает входящий урон."),
                                ),
                            ),
                    ),
                behaviors =
                    listOf(
                        MountTrampleBehavior(
                            "trample",
                            "Топот",
                            description = listOf("Бьёт врагов под корпусом."),
                        ),
                    ),
            )
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val controller =
            mountGuiController(
                configProvider = {
                    interactionConfig(
                        MountTuningDefinition(
                            listOf(50, 100),
                            listOf(110, 200, 400),
                            listOf(110, 200, 400),
                        ),
                    )
                },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("FeatureLoreRider")

        controller.start()
        try {
            controller.openList(player)
            val lore =
                    checkNotNull(player.openInventory.topInventory.getItem(0)?.itemMeta?.lore())
                    .map(PlainTextComponentSerializer.plainText()::serialize)

            lore.contains("Особенности") shouldBe true
            lore.contains("• Живая броня") shouldBe true
            lore.contains("  Снижает входящий урон.") shouldBe true
            lore.contains("• Топот") shouldBe true
            lore.contains("  Бьёт врагов под корпусом.") shouldBe true
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `collection shows every unowned mount with acquisition details`() {
        val pricedUnowned = testMount().copy(
            id = "priced",
            displayName = "Доступный",
            acquisition = "Гектор во дворе маунтов на спавне",
        )
        val lockedBase = testMount().copy(acquisition = "Гектор во дворе маунтов на спавне")
        val trulyLocked =
            lockedBase.copy(
                id = "truly-locked",
                displayName = "Закрытый",
                levels = lockedBase.levels.mapIndexed { index, level -> if (index == 0) level.copy(price = null) else level },
            )
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))
        val config = interactionConfig(tuning)
        val ownership = mockk<MountOwnership> {
            every { profile(any(), pricedUnowned) } returns MountProfile(0, false, false)
            every { profile(any(), trulyLocked) } returns MountProfile(0, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(pricedUnowned, trulyLocked)) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("AcquisitionRider")

        controller.start()
        try {
            controller.openList(player)
            val pricedItem = checkNotNull(player.openInventory.topInventory.getItem(0))
            val lockedItem = checkNotNull(player.openInventory.topInventory.getItem(1))
            pricedItem.type shouldBe Material.RED_DYE
            lockedItem.type shouldBe Material.RED_DYE
            plainName(pricedItem) shouldBe "Доступный"
            plainName(lockedItem) shouldBe "Закрытый"
            pricedItem.itemMeta?.lore()?.map(PlainTextComponentSerializer.plainText()::serialize).orEmpty()
                .contains("Получение: Гектор во дворе маунтов на спавне") shouldBe true
            pricedItem.itemMeta?.lore()?.map(PlainTextComponentSerializer.plainText()::serialize).orEmpty()
                .any { it.startsWith("Цена:") && it.contains("монет") } shouldBe true
            lockedItem.itemMeta?.lore()?.map(PlainTextComponentSerializer.plainText()::serialize).orEmpty()
                .contains("Получение: Гектор во дворе маунтов на спавне") shouldBe true
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `collection lists every locked catalog mount without a spawn egg`() {
        val mounts =
            (0 until 60).map { index ->
                testMount().copy(
                    id = "locked-$index",
                    displayName = "Закрытый $index",
                    acquisition = "Гектор во дворе маунтов на спавне",
                )
            }
        val config = interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400)))
        val ownership = mockk<MountOwnership> {
            every { profile(any(), any()) } returns MountProfile(0, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(mounts) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("LockedCatalogRider")

        controller.start()
        try {
            controller.openList(player)
            val contentSlots = (0..44).toList()
            repeat(2) { page ->
                val inventory = player.openInventory.topInventory
                val cards = contentSlots.mapNotNull { inventory.getItem(it) }.filter { it.type == Material.RED_DYE }
                cards.isNotEmpty() shouldBe true
                cards.forEach { card ->
                    card.type shouldBe Material.RED_DYE
                    card.type shouldNotBe Material.BEE_SPAWN_EGG
                    checkNotNull(card.itemMeta?.lore()).map(PlainTextComponentSerializer.plainText()::serialize).any {
                        it.contains("Гектор во дворе маунтов на спавне")
                    } shouldBe true
                }
                // Empty content cells are genuinely empty; no category filler is painted over them.
                contentSlots.count { inventory.getItem(it) == null } shouldBe if (page == 0) 0 else 30
                inventory.getItem(0) shouldNotBe null
                if (page == 0) controller.onClick(clickEvent(player.openInventory, 53))
            }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `mount list navigation uses gray ItemsAdder triangle models`() {
        val mounts = (0 until 60).map { index -> testMount().copy(id = "triangle-$index", displayName = "Маунт $index") }
        val ownership = mockk<MountOwnership> {
            every { profile(any(), any()) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val controller =
            mountGuiController(
                configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
                catalogProvider = { MountCatalog(mounts) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("TriangleRider")

        controller.start()
        try {
            controller.openList(player)
            player.openInventory.topInventory.getItem(53)?.customModelDataOrNull shouldBe 11012
            player.openInventory.topInventory.getItem(53)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
            controller.onClick(clickEvent(player.openInventory, 53))
            player.openInventory.topInventory.getItem(45)?.customModelDataOrNull shouldBe 11013
            player.openInventory.topInventory.getItem(45)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `catalog grids keep content empty while action screens fill their backgrounds`() {
        val mount = testMount().copy(displayName = "Вредина")
        val profile = MountProfile(1, false, false)
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns profile
            every { favoriteMountId(any()) } returns null
        }
        val controller =
            mountGuiController(
                configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("LayoutRider")

        fun assertBottomOnly(vararg contentSlots: Int) {
            val inventory = player.openInventory.topInventory
            val expectedContent = contentSlots.toSet()
            (0 until inventory.size - 9)
                .filterNot(expectedContent::contains)
                .forEach { slot -> inventory.getItem(slot) shouldBe null }
            (inventory.size - 9 until inventory.size).forEach { slot ->
                inventory.getItem(slot) shouldNotBe null
            }
        }

        fun assertDetailBackground(vararg contentSlots: Int) {
            val inventory = player.openInventory.topInventory
            val expectedContent = contentSlots.toSet()
            (0 until inventory.size).filterNot(expectedContent::contains).forEach { slot ->
                inventory.getItem(slot)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
            }
        }

        controller.start()
        try {
            controller.openList(player)
            assertBottomOnly(0)
            controller.onClick(clickEvent(player.openInventory, 0, ClickType.RIGHT))
            assertDetailBackground(4, 13, 20, 22, 24, 36, 42)
            controller.onClick(clickEvent(player.openInventory, 20))
            player.openInventory.topInventory.contents.all { it != null } shouldBe true
            plainName(player.openInventory.topInventory.getItem(11)) shouldBe "Уровень 1 · открыт"
            plainName(player.openInventory.topInventory.getItem(13)) shouldBe "Уровень 2 · доступен"
            plainName(player.openInventory.topInventory.getItem(15)) shouldBe "Уровень 3 · закрыт"
            player.openInventory.topInventory.getItem(12)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
            controller.onClick(clickEvent(player.openInventory, 22))
            player.openInventory.topInventory.size shouldBe 27
            player.openInventory.topInventory.getItem(22)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
            controller.onClick(clickEvent(player.openInventory, 18))
            assertDetailBackground(4, 13, 20, 22, 24, 36, 42)
            controller.onClick(clickEvent(player.openInventory, 24))
            player.openInventory.topInventory.size shouldBe 54
            controller.onClick(clickEvent(player.openInventory, 2))
            assertBottomOnly(0, 1)
            controller.onClick(clickEvent(player.openInventory, 49))
            player.openInventory.topInventory.size shouldBe 54
            plainName(player.openInventory.topInventory.getItem(45)) shouldBe "Назад"
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `owned service lists fit their cards and keep controls on their bottom row`() {
        var mounts = listOf(testMount())
        var ownedCount = 0
        val ownership = mockk<MountOwnership> {
            every { profile(any(), any()) } answers { MountProfile(if (ownedCount > 0) 1 else 0, false, false) }
            every { favoriteMountId(any()) } returns null
        }
        val transfer = mockk<MountTransferController>(relaxed = true)
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(mounts) },
            ownership = ownership,
            wallet = mockk(relaxed = true),
            purchases = mockk(relaxed = true),
            sessions = mockk(relaxed = true),
            transfers = { transfer },
        )
        val player = server.addPlayer("CompactRider")
        controller.start()
        try {
            listOf(controller::openUpgrades, controller::openTrade).forEach { open ->
                listOf(0 to 18, 1 to 18, 9 to 18, 10 to 27, 18 to 27, 19 to 36, 45 to 54, 46 to 54).forEach { (count, size) ->
                    ownedCount = count
                    mounts = (0 until count.coerceAtLeast(1)).map { index -> testMount().copy(id = "compact-$index", displayName = "Маунт $index") }
                    open(player)
                    val inventory = player.openInventory.topInventory
                    inventory.size shouldBe size
                    plainName(inventory.getItem(size - 5)) shouldBe "Фильтр коллекции"
                    (size - 9 until size).forEach { inventory.getItem(it) shouldNotBe null }
                    if (count == 0) plainName(inventory.getItem(4)) shouldBe "Коллекция пока пуста"
                    if (count == 1) (1..8).forEach { inventory.getItem(it) shouldBe null }
                    if (count == 46) {
                        controller.onClick(clickEvent(player.openInventory, 53))
                        player.openInventory.topInventory.size shouldBe 18
                        plainName(player.openInventory.topInventory.getItem(0)) shouldBe "Маунт 45"
                        plainName(player.openInventory.topInventory.getItem(9)) shouldBe "Предыдущая страница"
                        controller.onClick(clickEvent(player.openInventory, 9))
                        player.openInventory.topInventory.size shouldBe 54
                        plainName(player.openInventory.topInventory.getItem(0)) shouldBe "Маунт 0"
                    }
                }
            }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `upgrade left and right clicks keep separate destinations and restore compact filtered list`() {
        val mount = testMount().copy(displayName = "Улучшенный", movement = MountMovement.FLYING)
        val ownership = mockk<MountOwnership> {
            every { profile(any(), any()) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(listOf(mount)) },
            ownership = ownership,
            wallet = mockk(relaxed = true),
            purchases = purchases,
            sessions = mockk(relaxed = true),
            merchantAllowed = { true },
        )
        val player = server.addPlayer("UpgradeDestinations")
        controller.start()
        try {
            controller.openUpgrades(player)
            val footer = checkNotNull(player.openInventory.topInventory.getItem(0)?.itemMeta?.lore()).takeLast(2)
            footer.map(PlainTextComponentSerializer.plainText()::serialize) shouldBe
                listOf("ЛКМ — повышение уровня", "ПКМ — покупка улучшений")
            val footerMarkup = footer.map(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()::serialize)
            footerMarkup[0].contains("#92bed8", ignoreCase = true) shouldBe true
            footerMarkup[1].contains("#ff9f0f", ignoreCase = true) shouldBe true
            controller.onClick(clickEvent(player.openInventory, 13))
            player.openInventory.topInventory.getItem(13)?.type shouldBe Material.FEATHER
            controller.onClick(clickEvent(player.openInventory, 0))
            player.openInventory.topInventory.size shouldBe 27
            plainName(player.openInventory.topInventory.getItem(11)) shouldBe "Уровень 1 · открыт"
            controller.onClick(clickEvent(player.openInventory, 18))
            player.openInventory.topInventory.size shouldBe 18
            player.openInventory.topInventory.getItem(13)?.type shouldBe Material.FEATHER
            controller.onClick(clickEvent(player.openInventory, 0, ClickType.RIGHT))
            player.openInventory.topInventory.size shouldBe 45
            plainName(player.openInventory.topInventory.getItem(24)) shouldBe "Настройки"
            controller.onClick(clickEvent(player.openInventory, 24))
            player.openInventory.topInventory.size shouldBe 54
            plainName(player.openInventory.topInventory.getItem(45)) shouldBe "Назад"
            controller.onClick(clickEvent(player.openInventory, 45))
            player.openInventory.topInventory.size shouldBe 45
            controller.onClick(clickEvent(player.openInventory, 36))
            player.openInventory.topInventory.size shouldBe 18
            player.openInventory.topInventory.getItem(13)?.type shouldBe Material.FEATHER
            verify(exactly = 0) { purchases.purchaseLevel(any(), any(), any(), any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `shop opens purchase progression for an unowned priced mount`() {
        val mount = testMount().copy(id = "priced", displayName = "Доступный")
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(0, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(listOf(mount)) },
            ownership = ownership,
            wallet = mockk(relaxed = true),
            purchases = mockk(relaxed = true),
            sessions = mockk(relaxed = true),
            merchantAllowed = { true },
        )
        val player = server.addPlayer("ShopRider")
        controller.start()
        try {
            controller.openShop(player)
            plainName(player.openInventory.topInventory.getItem(0)) shouldBe "Доступный"
            controller.onClick(clickEvent(player.openInventory, 0))
            plainName(player.openInventory.topInventory.getItem(11)).startsWith("Уровень 1") shouldBe true
            controller.onClick(clickEvent(player.openInventory, 11))
            player.openInventory.topInventory.size shouldBe 27
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `collection context never opens paid confirmation even beside merchant`() {
        val mount = testMount().copy(id = "collection-paid", displayName = "Коллекционный")
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(listOf(mount)) },
            ownership = ownership,
            wallet = mockk(relaxed = true),
            purchases = purchases,
            sessions = mockk(relaxed = true),
            merchantAllowed = { true },
        )
        val player = server.addPlayer("CollectionPaidRider")

        controller.start()
        try {
            controller.openDetail(player, mount.id)
            controller.onClick(clickEvent(player.openInventory, 20))
            controller.onClick(clickEvent(player.openInventory, 13))
            player.openInventory.topInventory.size shouldBe 27
            verify(exactly = 0) { purchases.purchaseLevel(any(), mount, 2, any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `upgrades context beside merchant opens paid confirmation`() {
        val mount = testMount().copy(id = "upgrades-paid", displayName = "Улучшенный")
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(listOf(mount)) },
            ownership = ownership,
            wallet = mockk(relaxed = true),
            purchases = mockk(relaxed = true),
            sessions = mockk(relaxed = true),
            merchantAllowed = { true },
        )
        val player = server.addPlayer("UpgradesPaidRider")

        controller.start()
        try {
            controller.openUpgrades(player)
            controller.onClick(clickEvent(player.openInventory, 0))
            controller.onClick(clickEvent(player.openInventory, 13))
            player.openInventory.topInventory.size shouldBe 27
            plainName(player.openInventory.topInventory.getItem(13)).startsWith("Уровень 2") shouldBe true
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `remote owned management never opens paid confirmation without the merchant`() {
        val mount = testMount()
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(listOf(mount)) },
            ownership = ownership,
            wallet = mockk(relaxed = true),
            purchases = purchases,
            sessions = mockk(relaxed = true),
            merchantAllowed = { false },
        )
        val player = server.addPlayer("RemoteManagementRider")

        controller.start()
        try {
            controller.openDetail(player, mount.id)
            controller.onClick(clickEvent(player.openInventory, 20))
            controller.onClick(clickEvent(player.openInventory, 13))
            player.openInventory.topInventory.size shouldBe 27

            controller.onClick(clickEvent(player.openInventory, 18))
            controller.onClick(clickEvent(player.openInventory, 31))
            controller.onClick(clickEvent(player.openInventory, 31))
            player.openInventory.topInventory.size shouldBe 45
            controller.onClick(clickEvent(player.openInventory, 40))
            player.openInventory.topInventory.size shouldBe 45

            controller.onClick(clickEvent(player.openInventory, 24))
            player.openInventory.topInventory.size shouldBe 54
            controller.onClick(clickEvent(player.openInventory, 40))
            controller.onClick(clickEvent(player.openInventory, 6))
            player.openInventory.topInventory.size shouldBe 54
            controller.onClick(clickEvent(player.openInventory, 2))
            player.openInventory.topInventory.size shouldBe 54
            controller.onClick(clickEvent(player.openInventory, 1))
            player.openInventory.topInventory.size shouldBe 54
            controller.onClick(clickEvent(player.openInventory, 49))
            player.openInventory.topInventory.size shouldBe 54
            verify(exactly = 0) { purchases.purchaseLevel(any(), any(), any(), any()) }
            verify(exactly = 0) { purchases.purchaseAbility(any(), any(), any(), any()) }
            verify(exactly = 0) { purchases.purchaseSkin(any(), any(), any(), any()) }
            verify(exactly = 0) { purchases.purchaseGlow(any(), any(), any()) }
            verify(exactly = 0) { purchases.purchaseSize(any(), any(), any(), any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `stale confirmation returns to its parent after merchant gate rejection`() {
        val mount = testMount()
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        var atMerchant = true
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(listOf(mount)) },
            ownership = ownership,
            wallet = mockk {
                every { walletForCurrency("vault") } answers { self as MountWallet }
                every { available } returns true
                every { balanceMinor(any()) } returns 1_000_000L
            },
            purchases = purchases,
            sessions = mockk(relaxed = true),
            merchantAllowed = { atMerchant },
        )
        val player = server.addPlayer("StaleConfirmationRider")

        controller.start()
        try {
            controller.openShop(player)
            controller.onClick(clickEvent(player.openInventory, 0))
            controller.onClick(clickEvent(player.openInventory, 20))
            controller.onClick(clickEvent(player.openInventory, 13))
            player.openInventory.topInventory.size shouldBe 27
            atMerchant = false
            controller.onClick(clickEvent(player.openInventory, 15))
            player.openInventory.topInventory.size shouldBe 27
            verify(exactly = 0) { purchases.purchaseLevel(any(), mount, 2, any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `reused shop collection rechecks merchant before opening a paid mount`() {
        val mount = testMount().copy(id = "priced", displayName = "Доступный")
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(0, false, false)
            every { favoriteMountId(any()) } returns null
        }
        var atMerchant = true
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(listOf(mount)) },
            ownership = ownership,
            wallet = mockk(relaxed = true),
            purchases = mockk(relaxed = true),
            sessions = mockk(relaxed = true),
            merchantAllowed = { atMerchant },
        )
        val player = server.addPlayer("StaleShopRider")

        controller.start()
        try {
            controller.openShop(player)
            atMerchant = false
            controller.onClick(clickEvent(player.openInventory, 0))
            player.openInventory.topInventory.size shouldBe 54
            plainName(player.openInventory.topInventory.getItem(0)) shouldBe "Доступный"
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `category switch uses a distinct resource-pack icon for every mount type`() {
        val walking = testMount().copy(id = "walking", movement = MountMovement.WALKING)
        val flying = testMount().copy(id = "flying", movement = MountMovement.FLYING)
        val swimming = testMount().copy(id = "swimming", movement = MountMovement.SWIMMING)
        val tuning =
            MountTuningDefinition(
                speedPercentages = listOf(50, 65, 80, 90, 100),
                walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
            )
        val styles =
            mapOf(
                MountGuiItemRole.CATEGORY_ALL to MountGuiItemStyle(Material.COMPASS, 11023),
                MountGuiItemRole.CATEGORY_FLYING to MountGuiItemStyle(Material.FEATHER, 11024),
                MountGuiItemRole.CATEGORY_WALKING to MountGuiItemStyle(Material.SADDLE, 11025),
                MountGuiItemRole.CATEGORY_SWIMMING to MountGuiItemStyle(Material.HEART_OF_THE_SEA, 11026),
            )
        val ownership = mockk<MountOwnership> {
            every { profile(any(), any()) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val config = mockk<MountModuleConfig> {
            every { listTitle } returns "Коллекция маунтов"
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } answers { styles[firstArg()] ?: MountGuiItemStyle() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(walking, flying, swimming)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 1_000_000L },
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("IconRider")

        fun categoryIcon() = checkNotNull(player.openInventory.topInventory.getItem(49))

        controller.openList(player)
        categoryIcon().type shouldBe Material.COMPASS
        categoryIcon().customModelDataOrNull shouldBe 11023

        controller.onClick(clickEvent(player.openInventory, 49))
        categoryIcon().type shouldBe Material.FEATHER
        categoryIcon().customModelDataOrNull shouldBe 11024

        controller.onClick(clickEvent(player.openInventory, 49))
        categoryIcon().type shouldBe Material.SADDLE
        categoryIcon().customModelDataOrNull shouldBe 11025

        controller.onClick(clickEvent(player.openInventory, 49))
        categoryIcon().type shouldBe Material.HEART_OF_THE_SEA
        categoryIcon().customModelDataOrNull shouldBe 11026
    }

    @Test
    fun `detail routes settings and levels independently and free tuning stays in settings`() {
        val mount =
            testMount().copy(
                movement = MountMovement.WALKING,
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("keychain", "Брелок ×0.1", 0.1, grantOnly = true),
                        MountSizeOptionDefinition("standard", "Обычный ×1", 1.0),
                        MountSizeOptionDefinition("huge", "Огромный ×2", 2.0, minimumLevel = 2),
                        MountSizeOptionDefinition("absurd", "Абсурдный ×3", 3.0, minimumLevel = 3),
                        MountSizeOptionDefinition("colossal", "Колоссальный ×10", 10.0, grantOnly = true),
                    ),
            )
        val tuning =
            MountTuningDefinition(
                speedPercentages = listOf(50, 65, 80, 90, 100),
                walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
            )
        var profile =
            MountProfile(
                level = 2,
                glowOwned = false,
                glowDisabled = false,
                ownedSizeIds = setOf("keychain"),
                selectedSpeedPercentage = 65,
                selectedStepHeightHundredths = 150,
                selectedSizeId = "keychain",
            )
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } answers { profile }
            every { favoriteMountId(any()) } returns null
        }
        val config = mockk<MountModuleConfig> {
            every { detailTitle } returns "Маунт: <mount>"
            every { progressionTitle } returns "Развитие: <mount>"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { quickSummonWhistle } returns true
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        every { purchases.setSpeedTuning(any(), mount, tuning, 90, any()) } answers {
            lastArg<(MountPurchaseResult) -> Unit>()(MountPurchaseResult.Success)
        }
        every { purchases.setSizeTuning(any(), mount, "standard", any()) } answers {
            profile = profile.copy(selectedSizeId = "standard")
            lastArg<(MountPurchaseResult) -> Unit>()(MountPurchaseResult.Success)
        }
        every { purchases.setSizeTuning(any(), mount, "keychain", any()) } answers {
            profile = profile.copy(selectedSizeId = "keychain")
            lastArg<(MountPurchaseResult) -> Unit>()(MountPurchaseResult.Success)
        }
        every { purchases.setRiderViewAutoHide(any(), mount, true, any()) } answers {
            lastArg<(MountPurchaseResult) -> Unit>()(MountPurchaseResult.Success)
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 1_000_000L },
                purchases = purchases,
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("TuningRider")

        controller.start()
        try {
            controller.openDetail(player, mount.id)
            plainName(player.openInventory.topInventory.getItem(20)) shouldBe "Уровни"
            plainName(player.openInventory.topInventory.getItem(24)) shouldBe "Настройки"
            controller.onClick(clickEvent(player.openInventory, 31))
            controller.onClick(clickEvent(player.openInventory, 40))
            player.openInventory.topInventory.size shouldBe 45
            controller.onClick(clickEvent(player.openInventory, 24))
            player.openInventory.topInventory.size shouldBe 54
            plainName(player.openInventory.topInventory.getItem(40)) shouldBe "Ночное зрение"

            plainName(player.openInventory.topInventory.getItem(12)) shouldBe "Скорость: 65%"
            player.openInventory.topInventory.getItem(12)?.itemMeta?.enchantmentGlintOverride shouldBe true
            checkNotNull(player.openInventory.topInventory.getItem(12)?.itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .none { "▶" in it } shouldBe true
            plainName(player.openInventory.topInventory.getItem(21)) shouldBe "Подъём: 1.50 блока"
            player.openInventory.topInventory.getItem(21)?.itemMeta?.enchantmentGlintOverride shouldBe true
            plainName(player.openInventory.topInventory.getItem(24)) shouldBe "Подъём: 4.00 блока"
            plainName(player.openInventory.topInventory.getItem(29)) shouldBe "Размер: брелок ×0.1"
            plainName(player.openInventory.topInventory.getItem(30)) shouldBe "Размер: обычный ×1"
            plainName(player.openInventory.topInventory.getItem(31)) shouldBe "Размер: огромный ×2"
            plainName(player.openInventory.topInventory.getItem(32)) shouldBe "Размер: абсурдный ×3"
            plainName(player.openInventory.topInventory.getItem(33)) shouldBe "Размер: колоссальный ×10"
            player.openInventory.topInventory.getItem(33)?.type shouldBe Material.RED_DYE
            checkNotNull(player.openInventory.topInventory.getItem(33)?.itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .any { it == "Особый размер" } shouldBe true
            plainName(player.openInventory.topInventory.getItem(49)) shouldBe "Корпус: всегда виден"

            controller.onClick(clickEvent(player.openInventory, 30))
            verify(exactly = 1) { purchases.setSizeTuning(any(), mount, "standard", any()) }
            controller.onClick(clickEvent(player.openInventory, 32))
            controller.onClick(clickEvent(player.openInventory, 33))
            verify(exactly = 1) { purchases.setSizeTuning(any(), any(), any(), any()) }

            controller.onClick(clickEvent(player.openInventory, 29))
            verify(exactly = 1) { purchases.setSizeTuning(any(), mount, "keychain", any()) }

            controller.onClick(clickEvent(player.openInventory, 49))
            verify(exactly = 1) { purchases.setRiderViewAutoHide(any(), mount, true, any()) }

            controller.onClick(clickEvent(player.openInventory, 24))
            verify(exactly = 0) { purchases.setStepHeightTuning(any(), any(), any(), any(), any()) }

            controller.onClick(clickEvent(player.openInventory, 14))

            verify(exactly = 1) { purchases.setSpeedTuning(any(), mount, tuning, 90, any()) }
            player.openInventory.topInventory.size shouldBe 54
            verify(exactly = 0) { purchases.purchaseGlow(any(), any(), any()) }
            verify(exactly = 0) { purchases.purchaseAbility(any(), any(), any(), any()) }
            verify(exactly = 0) { purchases.purchaseSkin(any(), any(), any(), any()) }
            verify(exactly = 0) { purchases.purchaseSize(any(), any(), any(), any()) }

            controller.onClick(clickEvent(player.openInventory, 45))
            player.openInventory.topInventory.size shouldBe 45
            controller.onClick(clickEvent(player.openInventory, 20))
            plainName(player.openInventory.topInventory.getItem(13)) shouldBe "Уровень 2 · открыт"
            player.openInventory.topInventory.getItem(22)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
            controller.onClick(clickEvent(player.openInventory, 22))
            player.openInventory.topInventory.size shouldBe 27
            controller.onClick(clickEvent(player.openInventory, 18))
            player.openInventory.topInventory.size shouldBe 45
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `ravager skins show only real deltas and localized visible trails`() {
        val trail =
            MountTrailDefinition(
                particle = "END_ROD",
                displayName = "Звёздный след",
                count = 3,
                backOffset = 0.28,
                heightRatio = 0.38,
            )
        val ravager =
            testMount().copy(
                id = "ravager",
                movement = MountMovement.WALKING,
                entityType = "RAVAGER",
                displayName = "Разоритель",
                appearance = MountAppearance(scale = 0.82),
                skins =
                    listOf(
                        MountSkinDefinition(
                            id = "starlight",
                            displayName = "Звёздный комплект",
                            iconMaterial = "AMETHYST_SHARD",
                            price = 2_500_000.0,
                            appearance = MountAppearance(scale = 0.82),
                            trail = trail,
                        ),
                    ),
            )
        val profile = MountProfile(level = 3, glowOwned = false, glowDisabled = false, ownedSkinIds = setOf("starlight"))
        val ownership = mockk<MountOwnership> {
            every { profile(any(), ravager) } returns profile
            every { favoriteMountId(any()) } returns null
        }
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))
        val config = mockk<MountModuleConfig> {
            every { detailTitle } returns "Разоритель"
            every { skinsTitle } returns "Облики маунта"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { quickSummonWhistle } returns true
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(ravager)) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("RavagerStylist")

        controller.start()
        try {
            controller.openDetail(player, ravager.id)
            controller.onClick(clickEvent(player.openInventory, 24))
            controller.onClick(clickEvent(player.openInventory, 2))

            val classicLore = checkNotNull(player.openInventory.topInventory.getItem(0)?.itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
            classicLore.any { "0.82" in it || "взрослый" in it } shouldBe false
            classicLore.first() shouldBe "Базовый облик без следа."

            val starlightLore = checkNotNull(player.openInventory.topInventory.getItem(1)?.itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
            starlightLore.any { it == "След: Звёздный след" } shouldBe true
            starlightLore.any { "END_ROD" in it || "0.82" in it || "взрослый" in it } shouldBe false

            controller.onClick(clickEvent(player.openInventory, 49))
            player.openInventory.topInventory.size shouldBe 54
            plainName(player.openInventory.topInventory.getItem(45)) shouldBe "Назад"
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `purchase confirmation owns the balance decision and insufficient accept is a no-op`() {
        val mount = testMount()
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))
        val config = mockk<MountModuleConfig> {
            every { detailTitle } returns "Маунт"
            every { confirmTitle } returns "Покупка маунта"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { quickSummonWhistle } returns true
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 500_000L },
                purchases = purchases,
                sessions = mockk(relaxed = true),
                merchantAllowed = { true },
            )
        val player = server.addPlayer("CarefulBuyer")

        controller.start()
        try {
            controller.openShop(player)
            controller.onClick(clickEvent(player.openInventory, 0))
            controller.onClick(clickEvent(player.openInventory, 24))
            controller.onClick(clickEvent(player.openInventory, 6))

            plainName(player.openInventory.topInventory.getItem(15)) shouldBe "Недостаточно средств"
            player.openInventory.topInventory.contents.all { it != null } shouldBe true
            val info = checkNotNull(player.openInventory.topInventory.getItem(13)?.itemMeta?.lore())
                .map(PlainTextComponentSerializer.plainText()::serialize)
            info.any { it.startsWith("Баланс: ") } shouldBe true
            info.any { it.startsWith("Не хватает: ") } shouldBe true

            controller.onClick(clickEvent(player.openInventory, 15))
            verify(exactly = 0) { purchases.purchaseGlow(any(), any(), any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `canceling an ability confirmation returns to settings without purchase`() {
        val mount = testMount()
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns MountProfile(1, false, false)
            every { favoriteMountId(any()) } returns null
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        val controller = mountGuiController(
            configProvider = { interactionConfig(MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))) },
            catalogProvider = { MountCatalog(listOf(mount)) },
            ownership = ownership,
            wallet = mockk {
                every { walletForCurrency("vault") } answers { self as MountWallet }
                every { balanceMinor(any()) } returns 5_000_000L
            },
            purchases = purchases,
            sessions = mockk(relaxed = true),
            merchantAllowed = { true },
        )
        val player = server.addPlayer("CancelSettingsBuyer")
        controller.start()
        try {
            controller.openShop(player)
            controller.onClick(clickEvent(player.openInventory, 0))
            controller.onClick(clickEvent(player.openInventory, 24))
            controller.onClick(clickEvent(player.openInventory, 40))
            player.openInventory.topInventory.size shouldBe 27
            controller.onClick(clickEvent(player.openInventory, 11))
            player.openInventory.topInventory.size shouldBe 54
            plainName(player.openInventory.topInventory.getItem(40)) shouldBe "Ночное зрение"
            plainName(player.openInventory.topInventory.getItem(45)) shouldBe "Назад"
            verify(exactly = 0) { purchases.purchaseAbility(any(), any(), any(), any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `detail progression skins and confirmation ignore every non-left click`() {
        val mount = testMount()
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))
        val config = interactionConfig(tuning)
        val profile = MountProfile(level = 1, glowOwned = false, glowDisabled = false)
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns profile
            every { favoriteMountId(any()) } returns null
            every { setFavoriteMount(any(), mount) } returns CompletableFuture.completedFuture(null)
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk { every { walletForCurrency("vault") } answers { self as MountWallet }; every { balanceMinor(any()) } returns 1_000_000L },
                purchases = purchases,
                sessions = mockk(relaxed = true),
                merchantAllowed = { true },
            )
        val player = server.addPlayer("ExactLeftRider")
        val nonLeftClicks =
            listOf(
                ClickType.RIGHT,
                ClickType.SHIFT_LEFT,
                ClickType.SHIFT_RIGHT,
                ClickType.NUMBER_KEY,
                ClickType.MIDDLE,
                ClickType.DOUBLE_CLICK,
            )

        controller.start()
        try {
            controller.openShop(player)
            controller.onClick(clickEvent(player.openInventory, 0))
            nonLeftClicks.forEach { controller.onClick(clickEvent(player.openInventory, 13, it)) }
            verify(exactly = 0) { ownership.setFavoriteMount(any(), any()) }
            nonLeftClicks.forEach { controller.onClick(clickEvent(player.openInventory, 24, it)) }
            player.openInventory.topInventory.size shouldBe 45

            controller.onClick(clickEvent(player.openInventory, 24))
            player.openInventory.topInventory.size shouldBe 54
            nonLeftClicks.forEach {
                controller.onClick(clickEvent(player.openInventory, 40, it))
                controller.onClick(clickEvent(player.openInventory, 6, it))
                controller.onClick(clickEvent(player.openInventory, 2, it))
                player.openInventory.topInventory.size shouldBe 54
            }

            controller.onClick(clickEvent(player.openInventory, 2))
            player.openInventory.topInventory.size shouldBe 54
            nonLeftClicks.forEach {
                controller.onClick(clickEvent(player.openInventory, 1, it))
                controller.onClick(clickEvent(player.openInventory, 49, it))
                player.openInventory.topInventory.size shouldBe 54
            }

            controller.onClick(clickEvent(player.openInventory, 49))
            player.openInventory.topInventory.size shouldBe 54
            controller.onClick(clickEvent(player.openInventory, 40))
            player.openInventory.topInventory.size shouldBe 27
            nonLeftClicks.forEach {
                controller.onClick(clickEvent(player.openInventory, 11, it))
                controller.onClick(clickEvent(player.openInventory, 15, it))
                player.openInventory.topInventory.size shouldBe 27
            }
            controller.onClick(clickEvent(player.openInventory, 11))
            player.openInventory.topInventory.size shouldBe 54
            verify(exactly = 0) { purchases.setSpeedTuning(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { purchases.purchaseAbility(any(), any(), any(), any()) }
            verify(exactly = 0) { purchases.purchaseGlow(any(), any(), any()) }
            verify(exactly = 0) { purchases.purchaseSkin(any(), any(), any(), any()) }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `list controls accept only their advertised exact clicks`() {
        val mounts =
            (0 until 60).map { index ->
                testMount().copy(id = "bee-$index", displayName = "Маунт $index")
            }
        val tuning = MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(110, 200, 400))
        val config = interactionConfig(tuning)
        val ownership = mockk<MountOwnership> {
            every { profile(any(), any()) } returns MountProfile(level = 1, glowOwned = false, glowDisabled = false)
            every { favoriteMountId(any()) } returns null
        }
        val sessions = mockk<MountSessionController>(relaxed = true) {
            every { spawn(any(), any(), any(), any()) } returns MountSpawnResult.ALREADY_RIDING
        }
        val controller =
            mountGuiController(
                configProvider = { config },
                catalogProvider = { MountCatalog(mounts) },
                ownership = ownership,
                wallet = mockk(relaxed = true),
                purchases = mockk(relaxed = true),
                sessions = sessions,
            )
        val player = server.addPlayer("ListClickRider")
        val nonLeftClicks =
            listOf(
                ClickType.RIGHT,
                ClickType.SHIFT_LEFT,
                ClickType.SHIFT_RIGHT,
                ClickType.NUMBER_KEY,
                ClickType.MIDDLE,
                ClickType.DOUBLE_CLICK,
            )
        val unsupportedCardAndFilterClicks = nonLeftClicks - ClickType.RIGHT

        controller.start()
        try {
            nonLeftClicks.forEach {
                controller.openList(player)
                controller.onClick(clickEvent(player.openInventory, 53, it))
                plainName(player.openInventory.topInventory.getItem(0)) shouldBe "Маунт 0"

                controller.onClick(clickEvent(player.openInventory, 45, it))
                player.openInventory.topInventory.size shouldBe 54
            }

            controller.openList(player)
            controller.onClick(clickEvent(player.openInventory, 53))
            plainName(player.openInventory.topInventory.getItem(0)) shouldBe "Маунт 45"
            nonLeftClicks.forEach {
                controller.onClick(clickEvent(player.openInventory, 45, it))
                plainName(player.openInventory.topInventory.getItem(0)) shouldBe "Маунт 45"
            }

            unsupportedCardAndFilterClicks.forEach {
                controller.openList(player)
                controller.onClick(clickEvent(player.openInventory, 49, it))
                player.openInventory.topInventory.getItem(49)?.itemMeta?.enchantmentGlintOverride shouldBe false

                controller.onClick(clickEvent(player.openInventory, 0, it))
                player.openInventory.topInventory.size shouldBe 54
            }
            verify(exactly = 0) { sessions.spawn(any(), any(), any(), any()) }

            controller.openList(player)
            controller.onClick(clickEvent(player.openInventory, 49, ClickType.RIGHT))
            player.openInventory.topInventory.getItem(49)?.itemMeta?.enchantmentGlintOverride shouldBe false

            controller.openList(player)
            controller.onClick(clickEvent(player.openInventory, 49))
            player.openInventory.topInventory.getItem(49)?.type shouldBe Material.FEATHER

            controller.openList(player)
            controller.onClick(clickEvent(player.openInventory, 0, ClickType.RIGHT))
            player.openInventory.topInventory.size shouldBe 45
        } finally {
            controller.shutdown()
        }
    }

    private fun clickEvent(
        view: org.bukkit.inventory.InventoryView,
        rawSlot: Int,
        clickType: ClickType = ClickType.LEFT,
    ): InventoryClickEvent {
        val action =
            when (clickType) {
                ClickType.RIGHT -> InventoryAction.PICKUP_HALF
                ClickType.SHIFT_LEFT,
                ClickType.SHIFT_RIGHT,
                -> InventoryAction.MOVE_TO_OTHER_INVENTORY
                ClickType.NUMBER_KEY -> InventoryAction.HOTBAR_SWAP
                ClickType.MIDDLE -> InventoryAction.CLONE_STACK
                ClickType.DOUBLE_CLICK -> InventoryAction.COLLECT_TO_CURSOR
                else -> InventoryAction.PICKUP_ALL
            }
        return InventoryClickEvent(
            view,
            InventoryType.SlotType.CONTAINER,
            rawSlot,
            clickType,
            action,
            if (clickType == ClickType.NUMBER_KEY) 0 else -1,
        )
    }

    private fun interactionConfig(tuning: MountTuningDefinition) =
        mockk<MountModuleConfig> {
            every { listTitle } returns "Коллекция маунтов"
            every { detailTitle } returns "Маунт: <mount>"
            every { progressionTitle } returns "Развитие: <mount>"
            every { skinsTitle } returns "Облики: <mount>"
            every { confirmTitle } returns "Подтверждение"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { quickSummonWhistle } returns true
            every { backCommand } returns ""
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
            every { guiText(any(), any()) } answers { secondArg() }
            every { guiLines(any(), any()) } answers { secondArg() }
        }

    private fun mountGuiController(
        configProvider: () -> MountModuleConfig,
        catalogProvider: () -> MountCatalog,
        ownership: MountOwnership,
        wallet: MountWallet,
        purchases: MountPurchaseCoordinator,
        sessions: MountSessionController,
        transfers: () -> MountTransferController? = { null },
        merchantAllowed: (Player) -> Boolean = { false },
    ): MountGuiController {
        val summons = MountSummonService(configProvider, catalogProvider, ownership, sessions)
        return MountGuiController(
            plugin = plugin,
            configProvider = configProvider,
            catalogProvider = catalogProvider,
            ownership = ownership,
            wallet = wallet,
            purchases = purchases,
            summons = summons,
            quickSummons = MountQuickSummonController(plugin, configProvider, summons),
            menuRuntime = menuRuntime,
            transfers = transfers,
            merchantAllowed = merchantAllowed,
        )
    }

    private fun plainName(stack: org.bukkit.inventory.ItemStack?): String =
        PlainTextComponentSerializer.plainText().serialize(checkNotNull(stack?.itemMeta?.displayName()))
}
