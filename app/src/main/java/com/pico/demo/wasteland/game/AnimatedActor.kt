package com.pico.demo.wasteland.game

import android.util.Log
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.animation.AnimationPlaybackController
import com.pico.spatial.core.ecs.animation.AnimationPlayConfig
import com.pico.spatial.core.ecs.animation.AnimationTransitionMode
import com.pico.spatial.core.ecs.resource.AnimationResource
import com.pico.spatial.core.ecs.simulation.*
import com.pico.spatial.core.math.*
import kotlin.math.atan2
import kotlin.math.sin

class AnimatedActor(private val kit: SceneKit, val slot: Int,
    private val asset: GeneratedRobotAsset) : AutoCloseable {
    val entity = Entity().apply { setName("WastelandRobot$slot"); kit.root.addChild(this) }
    val hitTargets = mutableMapOf<Long, Pair<Int, Boolean>>()
    val shieldTargets = mutableMapOf<Long, Int>()
    val coreTargets = mutableMapOf<Long, Int>()
    private val hitboxes = mutableListOf<Entity>()
    private lateinit var model: Entity
    private lateinit var healthBar: Entity
    private lateinit var healthTrack: Entity
    private data class Rig(val entity: Entity, val clips: Map<String, AnimationResource>,
        val pose: SkeletonPoseComponent?,
        var controller: AnimationPlaybackController? = null,
        var paused: Boolean = false, var speed: Float = 1f)
    private val rigs = mutableListOf<Rig>()
    private var currentClip = ""
    private val motion = ActorMotion()
    private var generation = -1
    private val surfaces = mutableListOf<Pair<Entity, ModelComponent>>()
    private var wasAlive = false
    private var lastPosition: Vector3? = null
    private var attackMotion = 0f
    private var modelBase = Vector3.ZERO
    private var modelScale = Vector3(1f)
    private var clock = 0f
    private lateinit var weaponTip: Entity
    private lateinit var warning: Entity
    private var attackSerial = -1
    private var socketReady = false
    private var socketPosition = Vector3.ZERO
    private var muzzlePosition = Vector3.ZERO
    private var muzzleDirection = Vector3.FORWARD
    private var shieldCenter = Vector3.ZERO
    private var currentPosition = Vector3.ZERO
    private var facingRotation = Quat()
    private var shown = false
    private var collidable: Boolean? = null
    private var lastFacing = Float.NaN
    private var lastLean = Float.NaN
    private var lastDeath = Float.NaN
    private var warningShown = false
    private var tipWhite = false
    private var healthShown = true
    private var lastHealth = -1
    private var healthWhite = false
    private var shieldModel: Entity? = null
    private var shieldHitbox: Entity? = null
    private var shieldShown = true
    private var eliteCore: Entity? = null
    private var coreHitbox: Entity? = null
    private var coreWasOpen = false
    private var coreWasIntact = true
    private val sniperLine = kit.box(entity, "SniperAimLine$slot", Vector3.ZERO,
        Vector3(0.006f), kit.red).apply { enabled = false }
    private val liveFilter = CollisionFilter(CollisionGroup(SceneKit.GROUP))
    private val deadFilter = CollisionFilter(CollisionGroup(0u))
    private val elite get() = slot == 6
    private val visualKind get() = GameRules.kindForSlot(slot)

    suspend fun build() {
        if (visualKind == GameRules.Kind.DRONE) { buildDrone(); return }
        // Authored robot textures already carry ink. A second skeleton doubles animation work.
        model = kit.model(asset.path, entity, "${asset.name}_$slot", independent = true)
        val targetHeight = when (visualKind) {
            GameRules.Kind.ELITE -> 2.1f
            GameRules.Kind.FLANKER -> 1.75f
            GameRules.Kind.GUARD -> 2.05f
            else -> 1.9f
        }
        val raw = model.getVisualBounds(entity)
        check(!raw.isEmpty() && raw.size.y > 0f)
        val scale = targetHeight / raw.size.y
        modelScale = Vector3(scale, scale, scale)
        model.pose().setScaleVector(modelScale)
        val fitted = model.getVisualBounds(entity)
        modelBase = Vector3(-fitted.center.x, -fitted.min.y, -fitted.center.z)
        model.pose().setPosition(modelBase)
        prepareAnimations(skeletal = true)
        check(rigs.isNotEmpty()) { "Robot has no skeleton roots" }
        play("Idle")
        val finalBounds = model.getVisualBounds(entity)
        check(kotlin.math.abs(finalBounds.min.y) < 0.02f &&
            kotlin.math.abs(finalBounds.size.y - targetHeight) < 0.02f)
        Log.i("Wasteland", "V8_ROBOT slot=$slot kind=$visualKind asset=${asset.name} height=$targetHeight skeletons=${rigs.size} clips=${rigs.first().clips.keys} min=${finalBounds.min} max=${finalBounds.max}")
        weaponTip = kit.box(entity, "GeneratedMuzzle$slot", Vector3.ZERO,
            Vector3(0.026f), kit.orange)
        warning = kit.box(weaponTip, "AttackWindup$slot", Vector3.ZERO, Vector3(0.08f),
            kit.orange, mesh = kit.sphere).apply { enabled = false }
        fun hitbox(name: String, y: Float, size: Vector3, head: Boolean) {
            val e = kit.invisibleBox(entity, name, Vector3(0f, y, 0f), size, SceneKit.GROUP)
            hitboxes.add(e); hitTargets[e.id] = slot to head
        }
        val multiplier = targetHeight / 1.9f
        hitbox("RobotBody$slot", 0.91f * multiplier,
            Vector3(0.72f, 1.25f, 0.58f) * multiplier, false)
        hitbox("RobotHead$slot", 1.62f * multiplier,
            Vector3(0.52f, 0.4f, 0.5f) * multiplier, true)
        if (elite) {
            // Generated upper chest: front vertices at Y1.50..1.60, Z<=0.1574 m.
            // The plate touches that surface; the interaction volume clears the body proxy.
            eliteCore = kit.box(model, "EliteChestCore", Vector3(0f, 1.54f, 0.175f),
                Vector3(0.26f, 0.22f, 0.04f), kit.orange)
            coreHitbox = kit.invisibleBox(model, "EliteChestCoreHit",
                Vector3(0f, 1.54f, 0.175f), Vector3(0.27f, 0.26f, 0.4f), SceneKit.GROUP)
                .apply { enabled = false }
            coreTargets[coreHitbox!!.id] = slot
        }
        val top = targetHeight + 0.25f
        healthTrack = kit.box(entity, "HealthTrack", Vector3(0f, top, 0f),
            Vector3(0.9f, 0.08f, 0.035f), if (elite) kit.violet else kit.ink)
        healthBar = kit.box(entity, "EnemyHealth", Vector3(0f, top, 0.035f),
            Vector3(0.85f, 0.045f, 0.03f), if (elite) kit.gold else kit.cyan)
        if (elite) for (side in listOf(-1f, 1f)) {
            kit.box(entity, "EliteCrown", Vector3(side * 0.12f, top + 0.15f, 0f),
                Vector3(0.16f, 0.055f, 0.04f), kit.gold)
                .pose().setEulerAngles(EulerAngles(0f, 0f, side * 35f))
        }
        if (visualKind == GameRules.Kind.GUARD) {
            fun panel(node: Entity): Entity? = if (node.getName() == "BreakableShieldPanel") node
                else node.getChildren().firstNotNullOfOrNull(::panel)
            shieldModel = checkNotNull(panel(model)) { "Generated shield panel was not imported" }
            shieldHitbox = kit.invisibleBox(model, "GuardShieldHit$slot",
                Vector3.ZERO, Vector3(0.01f), SceneKit.GROUP)
            shieldTargets[shieldHitbox!!.id] = slot
        }
        updateSocket()
        entity.enabled = false
    }

    private suspend fun buildDrone() {
        model = kit.model(asset.path, entity, "${asset.name}_$slot", independent = true)
        val raw = model.getVisualBounds(entity)
        check(!raw.isEmpty() && raw.size.x > 0f)
        modelScale = Vector3(1.4f / raw.size.x)
        model.pose().setScaleVector(modelScale)
        val fit = model.getVisualBounds(entity)
        modelBase = Vector3.ZERO - fit.center
        model.pose().setPosition(modelBase)
        val body = kit.invisibleBox(entity, "DroneHull$slot", Vector3.ZERO,
            Vector3(0.9f, 0.43f, 0.65f), SceneKit.GROUP)
        // Cyan lens vertices measured from the delivered mesh/UV data.
        val sensor = kit.invisibleBox(entity, "DroneSensor$slot",
            modelBase + Vector3(-0.0223f, 0.49882f, 0.38512f) * modelScale.x,
            Vector3(0.14f, 0.13f, 0.11f), SceneKit.GROUP)
        hitboxes.addAll(listOf(body, sensor))
        hitTargets[body.id] = slot to false; hitTargets[sensor.id] = slot to true
        weaponTip = kit.box(entity, "DroneMuzzle$slot", Vector3.ZERO,
            Vector3(0.026f), kit.orange)
        warning = kit.box(weaponTip, "DroneWarning$slot", Vector3.ZERO, Vector3(0.08f),
            kit.orange, mesh = kit.sphere).apply { enabled = false }
        healthTrack = kit.box(entity, "DroneHealthTrack$slot", Vector3(0f, 0.58f, 0f),
            Vector3(0.9f, 0.08f, 0.035f))
        healthBar = kit.box(entity, "DroneHealth$slot", Vector3(0f, 0.58f, 0.035f),
            Vector3(0.85f, 0.045f, 0.03f), kit.cyan)
        prepareAnimations(skeletal = false)
        play("Idle")
        updateSocket()
        entity.enabled = false
        Log.i("Wasteland", "V8_DRONE slot=$slot asset=${asset.name} skeletons=0 rotors=3 width=1.4 sensor=upper-front clips=${rigs.flatMap { it.clips.keys }.distinct()}")
    }

    private fun prepareAnimations(skeletal: Boolean) {
        fun owners(node: Entity): List<Entity> = listOf(node) + node.getChildren().flatMap(::owners)
        surfaces.addAll(owners(model).mapNotNull { node ->
            node.components[ModelComponent::class.java]?.let { node to it }
        })
        val playbackOwners = if (skeletal) model.findSkeletonRootEntity().toList()
            else owners(model).filter { it.getAnimationResources().isNotEmpty() }
        playbackOwners.forEach { owner ->
            val clips = owner.getAnimationResources().associate {
                val name = it.getName()
                name to kit.own(if (name in listOf("Death", "Punch", "Fire")) it.repeat(0) else it)
            }
            check(clips.keys.containsAll(listOf("Idle", "Walking", "Fire", "Death"))) {
                "Generated animation owner ${owner.getName()} missing clips: ${clips.keys}"
            }
            rigs.add(Rig(owner, clips, if (skeletal)
                owner.components[SkeletonPoseComponent::class.java] ?:
                    SkeletonPoseComponent().also { owner.components.set(it) } else null))
        }
        check(rigs.isNotEmpty()) { "Generated ${asset.name} has no playback owner" }
    }

    private fun play(clip: String) {
        if (currentClip == clip) return
        rigs.forEach {
            val previous = it.controller?.takeIf { c -> c.valid }
            it.controller = it.entity.playAnimation(it.clips.getValue(clip),
                AnimationPlayConfig(transitionDuration = 0f,
                    transitionMode = AnimationTransitionMode.STOP_AND_CROSSFADE))
            // Start the replacement before releasing the handle. Stopping the old handle first
            // can restore bind values between queued scene updates.
            previous?.close()
            it.paused = false; it.speed = 1f
        }
        currentClip = clip
    }

    fun update(enemy: GameRules.Enemy, player: Vector3, running: Boolean, dt: Float) {
        check(enemy.kind == visualKind || !enemy.alive) {
            "Actor slot $slot built for $visualKind but received ${enemy.kind}"
        }
        if (enemy.alive && generation != enemy.generation) {
            generation = enemy.generation
            currentClip = ""; motion.reset(); attackMotion = 0f; attackSerial = -1
            lastPosition = null; lastFacing = Float.NaN
            lastHealth = -1; lastDeath = Float.NaN; lastLean = Float.NaN
            model.pose().setPosition(modelBase).setQuaternion(Quat())
            surfaces.forEach { (_, surface) -> surface.isVisible = true }
            wasAlive = false
        }
        val visible = enemy.alive || enemy.deathClock > 0f
        if (visible != shown) { entity.enabled = visible; shown = visible }
        if (collidable != enemy.alive) {
            collidable = enemy.alive
            hitboxes.forEach {
                it.components[CollisionComponent::class.java]?.collisionFilter =
                    if (enemy.alive) liveFilter else deadFilter
            }
        }
        if (!visible) {
            rigs.forEach {
                if (!it.paused) {
                    it.controller?.takeIf { c -> c.valid }?.pause()
                    it.paused = true
                }
            }
            wasAlive = false; lastPosition = null
            return
        }
        if (enemy.alive && !wasAlive) { currentClip = ""; attackMotion = 0f; attackSerial = -1 }
        wasAlive = enemy.alive
        if (running) clock += dt
        val ground = V3Layout.heightAt(enemy.x, enemy.z) + enemy.kind.hoverHeight
        val position = Vector3(enemy.x, ground, enemy.z)
        currentPosition = position
        if (position != lastPosition) entity.pose().setPosition(position)
        val delta = lastPosition?.let { position - it } ?: Vector3.ZERO
        lastPosition = position
        val aimPlayer = if (enemy.telegraph > 0f || attackMotion > 0f ||
            enemy.attackSerial != attackSerial) Vector3(enemy.aimX, enemy.aimY + 0.25f, enemy.aimZ) else player
        if (enemy.alive) {
            val facing = if (enemy.kind != GameRules.Kind.GUARD && enemy.kind != GameRules.Kind.DRONE &&
                enemy.motion == GameRules.Motion.RUN && delta.length() > 0.0001f)
                delta else aimPlayer - position
            val gunYaw = if (visualKind.melee) 0f else
                atan2(asset.bindDirection.x, asset.bindDirection.z) * 57.29578f
            val angle = atan2(facing.x, facing.z) * 57.29578f - gunYaw
            if (angle != lastFacing) {
                lastFacing = angle
                facingRotation = EulerAngles(0f, angle, 0f).toQuat()
                entity.pose().setQuaternion(facingRotation)
            }
        }
        if (running) attackMotion = (attackMotion - dt).coerceAtLeast(0f)
        if (enemy.attackSerial != attackSerial) {
            if (attackSerial >= 0) { attackMotion = 0.5f; currentClip = "" }
            attackSerial = enemy.attackSerial
        }
        if (enemy.stunClock > 0f) attackMotion = 0f
        val clip = motion.select(enemy.alive, visualKind.melee,
            if (visualKind.melee) attackMotion > 0f || enemy.telegraph > 0f else attackMotion > 0f,
            enemy.motion == GameRules.Motion.RUN, dt)
        play(clip)
        rigs.forEach { rig ->
            rig.controller?.takeIf { it.valid }?.apply {
                if (running) {
                    if (rig.paused) { resume(); rig.paused = false }
                    val speed = when (clip) {
                        "Walking" -> enemy.kind.speed / 1.1f
                        "Death" -> asset.deathSpeed
                        else -> 1f
                    }
                    if (speed != rig.speed) { setSpeed(speed); rig.speed = speed }
                } else if (!rig.paused) { pause(); rig.paused = true }
            }
        }
        val hitLean = if (enemy.hitClock > 0f || enemy.stunClock > 0f)
            kotlin.math.sin(clock * 35f) * 8f else 0f
        val death = if (!enemy.alive)
            (1f - enemy.deathClock / GameRules.CORPSE_SECONDS).coerceIn(0f, 1f) else 0f
        if (death != lastDeath) {
            model.pose().setPosition(modelBase + Vector3(0f, -death * 0.05f, 0f))
            lastDeath = death
        }
        if (hitLean != lastLean) {
            model.pose().setEulerAngles(EulerAngles(0f, 0f, hitLean))
            lastLean = hitLean
        }
        updateSocket()
        val windup = enemy.alive && enemy.telegraph > 0f
        val coreOpen = enemy.coreOpen
        if (coreOpen != coreWasOpen) {
            coreHitbox?.enabled = coreOpen
            eliteCore?.components?.get(ModelComponent::class.java)?.materials?.set(0,
                if (coreOpen) kit.cyan else kit.orange)
            coreWasOpen = coreOpen
        }
        val coreIntact = enemy.alive && enemy.coreHealth > 0
        if (coreIntact != coreWasIntact) {
            eliteCore?.enabled = coreIntact
            coreWasIntact = coreIntact
        }
        val showShield = enemy.alive && enemy.shield > 0
        if (showShield != shieldShown) {
            shieldModel?.enabled = showShield; shieldHitbox?.enabled = showShield
            shieldShown = showShield
        }
        val showLine = windup && visualKind == GameRules.Kind.SNIPER
        sniperLine.enabled = showLine
        if (showLine) {
            val inverse = SceneCoordinates.inverse(facingRotation)
            val start = inverse.rotateVector(muzzlePosition - position)
            val end = inverse.rotateVector(aimPlayer - position)
            val d = end - start
            val length = d.length().coerceAtLeast(0.001f)
            sniperLine.pose().setPosition((start + end) * 0.5f)
                .setEulerAngles(EulerAngles(kotlin.math.asin((d.y / length).coerceIn(-1f, 1f)) * 57.29578f,
                    atan2(-d.x, -d.z) * 57.29578f, 0f))
                .setScaleVector(Vector3(0.005f, 0.005f, length))
        }
        if (windup != warningShown) { warning.enabled = windup; warningShown = windup }
        if (windup && running)
            warning.pose().setScaleVector(Vector3(0.06f + (0.5f + 0.5f * sin(clock * 25f)) * 0.09f))
        val brightTip = windup || attackMotion > 0.4f
        if (brightTip != tipWhite) {
            weaponTip.components[ModelComponent::class.java]?.materials?.set(0,
                if (brightTip) kit.white else kit.orange)
            tipWhite = brightTip
        }
        if (healthShown != enemy.alive) {
            healthBar.enabled = enemy.alive; healthTrack.enabled = enemy.alive
            healthShown = enemy.alive
        }
        if (enemy.alive && lastHealth != enemy.health) {
            healthBar.pose().setScaleVector(Vector3(0.85f * enemy.health / enemy.kind.hp, 0.045f, 0.03f))
            lastHealth = enemy.health
        }
        if (windup != healthWhite) {
            healthBar.components[ModelComponent::class.java]?.materials?.set(0,
                if (windup) kit.white else if (elite) kit.gold else kit.cyan)
            healthWhite = windup
        }
    }

    private fun updateSocket() {
        val controller = rigs.firstOrNull()?.controller?.takeIf { it.valid }
        val time = controller?.getTime() ?: 0f
        val sample = asset.sample(currentClip, time)
        val rotation = model.pose().quaternion
        val scale = modelScale.x
        socketPosition = model.pose().position +
            rotation.rotateVector(Vector3(sample[0], sample[1], sample[2]) * scale)
        val direction = rotation.rotateVector(Vector3(sample[3], sample[4], sample[5]))
        muzzlePosition = currentPosition + facingRotation.rotateVector(socketPosition)
        muzzleDirection = facingRotation.rotateVector(direction)
        weaponTip.pose().setPosition(socketPosition)
        if (sample.size >= 12) {
            val center = Vector3(sample[6], sample[7], sample[8])
            shieldHitbox?.pose()?.setPosition(center)
                ?.setScaleVector(Vector3(sample[9], sample[10], sample[11]))
            shieldCenter = model.pose().position + rotation.rotateVector(center * scale)
            if (sample.size == 19) {
                val x = sample[15]; val y = sample[16]; val z = sample[17]; val w = sample[18]
                val length = kotlin.math.sqrt(x*x + y*y + z*z + w*w)
                shieldModel?.pose()?.setPosition(Vector3(sample[12], sample[13], sample[14]))
                    ?.setQuaternion(Quat(x / length, y / length, z / length, w / length))
            }
        }
        socketReady = controller != null && time.isFinite() && sample.all { it.isFinite() }
    }
    fun attackOrigin(): Vector3 = muzzlePosition
    fun attackDirection(): Vector3 = muzzleDirection
    fun shieldRay(): Pair<Vector3, Vector3> {
        val normal = facingRotation.rotateVector(checkNotNull(asset.shieldNormal))
        val center = currentPosition + facingRotation.rotateVector(shieldCenter)
        return (center + normal * 1.5f) to (normal * -1f)
    }
    fun coreRay(): Pair<Vector3, Vector3> {
        val pose = model.pose()
        val center = pose.position + pose.quaternion.rotateVector(
            checkNotNull(coreHitbox).pose().position * modelScale.x)
        val normal = facingRotation.rotateVector(pose.quaternion.rotateVector(Vector3.FORWARD))
        return (currentPosition + facingRotation.rotateVector(center) + normal * 1.5f) to
            (normal * -1f)
    }
    fun droneSensorRay(): Pair<Vector3, Vector3> {
        val sensor = modelBase + Vector3(-0.0223f, 0.49882f, 0.38512f) * modelScale.x
        val direction = facingRotation.rotateVector(Vector3.FORWARD)
        return (currentPosition + facingRotation.rotateVector(sensor) + direction * 1.5f) to
            (direction * -1f)
    }
    fun impactPoint(hit: Vector3, incoming: Vector3): Vector3 {
        val bounds = model.getVisualBounds(kit.root)
        if (bounds.isEmpty()) return hit - incoming * 0.08f
        // The logical hitboxes can be inside the generated armor. Back up along the shot
        // until the effects clear its current visual envelope on the player's side.
        var point = hit
        repeat(70) {
            if (point.x < bounds.min.x - 0.035f || point.x > bounds.max.x + 0.035f ||
                point.y < bounds.min.y - 0.035f || point.y > bounds.max.y + 0.035f ||
                point.z < bounds.min.z - 0.035f || point.z > bounds.max.z + 0.035f) return point
            point -= incoming * 0.04f
        }
        return point
    }
    fun verifyVisible(enemy: GameRules.Enemy): Boolean =
        !enemy.alive || entity.enabled && model.enabled && surfaces.all { (node, surface) ->
            surface.isVisible && (node == shieldModel || node.enabled)
        } && rigs.all { it.controller?.valid == true }
    fun verifyBounds(enemy: GameRules.Enemy): Boolean {
        if (!enemy.alive) return true
        val bounds = model.getVisualBounds(kit.root)
        val passed = !bounds.isEmpty() && bounds.size.y in 0.2f..3.5f &&
            kotlin.math.abs(bounds.center.x - enemy.x) < 2f &&
            kotlin.math.abs(bounds.center.z - enemy.z) < 2f
        if (!passed) Log.e("Wasteland", "V10_ACTOR_BOUNDS slot=$slot kind=$visualKind " +
            "enemy=${enemy.x},${enemy.z} size=${bounds.size} min=${bounds.min} max=${bounds.max} " +
            "actor=${entity.pose().position} model=${model.pose().position} scale=${model.pose().scaleVector} " +
            "clip=$currentClip time=${rigs.firstOrNull()?.controller?.getTime()} " +
            "actorNative=${entity.convertPositionTo(Vector3.ZERO, kit.root)} local=${model.getVisualBounds(entity)} " +
            "surfaces=${surfaces.map { it.first.getName() to it.second.isVisible }}")
        return passed
    }
    fun verifySocket() = socketReady &&
        rigs.all { it.controller?.valid == true } &&
        socketPosition.y in -0.8f..3f && kotlin.math.abs(socketPosition.x) < 1.8f &&
        kotlin.math.abs(socketPosition.z) < 1.8f
    fun weaponProbe(): String = "slot=$slot asset=${asset.name} clockSeconds=${rigs.firstOrNull()?.controller?.getTime()} local=$socketPosition muzzle=$muzzlePosition direction=$muzzleDirection connectedMesh=true"
    fun animationProbe(): String {
        val rig = rigs.first()
        val joints = rig.pose?.getJointTransforms()
        check(joints == null || joints.isNotEmpty()) { "SkeletonPoseComponent returned no joints" }
        return joints?.joinToString() ?: "clip=$currentClip time=${rig.controller?.getTime()}"
    }
    override fun close() {
        rigs.forEach { it.controller?.takeIf { c -> c.valid }?.apply { stop(); close() } }
        rigs.clear()
    }
}
