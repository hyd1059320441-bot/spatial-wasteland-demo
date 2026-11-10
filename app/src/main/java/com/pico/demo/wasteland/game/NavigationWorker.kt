package com.pico.demo.wasteland.game

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.round

/** Only immutable meter coordinates enter the worker. Entities and enemies stay on ECS/main. */
class NavigationWorker : AutoCloseable {
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(GameRules.MAX_ENEMIES), { runnable ->
            Thread(runnable, "WastelandNavigation").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
    private data class Request(val x: Float, val z: Float, val gate: Boolean,
        val result: Future<List<Pair<Float, Float>>>)
    private val pending = mutableMapOf<Int, Request>()
    private val blocks = mutableListOf<V3Layout.NavBlock>()
    private var blockSnapshot: List<V3Layout.NavBlock> = emptyList()
    private var grid: NavigationGrid? = null
    var submitted = 0; private set
    var completed = 0; private set

    fun request(slot: Int, ax: Float, az: Float, bx: Float, bz: Float, gate: Boolean,
        approaches: List<Pair<Float, Float>>? = null) {
        if (pending.containsKey(slot) || executor.isShutdown) return
        try {
            val obstacles = blockSnapshot
            val snapshot = grid
            val targets = approaches?.toList()
            val future = executor.submit<List<Pair<Float, Float>>> {
                if (targets == null) return@submit V3Layout.path(ax, az, bx, bz, gate, obstacles, snapshot)
                targets.firstNotNullOfOrNull { point ->
                    if (Thread.currentThread().isInterrupted) return@submit emptyList()
                    V3Layout.path(ax, az, point.first, point.second, gate, obstacles, snapshot)
                        .takeIf { route -> route.isNotEmpty() &&
                            hypot(route.last().first - point.first, route.last().second - point.second) < 0.06f }
                } ?: emptyList()
            }
            pending[slot] = Request(bx, bz, gate, future)
            submitted++
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    fun take(slot: Int, x: Float, z: Float, gate: Boolean): List<Pair<Float, Float>>? {
        val request = pending[slot] ?: return null
        if (!request.result.isDone) return null
        pending.remove(slot); completed++
        return if (request.gate == gate && hypot(x - request.x, z - request.z) <= 1.25f)
            try { request.result.get() } catch (_: Exception) { emptyList() }
        else null
    }

    fun clear(ax: Float, az: Float, bx: Float, bz: Float) =
        V3Layout.learnedClear(ax, az, bx, bz, blocks) && grid?.clear(ax, az, bx, bz) != false
    /** Spawn on sampled free cells, including irregular walls absent from the authored rectangles. */
    fun spawnPoint(x: Float, z: Float, gate: Boolean): Pair<Float, Float> {
        val seed = V3Layout.nearestWalkable(x, z, gate)
        val snapshot = grid ?: return seed
        if (snapshot.walkable(seed.first, seed.second)) return seed
        val sx = round(seed.first / NavigationGrid.STEP) * NavigationGrid.STEP
        val sz = round(seed.second / NavigationGrid.STEP) * NavigationGrid.STEP
        for (ring in 1..12) {
            val candidates = (-ring..ring).flatMap { dx ->
                (-ring..ring).filter { dz -> kotlin.math.abs(dx) == ring || kotlin.math.abs(dz) == ring }
                    .map { dz -> sx + dx * NavigationGrid.STEP to sz + dz * NavigationGrid.STEP }
            }
            candidates.filter {
                V3Layout.walkable(it.first, it.second, gate) && snapshot.walkable(it.first, it.second)
            }.minByOrNull { hypot(it.first - x, it.second - z) }?.let { return it }
        }
        error("No physically clear enemy spawn within 6 m of $x,$z")
    }
    fun setGrid(value: NavigationGrid) { reset(); grid = value }
    fun cancel(slot: Int) {
        pending.remove(slot)?.result?.cancel(true)
        executor.purge()
    }
    fun block(slot: Int, x: Float, z: Float, confirmedStatic: Boolean = false) {
        // A doorway crowd or a clipped corner must not permanently seal a baked entrance.
        if ((confirmedStatic || grid?.covers(x, z) != true) &&
            blocks.none { hypot(it.x - x, it.z - z) < 0.4f }) {
            if (blocks.size == 128) blocks.removeAt(0)
            blocks.add(V3Layout.NavBlock(x, z))
            blockSnapshot = blocks.toList()
        }
        pending.remove(slot)?.result?.cancel(true)
        executor.purge()
    }
    fun reset() {
        pending.values.forEach { it.result.cancel(true) }
        pending.clear(); blocks.clear(); blockSnapshot = emptyList(); executor.purge()
    }
    override fun close() { reset(); executor.shutdownNow() }
}
