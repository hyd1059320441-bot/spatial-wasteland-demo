package com.pico.demo.wasteland.game

import kotlin.math.hypot

/** Authored points remain in meter space; the worker resolves exact native capsule clearance. */
object BattleSpawns {
    val points = (V3Layout.entrySpawn + V3Layout.quarrySpawn + V3Layout.storageSpawn +
        V3Layout.reactorSpawn + V3Layout.finalSpawn).distinct()

    fun choose(serial: Int, playerX: Float, playerZ: Float, occupied: List<Pair<Float, Float>>,
        resolve: (Float, Float) -> Pair<Float, Float>): Pair<Float, Float>? {
        val ordered = points.indices.map { points[(it + serial * 7) % points.size] }
        for (range in listOf(8f..28f, 6f..40f)) {
            for ((x, z) in ordered) {
                val point = runCatching { resolve(x, z) }.getOrNull() ?: continue
                if (hypot(point.first - playerX, point.second - playerZ) !in range) continue
                if (!V3Layout.walkable(point.first, point.second, true)) continue
                if (occupied.any { hypot(it.first - point.first, it.second - point.second) < 2.2f }) continue
                return point
            }
        }
        // Edge areas still have safe local reinforcements; never spawn on the player's capsule.
        for (range in listOf(6f..28f, 6f..45f)) for (i in 0 until 72) {
            val angle = (i + serial * 7) * kotlin.math.PI.toFloat() / 12f
            val distance = 9f + (i / 24) * 5f
            val point = runCatching { resolve(playerX + kotlin.math.cos(angle) * distance,
                playerZ + kotlin.math.sin(angle) * distance) }.getOrNull() ?: continue
            if (hypot(point.first - playerX, point.second - playerZ) !in range) continue
            if (!V3Layout.walkable(point.first, point.second, true)) continue
            if (occupied.none { hypot(it.first - point.first, it.second - point.second) < 2.2f }) return point
        }
        return null
    }
}
