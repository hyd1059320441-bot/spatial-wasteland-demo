package com.pico.demo.wasteland.game

import android.content.Context
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Scene-local immutable tracks evaluated from the delivered GLB's weighted vertices. */
class GeneratedRobotAsset(val name: String, private val clips: Map<String, Track>,
    val bindMuzzle: Vector3, val bindDirection: Vector3, val shieldNormal: Vector3?) {
    class Track(val duration: Float, val loop: Boolean, val frames: Array<FloatArray>) {
        fun sample(timeSeconds: Float): FloatArray {
            val time = if (loop) ((timeSeconds % duration) + duration) % duration
                else timeSeconds.coerceIn(0f, duration)
            val frame = time / duration * (frames.size - 1)
            val first = frame.toInt().coerceIn(0, frames.lastIndex)
            val second = (first + 1).coerceAtMost(frames.lastIndex)
            val weight = frame - first
            return FloatArray(frames[first].size) {
                frames[first][it] * (1f - weight) + frames[second][it] * weight
            }
        }
    }
    val path get() = if (name == "HeavyGunnerV9") "models/v12/ElitePurpleGold.glb" else "models/v8/$name.glb"
    val deathSpeed get() = (clips["Death"]?.duration ?: GameRules.CORPSE_SECONDS) /
        GameRules.CORPSE_SECONDS
    fun sample(clip: String, seconds: Float): FloatArray =
        clips[clip]?.sample(seconds) ?: floatArrayOf(bindMuzzle.x, bindMuzzle.y, bindMuzzle.z,
            bindDirection.x, bindDirection.y, bindDirection.z)

    companion object {
        val names = listOf("AssaultRobotV8", "SniperRobotV8", "HeavyGunnerV9",
            "ShieldRobotV9", "CombatDroneV8")
        fun name(kind: GameRules.Kind) = when (kind) {
            GameRules.Kind.SNIPER -> "SniperRobotV8"
            GameRules.Kind.ELITE -> "HeavyGunnerV9"
            GameRules.Kind.GUARD -> "ShieldRobotV9"
            GameRules.Kind.DRONE -> "CombatDroneV8"
            else -> "AssaultRobotV8"
        }
        suspend fun load(context: Context): Map<String, GeneratedRobotAsset> = withContext(Dispatchers.IO) {
            names.associateWith { name ->
                if (name == "CombatDroneV8") {
                    // Measured body-front rim; rotor animation never moves the rigid hull.
                    GeneratedRobotAsset(name, emptyMap(), Vector3(-0.00508846f, 0.2882253f, 0.4921603f),
                        Vector3.FORWARD, null)
                } else {
                    val json = context.assets.open("models/v8/$name.sockets.json")
                        .bufferedReader().use { JSONObject(it.readText()) }
                    check(json.getInt("schemaVersion") == 1 && json.getString("units") == "meters" &&
                        json.getString("sampleTime") == "seconds")
                    fun vector(value: org.json.JSONArray) =
                        Vector3(value.getDouble(0).toFloat(), value.getDouble(1).toFloat(),
                            value.getDouble(2).toFloat())
                    val tracks = json.getJSONObject("clips")
                    val clips = tracks.keys().asSequence().associateWith { clip ->
                        val item = tracks.getJSONObject(clip)
                        val samples = item.getJSONArray("samples")
                        Track(item.getDouble("duration").toFloat(), item.getBoolean("loop"),
                            Array(samples.length()) { i ->
                                val row = samples.getJSONArray(i)
                                FloatArray(row.length()) { row.getDouble(it).toFloat() }
                            })
                    }
                    GeneratedRobotAsset(name, clips, vector(json.getJSONArray("bindMuzzle")),
                        vector(json.getJSONArray("bindDirection")), json.optJSONObject("shield")?.let {
                            vector(it.getJSONArray("planeNormal"))
                        })
                }
            }
        }
    }
}
