package com.pico.demo.wasteland.game

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.*
import kotlin.math.sin

/** Five persistent elite drops, allocated once with the scene and never timed out. */
class LootView(private val kit: SceneKit) {
    private data class Item(val root: Entity, val gun: Entity, val base: Vector3)
    private val items = mutableListOf<Item>()
    private var clock = 0f
    suspend fun build() {
        repeat(GameRules.ELITE_EMISSIONS.size) { index ->
            val weapon = listOf(GameRules.Weapon.ARC, GameRules.Weapon.RAIL,
                GameRules.Weapon.PLASMA)[index % 3]
            val root = Entity().apply { setName("EliteDrop$index"); kit.root.addChild(this) }
            val gun = kit.outlinedModel(weapon.modelPath, root, "Drop${weapon.modelFile}")
            val raw = gun.getVisualBounds(root)
            val scale = 0.65f / raw.size.x
            gun.pose().setScaleVector(Vector3(scale))
            val bounds = gun.getVisualBounds(root)
            val base = Vector3.ZERO - bounds.center + Vector3(0f, 0.55f, 0f)
            gun.pose().setPosition(base)
            val color = when (weapon) {
                GameRules.Weapon.ARC -> kit.cyan
                GameRules.Weapon.RAIL -> kit.gold
                else -> kit.magenta
            }
            kit.box(root, "LootBeacon", Vector3(0f, 0.28f, 0f),
                Vector3(0.025f, 0.55f, 0.025f), color)
            for (i in 0..3) {
                val rotation = EulerAngles(0f, i * 90f, 0f).toQuat()
                kit.box(root, "LootMarker$i", rotation.rotateVector(Vector3(0f, 0.025f, 0.35f)),
                    Vector3(0.42f, 0.025f, 0.06f), color).pose().setQuaternion(rotation)
            }
            root.enabled = false
            items.add(Item(root, gun, base))
        }
    }
    fun update(rules: GameRules, dt: Float) {
        clock += dt
        items.forEachIndexed { index, item ->
            val drop = rules.drops.firstOrNull { it.id == index && !it.collected }
            item.root.enabled = drop != null
            if (drop != null) {
                item.root.pose().setPosition(Vector3(drop.x, drop.floor, drop.z))
                    .setEulerAngles(EulerAngles(0f, clock * 28f, 0f))
                item.gun.pose().setPosition(item.base + Vector3(0f, sin(clock * 2f) * 0.045f, 0f))
            }
        }
    }
    fun reset() { clock = 0f; items.forEach { it.root.enabled = false } }
}
