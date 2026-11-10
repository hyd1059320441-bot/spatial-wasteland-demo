package com.pico.demo.wasteland.game

import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3

/** ECS-owned valid tracking pose. Stale/invalid callbacks never replace the last usable pose. */
class HeadPoseCache {
    var position: Vector3? = null; private set
    var rotation: Quat? = null; private set
    fun accept(point: Vector3, orientation: Quat, sampleMs: Long, nowMs: Long): Boolean {
        if (nowMs < sampleMs || nowMs - sampleMs > 150L || point == Vector3.ZERO ||
            !point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite() ||
            !orientation.x.isFinite() || !orientation.y.isFinite() ||
            !orientation.z.isFinite() || !orientation.w.isFinite()) return false
        val length = orientation.x * orientation.x + orientation.y * orientation.y +
            orientation.z * orientation.z + orientation.w * orientation.w
        if (!length.isFinite() || length < 0.0001f) return false
        position = point
        rotation = orientation
        return true
    }
}
