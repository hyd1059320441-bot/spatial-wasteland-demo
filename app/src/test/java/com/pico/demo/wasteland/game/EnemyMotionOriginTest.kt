package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test

class EnemyMotionOriginTest {
    @Test fun reusedSlotStartsAtNewSpawnAndNeverAtTheOldBarrelOrCorpse() {
        val enemy = GameRules.Enemy(11, generation = 2, spawnX = 12f, spawnZ = -17f,
            x = 12.03f, z = -16.99f)
        assertEquals(12f to -17f, EnemyMotionOrigin.point(1, -3f, -15f, enemy))
        assertEquals(12f to -17f, EnemyMotionOrigin.point(1, 0f, -9f, enemy))
        assertEquals(12f to -17f, EnemyMotionOrigin.point(2, 12f, -17f, enemy))
    }
    @Test fun existingEnemyUsesTheExactPreviousFramePose() {
        val enemy = GameRules.Enemy(0, generation = 4, spawnX = 18f, spawnZ = -20f,
            x = 2.03f, z = -6f)
        assertEquals(2f to -6f, EnemyMotionOrigin.point(4, 2f, -6f, enemy))
    }
}
