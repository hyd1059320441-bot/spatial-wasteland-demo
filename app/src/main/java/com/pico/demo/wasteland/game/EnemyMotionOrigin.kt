package com.pico.demo.wasteland.game

/** A reused actor slot is a new enemy, so its native movement starts at its own spawn. */
object EnemyMotionOrigin {
    fun point(previousGeneration: Int, previousX: Float, previousZ: Float,
        enemy: GameRules.Enemy): Pair<Float, Float> =
        if (previousGeneration == enemy.generation) previousX to previousZ
        else enemy.spawnX to enemy.spawnZ
}
