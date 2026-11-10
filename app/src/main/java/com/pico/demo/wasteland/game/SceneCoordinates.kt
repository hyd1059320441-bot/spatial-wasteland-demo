package com.pico.demo.wasteland.game

import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3

/** Use the current ECS pose, even before native hierarchy matrices commit at frame end. */
object SceneCoordinates {
    fun localPoint(parentPoint: Vector3, position: Vector3, rotation: Quat): Vector3 =
        inverse(rotation).rotateVector(parentPoint - position)

    fun placement(parentEye: Vector3, destination: Vector3, rotation: Quat): Vector3 =
        parentEye - rotation.rotateVector(destination)

    fun inverse(rotation: Quat) = Quat(-rotation.x, -rotation.y, -rotation.z, rotation.w)
}
