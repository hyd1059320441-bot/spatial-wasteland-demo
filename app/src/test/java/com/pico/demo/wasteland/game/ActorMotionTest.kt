package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test

class ActorMotionTest {
    @Test fun zeroTimeHitUpdatesAndShortMovementJitterDoNotRestartLocomotion() {
        val motion = ActorMotion()
        assertEquals("Walking", motion.select(true, false, false, true, 0.03f))
        repeat(10) { assertEquals("Walking", motion.select(true, false, false, false, 0f)) }
        repeat(3) { assertEquals("Walking", motion.select(true, false, false, false, 0.03f)) }
        assertEquals("Walking", motion.select(true, false, false, true, 0.03f))
        assertEquals("Idle", motion.select(true, false, false, false, 0.17f))
    }
    @Test fun deathAndRespawnCannotLeaveADeathClipOnALiveActor() {
        val motion = ActorMotion()
        assertEquals("Fire", motion.select(true, false, true, false, 0.03f))
        assertEquals("Death", motion.select(false, false, false, false, 0.03f))
        motion.reset()
        assertEquals("Idle", motion.select(true, false, false, false, 0f))
        assertEquals("Punch", motion.select(true, true, true, false, 0.03f))
    }
}
