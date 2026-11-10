package com.pico.demo.wasteland.game

import java.io.*
import java.util.zip.CRC32
import kotlin.math.*

/** Immutable capsule-clearance samples. Native physics is queried only during ECS initialization. */
class NavigationGrid private constructor(private val sampled: BooleanArray, private val blocked: BooleanArray) {
    fun covers(x: Float, z: Float): Boolean = index(x, z)?.let { sampled[it] } == true
    fun walkable(x: Float, z: Float): Boolean = index(x, z)?.let { !blocked[it] } ?: false
    /** Cache probes must revisit the sampled lattice point, not an arbitrary point in its cell. */
    fun sampledPosition(x: Float, z: Float): Pair<Float, Float>? = index(x, z)?.let {
        if (!sampled[it]) null
        else (V3Layout.MIN_X + (it % WIDTH) * STEP) to
            (V3Layout.MIN_Z + (it / WIDTH) * STEP)
    }
    fun clear(ax: Float, az: Float, bx: Float, bz: Float): Boolean {
        if (!ax.isFinite() || !az.isFinite() || !bx.isFinite() || !bz.isFinite()) return false
        val steps = ceil(hypot(bx - ax, bz - az) / (STEP / 4f)).toInt().coerceAtLeast(1)
        return (0..steps).all { i ->
            val t = i.toFloat() / steps
            walkable(ax + (bx - ax) * t, az + (bz - az) * t)
        }
    }
    /** Explicit byte states and checksum reject truncation, stale content and malformed caches. */
    fun encode(fingerprint: String): ByteArray {
        val payload = ByteArray(sampled.size) { i ->
            if (!sampled[i]) 0 else if (blocked[i]) 2 else 1
        }
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeInt(MAGIC); it.writeInt(FORMAT)
                it.writeUTF(fingerprint); it.writeInt(WIDTH); it.writeInt(DEPTH)
                it.writeFloat(STEP); it.writeInt(payload.size)
                it.write(payload); it.writeLong(CRC32().apply { update(payload) }.value)
            }
        }.toByteArray()
    }
    class Builder {
        private val sampled = BooleanArray(WIDTH * DEPTH)
        private val blocked = BooleanArray(WIDTH * DEPTH)
        fun sample(x: Float, z: Float, clear: Boolean) {
            index(x, z)?.let { sampled[it] = true; blocked[it] = !clear }
        }
        fun build() = NavigationGrid(sampled.copyOf(), blocked.copyOf())
    }
    companion object {
        private const val MAGIC = 0x574E4156
        private const val FORMAT = 1
        const val STEP = 0.5f
        private val WIDTH = ((V3Layout.MAX_X - V3Layout.MIN_X) / STEP).toInt() + 1
        private val DEPTH = ((V3Layout.MAX_Z - V3Layout.MIN_Z) / STEP).toInt() + 1
        fun decode(bytes: ByteArray, fingerprint: String): NavigationGrid? = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT ||
                    input.readUTF() != fingerprint || input.readInt() != WIDTH ||
                    input.readInt() != DEPTH || input.readFloat() != STEP ||
                    input.readInt() != WIDTH * DEPTH) return null
                val states = ByteArray(WIDTH * DEPTH).also { input.readFully(it) }
                if (input.readLong() != CRC32().apply { update(states) }.value ||
                    input.available() != 0 || states.any { it !in 0..2 }) return null
                NavigationGrid(BooleanArray(states.size) { states[it] != 0.toByte() },
                    BooleanArray(states.size) { states[it] == 2.toByte() })
            }
        } catch (_: IOException) { null }
        private fun index(x: Float, z: Float): Int? {
            if (!x.isFinite() || !z.isFinite() || x !in V3Layout.MIN_X..V3Layout.MAX_X ||
                z !in V3Layout.MIN_Z..V3Layout.MAX_Z) return null
            val ix = ((x - V3Layout.MIN_X) / STEP).roundToInt()
            val iz = ((z - V3Layout.MIN_Z) / STEP).roundToInt()
            return iz * WIDTH + ix
        }
        val buildingRegions = listOf(
            V3Layout.Obstacle("RepairNavigation", 0f, -10f, 18f, 14f, 0f),
            V3Layout.Obstacle("WarehouseNavigation", 28f, -50f, 22f, 19f, 0f),
            V3Layout.Obstacle("CraneNavigation", 0f, -54f, 23f, 12f, 0f),
            V3Layout.Obstacle("ReactorNavigation", 0f, -88f, 30f, 24f, 0f),
        ) + (V3Layout.covers + V7Layout.obstacles).map {
            // A capsule can touch a prop before its center reaches the layout rectangle.
            it.copy(width = it.width + 2f, depth = it.depth + 2f)
        }
    }
}
