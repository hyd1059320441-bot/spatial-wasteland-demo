package com.pico.demo.wasteland.game

import kotlin.math.sqrt

/** First intersection fraction along a swept projectile with the player's vertical body capsule. */
object CombatGeometry {
    const val PLAYER_RADIUS = 0.28f
    const val EYE_TO_CROWN = EyeHeight.CAPSULE - EyeHeight.DEFAULT
    const val BOLT_RADIUS = 0.024f
    /** A telegraphed baton swing has a fixed 80-degree cone; stepping past it avoids damage. */
    fun meleeArc(ax: Float, az: Float, aimX: Float, aimZ: Float, x: Float, z: Float): Boolean {
        if (!listOf(ax, az, aimX, aimZ, x, z).all { it.isFinite() }) return false
        val dx = x - ax; val dz = z - az
        val distanceSquared = dx * dx + dz * dz
        if (distanceSquared >= 1.75f * 1.75f) return false
        val tx = aimX - ax; val tz = aimZ - az
        val aimSquared = tx * tx + tz * tz
        if (distanceSquared < 0.01f || aimSquared < 0.01f) return true
        return (dx * tx + dz * tz) / sqrt(distanceSquared * aimSquared) >= 0.7660444f
    }

    fun capsuleHit(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        x: Float, z: Float, feet: Float, head: Float, radius: Float = 0.3f): Float? {
        return movingCapsuleHit(ax, ay, az, bx, by, bz,
            x, z, feet, head, x, z, feet, head, radius)
    }

    /** Relative sweep covers stick/head motion and crouching during the same projectile frame.
     * Head means the crown of the body, not the eye. Radius may include the bolt's thickness. */
    fun movingCapsuleHit(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        startX: Float, startZ: Float, startFeet: Float, startHead: Float,
        endX: Float, endZ: Float, endFeet: Float, endHead: Float,
        radius: Float = PLAYER_RADIUS + BOLT_RADIUS): Float? {
        if (!listOf(ax, ay, az, bx, by, bz, startX, startZ, startFeet, startHead,
                endX, endZ, endFeet, endHead, radius).all { it.isFinite() } || radius <= 0f) return null
        val dx = bx - ax - (endX - startX)
        val dy = by - ay
        val dz = bz - az - (endZ - startZ)
        val ox = ax - startX; val oz = az - startZ
        val low = startFeet + radius
        val high = maxOf(low, startHead - radius)
        val endLow = endFeet + radius
        val endHigh = maxOf(endLow, endHead - radius)
        val vertical = ay - ay.coerceIn(low, high)
        if (ox * ox + oz * oz + vertical * vertical <= radius * radius) return 0f
        var first: Float? = null
        fun accept(t: Float) {
            if (t in 0f..1f && (first == null || t < first!!)) first = t
        }
        fun roots(a: Float, b: Float, c: Float, valid: (Float) -> Boolean) {
            if (a < 0.0000001f) return
            val discriminant = b * b - 4f * a * c
            if (discriminant < 0f) return
            for (t in listOf((-b - sqrt(discriminant)) / (2f * a),
                    (-b + sqrt(discriminant)) / (2f * a)))
                if (valid(t)) accept(t)
        }
        roots(dx * dx + dz * dz, 2f * (ox * dx + oz * dz),
            ox * ox + oz * oz - radius * radius) {
                ay + dy * it in (low + (endLow - low) * it)..(high + (endHigh - high) * it)
            }
        for ((center, endCenter) in listOf(low to endLow, high to endHigh)) {
            val oy = ay - center
            val relativeY = dy - (endCenter - center)
            roots(dx * dx + relativeY * relativeY + dz * dz,
                2f * (ox * dx + oy * relativeY + oz * dz),
                ox * ox + oy * oy + oz * oz - radius * radius) { true }
        }
        return first
    }
}
