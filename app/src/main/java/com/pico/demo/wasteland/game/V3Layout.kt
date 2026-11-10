package com.pico.demo.wasteland.game

import java.util.PriorityQueue
import kotlin.math.*

/** The same meter-based layout drives visual placement, physics, player movement and AI. */
object V3Layout {
    const val MIN_X = -39f
    const val MAX_X = 39f
    const val MIN_Z = -97f
    const val MAX_Z = 3f
    const val GATE_Z = -68f
    const val GATE_HALF_WIDTH = 4.5f
    const val GENERATOR_X = 26f
    const val GENERATOR_Z = -60f
    const val CHEST_X = 3f
    const val CHEST_Z = -90f
    const val REPAIR_Z = -6f
    const val REPAIR_SCALE = 1.42f
    const val REPAIR_HEIGHT = 3f * REPAIR_SCALE
    // Composed USD source: metersPerUnit=1, Y-up. Enlarge all axes together.
    const val REPAIR_WIDTH = 3.213f * REPAIR_SCALE
    const val REPAIR_DEPTH = 3.177f * REPAIR_SCALE
    // The authored floor's upper triangles are at ~0.064 m, above the model's minimum Y.
    const val REPAIR_FLOOR = 0.07f * REPAIR_SCALE
    const val CONTAINER_LENGTH = 6.058f
    const val CONTAINER_WIDTH = 2.438f
    const val CONTAINER_HEIGHT = 2.591f
    enum class Zone(val label: String) { ENTRY("维修区"), QUARRY("中央采矿场"), STORAGE("仓储侧路"), REACTOR("反应堆厂房") }
    data class Obstacle(val name: String, val x: Float, val z: Float,
        val width: Float, val depth: Float, val height: Float, val asset: String = "CargoContainer") {
        fun contains(px: Float, pz: Float, margin: Float = 0.35f) =
            abs(px - x) < width / 2 + margin && abs(pz - z) < depth / 2 + margin
    }
    val covers = listOf(
        cargo("EntryCargoWest", -12f, -12f),
        cargo("EntryCargoEast", 11f, -14f),
        Obstacle("MiningTruckWest", -22f, -15f, 7f, 10f, 4.5f, "MiningTruck"),
        Obstacle("ServiceRock", 25f, -10f, 6f, 6f, 3.5f, "SandstoneRock"),
        Obstacle("EntryRockWest", -23f, -26f, 7f, 5f, 4f, "SandstoneRock"),
        Obstacle("EntryRockEast", 18f, -24f, 7f, 5f, 4.5f, "SandstoneRock"),
        Obstacle("QuarryOreWest", -20f, -36f, 8f, 8f, 4f, "SandstoneRock"),
        Obstacle("QuarryOreEast", 12f, -37f, 7f, 6f, 3f, "SandstoneRock"),
        cargo("QuarryCargoWest", -7.5f, -35f, true),
        cargo("QuarryCargoEast", 7.5f, -45f),
        cargo("QuarryCargoRear", -11f, -47f, true),
        cargo("StorageCargoFront", 25f, -40f),
        cargo("StorageCargoSide", 34f, -51f, true),
        cargo("StorageCargoWest", 14.5f, -53f, true),
        cargo("StorageCargoRear", 29f, -64f),
        Obstacle("Generator", GENERATOR_X, GENERATOR_Z, 2.5f, 2f, 2f, "Generator"),
        Obstacle("WesternRock", -26f, -58f, 9f, 10f, 5f, "SandstoneRock"),
        Obstacle("ReactorLeftBase", -11f, -77f, 5f, 7f, 4f, "SandstoneRock"),
        Obstacle("ReactorRightBase", 11f, -77f, 5f, 7f, 4f, "SandstoneRock"),
        cargo("ReactorCoverWest", -7f, -86f, true),
        cargo("ReactorCoverEast", 7.5f, -85f, true),
        Obstacle("LootChest", CHEST_X, CHEST_Z, 1.5f, 1.1f, 0.95f, "LootChest"),
    )
    /** Open entrances remain traversable; wall strips are shared with rendered collision geometry. */
    val structures = listOf(
        Obstacle("RepairWallWest", -1.51f * REPAIR_SCALE, REPAIR_Z,
            0.16f * REPAIR_SCALE, REPAIR_DEPTH, 1.05f * REPAIR_SCALE),
        Obstacle("RepairWallEast", 1.51f * REPAIR_SCALE, REPAIR_Z,
            0.16f * REPAIR_SCALE, REPAIR_DEPTH, 1.05f * REPAIR_SCALE),
        Obstacle("WarehouseWallWest", 19.3f, -50f, 0.6f, 15f, 7f),
        Obstacle("WarehouseWallEast", 36.7f, -50f, 0.6f, 15f, 7f),
        Obstacle("WarehouseWallBack", 28f, -57.2f, 18f, 0.6f, 7f),
        Obstacle("ReactorWallWest", -12.7f, -88f, 0.6f, 20f, 18f),
        Obstacle("ReactorWallEast", 12.7f, -88f, 0.6f, 20f, 18f),
        Obstacle("ReactorWallBack", 0f, -97.5f, 26f, 1f, 18f),
        Obstacle("CraneLegWest", -8.5f, -54f, 1.8f, 7f, 10f),
        Obstacle("CraneLegEast", 8.5f, -54f, 1.8f, 7f, 10f),
        Obstacle("GateWallWest", -21.75f, GATE_Z, 34.5f, 2f, 4.5f),
        Obstacle("GateWallEast", 21.75f, GATE_Z, 34.5f, 2f, 4.5f),
    ) + listOf(-35f, 35f).flatMap { x ->
        (0..8).map { i ->
            Obstacle("Perimeter${x}Rock$i", x + sign(x) * (i % 2) * 1.5f,
                -3f - i * 12f, 8f + i % 3, 7f + (i + 1) % 3, 5f + i % 2)
        }
    }
    private val solids = covers + structures + V5Layout.obstacles + V7Layout.obstacles + V8Layout.props
    val barrelPositions = listOf(-3f to -15f, 8f to -19f, -3f to -39f, 8f to -51f,
        22f to -48f, 29f to -60f, -5f to -82f, 10f to -88f)
    val entrySpawn = listOf(-3f to -17f, 3f to -19f, 10f to -17f, -10f to -18f,
        0f to -23f, 8f to -27f, -18f to -22f, 19f to -20f,
        -3f to -20f, 5f to -22f, -10f to -20f, 12f to -17f,
        -24f to -23f, 25f to -18f)
    val quarrySpawn = listOf(-12f to -33f, 3f to -32f, 17f to -32f, -2f to -49f,
        14f to -49f, 0f to -58f, -28f to -42f, 25f to -34f, -18f to -52f, 9f to -59f,
        -2f to -31f, 7f to -30f, -12f to -39f, 3f to -39f,
        -3f to -44f, 13f to -44f)
    val storageSpawn = listOf(22f to -46f, 29f to -47f, 22f to -55f, 31f to -58f, 14f to -60f, 20f to -62f)
    val reactorSpawn = listOf(-4f to -72f, 4f to -73f, -17f to -82f, 17f to -82f,
        -20f to -92f, 20f to -92f, 0f to -76f, -4f to -75f, 5f to -72f,
        -19f to -76f, 20f to -77f, -3f to -84f)
    val eliteSpawn = 0f to -89f
    val shippingContainers = covers.filter { it.asset == "CargoContainer" }.map { it.name }.toSet()
    private fun cargo(name: String, x: Float, z: Float, rotate: Boolean = false) =
        Obstacle(name, x, z, if (rotate) CONTAINER_WIDTH else CONTAINER_LENGTH,
            if (rotate) CONTAINER_LENGTH else CONTAINER_WIDTH, CONTAINER_HEIGHT)
    val finalSpawn = listOf(-4f to -78f, 4f to -79f, -16f to -90f, 17f to -91f,
        0f to -94f, -22f to -74f, 22f to -75f, -14f to -70f, 14f to -72f, 0f to -84f,
        -2f to -80f, 2f to -76f, -5f to -90f, 1f to -91f,
        -19f to -84f, 19f to -85f)
    fun zone(x: Float, z: Float) = when {
        z < GATE_Z -> Zone.REACTOR
        x > 14f && z < -36f -> Zone.STORAGE
        z < -27f -> Zone.QUARRY
        else -> Zone.ENTRY
    }
    /** A 2 m reactor deck has a broad central ramp. Its side edges are not walkable cliffs. */
    fun heightAt(x: Float, z: Float): Float =
        if (abs(x) <= 15f && z <= -81f) 2f
        else if (abs(x) <= 5f && z in -81f..-73f) (-73f - z) * 0.25f
        else {
            val outside = maxOf(abs(x) - REPAIR_WIDTH / 2f,
                abs(z - REPAIR_Z) - REPAIR_DEPTH / 2f, 0f)
            REPAIR_FLOOR * (1f - outside / 0.3f).coerceIn(0f, 1f)
        }

    fun walkable(x: Float, z: Float, gateOpen: Boolean): Boolean =
        x.isFinite() && z.isFinite() && x in MIN_X..MAX_X && z in MIN_Z..MAX_Z &&
            (gateOpen || z > GATE_Z + 0.6f) &&
            !(abs(z - GATE_Z) < 0.8f && abs(x) > GATE_HALF_WIDTH - 0.4f) &&
            solids.none { it.contains(x, z) }

    fun nearestWalkable(x: Float, z: Float, gateOpen: Boolean): Pair<Float, Float> {
        if (walkable(x, z, gateOpen)) return x to z
        for (ring in 1..30) {
            val radius = ring * 0.25f
            for (i in 0 until 24) {
                val angle = i * PI.toFloat() / 12f
                val px = x + cos(angle) * radius; val pz = z + sin(angle) * radius
                if (walkable(px, pz, gateOpen)) return px to pz
            }
        }
        return 0f to -2f
    }

    private fun crossingAllowed(ax: Float, az: Float, bx: Float, bz: Float, gateOpen: Boolean): Boolean {
        if ((az - GATE_Z) * (bz - GATE_Z) <= 0f && abs(bz - az) > 0.0001f) {
            val x = ax + (bx - ax) * (GATE_Z - az) / (bz - az)
            if (!gateOpen || abs(x) > GATE_HALF_WIDTH - 0.4f) return false
        }
        return true
    }
    fun routeClear(ax: Float, az: Float, bx: Float, bz: Float, gateOpen: Boolean): Boolean {
        if (!ax.isFinite() || !az.isFinite() || !bx.isFinite() || !bz.isFinite()) return false
        if (!crossingAllowed(ax, az, bx, bz, gateOpen)) return false
        val distance = hypot(bx - ax, bz - az)
        val steps = ceil(distance / 0.16f).toInt().coerceAtLeast(1)
        var lastHeight = heightAt(ax, az)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val x = ax + (bx - ax) * t; val z = az + (bz - az) * t
            val height = heightAt(x, z)
            if (!walkable(x, z, gateOpen) || abs(height - lastHeight) > 0.12f) return false
            lastHeight = height
        }
        return true
    }
    fun canTeleport(fromX: Float, fromZ: Float, x: Float, z: Float, gateOpen: Boolean): Boolean =
        hypot(x - fromX, z - fromZ) in 0.3f..11f &&
            walkable(x, z, gateOpen) && crossingAllowed(fromX, fromZ, x, z, gateOpen) &&
            // The ray/arc in the scene checks cover occlusion; no jumping up the deck sides.
            abs(heightAt(x, z) - heightAt(fromX, fromZ)) <= 0.5f

    fun lineClear(ax: Float, az: Float, bx: Float, bz: Float): Boolean {
        if (!ax.isFinite() || !az.isFinite() || !bx.isFinite() || !bz.isFinite()) return false
        return crossingAllowed(ax, az, bx, bz, true) &&
            solids.none { it.intersectsSegment(ax, az, bx, bz) }
    }
    /** Continuous slab intersection, including contact and endpoints; thin walls cannot be skipped. */
    private fun Obstacle.intersectsSegment(ax: Float, az: Float, bx: Float, bz: Float): Boolean {
        var enter = 0.0
        var exit = 1.0
        fun clip(start: Double, end: Double, minimum: Double, maximum: Double): Boolean {
            val delta = end - start
            if (delta == 0.0) return start in minimum..maximum
            val a = (minimum - start) / delta
            val b = (maximum - start) / delta
            enter = max(enter, min(a, b))
            exit = min(exit, max(a, b))
            return enter <= exit
        }
        return clip(ax.toDouble(), bx.toDouble(), x.toDouble() - width / 2.0, x.toDouble() + width / 2.0) &&
            clip(az.toDouble(), bz.toDouble(), z.toDouble() - depth / 2.0, z.toDouble() + depth / 2.0)
    }
    /** Limited-distance frame motion, split into small steps, never tunnels across thin walls. */
    fun slide(x: Float, z: Float, dx: Float, dz: Float, gateOpen: Boolean): Pair<Float, Float> {
        if (!x.isFinite() || !z.isFinite() || !dx.isFinite() || !dz.isFinite()) return x to z
        val distance = hypot(dx, dz)
        if (distance > 12f) return x to z
        val steps = ceil(distance / 0.06f).toInt().coerceAtLeast(1)
        var px = x; var pz = z
        repeat(steps) {
            val sx = dx / steps; val sz = dz / steps
            if (routeClear(px, pz, px + sx, pz + sz, gateOpen)) { px += sx; pz += sz }
            else {
                if (routeClear(px, pz, px + sx, pz, gateOpen)) px += sx
                if (routeClear(px, pz, px, pz + sz, gateOpen)) pz += sz
            }
        }
        return px to pz
    }
    fun move(enemy: GameRules.Enemy, dx: Float, dz: Float, gateOpen: Boolean) {
        val p = slide(enemy.x, enemy.z, dx, dz, gateOpen)
        enemy.x = p.first; enemy.z = p.second
    }

    data class NavBlock(val x: Float, val z: Float, val radius: Float = 0.6f)
    fun learnedClear(ax: Float, az: Float, bx: Float, bz: Float, blocks: List<NavBlock>): Boolean {
        val dx = bx - ax; val dz = bz - az
        val squared = dx * dx + dz * dz
        return blocks.none {
            val t = if (squared < 0.00001f) 0f else
                (((it.x - ax) * dx + (it.z - az) * dz) / squared).coerceIn(0f, 1f)
            hypot(ax + dx * t - it.x, az + dz * t - it.z) < it.radius
        }
    }
    /** A* is recalculated at AI decision intervals, not on each frame. */
    fun path(ax: Float, az: Float, bx: Float, bz: Float, gateOpen: Boolean,
        blocks: List<NavBlock> = emptyList(), grid: NavigationGrid? = null): List<Pair<Float, Float>> {
        if (!ax.isFinite() || !az.isFinite() || !bx.isFinite() || !bz.isFinite()) return emptyList()
        if (!walkable(ax, az, gateOpen) || !walkable(bx, bz, gateOpen)) return emptyList()
        fun clear(ax: Float, az: Float, bx: Float, bz: Float) =
            routeClear(ax, az, bx, bz, gateOpen) && learnedClear(ax, az, bx, bz, blocks) &&
                grid?.clear(ax, az, bx, bz) != false
        if (clear(ax, az, bx, bz))
            return listOf(bx to bz)
        data class Cell(val x: Int, val z: Int)
        data class Candidate(val cell: Cell, val cost: Float)
        val step = NavigationGrid.STEP
        fun point(c: Cell) = c.x * step to c.z * step
        fun valid(c: Cell): Boolean {
            val (x, z) = point(c)
            return walkable(x, z, gateOpen) && grid?.walkable(x, z) != false &&
                blocks.none { hypot(x - it.x, z - it.z) < it.radius }
        }
        fun nearest(x: Float, z: Float, connect: Boolean): Cell? {
            val seed = Cell((x / step).roundToInt(), (z / step).roundToInt())
            return (-4..4).flatMap { dx -> (-4..4).map { dz -> Cell(seed.x + dx, seed.z + dz) } }
                .filter { valid(it) && (!connect || point(it).let { p -> clear(x, z, p.first, p.second) }) }
                .minByOrNull { point(it).let { p -> hypot(p.first - x, p.second - z) } }
        }
        val start = nearest(ax, az, true) ?: return emptyList()
        // A player can stand closer to a wall than the sampled center; approach its nearest safe cell.
        val goal = nearest(bx, bz, grid?.walkable(bx, bz) != false) ?: return emptyList()
        fun heuristic(c: Cell) = hypot((goal.x - c.x).toFloat(), (goal.z - c.z).toFloat())
        val queue = PriorityQueue<Candidate>(compareBy { it.cost })
        val cost = mutableMapOf(start to 0f)
        val previous = mutableMapOf<Cell, Cell>()
        val visited = mutableSetOf<Cell>()
        queue.add(Candidate(start, heuristic(start)))
        var result: Cell? = null
        while (queue.isNotEmpty() && visited.size < 32000 && !Thread.currentThread().isInterrupted) {
            val current = queue.remove().cell
            if (!visited.add(current)) continue
            if (current == goal) { result = current; break }
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val next = Cell(current.x + dx, current.z + dz)
                val a = point(current); val b = point(next)
                if (!valid(next) || !clear(a.first, a.second, b.first, b.second)) continue
                val candidateCost = cost.getValue(current) + hypot(dx.toFloat(), dz.toFloat())
                if (candidateCost < (cost[next] ?: Float.MAX_VALUE)) {
                    cost[next] = candidateCost; previous[next] = current
                    queue.add(Candidate(next, candidateCost + heuristic(next)))
                }
            }
        }
        var cursor = result ?: return emptyList()
        val route = mutableListOf<Pair<Float, Float>>()
        while (cursor != start) {
            route.add(point(cursor)); cursor = previous[cursor] ?: return emptyList()
        }
        route.add(point(start))
        route.reverse()
        if (route.last() != (bx to bz) && clear(route.last().first, route.last().second, bx, bz))
            route.add(bx to bz)
        return route
    }
}
