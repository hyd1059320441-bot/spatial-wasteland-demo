package com.pico.demo.wasteland.game

import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlin.math.hypot

/** Radial dead zone and unit-speed diagonals, independent of tracking or rendering. */
object Locomotion {
    const val DEAD_ZONE = 0.18f
    const val SPEED = 3.4f
    data class Step(val x: Float, val z: Float)
    /** Rotation is the current HMD pose converted into the gameplay root's coordinates.
     * Use head-right near vertical gaze so looking up/down never changes walking speed. */
    fun headRelativeStep(stickX: Float, stickY: Float, headRotation: Quat, dt: Float): Step {
        val forward = headRotation.rotateVector(Vector3(0f, 0f, -1f))
        if (hypot(forward.x, forward.z) > 0.1f)
            return step(stickX, stickY, forward.x, forward.z, dt)
        val right = headRotation.rotateVector(Vector3(1f, 0f, 0f))
        return step(stickX, stickY, right.z, -right.x, dt)
    }
    fun step(stickX: Float, stickY: Float, forwardX: Float, forwardZ: Float, dt: Float): Step {
        if (!stickX.isFinite() || !stickY.isFinite() || !forwardX.isFinite() ||
            !forwardZ.isFinite() || !dt.isFinite() || dt <= 0f)
            return Step(0f, 0f)
        val magnitude = hypot(stickX, stickY)
        val heading = hypot(forwardX, forwardZ)
        if (magnitude <= DEAD_ZONE || heading < 0.001f) return Step(0f, 0f)
        val inputScale = ((magnitude.coerceAtMost(1f) - DEAD_ZONE) / (1f - DEAD_ZONE)) / magnitude
        val x = stickX * inputScale
        val y = stickY * inputScale
        val fx = forwardX / heading
        val fz = forwardZ / heading
        val distance = SPEED * dt.coerceAtMost(0.05f)
        return Step((-fz * x + fx * y) * distance, (fx * x + fz * y) * distance)
    }
}
