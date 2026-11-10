package com.pico.demo.wasteland.game

/** Eye coordinates and collision capsule height are independent. All distances are meters. */
object EyeHeight {
    const val DEFAULT = 1.65f
    const val CAPSULE = 1.75f
    const val MINIMUM = 0.35f

    fun onNewFloor(eyeY: Float, oldFloor: Float, newFloor: Float): Float =
        newFloor + (eyeY - oldFloor).coerceAtLeast(MINIMUM)

    fun afterRestart(physicalY: Float, referenceY: Float, standingHeight: Float): Float =
        (standingHeight + physicalY - referenceY).coerceAtLeast(MINIMUM)
}
