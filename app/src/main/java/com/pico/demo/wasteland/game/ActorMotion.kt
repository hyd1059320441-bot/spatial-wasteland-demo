package com.pico.demo.wasteland.game

/** Holds locomotion across sub-frame hit updates and short direction/route jitter. */
class ActorMotion {
    private var clip = "Idle"
    private var stillTime = 0f
    fun select(alive: Boolean, melee: Boolean, attacking: Boolean,
        running: Boolean, dt: Float): String {
        if (!alive) { clip = "Death"; return clip }
        if (attacking) {
            stillTime = 0f; clip = if (melee) "Punch" else "Fire"
        } else if (running) {
            stillTime = 0f; clip = "Walking"
        } else {
            stillTime += dt
            if (clip != "Walking" || stillTime >= 0.16f) clip = "Idle"
        }
        return clip
    }
    fun reset() { clip = "Idle"; stillTime = 0f }
}
