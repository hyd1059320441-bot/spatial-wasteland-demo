package com.pico.demo.wasteland.game

/** Reserved footprints are real plinths/feet, shared by rendering, collision and navigation. */
object V7Layout {
    const val POWER_X = -6.8f
    const val POWER_Z = -18f
    const val POWER_WIDTH = 3.2f
    val gantryZ = listOf(-0.1f, -11f)
    const val POST_X = 5.2f
    val obstacles = listOf(
        V3Layout.Obstacle("InductionPlinth", POWER_X, POWER_Z, POWER_WIDTH, POWER_WIDTH, 1.8f),
    ) + gantryZ.flatMapIndexed { index, z ->
        listOf(-POST_X, POST_X).map { x ->
            V3Layout.Obstacle("EntryLampFoot${index}_$x", x, z, 0.45f, 0.45f, 3.85f)
        }
    }
}
