package com.pico.demo.wasteland.game

import com.pico.spatial.core.math.Vector3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DefeatGeometryTest {
    @Test fun debrisNeverTravelsTowardThePlayerAndRemainsWithinTheTargetSilhouette() {
        val center = Vector3(0f, 1.05f, -3f)
        for (angle in 0..359 step 5) for (distance in listOf(0.2f, 0.6f, 1.5f, 12f)) {
            val a = angle * PI.toFloat() / 180f
            val player = center + Vector3(cos(a) * distance, 0.6f, sin(a) * distance)
            val away = DefeatGeometry.away(center, player)
            for (elite in listOf(false, true)) for (frame in 0..42) for (i in 0..5) {
                val p = DefeatGeometry.shardOffset(i, frame * 0.01f, away, elite)
                assertTrue(p.x * away.x + p.z * away.z >= 0.179f)
                assertTrue(p.y < 0f)
                assertTrue("offset=$p", p.length() <= DefeatGeometry.MAX_RADIUS)
                assertTrue((center + p - player).length() >= (center - player).length())
            }
        }
    }
    @Test fun contactKillsUseDebrisOnlyAndCoincidentPositionsRemainFinite() {
        val center = Vector3(0f, 1.05f, 0f)
        assertFalse(DefeatGeometry.particlesAllowed(center, Vector3(0f, 1.6f, 0.6f)))
        assertTrue(DefeatGeometry.particlesAllowed(center, Vector3(0f, 1.6f, 5f)))
        val offset = DefeatGeometry.shardOffset(0, 0.3f, DefeatGeometry.away(center, center), false)
        assertTrue(offset.x.isFinite() && offset.y.isFinite() && offset.z.isFinite())
        assertEquals(0.42f, DefeatGeometry.DURATION, 0f)
    }
}
