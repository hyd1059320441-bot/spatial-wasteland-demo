package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class GameRulesTest {
    private fun started() = GameRules().apply { tick(2.5f, 0f, -2f) }

    @Test fun pacedReinforcementsPreserveSurvivorsAndFinishExactlyAtOneHundred() {
        val game = started()
        val points = mutableSetOf<Pair<Float, Float>>()
        repeat(100) { index ->
            repeat(100) { if (game.aliveCount == 0) game.tick(0.1f, 0f, -2f) { false } }
            assertTrue(game.aliveCount in 1..8)
            assertFalse(game.complete)
            val alive = game.enemies.filter { it.alive }
            alive.forEach { points.add(it.x to it.z) }
            val victim = alive.first()
            val survivors = alive.drop(1).associate { it.slot to (it.health to it.generation) }
            assertTrue(game.hit(victim.slot, true, 10000))
            assertFalse(game.hit(victim.slot, true, 10000))
            assertEquals(index + 1, game.kills)
            game.tick(0.8f, 0f, -2f) { false }
            for ((slot, state) in survivors) {
                assertEquals(state.first, game.enemies[slot].health)
                assertEquals(state.second, game.enemies[slot].generation)
            }
        }
        assertTrue(game.complete)
        assertEquals(100, game.spawned)
        assertEquals(0, game.aliveCount)
        assertTrue(points.size >= 12)
        val score = game.score
        game.tick(100f); assertFalse(game.shoot()); assertFalse(game.reload())
        assertFalse(game.hit(0, true, 10000)); assertEquals(score, game.score)
    }

    @Test fun freshReinforcementsUseDifferentSafePointsAndResetPoolState() {
        val game = started()
        game.enemies.filter { it.alive }.forEach { e ->
            assertTrue(hypot(e.x, e.z + 2f) >= 6f)
            assertTrue(V3Layout.walkable(e.x, e.z, true))
        }
        repeat(5) {
            val snapshot = game.enemies.filter { it.alive }.associate { it.slot to it.generation }
            game.enemies.filter { it.alive }.forEach { game.hit(it.slot, true, 10000) }
            game.tick(0.8f, 0f, -2f) { false }
            assertEquals(0, game.aliveCount)
            repeat(100) { if (game.aliveCount == 0) game.tick(0.1f) { false } }
            assertTrue(game.aliveCount in 2..3)
            game.enemies.filter { it.alive }.forEach {
                assertTrue(it.generation > (snapshot[it.slot] ?: 0))
                assertEquals(0f, it.deathClock, 0f)
                assertEquals(it.kind.hp, it.health)
            }
        }
    }
    @Test fun nativeSpawnVetoRejectsBadPointsAndRetriesWithoutClearingLiveActors() {
        val game = GameRules()
        game.spawnClear = { x, _ -> x > 0f }
        game.tick(2.5f, 0f, -2f)
        assertEquals(3, game.aliveCount)
        assertTrue(game.enemies.filter { it.alive }.all { it.x > 0f })
        val alive = game.enemies.filter { it.alive }
        game.spawnClear = { _, _ -> false }
        game.hit(alive.first().slot, true, 10000); game.tick(0.8f) { false }
        assertEquals(2, game.aliveCount); assertEquals(3, game.spawned)
        assertTrue(alive.drop(1).all { it.alive })
        game.tick(5.2f) { false }; assertEquals(3, game.spawned)
        game.spawnClear = { _, _ -> true }; game.tick(6f) { false }
        assertEquals(5, game.aliveCount); assertEquals(6, game.spawned)
    }

    @Test fun rifleCadenceSeparateMagazinesAndReloadRemainUsable() {
        val game = started()
        repeat(24) { assertTrue(game.shoot()); assertFalse(game.shoot()); game.tick(0.121f) { false } }
        assertEquals(0, game.ammo); assertFalse(game.shoot())
        assertEquals(1.65f, game.reloadRemaining, 0f)
        assertFalse(game.switchWeapon())
        game.tick(1.66f) { false }; assertEquals(24, game.ammo)
        assertTrue(game.switchWeapon()); game.tick(0.26f) { false }
        assertTrue(game.shoot()); assertEquals(5, game.ammo)
        assertTrue(game.switchWeapon()); assertEquals(24, game.ammo)
    }

    @Test fun pauseDeathAndRestartCannotEmitOrDuplicateEnemies() {
        val game = started()
        game.hit(game.enemies.first { it.alive }.slot, true, 10000)
        val emitted = game.spawned
        game.togglePause(); game.tick(10f)
        assertEquals(emitted, game.spawned); assertEquals(2, game.aliveCount)
        assertFalse(game.shoot())
        game.togglePause(); game.tick(0.8f) { false }; assertEquals(2, game.aliveCount)
        game.damagePlayer(1000); game.tick(10f)
        assertTrue(game.over); assertEquals(3, game.spawned)
        game.retryCheckpoint()
        assertEquals(1, game.kills); assertEquals(3, game.spawned)
        game.restart()
        assertEquals(0, game.kills); assertEquals(0, game.spawned)
        assertEquals(0, game.aliveCount); assertEquals(100, game.health)
        assertEquals(24, game.ammo); assertTrue(game.gateOpen)
    }

    @Test fun attacksKeepWarningAndCannotBypassOcclusion() {
        val game = started()
        game.enemies.forEach { it.telegraph = 0f; it.attackClock = 100f }
        val e = game.enemies[0]
        e.x = 0f; e.z = -3.3f; e.attackClock = 0f
        game.tick(0.01f, 0f, -2f) { true }; assertEquals(0.5f, e.telegraph, 0f)
        game.tick(0.49f, 0f, -2f) { true }; assertTrue(game.attacks.isEmpty())
        game.tick(0.02f, 0f, -2f) { true }; assertTrue(game.attacks.single().melee)
        e.telegraph = 0.01f
        game.tick(0.25f, 0f, -2f) { false }; assertTrue(game.attacks.isEmpty())
    }

    @Test fun invalidTicksAndOffMapSpawnsCannotMutateTheBattle() {
        val game = GameRules()
        game.tick(0f); assertEquals(2.5f, game.startRemaining, 0f)
        for (bad in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try { game.tick(bad); fail("Accepted $bad") } catch (_: IllegalArgumentException) { }
        }
        assertFalse(game.hit(-1, true)); assertFalse(game.hit(200, true))
    }
}
