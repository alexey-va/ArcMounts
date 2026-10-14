package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

class MountTrailGeometryTest : StringSpec({
    val trail =
        MountTrailDefinition(
            particle = "SOUL",
            displayName = "След душ",
            backOffset = 0.25,
            heightRatio = 0.5,
        )
    val bounds = MountTrailBounds(-0.5, 10.0, -1.0, 0.5, 12.0, 1.0)

    "rear trail follows the moving body instead of the entity origin" {
        val origin = mountTrailOrigin(bounds, 0f, MotionVector(0.0, 0.0, 1.0), trail)

        origin.x shouldBe (0.0 plusOrMinus 1.0e-9)
        origin.y shouldBe (11.0 plusOrMinus 1.0e-9)
        origin.z shouldBe (-1.25 plusOrMinus 1.0e-9)
    }

    "stationary trail uses yaw and stays finite" {
        val origin = mountTrailOrigin(bounds, 90f, MotionVector.ZERO, trail)

        origin.x shouldBe (0.75 plusOrMinus 1.0e-9)
        origin.y.isFinite() shouldBe true
        origin.z.isFinite() shouldBe true
    }

    "larger live bounds move the trail farther behind the body" {
        val small = mountTrailOrigin(bounds, 0f, MotionVector.ZERO, trail)
        val large = mountTrailOrigin(MountTrailBounds(-1.0, 10.0, -2.0, 1.0, 14.0, 2.0), 0f, MotionVector.ZERO, trail)

        (large.z < small.z) shouldBe true
        large.y shouldBe (12.0 plusOrMinus 1.0e-9)
    }

    "authored patterns stay bounded and never exceed their particle budget" {
        MountTrailPattern.entries.filterNot { it == MountTrailPattern.SCATTER }.forEach { pattern ->
            val patterned = trail.copy(pattern = pattern, count = 8)
            val points = mountTrailPoints(bounds, 0f, MotionVector(0.0, 0.0, 1.0), patterned, ticks = 37)
            val origin = mountTrailOrigin(bounds, 0f, MotionVector(0.0, 0.0, 1.0), patterned)

            points.size shouldBeLessThanOrEqual 8
            points.all { point ->
                point.x.isFinite() && point.y.isFinite() && point.z.isFinite() &&
                    kotlin.math.abs(point.x - origin.x) <= 2.0 &&
                    kotlin.math.abs(point.y - origin.y) <= 2.0 &&
                    kotlin.math.abs(point.z - origin.z) <= 2.0
            } shouldBe true
        }
    }

    "wave ribbon comet and heart retain distinct geometry behind the mount" {
        val patterns = listOf(MountTrailPattern.WAVE, MountTrailPattern.RIBBON, MountTrailPattern.COMET, MountTrailPattern.HEART)
        val shapes = patterns.map { pattern ->
            val definition = trail.copy(pattern = pattern, count = 8)
            mountTrailPoints(bounds, 0f, MotionVector(0.0, 0.0, 1.0), definition, ticks = 11)
        }
        shapes.distinct().size shouldBe patterns.size
        shapes.flatten().all { it.z <= bounds.minZ - trail.backOffset + 1.0e-9 } shouldBe true
        val ribbon = shapes[1]
        (ribbon[0].x + ribbon[1].x) shouldBe (0.0 plusOrMinus 1.0e-9)
        ribbon[0].z shouldBe ribbon[1].z
        val heart = shapes[3]
        (heart[1].x + heart[7].x) shouldBe (0.0 plusOrMinus 1.0e-9)
        heart[1].y shouldBe (heart[7].y plusOrMinus 1.0e-9)
    }

    "new patterns rotate with travel and stay bounded for giant mounts" {
        val giant = MountTrailBounds(-50.0, 0.0, -50.0, 50.0, 100.0, 50.0)
        val patterns = listOf(MountTrailPattern.WAVE, MountTrailPattern.RIBBON, MountTrailPattern.COMET, MountTrailPattern.HEART)
        patterns.forEach { pattern ->
            val definition = trail.copy(pattern = pattern, count = 8)
            val north = mountTrailPoints(giant, 0f, MotionVector(0.0, 0.0, 1.0), definition, ticks = 31)
            val east = mountTrailPoints(giant, 0f, MotionVector(1.0, 0.0, 0.0), definition, ticks = 31)
            north.zip(east).forEach { (a, b) ->
                a.x shouldBe (-b.z plusOrMinus 1.0e-9)
                a.z shouldBe (b.x plusOrMinus 1.0e-9)
                a.y shouldBe (b.y plusOrMinus 1.0e-9)
            }
            (north.maxOf { it.y } - north.minOf { it.y } <= 4.0) shouldBe true
        }
    }

    "double helix emits two opposite strands" {
        val helix = trail.copy(pattern = MountTrailPattern.DOUBLE_HELIX, count = 6)
        val points = mountTrailPoints(bounds, 0f, MotionVector.ZERO, helix, ticks = 0)
        val origin = mountTrailOrigin(bounds, 0f, MotionVector.ZERO, helix)

        (points[0].x - origin.x) shouldBe (-(points[1].x - origin.x) plusOrMinus 1.0e-9)
        (points[0].y - origin.y) shouldBe (-(points[1].y - origin.y) plusOrMinus 1.0e-9)
    }
})
