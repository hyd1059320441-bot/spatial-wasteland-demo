package com.pico.demo.wasteland.game

import com.pico.spatial.core.math.Vector3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class VisualGeometryTest {
    @Test fun bakedSurfaceHasValidOutwardFacesAfterRotation() {
        val mesh = VisualGeometry.boxes(listOf(
            VisualGeometry.Box(Vector3(4f, 0.6f, -8f), Vector3(2f, 1f, 3f), 37f),
            VisualGeometry.Box(Vector3(-3f, 0.03f, -12f), Vector3(0.2f, 0.01f, 1.5f), -23f),
        ))
        assertEquals(48, mesh.positions.size)
        assertEquals(72, mesh.triangleIndices.size)
        for (triangle in mesh.triangleIndices.chunked(3)) {
            val a = mesh.positions[triangle[0]]
            val u = mesh.positions[triangle[1]] - a
            val v = mesh.positions[triangle[2]] - a
            val cross = Vector3(u.y * v.z - u.z * v.y, u.z * v.x - u.x * v.z, u.x * v.y - u.y * v.x)
            val normal = mesh.normals!![triangle[0]]
            assertTrue(cross.x * normal.x + cross.y * normal.y + cross.z * normal.z > 0f)
            assertTrue(abs(normal.length() - 1f) < 0.001f)
        }
    }
    @Test fun impactsAlwaysLeaveTheStruckSurface() {
        val sloped = Vector3(0.2f, 0.9f, 0.3f).let { it * (1f / it.length()) }
        for (normal in listOf(Vector3.UP, Vector3.RIGHT, Vector3.BACK, sloped)) {
            for (incoming in listOf(normal * -1f, normal, Vector3.BACK)) {
                repeat(5) {
                    val v = VisualGeometry.impactVelocity(it, normal, incoming)
                    assertTrue(v.x * normal.x + v.y * normal.y + v.z * normal.z >= 0.799f)
                    assertTrue(v.length().isFinite())
                }
            }
        }
    }
}
