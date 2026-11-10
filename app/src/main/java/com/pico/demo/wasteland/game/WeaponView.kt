package com.pico.demo.wasteland.game

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.*
import android.util.Log
import kotlin.math.sin

class WeaponView(private val kit: SceneKit) {
    val entity = Entity().apply { setName("PlayerWeapon"); kit.root.addChild(this); enabled = false }
    private data class Gun(val root: Entity, val model: Entity, val base: Vector3,
        val muzzle: Vector3)
    private val guns = mutableMapOf<GameRules.Weapon, Gun>()
    var kick = 0f; private set
    private var mechanical = 0f
    private var previousWeapon = GameRules.Weapon.RIFLE
    private var swap = 0f
    suspend fun build() {
        for (weapon in GameRules.Weapon.entries) {
            val parent = Entity().apply { setName(weapon.modelFile); entity.addChild(this) }
            val e = kit.outlinedModel(weapon.modelPath, parent, weapon.modelFile)
            // Six-axis source reviews: arc muzzle +X; other four weapons muzzle -X, Y up.
            e.pose().setEulerAngles(EulerAngles(0f,
                if (weapon == GameRules.Weapon.ARC) 90f else -90f, 0f))
            val b = e.getVisualBounds(parent)
            check(!b.isEmpty() && b.size.z > 0f)
            val scale = weapon.length / b.size.z
            e.pose().setScaleVector(Vector3(scale, scale, scale))
            val fitted = e.getVisualBounds(parent)
            val base = Vector3(-fitted.center.x, -0.035f - fitted.center.y, -0.3f - fitted.center.z)
            e.pose().setPosition(base)
            val final = e.getVisualBounds(parent)
            // Measured front-most 3.5% vertex slabs in the mobile source: Y/Z center
            // fractions, not the tallest scope or the whole weapon's visual center.
            val yz = when (weapon) {
                GameRules.Weapon.ARC -> 0.4663621f to 0.5045665f
                GameRules.Weapon.RAIL -> 0.59445f to 0.5301648f
                GameRules.Weapon.PLASMA -> 0.7173696f to 0.5003886f
                else -> null
            }
            val muzzle = if (yz == null) Vector3(0f, 0.025f, final.min.z - 0.018f)
                else Vector3(final.min.x + final.size.x * (if (weapon == GameRules.Weapon.ARC)
                    yz.second else 1f - yz.second),
                    final.min.y + final.size.y * yz.first, final.min.z - 0.018f)
            guns[weapon] = Gun(parent, e, base, muzzle)
            parent.enabled = weapon == GameRules.Weapon.RIFLE
            Log.i("Wasteland", "V12_GUN_FIT weapon=$weapon min=${final.min} max=${final.max} length=${final.size.z} muzzle=$muzzle uniformScale=$scale")
        }
    }
    fun muzzle(weapon: GameRules.Weapon, position: Vector3, rotation: Quat) =
        position + rotation.rotateVector(guns.getValue(weapon).muzzle)
    fun fired(weapon: GameRules.Weapon) {
        kick = weapon.kick
        mechanical = if (weapon == GameRules.Weapon.RIFLE) 0.12f else 0.55f
    }
    fun update(rules: GameRules, position: Vector3, rotation: Quat, dt: Float, tracked: Boolean) {
        kick = (kick - dt * 0.32f).coerceAtLeast(0f)
        mechanical = (mechanical - dt).coerceAtLeast(0f)
        if (previousWeapon != rules.weapon) {
            swap = 0.3f; previousWeapon = rules.weapon
            guns.forEach { (weapon, gun) -> gun.root.enabled = rules.weapon == weapon }
        }
        swap = (swap - dt).coerceAtLeast(0f)
        val visible = tracked && !rules.over && !rules.paused && !rules.rewardMenuOpen
        if (entity.enabled != visible) entity.enabled = visible
        if (!visible) return
        val progress = if (rules.reloadRemaining > 0f) 1f - rules.reloadRemaining / rules.reloadSeconds else 0f
        val reloadTilt = sin(progress * 3.14159f)
        val offset = Vector3(0f, -reloadTilt * 0.18f - swap * 0.3f, kick)
        entity.pose().setPosition(position + rotation.rotateVector(offset)).setQuaternion(rotation)
        val tilt = EulerAngles(reloadTilt * 28f + kick * 75f, 0f, -reloadTilt * 30f)
        val gun = guns.getValue(rules.weapon)
        gun.root.pose().setEulerAngles(tilt)
        gun.model.pose().setPosition(gun.base + if (rules.weapon == GameRules.Weapon.SHOTGUN)
            Vector3(0f, -reloadTilt * 0.025f, sin(mechanical / 0.55f * 3.14159f) * 0.11f)
            else Vector3(0f, 0f, mechanical * 0.06f))
    }
    fun reset() { kick = 0f; mechanical = 0f; swap = 0f }
}
