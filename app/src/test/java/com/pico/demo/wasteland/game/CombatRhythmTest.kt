package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test

class CombatRhythmTest {
    @Test fun synchronizedSquadHasBoundedWarningsSpacedAttacksAndNoStarvedSlots() {
        val game = GameRules().apply { tick(2.5f) }
        game.spawnClear = { _, _ -> false }
        val squad = game.enemies.filter { it.alive }
        squad.forEach {
            it.kind = GameRules.Kind.SHOOTER
            it.x = 0f; it.z = -3.3f; it.attackClock = 0f
        }
        val fired = mutableSetOf<Int>()
        var sinceAttack = 10f
        repeat(3600) {
            game.tick(0.01f, 0f, -2f) { true }
            sinceAttack += 0.01f
            assertTrue(game.enemies.count { it.alive && it.telegraph > 0f } <= 2)
            assertTrue(game.attacks.size <= 1)
            if (game.attacks.isNotEmpty()) {
                assertTrue("Attack gap=$sinceAttack", sinceAttack >= 0.219f)
                fired.add(game.attacks.single().slot)
                sinceAttack = 0f
            }
        }
        assertEquals(squad.map { it.slot }.toSet(), fired)
    }

    @Test fun waitingWindupCannotSkipGlobalGapDuringPauseAndHitCancelsIt() {
        val game = GameRules().apply { tick(2.5f) }
        game.enemies.drop(2).forEach { it.health = 0 }
        game.enemies.take(2).forEach {
            it.x = 0f; it.z = -3.3f; it.telegraph = 0.01f
        }
        game.tick(0.02f, 0f, -2f)
        assertEquals(1, game.attacks.size)
        val waiting = game.enemies.first { it.telegraph > 0f }
        game.togglePause(); game.tick(100f, 0f, -2f)
        game.togglePause(); game.tick(0.02f, 0f, -2f)
        assertTrue(game.attacks.isEmpty()); assertTrue(waiting.telegraph > 0f)
        game.hit(waiting.slot, false)
        game.tick(0.25f, 0f, -2f)
        assertTrue(game.attacks.none { it.slot == waiting.slot })
        game.restart(); game.tick(2.5f)
        val first = game.enemies[0]
        game.enemies.drop(1).forEach { it.health = 0 }
        first.x = 0f; first.z = -3.3f; first.attackClock = 0f
        game.tick(0.01f, 0f, -2f)
        assertEquals(0.5f, first.telegraph, 0f)
    }

    @Test fun movingPlayerDoesNotRetargetAnAttackAfterItsWarningBegins() {
        val game = GameRules().apply { tick(2.5f) }
        game.enemies.drop(1).forEach { it.health = 0 }
        val enemy = game.enemies[0]
        enemy.x = 0f; enemy.z = -3.3f; enemy.attackClock = 0f
        game.tick(0.01f, 0f, -2f)
        game.tick(0.51f, 1.2f, -3.3f) { true }
        val attack = game.attacks.single()
        assertEquals(0f, attack.aimX, 0f); assertEquals(-2f, attack.aimZ, 0f)
        assertTrue(CombatGeometry.meleeArc(enemy.x, enemy.z, attack.aimX, attack.aimZ, 0f, -2f))
        assertFalse(CombatGeometry.meleeArc(enemy.x, enemy.z, attack.aimX, attack.aimZ, 1.2f, -3.3f))
        assertFalse(CombatGeometry.meleeArc(enemy.x, enemy.z, attack.aimX, attack.aimZ, 0f, -4.5f))
        assertFalse(CombatGeometry.meleeArc(0f, 0f, 0f, -1f, 0f, -1.75f))
        assertFalse(CombatGeometry.meleeArc(0f, 0f, Float.NaN, -1f, 0f, -1f))
    }

    @Test fun directionalDamageRotatesWithTheHeadsetAndHandlesInvalidOffsets() {
        assertEquals(DamageDirection.Side.FRONT, DamageDirection.side(0f, -1f, 0f, -1f))
        assertEquals(DamageDirection.Side.REAR, DamageDirection.side(0f, 1f, 0f, -1f))
        assertEquals(DamageDirection.Side.LEFT, DamageDirection.side(-1f, 0f, 0f, -1f))
        assertEquals(DamageDirection.Side.RIGHT, DamageDirection.side(1f, 0f, 0f, -1f))
        assertEquals(DamageDirection.Side.FRONT, DamageDirection.side(1f, 0f, 1f, 0f))
        assertEquals(DamageDirection.Side.RIGHT, DamageDirection.side(0f, 1f, 1f, 0f))
        assertEquals(DamageDirection.Side.FRONT, DamageDirection.side(Float.NaN, 1f, 0f, -1f))
    }

    @Test fun mapInteractionsCannotEndOrSkipTheHundredEnemyBattle() {
        val game = GameRules().apply { tick(2.5f) }
        assertFalse(game.interact(V3Layout.GENERATOR_X, V3Layout.GENERATOR_Z))
        assertFalse(game.interact(V3Layout.CHEST_X, V3Layout.CHEST_Z))
        assertFalse(game.complete); assertTrue(game.gateOpen)
        assertEquals(0, game.kills); assertEquals(3, game.aliveCount)
    }

    @Test fun enemySpawnUsesBakedGeometryAndRetainsItAcrossEncounterResets() {
        val builder = NavigationGrid.Builder()
        for (x in -4..4) for (z in -38..-30) builder.sample(x * 0.5f, z * 0.5f, x != 0)
        val grid = builder.build()
        val worker = NavigationWorker()
        try {
            worker.setGrid(grid)
            repeat(3) {
                val point = worker.spawnPoint(0f, -17f, false)
                assertTrue(grid.walkable(point.first, point.second))
                assertTrue(V3Layout.walkable(point.first, point.second, false))
                assertEquals(0.5f, kotlin.math.abs(point.first), 0f)
                assertEquals(-17f, point.second, 0f)
                worker.reset()
            }
        } finally { worker.close() }
    }
}
