package com.pico.demo.wasteland.game

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Deterministic sustained battle. The scene advances it only with focus and usable tracking. */
class GameRules(private val navigation: NavigationWorker? = null,
    val talents: RunTalents = RunTalents()) {
    var spawnClear: (Float, Float) -> Boolean = { _, _ -> true }
    enum class Weapon(val label: String, val magazine: Int, val interval: Float,
        val reloadSeconds: Float, val damage: Int, val pellets: Int) {
        RIFLE("自动步枪", 24, 0.12f, 1.65f, 24, 1),
        SHOTGUN("霰弹枪", 6, 0.78f, 2.1f, 15, 8),
        ARC("电弧连射枪", 36, 0.075f, 1.4f, 18, 1),
        RAIL("轨道狙击枪", 5, 1.15f, 2.4f, 180, 1),
        PLASMA("等离子炮", 4, 0.95f, 2.65f, 120, 1);

        val automatic get() = this == RIFLE || this == ARC
        val range get() = when (this) { SHOTGUN -> 70f; ARC -> 65f; RAIL -> 160f; PLASMA -> 48f; else -> 120f }
        val projectileSpeed get() = when (this) { ARC -> 34f; PLASMA -> 14f; else -> 0f }
        val gravity get() = if (this == PLASMA) 4.5f else 0f
        val spread get() = when (this) { SHOTGUN -> 0.065f; ARC -> 0.018f; RAIL -> 0f; else -> 0.009f }
        val sound get() = when (this) { SHOTGUN -> "shotgun"; ARC -> "arc"; RAIL -> "rail"; PLASMA -> "plasma"; else -> "shot" }
        val kick get() = when (this) { SHOTGUN -> 0.085f; ARC -> 0.018f; RAIL -> 0.095f; PLASMA -> 0.11f; else -> 0.03f }
        val modelFile get() = when (this) { ARC -> "arc_smg"; RAIL -> "rail_sniper"; PLASMA -> "plasma_cannon"; SHOTGUN -> "HeroShotgunComicV3"; else -> "HeroRifleComicV3" }
        val modelPath get() = "models/${if (ordinal >= ARC.ordinal) "v12" else "v3"}/$modelFile.usdz"
        val length get() = when (this) { ARC -> 0.65f; RAIL -> 0.9f; PLASMA -> 0.75f; SHOTGUN -> 0.68f; else -> 0.62f }
    }
    enum class Kind(val hp: Int, val damage: Int, val speed: Float, val cooldown: Float) {
        CHARGER(120, 9, 1.7f, 1.9f),
        SHOOTER(160, 8, 1.1f, 2f),
        FLANKER(100, 7, 2.35f, 1.65f),
        GUARD(220, 12, 1f, 2.4f),
        SNIPER(110, 17, 0.85f, 3.4f),
        DRONE(90, 6, 1.8f, 1.8f),
        ELITE(960, 16, 0.85f, 1.35f),
        ;
        val melee get() = this == CHARGER || this == FLANKER || this == GUARD
        val hoverHeight get() = if (this == DRONE) 2.6f else 0f
        val warningSeconds get() = when {
            this == SNIPER -> 1.6f
            melee -> 0.5f
            this == DRONE -> 0.65f
            else -> 0.8f
        }
        val projectileSpeed get() = when (this) { SNIPER -> 18f; DRONE -> 7f; else -> 5f }
    }
    enum class Motion { IDLE, RUN, AIM, ATTACK, HIT, DEATH }
    data class Enemy(val slot: Int, var kind: Kind = Kind.CHARGER,
        var health: Int = 0, var x: Float = 0f, var z: Float = -9f,
        var attackClock: Float = 2f, var telegraph: Float = 0f,
        var deathClock: Float = 0f, var hitClock: Float = 0f,
        var stunClock: Float = 0f, var motion: Motion = Motion.IDLE,
        var route: List<Pair<Float, Float>> = emptyList(), var routeIndex: Int = 0,
        var routeClock: Float = 0f, var alerted: Boolean = false,
        var lastKnownX: Float = 0f, var lastKnownZ: Float = -2f,
        var lostClock: Float = 0f, var stuckClock: Float = 0f,
        var recoveryClock: Float = 0f, var attackSerial: Int = 0,
        var aimX: Float = 0f, var aimZ: Float = -2f,
        var aimY: Float = EyeHeight.DEFAULT - 0.25f, var shield: Int = 0,
        var coreHealth: Int = 180, var generation: Int = 0,
        var spawnX: Float = 0f, var spawnZ: Float = -9f, var emission: Int = 0) {
        val alive get() = health > 0
        val phase get() = if (kind != Kind.ELITE) 1 else when {
            health > kind.hp * 2 / 3 -> 1
            health > kind.hp / 3 -> 2
            else -> 3
        }
        val coreOpen get() = alive && kind == Kind.ELITE && telegraph > 0f && coreHealth > 0
    }
    data class Attack(val slot: Int, val x: Float, val z: Float, val damage: Int,
        val melee: Boolean, val aimX: Float, val aimZ: Float,
        val aimY: Float = EyeHeight.DEFAULT - 0.25f, val projectiles: Int = 1)
    data class Defeat(val slot: Int, val kind: Kind, val x: Float, val z: Float,
        val headshot: Boolean, val points: Int, val combo: Int)
    data class Barrel(val x: Float, val z: Float, var health: Int = 48) {
        val intact get() = health > 0
    }
    data class InteractionTarget(val label: String, val x: Float, val z: Float)
    data class Drop(val id: Int, val weapon: Weapon, val x: Float, val z: Float,
        val floor: Float, var collected: Boolean = false)
    val enemies = List(MAX_ENEMIES) { Enemy(it) }
    val drops = mutableListOf<Drop>()
    private val inventory = linkedSetOf(Weapon.RIFLE, Weapon.SHOTGUN)
    val ownedWeapons get() = inventory.toList()
    fun owns(weapon: Weapon) = weapon in inventory
    private var eliteSpawned = 0
    private var eliteDefeated = 0
    val barrels = V3Layout.barrelPositions.map { Barrel(it.first, it.second) }
    val attacks = mutableListOf<Attack>()
    private val defeats = mutableListOf<Defeat>()
    var killFeedbackRemaining = 0f; private set
    var lastDefeat: Defeat? = null; private set
    val killFeedback get() = lastDefeat?.takeIf { killFeedbackRemaining > 0f }?.let {
        val label = if (it.kind == Kind.ELITE) "精英击破" else if (it.headshot) "爆头击杀" else "击杀"
        "$label +${it.points}" + if (it.combo > 1) " · ${it.combo} 连杀" else ""
    } ?: ""
    fun drainDefeats(): List<Defeat> = defeats.toList().also { defeats.clear() }
    private fun resetDefeats() { defeats.clear(); lastDefeat = null; killFeedbackRemaining = 0f }
    private val magazines = IntArray(Weapon.entries.size) { Weapon.entries[it].magazine }
    var weapon = Weapon.RIFLE; private set
    val ammo get() = magazines[weapon.ordinal]
    val maxHealth get() = talents.maxHealth
    val reloadSeconds get() = weapon.reloadSeconds * talents.reloadScale
    val bulletDamage get() = talents.bulletDamage(weapon.damage)
    var health = 100; private set
    var kills = 0; private set
    var spawned = 0; private set
    var score = 0; private set
    var paused = false; private set
    var reloadRemaining = 0f; private set
    var startRemaining = 2.5f; private set
    var upgraded = false; private set
    var message = "多点增援 · 击破 100 个敌人"; private set
    var rewardMenuOpen = false; private set
    private var shotCooldown = 0f
    private var clock = 0f
    private var attackGap = 0f
    private var windupGap = 0f
    private var attackCursor = 0
    private var refillClock = 0f
    private var batchIndex = 0
    val reinforcementRemaining get() = if (spawned >= TOTAL_ENEMIES) 0f else refillClock
    private var spawnCursor = 0
    private var rewardCredits = 0
    private val collectedSupplies = mutableSetOf<String>()
    var pathRequests = 0; private set
    val over get() = health <= 0
    val complete get() = kills >= TOTAL_ENEMIES
    val gateOpen get() = true
    val aliveCount get() = enemies.count { it.alive }
    val blocked get() = paused || over || complete || rewardMenuOpen
    val canRestart get() = over || complete
    val objective get() = when {
        complete -> "100 / 100 · 敌人全部击破 · 挑战完成"
        startRemaining > 0f -> "准备迎击 · 多点增援 · 目标 100 个"
        spawned >= TOTAL_ENEMIES -> "击破 $kills / $TOTAL_ENEMIES · 清理剩余 $aliveCount 个"
        else -> "击破 $kills / $TOTAL_ENEMIES · 场上 $aliveCount · ${kotlin.math.ceil(refillClock).toInt()}秒后增援"
    }

    fun restart() {
        talents.reset()
        collectedSupplies.clear()
        drops.clear(); inventory.clear(); inventory.addAll(listOf(Weapon.RIFLE, Weapon.SHOTGUN))
        eliteSpawned = 0; eliteDefeated = 0; batchIndex = 0
        health = 100; kills = 0; score = 0; spawned = 0
        weapon = Weapon.RIFLE
        Weapon.entries.forEach { magazines[it.ordinal] = it.magazine }
        paused = false; reloadRemaining = 0f; shotCooldown = 0f; startRemaining = 2.5f
        clock = 0f; upgraded = false
        refillClock = 0f; spawnCursor = 0; rewardCredits = 0; rewardMenuOpen = false
        message = "多点增援 · 击破 100 个敌人"
        attacks.clear()
        resetDefeats()
        barrels.forEach { it.health = 48 }
        clearEnemies()
    }
    fun retryCheckpoint(): Pair<Float, Float> {
        // Retry restores the player without creating extra enemies or duplicating progress.
        if (rewardMenuOpen) rewardMenuOpen = false
        health = maxHealth; paused = false; reloadRemaining = 0f; shotCooldown = 0f
        inventory.forEach { magazines[it.ordinal] = it.magazine }
        resetDefeats()
        attacks.clear()
        if (complete) restart()
        message = "继续挑战 · 击破 $kills / $TOTAL_ENEMIES"
        return 0f to -2f
    }
    fun togglePause() { paused = !paused }
    fun openRewards(): Boolean {
        if (paused || over || complete || !talents.pending) return false
        rewardMenuOpen = true
        return true
    }
    fun closeRewards() { rewardMenuOpen = false }
    fun selectReward(index: Int): Boolean {
        if (!rewardMenuOpen || paused || over) return false
        return talents.select(index)
    }
    fun cycleReward(): Boolean {
        if (!rewardMenuOpen || paused || over) return false
        return talents.cycle()
    }
    fun switchWeapon(): Boolean {
        if (blocked) return false
        if (reloadRemaining > 0f) return false
        val owned = ownedWeapons
        weapon = owned[(owned.indexOf(weapon) + 1) % owned.size]
        shotCooldown = maxOf(shotCooldown, 0.25f)
        message = weapon.label
        return true
    }
    fun nearestDrop(x: Float, z: Float, floor: Float,
        clear: (Drop) -> Boolean = { true }): Drop? {
        if (!x.isFinite() || !z.isFinite() || !floor.isFinite()) return null
        return drops.filter { !it.collected && absFloor(it.floor - floor) <= 0.5f &&
            hypot(it.x - x, it.z - z) <= PICKUP_RADIUS && clear(it) }
            .minByOrNull { hypot(it.x - x, it.z - z) }
    }
    private fun absFloor(value: Float) = kotlin.math.abs(value)
    fun collectDrop(id: Int, x: Float, z: Float, floor: Float,
        clear: (Drop) -> Boolean = { true }): Boolean {
        if (blocked) return false
        val drop = drops.firstOrNull { it.id == id && !it.collected } ?: return false
        if (!x.isFinite() || !z.isFinite() || !floor.isFinite() ||
            !drop.x.isFinite() || !drop.z.isFinite() || !drop.floor.isFinite() ||
            absFloor(drop.floor - floor) > 0.5f ||
            hypot(drop.x - x, drop.z - z) > PICKUP_RADIUS || !clear(drop)) return false
        drop.collected = true
        val added = inventory.add(drop.weapon)
        magazines[drop.weapon.ordinal] = drop.weapon.magazine
        // Cancel only when the pickup fills the currently reloading weapon.
        if (weapon == drop.weapon) reloadRemaining = 0f
        message = if (added) "获得 ${drop.weapon.label} · I / Grip 切换"
            else "${drop.weapon.label} · 能量弹匣补满"
        return true
    }
    fun reload(): Boolean {
        if (blocked || ammo == weapon.magazine || reloadRemaining > 0f) return false
        reloadRemaining = reloadSeconds
        return true
    }
    fun shoot(): Boolean {
        if (blocked || reloadRemaining > 0f || shotCooldown > 0f) return false
        if (ammo == 0) { reload(); return false }
        magazines[weapon.ordinal]--
        shotCooldown = weapon.interval
        return true
    }
    /** Damage is applied per pellet; armor resists body hits, and corpse hits never score. */
    fun hit(slot: Int, headshot: Boolean, damage: Int = bulletDamage): Boolean {
        if (blocked || damage <= 0) return false
        val enemy = enemies.getOrNull(slot)?.takeIf { it.alive } ?: return false
        val actual = if (headshot) damage * talents.headshotMultiplier
            else if (enemy.kind == Kind.ELITE && enemy.coreHealth > 0) maxOf(1, damage / 2) else damage
        enemy.health = (enemy.health - actual).coerceAtLeast(0)
        enemy.hitClock = 0.14f
        enemy.alerted = true
        if (enemy.alive) {
            // Ordinary hits interrupt a windup; elites resist repeated light body stagger.
            val stagger = if (upgraded) 0.35f else if (headshot) 0.28f
                else if (enemy.kind != Kind.ELITE) 0.16f else 0.06f
            enemy.stunClock = maxOf(enemy.stunClock, stagger)
            enemy.telegraph = 0f
            enemy.attackClock = maxOf(enemy.attackClock, 0.35f)
            enemy.motion = Motion.HIT
        }
        if (enemy.alive) return false
        enemy.deathClock = CORPSE_SECONDS; enemy.telegraph = 0f; enemy.motion = Motion.DEATH
        val combo = if (killFeedbackRemaining > 0f) (lastDefeat?.combo ?: 0) + 1 else 1
        val basePoints = if (enemy.kind == Kind.ELITE) 1000 else if (headshot) 150 else 100
        val points = basePoints + ((combo - 2) * 25).coerceIn(0, 100)
        val defeat = Defeat(slot, enemy.kind, enemy.x, enemy.z, headshot, points, combo)
        defeats.add(defeat); lastDefeat = defeat; killFeedbackRemaining = 3f
        if (enemy.kind == Kind.ELITE) {
            val gun = listOf(Weapon.ARC, Weapon.RAIL, Weapon.PLASMA)[eliteDefeated % 3]
            drops.add(Drop(eliteDefeated++, gun, enemy.x, enemy.z,
                V3Layout.heightAt(enemy.x, enemy.z)))
        }
        kills++; score += points
        if (aliveCount == 0 && spawned < TOTAL_ENEMIES)
            refillClock = maxOf(refillClock, CLEAR_REST_SECONDS)
        health = min(maxHealth, health + talents.killRepair)
        if (complete) {
            attacks.clear(); rewardCredits = 0; talents.dismiss()
            rewardMenuOpen = false
            message = "100 / 100 · 挑战完成"
        } else if (kills % 20 == 0) {
            rewardCredits++
            if (!talents.pending) talents.draft()
            message = "击破 $kills / 100 · Y 选择升级" + waveSupply()
            if (kills == 60) upgraded = true
        }
        return true
    }
    /** Only the actual front shield collider calls this; exposed head/legs/rear use normal damage. */
    fun hitShield(slot: Int, damage: Int = bulletDamage): Boolean {
        if (blocked || damage <= 0) return false
        val e = enemies.getOrNull(slot)?.takeIf { it.alive && it.shield > 0 } ?: return false
        e.shield = (e.shield - damage).coerceAtLeast(0)
        e.alerted = true
        if (e.shield == 0) {
            e.stunClock = 0.6f; e.telegraph = 0f
            message = "盾牌击破 · 暴露装甲"
        } else message = "护盾承伤 · 瞄准头部或绕到背后"
        return true
    }
    /** The separate chest collider is hittable only during a real attack windup. */
    fun hitEliteCore(slot: Int, damage: Int = bulletDamage): Boolean {
        if (blocked || damage <= 0) return false
        val enemy = enemies.getOrNull(slot)?.takeIf { it.coreOpen } ?: return false
        enemy.coreHealth = (enemy.coreHealth - damage).coerceAtLeast(0)
        hit(slot, true, damage)
        enemy.telegraph = 0f
        enemy.stunClock = if (enemy.coreHealth == 0) 1.5f else 0.65f
        enemy.attackClock = if (enemy.coreHealth == 0) 2f else 1.1f
        enemy.motion = Motion.HIT
        message = if (enemy.coreHealth == 0) "胸核击破 · 装甲失效，扇形齐射停止"
            else "胸核命中 · 已打断蓄力"
        return true
    }

    fun supplyAvailable(id: String) = id !in collectedSupplies
    fun collectSupply(id: String): Boolean {
        if (blocked || complete || !supplyAvailable(id)) return false
        val supply = V5Layout.supplies.firstOrNull { it.id == id } ?: return false
        if (health >= maxHealth && inventory.all {
                magazines[it.ordinal] == it.magazine
            }) { message = "生命和弹药充足 · 可稍后取用"; return false }
        collectedSupplies.add(id)
        health = min(maxHealth, health + supply.healing)
        inventory.forEach { magazines[it.ordinal] = it.magazine }
        reloadRemaining = 0f
        message = "${supply.label} · 生命 +${supply.healing} · 已有武器弹药补满"
        return true
    }
    private fun waveSupply(): String {
        val restored = minOf(15, maxHealth - health)
        health += restored
        return if (restored > 0) " · 战区补给 +$restored 生命" else ""
    }
    /** Explosion processing marks each barrel first, so chained explosions cannot recurse forever. */
    fun hitBarrel(index: Int, damage: Int = bulletDamage,
        visible: (Float, Float, Float, Float) -> Boolean = { ax, az, bx, bz ->
            V3Layout.lineClear(ax, az, bx, bz)
        }): List<Int> {
        if (blocked || damage <= 0) return emptyList()
        val barrel = barrels.getOrNull(index)?.takeIf { it.intact } ?: return emptyList()
        barrel.health = (barrel.health - damage).coerceAtLeast(0)
        if (barrel.intact) return emptyList()
        val exploded = mutableListOf(index)
        var cursor = 0
        while (cursor < exploded.size) {
            val center = barrels[exploded[cursor++]]
            enemies.filter { it.alive }.forEach {
                val distance = hypot(it.x - center.x, it.z - center.z)
                if (distance < 4f && visible(center.x, center.z, it.x, it.z))
                    hit(it.slot, false, (200f * (1f - distance / 4f)).toInt())
            }
            barrels.forEachIndexed { i, other ->
                if (other.intact && hypot(other.x - center.x, other.z - center.z) < 4f &&
                    visible(center.x, center.z, other.x, other.z)) {
                    other.health = 0; exploded.add(i)
                }
            }
        }
        message = "油桶爆炸 · 范围伤害"
        return exploded
    }
    fun damagePlayer(damage: Int): Int {
        val before = health
        if (!blocked && !complete && damage > 0)
            health = (health - maxOf(1, damage - talents.armor)).coerceAtLeast(0)
        return before - health
    }
    fun confirmTalent(): Boolean {
        if (!rewardMenuOpen || paused || over || !talents.pending) return false
        val previousReloadScale = talents.reloadScale
        val chosen = talents.confirm() ?: return false
        rewardCredits = (rewardCredits - 1).coerceAtLeast(0)
        rewardMenuOpen = false
        reloadRemaining *= talents.reloadScale / previousReloadScale
        if (chosen == RunTalents.Talent.RESERVE) health = min(maxHealth, health + 25)
        message = "已获得 ${chosen.label} · ${chosen.description}"
        if (rewardCredits > 0) talents.draft()
        return true
    }
    fun interact(x: Float, z: Float): Boolean {
        if (paused || over || complete || !x.isFinite() || !z.isFinite()) return false
        if (rewardMenuOpen) return confirmTalent()
        if (talents.pending) return openRewards()
        message = "击破 $kills / 100 · 靠近补给箱按 Y"
        return false
    }
    fun hearShot(x: Float, z: Float) {
        enemies.filter { it.alive && hypot(it.x - x, it.z - z) < 55f }.forEach {
            it.alerted = true; it.lastKnownX = x; it.lastKnownZ = z; it.lostClock = 0f
        }
    }
    /** Exact scene visibility can veto attacks; pure model tests use the shared layout. */
    fun tick(dt: Float, playerX: Float = 0f, playerZ: Float = 0f,
        playerY: Float = EyeHeight.DEFAULT,
        visible: (Enemy) -> Boolean = { V3Layout.lineClear(it.x, it.z, playerX, playerZ) }) {
        require(dt.isFinite() && dt >= 0f)
        attacks.clear()
        if (paused || dt == 0f || !playerX.isFinite() || !playerZ.isFinite() || !playerY.isFinite()) return
        killFeedbackRemaining = (killFeedbackRemaining - dt).coerceAtLeast(0f)
        enemies.forEach { it.deathClock = (it.deathClock - dt).coerceAtLeast(0f) }
        if (blocked) return
        clock += dt
        shotCooldown = (shotCooldown - dt).coerceAtLeast(0f)
        if (reloadRemaining > 0f) {
            reloadRemaining = (reloadRemaining - dt).coerceAtLeast(0f)
            if (reloadRemaining == 0f) magazines[weapon.ordinal] = weapon.magazine
        }
        if (startRemaining > 0f) {
            startRemaining = (startRemaining - dt).coerceAtLeast(0f)
            if (startRemaining == 0f) {
                refill(playerX, playerZ, 3)
                refillClock = 6f
            }
            return
        }
        refillClock = (refillClock - dt).coerceAtLeast(0f)
        if (refillClock == 0f) {
            refill(playerX, playerZ, if (batchIndex % 2 == 0) 2 else 3)
            refillClock = floatArrayOf(6f, 7f, 5f)[batchIndex++ % 3]
        }
        attackGap = (attackGap - dt).coerceAtLeast(0f)
        windupGap = (windupGap - dt).coerceAtLeast(0f)
        val frameCursor = attackCursor
        enemies.indices.forEach { index ->
            val e = enemies[(frameCursor + index) % enemies.size]
            e.hitClock = (e.hitClock - dt).coerceAtLeast(0f)
            e.stunClock = (e.stunClock - dt).coerceAtLeast(0f)
            if (!e.alive) return@forEach
            // All active enemies share live player coordinates, even through walls and while stunned.
            e.alerted = true; e.lastKnownX = playerX; e.lastKnownZ = playerZ; e.lostClock = 0f
            if (e.stunClock > 0f) { e.motion = Motion.HIT; return@forEach }
            e.recoveryClock = (e.recoveryClock - dt).coerceAtLeast(0f)
            val dx = playerX - e.x; val dz = playerZ - e.z
            val distance = hypot(dx, dz).coerceAtLeast(0.01f)
            val seesPlayer = distance < 38f && visible(e)
            val side = if (e.slot % 2 == 0) 1f else -1f
            val flank = e.kind == Kind.FLANKER && distance > 4.5f && distance < 18f
            val flankX = playerX + dz / distance * 3f * side
            val flankZ = playerZ - dx / distance * 3f * side
            val useFlank = flank && V3Layout.walkable(flankX, flankZ, gateOpen)
            val targetX = if (useFlank) flankX else e.lastKnownX
            val targetZ = if (useFlank) flankZ else e.lastKnownZ
            val melee = e.kind.melee
            val inRange = if (melee) distance < 1.75f
                else distance < if (e.kind == Kind.SNIPER) 34f else 24f
            val directRoute = V3Layout.routeClear(e.x, e.z, targetX, targetZ, gateOpen) &&
                navigation?.clear(e.x, e.z, targetX, targetZ) != false
            val canAttack = inRange && seesPlayer
            if (e.telegraph > 0f) {
                e.motion = Motion.AIM
                e.telegraph = (e.telegraph - dt).coerceAtLeast(0f)
                if (e.telegraph == 0f) {
                    if (canAttack && attackGap > 0f) {
                        e.telegraph = 0.001f
                        return@forEach
                    }
                    if (canAttack) {
                        attacks.add(Attack(e.slot, e.x, e.z, e.kind.damage, melee, e.aimX, e.aimZ, e.aimY,
                            if (e.kind == Kind.ELITE && e.coreHealth > 0) e.phase else 1))
                        e.attackSerial++
                        attackGap = 0.22f
                    }
                    e.attackClock = e.kind.cooldown * if (e.kind == Kind.ELITE) (1f - (e.phase - 1) * 0.14f) else 1f
                    e.motion = if (canAttack) Motion.ATTACK else Motion.IDLE
                }
                return@forEach
            }
            e.attackClock -= dt
            if (canAttack && e.attackClock <= 0f && windupGap == 0f &&
                enemies.count { it.alive && it.telegraph > 0f } < 2) {
                e.telegraph = if (e.kind == Kind.ELITE) 1.3f else e.kind.warningSeconds
                e.aimX = playerX; e.aimZ = playerZ
                e.aimY = playerY - 0.25f
                e.motion = Motion.AIM
                windupGap = 0.18f
                attackCursor = (e.slot + 1) % enemies.size
            } else {
                val preferredRange = when (e.kind) {
                    Kind.SNIPER -> 22f; Kind.DRONE -> 8f; else -> 12f
                }
                val approach = !seesPlayer || !directRoute || if (melee) distance > 1.5f
                    else distance > preferredRange
                val retreat = !melee && distance < if (e.kind == Kind.SNIPER) 14f else 5f
                val speed = e.kind.speed
                val forward = if (approach) 1f else if (retreat) -1f else 0f
                val strafe = if (!melee && !approach && !retreat) sin(clock * 0.8f + e.slot) * 0.65f else 0f
                navigation?.take(e.slot, targetX, targetZ, gateOpen)?.let {
                    e.route = it
                    e.routeIndex = it.indices.take(8).filter { i ->
                        val p = it[i]
                        V3Layout.routeClear(e.x, e.z, p.first, p.second, gateOpen) &&
                            navigation.clear(e.x, e.z, p.first, p.second)
                    }.minByOrNull { i -> hypot(it[i].first - e.x, it[i].second - e.z) } ?: 0
                }
                e.routeClock = (e.routeClock - dt).coerceAtLeast(0f)
                if (!directRoute && e.routeClock == 0f) {
                    if (navigation == null) {
                        e.route = V3Layout.path(e.x, e.z, targetX, targetZ, gateOpen)
                        e.routeIndex = 0
                    } else navigation.request(e.slot, e.x, e.z, targetX, targetZ, gateOpen)
                    pathRequests++
                    e.routeClock = 0.6f + e.slot * 0.04f
                }
                while (e.routeIndex < e.route.size &&
                    hypot(e.route[e.routeIndex].first - e.x, e.route[e.routeIndex].second - e.z) < 0.18f)
                    e.routeIndex++
                val target = if (!directRoute && e.routeIndex < e.route.size)
                    e.route[e.routeIndex] else null
                val ndx = (target?.first ?: targetX) - e.x
                val ndz = (target?.second ?: targetZ) - e.z
                val navDistance = hypot(ndx, ndz).coerceAtLeast(0.01f)
                val evade = if (e.recoveryClock > 0f) (if (e.slot % 2 == 0) 1f else -1f) else strafe
                var separateX = 0f; var separateZ = 0f
                enemies.forEach { other ->
                    if (other.slot != e.slot && other.alive) {
                        val gapX = e.x - other.x; val gapZ = e.z - other.z
                        val gap = hypot(gapX, gapZ)
                        val spacing = if (e.kind == Kind.ELITE || other.kind == Kind.ELITE) 1.1f else 0.8f
                        if (gap in 0.001f..spacing) {
                            val force = (1f - gap / spacing) * if (directRoute) 0.8f else 0.22f
                            separateX += gapX / gap * force; separateZ += gapZ / gap * force
                        } else if (gap <= 0.001f) {
                            separateX += if (e.slot > other.slot) 0.5f else -0.5f
                        }
                    }
                }
                val mx = (ndx / navDistance * forward + ndz / navDistance * evade +
                    separateX.coerceIn(-0.6f, 0.6f)) * speed * dt
                val mz = (ndz / navDistance * forward - ndx / navDistance * evade +
                    separateZ.coerceIn(-0.6f, 0.6f)) * speed * dt
                val beforeX = e.x; val beforeZ = e.z
                V3Layout.move(e, mx, mz, gateOpen)
                if (navigation == null || hypot(e.x - beforeX, e.z - beforeZ) < hypot(mx, mz) * 0.25f)
                    reportMotion(e, beforeX, beforeZ, beforeX + mx, beforeZ + mz, dt)
                e.motion = if (hypot(e.x - beforeX, e.z - beforeZ) > 0.0001f) Motion.RUN else Motion.IDLE
            }
        }
    }
    fun reportMotion(e: Enemy, beforeX: Float, beforeZ: Float, desiredX: Float, desiredZ: Float, dt: Float) {
        val intended = hypot(desiredX - beforeX, desiredZ - beforeZ)
        if (intended < 0.0001f) return
        if (hypot(e.x - beforeX, e.z - beforeZ) < intended * 0.25f) {
            e.stuckClock += dt
            if (e.stuckClock >= 0.3f) {
                val dx = (desiredX - beforeX) / intended; val dz = (desiredZ - beforeZ) / intended
                navigation?.block(e.slot, beforeX + dx * 0.7f, beforeZ + dz * 0.7f)
                e.route = emptyList(); e.routeIndex = 0
                e.routeClock = minOf(e.routeClock, 0.15f)
                e.recoveryClock = 0.8f; e.stuckClock = 0f
            }
        } else e.stuckClock = (e.stuckClock - dt).coerceAtLeast(0f)
    }
    private fun clearEnemies() {
        navigation?.reset()
        attackGap = 0f; windupGap = 0f; attackCursor = 0
        enemies.forEach {
            it.health = 0; it.deathClock = 0f; it.hitClock = 0f; it.stunClock = 0f
            it.telegraph = 0f; it.motion = Motion.IDLE; it.route = emptyList()
            it.routeIndex = 0; it.routeClock = 0f
            it.alerted = false; it.lostClock = 0f; it.stuckClock = 0f; it.recoveryClock = 0f
        }
    }
    private fun refill(playerX: Float, playerZ: Float, count: Int) {
        val occupied = enemies.filter { it.alive || it.deathClock > 0f }
            .map { it.x to it.z }.toMutableList()
        var live = aliveCount
        var added = 0
        for (offset in enemies.indices) {
            if (added >= count) break
            if (live >= ACTIVE_ENEMIES || spawned >= TOTAL_ENEMIES) break
            // The 10th, 30th, 50th, 70th and 90th emissions are guaranteed elites.
            // If their retained slot is busy, wait; never silently skip the encounter.
            val eliteDue = eliteSpawned < ELITE_EMISSIONS.size &&
                spawned + 1 >= ELITE_EMISSIONS[eliteSpawned]
            val slot = if (eliteDue) 6 else (spawnCursor + offset) % MAX_ENEMIES
            val enemy = enemies[slot]
            if (eliteDue && (enemy.alive || enemy.deathClock > 0f)) break
            if (enemy.alive || enemy.deathClock > 0f || !eliteDue && slot == 6) continue
            val point = BattleSpawns.choose(spawned, playerX, playerZ, occupied) { x, z ->
                val candidate = navigation?.spawnPoint(x, z, true) ?: V3Layout.nearestWalkable(x, z, true)
                check(spawnClear(candidate.first, candidate.second))
                candidate
            } ?: break
            spawn(enemy, kindForSlot(slot), point.first, point.second)
            if (eliteDue) eliteSpawned++
            occupied.add(point); spawned++; live++; added++
        }
        spawnCursor = (spawnCursor + 1) % MAX_ENEMIES
    }
    private fun spawn(e: Enemy, kind: Kind, x: Float, z: Float) {
        val point = navigation?.spawnPoint(x, z, gateOpen) ?: V3Layout.nearestWalkable(x, z, gateOpen)
        navigation?.cancel(e.slot)
        e.generation++
        e.emission = spawned + 1
        e.kind = kind; e.health = kind.hp; e.x = point.first; e.z = point.second
        e.spawnX = e.x; e.spawnZ = e.z
        e.attackClock = 0.9f + e.slot * 0.12f; e.telegraph = 0f
        e.hitClock = 0f; e.deathClock = 0f; e.stunClock = 0f; e.motion = Motion.IDLE
        e.route = emptyList(); e.routeIndex = 0; e.routeClock = 0f
        e.alerted = false; e.stuckClock = 0f; e.recoveryClock = 0f; e.attackSerial = 0
        e.aimX = x; e.aimZ = z
        e.shield = if (kind == Kind.GUARD) 96 else 0
        e.coreHealth = 180
    }
    companion object {
        const val CORPSE_SECONDS = 0.75f
        const val CLEAR_REST_SECONDS = 4f
        const val PICKUP_RADIUS = 2f
        val ELITE_EMISSIONS = intArrayOf(10, 30, 50, 70, 90)
        const val ACTIVE_ENEMIES = 8
        const val TOTAL_ENEMIES = 100
        const val MAX_ENEMIES = 18
        fun kindForSlot(slot: Int): Kind = when (slot) {
            6 -> Kind.ELITE
            3, 9, 15 -> Kind.FLANKER
            4, 10, 16 -> Kind.GUARD
            5, 11 -> Kind.SNIPER
            7, 12, 17 -> Kind.DRONE
            2, 8, 14 -> Kind.SHOOTER
            else -> Kind.CHARGER
        }
    }
}
