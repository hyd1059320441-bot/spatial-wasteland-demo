package com.pico.demo.wasteland.game

import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.math.*
import kotlin.math.*

/** Bake static detail into one surface per material; no per-piece draw calls or frame uploads. */
object VisualGeometry {
    data class Box(val center: Vector3, val size: Vector3, val yaw: Float = 0f)
    fun boxes(boxes: List<Box>): MeshModel {
        val positions = ArrayList<Vector3>(boxes.size * 24)
        val normals = ArrayList<Vector3>(boxes.size * 24)
        val indices = ArrayList<Int>(boxes.size * 36)
        val faces = listOf(
            listOf(Vector3(-1f, -1f, 1f), Vector3(1f, -1f, 1f), Vector3(1f, 1f, 1f), Vector3(-1f, 1f, 1f)),
            listOf(Vector3(1f, -1f, -1f), Vector3(-1f, -1f, -1f), Vector3(-1f, 1f, -1f), Vector3(1f, 1f, -1f)),
            listOf(Vector3(1f, -1f, 1f), Vector3(1f, -1f, -1f), Vector3(1f, 1f, -1f), Vector3(1f, 1f, 1f)),
            listOf(Vector3(-1f, -1f, -1f), Vector3(-1f, -1f, 1f), Vector3(-1f, 1f, 1f), Vector3(-1f, 1f, -1f)),
            listOf(Vector3(-1f, 1f, 1f), Vector3(1f, 1f, 1f), Vector3(1f, 1f, -1f), Vector3(-1f, 1f, -1f)),
            listOf(Vector3(-1f, -1f, -1f), Vector3(1f, -1f, -1f), Vector3(1f, -1f, 1f), Vector3(-1f, -1f, 1f)),
        )
        val faceNormals = listOf(Vector3(0f, 0f, 1f), Vector3(0f, 0f, -1f),
            Vector3.RIGHT, Vector3(-1f, 0f, 0f), Vector3.UP, Vector3(0f, -1f, 0f))
        for (box in boxes) {
            require(box.size.x > 0f && box.size.y > 0f && box.size.z > 0f)
            val rotation = EulerAngles(0f, box.yaw, 0f).toQuat()
            faces.forEachIndexed { face, vertices ->
                val start = positions.size
                vertices.forEach {
                    positions.add(box.center + rotation.rotateVector(
                        Vector3(it.x * box.size.x, it.y * box.size.y, it.z * box.size.z) * 0.5f))
                    normals.add(rotation.rotateVector(faceNormals[face]))
                }
                indices.addAll(listOf(start, start + 1, start + 2, start, start + 2, start + 3))
            }
        }
        return MeshModel(positions, indices, normals)
    }

    /** Reflect into the visible half-space; never fling impact streaks through the struck wall. */
    fun impactVelocity(index: Int, normal: Vector3, incoming: Vector3): Vector3 {
        val n = if (normal.length() > 0.001f) normal.unit() else Vector3.UP
        val reflected = incoming - n * (2f * dot(incoming, n))
        val reference = if (abs(n.y) < 0.9f) Vector3.UP else Vector3.RIGHT
        val tangent = cross(n, reference).unit()
        val bitangent = cross(n, tangent).unit()
        val angle = index * 2.39996f
        val candidate = reflected * 1.8f + n * 2.4f +
            (tangent * cos(angle) + bitangent * sin(angle)) * (1.3f + index * 0.22f)
        return candidate + n * max(0f, 0.8f - dot(candidate, n))
    }
    private fun Vector3.unit() = this * (1f / length().coerceAtLeast(0.0001f))
    private fun dot(a: Vector3, b: Vector3) = a.x * b.x + a.y * b.y + a.z * b.z
    private fun cross(a: Vector3, b: Vector3) = Vector3(a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
}
