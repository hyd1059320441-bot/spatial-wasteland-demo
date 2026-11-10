package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test

class CombatRewardsTest {
    @Test fun consecutiveKillsEarnCappedBonusAndExpiredChainsStartAtBaseScore() {
        val game = GameRules().apply { tick(2.5f); tick(6f) { false }; tick(6f) { false } }
        val victims = game.enemies.filter { it.alive }.take(7)
        val expected = listOf(100, 100, 125, 150, 175, 200, 200)
        victims.forEachIndexed { index, enemy ->
            assertTrue(game.hit(enemy.slot, false, 10000))
            assertEquals(expected[index], game.lastDefeat!!.points)
        }
        val score = game.score
        assertFalse(game.hit(victims.last().slot, true, 10000))
        assertEquals(score, game.score)
        game.tick(3.01f)
        game.hit(game.enemies.first { it.alive }.slot, false, 10000)
        assertEquals(1, game.lastDefeat!!.combo)
        assertEquals(100, game.lastDefeat!!.points)
    }

    @Test fun twentyKillMilestonesGrantModestSupplyWithoutExceedingMaximumHealth() {
        val game = GameRules().apply { tick(2.5f) }
        game.damagePlayer(40)
        fun killTo(count: Int) {
            while (game.kills < count) {
                repeat(100) { if (game.aliveCount == 0) game.tick(0.1f) { false } }
                game.hit(game.enemies.first { it.alive }.slot, false, 10000)
                if (game.kills < count) game.tick(0.8f) { false }
            }
        }
        killTo(20)
        assertEquals(75, game.health)
        assertTrue(game.message.contains("+15 生命"))
        val last = game.lastDefeat!!.slot
        assertFalse(game.hit(last, false, 10000)); assertEquals(75, game.health)
        // Avoid a healing talent here: this assertion isolates the existing wave supply.
        game.openRewards()
        while (game.talents.selected == RunTalents.Talent.REPAIR ||
            game.talents.selected == RunTalents.Talent.RESERVE) game.cycleReward()
        game.confirmTalent(); game.tick(0.8f) { false }; killTo(40)
        assertEquals(90, game.health)
        game.restart(); game.tick(2.5f)
        game.damagePlayer(3); killTo(20)
        assertEquals(100, game.health)
        assertTrue(game.message.contains("+3 生命"))
    }

    @Test fun pausingCannotEarnRewardsOrSpendASupplyByShootingCorpses() {
        val game = GameRules().apply { tick(2.5f) }
        game.damagePlayer(20); game.hit(0, false, 10000)
        val score = game.score
        game.togglePause()
        game.enemies.forEach { assertFalse(game.hit(it.slot, true, 10000)) }
        assertEquals(score, game.score); assertEquals(80, game.health)
        game.togglePause()
        while (game.kills < 20) {
            repeat(100) { if (game.aliveCount == 0) game.tick(0.1f) { false } }
            game.hit(game.enemies.first { it.alive }.slot, false, 10000)
            if (game.kills < 20) game.tick(0.8f) { false }
        }
        assertEquals(95, game.health)
    }
}
