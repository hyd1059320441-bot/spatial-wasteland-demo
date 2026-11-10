package com.pico.demo.wasteland.game

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.*
import kotlin.math.*

/** Finite pools of narrow reflected streaks and low shrapnel, independent of damage resolution. */
class ImpactVfx(private val kit: SceneKit) {
    private data class Piece(val entity: Entity, var velocity: Vector3 = Vector3.ZERO,
        var position: Vector3 = Vector3.ZERO)
    private data class Burst(val owner: Entity, val pieces: List<Piece>, var life: Float = 0f,
        var duration: Float = 0.35f, var floor: Float = 0f, var explosion: Boolean = false,
        var fresh: Boolean = false, var robot: Boolean = false)
    private val impacts = List(8) { make("ImpactStreak$it", 5, kit.white) }
    private val headshots = List(4) { make("HeadshotShards$it", 9, kit.cyan) }
    private val explosions = List(4) { make("ExplosionShrapnel$it", 8, kit.paintedOrange) }
    private val all = impacts + headshots + explosions
    private var impactCursor = 0
    private var explosionCursor = 0
    private var headshotCursor = 0
    private var suspended = false
    private fun make(name: String, count: Int, material: com.pico.spatial.core.ecs.resource.Material): Burst {
        val root = Entity().apply { setName(name); kit.root.addChild(this); enabled = false }
        return Burst(root, List(count) {
            Piece(kit.box(root, "${name}Piece$it", Vector3.ZERO, Vector3(0.01f), material))
        })
    }
    fun impact(point: Vector3, normal: Vector3, incoming: Vector3, player: Vector3) {
        suspended = false
        // Keep nearby flashes small and cap their travel below half a meter.
        val near = (point - player).length() < 2f
        val b = impacts[impactCursor++ % impacts.size]
        b.duration = if (near) 0.16f else 0.28f
        b.life = b.duration; b.explosion = normal.y > 0.65f; b.fresh = true; b.robot = false
        b.floor = V3Layout.heightAt(point.x, point.z) + 0.035f
        b.owner.pose().setPosition(point); b.owner.enabled = true
        b.pieces.forEachIndexed { index, piece ->
            piece.entity.components[com.pico.spatial.core.ecs.ModelComponent::class.java]?.materials?.set(
                0, if (b.explosion) kit.ground else kit.white)
            piece.position = normal * 0.03f
            piece.velocity = VisualGeometry.impactVelocity(index, normal, incoming) * if (near) 0.35f else 0.7f
        }
        animate(b, 0f)
    }
    fun robotImpact(point: Vector3, incoming: Vector3, player: Vector3, critical: Boolean) {
        suspended = false
        val b = impacts[impactCursor++ % impacts.size]
        val normal = incoming * -1f
        b.duration = if ((point - player).length() < 2f) 0.2f else 0.34f
        b.life = b.duration; b.explosion = false; b.robot = true; b.fresh = true
        b.floor = V3Layout.heightAt(point.x, point.z) + 0.035f
        b.owner.pose().setPosition(point); b.owner.enabled = true
        b.pieces.forEachIndexed { i, piece ->
            piece.entity.components[com.pico.spatial.core.ecs.ModelComponent::class.java]?.materials?.set(
                0, if (critical) kit.cyan else kit.orange)
            piece.position = normal * 0.025f
            piece.velocity = VisualGeometry.impactVelocity(i, normal, incoming) * 0.9f
        }
        animate(b, 0f)
    }
    fun headshot(point: Vector3, incoming: Vector3, player: Vector3) {
        suspended = false
        val b = headshots[headshotCursor++ % headshots.size]
        val normal = incoming * -1f
        val reference = if (abs(normal.y) < 0.9f) Vector3.UP else Vector3.RIGHT
        fun cross(a: Vector3, v: Vector3) = Vector3(
            a.y * v.z - a.z * v.y, a.z * v.x - a.x * v.z, a.x * v.y - a.y * v.x)
        val right = cross(normal, reference).let { it * (1f / it.length().coerceAtLeast(0.001f)) }
        val up = cross(right, normal)
        val scale = if ((point - player).length() < 2f) 0.5f else 1f
        b.duration = 0.32f; b.life = b.duration
        b.explosion = false; b.robot = true; b.fresh = true
        b.floor = V3Layout.heightAt(point.x, point.z) + 0.035f
        b.owner.pose().setPosition(point); b.owner.enabled = true
        b.pieces.forEachIndexed { i, piece ->
            val angle = i * 2.39996f
            val side = right * cos(angle) + up * sin(angle)
            piece.position = normal * 0.04f + side * (0.035f * scale)
            piece.velocity = (normal * 0.7f + side * (1.7f + i % 3 * 0.25f)) * scale
            piece.entity.components[com.pico.spatial.core.ecs.ModelComponent::class.java]?.materials?.set(
                0, if (i % 3 == 0) kit.orange else kit.cyan)
        }
        animate(b, 0f)
    }
    fun activeHeadshots() = headshots.count { it.life > 0f }
    fun explosion(center: Vector3) {
        suspended = false
        val b = explosions[explosionCursor++ % explosions.size]
        b.duration = 0.55f; b.life = b.duration; b.explosion = true; b.fresh = true; b.robot = false
        b.floor = V3Layout.heightAt(center.x, center.z) + 0.05f
        b.owner.pose().setPosition(Vector3(center.x, b.floor, center.z)); b.owner.enabled = true
        b.pieces.forEachIndexed { i, piece ->
            val angle = i * 2.39996f
            piece.position = Vector3(0f, 0.1f, 0f)
            piece.velocity = Vector3(cos(angle) * (2f + i * 0.12f), 1.2f + i % 3 * 0.35f,
                sin(angle) * (2f + i * 0.12f))
        }
        animate(b, 0f)
    }
    private fun animate(b: Burst, elapsed: Float) {
        val t = (elapsed / b.duration).coerceIn(0f, 1f)
        val baseY = b.owner.pose().position.y
        b.pieces.forEachIndexed { index, p ->
            val position = p.position + p.velocity * elapsed + Vector3(0f, -3.5f * elapsed * elapsed, 0f)
            p.entity.pose().setPosition(Vector3(position.x, max(position.y, b.floor - baseY), position.z))
            if (b.explosion) {
                p.entity.pose().setScaleVector(Vector3(0.065f, 0.018f, 0.09f) * (1f - t * t))
                    .setEulerAngles(EulerAngles(elapsed * 270f, elapsed * 210f, index * 33f))
            } else {
                val velocity = p.velocity + Vector3(0f, -7f * elapsed, 0f)
                val length = velocity.length().coerceAtLeast(0.01f)
                val width = if (b.robot) 0.016f else 0.006f
                val streak = if (b.robot) min(0.22f, length * 0.065f) else min(0.16f, length * 0.035f)
                p.entity.pose().setScaleVector(Vector3(width * (1f - t * 0.7f),
                    width * (1f - t * 0.7f), streak * (1f - t)))
                    .setEulerAngles(EulerAngles(asin((velocity.y / length).coerceIn(-1f, 1f)) * 57.29578f,
                        atan2(-velocity.x, -velocity.z) * 57.29578f, 0f))
            }
        }
    }
    fun update(dt: Float) {
        if (dt <= 0f) {
            if (!suspended) reset()
            suspended = true
            return
        }
        suspended = false
        all.forEach {
            if (it.life <= 0f) return@forEach
            if (it.fresh) { it.fresh = false; return@forEach }
            it.life = max(0f, it.life - dt)
            if (it.life == 0f) it.owner.enabled = false else animate(it, it.duration - it.life)
        }
    }
    fun reset() {
        all.filter { it.life > 0f }.forEach { it.life = 0f; it.owner.enabled = false }
    }
    fun activeCount() = all.count { it.life > 0f }
}
