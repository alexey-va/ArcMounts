package ru.arc.mounts

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

internal enum class MountRamPhase {
    READY,
    REQUESTED,
    ACTIVE,
    COOLDOWN,
}

internal data class MountRamState(
    val phase: MountRamPhase = MountRamPhase.READY,
    val previousTriggerPressed: Boolean = false,
    val requestedUntilTick: Long = Long.MIN_VALUE,
    val activeUntilTick: Long = Long.MIN_VALUE,
    val readyAtTick: Long = Long.MIN_VALUE,
)

internal data class MountRamTransition(
    val state: MountRamState,
    val activated: Boolean = false,
)

internal fun advanceMountRam(
    current: MountRamState,
    behavior: MountRamBehavior,
    tick: Long,
    input: MountInputState,
    grounded: Boolean,
    speedFraction: Double,
): MountRamTransition {
    val pressed = input.forward && input.sprint
    val risingEdge = pressed && !current.previousTriggerPressed
    val requestTicks = (behavior.requestWindow.toMillis() / 50L).coerceAtLeast(1L)
    val cooldownTicks = (behavior.cooldown.toMillis() / 50L).coerceAtLeast(1L)
    var state = current.copy(previousTriggerPressed = pressed)

    if (state.phase == MountRamPhase.COOLDOWN && tick >= state.readyAtTick) {
        state = state.copy(phase = MountRamPhase.READY)
    }
    if (state.phase == MountRamPhase.ACTIVE && tick > state.activeUntilTick) {
        state = state.copy(phase = MountRamPhase.COOLDOWN)
    }
    if (state.phase == MountRamPhase.REQUESTED && tick > state.requestedUntilTick) {
        state = state.copy(phase = MountRamPhase.READY)
    }
    if (risingEdge && state.phase == MountRamPhase.READY) {
        state = state.copy(phase = MountRamPhase.REQUESTED, requestedUntilTick = tick + requestTicks)
    }
    if (
        state.phase == MountRamPhase.REQUESTED &&
        grounded &&
        speedFraction.isFinite() &&
        speedFraction >= behavior.minimumSpeedFraction
    ) {
        return MountRamTransition(
            state.copy(
                phase = MountRamPhase.ACTIVE,
                activeUntilTick = tick + behavior.activeWindowTicks - 1L,
                readyAtTick = tick + cooldownTicks,
            ),
            activated = true,
        )
    }
    return MountRamTransition(state)
}

internal fun consumeMountRam(state: MountRamState): MountRamState =
    if (state.phase == MountRamPhase.ACTIVE) state.copy(phase = MountRamPhase.COOLDOWN) else state

internal data class MountRamBounds(
    val minX: Double,
    val minZ: Double,
    val maxX: Double,
    val maxZ: Double,
) {
    init {
        require(listOf(minX, minZ, maxX, maxZ).all(Double::isFinite)) { "Mount ram bounds must be finite" }
        require(maxX >= minX && maxZ >= minZ) { "Mount ram bounds must be ordered" }
    }

    val centerX: Double get() = (minX + maxX) / 2.0
    val centerZ: Double get() = (minZ + maxZ) / 2.0
    val halfWidth: Double get() = (maxX - minX) / 2.0
    val halfDepth: Double get() = (maxZ - minZ) / 2.0
}

internal fun actualMountForwardSpeedFraction(
    previous: MountRamBounds,
    current: MountRamBounds,
    maximumSpeed: Double,
    forwardDirection: MotionVector,
): Double {
    if (!maximumSpeed.isFinite() || maximumSpeed <= 1.0e-9) return 0.0
    val forward = forwardDirection.normalizedHorizontal()
    if (forward == MotionVector.ZERO) return 0.0
    val displacement =
        (current.centerX - previous.centerX) * forward.x +
            (current.centerZ - previous.centerZ) * forward.z
    return displacement.coerceAtLeast(0.0) / maximumSpeed
}

internal fun sweptRamIntersects(
    previous: MountRamBounds,
    current: MountRamBounds,
    target: MountRamBounds,
    direction: MotionVector,
    reach: Double,
    lateralPadding: Double,
): Boolean {
    require(reach.isFinite() && reach >= 0.0) { "Mount ram reach must be finite and non-negative" }
    require(lateralPadding.isFinite() && lateralPadding >= 0.0) { "Mount ram padding must be finite and non-negative" }
    val forward = direction.normalizedHorizontal()
    if (forward == MotionVector.ZERO) return false
    val lateralX = -forward.z
    val lateralZ = forward.x

    fun forwardSupport(bounds: MountRamBounds): Double =
        abs(forward.x) * bounds.halfWidth + abs(forward.z) * bounds.halfDepth

    fun lateralSupport(bounds: MountRamBounds): Double =
        abs(lateralX) * bounds.halfWidth + abs(lateralZ) * bounds.halfDepth

    val previousFront = forwardSupport(previous)
    val currentFront = forwardSupport(current)
    val startX = previous.centerX + forward.x * previousFront
    val startZ = previous.centerZ + forward.z * previousFront
    val endX = current.centerX + forward.x * (currentFront + reach)
    val endZ = current.centerZ + forward.z * (currentFront + reach)

    val startProjection = startX * forward.x + startZ * forward.z
    val endProjection = endX * forward.x + endZ * forward.z
    val targetProjection = target.centerX * forward.x + target.centerZ * forward.z
    val targetForwardSupport = forwardSupport(target)
    if (targetProjection + targetForwardSupport < startProjection - GEOMETRY_EPSILON) return false
    if (targetProjection - targetForwardSupport > endProjection + GEOMETRY_EPSILON) return false

    val segmentX = endX - startX
    val segmentZ = endZ - startZ
    val segmentLengthSquared = segmentX * segmentX + segmentZ * segmentZ
    val targetOffsetX = target.centerX - startX
    val targetOffsetZ = target.centerZ - startZ
    val progress =
        if (segmentLengthSquared <= GEOMETRY_EPSILON) 0.0
        else ((targetOffsetX * segmentX + targetOffsetZ * segmentZ) / segmentLengthSquared).coerceIn(0.0, 1.0)
    val closestX = startX + segmentX * progress
    val closestZ = startZ + segmentZ * progress
    val lateralDistance = hypot(target.centerX - closestX, target.centerZ - closestZ)
    val allowedLateralDistance =
        max(lateralSupport(previous), lateralSupport(current)) + lateralSupport(target) + lateralPadding
    return lateralDistance <= allowedLateralDistance + GEOMETRY_EPSILON
}

private const val GEOMETRY_EPSILON = 1.0e-9
