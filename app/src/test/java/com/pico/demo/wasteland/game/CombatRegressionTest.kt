package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test

class CombatRegressionTest {
    @Test fun triggerNeedsReleaseAndNeverUsesPressedBooleanFallback() {
        val gate = TriggerGate()
        assertFalse(gate.sample(1f, true, true))
        assertFalse(gate.sample(0f, true, true))
        assertTrue(gate.sample(0.9f, true, true))
        assertTrue(gate.sample(0.5f, true, true))
        assertFalse(gate.sample(0.2f, true, true))
        assertFalse(gate.sample(0.5f, true, true))
    }
    @Test fun staleInputPauseAndTrackingLossRequireANewRelease() {
        val gate = TriggerGate()
        gate.sample(0f, true, true)
        assertTrue(gate.sample(1f, true, true))
        assertFalse(gate.sample(1f, false, true))
        assertFalse(gate.sample(1f, true, true))
        gate.sample(0f, true, true)
        assertTrue(gate.sample(1f, true, true))
        assertFalse(gate.sample(1f, true, false))
        assertFalse(gate.sample(1f, true, true))
        assertFalse(gate.sample(Float.NaN, true, true))
    }
    @Test fun fastProjectileHitsBodyButDoesNotHitAboveHeadOrUnderDeck() {
        assertNotNull(CombatGeometry.capsuleHit(-2f, 1f, 0f, 2f, 1f, 0f, 0f, 0f, 0f, 1.65f))
        assertNull(CombatGeometry.capsuleHit(-2f, 2f, 0f, 2f, 2f, 0f, 0f, 0f, 0f, 1.65f))
        assertNull(CombatGeometry.capsuleHit(-2f, 1f, 0f, 2f, 1f, 0f, 0f, 0f, 2f, 3.65f))
        assertNotNull(CombatGeometry.capsuleHit(0f, 3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1.65f))
    }
    @Test fun explosionsCannotDamageAnEnemyAcrossCover() {
        val game = GameRules().apply { tick(2.5f) }
        val enemy = game.enemies[0]
        enemy.x = 18.5f; enemy.z = -48f
        val health = enemy.health
        game.hitBarrel(4, 1000)
        assertEquals(health, enemy.health)
    }
    @Test fun emptyRoutesCannotTriggerPathfindingEveryFrame() {
        val game = GameRules().apply { tick(2.5f) }
        repeat(144) { game.tick(1f / 72f, -7f, -8f) }
        assertTrue("calls=${game.pathRequests}", game.pathRequests < 48)
    }
    @Test fun runtimeNavigationHasOnePendingJobPerEnemyAndCanBeClosed() {
        val worker = NavigationWorker()
        try {
            val game = GameRules(worker).apply { tick(2.5f) }
            repeat(144) { game.tick(1f / 72f, 0f, -60f) }
            assertTrue("jobs=${worker.submitted}", worker.submitted <= 40)
            worker.reset()
        } finally { worker.close() }
    }
    @Test fun buildingWallsPerimeterAndGateBlockMovementAndDamage() {
        assertFalse(V3Layout.routeClear(0f, V3Layout.REPAIR_Z, -4f, V3Layout.REPAIR_Z, true))
        assertFalse(V3Layout.lineClear(0f, V3Layout.REPAIR_Z, -4f, V3Layout.REPAIR_Z))
        assertFalse(V3Layout.walkable(35f, -3f, true))
        assertFalse(V3Layout.routeClear(7f, -65f, 7f, -71f, true))
        assertTrue(V3Layout.routeClear(0f, -65f, 0f, -71f, true))
        assertTrue(V3Layout.routeClear(0f, -2f, 0f, -18f, true))
    }
    @Test fun thinWallOcclusionIsContinuousAtShortSegmentsEndpointsAndNearEdges() {
        val wall = V3Layout.structures.first { it.name == "RepairWallWest" }
        val inside = wall.x + wall.width / 2f + 0.01f
        val outside = wall.x - wall.width / 2f - 0.01f
        assertFalse(V3Layout.lineClear(inside, -6f, outside, -6f))
        assertFalse(V3Layout.lineClear(outside, -6f, inside, -6f))
        assertFalse(V3Layout.lineClear(0f, -6f, wall.x, wall.z))
        assertFalse(V3Layout.lineClear(wall.x, wall.z, wall.x, wall.z))
        val edge = wall.z + wall.depth / 2f
        assertFalse(V3Layout.lineClear(inside, edge - 0.001f, outside, edge - 0.001f))
        assertTrue(V3Layout.lineClear(inside, edge + 0.001f, outside, edge + 0.001f))
        assertTrue(V3Layout.lineClear(0f, -2f, 0f, -2f))
        assertFalse(V3Layout.lineClear(Float.NaN, -2f, 0f, -3f))
        assertFalse(V3Layout.lineClear(0f, -2f, 0f, Float.POSITIVE_INFINITY))
    }
    @Test fun rangedEnemyStillFiresAGunAtCloseRangeAndCannotAttackThroughSceneWalls() {
        val game = GameRules().apply { tick(2.5f) }
        game.enemies.forEach { it.health = 0 }
        val e = game.enemies[2]
        e.health = e.kind.hp; e.x = 0f; e.z = -3.3f; e.attackClock = 0f
        game.tick(0.01f, 0f, -2f)
        assertEquals(0.8f, e.telegraph, 0f)
        game.tick(0.81f, 0f, -2f)
        assertEquals(1, game.attacks.size)
        assertFalse(game.attacks.single().melee)
        e.telegraph = 0.01f
        game.tick(0.02f, 0f, -2f) { false }
        assertTrue(game.attacks.isEmpty())
    }
    @Test fun bodyHitCancelsWindupAndDeathRemovesAttackAndThenCorpse() {
        val game = GameRules().apply { tick(2.5f) }
        val e = game.enemies[0]
        e.x = 0f; e.z = -3.3f; e.telegraph = 0.2f
        game.hit(0, false)
        assertEquals(0f, e.telegraph, 0f)
        game.tick(0.1f, 0f, -2f)
        assertEquals(GameRules.Motion.HIT, e.motion)
        assertTrue(game.attacks.isEmpty())
        game.hit(0, true, 1000)
        assertFalse(e.alive); assertEquals(GameRules.Motion.DEATH, e.motion)
        game.tick(2.5f, 0f, -2f)
        assertEquals(0f, e.deathClock, 0f)
        assertFalse(game.attacks.any { it.slot == 0 })
    }
    @Test fun squadPursuesPlayerAroundCoverAndRemembersHeardShots() {
        val game = GameRules().apply { tick(2.5f) }
        val e = game.enemies[0]
        e.x = -5f; e.z = -10f
        val initial = kotlin.math.hypot(e.x + 9f, e.z + 10f)
        repeat(700) { game.tick(0.05f, -9f, -10f) }
        assertTrue("enemy=${e.x},${e.z}", kotlin.math.hypot(e.x + 9f, e.z + 10f) < 2f)
        assertTrue(initial > 2f)
        game.hearShot(10f, -30f)
        assertEquals(10f, e.lastKnownX, 0f); assertEquals(-30f, e.lastKnownZ, 0f)
    }
    @Test fun learnedPhysicalObstructionMakesAStarChooseAnotherRoute() {
        val obstacles = listOf(V3Layout.NavBlock(0f, -10f, 0.8f))
        val path = V3Layout.path(0f, -2f, 0f, -18f, false, obstacles)
        assertTrue(path.isNotEmpty())
        assertTrue(path.any { kotlin.math.abs(it.first) >= 1f })
        var previous = 0f to -2f
        path.forEach {
            assertTrue(V3Layout.learnedClear(previous.first, previous.second, it.first, it.second, obstacles))
            previous = it
        }
    }
    @Test fun physicalStallInvalidatesNavigationWithoutUnboundedRequests() {
        val worker = NavigationWorker()
        try {
            val game = GameRules(worker).apply { tick(2.5f) }
            val e = game.enemies[0]
            e.x = 0f; e.z = -10f
            repeat(24) { game.reportMotion(e, 0f, -10f, 0f, -10.05f, 0.05f) }
            assertTrue(e.recoveryClock > 0f)
            assertFalse(worker.clear(0f, -10f, 0f, -12f))
            assertEquals(0, worker.submitted)
            worker.reset()
            assertTrue(worker.clear(0f, -10f, 0f, -12f))
        } finally { worker.close() }
    }
}
