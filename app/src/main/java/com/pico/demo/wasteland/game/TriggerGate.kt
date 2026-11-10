package com.pico.demo.wasteland.game

/** A new tracking session must observe a released analog trigger before it can fire. */
class TriggerGate {
    private var armed = false
    private var held = false
    fun sample(analog: Float?, fresh: Boolean, usable: Boolean): Boolean {
        if (!fresh || !usable || analog == null || !analog.isFinite() || analog !in 0f..1f) {
            reset(); return false
        }
        if (analog <= 0.25f) { armed = true; held = false }
        else if (armed && analog >= 0.75f) held = true
        return held
    }
    fun reset() { armed = false; held = false }
}
