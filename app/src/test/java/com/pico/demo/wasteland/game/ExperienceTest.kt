package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test

class ExperienceTest {
    @Test fun weaponsAndSuppliesAreReadyImmediatelyAndPauseFreezesTheEntryCountdown() {
        val rules = GameRules()
        assertEquals(0, rules.aliveCount)
        assertEquals(0, rules.spawned)
        assertTrue(rules.shoot())
        assertEquals(23, rules.ammo)
        assertTrue(rules.switchWeapon())
        assertTrue(rules.supplyAvailable("salvage"))
        rules.togglePause()
        assertFalse(rules.shoot())
        assertFalse(rules.switchWeapon())
        rules.tick(60f)
        assertEquals(0, rules.aliveCount)
        assertEquals(2.5f, rules.startRemaining, 0f)
        rules.togglePause()
        rules.tick(2.5f)
        assertEquals(3, rules.aliveCount)
        assertTrue(rules.enemies.filter { it.alive && it.kind == GameRules.Kind.GUARD }.all { it.shield == 96 })
        rules.damagePlayer(1000)
        rules.retryCheckpoint()
        assertEquals(3, rules.aliveCount)
        assertTrue(rules.shoot())
        rules.restart()
        assertEquals(0, rules.aliveCount)
        assertEquals(GameRules.Weapon.RIFLE, rules.weapon)
        assertTrue(rules.shoot())
        assertEquals(0, rules.spawned)
    }

    @Test fun navigationCacheRejectsWrongFingerprintTruncationMutationAndExtraBytes() {
        val builder = NavigationGrid.Builder()
        builder.sample(0f, -6f, true); builder.sample(1f, -6f, false)
        val bytes = builder.build().encode("verified-content")
        val restored = NavigationGrid.decode(bytes, "verified-content")!!
        assertTrue(restored.covers(0f, -6f))
        assertTrue(restored.walkable(0f, -6f))
        assertFalse(restored.walkable(1f, -6f))
        assertFalse(restored.covers(0f, -20f))
        assertTrue(restored.walkable(0f, -20f)) // Unsampled cells retain layout-only routing.
        assertNull(NavigationGrid.decode(bytes, "changed-content"))
        assertNull(NavigationGrid.decode(bytes.copyOf(bytes.size - 1), "verified-content"))
        assertNull(NavigationGrid.decode(bytes + byteArrayOf(0), "verified-content"))
        assertNull(NavigationGrid.decode(bytes.clone().also { it[it.size - 100] = 8 }, "verified-content"))
    }

    @Test fun cacheProbesRevisitTheStoredSampleAcrossAnOffGridCollisionBoundary() {
        // A thin wall crosses this cell between its lattice point and the requested probe.
        fun physicalClear(x: Float) = x < 0.22f
        val grid = NavigationGrid.decode(NavigationGrid.Builder().apply {
            sample(0f, -6f, physicalClear(0f))
            sample(0.5f, -6f, physicalClear(0.5f))
        }.build().encode("wall-boundary"), "wall-boundary")!!
        assertTrue(grid.walkable(0.24f, -6.1f))
        assertFalse(physicalClear(0.24f))
        for ((x, z) in listOf(0.24f to -6.1f, 0.25f to -5.9f, 0.49f to -6f)) {
            val sample = grid.sampledPosition(x, z)!!
            assertEquals(grid.walkable(x, z), physicalClear(sample.first))
            assertEquals(-6f, sample.second, 0f)
        }
        assertEquals(0f to -6f, grid.sampledPosition(0.24f, -6.1f))
        assertEquals(0.5f to -6f, grid.sampledPosition(0.25f, -5.9f))
        assertNull(grid.sampledPosition(0f, -20f))
        assertNull(grid.sampledPosition(Float.NaN, -6f))
        assertNull(grid.sampledPosition(V3Layout.MAX_X + 1f, -6f))
    }

    @Test fun eliteCoreRequiresWindupAndBreakingItCancelsFanAttackAndArmor() {
        val rules = GameRules()
        val elite = rules.enemies[6]
        elite.kind = GameRules.Kind.ELITE
        elite.health = elite.kind.hp; elite.x = 0f; elite.z = -80f
        assertFalse(rules.hitEliteCore(6, 60))
        elite.telegraph = 1f
        assertTrue(rules.hitEliteCore(6, 60))
        assertEquals(0f, elite.telegraph, 0f)
        assertTrue(elite.stunClock > 0f)
        assertFalse(rules.hitEliteCore(6, 60))
        rules.togglePause(); elite.telegraph = 1f
        assertFalse(rules.hitEliteCore(6, 120))
        rules.togglePause()
        assertTrue(rules.hitEliteCore(6, 120))
        assertEquals(0, elite.coreHealth)
        val health = elite.health
        rules.hit(6, false, 24)
        assertEquals(health - 24, elite.health)
        elite.health = 300
        assertEquals(3, elite.phase)
        elite.stunClock = 0f; elite.telegraph = 0.001f
        rules.tick(2.5f) // Finish initial countdown.
        // Countdown creates ordinary enemies; preserve the elite and isolate its attack.
        rules.enemies.filter { it.slot != 6 }.forEach { it.health = 0 }
        elite.health = 300; elite.coreHealth = 0; elite.telegraph = 0.001f; elite.stunClock = 0f
        rules.tick(0.02f, 0f, -70f) { true }
        assertEquals(1, rules.attacks.single { it.slot == 6 }.projectiles)
        elite.coreHealth = 180; elite.telegraph = 0.001f
        rules.tick(0.25f, 0f, -70f) { true }
        assertEquals(3, rules.attacks.single { it.slot == 6 }.projectiles)
    }
}
