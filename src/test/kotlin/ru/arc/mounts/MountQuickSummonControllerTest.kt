package ru.arc.mounts

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import java.time.Duration

class MountQuickSummonControllerTest : TestBase() {
    @Test
    fun `shortcut summons the selected favorite`() {
        val fixture = fixture(favoriteSelected = true)
        val player = server.addPlayer("HotkeyRider")
        player.addAttachment(plugin, "arc.mounts.use", true)
        player.setSneaking(true)

        fixture.controller.start()
        try {
            fixture.controller.summonFavorite(player) shouldBe true
        } finally {
            fixture.controller.shutdown()
        }

        verify(exactly = 1) { fixture.sessions.spawn(any(), any(), any(), any()) }
    }

    @Test
    fun `shortcut does not spawn a mount when no favorite is selected`() {
        val fixture = fixture(favoriteSelected = false)
        val player = server.addPlayer("VanillaSwapper")
        player.addAttachment(plugin, "arc.mounts.use", true)
        player.setSneaking(true)

        fixture.controller.start()
        try {
            fixture.controller.summonFavorite(player) shouldBe true
        } finally {
            fixture.controller.shutdown()
        }

        verify(exactly = 0) { fixture.sessions.spawn(any(), any(), any(), any()) }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `right-clicking an ARC whistle summons the current favorite`() {
        val fixture = fixture(favoriteSelected = true)
        val player = server.addPlayer("WhistleRider")
        player.addAttachment(plugin, "arc.mounts.use", true)
        val whistle = fixture.controller.createWhistle()
        val event =
            PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_AIR,
                whistle,
                null,
                BlockFace.SELF,
                EquipmentSlot.HAND,
            )

        fixture.controller.start()
        try {
            fixture.controller.onUseWhistle(event)
        } finally {
            fixture.controller.shutdown()
        }

        fixture.controller.isWhistle(whistle) shouldBe true
        event.isCancelled shouldBe true
        verify(exactly = 1) { fixture.sessions.spawn(any(), any(), any(), any()) }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `cancelled air click still reaches the ARC whistle through Bukkit dispatch`() {
        val fixture = fixture(favoriteSelected = true)
        val player = server.addPlayer("CancelledWhistleRider")
        player.addAttachment(plugin, "arc.mounts.use", true)
        val event =
            PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_AIR,
                fixture.controller.createWhistle(),
                null,
                BlockFace.SELF,
                EquipmentSlot.HAND,
            ).also { it.isCancelled = true }

        fixture.controller.start()
        try {
            val handler =
                MountQuickSummonController::class.java
                    .getDeclaredMethod("onUseWhistle", PlayerInteractEvent::class.java)
                    .getAnnotation(org.bukkit.event.EventHandler::class.java)
            handler.ignoreCancelled shouldBe false
            fixture.controller.onUseWhistle(event)
        } finally {
            fixture.controller.shutdown()
        }

        event.isCancelled shouldBe true
        verify(exactly = 1) { fixture.sessions.spawn(any(), any(), any(), any()) }
    }

    @Test
    fun `menu recovery gives at most one whistle in the player inventory`() {
        val fixture = fixture(favoriteSelected = true)
        val player = server.addPlayer("WhistleKeeper")

        fixture.controller.giveWhistle(player) shouldBe MountWhistleGiveOutcome.GIVEN
        fixture.controller.giveWhistle(player) shouldBe MountWhistleGiveOutcome.ALREADY_OWNED
        player.inventory.contents.count(fixture.controller::isWhistle) shouldBe 1
    }

    private fun fixture(favoriteSelected: Boolean): Fixture {
        val mount = testMount()
        val ownership = mockk<MountOwnership> {
            every { favoriteMountId(any()) } returns mount.id.takeIf { favoriteSelected }
            every { profile(any(), mount) } returns MountProfile(1, false, false)
        }
        val sessions = mockk<MountSessionController> {
            every { spawn(any(), any(), any(), any()) } returns MountSpawnResult.SUCCESS
        }
        val config = mockk<MountModuleConfig> {
            every { quickSummonSneakSwapHands } returns true
            every { quickSummonWhistle } returns true
            every { tuning } returns
                MountTuningDefinition(
                    speedPercentages = listOf(50, 65, 80, 90, 100),
                    walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                    walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
                )
            every { sessionDuration } returns Duration.ofHours(12)
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
        }
        val service = MountSummonService({ config }, { MountCatalog(listOf(mount)) }, ownership, sessions)
        return Fixture(
            controller = MountQuickSummonController(plugin, { config }, service),
            sessions = sessions,
        )
    }

    private data class Fixture(
        val controller: MountQuickSummonController,
        val sessions: MountSessionController,
    )
}
