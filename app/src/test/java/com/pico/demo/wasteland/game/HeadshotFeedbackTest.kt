package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test

class HeadshotFeedbackTest {
    @Test fun shotgunPelletsProduceOneHeadBurstPerEnemyAndOneShotSound() {
        val feedback = HeadshotFeedback()
        assertTrue(feedback.register(2, head = true, damageApplied = true))
        repeat(7) { assertFalse(feedback.register(2, head = true, damageApplied = true)) }
        assertTrue(feedback.hasHeadshot)
        assertTrue(feedback.register(3, head = true, damageApplied = true))
        assertTrue(HeadshotFeedback().register(2, head = true, damageApplied = true))
    }
    @Test fun bodyShieldCoreAndRejectedDamageDoNotBecomeHeadshots() {
        val feedback = HeadshotFeedback()
        assertFalse(feedback.register(2, head = false, damageApplied = true))
        assertFalse(feedback.register(2, head = true, damageApplied = false))
        assertFalse(feedback.register(-1, head = true, damageApplied = true))
        assertFalse(feedback.hasHeadshot)
        assertTrue(feedback.register(2, head = true, damageApplied = true))
    }
}
