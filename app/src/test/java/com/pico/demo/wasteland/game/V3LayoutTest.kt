package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class V3LayoutTest {
    @Test fun entryFacilitiesLeaveSeparatedFootprintsAndAnOpenAdultRoute() {
        val props = V3Layout.covers.filter { it.z > -28f } + V8Layout.props +
            V5Layout.obstacles.filter { it.z > -10f }
        for (a in props) for (b in props) {
            if (a.name >= b.name) continue
            val gapX = maxOf(abs(a.x - b.x) - (a.width + b.width) / 2f, 0f)
            val gapZ = maxOf(abs(a.z - b.z) - (a.depth + b.depth) / 2f, 0f)
            assertTrue("${a.name}/${b.name}", kotlin.math.hypot(gapX, gapZ) >= 1f)
        }
        assertTrue(V3Layout.REPAIR_WIDTH > 4.5f && V3Layout.REPAIR_DEPTH > 4.4f)
        assertTrue(V3Layout.routeClear(0f, -2f, 0f, -27f, false))
        assertTrue(V3Layout.routeClear(4f, -2f, 4f, -27f, false))
        assertTrue(V3Layout.routeClear(-4f, -2f, -4f, -27f, false))
        assertEquals(0.0994f, V3Layout.heightAt(0f, V3Layout.REPAIR_Z), 0.0001f)
        for (supply in V5Layout.supplies) assertTrue(V3Layout.walkable(supply.x, supply.z, false))
    }

    @Test fun mapHasFourZonesAndAllSpawnPositionsAreWalkable() {
        assertEquals(7800f, (V3Layout.MAX_X - V3Layout.MIN_X) * (V3Layout.MAX_Z - V3Layout.MIN_Z), 0f)
        assertEquals(V3Layout.Zone.ENTRY, V3Layout.zone(0f, 0f))
        assertEquals(V3Layout.Zone.QUARRY, V3Layout.zone(0f, -40f))
        assertEquals(V3Layout.Zone.STORAGE, V3Layout.zone(25f, -50f))
        assertEquals(V3Layout.Zone.REACTOR, V3Layout.zone(0f, -90f))
        for ((x, z) in V3Layout.entrySpawn + V3Layout.quarrySpawn + V3Layout.storageSpawn)
            assertTrue("Invalid spawn $x,$z", V3Layout.walkable(x, z, false))
        for ((x, z) in V3Layout.reactorSpawn + V3Layout.finalSpawn)
            assertTrue("Invalid reactor spawn $x,$z", V3Layout.walkable(x, z, true))
        assertTrue(V3Layout.walkable(V3Layout.eliteSpawn.first, V3Layout.eliteSpawn.second, true))
        assertFalse(V3Layout.walkable(40f, 0f, true))
        assertFalse(V3Layout.walkable(0f, -98f, true))
    }
    @Test fun gateCannotBeBypassedOrTeleportedThroughWhenLocked() {
        assertFalse(V3Layout.routeClear(0f, -64f, 0f, -72f, false))
        assertTrue(V3Layout.routeClear(0f, -64f, 0f, -72f, true))
        assertFalse(V3Layout.routeClear(20f, -64f, 20f, -72f, true))
        assertFalse(V3Layout.canTeleport(0f, -64f, 0f, -72f, false))
        assertTrue(V3Layout.canTeleport(0f, -64f, 0f, -72f, true))
        assertFalse(V3Layout.canTeleport(20f, -64f, 20f, -72f, true))
        assertTrue(V3Layout.path(0f, -64f, 0f, -72f, false).isEmpty())
    }
    @Test fun pathFindsCoverDetourWithEverySegmentTraversable() {
        var p = -6f to -30f
        val route = V3Layout.path(p.first, p.second, -6f, -41f, false)
        assertTrue(route.isNotEmpty())
        val cover = V3Layout.covers.first { it.name == "QuarryCargoWest" }
        assertTrue(route.any { abs(it.first - cover.x) > cover.width / 2f + 0.35f })
        for (next in route) {
            assertTrue("$p -> $next", V3Layout.routeClear(p.first, p.second, next.first, next.second, false))
            p = next
        }
        assertEquals(-6f to -41f, p)
    }
    @Test fun reactorDeckHasAccessibleRampAndBlocksSideCliffJump() {
        assertEquals(0f, V3Layout.heightAt(0f, -73f), 0f)
        assertEquals(1f, V3Layout.heightAt(0f, -77f), 0f)
        assertEquals(2f, V3Layout.heightAt(0f, -85f), 0f)
        assertTrue(V3Layout.routeClear(0f, -72f, 0f, -85f, true))
        assertFalse(V3Layout.routeClear(18f, -85f, 13f, -85f, true))
        assertFalse(V3Layout.canTeleport(18f, -85f, 13f, -85f, true))
        assertTrue(V3Layout.path(0f, -72f, 0f, -85f, true).isNotEmpty())
    }
    @Test fun longFrameMovementDoesNotTunnelAcrossCoversOrGate() {
        val p = V3Layout.slide(-6f, -30f, 0f, -11f, false)
        val cover = V3Layout.covers.first { it.name == "QuarryCargoWest" }
        assertTrue(p.second >= cover.z + cover.depth / 2f + 0.35f)
        val q = V3Layout.slide(0f, -64f, 0f, -10f, false)
        assertTrue(q.second > V3Layout.GATE_Z)
        assertEquals(0f to 0f, V3Layout.slide(0f, 0f, 100f, 0f, true))
    }
    @Test fun invalidCoordinatesNeverEnterPathfinding() {
        assertFalse(V3Layout.walkable(Float.NaN, 0f, true))
        assertFalse(V3Layout.routeClear(0f, 0f, Float.POSITIVE_INFINITY, 0f, true))
        assertTrue(V3Layout.path(Float.NaN, 0f, 0f, -30f, true).isEmpty())
        assertTrue(V3Layout.path(0f, 0f, 1000f, -30f, true).isEmpty())
    }
}
