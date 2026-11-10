package com.pico.demo.wasteland.game

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.*
import kotlin.math.*

/** Short muzzle petals and ground shockwaves; all resources live in the scene pool. */
class CombatVfx(private val kit: SceneKit) {
    private val ringMesh = kit.own(MeshResource.createTorus(1f, 0.94f))
    private data class Flash(val owner: Entity, val core: Entity, val petals: List<Entity>,
        var life: Float = 0f, var duration: Float = 0.1f, var scale: Float = 1f,
        var fresh: Boolean = false)
    private data class Wave(val owner: Entity, val rim: Entity,
        var life: Float = 0f, var radius: Float = 2.5f, var fresh: Boolean = false)
    private val flashes = List(10) { index ->
        val owner = Entity().apply {
            setName("ComicMuzzle$index"); kit.root.addChild(this); enabled = false
        }
        val core = kit.box(owner, "MuzzleCore$index", Vector3.ZERO, Vector3(0.01f), kit.white)
        val petals = List(4) { petal ->
            kit.box(owner, "MuzzlePetal${index}_$petal", Vector3.ZERO, Vector3(0.01f), kit.orange)
        }
        Flash(owner, core, petals)
    }
    private val waves = List(4) { index ->
        val owner = Entity().apply {
            setName("ComicShockwave$index"); kit.root.addChild(this); enabled = false
        }
        Wave(owner, kit.box(owner, "ShockwaveRim$index", Vector3.ZERO,
            Vector3(0.01f), kit.orange, mesh = ringMesh))
    }
    private var flashCursor = 0
    private var waveCursor = 0
    private var suspended = false

    fun muzzle(position: Vector3, rotation: Quat, heavy: Boolean = false, energy: Boolean = false) {
        val f = flashes[flashCursor++ % flashes.size]
        f.duration = if (heavy) 0.13f else 0.085f
        f.life = f.duration; f.scale = if (heavy) 1.3f else 1f; f.fresh = true
        f.owner.pose().setPosition(position).setQuaternion(rotation)
        f.owner.enabled = true
        f.petals.forEach {
            it.components[com.pico.spatial.core.ecs.ModelComponent::class.java]?.materials?.set(
                0, if (energy) kit.cyan else kit.orange)
        }
        animate(f)
    }

    fun shockwave(center: Vector3, player: Vector3, elite: Boolean = false) {
        val w = waves[waveCursor++ % waves.size]
        w.life = 0.48f; w.fresh = true
        w.radius = if ((center - player).length() < 2f) 1.3f else if (elite) 3f else 2.5f
        w.owner.pose().setPosition(Vector3(center.x, V3Layout.heightAt(center.x, center.z) + 0.05f,
            center.z))
        w.owner.enabled = true
        w.rim.components[com.pico.spatial.core.ecs.ModelComponent::class.java]?.materials?.set(
            0, if (elite) kit.cyan else kit.orange)
        animate(w)
    }

    private fun animate(f: Flash) {
        val t = (1f - f.life / f.duration).coerceIn(0f, 1f)
        val strength = (1f - t).pow(2) * f.scale
        // Even the shotgun burst stays within a few centimeters of the actual muzzle.
        f.core.pose().setPosition(Vector3(0f, 0f, -0.025f))
            .setScaleVector(Vector3(0.016f, 0.016f, 0.075f) * strength)
        f.petals.forEachIndexed { index, petal ->
            val angle = index * PI.toFloat() / 2f + t * 0.5f
            petal.pose().setPosition(Vector3(cos(angle) * 0.018f, sin(angle) * 0.018f, -0.07f))
                .setEulerAngles(EulerAngles(sin(angle) * 16f, -cos(angle) * 16f, 0f))
                .setScaleVector(Vector3(0.011f, 0.011f, 0.11f) * strength)
        }
    }
    private fun animate(w: Wave) {
        val t = (1f - w.life / 0.48f).coerceIn(0f, 1f)
        val radius = 0.25f + (w.radius - 0.25f) * (1f - (1f - t).pow(3))
        w.rim.pose().setScaleVector(Vector3(radius, 0.026f * (1f - t).pow(2), radius))
    }
    fun update(dt: Float) {
        if (dt <= 0f) {
            if (!suspended) reset()
            suspended = true
            return
        }
        suspended = false
        flashes.forEach {
            if (it.life <= 0f) return@forEach
            if (it.fresh) { it.fresh = false; return@forEach }
            it.life = max(0f, it.life - dt)
            if (it.life == 0f) it.owner.enabled = false else animate(it)
        }
        waves.forEach {
            if (it.life <= 0f) return@forEach
            if (it.fresh) { it.fresh = false; return@forEach }
            it.life = max(0f, it.life - dt)
            if (it.life == 0f) it.owner.enabled = false else animate(it)
        }
    }
    fun reset() {
        flashes.forEach { it.life = 0f; it.owner.enabled = false }
        waves.forEach { it.life = 0f; it.owner.enabled = false }
    }
    fun activeCount() = flashes.count { it.life > 0f } + waves.count { it.life > 0f }
}
