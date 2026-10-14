package ru.arc.mounts

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import java.time.Duration
import java.util.concurrent.CompletableFuture

class MountSummonServiceTest : TestBase() {
    @Test
    fun `favorite summon preserves vanilla state when no favorite is selected`() {
        val mount = testMount()
        val ownership = mockk<MountOwnership> {
            every { favoriteMountId(any()) } returns null
        }
        val sessions = mockk<MountSessionController>(relaxed = true)
        val service = service(mount, ownership, sessions)
        val player = server.addPlayer("NoFavorite")

        service.summonFavorite(player) shouldBe MountSummonOutcome.FAVORITE_NOT_SELECTED
        verify(exactly = 0) { sessions.spawn(any(), any(), any(), any()) }
    }

    @Test
    fun `favorite summon rejects a saved mount that is no longer unlocked`() {
        val mount = testMount()
        val ownership = mockk<MountOwnership> {
            every { favoriteMountId(any()) } returns mount.id
            every { profile(any(), mount) } returns MountProfile(0, false, false)
        }
        val sessions = mockk<MountSessionController>(relaxed = true)
        val service = service(mount, ownership, sessions)
        val player = server.addPlayer("FormerRider")

        service.summonFavorite(player) shouldBe MountSummonOutcome.FAVORITE_UNAVAILABLE
        verify(exactly = 0) { sessions.spawn(any(), any(), any(), any()) }
    }

    @Test
    fun `favorite summon applies the same tuned profile as the menu`() {
        val mount = testMount()
        val profile =
            MountProfile(
                level = 2,
                glowOwned = true,
                glowDisabled = false,
                ownedSkinIds = setOf("baby"),
                activeSkinId = "baby",
                ownedAbilityIds = setOf("night-vision"),
                selectedSpeedPercentage = 65,
                selectedStepHeightHundredths = 150,
            )
        val ownership = mockk<MountOwnership> {
            every { favoriteMountId(any()) } returns mount.id
            every { profile(any(), mount) } returns profile
        }
        val sessions = mockk<MountSessionController> {
            every {
                spawn(
                    player = any(),
                    definition = mount,
                    settings =
                        MountRuntimeSettings(
                            speed = 0.39,
                            walkingStepHeight = 1.5,
                            handlingMultiplier = 1.0,
                            sprintMultiplier = 1.0,
                            scaleMultiplier = 1.0,
                            skin = mount.skin("baby"),
                            glow = true,
                            abilityUpgrades = listOf(checkNotNull(mount.ability("night-vision"))),
                        ),
                    durationMillis = Duration.ofHours(12).toMillis(),
                )
            } returns MountSpawnResult.SUCCESS
        }
        val service = service(mount, ownership, sessions)
        val player = server.addPlayer("FavoriteRider")

        service.summonFavorite(player) shouldBe MountSummonOutcome.SUCCESS
    }

    @Test
    fun `favorite selection only persists an unlocked mount`() {
        val mount = testMount()
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returnsMany
                listOf(
                    MountProfile(0, false, false),
                    MountProfile(1, false, false),
                )
            every { setFavoriteMount(any(), mount) } returns CompletableFuture.completedFuture(null)
        }
        val service = service(mount, ownership, mockk(relaxed = true))
        val player = server.addPlayer("Chooser")

        service.selectFavorite(player, mount).join() shouldBe MountFavoriteSelectionOutcome.NOT_UNLOCKED
        service.selectFavorite(player, mount).join() shouldBe MountFavoriteSelectionOutcome.SUCCESS
        verify(exactly = 1) { ownership.setFavoriteMount(player.uniqueId, mount) }
    }

    @Test
    fun `live refresh sends one complete size and appearance snapshot to the active session`() {
        val mount =
            testMount().copy(
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("standard", "Обычный", 1.0),
                        MountSizeOptionDefinition("massive", "Крупный", 1.15, minimumLevel = 2),
                    ),
            )
        val profile =
            MountProfile(
                level = 2,
                glowOwned = true,
                glowDisabled = false,
                ownedSkinIds = setOf("baby"),
                activeSkinId = "baby",
                ownedAbilityIds = setOf("night-vision"),
                selectedSpeedPercentage = 65,
                selectedStepHeightHundredths = 150,
                selectedSizeId = "massive",
            )
        val ownership = mockk<MountOwnership> { every { profile(any(), mount) } returns profile }
        val sessions = mockk<MountSessionController> {
            every { reconcileSettings(any(), mount.id, any()) } returns MountSessionUpdateResult.APPLIED
        }
        val service = service(mount, ownership, sessions)
        val player = server.addPlayer("LiveRider")

        service.refreshActive(player, mount) shouldBe MountSessionUpdateResult.APPLIED
        verify(exactly = 1) {
            sessions.reconcileSettings(
                player.uniqueId,
                mount.id,
                match {
                    it.speed == 0.39 &&
                        it.walkingStepHeight == 1.5 &&
                        it.scaleMultiplier == 1.15 &&
                        !it.riderViewAutoHide &&
                        it.skin == mount.skin("baby") &&
                        it.glow &&
                        it.abilityUpgrades.map(MountAbilityUpgradeDefinition::id) == listOf("night-vision")
                },
            )
        }
    }

    @Test
    fun `summon feedback uses the configured message for the exact failure`() {
        val mount = testMount()
        val ownership = mockk<MountOwnership>(relaxed = true)
        val service = service(mount, ownership, mockk(relaxed = true))
        val player = server.addPlayer("FeedbackRider")

        service.sendFeedback(player, MountSummonOutcome.FAVORITE_NOT_SELECTED)

        PlainTextComponentSerializer.plainText().serialize(checkNotNull(player.nextComponentMessage())) shouldBe
            "Сначала выберите любимого маунта в /mount."
    }

    private fun service(
        mount: MountDefinition,
        ownership: MountOwnership,
        sessions: MountSessionController,
    ): MountSummonService {
        val config = mockk<MountModuleConfig> {
            every { tuning } returns
                MountTuningDefinition(
                    speedPercentages = listOf(50, 65, 80, 90, 100),
                    walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                    walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
                )
            every { sessionDuration } returns Duration.ofHours(12)
            every { message(any(), any()) } answers { secondArg() }
        }
        return MountSummonService(
            configProvider = { config },
            catalogProvider = { MountCatalog(listOf(mount)) },
            ownership = ownership,
            sessions = sessions,
        )
    }
}
