package com.pico.demo.wasteland.game

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.math.*
import kotlin.math.*

/** Bounded comic stars, coil flashes and rail beams. All resources are scene-owned. */
class ArsenalFx(private val kit: SceneKit) {
    private data class Star(val parts: List<Entity>, var life: Float = 0f,
        var origin: Vector3 = Vector3.ZERO, var rotation: Quat = Quat(), var radius: Float = 0.2f)
    private val stars = List(24) { index ->
        Star(List(6) { i -> kit.box(kit.root, "ComicStar${index}_$i", Vector3.ZERO,
            Vector3(0.015f), kit.cyan).apply { enabled = false } })
    }
    private data class Beam(val parts: List<Entity>, var life: Float = 0f)
    private val beams = List(6) { index ->
        Beam(List(2) { i -> kit.box(kit.root, "RailBeam${index}_$i", Vector3.ZERO,
            Vector3(0.015f), if (i == 0) kit.violet else kit.gold).apply { enabled = false } })
    }
    private var starIndex = 0
    private var beamIndex = 0
    private fun color(weapon: GameRules.Weapon) = when (weapon) {
        GameRules.Weapon.ARC -> kit.cyan
        GameRules.Weapon.RAIL -> kit.violet
        GameRules.Weapon.PLASMA -> kit.magenta
        else -> kit.orange
    }
    fun star(point: Vector3, rotation: Quat, weapon: GameRules.Weapon, radius: Float,
        player: Vector3) {
        val star = stars[starIndex++ % stars.size]
        star.origin = point; star.rotation = rotation; star.life = 0.22f
        val d = (point - player).length()
        star.radius = radius * if (d < 1.5f) 0.45f else 1f
        star.parts.forEachIndexed { i, part ->
            part.components[ModelComponent::class.java]?.materials?.set(0,
                if (i % 2 == 0) color(weapon) else when (weapon) {
                    GameRules.Weapon.PLASMA -> kit.lime
                    GameRules.Weapon.RAIL -> kit.gold
                    else -> kit.violet
                })
            part.enabled = true
        }
    }
    fun rail(start: Vector3, end: Vector3) {
        val beam = beams[beamIndex++ % beams.size]
        beam.life = 0.18f
        beam.parts.forEachIndexed { i, part ->
            val d = end - start
            val length = d.length().coerceAtLeast(0.001f)
            part.pose().setPosition((start + end) * 0.5f)
                .setEulerAngles(EulerAngles(asin((d.y / length).coerceIn(-1f, 1f)) * 57.29578f,
                    atan2(-d.x, -d.z) * 57.29578f, 0f))
                .setScaleVector(Vector3(if (i == 0) 0.045f else 0.009f,
                    if (i == 0) 0.045f else 0.009f, length))
            part.enabled = true
        }
    }
    fun update(dt: Float) {
        if (dt <= 0f) { reset(); return }
        stars.forEach { star ->
            if (star.life <= 0f) return@forEach
            star.life -= dt
            val age = 1f - star.life / 0.22f
            star.parts.forEachIndexed { i, part ->
                part.enabled = star.life > 0f
                if (part.enabled) {
                    val angle = i * PI.toFloat() / 3f
                    val offset = Vector3(cos(angle), sin(angle), 0f)
                    part.pose().setPosition(star.origin +
                        star.rotation.rotateVector(offset * star.radius * (0.3f + age)))
                        .setQuaternion(star.rotation * EulerAngles(0f, 0f,
                            angle * 57.29578f).toQuat())
                        .setScaleVector(Vector3(star.radius * 0.5f,
                            0.017f * (1f - age).coerceAtLeast(0.1f), 0.014f))
                }
            }
        }
        beams.forEach { beam ->
            if (beam.life <= 0f) return@forEach
            beam.life -= dt
            beam.parts.forEach { it.enabled = beam.life > 0f }
        }
    }
    fun reset() {
        stars.forEach { it.life = 0f; it.parts.forEach { part -> part.enabled = false } }
        beams.forEach { it.life = 0f; it.parts.forEach { part -> part.enabled = false } }
    }
}
