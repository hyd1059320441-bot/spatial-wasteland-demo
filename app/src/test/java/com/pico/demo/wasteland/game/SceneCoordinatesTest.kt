package com.pico.demo.wasteland.game

import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.*
import org.junit.Test

class SceneCoordinatesTest {
    private fun close(expected: Vector3, actual: Vector3) {
        assertEquals(expected.x, actual.x, 0.0001f)
        assertEquals(expected.y, actual.y, 0.0001f)
        assertEquals(expected.z, actual.z, 0.0001f)
    }
    @Test fun restartMapsTheCurrentTrackedEyeWithoutReadingThePreviousNativeMatrix() {
        val tracked = Vector3(1.4f, 1.68f, -0.7f)
        val start = Vector3(0f, 1.68f, -2f)
        var previous = Vector3.ZERO
        repeat(100) {
            val q = EulerAngles(0f, it * 30f, 0f).toQuat()
            val distant = Vector3(-10f, 3.68f, -90f)
            previous = SceneCoordinates.placement(tracked, distant, q)
            close(distant, SceneCoordinates.localPoint(tracked, previous, q))
            val zeroYaw = EulerAngles().toQuat()
            val restart = SceneCoordinates.placement(tracked, start, zeroYaw)
            close(start, SceneCoordinates.localPoint(tracked, restart, zeroYaw))
        }
    }
    @Test fun parentOffsetsAndHeadMotionSurviveSnapTurnAndRepositioning() {
        val head = Vector3(-0.7f, 1.6f, 0.9f)
        val virtual = Vector3(4f, 3.6f, -84f)
        val q = EulerAngles(0f, 90f, 0f).toQuat()
        val position = SceneCoordinates.placement(head, virtual, q)
        close(virtual, SceneCoordinates.localPoint(head, position, q))
        val delta = Vector3(0.2f, -0.1f, 0.3f)
        close(virtual + SceneCoordinates.inverse(q).rotateVector(delta),
            SceneCoordinates.localPoint(head + delta, position, q))
        val controller = head + Vector3(0.3f, -0.5f, -0.4f)
        close(virtual + SceneCoordinates.inverse(q).rotateVector(controller - head),
            SceneCoordinates.localPoint(controller, position, q))
    }
}
