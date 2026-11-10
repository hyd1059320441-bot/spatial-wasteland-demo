package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test

class ArsenalTest {
    @Test fun reinforcementsAreSmallSpacedAndHaveAClearRest() {
        val game = GameRules()
        game.tick(2.5f)
        assertEquals(3, game.aliveCount); assertEquals(3, game.spawned)
        game.tick(5.9f) { false }; assertEquals(3, game.spawned)
        game.tick(0.11f) { false }; assertEquals(5, game.spawned)
        val survivors = game.enemies.filter { it.alive }.map { Triple(it.slot, it.health, it.generation) }
        game.tick(5.9f) { false }; assertEquals(5, game.spawned)
        game.tick(0.11f) { false }; assertEquals(8, game.spawned)
        survivors.forEach { (slot, hp, generation) ->
            assertEquals(hp, game.enemies[slot].health)
            assertEquals(generation, game.enemies[slot].generation)
        }
        repeat(300) { game.tick(0.1f) { false }; assertTrue(game.aliveCount <= 8) }
        game.enemies.filter { it.alive }.forEach { game.hit(it.slot, true, 10000) }
        val emitted = game.spawned
        game.tick(3.99f) { false }; assertEquals(emitted, game.spawned)
        assertEquals(0, game.aliveCount)
        repeat(100) { if (game.aliveCount == 0) game.tick(0.1f) { false } }
        assertTrue(game.aliveCount in 2..3)
    }

    @Test fun everyEliteIsGuaranteedAndDropsExactlyOnceInArsenalOrder() {
        val game = GameRules().apply { tick(2.5f) }
        val emissions = mutableListOf<Int>()
        repeat(100) {
            repeat(120) { if (game.aliveCount == 0) game.tick(0.1f) { false } }
            val e = game.enemies.first { it.alive }
            if (e.kind == GameRules.Kind.ELITE) emissions.add(e.emission)
            assertTrue(game.hit(e.slot, true, 10000))
            val dropCount = game.drops.size
            assertFalse(game.hit(e.slot, true, 10000)); assertEquals(dropCount, game.drops.size)
            game.tick(0.8f) { false }
        }
        assertEquals(listOf(10, 30, 50, 70, 90), emissions)
        assertEquals(listOf(GameRules.Weapon.ARC, GameRules.Weapon.RAIL, GameRules.Weapon.PLASMA,
            GameRules.Weapon.ARC, GameRules.Weapon.RAIL), game.drops.map { it.weapon })
        assertEquals(100, game.spawned); assertTrue(game.complete)
    }

    private fun drop(weapon: GameRules.Weapon): GameRules = GameRules().apply {
        drops.add(GameRules.Drop(0, weapon, 0f, -2f, 0f))
    }

    @Test fun pickupRequiresNearSameFloorClearLineAndUsableGameState() {
        val game = drop(GameRules.Weapon.ARC)
        assertNull(game.nearestDrop(2.01f, -2f, 0f))
        assertFalse(game.collectDrop(0, 2.01f, -2f, 0f))
        assertFalse(game.collectDrop(0, 0f, -2f, 0.51f))
        assertFalse(game.collectDrop(0, 0f, -2f, 0f) { false })
        assertFalse(game.collectDrop(0, Float.NaN, -2f, 0f))
        game.togglePause(); assertFalse(game.collectDrop(0, 0f, -2f, 0f)); game.togglePause()
        assertTrue(game.collectDrop(0, 2f, -2f, 0f) { true })
        assertFalse(game.collectDrop(0, 0f, -2f, 0f))
        assertTrue(game.owns(GameRules.Weapon.ARC))
        assertNull(game.nearestDrop(0f, -2f, 0f))
        game.drops.add(GameRules.Drop(1, GameRules.Weapon.PLASMA, Float.NaN, -2f, 0f))
        assertFalse(game.collectDrop(1, 0f, -2f, 0f))
    }

    @Test fun switchingCyclesOnlyOwnedGunsAndEveryMagazineIsIndependent() {
        val game = GameRules()
        assertEquals(listOf(GameRules.Weapon.RIFLE, GameRules.Weapon.SHOTGUN), game.ownedWeapons)
        game.switchWeapon(); assertEquals(GameRules.Weapon.SHOTGUN, game.weapon)
        game.switchWeapon(); assertEquals(GameRules.Weapon.RIFLE, game.weapon)
        listOf(GameRules.Weapon.ARC, GameRules.Weapon.RAIL, GameRules.Weapon.PLASMA).forEachIndexed { i, w ->
            game.drops.add(GameRules.Drop(i, w, 0f, -2f, 0f))
            assertTrue(game.collectDrop(i, 0f, -2f, 0f))
        }
        repeat(5) {
            val weapon = game.weapon
            game.tick(3f) { false }; assertTrue(game.shoot())
            assertEquals(weapon.magazine - 1, game.ammo)
            assertTrue(game.switchWeapon())
        }
        assertEquals(GameRules.Weapon.RIFLE, game.weapon); assertEquals(23, game.ammo)
        assertTrue(game.reload()); assertFalse(game.switchWeapon())
        game.tick(game.reloadSeconds + 0.01f) { false }; assertEquals(24, game.ammo)
        game.retryCheckpoint()
        assertEquals(5, game.ownedWeapons.size); assertTrue(game.drops.all { it.collected })
        game.restart()
        assertEquals(2, game.ownedWeapons.size); assertTrue(game.drops.isEmpty())
    }

    @Test fun duplicateDropsRefillWithoutDuplicatingInventoryOrCancellingAnotherReload() {
        val game = drop(GameRules.Weapon.ARC)
        assertTrue(game.shoot()); assertTrue(game.reload())
        assertTrue(game.collectDrop(0, 0f, -2f, 0f))
        assertTrue(game.reloadRemaining > 0f)
        game.drops.add(GameRules.Drop(1, GameRules.Weapon.ARC, 0f, -2f, 0f))
        assertTrue(game.collectDrop(1, 0f, -2f, 0f))
        assertEquals(3, game.ownedWeapons.size)
    }

    @Test fun arsenalHasDistinctCadenceDamageRangeAndRealTrajectoryParameters() {
        val guns = GameRules.Weapon.entries
        assertEquals(5, guns.map { it.damage }.toSet().size)
        assertEquals(5, guns.map { it.interval }.toSet().size)
        assertTrue(GameRules.Weapon.ARC.automatic)
        assertEquals(34f, GameRules.Weapon.ARC.projectileSpeed, 0f)
        assertEquals(0f, GameRules.Weapon.ARC.gravity, 0f)
        assertEquals(0f, GameRules.Weapon.RAIL.projectileSpeed, 0f)
        assertEquals(0f, GameRules.Weapon.RAIL.spread, 0f)
        assertTrue(GameRules.Weapon.PLASMA.gravity > 0f)
        assertTrue(GameRules.Weapon.PLASMA.projectileSpeed < GameRules.Weapon.ARC.projectileSpeed)
    }
}
