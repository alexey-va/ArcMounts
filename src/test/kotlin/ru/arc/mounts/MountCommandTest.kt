package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.core.TestTaskScheduler
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MountCommandTest : StringSpec({
    val mount = testMount()
    val catalog = MountCatalog(listOf(mount))

    "plain mount command opens the player collection" {
        val fixture = commandFixture(catalog)

        fixture.command.onCommand(fixture.player, fixture.bukkitCommand, "mount", emptyArray()) shouldBe true

        verify(exactly = 1) { fixture.openMenu(fixture.player) }
        verify(exactly = 0) { fixture.sessions.spawn(any(), any(), any(), any()) }
    }

    "service subcommands route to distinct menus" {
        val fixture = commandFixture(catalog)
        fixture.command.onCommand(fixture.player, fixture.bukkitCommand, "mount", arrayOf("shop"))
        fixture.command.onCommand(fixture.player, fixture.bukkitCommand, "mount", arrayOf("upgrades"))
        fixture.command.onCommand(fixture.player, fixture.bukkitCommand, "mount", arrayOf("trade"))

        verify(exactly = 1) { fixture.openShop(fixture.player) }
        verify(exactly = 1) { fixture.openUpgrades(fixture.player) }
        verify(exactly = 1) { fixture.openTrade(fixture.player) }
        verify(exactly = 0) { fixture.openMenu(fixture.player) }
    }

    "admin summon uses a configured level instead of arbitrary raw speed" {
        val fixture = commandFixture(catalog, admin = true)
        every {
            fixture.sessions.spawn(
                player = fixture.player,
                definition = mount,
                settings =
                    MountRuntimeSettings(
                        speed = mount.level(3).speed,
                        walkingStepHeight = 4.0,
                        handlingMultiplier = mount.level(3).handlingMultiplier,
                        sprintMultiplier = mount.level(3).sprintMultiplier,
                        scaleMultiplier = mount.level(3).scaleMultiplier,
                        skin = mount.skin("baby"),
                        glow = false,
                        abilityUpgrades = mount.abilities.upgrades,
                    ),
                durationMillis = 10_000L,
            )
        } returns MountSpawnResult.SUCCESS

        fixture.command.onCommand(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "summon", "bee", "3", "baby"),
        ) shouldBe true

        verify(exactly = 1) {
            fixture.sessions.spawn(
                player = fixture.player,
                definition = mount,
                settings =
                    MountRuntimeSettings(
                        speed = 0.9,
                        walkingStepHeight = 4.0,
                        handlingMultiplier = 1.28,
                        sprintMultiplier = 1.12,
                        scaleMultiplier = 1.0,
                        skin = mount.skin("baby"),
                        glow = false,
                        abilityUpgrades = mount.abilities.upgrades,
                    ),
                durationMillis = 10_000L,
            )
        }
    }

    "non-admin cannot use administrative subcommands or see them in completion" {
        val fixture = commandFixture(catalog, admin = false)

        fixture.command.onCommand(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "summon", "bee"),
        ) shouldBe true

        verify(exactly = 0) { fixture.sessions.spawn(any(), any(), any(), any()) }
        fixture.command.onCommand(fixture.player, fixture.bukkitCommand, "mount", arrayOf("admin", "grant-all", "Rider"))
        verify(exactly = 0) { fixture.ownership.grantAll(any(), any()) }
        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf(""))
            .shouldContainExactly("help", "menu", "owned", "pack", "shop", "trade", "upgrades", "view")
    }

    "admin can grant and revoke levels, skins, glow and abilities" {
        val fixture = commandFixture(catalog, admin = true)

        listOf(
            arrayOf("admin", "grant", "level", "Rider", "bee", "3"),
            arrayOf("admin", "grant", "skin", "Rider", "bee", "baby"),
            arrayOf("admin", "grant", "glow", "Rider", "bee"),
            arrayOf("admin", "grant", "ability", "Rider", "bee", "night-vision"),
            arrayOf("admin", "revoke", "level", "Rider", "bee", "3"),
            arrayOf("admin", "revoke", "skin", "Rider", "bee", "baby"),
            arrayOf("admin", "revoke", "glow", "Rider", "bee"),
            arrayOf("admin", "revoke", "ability", "Rider", "bee", "night-vision"),
        ).forEach { args ->
            fixture.command.onCommand(fixture.console, fixture.bukkitCommand, "mount", args) shouldBe true
        }
        fixture.scheduler.executeImmediate()

        verify(exactly = 1) { fixture.ownership.grantLevel(fixture.playerId, mount, 3) }
        verify(exactly = 1) { fixture.ownership.grantSkin(fixture.playerId, mount, mount.skin("baby")!!) }
        verify(exactly = 1) { fixture.ownership.grantGlow(fixture.playerId, mount) }
        verify(exactly = 1) { fixture.ownership.revokeLevel(fixture.playerId, mount, 3) }
        verify(exactly = 1) { fixture.ownership.revokeSkin(fixture.playerId, mount, mount.skin("baby")!!) }
        verify(exactly = 1) { fixture.ownership.revokeGlow(fixture.playerId, mount) }
        verify(exactly = 1) { fixture.ownership.grantAbility(fixture.playerId, mount, mount.ability("night-vision")!!) }
        verify(exactly = 1) { fixture.ownership.revokeAbility(fixture.playerId, mount, mount.ability("night-vision")!!) }
        verify(exactly = 8) { fixture.console.sendMessage(any<Component>()) }
    }

    "admin completion follows the unified command tree" {
        val specialMount =
            mount.copy(
                sizeOptions =
                    listOf(
                        MountSizeOptionDefinition("standard", "Обычный", 1.0),
                        MountSizeOptionDefinition("colossal", "Колоссальный", 10.0, grantOnly = true),
                    ),
            )
        val fixture = commandFixture(MountCatalog(listOf(specialMount)), admin = true)

        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf(""))
            .shouldContainExactly("admin", "help", "menu", "owned", "pack", "shop", "trade", "upgrades", "view")
        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf("admin", ""))
            .shouldContainExactly("grant", "grant-all", "revoke", "revoke-all", "summon")
        fixture.command.onTabComplete(fixture.player, fixture.bukkitCommand, "mount", arrayOf("admin", "grant", ""))
            .shouldContainExactly("ability", "glow", "level", "size", "skin")
        fixture.command.onTabComplete(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "skin", "Rider", "bee", ""),
        ).shouldContainExactly("baby")
        fixture.command.onTabComplete(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "ability", "Rider", "bee", ""),
        ).shouldContainExactly("night-vision")
        fixture.command.onTabComplete(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "size", "Rider", "bee", ""),
        ).shouldContainExactly("colossal")
    }

    "admin can grant and revoke command-only sizes" {
        val special = MountSizeOptionDefinition("colossal", "Колоссальный", 10.0, grantOnly = true)
        val specialMount =
            mount.copy(
                sizeOptions = listOf(MountSizeOptionDefinition("standard", "Обычный", 1.0), special),
            )
        val fixture = commandFixture(MountCatalog(listOf(specialMount)), admin = true)

        fixture.command.onCommand(
            fixture.console,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "size", "Rider", "bee", "colossal"),
        ) shouldBe true
        fixture.command.onCommand(
            fixture.console,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "revoke", "size", "Rider", "bee", "colossal"),
        ) shouldBe true
        fixture.scheduler.executeImmediate()

        verify(exactly = 1) { fixture.ownership.grantSize(fixture.playerId, specialMount, special) }
        verify(exactly = 1) { fixture.ownership.revokeSize(fixture.playerId, specialMount, special) }
    }

    "admin grant-all persists the complete catalog before reporting success" {
        val secondMount = testMount().copy(id = "bat", displayName = "Летучая мышь")
        val mounts = MountCatalog(listOf(mount, secondMount))
        val fixture = commandFixture(mounts, admin = true)
        val saved = CompletableFuture<Void>()
        every { fixture.ownership.grantAll(fixture.playerId, mounts.all) } returns saved

        fixture.command.onCommand(
            fixture.console,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant-all", "Rider"),
        ) shouldBe true
        fixture.scheduler.executeImmediate()
        verify(exactly = 0) { fixture.console.sendMessage(any<Component>()) }
        saved.complete(null)
        fixture.scheduler.executeImmediate()

        verify(exactly = 1) { fixture.ownership.grantAll(fixture.playerId, mounts.all) }
        verify(exactly = 0) { fixture.ownership.grantLevel(any(), any(), any()) }
        verify(exactly = 1) {
            fixture.console.sendMessage(match<Component> {
                PlainTextComponentSerializer.plainText().serialize(it).contains("Все маунты и улучшения выданы")
            })
        }
    }

    "admin grant-all reports a failed asynchronous save without confirming unlocks" {
        val fixture = commandFixture(catalog, admin = true)
        val saved = CompletableFuture<Void>()
        every { fixture.ownership.grantAll(fixture.playerId, catalog.all) } returns saved

        fixture.command.onCommand(fixture.console, fixture.bukkitCommand, "mount", arrayOf("admin", "grant-all", "Rider"))
        fixture.scheduler.executeImmediate()
        verify(exactly = 0) { fixture.console.sendMessage(any<Component>()) }
        saved.completeExceptionally(IllegalStateException("save failed"))
        fixture.scheduler.executeImmediate()

        verify(exactly = 1) {
            fixture.console.sendMessage(match<Component> {
                PlainTextComponentSerializer.plainText().serialize(it).contains("Не удалось выдать")
            })
        }
    }

    "admin revoke-all without a target revokes the executing player's mounts" {
        val fixture = commandFixture(catalog, admin = true)
        every { fixture.ownership.revokeAll(fixture.playerId, catalog.all) } returns CompletableFuture.completedFuture(4)

        fixture.command.onCommand(
            fixture.player,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "revoke-all"),
        ) shouldBe true
        fixture.scheduler.executeImmediate()

        verify(exactly = 1) { fixture.ownership.revokeAll(fixture.playerId, catalog.all) }
        verify(exactly = 1) { fixture.sessions.remove(fixture.playerId, MountRemovalReason.INVALID) }
    }

    "admin mutations reject trailing arguments instead of applying a partial parse" {
        val fixture = commandFixture(catalog, admin = true)

        fixture.command.onCommand(
            fixture.console,
            fixture.bukkitCommand,
            "mount",
            arrayOf("admin", "grant", "level", "Rider", "bee", "3", "unexpected"),
        ) shouldBe true

        verify(exactly = 0) { fixture.ownership.grantLevel(any(), any(), any()) }
    }

    "plugin descriptor exposes only the unified mount command" {
        val descriptor = checkNotNull(MountCommandTest::class.java.getResource("/plugin.yml")).readText()

        Regex("(?m)^  mount:").containsMatchIn(descriptor) shouldBe true
        Regex("(?m)^  mounts:").containsMatchIn(descriptor) shouldBe false
        Regex("(?m)^  ride-mob:").containsMatchIn(descriptor) shouldBe false
        Regex("(?m)^  unlock-mount:").containsMatchIn(descriptor) shouldBe false
        descriptor.contains("arc.mounts.ride") shouldBe false
    }
})

private data class MountCommandFixture(
    val command: MountCommand,
    val player: Player,
    val console: CommandSender,
    val bukkitCommand: Command,
    val sessions: MountSessionController,
    val ownership: MountOwnership,
    val openMenu: (Player) -> Unit,
    val openShop: (Player) -> Unit,
    val openUpgrades: (Player) -> Unit,
    val openTrade: (Player) -> Unit,
    val scheduler: TestTaskScheduler,
    val playerId: UUID,
)

private fun commandFixture(catalog: MountCatalog, admin: Boolean = false): MountCommandFixture {
    val playerId = UUID.randomUUID()
    val server = mockk<Server>(relaxed = true)
    val player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns playerId
        every { name } returns "Rider"
        every { hasPermission("arc.mounts.admin") } returns admin
        every { hasPermission("arc.mounts.use") } returns true
        every { this@mockk.server } returns server
    }
    val console = mockk<CommandSender>(relaxed = true) {
        every { hasPermission("arc.mounts.admin") } returns true
        every { this@mockk.server } returns server
    }
    every { server.getPlayerExact("Rider") } returns player
    every { server.onlinePlayers } returns mutableListOf(player)
    val ownership = mockk<MountOwnership> {
        every { resolveUniqueId(any()) } returns CompletableFuture.completedFuture(playerId)
        every { grantLevel(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { grantAll(any(), any()) } returns CompletableFuture.completedFuture(null)
        every { grantSkin(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { grantGlow(any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeLevel(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeSkin(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeGlow(any(), any()) } returns CompletableFuture.completedFuture(null)
        every { grantAbility(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeAbility(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { grantSize(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeSize(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        every { revokeAll(any(), any()) } returns CompletableFuture.completedFuture(0)
    }
    val config = mockk<MountModuleConfig> {
        every { adminSessionDuration } returns java.time.Duration.ofSeconds(10)
        every { tuning } returns MountTuningDefinition(listOf(50, 65, 80, 90, 100), listOf(110, 150, 200, 300, 400), listOf(110, 200, 400))
        every { message(any(), any()) } answers { secondArg() }
    }
    val sessions = mockk<MountSessionController>(relaxed = true)
    val openMenu = mockk<(Player) -> Unit>(relaxed = true)
    val openShop = mockk<(Player) -> Unit>(relaxed = true)
    val openUpgrades = mockk<(Player) -> Unit>(relaxed = true)
    val openTrade = mockk<(Player) -> Unit>(relaxed = true)
    val scheduler = TestTaskScheduler()
    val command =
        MountCommand(
            config = { config },
            catalog = { catalog },
            ownership = ownership,
            sessions = sessions,
            scheduler = scheduler,
            openMenu = openMenu,
            openShop = openShop,
            openUpgrades = openUpgrades,
            openTrade = openTrade,
        )
    return MountCommandFixture(command, player, console, mockk(relaxed = true), sessions, ownership, openMenu, openShop, openUpgrades, openTrade, scheduler, playerId)
}
