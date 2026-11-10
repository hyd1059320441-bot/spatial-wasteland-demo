package com.pico.demo.wasteland.game

/** A gunshot can hit several heads, but multiple shotgun pellets must not repeat each cue. */
class HeadshotFeedback {
    private val slots = mutableSetOf<Int>()
    val hasHeadshot get() = slots.isNotEmpty()
    fun register(slot: Int, head: Boolean, damageApplied: Boolean): Boolean =
        head && damageApplied && slot >= 0 && slots.add(slot)
}
