package ru.arc.mounts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import ru.arc.config.ConfigManager
import ru.arc.config.TestConfig
import java.nio.file.Files
import java.time.Duration

class MountModuleConfigTest : StringSpec({
    "localized messages fall back to Russian and keep dynamic values literal" {
        val config = MountModuleConfig(
            TestConfig(mapOf("messages" to mapOf("welcome" to "<green>Добро пожаловать: <name>"))),
        )

        PlainTextComponentSerializer.plainText().serialize(
            config.messageComponent(
                "welcome",
                "<red>fallback",
                "en-US",
                mapOf("name" to Component.text("<red>literal")),
            ),
        ) shouldBe "Добро пожаловать: <red>literal"
    }

    "bundled catalog exposes the full production collection" {
        val config = bundledConfig("catalog")
        val catalog = config.catalog()

        catalog.all shouldHaveSize 72
        catalog.all.groupingBy(MountDefinition::movement).eachCount() shouldBe
            mapOf(MountMovement.WALKING to 47, MountMovement.FLYING to 14, MountMovement.SWIMMING to 11)
        catalog.all.map(MountDefinition::id).toSet().size shouldBe 72
        setOf(
            "cat",
            "wolf",
            "armadillo",
            "llama",
            "ocelot",
            "panda",
            "pufferfish",
            "magma_cube",
            "mooshroom",
            "tadpole",
            "polar_bear",
            "copper_golem",
            "iron_golem",
            "trader_llama",
            "snow_golem",
            "villager",
            "wandering_trader",
            "skeleton_horse",
            "hoglin",
            "shulker",
            "endermite",
            "piglin",
            "wither_skeleton",
            "vindicator",
            "creaking",
            "creeper",
            "elder_guardian",
            "silverfish",
            "witch",
            "camel_husk",
            "stray",
            "parched",
            "zoglin",
            "nautilus",
            "zombie_nautilus",
            "bogged",
            "piglin_brute",
            "pillager",
            "evoker",
        ).all { catalog[it] != null } shouldBe true
        catalog["ender_dragon"] shouldBe null
        catalog["blaze"]?.price(1) shouldBe null
        catalog["happy_ghast"]?.movement shouldBe MountMovement.FLYING
        catalog["dolphin"]?.movement shouldBe MountMovement.SWIMMING
        catalog["vex"]?.levels?.map(MountLevelDefinition::scaleMultiplier) shouldBe listOf(1.0, 1.0, 1.0)
        catalog.all.all { it.levels.size == 3 } shouldBe true
        catalog.all.all { it.skins.size >= 2 } shouldBe true
        catalog.all.all { it.skins.size >= 7 } shouldBe true
        catalog.all.all { mount ->
            setOf("electric-spiral", "portal-rings", "witch-helix", "enchanted-orbit", "glow-pulse")
                .all { mount.skin(it) != null }
        } shouldBe true
        catalog.all.last().id shouldBe "evoker"
    }

    "catalog separates the solo player-flight companion from vehicle mounts" {
        val config = bundledConfig("controls")
        config.catalog().all.forEach { mount ->
            mount.motion.resolve(config.motionTiming) shouldBe MountMotionTiming(Duration.ZERO, Duration.ZERO, Duration.ZERO)
            mount.control shouldBe if (mount.id == "skycruiser") MountControl.PLAYER_FLIGHT else MountControl.VEHICLE
        }
        val bee = config.catalog()["bee"]!!.copy(control = MountControl.PLAYER_FLIGHT)
        shouldThrow<IllegalArgumentException> { bee.copy(movement = MountMovement.WALKING) }
        shouldThrow<IllegalArgumentException> { config.catalog()["happy_ghast"]!!.copy(control = MountControl.PLAYER_FLIGHT) }
        shouldThrow<IllegalArgumentException> { bee.copy(passengerSeats = 1) }
    }

    "Skycruiser uses solo player flight while Happy Ghast keeps native-flight constraints" {
        val mount = bundledConfig("native-flight").catalog()["skycruiser"]!!
        mount.control shouldBe MountControl.PLAYER_FLIGHT
        mount.entityType shouldBe "PHANTOM"
        mount.visualEntityType shouldBe null
        mount.passengerSeats shouldBe 0
        shouldThrow<IllegalArgumentException> { mount.copy(passengerSeats = 1) }
        shouldThrow<IllegalArgumentException> { mount.copy(control = MountControl.NATIVE_FLIGHT) }

        val happy = bundledConfig("native-happy").catalog()["happy_ghast"]!!
        happy.entityType shouldBe "HAPPY_GHAST"
        val nativeHappy = happy.copy(control = MountControl.NATIVE_FLIGHT)
        shouldThrow<IllegalArgumentException> { nativeHappy.copy(appearance = nativeHappy.appearance.copy(baby = true)) }
        shouldThrow<IllegalArgumentException> { nativeHappy.copy(appearance = nativeHappy.appearance.copy(equipment = emptyMap())) }
    }

    "passenger seats default to zero and stay within the supported carrier envelope" {
        val catalog = bundledConfig("passenger-seats").catalog()

        catalog["zombie"]!!.passengerSeats shouldBe 0
        catalog["camel"]!!.passengerSeats shouldBe 1
        catalog["camel_husk"]!!.passengerSeats shouldBe 1
        catalog["happy_ghast"]!!.passengerSeats shouldBe 2
        catalog["ravager"]!!.passengerSeats shouldBe 2
        catalog["polar_bear"]!!.passengerSeats shouldBe 1

        fun invalid(entity: String, seats: Int, index: Int) {
            val dataPath = Files.createTempDirectory("arc-mounts-passenger-invalid-$index-")
            val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
            Files.writeString(
                moduleDir.resolve("mounts.yml"),
                """
                enabled: false
                mounts:
                  test_mount:
                    type: walking
                    entity: $entity
                    passenger-seats: $seats
                    item: PAPER
                    name: Test mount
                    acquisition: Test
                    levels:
                      - {speed: 1.0, price: 1}
                """.trimIndent(),
            )

            shouldThrow<IllegalArgumentException> { MountModuleConfig.load(dataPath).catalog() }
        }

        invalid("CAMEL", 2, 0)
        invalid("HAPPY_GHAST", 4, 1)
        invalid("BEE", 3, 2)
    }

    "maximum level is a fast and intentionally expensive final sprint" {
        val config = bundledConfig("progression")
        val catalog = config.catalog()
        val purchasable = catalog.all.filter { it.price(3) != null && it.id != "skycruiser" }

        purchasable.all { it.level(3).handlingMultiplier == 1.28 } shouldBe true
        purchasable.all { it.level(3).sprintMultiplier == 1.12 } shouldBe true
        purchasable.all { mount -> mount.level(3).speed >= mount.level(2).speed * 1.85 } shouldBe true
        purchasable.all { mount -> checkNotNull(mount.price(3)) >= checkNotNull(mount.price(2)) * 1.5 } shouldBe true
        catalog.all.all { mount ->
            val scale =
                when (mount.movement) {
                    MountMovement.WALKING -> config.walkingSpeedScale
                    MountMovement.FLYING -> config.flyingSpeedScale
                    MountMovement.SWIMMING -> config.swimmingSpeedScale
                }
            val final = mount.level(3)
            val blocksPerTick =
                (final.speed * scale * config.sprintMultiplier * final.sprintMultiplier)
                    .coerceAtMost(config.maximumSpeedBlocksPerTick)
            blocksPerTick * 20.0 >= 19.0
        } shouldBe true
        catalog.all.filter { it.movement == MountMovement.FLYING }.mapNotNull { it.price(1) }
            .all { it >= 120.0 } shouldBe true
        catalog["pig"]!!.levels.mapNotNull { it.price }.sum() shouldBe 205_000.0
        catalog["horse"]!!.levels.mapNotNull { it.price }.sum() shouldBe 420_000.0
        catalog["camel"]!!.levels.mapNotNull { it.price }.sum() shouldBe 1_050_000.0
        catalog["iron_golem"]!!.levels.mapNotNull { it.price }.sum() shouldBe 3_500_000.0
        catalog.all.filter { it.rarity == MountRarity.LEGENDARY && it.currency == "tokens" && it.id != "skycruiser" }
            .mapNotNull { it.price(3) }.toSet() shouldBe setOf(1200.0)
        catalog["skycruiser"]!!.levels.map(MountLevelDefinition::speed) shouldBe listOf(1.1, 1.5, 1.9)
        catalog["skycruiser"]!!.levels.map(MountLevelDefinition::price) shouldBe listOf(1200.0, 1800.0, 3000.0)
    }

    "upgrade currencies allow simple coin purchases and premium token entitlements" {
        val catalog = bundledConfig("currencies").catalog()
        catalog.all.forEach { mount ->
            mount.glowCurrency shouldBe "vault"
            mount.skins.filter { it.price != null }.all { it.currency == "tokens" && checkNotNull(it.price) in 1.0..150.0 } shouldBe true
            checkNotNull(mount.sizeOptions.firstOrNull { it.id == "keychain" }).let { size ->
                checkNotNull(size.price) shouldBe 40.0
                size.currency shouldBe "tokens"
            }
            checkNotNull(mount.sizeOptions.firstOrNull { it.id == "colossal" }).let { size ->
                checkNotNull(size.price) shouldBe 100.0
                size.currency shouldBe "tokens"
            }
        }
        catalog["pig"]!!.glowPrice shouldBe 5_000.0
        catalog["skycruiser"]!!.glowPrice shouldBe 25_000.0
        catalog["horse"]!!.skin("electric-spiral")!!.price shouldBe 50.0
        catalog["horse"]!!.skin("electric-spiral")!!.currency shouldBe "tokens"
        catalog["bee"]!!.skin("electric-spiral")!!.price shouldBe 50.0
        catalog["skycruiser"]!!.skin("starlight")!!.price shouldBe 150.0
        catalog["skycruiser"]!!.skin("starlight")!!.currency shouldBe "tokens"
        catalog["skycruiser"]!!.ability("night-vision")!!.price shouldBe 15_000.0
        catalog["skycruiser"]!!.ability("night-vision")!!.currency shouldBe "vault"
        catalog["skycruiser"]!!.ability("fire-resistance")!!.price shouldBe 50.0
        catalog["skycruiser"]!!.ability("fire-resistance")!!.currency shouldBe "tokens"
    }

    "per-skin glow and extreme-size currency fields parse independently of mount currency" {
        val dataPath = Files.createTempDirectory("arc-mounts-mixed-upgrade-currency-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            moduleDir.resolve("mounts.yml"),
            """
            enabled: false
            size-tuning-defaults:
              tiny: {id: keychain, name: "Крошечный", multiplier: 0.1, price: 40, currency: tokens}
              giant: {id: colossal, name: "Колоссальный", multiplier: 10.0, price: 100, currency: tokens}
            mounts:
              currency_probe:
                type: walking
                entity: PIG
                item: PIG_SPAWN_EGG
                name: Currency probe
                acquisition: Test
                currency: vault
                buy-glow: 15000
                buy-glow-currency: tokens
                levels: [{speed: 1.0, price: 1}]
                skins:
                  tokens:
                    name: Token skin
                    item: AMETHYST_SHARD
                    currency: tokens
                    price: 7
                  coins:
                    name: Coin skin
                    item: PINK_DYE
                    currency: vault
                    price: 99
                    price-vault: 2000
                size-tuning:
                  - {id: keychain, name: "Крошечный", multiplier: 0.1}
            """.trimIndent() + "\n",
        )
        val mount = checkNotNull(MountModuleConfig.load(dataPath).catalog()["currency_probe"])

        mount.currency shouldBe "vault"
        mount.glowPrice shouldBe 15_000.0
        mount.glowCurrency shouldBe "tokens"
        mount.skin("tokens")?.price shouldBe 7.0
        mount.skin("tokens")?.currency shouldBe "tokens"
        mount.skin("coins")?.price shouldBe 2_000.0
        mount.skin("coins")?.currency shouldBe "vault"
        mount.sizeOptions.first { it.id == "keychain" }.price shouldBe 40.0
        mount.sizeOptions.first { it.id == "colossal" }.price shouldBe 100.0
    }

    "new shared trails are data-free particles within the emission budget" {
        val catalog = bundledConfig("new-trails").catalog()
        val expected = mapOf(
            "frost-wave" to MountTrailPattern.WAVE,
            "cherry-ribbons" to MountTrailPattern.RIBBON,
            "star-comet" to MountTrailPattern.COMET,
            "heart-beat" to MountTrailPattern.HEART,
        )
        catalog.all.forEach { mount ->
            expected.forEach { (id, pattern) ->
                val trail = checkNotNull(mount.skin(id)?.trail)
                trail.pattern shouldBe pattern
                org.bukkit.Particle.valueOf(trail.particle).dataType shouldBe Void::class.java
                (trail.count * 20.0 / trail.intervalTicks <= 60.0) shouldBe true
            }
        }
        catalog["horse"]!!.skin("star-comet")!!.price shouldBe 80.0
        catalog["horse"]!!.skin("star-comet")!!.currency shouldBe "tokens"
        catalog["bee"]!!.skin("star-comet")!!.price shouldBe 80.0
    }

    "zombie forms are deterministic and separated into explicit skins" {
        val zombie = checkNotNull(bundledConfig("zombie").catalog()["zombie"])

        zombie.appearance.baby shouldBe false
        zombie.appearance.equipment shouldBe emptyMap()
        zombie.skins.map(MountSkinDefinition::id) shouldBe
            listOf(
                "baby",
                "iron_guard",
                "diamond_warlord",
                "electric-spiral",
                "portal-rings",
                "witch-helix",
                "enchanted-orbit",
                "glow-pulse",
                "frost-wave",
                "cherry-ribbons",
                "star-comet",
                "heart-beat",
            )
        zombie.skin("baby")?.appearance?.baby shouldBe true
        zombie.skin("iron_guard")?.appearance?.equipment?.get(MountEquipmentSlot.CHEST) shouldBe "IRON_CHESTPLATE"
        zombie.skin("diamond_warlord")?.appearance?.equipment?.get(MountEquipmentSlot.MAIN_HAND) shouldBe "NETHERITE_SWORD"
        zombie.skinPermission("baby") shouldBe "arc.mounts.zombie.skin.baby"
    }

    "mount abilities are explicit and assigned only where they fit" {
        val catalog = bundledConfig("abilities").catalog()
        val goat = checkNotNull(catalog["goat"])
        val frog = checkNotNull(catalog["frog"])
        val horse = checkNotNull(catalog["horse"])
        val fox = checkNotNull(catalog["fox"])

        goat.abilities.highJump?.displayName shouldBe "Высокий прыжок"
        goat.abilities.highJump?.multiplier shouldBe 1.8
        frog.abilities.highJump?.multiplier shouldBe 1.55
        horse.abilities.highJump?.multiplier shouldBe 1.25
        fox.abilities.highJump?.displayName shouldBe "Лисий прыжок"
        fox.abilities.highJump?.multiplier shouldBe 1.35
        catalog.all.filterNot {
            it.id in setOf("goat", "frog", "horse", "fox", "pocket_chicken", "magma_cube", "skeleton_horse")
        }
            .all { it.abilities.highJump == null } shouldBe true
        catalog.all.filter { it.movement == MountMovement.SWIMMING }.all { mount ->
            (mount.ability("water-breathing") != null || mount.abilities.passives.any { it.effect == MountAbilityEffect.WATER_BREATHING }) &&
                mount.ability("night-vision") != null
        } shouldBe true
        catalog["dolphin"]?.ability("dolphins-grace")?.speedMultiplier shouldBe 1.15
        setOf("skeleton", "enderman", "bat", "phantom").all {
            catalog[it]?.ability("night-vision")?.effect == MountAbilityEffect.NIGHT_VISION
        } shouldBe true
        setOf("strider", "blaze", "ghast", "happy_ghast").all { catalog[it]?.ability("fire-resistance") != null } shouldBe true
        checkNotNull(catalog["pig"]).abilities.passives.single().effect shouldBe MountAbilityEffect.SPEED
        checkNotNull(catalog["bee"]).abilities.passives.single().effect shouldBe MountAbilityEffect.REGENERATION
        checkNotNull(catalog["ghast"]).abilities.passives.single().effect shouldBe MountAbilityEffect.FIRE_RESISTANCE
        checkNotNull(catalog["magma_cube"]).abilities.passives.single().effect shouldBe MountAbilityEffect.FIRE_RESISTANCE
        checkNotNull(catalog["mega_warden"]).abilities.passives.map(MountPassiveAbilityDefinition::effect) shouldBe
            listOf(MountAbilityEffect.NIGHT_VISION, MountAbilityEffect.STRENGTH, MountAbilityEffect.RESISTANCE)
        catalog.all.all { it.abilities.displayNames.isNotEmpty() || it.behaviors.isNotEmpty() } shouldBe true
    }

    "authored sizes remain distinct with inertia disabled" {
        val catalog = bundledConfig("mount-personality").catalog()
        checkNotNull(catalog["horse"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe listOf(0.1, 1.0, 2.0, 3.0, 10.0)
        checkNotNull(catalog["bee"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe listOf(0.1, 0.5, 1.0, 3.0, 10.0)
    }

    "ravager has authored size tuning, a guarded ram and visible localized trails" {
        val catalog = bundledConfig("ravager-quality").catalog()
        val ravager = checkNotNull(catalog["ravager"])

        ravager.sizeOptions.map(MountSizeOptionDefinition::id) shouldBe listOf("keychain", "standard", "huge", "absurd", "colossal")
        ravager.sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe listOf(0.1, 1.0, 2.0, 3.0, 10.0)
        ravager.sizeOptions.filter(MountSizeOptionDefinition::grantOnly).map(MountSizeOptionDefinition::id) shouldBe
            listOf("keychain", "colossal")
        val ram = ravager.behaviors.filterIsInstance<MountRamBehavior>().single()
        ram.displayName shouldBe "Таран"
        ram.description shouldBe
            listOf(
                "Нажмите спринт при движении вперёд.",
                "Первый враждебный моб на пути",
                "получит урон при столкновении.",
            )
        ram.damage shouldBe 4.0
        ram.cooldown shouldBe Duration.ofSeconds(4)
        val trample = ravager.behaviors.filterIsInstance<MountTrampleBehavior>().single()
        trample.displayName shouldBe "Топот"
        trample.damage shouldBe 2.0
        trample.cooldown shouldBe Duration.ofSeconds(1)
        trample.maximumTargets shouldBe 4
        ravager.skin("starlight")?.trail?.displayName shouldBe "Звёздный след"
        ravager.skin("starlight")?.trail?.count shouldBe 3
        ravager.skin("shadow")?.trail?.displayName shouldBe "След душ"
        ravager.skin("shadow")?.trail?.count shouldBe 4
        ravager.skin("electric-spiral")?.trail?.pattern shouldBe MountTrailPattern.SPIRAL
        ravager.skin("portal-rings")?.trail?.pattern shouldBe MountTrailPattern.RING
        ravager.skin("witch-helix")?.trail?.pattern shouldBe MountTrailPattern.DOUBLE_HELIX
        ravager.skin("enchanted-orbit")?.trail?.pattern shouldBe MountTrailPattern.ORBIT
        ravager.skin("glow-pulse")?.trail?.pattern shouldBe MountTrailPattern.PULSE
    }

    "absurd catalog mounts retain their authored effective scale" {
        val catalog = bundledConfig("absurd-scales").catalog()

        catalog["mega_pig"] shouldBe null
        catalog["colossal_bee"] shouldBe null
        catalog["pocket_ghast"] shouldBe null
        checkNotNull(catalog["pig"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe
            listOf(0.1, 0.5, 1.0, 3.0, 10.0)
        checkNotNull(catalog["ghast"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe
            listOf(0.1, 0.5, 1.0, 3.0, 10.0)
        checkNotNull(catalog["mega_warden"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe
            listOf(0.1, 0.5, 1.0, 3.0, 10.0)
        checkNotNull(catalog["pocket_chicken"]).sizeOptions.map(MountSizeOptionDefinition::multiplier) shouldBe
            listOf(0.1, 0.5, 1.0, 3.0, 10.0)
    }

    "every mount receives command-only extreme sizes inside the native scale envelope" {
        val catalog = bundledConfig("extreme-sizes").catalog()

        catalog.all.all { mount ->
            val extremes = mount.sizeOptions.filter(MountSizeOptionDefinition::grantOnly)
            extremes.size == 2 &&
                extremes.all { it.minimumLevel == 1 } &&
                extremes.first().multiplier == 0.1 &&
                extremes.last().multiplier >= 7.6 &&
                extremes.last().multiplier <= 10.0 &&
                mount.defaultSizeOption?.grantOnly == false
        } shouldBe true
        checkNotNull(catalog["bat"]).sizeOptions.last().multiplier shouldBe 7.6
        checkNotNull(catalog["bee"]).sizeOptions.last().multiplier shouldBe 10.0
    }

    "every catalog appearance, material, entity and particle matches the exact Paper API" {
        MountModule.validatePaperTypes(bundledConfig("paper-types").catalog())
    }

    "paper validation rejects a paid size price below minor-unit precision" {
        val bee = checkNotNull(bundledConfig("paper-types").catalog()["bee"])
        val invalidPrice = MountSizeOptionDefinition(
            id = "fractional",
            displayName = "Дробный размер",
            multiplier = 1.1,
            grantOnly = true,
            price = 0.001,
            currency = "tokens",
        )

        shouldThrow<IllegalArgumentException> {
            MountModule.validatePaperTypes(MountCatalog(listOf(bee.copy(sizeOptions = bee.sizeOptions + invalidPrice))))
        }
    }

    "bundled module is fail-closed until a runtime mirror enables it" {
        val config = bundledConfig("gates")

        config.enabled shouldBe false
        config.ownershipMigrationComplete shouldBe false
        config.purchasesEnabled shouldBe false
        config.riderKnockoffDamage shouldBe 6.0
        config.passengerCarrierScale shouldBe 0.8
        config.passengerCarrierYawOffset shouldBe 90.0
        config.hideFlyingMountFromRider shouldBe true
        config.hideFlyingMountPitch shouldBe 35.0
        config.showFlyingMountPitch shouldBe 20.0
        config.compensateAirborneMining shouldBe true
        config.quickSummonSneakSwapHands shouldBe true
        config.quickSummonWhistle shouldBe true
        config.motionTiming shouldBe MountMotionTiming(Duration.ZERO, Duration.ZERO, Duration.ZERO)
        config.tuning.speedPercentages shouldBe listOf(50, 65, 80, 90, 100)
        config.tuning.walkingStepHeightsHundredths shouldBe listOf(110, 150, 200, 300, 400)
        config.tuning.walkingMaxStepHeightByLevelHundredths shouldBe listOf(110, 200, 400)
    }

    "passenger carrier tuning rejects non-finite and out-of-range enabled values" {
        val invalidValues = listOf(
            0.09 to 90.0,
            2.01 to 90.0,
            Double.NaN to 90.0,
            0.8 to -180.1,
            0.8 to 180.1,
            0.8 to Double.POSITIVE_INFINITY,
        )

        invalidValues.forEach { (scale, yaw) ->
            val config = MountModuleConfig(
                TestConfig(
                    mapOf(
                        "enabled" to true,
                        "passengers" to mapOf(
                            "carrier-scale" to scale,
                            "carrier-yaw-offset" to yaw,
                        ),
                    ),
                ),
            )
            shouldThrow<IllegalArgumentException> { config.validated() }
        }
    }

    "existing runtime config merges new bundled mount features without replacing server gates" {
        val dataPath = Files.createTempDirectory("arc-mounts-merge-forward-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        val file = moduleDir.resolve("mounts.yml")
        Files.writeString(
            file,
            """
            enabled: true
            ownership-migration-complete: true
            purchases-enabled: true
            allowed-worlds: [operator_world]
            gui:
              items:
                background: {material: GRAY_STAINED_GLASS_PANE, customModelData: 11000}
            mounts:
              operator_mount:
                type: walking
                entity: PIG
                item: PIG_SPAWN_EGG
                name: Operator mount
                acquisition: Operator catalog
                levels:
                  - {speed: 1.0, price: 1}
            """.trimIndent() + "\n",
        )

        val config = MountModuleConfig.load(dataPath)

        config.enabled shouldBe true
        config.purchasesEnabled shouldBe true
        config.allowedWorlds shouldBe setOf("operator_world")
        config.guiStyle(MountGuiItemRole.BACKGROUND) shouldBe
            MountGuiItemStyle(Material.GRAY_STAINED_GLASS_PANE, 11000)
        config.quickSummonWhistle shouldBe true
        config.guiText("common.footer-open", "") shouldBe
            "<#8c8c8c>[<#92bed8>▶<#8c8c8c>] <#92bed8>ЛКМ<#e6fff3> — открыть"
        config.catalog().all.map(MountDefinition::id).toSet() shouldBe
            bundledConfig("merge-forward-catalog").catalog().all.map(MountDefinition::id).toSet() + "operator_mount"
        checkNotNull(config.catalog()["ravager"]).behaviors shouldBe
            checkNotNull(bundledConfig("merge-forward-ravager").catalog()["ravager"]).behaviors

        val merged = Files.readString(file)
        ConfigManager.ofModule(dataPath, "mounts.yml")
            .mergeMissingFromBundled("modules/mounts.yml") shouldBe false
        Files.readString(file) shouldBe merged
    }

    "bundled GUI remains resource-pack neutral" {
        val config = bundledConfig("generic-gui")

        MountGuiItemRole.entries.forEach { role ->
            config.guiStyle(role) shouldBe MountGuiItemStyle()
        }
    }

    "bundled GUI copy owns the dark titles" {
        val config = bundledConfig("gui-copy")

        config.listTitle shouldBe "<color:#20252b><bold>Коллекция маунтов</bold>"
        config.detailTitle shouldBe "<color:#20252b><bold><mount></bold>"
        config.progressionTitle shouldBe "<color:#20252b><bold>Развитие маунта</bold>"
        config.skinsTitle shouldBe "<color:#20252b><bold>Облики маунта</bold>"
        config.confirmTitle shouldBe "<color:#20252b><bold>Покупка маунта</bold>"
        config.guiText("common.action-footer", "").contains("▶") shouldBe true
        config.guiText("list.mount-acquirable-name", "") shouldBe "<color:#92bed8><mount>"
        config.guiText("detail.whistle-disabled-name", "") shouldBe "<color:#969696>Свисток недоступен"
        config.guiText("progression.mount-required", "") shouldBe "<color:#c42323>Сначала получите маунта"
    }

    "runtime GUI overlay accepts material and custom model data" {
        val dataPath = Files.createTempDirectory("arc-mounts-runtime-gui-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            moduleDir.resolve("mounts.yml"),
            """
            enabled: false
            gui:
              items:
                back:
                  material: BLUE_STAINED_GLASS_PANE
                  customModelData: 11013
                category-all:
                  material: COMPASS
                  customModelData: 11023
                category-flying:
                  material: FEATHER
                  customModelData: 11024
                category-walking:
                  material: SADDLE
                  customModelData: 11025
                category-swimming:
                  material: HEART_OF_THE_SEA
                  customModelData: 11026
            """.trimIndent(),
        )

        val config = MountModuleConfig.load(dataPath)
        config.guiStyle(MountGuiItemRole.BACK) shouldBe
            MountGuiItemStyle(Material.BLUE_STAINED_GLASS_PANE, 11013)
        config.guiStyle(MountGuiItemRole.CATEGORY_ALL) shouldBe
            MountGuiItemStyle(Material.COMPASS, 11023)
        config.guiStyle(MountGuiItemRole.CATEGORY_FLYING) shouldBe
            MountGuiItemStyle(Material.FEATHER, 11024)
        config.guiStyle(MountGuiItemRole.CATEGORY_WALKING) shouldBe
            MountGuiItemStyle(Material.SADDLE, 11025)
        config.guiStyle(MountGuiItemRole.CATEGORY_SWIMMING) shouldBe
            MountGuiItemStyle(Material.HEART_OF_THE_SEA, 11026)
    }

    "level scale is parsed explicitly and defaults to one when omitted" {
        val dataPath = Files.createTempDirectory("arc-mounts-level-scale-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            moduleDir.resolve("mounts.yml"),
            """
            enabled: false
            mounts:
              test_bee:
                type: flying
                entity: BEE
                item: BEE_SPAWN_EGG
                name: Bee
                acquisition: Test
                levels:
                  - {speed: 1.0, scale: 0.8, price: 1}
                  - {speed: 1.2, price: 2}
            """.trimIndent(),
        )

        val levels = checkNotNull(MountModuleConfig.load(dataPath).catalog()["test_bee"]).levels
        levels.map(MountLevelDefinition::scaleMultiplier) shouldBe listOf(0.8, 1.0)
    }

    "size tuning rejects malformed level gates and unknown fields" {
        listOf(
            "{id: standard, name: Standard, multiplier: 1.0, minimum-level: nope}",
            "{id: standard, name: Standard, multiplier: 1.0, minimum-leevl: 1}",
        ).forEachIndexed { index, option ->
            val dataPath = Files.createTempDirectory("arc-mounts-size-invalid-$index-")
            val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
            Files.writeString(
                moduleDir.resolve("mounts.yml"),
                """
                enabled: false
                mounts:
                  test_bee:
                    type: flying
                    entity: BEE
                    item: BEE_SPAWN_EGG
                    name: Bee
                    acquisition: Test
                    levels:
                      - {speed: 1.0, price: 1}
                    size-tuning:
                      - $option
                """.trimIndent(),
            )

            shouldThrow<IllegalArgumentException> { MountModuleConfig.load(dataPath).catalog() }
        }
    }

    "per-mount motion timing overrides inherit unspecified global values" {
        val dataPath = Files.createTempDirectory("arc-mounts-motion-override-")
        val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            moduleDir.resolve("mounts.yml"),
            """
            enabled: false
            movement:
              acceleration-time: 900ms
              deceleration-time: 350ms
              turn-time: 200ms
            mounts:
              test_bee:
                type: flying
                entity: BEE
                item: BEE_SPAWN_EGG
                name: Bee
                acquisition: Test
                motion:
                  acceleration-time: 0s
                  turn-time: 400ms
                levels:
                  - {speed: 1.0, price: 1}
            """.trimIndent(),
        )

        val config = MountModuleConfig.load(dataPath)
        val timing = checkNotNull(config.catalog()["test_bee"]).motion.resolve(config.motionTiming)

        timing shouldBe
            MountMotionTiming(
                accelerationTime = Duration.ZERO,
                decelerationTime = Duration.ofMillis(350),
                turnTime = Duration.ofMillis(400),
            )
    }
})

private fun bundledConfig(label: String): MountModuleConfig {
    val dataPath = Files.createTempDirectory("arc-mounts-$label-")
    val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
    val resource = checkNotNull(MountModuleConfigTest::class.java.getResourceAsStream("/modules/mounts.yml"))
    resource.use { input -> Files.newOutputStream(moduleDir.resolve("mounts.yml")).use(input::copyTo) }
    return MountModuleConfig.load(dataPath)
}
