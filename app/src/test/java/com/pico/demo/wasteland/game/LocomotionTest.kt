package com.pico.demo.wasteland.game

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3

class LocomotionTest {
    @Test fun headRelativeFourDirectionsAndDeadZone() {
        assertEquals(Locomotion.Step(0f, 0f), Locomotion.step(0.1f, 0.1f, 0f, -1f, 0.05f))
        val ahead = Locomotion.step(0f, 1f, 0f, -1f, 0.05f)
        assertEquals(0f, ahead.x, 0.0001f); assertTrue(ahead.z < 0f)
        assertTrue(Locomotion.step(0f, -1f, 0f, -1f, 0.05f).z > 0f)
        assertTrue(Locomotion.step(1f, 0f, 0f, -1f, 0.05f).x > 0f)
        assertTrue(Locomotion.step(-1f, 0f, 0f, -1f, 0.05f).x < 0f)
        val rotated = Locomotion.step(0f, 1f, 1f, 0f, 0.05f)
        assertTrue(rotated.x > 0f); assertEquals(0f, rotated.z, 0.0001f)
    }
    @Test fun diagonalSpeedPitchAndLongFrameRemainBounded() {
        val straight = Locomotion.step(0f, 1f, 0f, -1f, 0.05f)
        val diagonal = Locomotion.step(1f, 1f, 0f, -0.1f, 0.05f)
        assertEquals(hypot(straight.x, straight.z), hypot(diagonal.x, diagonal.z), 0.0001f)
        assertEquals(straight, Locomotion.step(0f, 1f, 0f, -1f, 4f))
        assertEquals(Locomotion.Step(0f, 0f), Locomotion.step(Float.NaN, 1f, 0f, -1f, 0.05f))
        assertEquals(Locomotion.Step(0f, 0f), Locomotion.step(0f, 1f, 0f, 0f, 0.05f))
    }
    @Test fun wallSlideAndSubstepsPreventCoverTunnelling() {
        val blocked = V3Layout.slide(0f, -64f, 0f, -8f, false)
        assertTrue(blocked.second > V3Layout.GATE_Z)
        val cover = V3Layout.covers.first { it.name == "QuarryCargoWest" }
        val along = V3Layout.slide(cover.x, cover.z + cover.depth / 2 + 0.4f, 1f, -2f, true)
        assertFalse(cover.contains(along.first, along.second))
        assertTrue(along.first > cover.x)
    }
    @Test fun hmdYawAndVirtualSnapComposeIntoCurrentWorldFacing() {
        for (headYaw in listOf(-170f, -90f, 0f, 45f, 90f, 175f)) {
            for (rootYaw in listOf(-120f, -30f, 0f, 60f, 180f)) {
                val physical = EulerAngles(0f, headYaw, 0f).toQuat()
                val root = EulerAngles(0f, rootYaw, 0f).toQuat()
                val local = SceneCoordinates.inverse(root) * physical
                val delta = Locomotion.headRelativeStep(0f, 1f, local, 0.05f)
                val inWorld = root.rotateVector(Vector3(delta.x, 0f, delta.z))
                val expected = physical.rotateVector(Vector3(0f, 0f, -1f)) * (Locomotion.SPEED * 0.05f)
                assertEquals("head=$headYaw root=$rootYaw", expected.x, inWorld.x, 0.0001f)
                assertEquals(expected.z, inWorld.z, 0.0001f)
            }
        }
    }
    @Test fun lookingStraightUpOrDownPreservesHorizontalHeading() {
        for (pitch in listOf(-90f, -85f, 0f, 85f, 90f)) {
            val rotation = EulerAngles(pitch, 60f, 0f).toQuat()
            val forward = Locomotion.headRelativeStep(0f, 1f, rotation, 0.05f)
            val expected = EulerAngles(0f, 60f, 0f).toQuat().rotateVector(Vector3(0f, 0f, -1f))
            assertEquals(expected.x * 0.17f, forward.x, 0.0001f)
            assertEquals(expected.z * 0.17f, forward.z, 0.0001f)
            assertEquals(0.17f, hypot(forward.x, forward.z), 0.0001f)
        }
    }
}
