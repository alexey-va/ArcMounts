package ru.arc.mounts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.luckperms.api.node.types.PermissionNode
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MountDomainTest : StringSpec({
    "catalog preserves the highest unlocked permission level and selected skin" {
        val mount = testMount()
        val subject =
            MountPermissionSubject(UUID.randomUUID(), "Rider") { permission ->
                permission in
                    setOf(
                        mount.levelPermission(1),
                        mount.levelPermission(3),
                        mount.glowPermission,
                        mount.skinPermission("baby"),
                        mount.activeSkinPermission("baby"),
                    )
            }

        TestOwnership().profile(subject, mount) shouldBe
            MountProfile(
                level = 3,
                glowOwned = true,
                glowDisabled = false,
                ownedSkinIds = setOf("baby"),
                activeSkinId = "baby",
            )
    }

    "rider view defaults stay visible at normal size and hide enlarged mounts" {
        val mount = testMount().copy(
            appearance = MountAppearance(scale = 1.0),
            sizeOptions = listOf(
                MountSizeOptionDefinition("standard", "Обычный", 1.0),
                MountSizeOptionDefinition("giant", "Гигант", 2.0),
            ),
        )
        val profile = MountProfile(1, false, false)
        mount.effectiveRiderViewAutoHide(profile) shouldBe false
        mount.effectiveRiderViewAutoHide(profile.copy(selectedSizeId = "giant")) shouldBe true
        mount.effectiveRiderViewAutoHide(profile.copy(riderViewAutoHide = true)) shouldBe true
        mount.effectiveRiderViewAutoHide(profile.copy(selectedSizeId = "giant", riderViewAutoHide = false)) shouldBe false
        mount.copy(appearance = MountAppearance(scale = 0.5))
            .effectiveRiderViewAutoHide(profile.copy(selectedSizeId = "giant")) shouldBe false
    }

    "all mount ownership nodes use the ARC mounts namespace" {
        val mount = testMount()

        mount.levelPermission(2) shouldBe "arc.mounts.bee.2"
        mount.glowPermission shouldBe "arc.mounts.bee.glow"
        mount.glowDisabledPermission shouldBe "arc.mounts.bee.glow.disabled"
        mount.skinPermission("baby") shouldBe "arc.mounts.bee.skin.baby"
        mount.activeSkinPermission("baby") shouldBe "arc.mounts.bee.skin.active.baby"
        mount.abilityPermission("night-vision") shouldBe "arc.mounts.bee.ability.night-vision"
        mount.speedTuningPermission(65) shouldBe "arc.mounts.bee.tuning.speed.65"
        mount.stepHeightTuningPermission(110) shouldBe "arc.mounts.bee.tuning.step-height.110"
        val sized = mount.copy(
            sizeOptions = listOf(MountSizeOptionDefinition("standard", "Обычный", 1.0)),
        )
        sized.sizeTuningPermission("standard") shouldBe "arc.mounts.bee.tuning.size.standard"
        sized.sizeOwnershipPermission("keychain") shouldBe "arc.mounts.bee.size.keychain"
        favoriteMountPermission(mount.id) shouldBe "arc.mounts.favorite.bee"
    }

    "favorite state accepts only positive valid mount ids and resolves duplicate direct nodes deterministically" {
        directPositiveStringSuffix(
            listOf(
                permissionNode("${MOUNT_FAVORITE_PERMISSION_PREFIX}zombie"),
                permissionNode("${MOUNT_FAVORITE_PERMISSION_PREFIX}bee"),
                permissionNode("${MOUNT_FAVORITE_PERMISSION_PREFIX}BAD"),
                permissionNode("${MOUNT_FAVORITE_PERMISSION_PREFIX}bat", value = false),
                permissionNode("arc.mounts.bee.1"),
            ),
            MOUNT_FAVORITE_PERMISSION_PREFIX,
            MountDefinition::validId,
        ) shouldBe "bee"
    }

    "disabled glow permission wins after glow was purchased" {
        val mount = testMount()
        val subject =
            MountPermissionSubject(UUID.randomUUID(), "Rider") { permission ->
                permission == mount.levelPermission(1) ||
                    permission == mount.glowPermission ||
                    permission == mount.glowDisabledPermission
            }

        TestOwnership().profile(subject, mount).glowEnabled shouldBe false
    }

    "a wildcard is not treated as the direct glow-disabled setting" {
        val disabled = testMount().glowDisabledPermission

        hasDirectPositivePermission(listOf(permissionNode("*")), disabled) shouldBe false
        hasDirectPositivePermission(listOf(permissionNode(disabled)), disabled) shouldBe true
    }

    "revoke-all allowlist excludes wildcard and unrelated permissions" {
        val mount = testMount()

        isMountOwnedPermission(mount, mount.levelPermission(1)) shouldBe true
        isMountOwnedPermission(mount, favoriteMountPermission(mount.id)) shouldBe true
        isMountOwnedPermission(mount, "*") shouldBe false
        isMountOwnedPermission(mount, "arc.mounts.other.1") shouldBe false
        isMountOwnedPermission(mount, "arc.admin") shouldBe false
        isMountOwnedPermission(mount, "75") shouldBe false
        isMountOwnedPermission(mount, "120") shouldBe false
    }

    "direct tuning state accepts only positive exact numeric suffixes and resolves duplicates conservatively" {
        val prefix = testMount().speedTuningPermissionPrefix

        directPositiveNumericSuffix(
            listOf(
                permissionNode("$prefix${90}"),
                permissionNode("$prefix${65}"),
                permissionNode("arc.mounts.bee.tuning.speed.fast"),
                permissionNode("arc.mounts.bee.tuning.step-height.100"),
            ),
            prefix,
        ) shouldBe 65
    }

    "level ceilings unlock step height while player tuning can stay below the maximum" {
        val tuning = MountTuningDefinition(listOf(50, 65, 80, 90, 100), listOf(110, 150, 200, 300, 400), listOf(110, 200, 400))

        tuning.speed(4.8, 65) shouldBe (3.12 plusOrMinus 1.0e-9)
        tuning.speedPercentage(null) shouldBe 100
        tuning.stepHeight(1, null) shouldBeExactly 1.1
        tuning.stepHeight(2, 150) shouldBeExactly 1.5
        tuning.stepHeight(2, 400) shouldBeExactly 2.0
        tuning.stepHeight(3, null) shouldBeExactly 4.0
        tuning.stepHeight(3, 80) shouldBeExactly 1.1
    }

    "tuning rejects unordered or unsafe GUI options" {
        shouldThrow<IllegalArgumentException> {
            MountTuningDefinition(listOf(100, 65), listOf(110, 200), listOf(200))
        }
        shouldThrow<IllegalArgumentException> {
            MountTuningDefinition(listOf(50, 100), listOf(110, 200), listOf(300))
        }
        shouldThrow<IllegalArgumentException> {
            MountTuningDefinition(listOf(50, 100), listOf(110, 200, 400), listOf(200))
        }
        shouldThrow<IllegalArgumentException> {
            MountTuningDefinition(listOf(50, 100), listOf(50, 200), listOf(200))
        }
        shouldThrow<IllegalArgumentException> {
            MountTuningDefinition(listOf(50, 100), listOf(110, 401), listOf(401))
        }
    }

    "planar input is normalized so diagonal movement is not faster" {
        val straight = MountMotion.planarDirection(0f, MountInputState(forward = true))
        val diagonal = MountMotion.planarDirection(0f, MountInputState(forward = true, right = true))

        straight.horizontalLength shouldBe (1.0 plusOrMinus 1.0e-9)
        diagonal.horizontalLength shouldBe (1.0 plusOrMinus 1.0e-9)
        straight.z.shouldBeExactly(1.0)
    }

    "acceleration time controls a gradual speed ramp independently of entity velocity" {
        val timing =
            MountMotionTiming(
                accelerationTime = Duration.ofMillis(900),
                decelerationTime = Duration.ofMillis(350),
                turnTime = Duration.ofMillis(200),
            )
        var state = MountMotionState()

        state = MountMotion.advance(state, MotionVector(0.0, 0.0, 1.0), timing, handlingMultiplier = 1.0)
        state.speed shouldBe (0.1534 plusOrMinus 0.0001)
        repeat(17) {
            state = MountMotion.advance(state, MotionVector(0.0, 0.0, 1.0), timing, handlingMultiplier = 1.0)
        }

        state.speed shouldBe (0.95 plusOrMinus 1.0e-9)
        state.velocity shouldBe MotionVector(0.0, 0.0, state.speed)
    }

    "zero timings respond immediately even on reversal and stop" {
        val timing = MountMotionTiming(Duration.ZERO, Duration.ZERO, Duration.ZERO)
        var state = MountMotionState(direction = MotionVector(0.0, 0.0, 1.0), speed = 1.0)
        for (target in listOf(MotionVector(0.0, 0.0, -0.5), MotionVector(0.5, 0.0, 0.0), MotionVector.ZERO)) {
            state = MountMotion.advance(state, target, timing, handlingMultiplier = 0.9)
            state.velocity shouldBe target
        }
    }

    "reversing brakes before accelerating in the opposite direction" {
        val timing =
            MountMotionTiming(
                accelerationTime = Duration.ofMillis(900),
                decelerationTime = Duration.ofMillis(350),
                turnTime = Duration.ofMillis(200),
            )
        var state = MountMotionState(direction = MotionVector(0.0, 0.0, 1.0), speed = 1.0)

        state = MountMotion.advance(state, MotionVector(0.0, 0.0, -1.0), timing, handlingMultiplier = 1.0)

        (state.speed < 1.0) shouldBe true
        state.direction shouldBe MotionVector(0.0, 0.0, 1.0)
        repeat(12) {
            state = MountMotion.advance(state, MotionVector(0.0, 0.0, -1.0), timing, handlingMultiplier = 1.0)
        }
        (state.velocity.z < 0.0) shouldBe true
        (state.speed < 0.5) shouldBe true
    }

    "turn time bends the controlled direction without changing speed" {
        val timing =
            MountMotionTiming(
                accelerationTime = Duration.ofMillis(900),
                decelerationTime = Duration.ofMillis(350),
                turnTime = Duration.ofMillis(200),
            )
        val state = MountMotionState(direction = MotionVector(0.0, 0.0, 1.0), speed = 1.0)

        val turned = MountMotion.advance(state, MotionVector(1.0, 0.0, 0.0), timing, handlingMultiplier = 1.0)

        turned.speed shouldBeExactly 1.0
        (turned.direction.x > 0.0) shouldBe true
        (turned.direction.z > 0.0) shouldBe true
    }

    "motion timing rejects negative or impractically slow responses" {
        shouldThrow<IllegalArgumentException> {
            MountMotionTiming(Duration.ofMillis(-1), Duration.ofMillis(350), Duration.ofMillis(200))
        }
        shouldThrow<IllegalArgumentException> {
            MountMotionTiming(Duration.ofSeconds(11), Duration.ofMillis(350), Duration.ofMillis(200))
        }
    }

    "airborne controls use space to ascend and shift to descend" {
        val planar = MountMotion.planarDirection(0f, MountInputState(forward = true))
        val ascending = airborne(MountInputState(forward = true, jump = true), planar)
        val descending = airborne(MountInputState(forward = true, sneak = true), planar)

        (ascending.y > 0.0) shouldBe true
        (descending.y < 0.0) shouldBe true
        (ascending.length <= 1.0 + 1.0e-9) shouldBe true
        (descending.length <= 1.0 + 1.0e-9) shouldBe true
    }

    "flight pitch adds vertical intent while preserving the speed cap" {
        val input = MountInputState(forward = true)
        val planar = MountMotion.planarDirection(0f, input)
        val climbing = airborne(input, planar, pitch = -45f)

        (climbing.y > 0.0) shouldBe true
        (climbing.length <= 1.0 + 1.0e-9) shouldBe true
    }

    "double sneak requires two rising edges inside the window" {
        val gesture = DoubleSneakGesture(450)

        gesture.update(true, 1_000) shouldBe SneakGestureResult.PRESSED
        gesture.update(true, 1_100) shouldBe SneakGestureResult.NONE
        gesture.update(false, 1_150) shouldBe SneakGestureResult.NONE
        gesture.update(true, 1_400) shouldBe SneakGestureResult.DOUBLE_PRESSED
    }

    "high jump ability scales the walking jump velocity" {
        val abilities =
            MountAbilities(
                highJump = MountHighJumpAbility(displayName = "Высокий прыжок", multiplier = 1.8),
            )

        walkingJumpVelocity(baseVelocity = 0.5, abilities = abilities) shouldBe (0.9 plusOrMinus 1.0e-9)
        walkingJumpVelocity(baseVelocity = 0.5, MountAbilities()) shouldBeExactly 0.5
    }

    "high jump ability rejects unsafe multipliers" {
        shouldThrow<IllegalArgumentException> {
            MountHighJumpAbility(displayName = "Слишком высоко", multiplier = 3.1)
        }
        shouldThrow<IllegalArgumentException> {
            testMount().copy(
                abilities =
                    MountAbilities(
                        MountHighJumpAbility(displayName = "Высокий прыжок", multiplier = 1.8),
                    ),
            )
        }
    }

    "owned abilities contribute only their configured movement multiplier" {
        val ability = checkNotNull(testMount().ability("night-vision"))

        activeAbilitySpeedMultiplier(emptyList()) shouldBeExactly 1.0
        activeAbilitySpeedMultiplier(listOf(ability)) shouldBeExactly 1.0
    }

    "passive abilities are bounded and aquatic passives stay on swimming mounts" {
        MountPassiveAbilityDefinition("armor", "Броня", MountAbilityEffect.RESISTANCE, amplifier = 2).amplifier shouldBe 2
        shouldThrow<IllegalArgumentException> {
            MountPassiveAbilityDefinition("armor", "Броня", MountAbilityEffect.RESISTANCE, amplifier = 3)
        }
        shouldThrow<IllegalArgumentException> {
            testMount().copy(
                abilities =
                    MountAbilities(
                        passives =
                            listOf(
                                MountPassiveAbilityDefinition(
                                    "gills",
                                    "Жабры",
                                    MountAbilityEffect.WATER_BREATHING,
                                ),
                            ),
                    ),
            )
        }
    }

    "mount definitions reject invalid level prices and speeds" {
        shouldThrow<IllegalArgumentException> {
            testMount().copy(levels = listOf(MountLevelDefinition(speed = 0.0, price = 1.0)))
        }
        shouldThrow<IllegalArgumentException> {
            testMount().copy(levels = listOf(MountLevelDefinition(speed = 1.0, price = -1.0)))
        }
    }

    "level scale multiplies the selected appearance without exceeding safe entity bounds" {
        val mount =
            testMount().copy(
                levels = listOf(MountLevelDefinition(speed = 1.0, price = 1.0, scaleMultiplier = 0.75)),
                appearance = MountAppearance(scale = 1.2),
            )

        mount.effectiveAppearance(scaleMultiplier = mount.level(1).scaleMultiplier, skin = null).scale shouldBe (0.9 plusOrMinus 1.0e-9)
        shouldThrow<IllegalArgumentException> {
            MountLevelDefinition(speed = 1.0, price = 1.0, scaleMultiplier = 0.0)
        }
        shouldThrow<IllegalArgumentException> {
            mount.copy(
                levels = listOf(MountLevelDefinition(speed = 1.0, price = 1.0, scaleMultiplier = 2.0)),
                appearance = MountAppearance(scale = 10.0),
            )
        }
    }

    "authored size tuning composes with level and appearance scale" {
        val mount =
            testMount().copy(
                appearance = MountAppearance(scale = 0.82),
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("compact", "Компактный", 0.9),
                        MountSizeOptionDefinition("standard", "Обычный", 1.0),
                        MountSizeOptionDefinition("massive", "Крупный", 1.15, minimumLevel = 3),
                    ),
            )

        checkNotNull(mount.sizeOption("massive")).multiplier shouldBeExactly 1.15
        mount.sizeOption("missing")?.id shouldBe "standard"
        mount.availableSizeOptions(2).map(MountSizeOptionDefinition::id) shouldBe listOf("compact", "standard")
        mount.effectiveSizeOption("massive", 2)?.id shouldBe "standard"
        mount.effectiveAppearance(mount.level(3).scaleMultiplier * checkNotNull(mount.sizeOption("massive")).multiplier, null).scale shouldBe
            (0.943 plusOrMinus 1.0e-9)
    }

    "grant-only sizes need ownership and fall back to the ordinary size" {
        val mount =
            testMount().copy(
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("keychain", "Крошечный", 0.1, grantOnly = true),
                        MountSizeOptionDefinition("standard", "Обычный", 1.0),
                        MountSizeOptionDefinition("colossal", "Колоссальный", 10.0, grantOnly = true),
                    ),
            )

        mount.availableSizeOptions(3).map(MountSizeOptionDefinition::id) shouldBe listOf("standard")
        mount.effectiveSizeOption("colossal", 3)?.id shouldBe "standard"
        mount.availableSizeOptions(3, setOf("keychain", "colossal")).map(MountSizeOptionDefinition::id) shouldBe
            listOf("keychain", "standard", "colossal")
        mount.effectiveSizeOption("colossal", 3, setOf("colossal"))?.id shouldBe "colossal"
        MountProfile(3, false, false, ownedSizeIds = setOf("keychain")).ownsSize(mount.sizeOptions.first()) shouldBe true
    }

    "comic mount scales stay inside the native attribute envelope" {
        MountAppearance(scale = 0.0625).scale shouldBeExactly 0.0625
        MountAppearance(scale = 16.0).scale shouldBeExactly 16.0
        MountSizeOptionDefinition("tiny", "Крошечный", 0.1).multiplier shouldBeExactly 0.1
        MountSizeOptionDefinition("giant", "Гигант", 10.0).multiplier shouldBeExactly 10.0
        shouldThrow<IllegalArgumentException> { MountAppearance(scale = 0.0624) }
        shouldThrow<IllegalArgumentException> { MountAppearance(scale = 16.1) }
        shouldThrow<IllegalArgumentException> { MountSizeOptionDefinition("tiny", "Крошечный", 0.09) }
        shouldThrow<IllegalArgumentException> { MountSizeOptionDefinition("giant", "Гигант", 10.1) }
    }

    "size tuning rejects catalogs without one standard option" {
        shouldThrow<IllegalArgumentException> {
            testMount().copy(sizeOptions = listOf(MountSizeOptionDefinition("compact", "Компактный", 0.9)))
        }
        shouldThrow<IllegalArgumentException> {
            testMount().copy(
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("standard", "Обычный", 1.0),
                        MountSizeOptionDefinition("standard-2", "Ещё обычный", 1.0),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            testMount().copy(
                sizeOptions = listOf(MountSizeOptionDefinition("standard", "Обычный", 1.0, minimumLevel = 2)),
            )
        }
    }
})

private fun airborne(input: MountInputState, planar: MotionVector, pitch: Float = 0f) =
    MountMotion.airborneTarget(
        pitchDegrees = pitch,
        input = input,
        planar = planar,
        maximumSpeed = 1.0,
        verticalSpeedRatio = 0.75,
        maximumVerticalSpeed = 0.5,
        pitchInfluence = 0.65,
    )

private fun permissionNode(permissionName: String, value: Boolean = true): PermissionNode =
    mockk {
        every { permission } returns permissionName
        every { this@mockk.value } returns value
    }

internal fun testMount() =
    MountDefinition(
        id = "bee",
        movement = MountMovement.FLYING,
        entityType = "BEE",
        iconMaterial = "BEE_SPAWN_EGG",
        displayName = "Пчела",
        description = listOf("Тестовый маунт"),
        acquisition = "Тест",
        rarity = MountRarity.COMMON,
        levels =
            listOf(
                MountLevelDefinition(0.4, 50_000.0),
                MountLevelDefinition(0.6, 100_000.0),
                MountLevelDefinition(0.9, 5_000_000.0, 1.28, 1.12),
            ),
        glowPrice = 10_000.0,
        abilities =
            MountAbilities(
                upgrades =
                    listOf(
                        MountAbilityUpgradeDefinition(
                            id = "night-vision",
                            displayName = "Ночное зрение",
                            description = listOf("Видимость в темноте"),
                            iconMaterial = "GLOW_INK_SAC",
                            price = 25_000.0,
                            effect = MountAbilityEffect.NIGHT_VISION,
                        ),
                    ),
            ),
        skins =
            listOf(
                MountSkinDefinition(
                    id = "baby",
                    displayName = "Малыш",
                    iconMaterial = "BEE_SPAWN_EGG",
                    price = 25_000.0,
                    appearance = MountAppearance(baby = true),
                ),
            ),
    )

private class TestOwnership : MountOwnership {
    override fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile {
        val level = (1..mount.maxLevel).filter { subject.hasPermission(mount.levelPermission(it)) }.maxOrNull() ?: 0
        val ownedSkins = mount.skins.filter { subject.hasPermission(mount.skinPermission(it.id)) }.mapTo(hashSetOf()) { it.id }
        val active = mount.skins.firstOrNull { it.id in ownedSkins && subject.hasPermission(mount.activeSkinPermission(it.id)) }?.id
        val ownedAbilities = mount.abilities.upgrades.filter { subject.hasPermission(mount.abilityPermission(it.id)) }.mapTo(hashSetOf()) { it.id }
        return MountProfile(
            level,
            subject.hasPermission(mount.glowPermission),
            subject.hasPermission(mount.glowDisabledPermission),
            ownedSkins,
            active ?: MountDefinition.DEFAULT_SKIN_ID,
            ownedAbilities,
        )
    }

    override fun favoriteMountId(playerId: UUID): String? = null
    override fun setFavoriteMount(playerId: UUID, mount: MountDefinition) = CompletableFuture.completedFuture<Void>(null)

    override fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int) = CompletableFuture.completedFuture<Void>(null)
    override fun revokeLevel(playerId: UUID, mount: MountDefinition, level: Int) = CompletableFuture.completedFuture<Void>(null)
    override fun grantGlow(playerId: UUID, mount: MountDefinition) = CompletableFuture.completedFuture<Void>(null)
    override fun revokeGlow(playerId: UUID, mount: MountDefinition) = CompletableFuture.completedFuture<Void>(null)
    override fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean) = CompletableFuture.completedFuture<Void>(null)
    override fun grantSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition) = CompletableFuture.completedFuture<Void>(null)
    override fun revokeSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition) = CompletableFuture.completedFuture<Void>(null)
    override fun setActiveSkin(playerId: UUID, mount: MountDefinition, skinId: String) = CompletableFuture.completedFuture<Void>(null)
    override fun grantAbility(playerId: UUID, mount: MountDefinition, ability: MountAbilityUpgradeDefinition) = CompletableFuture.completedFuture<Void>(null)
    override fun revokeAbility(playerId: UUID, mount: MountDefinition, ability: MountAbilityUpgradeDefinition) = CompletableFuture.completedFuture<Void>(null)
    override fun setSpeedTuning(playerId: UUID, mount: MountDefinition, percentage: Int) = CompletableFuture.completedFuture<Void>(null)
    override fun setStepHeightTuning(playerId: UUID, mount: MountDefinition, hundredths: Int) = CompletableFuture.completedFuture<Void>(null)
    override fun hasDirectPermission(playerId: UUID, permission: String) = CompletableFuture.completedFuture(false)
    override fun resolveUniqueId(playerName: String) = CompletableFuture.completedFuture<UUID?>(null)
}
