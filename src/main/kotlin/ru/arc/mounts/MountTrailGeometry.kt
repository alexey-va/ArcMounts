package ru.arc.mounts

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class MountTrailBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    init {
        require(listOf(minX, minY, minZ, maxX, maxY, maxZ).all(Double::isFinite)) {
            "Mount trail bounds must be finite"
        }
        require(maxX >= minX && maxY >= minY && maxZ >= minZ) { "Mount trail bounds are inverted" }
    }
}

internal fun mountTrailOrigin(
    bounds: MountTrailBounds,
    yawDegrees: Float,
    motionDirection: MotionVector,
    trail: MountTrailDefinition,
): MotionVector {
    val forward = mountTrailForward(yawDegrees, motionDirection)
    val width = bounds.maxX - bounds.minX
    val depth = bounds.maxZ - bounds.minZ
    val halfBodyAlongForward = abs(forward.x) * width / 2.0 + abs(forward.z) * depth / 2.0
    val rearDistance = halfBodyAlongForward + trail.backOffset
    return MotionVector(
        x = (bounds.minX + bounds.maxX) / 2.0 - forward.x * rearDistance,
        y = bounds.minY + (bounds.maxY - bounds.minY) * trail.heightRatio,
        z = (bounds.minZ + bounds.maxZ) / 2.0 - forward.z * rearDistance,
    )
}

internal fun mountTrailPoints(
    bounds: MountTrailBounds,
    yawDegrees: Float,
    motionDirection: MotionVector,
    trail: MountTrailDefinition,
    ticks: Long,
): List<MotionVector> {
    val origin = mountTrailOrigin(bounds, yawDegrees, motionDirection, trail)
    if (trail.pattern == MountTrailPattern.SCATTER) return listOf(origin)

    val forward = mountTrailForward(yawDegrees, motionDirection)
    val right = MotionVector(forward.z, 0.0, -forward.x)
    val width = bounds.maxX - bounds.minX
    val height = bounds.maxY - bounds.minY
    val depth = bounds.maxZ - bounds.minZ
    val bodyScale = min(max(width, depth), max(height, 0.5))
    val radius = (bodyScale * 0.22).coerceIn(0.18, 2.0)
    val phase = ticks.toDouble() * 0.34

    return List(trail.count) { index ->
        val fraction = index.toDouble() / trail.count
        val ringAngle = phase + fraction * Math.PI * 2.0
        when (trail.pattern) {
            MountTrailPattern.SPIRAL ->
                trailPoint(origin, forward, right, ringAngle, radius, index * 0.12)
            MountTrailPattern.RING ->
                trailPoint(origin, forward, right, ringAngle, radius, 0.0)
            MountTrailPattern.DOUBLE_HELIX -> {
                val pair = index / 2
                val angle = phase - pair * 0.72 + if (index % 2 == 0) 0.0 else Math.PI
                trailPoint(origin, forward, right, angle, radius, pair * 0.14)
            }
            MountTrailPattern.ORBIT ->
                MotionVector(
                    origin.x + right.x * cos(ringAngle) * radius + forward.x * sin(ringAngle) * radius,
                    origin.y + sin(ringAngle * 2.0) * radius * 0.28,
                    origin.z + right.z * cos(ringAngle) * radius + forward.z * sin(ringAngle) * radius,
                )
            MountTrailPattern.PULSE -> {
                val pulseRadius = radius * (0.45 + 0.55 * ((sin(phase) + 1.0) / 2.0))
                trailPoint(origin, forward, right, ringAngle, pulseRadius, 0.0)
            }
            MountTrailPattern.WAVE -> {
                val angle = phase - index * 0.65
                offsetPoint(origin, forward, right, sin(angle) * radius, cos(angle) * radius * 0.2, index * 0.16)
            }
            MountTrailPattern.RIBBON -> {
                val pair = index / 2
                val side = if (index % 2 == 0) 1.0 else -1.0
                val angle = phase - pair * 0.6
                offsetPoint(origin, forward, right, side * radius * (0.55 + 0.35 * sin(angle)),
                    cos(angle) * radius * 0.35, pair * 0.28)
            }
            MountTrailPattern.COMET -> {
                // The fan widens behind the bright head; its bounded trail follows motion.
                val progress = index.toDouble() / max(trail.count - 1, 1)
                trailPoint(origin, forward, right, ringAngle, radius * progress * 0.65, progress * 1.1)
            }
            MountTrailPattern.HEART -> {
                val angle = fraction * Math.PI * 2.0
                val pulse = 0.8 + 0.1 * sin(phase)
                val horizontal = sin(angle) * sin(angle) * sin(angle)
                val vertical = (13 * cos(angle) - 5 * cos(2 * angle) - 2 * cos(3 * angle) - cos(4 * angle)) / 17.0
                offsetPoint(origin, forward, right, horizontal * radius * pulse, vertical * radius * pulse, 0.0)
            }
            MountTrailPattern.SCATTER -> origin
        }
    }
}

private fun mountTrailForward(yawDegrees: Float, motionDirection: MotionVector): MotionVector =
    motionDirection.normalizedHorizontal().takeUnless { it == MotionVector.ZERO }
        ?: run {
            val yaw = Math.toRadians(yawDegrees.toDouble().takeIf(Double::isFinite) ?: 0.0)
            MotionVector(-sin(yaw), 0.0, cos(yaw))
        }

private fun trailPoint(
    origin: MotionVector,
    forward: MotionVector,
    right: MotionVector,
    angle: Double,
    radius: Double,
    rearward: Double,
): MotionVector =
    MotionVector(
        origin.x - forward.x * rearward + right.x * cos(angle) * radius,
        origin.y + sin(angle) * radius,
        origin.z - forward.z * rearward + right.z * cos(angle) * radius,
    )


private fun offsetPoint(
    origin: MotionVector,
    forward: MotionVector,
    right: MotionVector,
    horizontal: Double,
    vertical: Double,
    rearward: Double,
): MotionVector = MotionVector(
    origin.x - forward.x * rearward + right.x * horizontal,
    origin.y + vertical,
    origin.z - forward.z * rearward + right.z * horizontal,
)
