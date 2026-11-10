package com.pico.demo.wasteland.game

import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.*
import org.junit.Test

class HeadPoseCacheTest {
    @Test fun staleOrInvalidTrackingCannotJumpThePlayerToTheAnchorOrigin() {
        val cache = HeadPoseCache()
        val standing = Vector3(0.1f, 0.906f, 0.2f)
        assertTrue(cache.accept(standing, Quat(), 1000, 1000))
        assertFalse(cache.accept(Vector3.ZERO, Quat(), 1001, 1001))
        assertFalse(cache.accept(Vector3(0f, 1.8f, 0f), Quat(), 1000, 1151))
        assertFalse(cache.accept(standing, Quat(), 1002, 1001))
        assertEquals(standing, cache.position)
        val crouched = Vector3(0.3f, 0.506f, -0.1f)
        assertTrue(cache.accept(crouched, Quat(), 1100, 1250))
        assertEquals(crouched, cache.position)
    }
}
