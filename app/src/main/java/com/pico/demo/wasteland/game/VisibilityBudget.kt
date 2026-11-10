package com.pico.demo.wasteland.game

import kotlin.math.sqrt

/** Conservative stereo visibility with a wide peripheral margin and an always-visible near zone. */
object VisibilityBudget {
    /** Keep contact shadows by distance to the bounds, with 4 m hysteresis to avoid flicker. */
    fun castsShadow(dx: Float, dz: Float, radius: Float, range: Float, wasCasting: Boolean): Boolean {
        val limit = radius + range + if (wasCasting) 4f else 0f
        return dx * dx + dz * dz <= limit * limit
    }

    fun visible(dx: Float, dy: Float, dz: Float, radius: Float, range: Float,
        forwardX: Float, forwardY: Float, forwardZ: Float): Boolean {
        val distance2 = dx * dx + dy * dy + dz * dz
        if (distance2 <= (18f + radius) * (18f + radius)) return true
        if (distance2 > (range + radius) * (range + radius)) return false
        // More than 110 degrees from gaze must be wholly outside the generous bounding sphere.
        return dx * forwardX + dy * forwardY + dz * forwardZ >= -0.35f * sqrt(distance2) - radius
    }
}
