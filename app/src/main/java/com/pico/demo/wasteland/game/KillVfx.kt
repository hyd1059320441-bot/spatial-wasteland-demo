package com.pico.demo.wasteland.game

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlin.math.*

/** Tiny metallic debris behind the fallen target. The aim line stays clear even at melee range. */
class KillVfx(private val kit: SceneKit) {
    private data class Burst(val root: Entity, val shards: List<Entity>, val seams: List<Entity>,
        var life: Float = 0f, var elite: Boolean = false, var away: Vector3 = Vector3.BACK,
        var fresh: Boolean = false)
    private val bursts = List(8) { slot ->
        val root = Entity().apply {
            setName("DefeatDebris$slot"); kit.root.addChild(this); enabled = false
        }
        val seams = mutableListOf<Entity>()
        val shards = List(6) {
            val plate = kit.box(root, "ArmorChip$it", Vector3.ZERO,
                Vector3(0.025f, 0.012f, 0.042f), kit.deck)
            seams.add(kit.box(plate, "LiveCircuit$it", Vector3(0f, 0.52f, 0f),
                Vector3(0.35f, 0.12f, 0.65f), kit.cyan))
            plate
        }
        Burst(root, shards, seams)
    }
    private var cursor = 0
    fun burst(center: Vector3, elite: Boolean, headshot: Boolean, player: Vector3) {
        val b = bursts[cursor++ % bursts.size]
        b.life = DefeatGeometry.DURATION; b.elite = elite; b.away = DefeatGeometry.away(center, player)
        b.fresh = true; b.root.enabled = true
        b.root.pose().setPosition(center)
        b.seams.forEach {
            it.components[com.pico.spatial.core.ecs.ModelComponent::class.java]?.materials?.set(0,
                if (elite) kit.orange else if (headshot) kit.cyan else kit.orange)
        }
        animate(b, 0f)
    }
    private fun animate(b: Burst, elapsed: Float) {
        val t = (elapsed / DefeatGeometry.DURATION).coerceIn(0f, 1f)
        b.shards.forEachIndexed { i, shard ->
            shard.pose().setPosition(DefeatGeometry.shardOffset(i, elapsed, b.away, b.elite))
                .setEulerAngles(EulerAngles(elapsed * (220f + i * 30f), elapsed * 290f, elapsed * 180f))
                .setScaleVector(Vector3(0.025f, 0.012f, 0.042f) * (1f - t * t))
            b.seams[i].enabled = t < 0.35f
        }
    }
    fun update(dt: Float) {
        if (dt <= 0f) return
        bursts.forEach {
            if (it.life <= 0f) return@forEach
            if (it.fresh) { it.fresh = false; return@forEach }
            it.life = (it.life - dt).coerceAtLeast(0f)
            animate(it, DefeatGeometry.DURATION - it.life)
            it.root.enabled = it.life > 0f
        }
    }
    fun reset() { bursts.forEach { it.life = 0f; it.root.enabled = false } }
    fun activeCount() = bursts.count { it.life > 0f }
}
