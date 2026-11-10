package com.pico.demo.wasteland.game

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class RunTalentsTest {
    private fun killTo(game: GameRules, count: Int) {
        while (game.kills < count) {
            repeat(100) { if (game.aliveCount == 0) game.tick(0.1f) { false } }
            game.hit(game.enemies.first { it.alive }.slot, true, 10000)
            game.tick(0.8f) { false }
        }
        repeat(100) { if (game.aliveCount == 0 && !game.complete) game.tick(0.1f) { false } }
    }
    private fun reward(talent: RunTalents.Talent): GameRules {
        val seed = (0..100).first { RunTalents(Random(it)).apply { draft() }.offers.contains(talent) }
        return GameRules(talents = RunTalents(Random(seed))).apply {
            tick(2.5f); killTo(this, 20); assertTrue(openRewards())
            selectReward(talents.offers.indexOf(talent)); assertTrue(confirmTalent())
        }
    }
    @Test fun milestoneRewardsDoNotStopRefillsUntilTheUserOpensTheMenu() {
        val game = GameRules().apply { tick(2.5f) }
        killTo(game, 20)
        assertTrue(game.talents.pending); assertFalse(game.blocked); assertTrue(game.aliveCount in 1..8)
        assertTrue(game.shoot()); assertTrue(game.reload())
        assertTrue(game.openRewards()); assertTrue(game.blocked)
        val emitted = game.spawned
        game.tick(10f)
        assertEquals(emitted, game.spawned); assertFalse(game.shoot())
        assertTrue(game.selectReward(2)); assertTrue(game.confirmTalent())
        assertFalse(game.rewardMenuOpen); assertFalse(game.talents.pending)
        game.tick(game.reloadSeconds) { false }; assertTrue(game.shoot())
    }
    @Test fun unclaimedMilestonesQueueWithoutDuplicatingOrRepeatingOwnedTalents() {
        val game = GameRules(talents = RunTalents(Random(7))).apply { tick(2.5f) }
        killTo(game, 80)
        repeat(4) {
            assertTrue(game.openRewards())
            assertTrue(game.talents.offers.none { it in game.talents.acquired })
            assertTrue(game.confirmTalent())
        }
        assertEquals(4, game.talents.acquired.size); assertFalse(game.talents.pending)
        assertFalse(game.openRewards())
        game.retryCheckpoint()
        assertEquals(80, game.kills); assertFalse(game.talents.pending)
        game.restart(); assertTrue(game.talents.acquired.isEmpty())
    }
    @Test fun upgradesModifyGunsReloadAndArmorWithoutAffectingExplicitDamage() {
        val hot = reward(RunTalents.Talent.HOT_LOAD)
        assertEquals(28, hot.bulletDamage)
        assertTrue(hot.switchWeapon()); assertEquals(18, hot.bulletDamage)
        val fast = reward(RunTalents.Talent.QUICK_RELOAD)
        assertTrue(fast.shoot()); assertTrue(fast.reload())
        assertEquals(1.2375f, fast.reloadRemaining, 0.0001f)
        val armor = reward(RunTalents.Talent.ARMOR)
        assertEquals(11, armor.damagePlayer(14)); assertEquals(89, armor.health)
        assertEquals(1, armor.damagePlayer(1))
        val repair = reward(RunTalents.Talent.REPAIR)
        repair.damagePlayer(10); repair.hit(repair.enemies.first { it.alive }.slot, true, 10000)
        assertEquals(92, repair.health)
        val reserve = reward(RunTalents.Talent.RESERVE)
        assertEquals(125, reserve.maxHealth); assertEquals(125, reserve.health)
    }
    @Test fun fastReloadAcquisitionPreservesAnExistingReloadProgress() {
        val seed = (0..100).first {
            RunTalents(Random(it)).apply { draft() }.offers.contains(RunTalents.Talent.QUICK_RELOAD)
        }
        val game = GameRules(talents = RunTalents(Random(seed))).apply { tick(2.5f) }
        killTo(game, 20); game.shoot(); game.reload(); game.tick(0.5f) { false }
        assertTrue(game.openRewards())
        game.selectReward(game.talents.offers.indexOf(RunTalents.Talent.QUICK_RELOAD))
        assertTrue(game.confirmTalent())
        assertEquals((1.65f - 0.5f) * 0.75f, game.reloadRemaining, 0.0001f)
    }
    @Test fun draftIsSeededUniqueAndDismissKeepsOwnedEffects() {
        val a = RunTalents(Random(7)); val b = RunTalents(Random(7))
        a.draft(); b.draft(); assertEquals(a.offers, b.offers)
        assertEquals(3, a.offers.toSet().size)
        a.confirm(); a.draft(); a.dismiss()
        assertEquals(1, a.acquired.size); assertFalse(a.pending)
        a.reset(); assertTrue(a.acquired.isEmpty())
    }
}
