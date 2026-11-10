package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class CombatPolishTest {
    @Test fun hiddenMovingPlayerUpdatesEveryEnemyIncludingStunnedEnemiesWithoutWallAttacks() {
        val game = GameRules().apply { tick(2.5f) }
        game.enemies[0].stunClock = 10f
        repeat(40) { frame ->
            val x = -2f + frame * 0.1f
            val z = -8f - frame * 0.03f
            game.tick(0.02f, x, z) { false }
            game.enemies.filter { it.alive }.forEach {
                assertEquals(x, it.lastKnownX, 0f); assertEquals(z, it.lastKnownZ, 0f)
                assertTrue(it.alerted)
            }
            assertTrue(game.attacks.isEmpty())
        }
        val oldX = game.enemies[0].lastKnownX
        game.tick(1f, Float.NaN, 0f)
        assertEquals(oldX, game.enemies[0].lastKnownX, 0f)
    }
    @Test fun halfMeterNavigationFindsAnOffsetDoorAndEverySegmentHasCapsuleClearance() {
        val builder = NavigationGrid.Builder()
        for (x in -12..12) builder.sample(x * 0.5f, -10f, x == 1)
        val grid = builder.build()
        assertFalse(grid.clear(0f, -2f, 0f, -18f))
        val route = V3Layout.path(0f, -2f, 0f, -18f, false, grid = grid)
        assertTrue("No path through offset door", route.isNotEmpty())
        assertTrue(route.any { it.first == 0.5f && it.second == -10f })
        var before = 0f to -2f
        route.forEach {
            assertTrue(grid.clear(before.first, before.second, it.first, it.second))
            assertTrue(V3Layout.routeClear(before.first, before.second, it.first, it.second, false))
            before = it
        }
        assertEquals(0f to -18f, route.last())
    }
    @Test fun immutableBuildingGridSurvivesEncounterResetAndCrowdStallsCannotSealDoors() {
        val builder = NavigationGrid.Builder().apply {
            sample(0f, -10f, true); sample(1f, -10f, false)
        }
        val grid = builder.build()
        builder.sample(1f, -10f, true)
        val worker = NavigationWorker()
        try {
            worker.setGrid(grid)
            worker.block(0, 0f, -10f)
            assertTrue(worker.clear(0f, -9.5f, 0f, -10.5f))
            worker.reset()
            assertFalse(worker.clear(1f, -9.5f, 1f, -10.5f))
        } finally { worker.close() }
    }
    @Test fun defeatEventsCapturePositionOnceAndSurviveMissionTransition() {
        val game = GameRules().apply { tick(2.5f) }
        val victim = game.enemies[0]
        val position = victim.x to victim.z
        game.hit(0, true, 1000)
        victim.x = 20f
        repeat(8) { assertFalse(game.hit(0, true, 1000)) }
        game.enemies.filter { it.alive }.forEach { game.hit(it.slot, false, 10000) }
        assertFalse(game.complete)
        game.tick(0.8f) { false }
        val events = game.drainDefeats()
        assertEquals(3, events.size)
        assertEquals(position, events.first().x to events.first().z)
        assertTrue(events.first().headshot); assertEquals(150, events.first().points)
        assertEquals(3, events.last().combo)
        assertTrue(game.drainDefeats().isEmpty())
    }
    @Test fun barrelMultiKillUsesTheSameDefeatEventsAndCannotRepeat() {
        val game = GameRules().apply { tick(2.5f) }
        game.enemies.take(2).forEach { it.x = -3f; it.z = -15f }
        game.hitBarrel(0, 1000)
        val events = game.drainDefeats()
        assertEquals(2, events.size)
        assertEquals(setOf(0, 1), events.map { it.slot }.toSet())
        assertTrue(events.all { !it.headshot && it.points == 100 })
        game.hitBarrel(0, 1000)
        assertTrue(game.drainDefeats().isEmpty())
    }
    @Test fun comboExpiresButPauseFreezesItAndRestartDiscardsAllFeedback() {
        val game = GameRules().apply { tick(2.5f) }
        game.hit(0, true, 1000)
        game.togglePause(); game.tick(20f)
        assertEquals(3f, game.killFeedbackRemaining, 0f)
        game.togglePause(); game.tick(1f); game.hit(1, false, 1000)
        assertTrue(game.killFeedback.contains("2 连杀"))
        game.tick(3.01f)
        assertEquals("", game.killFeedback)
        game.hit(2, false, 1000)
        assertEquals(1, game.lastDefeat!!.combo)
        game.restart()
        assertEquals("", game.killFeedback); assertTrue(game.drainDefeats().isEmpty())
        assertNull(game.lastDefeat)
    }
    @Test fun playerDeathStillAllowsCorpseCleanupWhileCombatStaysBlocked() {
        val game = GameRules().apply { tick(2.5f) }
        game.hit(0, false, 1000); game.damagePlayer(1000)
        game.tick(2.5f)
        assertEquals(0f, game.enemies[0].deathClock, 0f)
        assertTrue(game.over); assertFalse(game.shoot()); assertTrue(game.attacks.isEmpty())
    }
    @Test fun crowdStartingAtOnePointSeparatesWhilePursuing() {
        val game = GameRules().apply { tick(2.5f) }
        game.enemies.drop(2).forEach { it.health = 0 }
        game.enemies.take(2).forEach { it.x = 0f; it.z = -4f }
        repeat(20) { game.tick(0.05f, 0f, -10f) { false } }
        assertTrue(hypot(game.enemies[0].x - game.enemies[1].x,
            game.enemies[0].z - game.enemies[1].z) > 0.2f)
    }
}
