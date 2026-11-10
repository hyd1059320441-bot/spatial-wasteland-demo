package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test

class ExpansionTest {
    private fun squad() = GameRules().apply { tick(2.5f); tick(6f) { false }; tick(6f) { false } }
    @Test fun standingAndCrouchingRemainContinuousAcrossRampsTeleportsAndRestarts() {
        assertEquals(2.85f, EyeHeight.onNewFloor(0.85f, 0f, 2f), 0.0001f)
        assertEquals(1.65f, EyeHeight.onNewFloor(3.65f, 2f, 0f), 0.0001f)
        assertEquals(0.85f, EyeHeight.afterRestart(0.1f, 0.9f, EyeHeight.DEFAULT), 0.0001f)
        assertEquals(1.65f, EyeHeight.afterRestart(-0.7f, -0.7f, EyeHeight.DEFAULT), 0.0001f)
        assertEquals(EyeHeight.MINIMUM, EyeHeight.onNewFloor(-1f, 0f, 0f), 0f)
        assertEquals(EyeHeight.CAPSULE, 1.75f, 0f)
    }

    @Test fun shieldAbsorbsOnlyItsOwnSurfaceAndBreakCannotScoreADefeat() {
        val game = squad()
        val guard = game.enemies.first { it.kind == GameRules.Kind.GUARD }
        repeat(3) { assertTrue(game.hitShield(guard.slot, 24)) }
        assertEquals(220, guard.health); assertEquals(24, guard.shield)
        assertTrue(game.hitShield(guard.slot, 24))
        assertEquals(0, guard.shield); assertEquals(220, guard.health)
        assertTrue(guard.stunClock >= 0.6f); assertEquals(0, game.kills)
        assertFalse(game.hitShield(guard.slot, 100))
        assertFalse(game.hit(guard.slot, false, 24))
        assertEquals(196, guard.health)
        assertFalse(game.hit(guard.slot, true, 24))
        assertEquals(124, guard.health)
        assertTrue(game.hit(guard.slot, true, 100))
        assertEquals(1, game.kills); assertFalse(game.hitShield(guard.slot))
    }

    @Test fun shieldsAndSuppliesRespectPauseAndRestartButCheckpointCannotFarmSupplies() {
        val game = squad()
        val guard = game.enemies.first { it.kind == GameRules.Kind.GUARD }
        game.togglePause()
        assertFalse(game.hitShield(guard.slot)); assertFalse(game.collectSupply("salvage"))
        assertEquals(96, guard.shield)
        game.togglePause()
        assertFalse(game.collectSupply("salvage"))
        assertTrue(game.supplyAvailable("salvage"))
        game.damagePlayer(30); assertTrue(game.shoot()); assertTrue(game.collectSupply("salvage"))
        assertEquals(100, game.health); assertEquals(24, game.ammo)
        assertFalse(game.collectSupply("salvage")); assertFalse(game.collectSupply("unknown"))
        game.retryCheckpoint(); assertFalse(game.supplyAvailable("salvage"))
        game.restart(); assertTrue(game.supplyAvailable("salvage"))
    }

    @Test fun reinforcementsKeepTacticsWithinTheActiveCap() {
        val game = squad()
        assertEquals(18, game.enemies.size); assertEquals(8, game.aliveCount)
        assertEquals(GameRules.Kind.entries.toSet() - GameRules.Kind.ELITE,
            game.enemies.filter { it.alive }.map { it.kind }.toSet())
        fun clear() { game.enemies.filter { it.alive }.forEach { game.hit(it.slot, true, 10000) } }
        repeat(4) {
            clear(); game.tick(0.8f) { false }; assertEquals(0, game.aliveCount)
            repeat(100) { if (game.aliveCount == 0) game.tick(0.1f) { false } }
            assertTrue(game.aliveCount in 2..3)
        }
        assertTrue(game.spawned > game.kills)
    }

    @Test fun sniperWarningIsLongAndKeepsItsAimPointWhileOtherKindsShareEmissionBudget() {
        val game = squad()
        game.enemies.filter { it.kind != GameRules.Kind.SNIPER }.forEach { it.health = 0 }
        val sniper = game.enemies.first { it.alive }
        game.enemies.filter { it.slot != sniper.slot }.forEach { it.health = 0 }
        sniper.x = 0f; sniper.z = -30f; sniper.attackClock = 0f
        game.tick(0.01f, 0f, -2f) { true }
        assertEquals(1.6f, sniper.telegraph, 0f)
        game.tick(1f, 2f, -2f) { true }
        assertTrue(game.attacks.isEmpty())
        game.tick(0.61f, 2f, -2f) { true }
        assertEquals(0f, game.attacks.single().aimX, 0f)
        assertEquals(18f, sniper.kind.projectileSpeed, 0f)
    }

    @Test fun nearbyGeometryAndPeripheralEdgesStayVisibleWhileDistantRearPropsCull() {
        assertTrue(VisibilityBudget.visible(0f, 0f, 10f, 1f, 55f, 0f, 0f, -1f))
        assertTrue(VisibilityBudget.visible(35f, 0f, 0f, 2f, 55f, 0f, 0f, -1f))
        assertFalse(VisibilityBudget.visible(0f, 0f, 40f, 2f, 55f, 0f, 0f, -1f))
        assertFalse(VisibilityBudget.visible(0f, 0f, -100f, 2f, 55f, 0f, 0f, -1f))
        assertTrue(VisibilityBudget.visible(0f, 20f, -100f, 12f, 130f, 0f, 0f, -1f))
    }

    @Test fun shadowBudgetRetainsNearbyBoundsAndDoesNotFlickerAroundTheCutoff() {
        assertTrue(VisibilityBudget.castsShadow(0f, 25f, 3f, 22f, false))
        assertFalse(VisibilityBudget.castsShadow(0f, 25.1f, 3f, 22f, false))
        assertTrue(VisibilityBudget.castsShadow(0f, 28.9f, 3f, 22f, true))
        assertFalse(VisibilityBudget.castsShadow(0f, 29.1f, 3f, 22f, true))
        assertTrue(VisibilityBudget.castsShadow(0f, 46f, 15f, 32f, false))
        assertTrue(VisibilityBudget.castsShadow(0f, -46f, 15f, 32f, false))
    }

    @Test fun eachSupplyIsOutsideTheLandmarkAndHasAStandableApproach() {
        V5Layout.supplies.forEach {
            assertTrue(V3Layout.walkable(it.x, it.z, true))
            assertTrue(V3Layout.heightAt(it.x, it.z) == 0f)
            assertFalse(V5Layout.obstacles.any { obstacle -> obstacle.contains(it.x, it.z) })
        }
        assertTrue(V3Layout.walkable(0f, -2f, false))
        assertTrue(V3Layout.walkable(0f, -66f, false))
    }
}
