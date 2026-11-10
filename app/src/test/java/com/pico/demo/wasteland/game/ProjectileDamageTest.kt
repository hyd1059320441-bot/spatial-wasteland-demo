package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test

class ProjectileDamageTest {
    @Test fun crownAboveEyeAndBoltThicknessCountButClearMissesDoNot() {
        fun hit(y: Float, z: Float = 0f, eye: Float = EyeHeight.DEFAULT, floor: Float = 0f) =
            CombatGeometry.movingCapsuleHit(-2f, y, z, 2f, y, z,
                0f, 0f, floor - CombatGeometry.BOLT_RADIUS,
                eye + CombatGeometry.EYE_TO_CROWN + CombatGeometry.BOLT_RADIUS,
                0f, 0f, floor - CombatGeometry.BOLT_RADIUS,
                eye + CombatGeometry.EYE_TO_CROWN + CombatGeometry.BOLT_RADIUS)
        assertNotNull(hit(1.69f)) // Above eyes, still inside a 1.75 m adult head.
        assertNull(hit(1.8f))
        assertNotNull(hit(1f, 0.295f)) // Edge of actual 0.024 m bolt radius.
        assertNull(hit(1f, 0.32f))
        assertNotNull(hit(0.9f, eye = 0.85f))
        assertNull(hit(1.1f, eye = 0.85f))
        assertNotNull(hit(3.69f, eye = 3.65f, floor = 2f))
        assertNull(hit(1f, eye = 3.65f, floor = 2f))
    }

    @Test fun playerCrossingTheBoltBetweenFramesIsHit() {
        // Both endpoints miss; player moves across a stationary bolt.
        val contact = CombatGeometry.movingCapsuleHit(0f, 1f, 0f, 0f, 1f, 0f,
            -0.4f, 0f, 0f, 1.75f, 0.4f, 0f, 0f, 1.75f)
        assertNotNull(contact)
        assertEquals(0.12f, contact!!, 0.001f)
        assertNull(CombatGeometry.movingCapsuleHit(0f, 1f, 0.5f, 0f, 1f, 0.5f,
            -0.4f, 0f, 0f, 1.75f, 0.4f, 0f, 0f, 1.75f))
    }

    @Test fun fastVerticalAndSlopedBoltsHitAtStandingAndCrouchedHeights() {
        assertNotNull(CombatGeometry.movingCapsuleHit(0f, 4f, 0f, 0f, -1f, 0f,
            0f, 0f, 0f, 1.75f, 0f, 0f, 0f, 0.95f))
        assertNotNull(CombatGeometry.movingCapsuleHit(-1f, 2.5f, 0f, 1f, 0.5f, 0f,
            0f, 0f, 0f, 1.75f, 0.1f, 0f, 0f, 1.75f))
        assertNull(CombatGeometry.movingCapsuleHit(Float.NaN, 1f, 0f, 1f, 1f, 0f,
            0f, 0f, 0f, 1.75f, 0f, 0f, 0f, 1.75f))
    }

    @Test fun successiveHitsEachDamageAndPausedHitsGiveNoFalseFeedback() {
        val game = GameRules()
        repeat(4) { assertEquals(8, game.damagePlayer(GameRules.Kind.SHOOTER.damage)) }
        assertEquals(68, game.health)
        game.togglePause()
        assertEquals(0, game.damagePlayer(8))
        assertEquals(68, game.health)
        game.togglePause()
        assertEquals(6, game.damagePlayer(GameRules.Kind.DRONE.damage))
        assertEquals(62, game.health)
        assertEquals(62, game.damagePlayer(1000))
        assertEquals(0, game.damagePlayer(8))
    }

    @Test fun corpseExpiresQuicklyAndPauseFreezesItsShortDeathWindow() {
        val game = GameRules().apply { tick(2.5f) }
        game.hit(0, true, 10000)
        game.tick(0.4f)
        assertTrue(game.enemies[0].deathClock > 0f)
        game.togglePause(); game.tick(20f)
        assertEquals(0.35f, game.enemies[0].deathClock, 0.0001f)
        game.togglePause(); game.tick(0.36f)
        assertEquals(0f, game.enemies[0].deathClock, 0f)
        assertFalse(game.attacks.any { it.slot == 0 })
    }
}
