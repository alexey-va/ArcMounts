package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class MountRamStateTest : StringSpec({
    val ram = MountRamBehavior(id = "ravager-ram", displayName = "Таран")

    "fresh sprint-forward press requests once and waits for acceleration" {
        val requested =
            advanceMountRam(
                MountRamState(),
                ram,
                tick = 10,
                input = MountInputState(forward = true, sprint = true),
                grounded = true,
                speedFraction = 0.2,
            )
        requested.state.phase shouldBe MountRamPhase.REQUESTED

        val held =
            advanceMountRam(
                requested.state,
                ram,
                tick = 11,
                input = MountInputState(forward = true, sprint = true),
                grounded = true,
                speedFraction = 0.7,
            )
        held.activated shouldBe true
        held.state.phase shouldBe MountRamPhase.ACTIVE
    }

    "ram consumes exactly one impact and cannot retrigger while held" {
        val active =
            MountRamState(
                phase = MountRamPhase.ACTIVE,
                previousTriggerPressed = true,
                activeUntilTick = 20,
                readyAtTick = 90,
            )
        val consumed = consumeMountRam(active)
        consumed.phase shouldBe MountRamPhase.COOLDOWN

        val held =
            advanceMountRam(
                consumed,
                ram,
                tick = 90,
                input = MountInputState(forward = true, sprint = true),
                grounded = true,
                speedFraction = 1.0,
            )
        held.state.phase shouldBe MountRamPhase.READY
        held.activated shouldBe false
    }

    "release and re-press after cooldown creates a new request" {
        val released =
            advanceMountRam(
                MountRamState(phase = MountRamPhase.COOLDOWN, previousTriggerPressed = true, readyAtTick = 20),
                ram,
                tick = 20,
                input = MountInputState(),
                grounded = true,
                speedFraction = 0.0,
            )
        val pressed =
            advanceMountRam(
                released.state,
                ram,
                tick = 21,
                input = MountInputState(forward = true, sprint = true),
                grounded = true,
                speedFraction = 0.0,
            )
        pressed.state.phase shouldBe MountRamPhase.REQUESTED
    }

    "actual displacement keeps a blocked mount below the ram threshold" {
        val box = MountRamBounds(-0.8, -0.8, 0.8, 0.8)

        val forward = MotionVector(0.0, 0.0, 1.0)
        actualMountForwardSpeedFraction(box, box, maximumSpeed = 0.6, forwardDirection = forward) shouldBe 0.0
        actualMountForwardSpeedFraction(
            box,
            box.copy(minZ = -0.5, maxZ = 1.1),
            maximumSpeed = 0.6,
            forwardDirection = forward,
        ) shouldBe
            (0.5 plusOrMinus 1.0e-9)
        actualMountForwardSpeedFraction(
            box,
            box.copy(minZ = -1.1, maxZ = 0.5),
            maximumSpeed = 0.6,
            forwardDirection = forward,
        ) shouldBe 0.0
    }

    "directional sweep rejects targets behind and diagonal corners" {
        val mount = MountRamBounds(-0.8, -0.8, 0.8, 0.8)
        val forward = MotionVector(0.0, 0.0, 1.0)

        sweptRamIntersects(
            mount,
            mount,
            MountRamBounds(-0.3, 1.9, 0.3, 2.5),
            forward,
            reach = 1.25,
            lateralPadding = 0.35,
        ) shouldBe true
        sweptRamIntersects(
            mount,
            mount,
            MountRamBounds(-0.3, -2.0, 0.3, -1.4),
            forward,
            reach = 1.25,
            lateralPadding = 0.35,
        ) shouldBe false
        sweptRamIntersects(
            mount,
            mount,
            MountRamBounds(1.7, 1.9, 2.3, 2.5),
            forward,
            reach = 1.25,
            lateralPadding = 0.35,
        ) shouldBe false
    }

    "directional sweep includes a target exactly at authored reach" {
        val mount = MountRamBounds(-0.8, -0.8, 0.8, 0.8)

        sweptRamIntersects(
            mount,
            mount,
            MountRamBounds(-0.3, 2.05, 0.3, 2.65),
            MotionVector(0.0, 0.0, 1.0),
            reach = 1.25,
            lateralPadding = 0.0,
        ) shouldBe true
    }
})
