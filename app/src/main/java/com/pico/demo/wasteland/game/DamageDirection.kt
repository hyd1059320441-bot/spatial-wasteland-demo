package com.pico.demo.wasteland.game

import kotlin.math.abs

/** Peripheral indicators follow the headset's horizontal orientation, without moving the camera. */
object DamageDirection {
    enum class Side(val label: String) { LEFT("左侧"), RIGHT("右侧"), FRONT("前方"), REAR("后方") }

    fun side(offsetX: Float, offsetZ: Float, forwardX: Float, forwardZ: Float): Side {
        val front = offsetX * forwardX + offsetZ * forwardZ
        val right = offsetX * -forwardZ + offsetZ * forwardX
        if (!front.isFinite() || !right.isFinite()) return Side.FRONT
        return if (abs(right) > abs(front)) {
            if (right > 0f) Side.RIGHT else Side.LEFT
        } else if (front >= 0f) Side.FRONT else Side.REAR
    }
}
