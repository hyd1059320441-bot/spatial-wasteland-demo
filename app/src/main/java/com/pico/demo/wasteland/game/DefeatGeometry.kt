package com.pico.demo.wasteland.game

import com.pico.spatial.core.math.Vector3
import kotlin.math.*

/** A small debris fan travels behind the defeated robot and down, away from the player's eyes. */
object DefeatGeometry {
    const val DURATION = 0.42f
    const val MAX_RADIUS = 0.65f
    fun away(center: Vector3, player: Vector3): Vector3 {
        val dx = center.x - player.x; val dz = center.z - player.z
        val length = hypot(dx, dz)
        return if (length < 0.001f) Vector3(0f, 0f, -1f) else Vector3(dx / length, 0f, dz / length)
    }
    fun shardOffset(index: Int, elapsed: Float, away: Vector3, elite: Boolean): Vector3 {
        val t = elapsed.coerceIn(0f, DURATION)
        val strength = if (elite) 1.12f else 1f
        val side = Vector3(-away.z, 0f, away.x)
        val spread = (index - 2.5f) / 2.5f
        return away * (0.18f + t * 0.48f * strength) +
            side * (spread * (0.06f + t * 0.32f) * strength) +
            Vector3(0f, -0.12f - t * (0.12f + index % 2 * 0.08f) - 0.8f * t * t, 0f)
    }
    fun particlePoint(center: Vector3, player: Vector3) = center + away(center, player) * 0.35f +
        Vector3(0f, -0.22f, 0f)
    fun particlesAllowed(center: Vector3, player: Vector3) = (center - player).length() >= 1.8f
}
