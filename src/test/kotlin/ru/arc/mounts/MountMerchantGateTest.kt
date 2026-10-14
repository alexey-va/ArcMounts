package ru.arc.mounts

import io.mockk.every
import io.mockk.mockk
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

class MountMerchantGateTest : StringSpec({
    val world = mockk<World>()
    val otherWorld = mockk<World>()
    every { world.uid } returns UUID.randomUUID()
    every { otherWorld.uid } returns UUID.randomUUID()

    "accepts the live spawn merchant radius only in the spawn world" {
        MountMerchantGate.isWithin(
            "rc_origin_spawn",
            Location(world, 58.0, 70.0, -118.0),
            Location(world, 62.0, 70.0, -118.0),
        ) shouldBe true
        MountMerchantGate.isWithin(
            "world",
            Location(world, 58.0, 70.0, -118.0),
            Location(world, 58.0, 70.0, -118.0),
        ) shouldBe false
        MountMerchantGate.isWithin(
            "rc_origin_spawn",
            Location(world, 70.0, 70.0, -118.0),
            Location(world, 58.0, 70.0, -118.0),
        ) shouldBe false
        MountMerchantGate.isWithin(
            "rc_origin_spawn",
            Location(world, 58.0, 70.0, -118.0),
            Location(otherWorld, 58.0, 70.0, -118.0),
        ) shouldBe false
    }
})
