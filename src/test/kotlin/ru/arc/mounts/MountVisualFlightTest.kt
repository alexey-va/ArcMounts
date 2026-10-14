package ru.arc.mounts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import ru.arc.TestBase

class MountVisualFlightTest : TestBase() {
    @Test
    fun `temporary flight restores a rider who had no flight`() {
        val player = server.addPlayer("NoFlight")
        val originalSpeed = player.flySpeed
        val flight = MountVisualFlight(plugin)

        flight.begin(player, 0.45f)
        player.allowFlight shouldBe true
        player.isFlying shouldBe true
        player.flySpeed shouldBe 0.45f

        flight.restore(player)

        player.allowFlight shouldBe false
        player.isFlying shouldBe false
        player.flySpeed shouldBe originalSpeed
        player.persistentDataContainer.keys.contains(recoveryKey()) shouldBe false
    }

    @Test
    fun `preexisting flight and speed survive mount cleanup`() {
        val player = server.addPlayer("ExistingFlight")
        player.allowFlight = true
        player.flySpeed = 0.23f
        player.isFlying = true
        val flight = MountVisualFlight(plugin)

        flight.begin(player, 0.45f)
        flight.restore(player)

        player.allowFlight shouldBe true
        player.isFlying shouldBe true
        player.flySpeed shouldBe 0.23f
    }

    @Test
    fun `speed updates keep the original speed and yield to an external owner`() {
        val player = server.addPlayer("SpeedUpdates")
        val originalSpeed = player.flySpeed
        val flight = MountVisualFlight(plugin)
        flight.begin(player, 0.3f)
        flight.updateSpeed(player, 0.4f)
        player.flySpeed shouldBe 0.4f
        flight.restore(player)
        player.flySpeed shouldBe originalSpeed

        flight.begin(player, 0.3f)
        player.flySpeed = 0.2f
        flight.updateSpeed(player, 0.4f)
        player.flySpeed shouldBe 0.2f
        flight.restore(player)
        player.flySpeed shouldBe 0.2f
    }

    @Test
    fun `recovery works through a new helper and repeated cleanup is harmless`() {
        val player = server.addPlayer("RecoverFlight")
        val originalSpeed = player.flySpeed
        MountVisualFlight(plugin).begin(player, 0.45f)

        MountVisualFlight(plugin).recover(player)
        MountVisualFlight(plugin).restore(player)

        player.allowFlight shouldBe false
        player.isFlying shouldBe false
        player.flySpeed shouldBe originalSpeed
        player.persistentDataContainer.keys.contains(recoveryKey()) shouldBe false
    }

    @Test
    fun `cleanup preserves flight revocation and external fly speed changes`() {
        val player = server.addPlayer("ExternalFlightChange")
        val flight = MountVisualFlight(plugin)
        flight.begin(player, 0.45f)

        player.allowFlight = false
        player.isFlying = false
        player.flySpeed = 0.2f
        flight.restore(player)

        player.allowFlight shouldBe false
        player.isFlying shouldBe false
        player.flySpeed shouldBe 0.2f
    }

    @Test
    fun `cleanup does not revoke native flight after a change into creative`() {
        val player = server.addPlayer("CreativeFlight")
        val flight = MountVisualFlight(plugin)
        flight.begin(player, 0.45f)

        player.gameMode = GameMode.CREATIVE
        player.allowFlight = true
        player.isFlying = true
        flight.restore(player)

        player.allowFlight shouldBe true
        player.isFlying shouldBe true
    }

    @Test
    fun `cleanup does not carry creative flight into survival`() {
        val player = server.addPlayer("SurvivalFlight")
        player.gameMode = GameMode.CREATIVE
        player.allowFlight = true
        player.isFlying = true
        val flight = MountVisualFlight(plugin)
        flight.begin(player, 0.45f)

        player.gameMode = GameMode.SURVIVAL
        player.allowFlight = true
        player.isFlying = true
        flight.restore(player)

        player.allowFlight shouldBe false
        player.isFlying shouldBe false
    }

    @Test
    fun `malformed and wrong type markers fail closed for survival players`() {
        val flight = MountVisualFlight(plugin)
        val key = recoveryKey()

        val malformed = server.addPlayer("MalformedFlight")
        malformed.allowFlight = true
        malformed.isFlying = true
        malformed.flySpeed = 0.45f
        malformed.persistentDataContainer.set(key, PersistentDataType.STRING, "v9|broken")
        flight.recover(malformed)

        malformed.allowFlight shouldBe false
        malformed.isFlying shouldBe false
        malformed.flySpeed shouldBe 0.1f
        malformed.persistentDataContainer.keys.contains(key) shouldBe false

        val wrongType = server.addPlayer("WrongTypeFlight")
        wrongType.allowFlight = true
        wrongType.isFlying = true
        wrongType.persistentDataContainer.set(key, PersistentDataType.INTEGER, 1)
        flight.recover(wrongType)

        wrongType.allowFlight shouldBe false
        wrongType.isFlying shouldBe false
        wrongType.persistentDataContainer.keys.contains(key) shouldBe false
    }

    @Test
    fun `a failed grant rolls back flight and its recovery marker`() {
        val backingPlayer = server.addPlayer("FailedGrant")
        var allowFlight = false
        var flying = false
        val player = mockk<Player>(relaxed = true)
        every { player.persistentDataContainer } returns backingPlayer.persistentDataContainer
        every { player.gameMode } returns GameMode.SURVIVAL
        every { player.allowFlight } answers { allowFlight }
        every { player.isFlying } answers { flying }
        every { player.flySpeed } returns 0.1f
        every { player.allowFlight = any() } answers { allowFlight = firstArg() }
        every { player.isFlying = any() } answers { flying = firstArg() }
        every { player.flySpeed = any() } throws IllegalStateException("simulated speed assignment failure")

        shouldThrow<IllegalStateException> { MountVisualFlight(plugin).begin(player, 0.45f) }

        allowFlight shouldBe false
        flying shouldBe false
        backingPlayer.persistentDataContainer.keys.contains(recoveryKey()) shouldBe false
    }

    private fun recoveryKey() = NamespacedKey(plugin, MountVisualFlight.RECOVERY_KEY)
}
