package com.pico.demo.wasteland.game

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.System as EcsSystem
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.ecs.simulation.*
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.*
import com.pico.spatial.tracking.DataProvider.DataListener
import com.pico.spatial.tracking.controller.*
import com.pico.spatial.tracking.hmd.HMDTrackingData
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

data class HudState(
    val status: String = "正在加载废土矿区…", val objective: String = "清理入口维修区",
    val health: Int = 100, val ammo: Int = 24, val magazine: Int = 24,
    val weapon: String = "自动步枪", val enemies: Int = 0, val kills: Int = 0, val score: Int = 0,
    val paused: Boolean = false, val over: Boolean = false, val complete: Boolean = false,
    val ready: Boolean = false, val reloading: Boolean = false,
    val killFeedback: String = "",
    val ammoHint: String = "",
    val maxHealth: Int = 100,
    val rewardAvailable: Boolean = false,
    val rewardMenuOpen: Boolean = false,
    val rewardOffers: List<RunTalents.Talent> = emptyList(),
    val rewardCursor: Int = 0,
    val talents: List<RunTalents.Talent> = emptyList(),
    val reloadProgress: Float = 0f,
    val objectiveProgress: Int = 0,
    val pickupHint: String = "",
    val arsenalHint: String = "",
)
enum class GameCommand { RELOAD, SWITCH, INTERACT, PAUSE, RESTART, EXIT,
    OPEN_REWARDS, CLOSE_REWARDS, CONFIRM_REWARD, SELECT_REWARD_0, SELECT_REWARD_1, SELECT_REWARD_2 }

/** Tracking callbacks publish snapshots; every scene mutation and animation call stays on ECS/main. */
class WastelandScene(context: Context, private val publish: (HudState) -> Unit) : AutoCloseable {
    private val appContext = context.applicationContext
    val root = Entity().apply { setName("WastelandMineV3") }
    val camera = AnchorEntity(AnchorTarget.createCameraTarget())
    private val hudAnchor = Entity().apply { setName("HudAnchor"); camera.addChild(this) }
    private var physicalEyeReferenceY = 0f
    private val kit = SceneKit(root)
    private val environment = OutpostEnvironment(kit)
    private val weaponView = WeaponView(kit)
    private val lootView = LootView(kit)
    private val arsenalFx = ArsenalFx(kit)
    private val navigation = NavigationWorker()
    private val missionNavigation = NavigationWorker()
    private val rules = GameRules(navigation)
    private val navigationCache = NavigationCache(context)
    private val initializationStart = SystemClock.elapsedRealtime()
    private val triggerGate = TriggerGate()
    private val gripGate = TriggerGate()
    private val aGate = TriggerGate()
    private val bGate = TriggerGate()
    private val xGate = TriggerGate()
    private val yGate = TriggerGate()
    // This ROM's native probe reports an X-axis capsule with height as its cylinder length.
    // Subtract the caps and rotate upright for a 1.75 m adult body / 0.28 m radius.
    private var playerCapsule = kit.own(ShapeResource.createCapsule(EyeHeight.CAPSULE - 0.56f, 0.28f))
    private val capsuleRotation = EulerAngles(0f, 0f, 90f).toQuat()
    private var playerHeight = EyeHeight.DEFAULT
    private var safePlayer: Vector3? = null
    private val audio = GameAudio(context)
    private val actors = mutableListOf<AnimatedActor>()
    private val previousEnemyX = FloatArray(GameRules.MAX_ENEMIES)
    private val previousEnemyZ = FloatArray(GameRules.MAX_ENEMIES)
    private val previousEnemyGeneration = IntArray(GameRules.MAX_ENEMIES)
    private val hitTargets = mutableMapOf<Long, Pair<Int, Boolean>>()
    private val shieldTargets = mutableMapOf<Long, Int>()
    private val coreTargets = mutableMapOf<Long, Int>()
    private val provider = ControllerTrackingProvider()
    private val hmdProvider = HMDTrackingProvider()
    private data class HeadSample(val data: HMDTrackingData, val time: Long)
    @Volatile private var headSample: HeadSample? = null
    private val headPoseCache = HeadPoseCache()
    private val headListener = DataListener<HMDTrackingData> {
        headSample = HeadSample(it, SystemClock.elapsedRealtime())
    }
    private val commands = ConcurrentLinkedQueue<GameCommand>()
    private var hudPanel: Entity? = null
    private var hudMenuShown = false
    private data class HudRender(val menu: Boolean, val paused: Boolean, val over: Boolean,
        val ready: Boolean, val cursor: Int, val width: Int, val height: Int)
    @Volatile private var hudRender: HudRender? = null
    private data class PoseSample(val data: ControllerTrackingData, val time: Long)
    private data class ActionSample(val data: ControllerActionData, val time: Long)
    @Volatile private var poseSample: PoseSample? = null
    @Volatile private var actionSample: ActionSample? = null
    private val poseListener = DataListener<ControllerTrackingData> {
        poseSample = PoseSample(it, SystemClock.elapsedRealtime())
    }
    private val actionListener = ControllerTrackingProvider.ControllerActionListener {
        actionSample = ActionSample(it, SystemClock.elapsedRealtime())
    }
    var active = true
        set(value) {
            if (field != value) {
                triggerGate.reset(); gripGate.reset()
                aGate.reset(); bGate.reset(); xGate.reset(); yGate.reset()
                lastTrigger = false; lastGrip = false
                lastA = false; lastB = false; lastX = false; lastY = false
                commands.clear()
            }
            field = value
        }
    var haptic: (Int, Int) -> Unit = { _, _ -> }
    private var initialized = false
    private var closed = false
    private var lastA = false
    private var lastB = false
    private var lastX = false
    private var lastGrip = false
    private var lastY = false
    private var lastTrigger = false
    private var snapArmed = true
    private var teleportHeld = false
    private var teleportTarget: Vector3? = null
    private var yaw = 0f
    private var hudClock = 0f
    private var clock = 0f
    private var hitFlash = 0f
    private var hurtClock = 0f
    private var hurtSide = DamageDirection.Side.FRONT
    private var shownHurtSide: DamageDirection.Side? = null
    private var feedbackClock = 0f
    private var feedback = ""
    private var shotIndex = 0
    private var trackingWasValid = false
    private var lastProviderState = ""
    private var selfCheckFrame = 0
    private var expansionWallHit = -1L
    private var hudReplayAt = 0L
    private var movementReference = Vector3.ZERO
    private var animationReference = ""
    private var scaleEvidenceFrame = 0
    private val reticle = Entity().apply { setName("AimCrosshair"); root.addChild(this); enabled = false }
    private val reticleParts = mutableListOf<Entity>()
    private val hitMarkerParts = mutableListOf<Entity>()
    private val hurtEdges = mutableListOf<Entity>()
    private val lastWarnings = IntArray(GameRules.MAX_ENEMIES)
    private var hitColor = kit.orange
    private val teleportMarker = kit.box(root, "TeleportLanding", Vector3.ZERO,
        Vector3(0.55f, 0.018f, 0.55f), kit.cyan, mesh = kit.sphere).apply { enabled = false }
    private val arc = List(14) { kit.box(root, "TeleportArc$it", Vector3.ZERO,
        Vector3(0.014f, 0.014f, 0.1f), kit.cyan).apply { enabled = false } }
    private val muzzleFx = ParticleFxPool(kit, "MuzzleFx", ParticleFxProfile.MUZZLE)
    private val sparkFx = ParticleFxPool(kit, "MetalSparkFx", ParticleFxProfile.SPARK)
    private val dustFx = ParticleFxPool(kit, "DustFx", ParticleFxProfile.DUST)
    private val electricFx = ParticleFxPool(kit, "ElectricFx", ParticleFxProfile.ELECTRIC)
    private val explosionFx = ParticleFxPool(kit, "ExplosionFx", ParticleFxProfile.EXPLOSION, 4)
    private val smokeFx = ParticleFxPool(kit, "ExplosionSmoke", ParticleFxProfile.BLAST_SMOKE, 4)
    private val coreFx = ParticleFxPool(kit, "DefeatedCore", ParticleFxProfile.CORE_BREAK, 8)
    private val killVfx = KillVfx(kit)
    private val impactVfx = ImpactVfx(kit)
    private val combatVfx = CombatVfx(kit)
    private var defeatFxCount = 0
    private var headshotFxCount = 0
    private var headshotAudioCount = 0
    private var navigationGrid: NavigationGrid? = null
    private data class Shell(val entity: Entity, var life: Float = 0f,
        var velocity: Vector3 = Vector3.ZERO)
    private val shells = mutableListOf<Shell>()
    private var shellIndex = 0
    private val boltShape = kit.own(ShapeResource.createBox(Vector3(CombatGeometry.BOLT_RADIUS * 2f)))
    private val plasmaShape = kit.own(ShapeResource.createBox(Vector3(0.24f)))
    private data class Projectile(val entity: Entity, val tail: Entity, var life: Float = 0f,
        var position: Vector3 = Vector3.ZERO, var origin: Vector3 = Vector3.ZERO,
        var velocity: Vector3 = Vector3.ZERO, var damage: Int = 8, var fresh: Boolean = false,
        var sourceSlot: Int = -1, var sourceGeneration: Int = -1,
        var weapon: GameRules.Weapon? = null)
    private val projectiles = mutableListOf<Projectile>()
    private val playerBolts = mutableListOf<Projectile>()
    private var playerBoltIndex = 0
    private var projectilePlayer: Vector3? = null
    private var projectileIndex = 0
    private data class Tracer(val entity: Entity, var start: Vector3 = Vector3.ZERO,
        var end: Vector3 = Vector3.ZERO, var progress: Float = 0f, var active: Boolean = false,
        var fresh: Boolean = false)
    private val tracers = List(32) {
        Tracer(kit.box(root, "PlayerTracer$it", Vector3.ZERO, Vector3(0.006f), kit.orange)
            .apply { enabled = false })
    }
    private var tracerIndex = 0
    private var reticleFlashing = false
    private var hitMarkerShown = false
    private var hitMarkerColor = kit.orange
    private var hurtShown = false
    private var stressFrame = 0
    private var stressShots = 0
    private var stressWorst = 0f
    private var stressStart = 0L
    private var stressNavigationPassed = false
    private var stressFirstRoomPassed = false
    private var stressPauseRemaining = 0
    private var stressPauseCycles = 0
    private var stressRoomStart = Vector3.ZERO
    private var stressRoomEnd = Vector3.ZERO
    private val stressGunShots = IntArray(GameRules.Weapon.entries.size)
    private var rangeProbe: Entity? = null
    private var restartChecks = 0
    private val geometryFailures = mutableListOf<String>()
    private var missionCheckStart = 0L
    private var missionPhaseStart = 0L
    private var missionPhase = -2
    private var missionCycles = 0
    private var missionFrames = 0
    private var missionPlayer = Vector3.ZERO
    private var missionRoute: List<Pair<Float, Float>>? = null
    private var missionRouteIndex = 0
    private var missionRoutePending = false
    private var missionGoal = Vector3.ZERO
    private var missionMotionWait = 0f

    suspend fun initialize(hud: Entity?) {
        loading("初始化空间", 0)
        root.pose().setScaleVector(Vector3(1f))
        camera.components[AnchorComponent::class.java]?.positionOffset = Vector3.ZERO
        provider.addListener(poseListener); provider.addControllerActionListener(actionListener)
        hmdProvider.addListener(headListener)
        Log.i(TAG, "HMD provider start=${hmdProvider.start()}")
        Log.i(TAG, "Controller provider start=${provider.start()}")
        hudAnchor.pose().setPosition(Vector3(0f, -0.48f, -1.25f))
        hud?.let {
            hudPanel = it
            hudAnchor.addChild(it)
            it.components[AttachmentPanelComponent::class.java]?.content?.apply {
                isEnabled = true
                isClickable = true
                isFocusable = true
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
            Log.i(TAG, "V3_HUD_INPUT SpatialUI controls enabled")
        }
        // The root is never rendered at the origin while its children are being imported.
        delay(32)
        physicalEyeReferenceY = physicalEyeInParent().y
        relocate(Vector3(0f, playerHeight, -2f), 0f)
        val generatedRobots = GeneratedRobotAsset.load(appContext)
        val imports = listOf("SandstoneRockComicV3", "MiningCraneComicV3", "WarehouseComicV3",
            "ReactorPlantComicV3", "MiningTruckComicV3", "LootChestComicV3", "EnergyBoltComicV3",
            "FuelBarrelComicV3", "CartridgeComicV3", "HeroRifleComicV3", "HeroShotgunComicV3")
            .map { "models/v3/$it.usdz" } +
            listOf("RepairBayV4", "PowerRelayV4").map { "models/v4/$it.usdz" } +
            listOf("SalvageKioskV5", "PumpStationV5", "WaterCommsTowerV5").map { "models/v5/$it.usdz" } +
            GeneratedRobotAsset.names.map { "models/v8/$it.glb" } +
            listOf("ShippingContainerV8", "ServiceTruckV8", "RepairFuelStationV8").map { "models/v8/$it.usdz" }
        val resourceStart = SystemClock.elapsedRealtime()
        kit.preload(imports) { done, total -> loading("导入资源 $done / $total", 5 + done * 35 / total) }
        if (closed) return
        Log.i(TAG, "V6_LOAD_PHASE phase=resources durationMs=${SystemClock.elapsedRealtime() - resourceStart}")
        loading("布置战区与碰撞", 40)
        for (sign in listOf(-1f, 1f)) {
            reticleParts.add(kit.box(reticle, "CrosshairH", Vector3(sign * 0.0048f, 0f, 0f),
                Vector3(0.0065f, 0.001f, 0.0006f), kit.white))
            reticleParts.add(kit.box(reticle, "CrosshairV", Vector3(0f, sign * 0.0048f, 0f),
                Vector3(0.001f, 0.0065f, 0.0006f), kit.white))
        }
        for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) {
            hitMarkerParts.add(kit.box(reticle, "HitMarker", Vector3(x * 0.011f, y * 0.011f, 0f),
                Vector3(0.007f, 0.0018f, 0.0008f), kit.orange).apply {
                pose().setEulerAngles(EulerAngles(0f, 0f, x * y * 45f)); enabled = false
            })
        }
        for (sign in listOf(-1f, 1f)) {
            hurtEdges.add(kit.box(hudAnchor, "DamageEdge", Vector3(sign * 0.55f, 0.48f, 0.25f),
                Vector3(0.025f, 0.45f, 0.004f), kit.red).apply { enabled = false })
        }
        for (sign in listOf(1f, -1f)) {
            hurtEdges.add(kit.box(hudAnchor, if (sign > 0f) "FrontDamageEdge" else "RearDamageEdge",
                Vector3(0f, 0.48f + sign * 0.34f, 0.25f),
                Vector3(0.3f, 0.014f, 0.004f), kit.red).apply { enabled = false })
        }
        val buildStart = SystemClock.elapsedRealtime()
        environment.build()
        if (closed) return
        weaponView.build()
        buildEffectModels()
        listOf(muzzleFx, sparkFx, dustFx, electricFx, explosionFx, smokeFx, coreFx)
            .forEach { it.initialize() }
        if (RunOptions.selfCheck) rangeProbe = kit.invisibleBox(root, "DebugLongRangeTarget",
            Vector3(-28f, 8f, -60f), Vector3(1f))
        if (RunOptions.selfCheck) kit.invisibleBox(root, "DebugThinWallV12",
            Vector3(-34f, 8f, -55f), Vector3(0.025f, 1f, 1f))
        repeat(GameRules.MAX_ENEMIES) { slot ->
            val actor = AnimatedActor(kit, slot,
                generatedRobots.getValue(GeneratedRobotAsset.name(GameRules.kindForSlot(slot))))
            actors.add(actor); actor.build(); hitTargets.putAll(actor.hitTargets)
            shieldTargets.putAll(actor.shieldTargets)
            coreTargets.putAll(actor.coreTargets)
            loading("配置战斗对象 ${slot + 1} / ${GameRules.MAX_ENEMIES}", 55 + (slot + 1) * 20 / GameRules.MAX_ENEMIES)
            if (closed) return
        }
        Log.i(TAG, "V6_LOAD_PHASE phase=scene_collision durationMs=${SystemClock.elapsedRealtime() - buildStart}")
        loading("检查通路与导航缓存", 75)
        bakeNavigation()
        if (closed) return
        rules.spawnClear = { x, z ->
            root.scene?.let { bodyClear(it, Vector3(x, V3Layout.heightAt(x, z) + playerHeight, z)) } == true
        }
        // Calibrate against a fresh tail sample after imports, before any world content is visible.
        repeat(15) {
            if (!refreshHeadPose()) delay(32)
        }
        check(headPoseCache.position != null) { "头显追踪暂不可用，请返回后重新进入" }
        val sample = headSample?.takeIf { SystemClock.elapsedRealtime() - it.time < 150L }
        val physical = physicalEyeInParent()
        check(physical.x.isFinite() && physical.y.isFinite() && physical.z.isFinite())
        physicalEyeReferenceY = physical.y
        relocate(Vector3(0f, EyeHeight.DEFAULT, -2f), 0f)
        delay(32)
        val origin = root.convertPositionTo(Vector3.ZERO, null)
        val unitLengths = listOf(Vector3.RIGHT, Vector3.UP, Vector3(0f, 0f, 1f)).map {
            (root.convertPositionTo(it, null) - origin).length()
        }
        check(unitLengths.all { abs(it - 1f) < 0.01f }) { "Non-meter Stage hierarchy: $unitLengths" }
        Log.i(TAG, "V6_SCALE worldMeters=$unitLengths rootScale=${root.pose().scaleVector} physicalParentY=$physicalEyeReferenceY eye=${eye()} freshHmd=${sample != null} hmd=${sample?.data?.hmdPose?.position} physicalWorld=${eyeWorld()} anchorWorld=${camera.convertPositionTo(Vector3.ZERO, null)} parentOrigin=${root.getParent()?.convertPositionTo(Vector3.ZERO, null)} capsule=${EyeHeight.CAPSULE}")
        environment.update(rules, 0f, false)
        kit.reveal()
        kit.updateVisibility(eye(), headRotation().rotateVector(Vector3.BACK), 1f)
        initialized = true
        WastelandSystem.runtime = this; EcsSystem.register(WastelandSystem::class.java)
        publish(state(false))
        Log.i(TAG, "SCENE_READY version=12 sdk=6.2.6 robots=${actors.size} barrels=${rules.barrels.size} initMs=${SystemClock.elapsedRealtime() - initializationStart} launchMs=${SystemClock.elapsedRealtime() - RunOptions.launchStartMs} profileNoInk=${RunOptions.profileNoInk} profileFullShadows=${RunOptions.profileFullShadows}")
        Log.i(TAG, "V8_PLAYER_CONTRACT eye=${eye()} standingEye=${EyeHeight.DEFAULT} body=${EyeHeight.CAPSULE} heightControls=false defaultWeapons=true containers=${V3Layout.shippingContainers.size}")
    }
    private fun loading(stage: String, progress: Int) {
        publish(HudState(status = "$stage · $progress%", objective = "准备同一座矿区", ready = false))
        Log.i(TAG, "V6_LOADING elapsedMs=${SystemClock.elapsedRealtime() - initializationStart} progress=$progress phase=$stage")
    }
    private suspend fun bakeNavigation() {
        // Let mesh colliders register before sampling. Yield each bounded chunk on ECS/main.
        delay(32)
        val scene = root.scene ?: error("Navigation scene missing")
        if (RunOptions.selfCheck) {
            val origin = Vector3(-26f, 8f, -60f)
            val direction = Vector3(-1f, 0f, 0f)
            val ray = cast(scene, origin, direction, WORLD_ONLY, 3f)
            for ((label, rotation) in listOf("identity" to Quat(),
                "z90" to EulerAngles(0f, 0f, 90f).toQuat(),
                "x90" to EulerAngles(90f, 0f, 0f).toQuat(),
                "y90" to EulerAngles(0f, 90f, 0f).toQuat())) {
                val hits = scene.convexCast(playerCapsule, origin, rotation, direction, 3f,
                    CollisionCastHitMode.ALL, CollisionGroup(WORLD_ONLY), root).results
                Log.i(TAG, "V7_CAPSULE_PROBE orientation=$label ray=${ray?.entity?.getName()}:${ray?.distance} " +
                    "hits=${hits.map { "${it.entity.getName()}:${it.distance}:${it.normal}" }}")
            }
            val radiusHit = scene.convexCast(playerCapsule, origin, capsuleRotation, direction, 3f,
                CollisionCastHitMode.ALL, CollisionGroup(WORLD_ONLY), root).results.first {
                    it.entity.id == rangeProbe!!.id
                }
            val topOrigin = Vector3(-28f, 10f, -60f)
            val down = Vector3(0f, -1f, 0f)
            val topRay = checkNotNull(cast(scene, topOrigin, down, WORLD_ONLY, 3f))
            val heightHit = scene.convexCast(playerCapsule, topOrigin, capsuleRotation, down, 3f,
                CollisionCastHitMode.ALL, CollisionGroup(WORLD_ONLY), root).results.first {
                    it.entity.id == rangeProbe!!.id
                }
            val radius = checkNotNull(ray).distance - radiusHit.distance
            val height = (topRay.distance - heightHit.distance) * 2f
            check(abs(radius - 0.28f) < 0.005f && abs(height - EyeHeight.CAPSULE) < 0.005f) {
                "Native capsule dimensions differ from the player contract: radius=$radius height=$height"
            }
            Log.i(TAG, "V7_CAPSULE_DIMENSIONS_PASS radius=$radius totalHeight=$height axis=Y")
        }
        val begin = SystemClock.elapsedRealtime()
        val key = navigationCache.fingerprint(kit.collisionDescription())
        var cached = navigationCache.read(key)
        val probes = (V3Layout.covers + V3Layout.structures.take(10) + V7Layout.obstacles).flatMap {
            listOf(it.x - it.width / 2 - 0.5f to it.z, it.x + it.width / 2 + 0.5f to it.z)
        } + listOf(0f to -2f, 0f to -6f, 0f to -32f, 17f to -46f, 0f to -79f, 0f to -86f)
        val cachedGrid = cached?.first
        if (cachedGrid != null) {
            val sampledProbes = probes.mapNotNull {
                cachedGrid.sampledPosition(it.first, it.second)
            }.distinct()
            val mismatch = sampledProbes.firstOrNull { (x, z) ->
                val actual = V3Layout.walkable(x, z, true) && bodyClear(scene,
                    Vector3(x, V3Layout.heightAt(x, z) + playerHeight, z), true)
                cachedGrid.walkable(x, z) != actual
            }
            if (mismatch != null) {
                Log.w(TAG, "NAV_CACHE_REJECT source=${cached?.second} reason=physics-probe " +
                    "sample=$mismatch cached=${cachedGrid.walkable(mismatch.first, mismatch.second)}")
                cached = null
            }
        }
        Log.i(TAG, "NAV_CACHE ${if (cached == null) "MISS" else "HIT"} key=$key source=${cached?.second} probes=${probes.size}")
        val builder = NavigationGrid.Builder()
        var samples = 0; var walls = 0
        if (cached == null) {
            var sliceStart = SystemClock.elapsedRealtime()
            for (ix in 0..156) for (iz in 0..200) {
                val x = V3Layout.MIN_X + ix * NavigationGrid.STEP
                val z = V3Layout.MIN_Z + iz * NavigationGrid.STEP
                if (NavigationGrid.buildingRegions.none { it.contains(x, z, 0f) }) continue
                val clear = V3Layout.walkable(x, z, true) && bodyClear(scene,
                    Vector3(x, V3Layout.heightAt(x, z) + playerHeight, z), ignoreBarrels = true)
                builder.sample(x, z, clear)
                samples++; if (!clear) walls++
                // Bound main-thread work by time; no fixed 16 ms sleep for every 48 cells.
                if (SystemClock.elapsedRealtime() - sliceStart >= 8L) {
                    loading("首次采样通路 $samples", 75 + ix * 20 / 156)
                    delay(1); if (closed) return
                    sliceStart = SystemClock.elapsedRealtime()
                }
            }
        }
        navigationGrid = (cached?.first ?: builder.build().also { navigationCache.write(key, it) }).also {
            navigation.setGrid(it); missionNavigation.setGrid(it)
        }
        if (RunOptions.missionStress) {
            for (iz in 0..200) {
                val z = V3Layout.MIN_Z + iz * NavigationGrid.STEP
                val row = (0..156).map { ix ->
                    val x = V3Layout.MIN_X + ix * NavigationGrid.STEP
                    when {
                        !V3Layout.walkable(x, z, true) -> '#'
                        navigationGrid!!.walkable(x, z) -> '.'
                        else -> 'X'
                    }
                }.joinToString("")
                Log.i(TAG, "NAV_GRID_ROW z=$z cells=$row")
            }
        }
        if (RunOptions.stress) {
            val grid = navigationGrid!!
            val candidates = (-4..4).flatMap { ix -> (-4..4).map { iz ->
                Vector3(ix * 0.25f, playerHeight, V3Layout.REPAIR_Z + iz * 0.25f)
            } }.filter { grid.walkable(it.x, it.z) && bodyClear(scene, it) }
                .sortedBy { hypot(it.x, it.z - V3Layout.REPAIR_Z) }
            val pair = candidates.firstNotNullOfOrNull { a ->
                candidates.firstOrNull { b ->
                    (b - a).length() >= 1.2f && grid.clear(a.x, a.z, b.x, b.z) &&
                        V3Layout.routeClear(a.x, a.z, b.x, b.z, false)
                }?.let { a to it }
            }
            check(pair != null) { "No connected standable room positions: $candidates" }
            stressRoomStart = pair.first; stressRoomEnd = pair.second
            Log.i(TAG, "NAV_ROOM_TARGETS start=$stressRoomStart end=$stressRoomEnd free=${candidates.size}")
        }
        Log.i(TAG, "NAV_GRID_READY step=0.5 samples=$samples walls=$walls cache=${cached?.second ?: "generated"} durationMs=${SystemClock.elapsedRealtime() - begin}")
    }
    private suspend fun buildEffectModels() {
        suspend fun normalized(file: String, name: String, length: Float): Entity {
            val entity = Entity().apply { setName(name); root.addChild(this) }
            val model = kit.outlinedModel("models/v3/$file.usdz", entity, "${name}Model")
            // Source bolt's long axis is +X and its pivot is at the lower face, not its center.
            // Canonical -Z lets the root use the same full XYZ orientation as the tail.
            if (file == "EnergyBoltComicV3") model.pose().setEulerAngles(EulerAngles(0f, 90f, 0f))
            val bounds = model.getVisualBounds(entity)
            val scale = length / maxOf(bounds.size.x, bounds.size.y, bounds.size.z)
            model.pose().setScaleVector(Vector3(scale))
            model.pose().setPosition(model.getVisualBounds(entity).center * -1f)
            entity.enabled = false
            return entity
        }
        repeat(24) { shells.add(Shell(normalized("CartridgeComicV3", "SpentCartridge$it", 0.045f))) }
        repeat(32) {
            projectiles.add(Projectile(kit.box(root, "EnemyRedBolt$it", Vector3.ZERO,
                Vector3(0.048f), kit.red, mesh = kit.sphere).apply { enabled = false },
                kit.box(root, "EnemyEnergyTail$it", Vector3.ZERO, Vector3(0.012f), kit.red)
                    .apply { enabled = false }))
            playerBolts.add(Projectile(kit.box(root, "PlayerEnergy$it", Vector3.ZERO,
                Vector3(0.06f), kit.cyan, mesh = kit.sphere).apply { enabled = false },
                kit.box(root, "PlayerEnergyTail$it", Vector3.ZERO, Vector3(0.015f), kit.cyan)
                    .apply { enabled = false }))
        }
        lootView.build()
    }
    private fun refreshHeadPose(): Boolean {
        val sample = headSample ?: return false
        return headPoseCache.accept(sample.data.hmdPose.position, sample.data.hmdPose.rotation,
            sample.time, SystemClock.elapsedRealtime())
    }
    // Camera anchors follow the renderer but their ECS transform can remain at the parent origin.
    // Public HMD sample position is in container coordinates; null conversions consume that space.
    private fun eyeWorld(): Vector3 {
        refreshHeadPose()
        return headPoseCache.position ?: camera.convertPositionTo(Vector3.ZERO, null)
    }
    private fun physicalEyeInParent(): Vector3 {
        val world = eyeWorld()
        return root.getParent()?.convertPositionFrom(world, null) ?: world
    }
    private fun localPoint(world: Vector3): Vector3 {
        val parentPoint = root.getParent()?.convertPositionFrom(world, null) ?: world
        return SceneCoordinates.localPoint(parentPoint, root.pose().position, root.pose().quaternion)
    }
    private fun localRotation(world: Quat): Quat {
        val parentRotation = root.getParent()?.convertRotationFrom(world, null) ?: world
        return SceneCoordinates.inverse(root.pose().quaternion) * parentRotation
    }
    private fun eye() = localPoint(eyeWorld())
    private fun headRotation(): Quat {
        refreshHeadPose()
        return localRotation(headPoseCache.rotation ?: camera.convertRotationTo(Quat(), null))
    }
    private fun relocate(destination: Vector3, newYaw: Float = yaw) {
        val physical = eyeWorld()
        movementReference = physical
        val physicalInParent = root.getParent()?.convertPositionFrom(physical, null) ?: physical
        yaw = newYaw
        val rotation = EulerAngles(0f, yaw, 0f).toQuat()
        root.pose().setQuaternion(rotation)
            .setPosition(SceneCoordinates.placement(physicalInParent, destination, rotation))
        safePlayer = destination
    }
    private fun restart() {
        rules.restart()
        val checkpoint = 0f to -2f
        environment.reset(); weaponView.reset()
        playerHeight = EyeHeight.afterRestart(physicalEyeInParent().y, physicalEyeReferenceY, EyeHeight.DEFAULT)
        relocate(Vector3(checkpoint.first, V3Layout.heightAt(checkpoint.first, checkpoint.second),
            checkpoint.second) + Vector3(0f, playerHeight, 0f), 0f)
        triggerGate.reset(); gripGate.reset(); lastTrigger = false; lastGrip = false
        aGate.reset(); bGate.reset(); xGate.reset(); yGate.reset()
        teleportHeld = false; teleportTarget = null
        hitFlash = 0f; hurtClock = 0f
        feedback = ""; feedbackClock = 0f; lastWarnings.fill(0)
        shells.forEach { it.life = 0f; it.entity.enabled = false }
        projectiles.forEach { it.life = 0f; it.entity.enabled = false; it.tail.enabled = false }
        playerBolts.forEach { it.life = 0f; it.entity.enabled = false; it.tail.enabled = false }
        lootView.reset(); arsenalFx.reset()
        projectilePlayer = null
        tracers.forEach { it.active = false; it.entity.enabled = false }
        muzzleFx.reset(); sparkFx.reset(); dustFx.reset(); electricFx.reset()
        explosionFx.reset(); smokeFx.reset()
        coreFx.reset()
        killVfx.reset()
        impactVfx.reset()
        combatVfx.reset()
        reticle.enabled = false; hitMarkerParts.forEach { it.enabled = false }
        hurtEdges.forEach { it.enabled = false }
        hitMarkerShown = false; hurtShown = false; shownHurtSide = null
        teleportMarker.enabled = false; arc.forEach { it.enabled = false }
        snapArmed = false
        commands.clear()
        notice(if (checkpoint.second < -2f) "从区域检查点恢复" else "重新开始 · 清理入口", "reload")
    }
    fun submit(command: GameCommand) {
        require(command != GameCommand.EXIT) { "EXIT must be handled by the Stage navigator" }
        if (!closed && commands.size < 24) commands.offer(command)
    }
    fun onHudRendered(state: HudState, width: Int, height: Int) {
        val previous = hudRender
        hudRender = HudRender(state.rewardMenuOpen, state.paused, state.over, state.ready,
            state.rewardCursor, width, height)
        if (previous == null || previous.paused != state.paused ||
            previous.over != state.over || previous.ready != state.ready)
            Log.i(TAG, "GAME_MENU_RENDERED paused=${state.paused} over=${state.over} ready=${state.ready} size=${width}x$height")
    }
    private fun execute(command: GameCommand) {
        when (command) {
            GameCommand.RELOAD -> if (rules.reload()) notice("换弹中", "reload")
            GameCommand.SWITCH -> if (rules.switchWeapon()) notice(rules.message, "reload")
            GameCommand.INTERACT -> {
                val p = eye()
                val supply = nearbySupply(p)
                val drop = nearbyDrop(p)
                if (!rules.rewardMenuOpen && drop != null && root.scene != null) {
                    if (rules.collectDrop(drop.id, p.x, p.z, V3Layout.heightAt(p.x, p.z)) {
                        dropReachable(root.scene!!, p, it)
                    }) { notice(rules.message, "pickup"); haptic(100, 65); hudClock = 0.1f }
                } else if (!rules.talents.pending && !rules.rewardMenuOpen && supply != null) {
                    val target = GameRules.InteractionTarget(supply.label, supply.x, supply.z)
                    if (root.scene?.let { targetReachable(it, p, target) } == true) {
                        if (rules.collectSupply(supply.id)) haptic(80, 40)
                        notice(rules.message, "upgrade")
                    } else notice("补给被遮挡 · 绕到正面领取")
                } else if (rules.interact(p.x, p.z)) { notice(rules.message, "upgrade"); haptic(100, 80) }
                else notice(rules.message)
            }
            GameCommand.PAUSE -> {
                rules.togglePause()
                triggerGate.reset(); lastTrigger = false
                Log.i(TAG, "GAME_PAUSE paused=${rules.paused} over=${rules.over}")
                hudClock = 0.1f
            }
            GameCommand.RESTART -> restart()
            GameCommand.EXIT -> error("EXIT must be handled by the Stage navigator")
            GameCommand.OPEN_REWARDS -> rules.openRewards()
            GameCommand.CLOSE_REWARDS -> rules.closeRewards()
            GameCommand.CONFIRM_REWARD -> if (rules.confirmTalent()) { notice(rules.message, "upgrade"); haptic(100, 80) }
            GameCommand.SELECT_REWARD_0 -> rules.selectReward(0)
            GameCommand.SELECT_REWARD_1 -> rules.selectReward(1)
            GameCommand.SELECT_REWARD_2 -> rules.selectReward(2)
        }
    }
    private fun nearbySupply(player: Vector3) = V5Layout.supplies.filter {
        rules.supplyAvailable(it.id) && hypot(player.x - it.x, player.z - it.z) <= 2.3f
    }.minByOrNull { hypot(player.x - it.x, player.z - it.z) }
    fun update(context: SceneUpdateContext) {
        if (!initialized || closed || root.scene != context.scene) return
        if (scaleEvidenceFrame < 30) {
            scaleEvidenceFrame++
            if (scaleEvidenceFrame == 1 || scaleEvidenceFrame == 4 || scaleEvidenceFrame == 30)
                Log.i(TAG, "V6_SCALE_FRAME frame=$scaleEvidenceFrame eye=${eye()} physicalWorld=${eyeWorld()} anchorWorld=${camera.convertPositionTo(Vector3.ZERO, null)} physicalParent=${physicalEyeInParent()} parentOrigin=${root.getParent()?.convertPositionTo(Vector3.ZERO, null)} hmd=${headSample?.data?.hmdPose?.position}")
        }
        if (RunOptions.selfCheck && selfCheckFrame < 155) { runSelfCheck(context.scene); return }
        if (RunOptions.missionStress) { runMissionCheck(context.scene, context.deltaTime); return }
        if (RunOptions.stress) { runCombatStress(context.scene, context.deltaTime); return }
        if (active) repeat(24) { commands.poll()?.let(::execute) } else commands.clear()
        val now = SystemClock.elapsedRealtime()
        val poses = poseSample?.takeIf { now - it.time < 500L }?.data
        val rightPose = poses?.right?.takeIf { it.position != Vector3.ZERO }
        val leftPose = poses?.left?.takeIf { it.position != Vector3.ZERO }
        val tracked = rightPose != null
        val actions = actionSample?.takeIf { now - it.time < 150L }?.data
        val buttonsEnabled = active && tracked
        val a = aGate.sample(actions?.right?.let { if (it.aButtonPressed) 1f else 0f },
            actions != null, buttonsEnabled && !rules.paused)
        val b = bGate.sample(actions?.right?.let { if (it.bButtonPressed) 1f else 0f },
            actions != null, active)
        val x = xGate.sample(actions?.left?.let { if (it.xButtonPressed) 1f else 0f },
            actions != null, active)
        val grip = gripGate.sample(actions?.right?.gripValue, actions != null,
            active && tracked && !rules.paused && !rules.over)
        val y = yGate.sample(actions?.left?.let { if (it.yButtonPressed) 1f else 0f },
            actions != null, buttonsEnabled && !rules.paused && !rules.over)
        val trigger = triggerGate.sample(actions?.right?.triggerValue, actions != null,
            active && tracked && !rules.blocked)
        if (active) {
            if (x && !lastX) execute(GameCommand.PAUSE)
            else if (b && !lastB) {
                if (rules.rewardMenuOpen && !rules.paused) execute(GameCommand.CLOSE_REWARDS)
                else execute(GameCommand.PAUSE)
            }
        }
        if (active && tracked) {
            if (a && !lastA) execute(if (rules.rewardMenuOpen) GameCommand.CLOSE_REWARDS
                else if (rules.over) GameCommand.RESTART else GameCommand.RELOAD)
            if (grip && !lastGrip) {
                if (rules.rewardMenuOpen) rules.cycleReward() else execute(GameCommand.SWITCH)
            }
            if (y && !lastY) execute(GameCommand.INTERACT)
        }
        val frameDt = context.deltaTime.coerceIn(0f, 0.05f)
        val running = active && tracked && !rules.blocked
        val dt = if (running) frameDt else 0f
        val measuredPlayer = eye()
        val safe = safePlayer
        if (active && safe != null && hypot(measuredPlayer.x - safe.x, measuredPlayer.z - safe.z) > 0.001f) {
            val corrected = movePlayer(context.scene, safe,
                measuredPlayer.x - safe.x, measuredPlayer.z - safe.z)
            if (hypot(corrected.x - measuredPlayer.x, corrected.z - measuredPlayer.z) > 0.001f)
                relocate(Vector3(corrected.x, measuredPlayer.y, corrected.z))
        }
        val player = eye()
        if (running) {
            clock += dt
            val rightStick = actions?.right?.thumbstickValue
            val step = Locomotion.headRelativeStep(rightStick?.x ?: 0f, rightStick?.y ?: 0f,
                headRotation(), dt)
            if (step.x != 0f || step.z != 0f) {
                val destination = movePlayer(context.scene, player, step.x, step.z)
                relocate(Vector3(destination.x, EyeHeight.onNewFloor(player.y,
                    V3Layout.heightAt(player.x, player.z),
                    V3Layout.heightAt(destination.x, destination.z)), destination.z))
            }
            val stick = actions?.left?.thumbstickValue?.x ?: 0f
            if (abs(stick) < 0.25f) snapArmed = true
            if (abs(stick) > 0.7f && snapArmed) {
                teleportHeld = false; teleportTarget = null
                relocate(eye(), yaw + if (stick > 0f) 30f else -30f)
                snapArmed = false
            }
            updateTeleport(context.scene, if (actions != null && abs(stick) < 0.4f) leftPose else null,
                actions?.left?.thumbstickValue?.y ?: 0f)
            val movedPlayer = eye()
            val reloadBefore = rules.reloadRemaining
            advanceEnemies(context.scene, dt, movedPlayer)
            if (reloadBefore > 0f && rules.reloadRemaining == 0f) notice("弹匣就位", "reload_done")
            actors.forEachIndexed { i, actor -> actor.update(rules.enemies[i], movedPlayer, true, dt) }
            processEnemyAttacks(context.scene, movedPlayer)
            rules.enemies.forEach { enemy ->
                val warning = if (enemy.telegraph > 0f) 1 else 0
                if (warning > lastWarnings[enemy.slot])
                    soundAt("warning", actors[enemy.slot].attackOrigin(), movedPlayer)
                lastWarnings[enemy.slot] = warning
            }
        } else {
            if (active && !rules.paused && (rules.over || rules.complete))
                rules.tick(frameDt, player.x, player.z)
            teleportHeld = false; teleportTarget = null
            teleportMarker.enabled = false; arc.forEach { it.enabled = false }
        }
        if (active && tracked) {
            val position = localPoint(rightPose!!.position)
            val rotation = localRotation(rightPose.rotation)
            val direction = rotation.rotateVector(Vector3.BACK)
            val origin = weaponView.muzzle(rules.weapon, position, rotation)
            val hit = cast(context.scene, origin, direction, distance = weaponRange())
            val end = hit?.position ?: origin + direction * weaponRange()
            val muzzleBlocked = !muzzleClear(context.scene, eye(), origin, rules.weapon)
            reticle.enabled = running
            if (reticle.enabled) {
                val size = (end - eye()).length().coerceAtLeast(0.3f) *
                    (1f + weaponView.kick * 12f + if (rules.weapon == GameRules.Weapon.SHOTGUN) 0.4f else 0f)
                reticle.pose().setPosition(end - direction * 0.018f).setQuaternion(rotation)
                    .setScaleVector(Vector3(size, size, size))
            }
            if (running && trigger && (rules.weapon.automatic || !lastTrigger) && !muzzleBlocked) {
                val reloadBefore = rules.reloadRemaining
                if (rules.shoot()) {
                    fire(context.scene, position, rotation, origin)
                    hudClock = 0.1f
                } else if (reloadBefore == 0f && rules.reloadRemaining > 0f)
                    notice("弹匣已空 · 自动换弹", "reload")
            }
            weaponView.update(rules, position, rotation, dt, true)
        } else {
            reticle.enabled = false
            weaponView.update(rules, Vector3.ZERO, Quat(), 0f, false)
        }
        lastA = a; lastB = b; lastX = x; lastGrip = grip; lastY = y; lastTrigger = trigger
        if (!running) actors.forEachIndexed { i, actor ->
            val dying = !rules.enemies[i].alive && active && !rules.paused && !rules.rewardMenuOpen
            actor.update(rules.enemies[i], eye(), dying, if (dying) frameDt else 0f)
        }
        val visualActive = active && !rules.paused && !rules.rewardMenuOpen
        environment.update(rules, if (visualActive) frameDt else 0f, visualActive)
        lootView.update(rules, if (visualActive) frameDt else 0f)
        kit.updateVisibility(eye(), headRotation().rotateVector(Vector3.BACK), frameDt)
        updateEffects(context.scene, eye(), if (visualActive && tracked) frameDt else 0f)
        audio.update(frameDt, active && !rules.paused, V3Layout.zone(eye().x, eye().z),
            rules.aliveCount > 0 && !rules.over && !rules.complete)
        safePlayer = eye()
        if (trackingWasValid != tracked) { Log.i(TAG, "TRACKING valid=$tracked"); trackingWasValid = tracked }
        hudClock += frameDt
        val menuShown = rules.rewardMenuOpen || rules.paused || rules.over
        if (hudMenuShown != menuShown) {
            hudMenuShown = menuShown
            hudPanel?.pose()?.setPosition(if (hudMenuShown) Vector3(0f, 0.4f, -0.2f) else Vector3.ZERO)
            triggerGate.reset(); lastTrigger = false
            hudClock = 0.1f
        }
        if (hudClock >= 0.1f) {
            hudClock = 0f
            val state = "${provider.state}/${provider.supportState}"
            if (lastProviderState != state) { Log.i(TAG, "CONTROLLER_STATE $state"); lastProviderState = state }
            publish(state(tracked))
        }
    }
    private fun fire(scene: Scene, position: Vector3, rotation: Quat, origin: Vector3) {
        val weapon = rules.weapon
        weaponView.fired(weapon); audio.play(weapon.sound)
        haptic(if (weapon.kick > 0.07f) 180 else 95, if (weapon.kick > 0.07f) 70 else 28)
        rules.hearShot(eye().x, eye().z)
        muzzleFx.burst(origin, rotation)
        combatVfx.muzzle(origin, rotation, heavy = weapon == GameRules.Weapon.SHOTGUN,
            energy = rules.upgraded)
        if (weapon.ordinal >= GameRules.Weapon.ARC.ordinal) {
            arsenalFx.star(origin, rotation, weapon, if (weapon == GameRules.Weapon.PLASMA) 0.3f else 0.2f, eye())
            if (weapon.projectileSpeed > 0f) {
                shotIndex++
                spawnPlayerBolt(origin, rotation, weapon)
                return
            }
        }
        shotIndex++
        var damagedEnemy = false
        var strongestHead = false
        val heads = HeadshotFeedback()
        repeat(weapon.pellets) { i ->
            val spread = weapon.spread
            val angle = i * 2.39996f + shotIndex * 0.37f
            val radius = if (i == 0) 0f else sqrt(i.toFloat() / weapon.pellets) * spread
            val direction = rotation.rotateVector(Vector3(sin(angle) * radius, cos(angle) * radius, -1f).unit())
            val hit = cast(scene, origin, direction, distance = weaponRange())
            val end = hit?.position ?: origin + direction * weaponRange()
            spawnTracer(origin, end)
            if (weapon == GameRules.Weapon.RAIL) {
                arsenalFx.rail(origin, end)
                if (hit != null) arsenalFx.star(end - direction * 0.05f, rotation, weapon, 0.42f, eye())
            }
            val target = hit?.let { hitTargets[it.entity.id] }
            val shield = hit?.let { shieldTargets[it.entity.id] }
            val core = hit?.let { coreTargets[it.entity.id] }
            val actorSlot = core ?: shield ?: target?.first
            val impact = if (actorSlot != null) actors[actorSlot].impactPoint(end, direction) else end
            if (hit != null) {
                if (actorSlot != null) {
                    impactVfx.robotImpact(impact, direction, eye(), core != null || target?.second == true)
                    if (RunOptions.selfCheck || RunOptions.stress)
                        Log.i(TAG, "V10_ROBOT_IMPACT slot=$actorSlot hit=$end effect=$impact visibleOutside=true")
                } else if (i == 0) impactVfx.impact(end, hit.normal, direction, eye())
            }
            if (core != null && rules.hitEliteCore(core)) {
                actors[core].update(rules.enemies[core], eye(), true, 0f)
                damagedEnemy = true; strongestHead = true
                electricFx.burst(impact)
                notice(rules.message, "core_break")
                Log.i(TAG, "V6_CORE_HIT remaining=${rules.enemies[core].coreHealth} phase=${rules.enemies[core].phase} interrupted=true")
            } else if (shield != null && rules.hitShield(shield)) {
                actors[shield].update(rules.enemies[shield], eye(), true, 0f)
                sparkFx.burst(impact)
                if (i == 0) notice(rules.message, "metal")
            } else if (target != null && rules.enemies[target.first].alive) {
                val beforeHealth = rules.enemies[target.first].health
                val killed = rules.hit(target.first, target.second)
                if (killed) actors[target.first].update(rules.enemies[target.first], eye(), true, 0f)
                damagedEnemy = true; strongestHead = strongestHead || target.second
                sparkFx.burst(impact)
                if (rules.upgraded) electricFx.burst(impact)
                if (heads.register(target.first, target.second,
                        rules.enemies[target.first].health < beforeHealth)) {
                    impactVfx.headshot(impact, direction, eye())
                    headshotFxCount++
                    if (RunOptions.selfCheck || RunOptions.stress)
                        Log.i(TAG, "V11_HEADSHOT_FX slot=${target.first} position=$impact killed=$killed")
                }
            } else if (hit != null && target == null) {
                environment.barrelTargets[hit.entity.id]?.let { index ->
                    rules.hitBarrel(index, visible = { ax, az, bx, bz ->
                        visible(scene, Vector3(ax, V3Layout.heightAt(ax, az) + 0.8f, az),
                            Vector3(bx, V3Layout.heightAt(bx, bz) + 1f, bz), ignoreBarrels = true)
                    }).forEach { exploded ->
                        val barrel = rules.barrels[exploded]
                        val center = Vector3(barrel.x,
                            V3Layout.heightAt(barrel.x, barrel.z) + 0.7f, barrel.z)
                        explosionFx.burst(center); smokeFx.burst(center); sparkFx.burst(center)
                        impactVfx.explosion(center)
                        combatVfx.shockwave(center, eye())
                        val distance = hypot(eye().x - barrel.x, eye().z - barrel.z)
                        if (distance < 3f && visible(scene, center, eye(), ignoreBarrels = true)) {
                            rules.damagePlayer((25f * (1f - distance / 3f)).toInt()); hurt(center)
                        }
                        notice("油桶爆炸 · 范围伤害", "explosion")
                    }
                }
                if (environment.barrelTargets[hit.entity.id] == null) {
                    if (hit.normal.y > 0.65f) dustFx.burst(end + hit.normal * 0.035f)
                    else {
                        sparkFx.burst(end + hit.normal * 0.025f)
                        if (i == 0) soundAt("metal", end, eye())
                    }
                }
            }
        }
        if (damagedEnemy) {
            hitFlash = if (strongestHead) 0.22f else 0.12f
            hitColor = if (strongestHead) kit.cyan else kit.orange
        }
        val defeated = processDefeats(eye())
        if (heads.hasHeadshot) {
            val played = audio.play("headshot")
            if (played) headshotAudioCount++
            haptic(120, 48)
            if (RunOptions.selfCheck || RunOptions.stress)
                Log.i(TAG, "V11_HEADSHOT_AUDIO played=$played perShot=true")
        } else if (!defeated && damagedEnemy) audio.play("hit")
        if (weapon.ordinal >= GameRules.Weapon.ARC.ordinal) return
        val shell = shells[shellIndex++ % shells.size]
        shell.entity.pose().setPosition(position + rotation.rotateVector(Vector3(0.13f, 0.03f, -0.22f)))
            .setQuaternion(rotation)
        shell.life = 0.9f
        shell.velocity = rotation.rotateVector(Vector3(1.5f, 0.9f, 0.4f)); shell.entity.enabled = true
    }
    private fun processDefeats(player: Vector3): Boolean {
        val events = rules.drainDefeats()
        if (events.isEmpty()) return false
        events.forEach { event ->
            val center = Vector3(event.x, V3Layout.heightAt(event.x, event.z) +
                if (event.kind == GameRules.Kind.ELITE) 1.55f else 1.05f, event.z)
            if (DefeatGeometry.particlesAllowed(center, player))
                coreFx.burst(DefeatGeometry.particlePoint(center, player))
            killVfx.burst(center, event.kind == GameRules.Kind.ELITE, event.headshot, player)
            if (event.kind == GameRules.Kind.ELITE) combatVfx.shockwave(center, player, elite = true)
            actors[event.slot].update(rules.enemies[event.slot], player, true, 0f)
            defeatFxCount++
        }
        events.sortedBy { hypot(it.x - player.x, it.z - player.z) }.take(3).forEach {
            soundAt("core_break", Vector3(it.x, V3Layout.heightAt(it.x, it.z) + 1f, it.z), player)
        }
        val cue = when {
            events.any { it.kind == GameRules.Kind.ELITE } -> "kill_elite"
            events.any { it.headshot } -> "kill_head"
            else -> "kill"
        }
        val played = audio.play(cue)
        if (rules.aliveCount == 0 && !rules.complete) notice(rules.message)
        hitFlash = 0.32f; hitColor = kit.red
        haptic(if (cue == "kill_elite") 160 else 110, if (cue == "kill_elite") 90 else 42)
        Log.i(TAG, "DEFEAT_FEEDBACK count=${events.size} cue=$cue audio=$played totalFx=$defeatFxCount combo=${events.last().combo}")
        return true
    }
    private fun soundAt(name: String, position: Vector3, player: Vector3) {
        val offset = position - player
        val headRotation = headRotation()
        val right = headRotation.rotateVector(Vector3(1f, 0f, 0f))
        val pan = (offset.x * right.x + offset.z * right.z) / offset.length().coerceAtLeast(0.1f)
        audio.playAt(name, offset.length(), pan)
    }
    private fun processEnemyAttacks(scene: Scene, player: Vector3) {
        rules.attacks.forEach { attack ->
            val origin = actors[attack.slot].attackOrigin()
            val enemy = rules.enemies[attack.slot]
            val shoulder = Vector3(enemy.x, V3Layout.heightAt(enemy.x, enemy.z) +
                if (enemy.kind == GameRules.Kind.DRONE) enemy.kind.hoverHeight else 1.2f, enemy.z)
            if (!visible(scene, shoulder, origin) || !visible(scene, origin, player)) return@forEach
            if (attack.melee) {
                soundAt("baton", origin, player)
                if (CombatGeometry.meleeArc(enemy.x, enemy.z, attack.aimX, attack.aimZ, player.x, player.z)) {
                    if (rules.damagePlayer(attack.damage) > 0) hurt(shoulder)
                }
            } else {
                soundAt("shot", origin, player)
                val center = Vector3(attack.aimX, attack.aimY, attack.aimZ)
                val direction = (center - origin).unit()
                val rotation = EulerAngles(asin(direction.y.coerceIn(-1f, 1f)) * 57.29578f,
                    atan2(-direction.x, -direction.z) * 57.29578f, 0f).toQuat()
                combatVfx.muzzle(origin, rotation, heavy = enemy.kind == GameRules.Kind.ELITE, energy = true)
                val side = Vector3(-direction.z, 0f, direction.x)
                repeat(attack.projectiles) { i ->
                    val offset = if (i == 0) 0f else if (i == 1) -1.25f else 1.25f
                    spawnProjectile(origin, center + side * offset, attack.damage,
                        enemy.kind.projectileSpeed, enemy.slot, enemy.generation, scene)
                }
            }
        }
    }
    private fun updateTeleport(scene: Scene, pose: ControllerPose?, forward: Float) {
        if (pose == null) { teleportHeld = false; teleportTarget = null }
        val held = pose != null && forward > 0.65f
        if (held) {
            teleportTarget = null; arc.forEach { it.enabled = false }
            val origin = localPoint(pose!!.position)
            val direction = localRotation(pose.rotation).rotateVector(Vector3.BACK)
            var start = origin
            for (i in arc.indices) {
                val t = (i + 1) * 0.12f
                val end = origin + direction * (t * 5f) + Vector3(0f, -3.2f * t * t, 0f)
                val delta = end - start
                val hit = cast(scene, start, delta.unit(), WORLD_ONLY, delta.length())
                segment(arc[i], start, hit?.position ?: end, 0.015f); arc[i].enabled = true
                if (hit != null || end.y <= 0.01f) {
                    val landing = hit?.position ?: end
                    val player = eye()
                    val floor = V3Layout.heightAt(landing.x, landing.z)
                    if (abs(landing.y - floor) <= 0.38f && V3Layout.canTeleport(player.x, player.z,
                            landing.x, landing.z, rules.gateOpen))
                        if (bodyClear(scene, Vector3(landing.x, floor + playerHeight, landing.z)))
                            teleportTarget = Vector3(landing.x,
                                EyeHeight.onNewFloor(player.y, V3Layout.heightAt(player.x, player.z), floor),
                                landing.z)
                    break
                }
                start = end
            }
            teleportMarker.enabled = true
            val landing = teleportTarget
            teleportMarker.pose().setPosition(landing?.let { it + Vector3(0f, 0.025f, 0f) } ?: start)
            teleportMarker.components[ModelComponent::class.java]?.materials?.set(0, if (landing != null) kit.cyan else kit.red)
        } else {
            if (teleportHeld) teleportTarget?.let {
                relocate(it); notice("瞬移落点已到达", "teleport"); haptic(65, 35)
            }
            teleportMarker.enabled = false; arc.forEach { it.enabled = false }; teleportTarget = null
        }
        teleportHeld = held
    }
    private fun spawnProjectile(origin: Vector3, target: Vector3, damage: Int, speed: Float = 5f,
        sourceSlot: Int = -1, sourceGeneration: Int = -1, scene: Scene? = root.scene) {
        if (scene != null && !boltOriginClear(scene, origin)) {
            if (RunOptions.selfCheck || RunOptions.stress) Log.i(TAG, "V12_BOLT_ORIGIN_BLOCKED slot=$sourceSlot origin=$origin")
            return
        }
        val p = projectiles[projectileIndex++ % projectiles.size]
        val distance = (target - origin).length()
        p.velocity = (target - origin).unit() * speed
        val direction = p.velocity.unit()
        p.entity.pose().setPosition(origin).setEulerAngles(EulerAngles(
            asin(direction.y.coerceIn(-1f, 1f)) * 57.29578f,
            atan2(-direction.x, -direction.z) * 57.29578f, 0f))
        p.position = origin; p.origin = origin
        p.sourceSlot = sourceSlot; p.sourceGeneration = sourceGeneration
        p.damage = damage; p.life = (distance / speed + 0.5f).coerceIn(0.5f, 8f); p.entity.enabled = true
        p.tail.enabled = false; p.fresh = true
    }
    private fun updateEffects(scene: Scene, player: Vector3, dt: Float) {
        hitFlash = (hitFlash - dt).coerceAtLeast(0f); hurtClock = (hurtClock - dt).coerceAtLeast(0f)
        feedbackClock = (feedbackClock - dt).coerceAtLeast(0f)
        if (reticleFlashing != (hitFlash > 0f)) {
            reticleFlashing = hitFlash > 0f
            reticleParts.forEach { it.components[ModelComponent::class.java]?.materials?.set(0,
                if (reticleFlashing) kit.orange else kit.white) }
        }
        val showHit = hitFlash > 0f
        if (showHit != hitMarkerShown) {
            hitMarkerParts.forEach { it.enabled = showHit }
            hitMarkerShown = showHit
        }
        if (showHit && hitColor !== hitMarkerColor) {
            hitMarkerParts.forEach { it.components[ModelComponent::class.java]?.materials?.set(0, hitColor) }
            hitMarkerColor = hitColor
        }
        val showHurt = hurtClock > 0f && dt > 0f
        if (hurtShown != showHurt || showHurt && shownHurtSide != hurtSide) {
            hurtShown = showHurt; shownHurtSide = if (showHurt) hurtSide else null
            hurtEdges.forEachIndexed { index, entity ->
                entity.enabled = showHurt && index == hurtSide.ordinal
            }
        }
        muzzleFx.update(dt); sparkFx.update(dt); dustFx.update(dt); electricFx.update(dt)
        explosionFx.update(dt); smokeFx.update(dt)
        coreFx.update(dt)
        killVfx.update(dt)
        impactVfx.update(dt)
        combatVfx.update(dt)
        arsenalFx.update(dt)
        updateTracers(dt)
        updatePlayerBolts(scene, player, dt)
        shells.forEach { shell ->
            if (shell.life <= 0f || dt == 0f) return@forEach
            shell.life -= dt
            shell.entity.pose().setPosition(shell.entity.pose().position + shell.velocity * dt)
                .setEulerAngles(EulerAngles(shell.life * 480f, shell.life * 330f, shell.life * 210f))
            shell.velocity += Vector3(0f, -5f * dt, 0f)
            shell.entity.enabled = shell.life > 0f
        }
        // Only continuous, active motion is swept. Pause/tracking gaps and teleport do not
        // draw a fictitious hit path through the space between disconnected positions.
        val previousPlayer = projectilePlayer?.takeIf {
            dt > 0f && (player - it).length() <= 1f
        } ?: player
        projectilePlayer = if (dt > 0f) player else null
        projectiles.forEach { p ->
            if (p.life <= 0f || dt == 0f) {
                p.tail.enabled = false
                return@forEach
            }
            val start = p.position
            val delta = p.velocity * minOf(dt, p.life)
            val end = start + delta
            val worldHit = sweptHit(scene, start, end, boltShape, WORLD_ONLY)
            // Newly emitted bolts start after this frame's player movement.
            val bodyStart = if (p.fresh) player else previousPlayer
            p.fresh = false
            val playerHit = CombatGeometry.movingCapsuleHit(
                start.x, start.y, start.z, end.x, end.y, end.z,
                bodyStart.x, bodyStart.z,
                V3Layout.heightAt(bodyStart.x, bodyStart.z) - CombatGeometry.BOLT_RADIUS,
                bodyStart.y + CombatGeometry.EYE_TO_CROWN + CombatGeometry.BOLT_RADIUS,
                player.x, player.z,
                V3Layout.heightAt(player.x, player.z) - CombatGeometry.BOLT_RADIUS,
                player.y + CombatGeometry.EYE_TO_CROWN + CombatGeometry.BOLT_RADIUS)
            if (worldHit != null && (playerHit == null || worldHit.distance <= playerHit * delta.length() + 0.001f)) {
                p.life = 0f; sparkFx.burst(worldHit.position)
            } else if (playerHit != null) {
                val contact = start + delta * playerHit
                val body = bodyStart + (player - bodyStart) * playerHit
                val center = Vector3(body.x, contact.y.coerceIn(
                    V3Layout.heightAt(body.x, body.z) + 0.1f, body.y), body.z)
                val clear = visible(scene, contact, center)
                val applied = if (clear) rules.damagePlayer(p.damage) else 0
                if (applied > 0) {
                    hurt(p.origin); hudClock = 0.1f
                    if (RunOptions.selfCheck || RunOptions.stress)
                        Log.i(TAG, "V9_PROJECTILE_DAMAGE applied=$applied health=${rules.health} source=${p.sourceSlot}:${p.sourceGeneration} origin=${p.origin} contact=$contact occlusion=clear")
                }
                p.life = 0f
            } else { p.life -= dt; p.position = end; p.entity.pose().setPosition(end) }
            p.entity.enabled = p.life > 0f
            p.tail.enabled = p.life > 0f
            if (p.tail.enabled) segment(p.tail, end - p.velocity.unit() *
                minOf(0.75f, 0.25f + p.velocity.length() * 0.015f), end, 0.012f)
        }
    }
    private fun hurt(source: Vector3) {
        val player = eye()
        val forward = headRotation().rotateVector(Vector3.BACK)
        hurtSide = DamageDirection.side(source.x - player.x, source.z - player.z, forward.x, forward.z)
        hurtClock = 0.35f; audio.play("hurt"); haptic(120, 80)
    }
    /** Debug regression exercises the real pooled projectile update and native world casts. */
    private fun clearTransientEffects() {
        (projectiles + playerBolts).forEach { it.life = 0f; it.entity.enabled = false; it.tail.enabled = false }
        projectilePlayer = null
    }
    private fun verifyProjectileDamage(scene: Scene) {
        rules.restart(); clearTransientEffects()
        val standing = Vector3(0f, EyeHeight.DEFAULT, -2f)
        spawnProjectile(Vector3(-0.5f, 1.69f, -2f), Vector3(0.5f, 1.69f, -2f), 8, 18f)
        updateEffects(scene, standing, 0.05f)
        check(rules.health == 92) { "Crown hit did not damage: ${rules.health}" }
        clearTransientEffects()
        spawnProjectile(Vector3(-0.5f, 1.9f, -2f), Vector3(0.5f, 1.9f, -2f), 8, 18f)
        updateEffects(scene, standing, 0.05f)
        check(rules.health == 92) { "Above-head miss damaged player" }
        clearTransientEffects()
        val crouched = Vector3(0f, 0.85f, -2f)
        spawnProjectile(Vector3(-0.5f, 0.9f, -2f), Vector3(0.5f, 0.9f, -2f), 8, 18f)
        updateEffects(scene, crouched, 0.05f)
        check(rules.health == 84) { "Crouch crown hit did not damage" }
        clearTransientEffects()
        spawnProjectile(Vector3(0f, 1f, -2f), Vector3(0f, 1f, -3f), 8, 0.02f)
        updateEffects(scene, Vector3(-0.4f, EyeHeight.DEFAULT, -2f), 0.001f)
        updateEffects(scene, Vector3(0.4f, EyeHeight.DEFAULT, -2f), 0.05f)
        check(rules.health == 76) { "Moving body crossed bolt without damage" }
        clearTransientEffects()
        val wall = V3Layout.structures.first { it.name == "RepairWallWest" }
        spawnProjectile(Vector3(0f, 1f, wall.z), Vector3(-4f, 1f, wall.z), 8, 100f)
        updateEffects(scene, Vector3(-4f, EyeHeight.DEFAULT, wall.z), 0.05f)
        check(rules.health == 76) { "Enemy bolt passed through repair wall" }
        clearTransientEffects()
        repeat(2) {
            spawnProjectile(Vector3(-0.5f, 1f, -2f), Vector3(0.5f, 1f, -2f), 8, 18f)
        }
        updateEffects(scene, standing, 0.05f)
        check(rules.health == 60) { "Same-frame hits lost damage: ${rules.health}" }
        Log.i(TAG, "V9_PROJECTILE_PASS standingCrown=true aboveHeadMiss=true crouchedCrown=true movingSweep=true wallOcclusion=true repeatedHits=true health=60")
        rules.restart(); clearTransientEffects()
    }
    private fun verifyArsenalNative(scene: Scene) {
        fun clear() {
            (projectiles + playerBolts).forEach { it.life = 0f; it.entity.enabled = false; it.tail.enabled = false }
            projectilePlayer = null
        }
        rules.restart(); clear()
        val obstacles = listOf(V3Layout.covers.first { it.name in V3Layout.shippingContainers },
            V3Layout.covers.first { it.name == "ServiceRock" },
            V3Layout.structures.first { it.name == "RepairWallWest" })
        obstacles.forEach { obstacle ->
            val start = Vector3(obstacle.x + obstacle.width / 2f + 1f, 0.8f, obstacle.z)
            val end = Vector3(obstacle.x - obstacle.width / 2f - 1f, 0.8f, obstacle.z)
            val native = checkNotNull(sweptHit(scene, start, end, boltShape, WORLD_ONLY))
            spawnProjectile(start, end, 8, 100f, 2, 77, scene)
            updateEffects(scene, Vector3(end.x, EyeHeight.DEFAULT, end.z), 0.25f)
            check(rules.health == 100 && projectiles.none { it.life > 0f })
            Log.i(TAG, "V12_WORLD_BOLT_PASS obstacle=${obstacle.name} native=${native.entity.getName()} radius=true health=100")
            clear()
        }
        val thinStart = Vector3(-36f, 8f, -55f)
        val thinEnd = Vector3(-32f, 8f, -55f)
        spawnProjectile(thinStart, thinEnd, 8, 100f, 2, 77, scene)
        updateEffects(scene, Vector3(-33.72f, 9f, -55f), 0.05f)
        check(rules.health == 100 && projectiles.none { it.life > 0f }) { "Inflated body damaged through thin wall" }
        clear()
        val grazeStart = Vector3(-36f, 8f, -54.48f)
        val grazeEnd = Vector3(-32f, 8f, -54.48f)
        check(cast(scene, grazeStart, Vector3(1f, 0f, 0f), WORLD_ONLY, 4f) == null)
        check(sweptHit(scene, grazeStart, grazeEnd, boltShape, WORLD_ONLY)?.entity?.getName() == "DebugThinWallV12")
        spawnProjectile(Vector3(-34f, 8f, -55f), thinEnd, 8, 100f, scene = scene)
        check(projectiles.none { it.life > 0f }) { "Bolt spawned inside wall" }
        GameRules.Weapon.entries.forEach { gun ->
            val ammo = rules.ammo
            val allowed = muzzleClear(scene, thinStart, Vector3(-34f, 8f, -55f), gun)
            if (allowed) rules.shoot()
            check(!allowed && rules.ammo == ammo) { "Embedded muzzle consumed ammo: $gun" }
        }
        check(!muzzleClear(scene, grazeStart, Vector3(-34f, 8f, -54.48f), GameRules.Weapon.PLASMA))
        spawnProjectile(thinStart, thinEnd, 8, 100f, 2, 77, scene)
        val shot = projectiles.first { it.life > 0f }
        // Deliberately stale visual pose must never replace the authoritative logical start.
        shot.entity.pose().setPosition(thinEnd)
        updateEffects(scene, Vector3(-32f, 9f, -55f), 0.05f)
        check(rules.health == 100 && shot.life == 0f)
        clear()
        val barrel = rules.barrels[0]
        val barrelStart = Vector3(barrel.x + 1f, 0.55f, barrel.z)
        val barrelEnd = Vector3(barrel.x - 1f, 0.55f, barrel.z)
        check(sweptHit(scene, barrelStart, barrelEnd, boltShape, WORLD_ONLY) != null)
        spawnProjectile(barrelStart, barrelEnd, 8, 100f, scene = scene)
        updateEffects(scene, Vector3(barrelEnd.x, EyeHeight.DEFAULT, barrelEnd.z), 0.05f)
        check(rules.health == 100)
        clear()
        // Ownership is obtained through the actual collectible path.
        listOf(GameRules.Weapon.ARC, GameRules.Weapon.RAIL, GameRules.Weapon.PLASMA).forEachIndexed { i, gun ->
            rules.drops.add(GameRules.Drop(i, gun, 0f, -2f, 0f))
            check(rules.collectDrop(i, 0f, -2f, 0f) { dropReachable(scene, Vector3(0f, EyeHeight.DEFAULT, -2f), it) })
        }
        repeat(5) {
            val gun = rules.weapon
            check(rules.owns(gun))
            rules.retryCheckpoint()
            rules.tick(0.3f) { false }
            check(rules.shoot()) { "Gun fixture not ready: $gun ammo=${rules.ammo} reload=${rules.reloadRemaining}" }
            val before = rules.ammo
            if (gun.projectileSpeed > 0f) {
                val origin = Vector3(0f, 1.5f, -2f)
                spawnPlayerBolt(origin, Quat(), gun)
                val bolt = playerBolts.first { it.life > 0f }
                val initialYSpeed = bolt.velocity.y
                updateEffects(scene, Vector3(0f, EyeHeight.DEFAULT, -2f), 0.05f)
                check(bolt.position.z < origin.z)
                check(if (gun.gravity > 0f) bolt.velocity.y < initialYSpeed else bolt.velocity.y == initialYSpeed)
                check(before == rules.ammo)
                Log.i(TAG, "V12_GUN_BALLISTICS_PASS gun=$gun position=${bolt.position} velocity=${bolt.velocity} ammo=$before audio=${audio.play(gun.sound)}")
                clear()
            } else check(audio.play(gun.sound)) { "Arsenal SoundPool not ready for $gun" }
            check(rules.switchWeapon())
        }
        check(rules.ownedWeapons.size == 5)
        Log.i(TAG, "V12_ARSENAL_PASS solidWorld=true thinWall=true grazeRadius=true originGuard=true stalePose=true barrel=true ownedSwitch=5 projectiles=arcAndGravityPlasma")
        rules.restart(); clear(); lootView.reset()
        listOf(GameRules.Weapon.ARC, GameRules.Weapon.RAIL, GameRules.Weapon.PLASMA).forEachIndexed { i, gun ->
            val p = eye()
            rules.drops.add(GameRules.Drop(i, gun, p.x, p.z, V3Layout.heightAt(p.x, p.z)))
            execute(GameCommand.INTERACT)
            check(rules.owns(gun) && rules.drops.last().collected)
        }
        rules.enemies.forEach { it.health = 0 }
        rules.enemies[0].apply { health = 10000; x = 24f; z = -3f; motion = GameRules.Motion.IDLE }
        actors.forEachIndexed { i, actor -> actor.update(rules.enemies[i], eye(), true, 0f) }
        Log.i(TAG, "V12_PICKUP_COMMAND_PASS count=3 sameFloor=true nativeLOS=true")
    }
    private fun verifyArsenalHit(scene: Scene, gun: GameRules.Weapon) {
        repeat(5) { if (rules.weapon != gun) execute(GameCommand.SWITCH) }
        check(rules.weapon == gun)
        rules.retryCheckpoint()
        val enemy = rules.enemies[0]
        val before = enemy.health
        val origin = Vector3(24f, 1.05f, -1.5f)
        check(muzzleClear(scene, origin, origin, gun))
        check(cast(scene, origin, Vector3.BACK)?.let { hitTargets[it.entity.id] } == (0 to false))
        val ammo = rules.ammo
        check(rules.shoot())
        fire(scene, origin, Quat(), origin)
        if (gun.projectileSpeed > 0f) repeat(5) { updateEffects(scene, eye(), 0.05f) }
        check(enemy.health < before && rules.ammo == ammo - 1) {
            "Native gun hit failed: $gun hp=$before->${enemy.health} ammo=${rules.ammo}"
        }
        clearTransientEffects()
        Log.i(TAG, "V12_NATIVE_GUN_HIT_PASS gun=$gun damage=${before - enemy.health} ammoOnce=true")
        rules.retryCheckpoint()
        val wallOrigin = Vector3(-36f, 8f, -55f)
        val wallRotation = EulerAngles(0f, -90f, 0f).toQuat()
        check(cast(scene, wallOrigin, Vector3(1f, 0f, 0f))?.entity?.getName() == "DebugThinWallV12")
        check(rules.shoot())
        fire(scene, wallOrigin, wallRotation, wallOrigin)
        if (gun.projectileSpeed > 0f) {
            repeat(6) { updateEffects(scene, eye(), 0.05f) }
            check(playerBolts.none { it.life > 0f }) { "New gun projectile crossed thin wall: $gun" }
        }
        clearTransientEffects()
        Log.i(TAG, "V12_NATIVE_GUN_WALL_PASS gun=$gun rayOrSweep=blocked")
    }
    private fun verifyPlasmaCover(scene: Scene) {
        val wall = V3Layout.structures.first { it.name == "RepairWallWest" }
        val enemy = rules.enemies[0]
        enemy.health = 10000; enemy.x = -4f; enemy.z = wall.z
        val center = Vector3(wall.x + 0.2f, 0.9f, wall.z)
        val player = Vector3(-4f, EyeHeight.DEFAULT, wall.z)
        rules.retryCheckpoint()
        check(!visible(scene, center, Vector3(enemy.x, 0.9f, enemy.z)))
        plasmaBlast(scene, center, player, 120)
        check(enemy.health == 10000 && rules.health == rules.maxHealth) { "Plasma blast crossed repair wall" }
        enemy.x = wall.x + 0.8f
        plasmaBlast(scene, center, Vector3(0f, EyeHeight.DEFAULT, -2f), 120)
        check(enemy.health < 10000) { "Clear plasma blast did no damage" }
        Log.i(TAG, "V12_PLASMA_COVER_PASS behindWall=blocked sameSide=damaged playerBehindWall=protected")
        rules.restart(); clearTransientEffects()
    }
    private fun notice(text: String, sound: String? = null) {
        feedback = text; feedbackClock = 2.3f; sound?.let { audio.play(it) }
    }
    /** World-only casts use a distinct mask, avoiding self hits on enemy firing origins. */
    private fun cast(scene: Scene, origin: Vector3, direction: Vector3,
        mask: UInt = SceneKit.GROUP, distance: Float = 120f): CollisionCastResult? =
        scene.rayCast(origin, direction, distance.coerceAtLeast(0.001f), CollisionCastHitMode.NEAREST,
            CollisionGroup(mask), root).results.firstOrNull()
    /** Include radius and the center ray: native mesh casts and solid box casts share one mask. */
    private fun sweptHit(scene: Scene, start: Vector3, end: Vector3, shape: ShapeResource,
        mask: UInt): CollisionCastResult? {
        val delta = end - start
        val length = delta.length().coerceAtLeast(0.001f)
        val direction = if (delta.length() > 0.00001f) delta.unit() else Vector3.BACK
        val convex = scene.convexCast(shape, start, Quat(), direction, length,
            CollisionCastHitMode.NEAREST, CollisionGroup(mask), root).results.firstOrNull()
        val center = cast(scene, start, direction, mask, length)
        return listOfNotNull(convex, center).minByOrNull { it.distance }
    }
    private fun boltOriginClear(scene: Scene, origin: Vector3, shape: ShapeResource = boltShape): Boolean =
        !kit.insideWorldBox(origin) &&
            sweptHit(scene, origin, origin + Vector3(0f, 0.001f, 0f), shape, WORLD_ONLY) == null
    private fun muzzleClear(scene: Scene, player: Vector3, origin: Vector3, weapon: GameRules.Weapon) =
        visible(scene, player, origin) &&
            boltOriginClear(scene, origin, if (weapon == GameRules.Weapon.PLASMA) plasmaShape else boltShape)
    private fun dropReachable(scene: Scene, player: Vector3, drop: GameRules.Drop) =
        visible(scene, player, Vector3(drop.x, drop.floor + 0.55f, drop.z))
    private fun nearbyDrop(player: Vector3): GameRules.Drop? {
        val scene = root.scene ?: return null
        return rules.nearestDrop(player.x, player.z, V3Layout.heightAt(player.x, player.z)) {
            dropReachable(scene, player, it)
        }
    }
    private fun spawnPlayerBolt(origin: Vector3, rotation: Quat, weapon: GameRules.Weapon) {
        val scene = root.scene ?: return
        val shape = if (weapon == GameRules.Weapon.PLASMA) plasmaShape else boltShape
        if (!boltOriginClear(scene, origin, shape)) return
        val p = playerBolts[playerBoltIndex++ % playerBolts.size]
        p.position = origin; p.origin = origin; p.weapon = weapon
        p.damage = rules.bulletDamage
        val direction = if (weapon == GameRules.Weapon.ARC)
            Vector3(sin(shotIndex * 2.39996f) * weapon.spread,
                cos(shotIndex * 2.39996f) * weapon.spread, -1f).unit() else Vector3.BACK
        p.velocity = rotation.rotateVector(direction) * weapon.projectileSpeed
        p.life = weapon.range / weapon.projectileSpeed
        p.entity.pose().setPosition(origin).setQuaternion(rotation)
            .setScaleVector(Vector3(if (weapon == GameRules.Weapon.PLASMA) 0.24f else 0.048f))
        p.entity.components[ModelComponent::class.java]?.materials?.set(0,
            if (weapon == GameRules.Weapon.PLASMA) kit.magenta else kit.cyan)
        p.tail.components[ModelComponent::class.java]?.materials?.set(0,
            if (weapon == GameRules.Weapon.PLASMA) kit.lime else kit.cyan)
        p.entity.enabled = true; p.tail.enabled = false
    }
    private fun updatePlayerBolts(scene: Scene, player: Vector3, dt: Float) {
        playerBolts.forEach { p ->
            if (p.life <= 0f || dt <= 0f) { p.tail.enabled = false; return@forEach }
            val weapon = checkNotNull(p.weapon)
            val step = minOf(dt, p.life)
            val gravity = Vector3(0f, -weapon.gravity, 0f)
            val end = p.position + p.velocity * step + gravity * (0.5f * step * step)
            val shape = if (weapon == GameRules.Weapon.PLASMA) plasmaShape else boltShape
            val hit = sweptHit(scene, p.position, end, shape, SceneKit.GROUP)
            if (hit != null) {
                p.life = 0f
                val incoming = p.velocity.unit()
                val rotation = EulerAngles(asin(incoming.y.coerceIn(-1f, 1f)) * 57.29578f,
                    atan2(-incoming.x, -incoming.z) * 57.29578f, 0f).toQuat()
                val target = hitTargets[hit.entity.id]
                val core = coreTargets[hit.entity.id]
                val shield = shieldTargets[hit.entity.id]
                val actorSlot = core ?: shield ?: target?.first
                val impact = if (actorSlot != null) actors[actorSlot].impactPoint(hit.position, incoming)
                    else hit.position + hit.normal * 0.04f
                arsenalFx.star(impact, rotation, weapon, 0.3f, player)
                var damaged = false
                if (core != null) damaged = rules.hitEliteCore(core, p.damage)
                else if (shield != null) rules.hitShield(shield, p.damage)
                else if (target != null && rules.enemies[target.first].alive) {
                    val before = rules.enemies[target.first].health
                    rules.hit(target.first, target.second, p.damage)
                    damaged = rules.enemies[target.first].health < before
                    if (target.second && damaged) {
                        impactVfx.headshot(impact, incoming, player); headshotFxCount++
                        if (audio.play("headshot")) headshotAudioCount++
                    }
                }
                if (actorSlot != null) {
                    actors[actorSlot].update(rules.enemies[actorSlot], player, true, 0f)
                    impactVfx.robotImpact(impact, incoming, player, target?.second == true || core != null)
                } else {
                    impactVfx.impact(impact, hit.normal, incoming, player)
                    sparkFx.burst(impact)
                }
                environment.barrelTargets[hit.entity.id]?.let { index ->
                    rules.hitBarrel(index, p.damage, visible = { ax, az, bx, bz ->
                        visible(scene, Vector3(ax, V3Layout.heightAt(ax, az) + 0.8f, az),
                            Vector3(bx, V3Layout.heightAt(bx, bz) + 1f, bz), ignoreBarrels = true)
                    }).forEach { barrel ->
                        val b = rules.barrels[barrel]
                        val center = Vector3(b.x, V3Layout.heightAt(b.x, b.z) + 0.8f, b.z)
                        explosionFx.burst(center); impactVfx.explosion(center); audio.play("explosion")
                    }
                }
                if (weapon == GameRules.Weapon.PLASMA)
                    plasmaBlast(scene, hit.position + hit.normal * 0.15f, player, p.damage)
                if (damaged) { hitFlash = 0.18f; hitColor = kit.cyan; audio.play("hit") }
                processDefeats(player)
                hudClock = 0.1f
            } else {
                p.position = end; p.velocity += gravity * step; p.life -= step
                p.entity.pose().setPosition(end)
                p.tail.enabled = p.life > 0f
                if (p.tail.enabled) segment(p.tail, end - p.velocity.unit() *
                    if (weapon == GameRules.Weapon.PLASMA) 0.45f else 0.7f, end,
                    if (weapon == GameRules.Weapon.PLASMA) 0.035f else 0.015f)
            }
            p.entity.enabled = p.life > 0f
            if (!p.entity.enabled) p.tail.enabled = false
        }
    }
    private fun plasmaBlast(scene: Scene, center: Vector3, player: Vector3, damage: Int) {
        arsenalFx.star(center, Quat(), GameRules.Weapon.PLASMA, 0.65f, player)
        impactVfx.explosion(center); combatVfx.shockwave(center, player)
        audio.play("plasma_impact")
        rules.enemies.filter { it.alive }.forEach { enemy ->
            val target = Vector3(enemy.x, V3Layout.heightAt(enemy.x, enemy.z) +
                enemy.kind.hoverHeight + if (enemy.kind == GameRules.Kind.DRONE) 0f else 0.9f, enemy.z)
            val distance = (target - center).length()
            if (distance < 3.3f && visible(scene, center, target))
                rules.hit(enemy.slot, false, (damage * 0.9f * (1f - distance / 3.3f)).toInt())
        }
        val distance = (player - center).length()
        if (distance < 2.5f && visible(scene, center, player) &&
            rules.damagePlayer((18f * (1f - distance / 2.5f)).toInt()) > 0) hurt(center)
    }
    private fun weaponRange() = rules.weapon.range
    private fun advanceEnemies(scene: Scene, dt: Float, player: Vector3) {
        rules.enemies.forEachIndexed { index, enemy ->
            previousEnemyX[index] = enemy.x; previousEnemyZ[index] = enemy.z
            previousEnemyGeneration[index] = enemy.generation
        }
        rules.tick(dt, player.x, player.z, playerY = player.y) { enemy ->
            visible(scene, Vector3(enemy.x, V3Layout.heightAt(enemy.x, enemy.z) +
                if (enemy.kind == GameRules.Kind.DRONE) enemy.kind.hoverHeight else 1.4f, enemy.z), player)
        }
        rules.enemies.forEachIndexed { i, enemy ->
            if (enemy.alive && enemy.motion == GameRules.Motion.RUN) {
                val origin = EnemyMotionOrigin.point(previousEnemyGeneration[i],
                    previousEnemyX[i], previousEnemyZ[i], enemy)
                val before = Vector3(origin.first,
                    V3Layout.heightAt(origin.first, origin.second) + playerHeight, origin.second)
                val desiredX = enemy.x; val desiredZ = enemy.z
                val next = movePlayer(scene, before, enemy.x - before.x, enemy.z - before.z)
                enemy.x = next.x; enemy.z = next.z
                rules.reportMotion(enemy, before.x, before.z, desiredX, desiredZ, dt)
                if (hypot(next.x - before.x, next.z - before.z) < 0.0001f)
                    enemy.motion = GameRules.Motion.IDLE
            }
        }
    }
    private fun visible(scene: Scene, start: Vector3, end: Vector3, ignoreBarrels: Boolean = false): Boolean {
        val delta = end - start
        if (delta.length() <= 0.001f) return true
        if (!ignoreBarrels) {
            val hit = scene.rayCast(start, delta.unit(), delta.length(), CollisionCastHitMode.NEAREST,
                CollisionGroup(WORLD_ONLY), root).results.firstOrNull()
            return hit == null || hit.distance >= delta.length() - 0.025f
        }
        return scene.rayCast(start, delta.unit(), delta.length(), CollisionCastHitMode.ALL,
            CollisionGroup(WORLD_ONLY), root).results.none {
            it.distance < delta.length() - 0.025f &&
                (!ignoreBarrels || !environment.barrelTargets.containsKey(it.entity.id))
        }
    }
    private fun targetReachable(scene: Scene, player: Vector3, target: GameRules.InteractionTarget): Boolean {
        if (abs(V3Layout.heightAt(player.x, player.z) - V3Layout.heightAt(target.x, target.z)) > 0.5f)
            return false
        val end = Vector3(target.x, V3Layout.heightAt(target.x, target.z) + 1f, target.z)
        val delta = end - player
        if (delta.length() < 0.025f) return true
        val targetCollider = when (target.label) {
            "发电机" -> "GeneratorCollision"
            "宝箱" -> "LootChestCollision"
            else -> ""
        }
        return scene.rayCast(player, delta.unit(), delta.length(), CollisionCastHitMode.ALL,
            CollisionGroup(WORLD_ONLY), root).results.none {
            it.distance < delta.length() - 0.025f && it.entity.getName() != targetCollider
        }
    }
    private fun isWall(hit: CollisionCastResult): Boolean =
        abs(hit.normal.y) < 0.7f && hit.entity.getName() !in
            listOf("WastelandGround", "ReactorDeck", "ReactorRamp")

    private fun bodyClear(scene: Scene, head: Vector3, ignoreBarrels: Boolean = false): Boolean {
        val floor = V3Layout.heightAt(head.x, head.z)
        val origin = Vector3(head.x, floor + EyeHeight.CAPSULE / 2f + 0.03f, head.z)
        return scene.convexCast(playerCapsule, origin, capsuleRotation, Vector3.BACK, 0.001f,
            CollisionCastHitMode.ALL, CollisionGroup(WORLD_ONLY), root).results.none {
                isWall(it) && (!ignoreBarrels || !environment.barrelTargets.containsKey(it.entity.id))
            }
    }

    /** Capsule sweep clips against exact building surfaces, then slides once along the wall. */
    private fun movePlayer(scene: Scene, head: Vector3, dx: Float, dz: Float): Vector3 {
        val target = V3Layout.slide(head.x, head.z, dx, dz, rules.gateOpen)
        val delta = Vector3(target.first - head.x, 0f, target.second - head.z)
        if (delta.length() < 0.0001f) return head
        val floor = V3Layout.heightAt(head.x, head.z)
        val origin = Vector3(head.x, floor + EyeHeight.CAPSULE / 2f + 0.03f, head.z)
        val hit = scene.convexCast(playerCapsule, origin, capsuleRotation, delta.unit(), delta.length(),
            CollisionCastHitMode.ALL, CollisionGroup(WORLD_ONLY), root).results
            .filter(::isWall).minByOrNull { it.distance }
        if (hit == null) return clearMovementEnd(scene, head, Vector3(target.first, head.y, target.second))
        val travel = (hit.distance - 0.02f).coerceIn(0f, delta.length())
        val reached = head + delta.unit() * travel
        val remaining = delta - delta.unit() * travel
        val normal = Vector3(hit.normal.x, 0f, hit.normal.z).unit()
        val slide = remaining - normal * (remaining.x * normal.x + remaining.z * normal.z)
        if (slide.length() < 0.001f) return clearMovementEnd(scene, head, reached)
        val blocked = scene.convexCast(playerCapsule,
            Vector3(reached.x, floor + EyeHeight.CAPSULE / 2f + 0.03f, reached.z), capsuleRotation, slide.unit(), slide.length(),
            CollisionCastHitMode.ALL, CollisionGroup(WORLD_ONLY), root).results
            .filter(::isWall).minByOrNull { it.distance }
        val distance = blocked?.let { (it.distance - 0.02f).coerceAtLeast(0f) } ?: slide.length()
        val p = reached + slide.unit() * distance
        return clearMovementEnd(scene, head, if (V3Layout.walkable(p.x, p.z, rules.gateOpen)) p else reached)
    }
    private fun clearMovementEnd(scene: Scene, start: Vector3, end: Vector3): Vector3 {
        if (bodyClear(scene, end)) return end
        var safe = 0f; var blocked = 1f
        repeat(6) {
            val fraction = (safe + blocked) * 0.5f
            if (bodyClear(scene, start + (end - start) * fraction)) safe = fraction else blocked = fraction
        }
        return start + (end - start) * safe
    }
    private fun spawnTracer(start: Vector3, end: Vector3) {
        val tracer = tracers[tracerIndex++ % tracers.size]
        val length = (end - start).length()
        if (length < 0.01f) {
            tracer.active = false; tracer.entity.enabled = false
            return
        }
        tracer.start = start; tracer.end = end; tracer.progress = 0f
        tracer.active = true; tracer.fresh = true; tracer.entity.enabled = true
        segment(tracer.entity, start, start + (end - start).unit() * minOf(0.22f, length), 0.006f)
    }
    private fun updateTracers(dt: Float) {
        if (dt <= 0f) return
        tracers.forEach { t ->
            if (t.active) {
                if (t.fresh) { t.fresh = false; return@forEach }
                val delta = t.end - t.start; val length = delta.length()
                t.progress += dt * 180f
                val tip = minOf(t.progress + 0.22f, length)
                if (t.progress >= length - 0.001f) {
                    t.active = false; t.entity.enabled = false
                    return@forEach
                }
                segment(t.entity, t.start + delta.unit() * minOf(t.progress, length),
                    t.start + delta.unit() * tip, 0.006f)
                t.active = t.progress < length; t.entity.enabled = t.active
            }
        }
    }
    private fun segment(e: Entity, start: Vector3, end: Vector3, width: Float) {
        val d = end - start; val length = d.length().coerceAtLeast(0.001f)
        e.pose().setPosition((start + end) * 0.5f).setScaleVector(Vector3(width, width, length))
            .setEulerAngles(EulerAngles(asin((d.y / length).coerceIn(-1f, 1f)) * 57.29578f,
                atan2(-d.x, -d.z) * 57.29578f, 0f))
    }
    private fun Vector3.unit() = this * (1f / length().coerceAtLeast(0.0001f))
    private fun state(tracked: Boolean): HudState {
        val status = when {
            rules.over -> "生命耗尽 · 按 A 重新开始"
            rules.complete -> "100 个敌人全部击破 · 挑战完成"
            !active -> "离开游戏，战斗已停止"
            !tracked -> "戴好头显并唤醒右手柄，战斗计时停止"
            rules.paused -> "暂停 · X / B 继续 · 菜单内可退出游戏"
            rules.rewardMenuOpen -> "选择一项升级 · Grip 切换 · Y 确认 · B 返回"
            nearbyDrop(eye()) != null -> "${nearbyDrop(eye())!!.weapon.label} · 靠近按 Y 拾取"
            rules.talents.pending -> "战区升级可领取 · Y 打开选择 · 射击、换弹和切枪照常"
            rules.reloadRemaining > 0f -> "换弹中 · ${ceil(rules.reloadRemaining * 10) / 10} 秒"
            feedbackClock > 0f -> feedback
            rules.startRemaining > 0f -> "守卫启动 · ${ceil(rules.startRemaining).toInt()} 秒"
            hurtClock > 0f -> "${hurtSide.label}来袭 · 侧移躲避或利用掩体"
            nearbySupply(eye()) != null -> "${nearbySupply(eye())!!.label} · Y 领取生命和弹药"
            else -> "右摇杆移动 · 左摇杆转向 / 瞬移 · 扳机射击 · A 换弹 · I / 右 Grip 切枪 · Y 拾取"
        }
        val ammoHint = when {
            rules.reloadRemaining > 0f -> "换弹 ${ceil(rules.reloadRemaining * 10) / 10}s"
            rules.ammo == 0 -> "空弹匣 · A 换弹"
            rules.ammo <= maxOf(1, rules.weapon.magazine / 5) -> "残弹 ${rules.ammo} · A 换弹"
            else -> "${rules.ammo}/${rules.weapon.magazine}"
        }
        return HudState(status, rules.objective, rules.health, rules.ammo, rules.weapon.magazine,
            rules.weapon.label + if (rules.upgraded) " ⚡" else "", rules.aliveCount, rules.kills,
            rules.score, rules.paused, rules.over, rules.complete, true, rules.reloadRemaining > 0f,
            rules.killFeedback, ammoHint, rules.maxHealth, rules.talents.pending,
            rules.rewardMenuOpen, rules.talents.offers, rules.talents.cursor, rules.talents.acquired.toList(),
            if (rules.reloadRemaining > 0f) 1f - rules.reloadRemaining / rules.reloadSeconds else 0f,
            rules.kills,
            nearbyDrop(eye())?.let { "${it.weapon.label} · Y 拾取" } ?: "",
            rules.ownedWeapons.joinToString(" · ") { it.label })
    }
    private fun verifySpawnGeometry(scene: Scene, wave: String) {
        rules.enemies.filter { it.alive }.forEach { enemy ->
            val point = Vector3(enemy.x, V3Layout.heightAt(enemy.x, enemy.z) + playerHeight, enemy.z)
            if (!bodyClear(scene, point)) {
                val failure = "$wave slot=${enemy.slot} point=$point"
                geometryFailures.add(failure)
                Log.e(TAG, "SPAWN_GEOMETRY_BLOCKED $failure")
            }
        }
        Log.i(TAG, "SPAWN_GEOMETRY_CHECK wave=$wave count=${rules.aliveCount} failures=${geometryFailures.size}")
    }
    private fun targetApproaches(scene: Scene, target: GameRules.InteractionTarget): List<Vector3> =
        (1..4).flatMap { ring ->
            (0..23).map { index ->
                val angle = index * PI.toFloat() / 12f
                val x = target.x + cos(angle) * (0.5f + ring * 0.4f)
                val z = target.z + sin(angle) * (0.5f + ring * 0.4f)
                Vector3(x, V3Layout.heightAt(x, z) + playerHeight, z)
            }.filter { point ->
                V3Layout.walkable(point.x, point.z, rules.gateOpen) &&
                    navigationGrid?.walkable(point.x, point.z) != false &&
                    bodyClear(scene, point) && targetReachable(scene, point, target)
            }
        }
    private fun findTargetApproach(scene: Scene, target: GameRules.InteractionTarget): Vector3? =
        targetApproaches(scene, target).firstOrNull()
    /** Bounded full-battle lifecycle and physical map traversal; scripted damage is explicit. */
    private fun runMissionCheck(scene: Scene, frameDt: Float) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (missionCheckStart == 0L) missionCheckStart = now
            check(now - missionCheckStart < RunOptions.stressDurationMs + 180_000L) {
                "Mission could not finish its final cycle within the bounded budget"
            }
            val dt = frameDt.coerceIn(1f / 144f, 0.05f)
            if (missionPhase == -2) {
                restart()
                missionPlayer = Vector3(0f, playerHeight, -2f)
                nextMissionPhase(now)
            }
            check(now - missionPhaseStart < 120_000L) {
                "Mission phase timed out phase=$missionPhase player=$missionPlayer goal=$missionGoal"
            }
            advanceEnemies(scene, dt, missionPlayer)
            check(rules.attacks.size <= 1 &&
                rules.enemies.count { it.alive && it.telegraph > 0f } <= 2)
            actors.forEachIndexed { i, actor -> actor.update(rules.enemies[i], missionPlayer, true, dt) }
            check(rules.aliveCount <= GameRules.ACTIVE_ENEMIES && rules.spawned <= 100)
            if (rules.startRemaining == 0f) {
                if (missionFrames % 20 == 0 && !rules.complete) {
                    rules.enemies.firstOrNull { it.alive }?.let { rules.hit(it.slot, true, 10000) }
                    processDefeats(missionPlayer)
                }
                val waypoints = listOf(0f to -32f, 17f to -46f, 0f to -66f, 0f to -86f)
                if (missionPhase < waypoints.lastIndex) {
                    val target = waypoints[(missionPhase + 1).coerceAtLeast(0)]
                    if (missionWalk(scene, target.first, target.second, dt)) nextMissionPhase(now)
                }
                if (rules.complete) {
                    check(rules.kills == 100 && rules.spawned == 100 && rules.aliveCount == 0)
                    Log.i(TAG, "MISSION_STRESS_PASS kills=100 emitted=100 scriptedDamage=true frames=$missionFrames")
                    RunOptions.missionStress = false; restart(); return
                }
            }
            relocate(missionPlayer)
            environment.update(rules, dt)
            updateEffects(scene, missionPlayer, dt)
            audio.update(dt, true, V3Layout.zone(missionPlayer.x, missionPlayer.z), rules.aliveCount > 0)
            missionFrames++
            if (missionFrames % 300 == 0)
                Log.i(TAG, "MISSION_STRESS phase=$missionPhase cycles=$missionCycles player=$missionPlayer nav=${missionNavigation.completed}/${missionNavigation.submitted}")
        } catch (error: Exception) {
            Log.e(TAG, "MISSION_STRESS_FAIL phase=$missionPhase", error)
            RunOptions.missionStress = false; restart()
        }
    }
    private fun scriptedClear(player: Vector3) {
        rules.enemies.filter { it.alive }.forEach { rules.hit(it.slot, true, 10000) }
        processDefeats(player)
    }
    private fun verifySustainedBattle(scene: Scene) {
        restart(); rules.tick(2.5f)
        val used = mutableSetOf<Pair<Float, Float>>()
        repeat(100) {
            repeat(120) { if (rules.aliveCount == 0) rules.tick(0.1f) { false } }
            check(!rules.complete && rules.aliveCount in 1..GameRules.ACTIVE_ENEMIES && rules.spawned <= 100)
            verifySpawnGeometry(scene, "sustained-${rules.kills}")
            rules.enemies.filter { it.alive }.forEach { enemy ->
                used.add(enemy.x to enemy.z)
                actors[enemy.slot].update(enemy, eye(), true, 0.02f)
                check(actors[enemy.slot].verifyVisible(enemy)) { "Invisible live actor ${enemy.slot}" }
            }
            val victim = rules.enemies.first { it.alive }
            rules.hit(victim.slot, true, 10000); processDefeats(eye())
            repeat(16) { advanceEnemies(scene, 0.05f, Vector3(0f, playerHeight, -2f)) }
        }
        check(rules.complete && rules.kills == 100 && rules.spawned == 100 && rules.aliveCount == 0)
        check(used.size >= 12 && geometryFailures.isEmpty())
        Log.i(TAG, "V10_BATTLE_PASS kills=100 emitted=100 liveCap=${GameRules.ACTIVE_ENEMIES} positions=${used.size} actorVisibility=true")
        restart(); rules.tick(2.5f)
        while (rules.kills < 20) {
            repeat(120) { if (rules.aliveCount == 0) rules.tick(0.1f) { false } }
            rules.hit(rules.enemies.first { it.alive }.slot, true, 10000)
            rules.tick(0.8f) { false }
        }
        processDefeats(eye())
        verifyRewardSelection()
        restart(); rules.tick(2.5f)
    }
    private fun verifyRewardSelection() {
        check(rules.talents.pending && rules.talents.offers.size == 3)
        // Waiting rewards cannot take away gameplay. Only an explicitly opened menu does.
        rules.tick(0.3f)
        check(rules.shoot())
        check(rules.reload())
        check(rules.openRewards())
        check(!rules.shoot() && !rules.reload())
        val weapon = rules.weapon
        val first = rules.talents.selected
        repeat(3) { check(rules.cycleReward() && rules.weapon == weapon) }
        check(rules.talents.selected == first)
        val acquired = rules.talents.acquired.size
        check(rules.confirmTalent() && !rules.talents.pending)
        rules.tick(rules.reloadSeconds)
        check(rules.talents.acquired.size == acquired + 1)
        Log.i(TAG, "RUN_REWARD_PASS count=${rules.talents.acquired.size} talent=${first!!.name}")
    }
    private fun nextMissionPhase(now: Long) {
        missionPhase++; missionPhaseStart = now
        missionRoute = null; missionRouteIndex = 0; missionRoutePending = false
        missionMotionWait = 0f
        missionNavigation.reset()
        Log.i(TAG, "MISSION_PHASE phase=$missionPhase player=$missionPlayer")
    }
    private fun missionWalk(scene: Scene, x: Float, z: Float, dt: Float, exact: Boolean = false,
        approaches: List<Pair<Float, Float>>? = null): Boolean {
        if (missionRoute == null) {
            if (!missionRoutePending) {
                val point = if (exact) x to z else missionNavigation.spawnPoint(x, z, rules.gateOpen)
                missionGoal = Vector3(point.first, V3Layout.heightAt(point.first, point.second) + playerHeight,
                    point.second)
                if (approaches == null) check(bodyClear(scene, missionGoal)) { "Route goal blocked: $missionGoal" }
                else check(approaches.isNotEmpty()) { "No physically clear target approaches" }
                missionNavigation.request(0, missionPlayer.x, missionPlayer.z,
                    missionGoal.x, missionGoal.z, rules.gateOpen, approaches)
                missionRoutePending = true
            }
            missionNavigation.take(0, missionGoal.x, missionGoal.z, rules.gateOpen)?.let {
                check(it.isNotEmpty()) { "No physical route phase=$missionPhase from=$missionPlayer to=$missionGoal" }
                missionRoute = it
                missionGoal = Vector3(it.last().first,
                    V3Layout.heightAt(it.last().first, it.last().second) + playerHeight, it.last().second)
            }
            return false
        }
        val route = missionRoute!!
        while (missionRouteIndex < route.size && hypot(route[missionRouteIndex].first - missionPlayer.x,
                route[missionRouteIndex].second - missionPlayer.z) < 0.06f) missionRouteIndex++
        if (missionRouteIndex == route.size) {
            Log.i(TAG, "MISSION_TRAVERSAL_PASS phase=$missionPhase goal=$missionGoal actual=$missionPlayer")
            return true
        }
        val target = route[missionRouteIndex]
        val dx = target.first - missionPlayer.x; val dz = target.second - missionPlayer.z
        val length = hypot(dx, dz).coerceAtLeast(0.0001f)
        val step = minOf(length, dt * 3f)
        val moved = movePlayer(scene, missionPlayer, dx / length * step, dz / length * step)
        check(bodyClear(scene, moved)) { "Traversal capsule entered solid geometry: $moved" }
        missionMotionWait = if (hypot(moved.x - missionPlayer.x, moved.z - missionPlayer.z) < step * 0.1f)
            missionMotionWait + dt else 0f
        if (missionMotionWait >= 0.3f) {
            missionNavigation.block(0, missionPlayer.x + dx / length * 0.7f,
                missionPlayer.z + dz / length * 0.7f, confirmedStatic = true)
            missionRoute = null; missionRouteIndex = 0; missionRoutePending = false; missionMotionWait = 0f
            Log.i(TAG, "MISSION_ROUTE_REPLAN phase=$missionPhase point=$missionPlayer blocked=$target")
        }
        missionPlayer = Vector3(moved.x, V3Layout.heightAt(moved.x, moved.z) + playerHeight, moved.z)
        return false
    }
    private fun runCombatStress(scene: Scene, frameDt: Float) {
        val begin = SystemClock.elapsedRealtimeNanos()
        try {
            if (stressStart == 0L) stressStart = SystemClock.elapsedRealtime()
            val elapsed = SystemClock.elapsedRealtime() - stressStart
            if (stressFrame == 0 || rules.over || rules.complete) {
                restart(); rules.tick(2.5f)
                if (stressNavigationPassed) acquireStressArsenal(scene)
                if (!stressNavigationPassed) {
                    rules.enemies.drop(1).forEach { it.health = 0 }
                    rules.enemies[0].x = -4f; rules.enemies[0].z = V3Layout.REPAIR_Z
                    val probeY = V3Layout.structures.first { it.name == "RepairWallWest" }.height * 0.7f
                    check(!visible(scene, Vector3(-4f, probeY, V3Layout.REPAIR_Z),
                        Vector3(stressRoomStart.x, probeY, stressRoomStart.z))) {
                        "Repair side does not occlude a ray below its measured height: $stressRoomStart"
                    }
                }
            }
            if (RunOptions.stressDurationMs > 120_000L && stressNavigationPassed) {
                if (stressFrame % 600 == 300 && !rules.paused) {
                    rules.togglePause(); stressPauseRemaining = 45
                } else if (rules.paused && --stressPauseRemaining <= 0) {
                    rules.togglePause(); stressPauseCycles++
                    Log.i(TAG, "COMBAT_PAUSE_RESUME_PASS cycles=$stressPauseCycles frame=$stressFrame")
                }
            }
            val frameStep = frameDt.coerceIn(1f / 144f, 0.05f)
            val dt = if (rules.paused) 0f else frameStep
            val player = if (!stressNavigationPassed) {
                val fraction = if (elapsed >= 25_000L)
                    (1f - cos((elapsed - 25_000L) / 1000f * 0.3f)) * 0.5f else 0f
                val p = stressRoomStart + (stressRoomEnd - stressRoomStart) * fraction
                Vector3(p.x, playerHeight, p.z)
            }
                else Vector3(0f, playerHeight, -2f)
            if (!stressNavigationPassed) check(bodyClear(scene, player)) { "Room target is not physically clear: $player" }
            advanceEnemies(scene, dt, player)
            actors.forEachIndexed { i, actor -> actor.update(rules.enemies[i], player, !rules.paused, dt) }
            check(rules.aliveCount <= GameRules.ACTIVE_ENEMIES && rules.spawned <= 100)
            rules.enemies.filter { it.alive }.forEach {
                check(actors[it.slot].verifyVisible(it)) { "Live actor hidden slot=${it.slot}" }
            }
            check(rules.attacks.size <= 1 && rules.enemies.count { it.alive && it.telegraph > 0f } <= 2) {
                "Enemy attack scheduler exceeded its budget"
            }
            if (!stressFirstRoomPassed && elapsed >= 25_000L) {
                val enemy = rules.enemies[0]
                check(hypot(enemy.x - stressRoomStart.x, enemy.z - stressRoomStart.z) < 2.7f) {
                    "Enemy failed to enter repair room: x=${enemy.x} z=${enemy.z}"
                }
                stressFirstRoomPassed = true
                Log.i(TAG, "NAV_ROOM_ENTRY_PASS enemy=${enemy.x},${enemy.z} nav=${navigation.completed}/${navigation.submitted}")
            }
            if (!stressNavigationPassed && elapsed >= 50_000L) {
                val enemy = rules.enemies[0]
                check(hypot(enemy.x - player.x, enemy.z - player.z) < 2.7f) {
                    "Enemy failed to track moving room target: x=${enemy.x} z=${enemy.z} player=$player"
                }
                check(enemy.lastKnownX == player.x && enemy.lastKnownZ == player.z)
                stressNavigationPassed = true
                Log.i(TAG, "NAV_DEVICE_PASS movingRoomTarget=$player enemy=${enemy.x},${enemy.z} nav=${navigation.completed}/${navigation.submitted}")
                restart(); rules.tick(2.5f)
                acquireStressArsenal(scene)
            }
            if (stressNavigationPassed && stressFrame % 4 == 0) {
                rules.enemies.firstOrNull { it.alive }?.let { enemy ->
                    val target = Vector3(enemy.x, V3Layout.heightAt(enemy.x, enemy.z) +
                        if (enemy.kind == GameRules.Kind.DRONE) enemy.kind.hoverHeight else 1.05f, enemy.z)
                    val origin = player + Vector3(0.2f, -0.35f, -0.4f)
                    val direction = (target - origin).unit()
                    val rotation = EulerAngles(asin(direction.y) * 57.29578f,
                        atan2(-direction.x, -direction.z) * 57.29578f, 0f).toQuat()
                    if (muzzleClear(scene, player, origin, rules.weapon) && rules.shoot()) {
                        fire(scene, origin, rotation, origin); stressShots++
                        stressGunShots[rules.weapon.ordinal]++
                    }
                }
            }
            if (stressNavigationPassed) processEnemyAttacks(scene, player)
            if (stressFrame % 240 == 0) rules.switchWeapon()
            if (rules.ammo == 0) rules.reload()
            environment.update(rules, dt, !rules.paused)
            lootView.update(rules, dt)
            weaponView.update(rules, player, Quat(), dt, !rules.paused)
            kit.updateVisibility(player, headRotation().rotateVector(Vector3.BACK), frameStep)
            updateEffects(scene, player, dt)
            audio.update(frameStep, !rules.paused, V3Layout.zone(player.x, player.z), true)
            stressFrame++
            val millis = (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000f
            stressWorst = maxOf(stressWorst, millis)
            if (stressFrame % 144 == 0) {
                rules.enemies.filter { it.alive }.forEach {
                    check(actors[it.slot].verifyBounds(it)) { "Live actor bounds invalid slot=${it.slot}" }
                }
                check(kit.white.valid && kit.orange.valid)
                check(muzzleFx.verifyEmission() && sparkFx.verifyEmission() && coreFx.verifyEmission())
                check(muzzleFx.verifyIdle() && sparkFx.verifyIdle() && coreFx.verifyIdle())
                check(audio.verifyLoops()) { "Dedicated music/ambience player stopped unexpectedly" }
                Log.i(TAG, "COMBAT_STRESS frame=$stressFrame shots=$stressShots live=${rules.aliveCount} emitted=${rules.spawned} kills=${rules.kills} visible=true worstMs=$stressWorst nav=${navigation.completed}/${navigation.submitted} nativeBursts=${sparkFx.bursts} killsFx=$defeatFxCount target=${rules.enemies[0].x},${rules.enemies[0].z}")
            }
            if (SystemClock.elapsedRealtime() - stressStart >= RunOptions.stressDurationMs) {
                if (RunOptions.stressDurationMs > 120_000L) check(stressPauseCycles > 0)
                check(stressGunShots.all { it > 0 }) { "Arsenal soak missed a gun: ${stressGunShots.toList()}" }
                Log.i(TAG, "COMBAT_STRESS_PASS durationMs=${SystemClock.elapsedRealtime() - stressStart} frames=$stressFrame shots=$stressShots worstMs=$stressWorst audioFailures=${audio.failedPlays} loopFailures=${audio.loopFailures} pauseCycles=$stressPauseCycles gunShots=${stressGunShots.toList()}")
                RunOptions.stress = false; restart()
            }
        } catch (error: Exception) {
            Log.e(TAG, "COMBAT_STRESS_FAIL", error)
            RunOptions.stress = false; restart()
        }
    }
    private fun acquireStressArsenal(scene: Scene) {
        listOf(GameRules.Weapon.ARC, GameRules.Weapon.RAIL, GameRules.Weapon.PLASMA).forEachIndexed { i, gun ->
            rules.drops.add(GameRules.Drop(i, gun, 0f, -2f, 0f))
            check(rules.collectDrop(i, 0f, -2f, 0f) {
                dropReachable(scene, Vector3(0f, EyeHeight.DEFAULT, -2f), it)
            })
        }
    }
    private fun runSelfCheck(scene: Scene) {
        try {
            audio.update(1f / 72f, true, V3Layout.Zone.ENTRY, true)
            when (selfCheckFrame) {
                0 -> {
                    val probe = GameRules(navigation).apply { tick(2.5f) }
                    var worst = 0f
                    val begin = SystemClock.elapsedRealtimeNanos()
                    repeat(120) {
                        val tickStart = SystemClock.elapsedRealtimeNanos()
                        probe.tick(1f / 72f, 0f, -60f)
                        worst = maxOf(worst, (SystemClock.elapsedRealtimeNanos() - tickStart) / 1_000_000f)
                    }
                    Log.i(TAG, "NAV_CHECK frames=120 millis=${(SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000f} worstMs=$worst requests=${probe.pathRequests}")
                    rules.restart(); rules.tick(2.5f)
                    check(sparkFx.verifyIdle() && coreFx.verifyIdle())
                    actors.forEachIndexed { i, a -> a.update(rules.enemies[i], Vector3.ZERO, true, 0.1f) }
                    environment.update(rules, 0f)
                    check(rules.aliveCount == 3)
                    verifySpawnGeometry(scene, "entry")
                    actors.forEach { Log.i(TAG, "V3_WEAPON_PROBE ${it.weaponProbe()}") }
                    animationReference = actors[0].animationProbe()
                    rules.enemies[0].motion = GameRules.Motion.RUN
                    actors[0].update(rules.enemies[0], Vector3.ZERO, true, 0.1f)
                }
                2 -> {
                    Log.i(TAG, "V3_SELFCHECK ink/unlit shadow overrides models=${kit.verifyShadowOverrides()} PASS")
                    actors.filter { rules.enemies[it.slot].alive }.forEach {
                        Log.i(TAG, "V3_WEAPON_SETTLED ${it.weaponProbe()}")
                        check(it.verifySocket()) { "Generated animated muzzle is invalid: ${it.weaponProbe()}" }
                    }
                    check(actors[0].animationProbe() != animationReference) {
                        "skeletal Walking clip did not advance"
                    }
                    Log.i(TAG, "V3_SELFCHECK skeletal joint transforms changed PASS")
                    val distant = cast(scene, Vector3(-28f, 8f, 0f), Vector3.BACK, distance = 120f)
                    check(distant?.entity?.id == rangeProbe?.id) { "120m raycast=$distant" }
                    check(!visible(scene, Vector3(0f, 1f, V3Layout.REPAIR_Z), Vector3(-4f, 1f, V3Layout.REPAIR_Z)))
                    check(bodyClear(scene, Vector3(0f, playerHeight, -2f)))
                    val stopped = movePlayer(scene, Vector3(0f, playerHeight, V3Layout.REPAIR_Z), -4f, 0f)
                    val wall = V3Layout.structures.first { it.name == "RepairWallWest" }
                    check(stopped.x > wall.x + wall.width / 2f) { "player capsule crossed wall: $stopped" }
                    Log.i(TAG, "V3_SELFCHECK 60m hit + wall occlusion + capsule sweep PASS")
                    val e = rules.enemies[1]
                    val ground = V3Layout.heightAt(e.x, e.z)
                    val head = cast(scene, Vector3(e.x, ground + 1.62f, e.z + 0.8f), Vector3.BACK)
                    val body = cast(scene, Vector3(e.x, ground + 0.95f, e.z + 0.8f), Vector3.BACK)
                    check(head?.let { hitTargets[it.entity.id] } == (1 to true)) { "head=$head" }
                    check(body?.let { hitTargets[it.entity.id] } == (1 to false)) { "body=$body" }
                    val headsBefore = headshotFxCount
                    val audioBefore = headshotAudioCount
                    val headOrigin = Vector3(e.x, ground + 1.62f, e.z + 0.8f)
                    check(rules.shoot()); fire(scene, headOrigin, Quat(), headOrigin)
                    check(e.health == 48 && rules.ammo == 23)
                    check(headshotFxCount == headsBefore + 1 &&
                        headshotAudioCount == audioBefore + 1 && impactVfx.activeHeadshots() == 1)
                    actors[1].update(e, Vector3.ZERO, true, 0f)
                    val fxPoint = Vector3(e.x, ground + 1f, e.z)
                    muzzleFx.burst(fxPoint); sparkFx.burst(fxPoint)
                    dustFx.burst(fxPoint); electricFx.burst(fxPoint)
                    updateEffects(scene, Vector3.ZERO, 0.07f)
                    repeat(20) { cycle ->
                        sparkFx.burst(fxPoint); spawnTracer(fxPoint, fxPoint + Vector3(0f, 0f, -0.5f))
                        updateEffects(scene, Vector3.ZERO, 0.05f)
                        check(sparkFx.activeCount() > 0 && sparkFx.verifyEmission() && tracers.any { it.active })
                    }
                    repeat(32) { cycle ->
                        val material = if (cycle % 2 == 0) kit.red else kit.white
                        reticleParts.forEach {
                            it.components[ModelComponent::class.java]?.materials?.set(0, material)
                        }
                        check(kit.white.valid && kit.red.valid)
                    }
                    Log.i(TAG, "V3_SELFCHECK hitboxes + motion + particles + material stress PASS")
                    fire(scene, headOrigin, Quat(), headOrigin)
                    check(!e.alive && headshotFxCount == headsBefore + 2 &&
                        headshotAudioCount == audioBefore + 2)
                    Log.i(TAG, "V11_HEADSHOT_PASS nonfatal=true lethal=true fx=2 audio=2 nativeHeadRay=true")
                    check(!rules.hit(1, true) && rules.drainDefeats().isEmpty())
                    val barrelVictim = rules.enemies[0]
                    barrelVictim.x = rules.barrels[0].x; barrelVictim.z = rules.barrels[0].z
                    rules.hitBarrel(0, 1000)
                    check(!barrelVictim.alive && processDefeats(Vector3.ZERO))
                    check(coreFx.activeCount() >= 2 && coreFx.verifyEmission())
                    check(killVfx.activeCount() >= 2)
                    check(rules.killFeedback.contains("连杀"))
                    Log.i(TAG, "V3_SELFCHECK gun + barrel defeat dedup/native core/kill cues PASS")
                }
                4 -> {
                    sparkFx.update(1f); sparkFx.update(1f)
                    check(sparkFx.activeCount() == 0 && sparkFx.verifyIdle())
                    sparkFx.burst(Vector3(0f, 1f, -8f))
                    sparkFx.update(0f)
                    check(sparkFx.activeCount() == 0 && sparkFx.verifyIdle())
                    sparkFx.update(0.02f)
                    sparkFx.burst(Vector3(0f, 1f, -8f))
                    check(sparkFx.activeCount() == 1 && sparkFx.verifyEmission())
                    sparkFx.reset()
                    check(sparkFx.verifyIdle() && audio.verifyLoops())
                    Log.i(TAG, "V3_SELFCHECK native particles idle/expire/pause/reuse + dedicated audio PASS")
                    check(rules.reload()); rules.togglePause(); rules.tick(10f)
                    check(rules.reloadRemaining == 1.65f)
                    rules.togglePause(); rules.tick(1.65f); check(rules.ammo == 24)
                    rules.enemies.filter { it.alive }.forEach { rules.hit(it.slot, true, 10000) }
                    rules.drainDefeats()
                    verifySustainedBattle(scene)
                }
                6 -> {
                    relocate(Vector3(0f, 0f, -60f), 30f)
                    listOf(Vector3(0f, 0f, -1f), Vector3(1f, 0f, 0f),
                        Vector3(0f, 1f, 0f), Vector3(1f, 0.7f, -1f)).forEachIndexed { i, direction ->
                        val start = Vector3(-28f + i, 6f, -4f)
                        segment(tracers[i].entity, start, start + direction.unit() * 0.22f, 0.006f)
                        tracers[i].entity.enabled = true
                    }
                }
                7 -> {
                    // Check the same physical point used to relocate, independent of head movement.
                    val p = root.convertPositionFrom(movementReference, null)
                    check(abs(p.x) < 0.01f && abs(p.z + 60f) < 0.01f) { "virtual movement=$p" }
                    Log.i(TAG, "V3_SELFCHECK locomotion coordinate conversion PASS")
                    val physicalRotation = headSample?.data?.hmdPose?.rotation
                        ?: camera.convertRotationTo(Quat(), null)
                    val forward = Locomotion.headRelativeStep(0f, 1f, localRotation(physicalRotation), 0.05f)
                    val worldStep = root.pose().quaternion.rotateVector(Vector3(forward.x, 0f, forward.z))
                    val parentRotation = root.getParent()?.convertRotationFrom(physicalRotation, null)
                        ?: physicalRotation
                    val expected = Locomotion.headRelativeStep(0f, 1f, parentRotation, 0.05f)
                    check(abs(worldStep.x - expected.x) < 0.001f && abs(worldStep.z - expected.z) < 0.001f)
                    Log.i(TAG, "HEAD_RELATIVE_DEVICE_PASS source=${if (headSample != null) "HMD" else "camera"} " +
                        "forwardWorld=$worldStep expected=$expected")
                    listOf(Vector3(0f, 0f, -1f), Vector3(1f, 0f, 0f),
                        Vector3(0f, 1f, 0f), Vector3(1f, 0.7f, -1f)).forEachIndexed { i, direction ->
                        val start = Vector3(-28f + i, 6f, -4f)
                        val tracer = tracers[i].entity
                        val nativeStart = tracer.convertPositionTo(Vector3(0f, 0f, 0.5f), root)
                        val nativeEnd = tracer.convertPositionTo(Vector3(0f, 0f, -0.5f), root)
                        check((nativeStart - start).length() < 0.003f) { "tracer start=$nativeStart expected=$start" }
                        check((nativeEnd - start - direction.unit() * 0.22f).length() < 0.003f) {
                            "tracer end=$nativeEnd direction=$direction"
                        }
                        val width = (tracer.convertPositionTo(Vector3(0.5f, 0f, 0f), root) -
                            tracer.convertPositionTo(Vector3(-0.5f, 0f, 0f), root)).length()
                        check(abs(width - 0.006f) < 0.001f) { "tracer width=$width" }
                    }
                    Log.i(TAG, "V3_SELFCHECK tracer native endpoints four axes/diagonal width=6mm length=22cm PASS")
                }
                8 -> {
                    check(geometryFailures.isEmpty()) { "Physical spawn/target failures: $geometryFailures" }
                    Log.i(TAG, "V10_SELFCHECK physical spawned actors and open map PASS")
                    rangeProbe?.destroy(); rangeProbe = null
                    restart(); environment.update(rules, 0f)
                    check(rules.barrels.all { it.intact } && rules.gateOpen && !rules.upgraded && rules.kills == 0)
                    actors.forEachIndexed { i, a -> a.update(rules.enemies[i], Vector3.ZERO, false, 0f) }
                }
                in 9..128 -> {
                    val phase = (selfCheckFrame - 9) % 3
                    if (phase == 0) {
                        relocate(Vector3(0f, playerHeight + 2f, -84f), restartChecks * 30f)
                    } else if (phase == 1) {
                        restart()
                        val immediate = eye()
                        check((immediate - Vector3(0f, playerHeight, -2f)).length() < 0.003f) {
                            "restart immediate=$immediate"
                        }
                        if (restartChecks == 0) {
                            val stale = root.convertPositionFrom(movementReference, null)
                            check((stale - immediate).length() > 50f)
                            Log.i(TAG, "RESTART_BATCH_EVIDENCE nativeSameFrame=$stale currentEcs=$immediate")
                        }
                    } else {
                        val committed = root.convertPositionFrom(movementReference, null)
                        check((committed - Vector3(0f, playerHeight, -2f)).length() < 0.003f) {
                            "restart committed=$committed"
                        }
                        check(bodyClear(scene, committed))
                        check(!reticle.enabled && !teleportMarker.enabled && hurtEdges.none { it.enabled })
                        check(killVfx.activeCount() == 0 && coreFx.activeCount() == 0)
                        restartChecks++
                    }
                }
                129 -> {
                    check(restartChecks == 40)
                    restart()
                    Log.i(TAG, "V3_SELFCHECK repeated restart immediate/next-frame/collision/effects cycles=$restartChecks PASS")
                    hudReplayAt = SystemClock.elapsedRealtime()
                }
                130 -> {
                    rules.tick(2.5f)
                    while (rules.kills < 20) {
                        repeat(120) { if (rules.aliveCount == 0) rules.tick(0.1f) { false } }
                        rules.hit(rules.enemies.first { it.alive }.slot, true, 10000)
                        rules.tick(0.8f) { false }
                    }
                    processDefeats(eye())
                    check(rules.talents.pending && !rules.rewardMenuOpen)
                    check(rules.shoot())
                    publish(state(true))
                    hudReplayAt = SystemClock.elapsedRealtime()
                }
                131 -> {
                    if (SystemClock.elapsedRealtime() - hudReplayAt < 350L) return
                    submit(GameCommand.OPEN_REWARDS)
                    commands.poll()?.let(::execute)
                    check(rules.rewardMenuOpen)
                    hudPanel?.pose()?.setPosition(Vector3(0f, 0.4f, -0.2f))
                    publish(state(true))
                    hudReplayAt = SystemClock.elapsedRealtime()
                }
                132 -> {
                    if (SystemClock.elapsedRealtime() - hudReplayAt < 350L) return
                    val render = hudRender
                    check(render?.menu == true && render.width > 0 && render.height > 0) {
                        "Reward UI did not render: $render"
                    }
                    submit(GameCommand.SELECT_REWARD_2)
                    commands.poll()?.let(::execute)
                    check(rules.talents.cursor == 2)
                    publish(state(true))
                    hudReplayAt = SystemClock.elapsedRealtime()
                }
                133 -> {
                    if (SystemClock.elapsedRealtime() - hudReplayAt < 350L) return
                    check(hudRender?.menu == true && hudRender?.cursor == 2) {
                        "Selected reward did not reach rendered UI: $hudRender"
                    }
                    submit(GameCommand.CLOSE_REWARDS)
                    commands.poll()?.let(::execute)
                    check(!rules.rewardMenuOpen && rules.talents.pending)
                    rules.tick(rules.weapon.interval)
                    check(rules.shoot() && rules.reload())
                    rules.tick(rules.reloadSeconds)
                    check(rules.switchWeapon())
                    submit(GameCommand.OPEN_REWARDS)
                    commands.poll()?.let(::execute)
                    submit(GameCommand.SELECT_REWARD_1)
                    commands.poll()?.let(::execute)
                    submit(GameCommand.CONFIRM_REWARD)
                    commands.poll()?.let(::execute)
                    check(!rules.rewardMenuOpen && rules.talents.acquired.size == 1)
                    publish(state(true))
                    hudReplayAt = SystemClock.elapsedRealtime()
                }
                134 -> {
                    if (SystemClock.elapsedRealtime() - hudReplayAt < 350L) return
                    submit(GameCommand.PAUSE); commands.poll()?.let(::execute)
                    check(rules.paused)
                    publish(state(true))
                    hudReplayAt = SystemClock.elapsedRealtime()
                }
                135 -> {
                    if (SystemClock.elapsedRealtime() - hudReplayAt < 350L) return
                    submit(GameCommand.PAUSE); commands.poll()?.let(::execute)
                    check(!rules.paused)
                    Log.i(TAG, "HUD_REPLAY_PASS menuRendered=true selected=2 cancel=true confirm=true pauseResume=true")
                    restart()
                    hudPanel?.pose()?.setPosition(Vector3.ZERO)
                    hudMenuShown = false
                    publish(state(false))
                    rules.tick(2.5f)
                    rules.tick(6f) { false }; rules.tick(6f) { false }
                    for (enemy in rules.enemies.filter { it.alive && (it.kind == GameRules.Kind.GUARD ||
                        it.kind == GameRules.Kind.DRONE) }.distinctBy { it.kind }) {
                        enemy.x = if (enemy.kind == GameRules.Kind.GUARD) 24f else 28f
                        enemy.z = -3f
                        if (enemy.kind == GameRules.Kind.GUARD) enemy.shield = 96
                        enemy.telegraph = 0f
                        enemy.aimX = enemy.x; enemy.aimZ = enemy.z + 5f
                        actors[enemy.slot].update(enemy, Vector3(enemy.x, 1.55f, enemy.z + 5f), true, 0f)
                    }
                }
                137 -> {
                    val guard = rules.enemies.first { it.kind == GameRules.Kind.GUARD && it.alive }
                    val drone = rules.enemies.first { it.kind == GameRules.Kind.DRONE && it.alive }
                    val shieldRay = actors[guard.slot].shieldRay()
                    val front = cast(scene, shieldRay.first, shieldRay.second)
                    val head = cast(scene, Vector3(guard.x, 1.85f, guard.z + 1.5f), Vector3.BACK)
                    val back = cast(scene, Vector3(guard.x, 1.04f, guard.z - 1.5f), Vector3.FORWARD)
                    check(front?.let { shieldTargets[it.entity.id] } == guard.slot) { "shield front=$front" }
                    check(head?.let { hitTargets[it.entity.id] } == (guard.slot to true)) { "guard head=$head" }
                    check(back?.let { hitTargets[it.entity.id] } == (guard.slot to false)) { "guard back=$back" }
                    val hull = cast(scene, Vector3(drone.x, 2.6f, drone.z + 1.5f), Vector3.BACK)
                    val sensorRay = actors[drone.slot].droneSensorRay()
                    val sensor = cast(scene, sensorRay.first, sensorRay.second)
                    check(hull?.let { hitTargets[it.entity.id] } == (drone.slot to false)) { "drone hull=$hull" }
                    check(sensor?.let { hitTargets[it.entity.id] } == (drone.slot to true)) { "drone sensor=$sensor" }
                    check(rules.hitShield(guard.slot, 96) && guard.health == guard.kind.hp)
                    actors[guard.slot].update(guard, Vector3(guard.x, 1.55f, guard.z + 5f), true, 0f)
                    Log.i(TAG, "V5_HITBOX_PASS shieldFront=true exposedHead=true back=true droneHull=true sensor=true")
                }
                139 -> {
                    val guard = rules.enemies.first { it.kind == GameRules.Kind.GUARD && it.alive }
                    val afterBreak = cast(scene, Vector3(guard.x, 1.04f, guard.z + 1.5f), Vector3.BACK)
                    check(afterBreak?.let { hitTargets[it.entity.id] } == (guard.slot to false)) {
                        "Broken shield still blocks body: $afterBreak"
                    }
                    expansionWallHit = checkNotNull(cast(scene, Vector3(0f, 1f, V3Layout.REPAIR_Z),
                        Vector3(-1f, 0f, 0f))).entity.id
                    kit.updateVisibility(Vector3(0f, 3.55f, -84f), Vector3.BACK, 1f)
                    Log.i(TAG, "V5_SHIELD_BREAK_PASS health=${guard.health} bodyReachable=true")
                }
                141 -> {
                    val repair = root.getChildren().first { it.getName() == "EntryRepairBay" }
                    fun models(node: Entity): List<ModelComponent> =
                        listOfNotNull(node.components[ModelComponent::class.java]) +
                            node.getChildren().flatMap(::models)
                    fun collisions(node: Entity): Int =
                        (if (node.components[CollisionComponent::class.java] != null) 1 else 0) +
                            node.getChildren().sumOf(::collisions)
                    check(repair.enabled && models(repair).isNotEmpty() && models(repair).all { !it.isVisible })
                    check(collisions(repair) > 0)
                    val hiddenHit = cast(scene, Vector3(0f, 1f, V3Layout.REPAIR_Z), Vector3(-1f, 0f, 0f))
                    check(hiddenHit?.entity?.id == expansionWallHit) { "Culling changed collision: $hiddenHit" }
                    val shadowChecks = kit.verifyShadowBudget()
                    kit.updateVisibility(Vector3(0f, 1.55f, -2f), Vector3.BACK, 1f)
                    check(models(repair).all { it.isVisible })
                    kit.verifyShadowBudget()
                    Log.i(TAG, "V5_SHADOW_BUDGET_PASS models=$shadowChecks receiving=retained nearCasting=restored")
                    Log.i(TAG, "V5_CULL_COLLISION_PASS hiddenModels=${models(repair).size} retainedColliders=${collisions(repair)}")
                    V5Layout.supplies.forEach { supply ->
                        val player = Vector3(supply.x, 1.55f, supply.z)
                        check(bodyClear(scene, player)) { "Supply not physically accessible: ${supply.id}" }
                        check(nearbySupply(player)?.id == supply.id)
                        check(!rules.collectSupply(supply.id) && rules.supplyAvailable(supply.id))
                        rules.damagePlayer(45)
                        check(rules.collectSupply(supply.id) && !rules.supplyAvailable(supply.id))
                        rules.restart(); rules.tick(2.5f)
                        Log.i(TAG, "V5_SUPPLY_PASS id=${supply.id} capsuleClear=true fullRetained=true collected=true")
                    }
                    check(camera.components[AnchorComponent::class.java]?.positionOffset == Vector3.ZERO)
                    val crouched = Vector3(0f, 0.85f, -2f)
                    relocate(crouched)
                    val moved = movePlayer(scene, eye(), 0f, -0.5f)
                    check(abs(moved.y - 0.85f) < 0.003f) { "Walking raised crouched eye: $moved" }
                    relocate(Vector3(moved.x, EyeHeight.onNewFloor(moved.y, 0f, 0f), moved.z - 0.5f))
                    check(abs(eye().y - 0.85f) < 0.003f)
                }
                143 -> {
                    val committed = root.convertPositionFrom(movementReference, null)
                    check(abs(committed.y - 0.85f) < 0.003f) { "Eye height did not commit: $committed" }
                    Log.i(TAG, "V5_EYE_DEVICE_PASS anchorOffset=zero crouchedEye=0.85 walkingAndTeleportRetained=true syntheticCrouch=true")
                    restart()
                    rules.tick(2.5f)
                    rules.enemies.forEach { it.health = 0 }
                    val elite = rules.enemies[6]
                    elite.kind = GameRules.Kind.ELITE; elite.health = elite.kind.hp
                    elite.x = 24f; elite.z = -3f; elite.coreHealth = 180; elite.telegraph = 1f
                    elite.aimX = 24f; elite.aimZ = 2f
                    actors.forEachIndexed { i, actor ->
                        actor.update(rules.enemies[i], Vector3(24f, 1.55f, 2f), true, 0f)
                    }
                }
                145 -> {
                    val elite = rules.enemies[6]
                    val coreRay = actors[6].coreRay()
                    val hit = cast(scene, coreRay.first, coreRay.second)
                    check(hit?.let { coreTargets[it.entity.id] } == 6) { "Open elite core ray=$hit" }
                    check(rules.hitEliteCore(6, 48) && elite.telegraph == 0f && elite.coreHealth == 132)
                    actors[6].update(elite, Vector3(24f, 1.55f, 2f), true, 0f)
                }
                147 -> {
                    val elite = rules.enemies[6]
                    val coreRay = actors[6].coreRay()
                    val hit = cast(scene, coreRay.first, coreRay.second)
                    check(hit?.let { hitTargets[it.entity.id] } == (6 to false)) { "Interrupted core still blocks body=$hit" }
                    check(!rules.hitEliteCore(6, 48))
                    elite.telegraph = 1f
                    check(rules.hitEliteCore(6, elite.coreHealth) && elite.coreHealth == 0 && !elite.coreOpen)
                    actors[6].update(elite, Vector3(24f, 1.55f, 2f), true, 0f)
                }
                149 -> {
                    val hit = cast(scene, Vector3(24f, 1.55f, -1.5f), Vector3.BACK)
                    check(hit?.let { hitTargets[it.entity.id] } == (6 to false)) { "Destroyed core blocks body=$hit" }
                    Log.i(TAG, "V6_ELITE_CORE_PASS openNativeRay=true interrupt=true closedBodyRay=true destroyedBodyRay=true")
                    check(environment.verifyVisiblePolish())
                    V7Layout.obstacles.forEach { obstacle ->
                        check(!V3Layout.walkable(obstacle.x, obstacle.z, true))
                        // Begin outside the solid and compare native capsule orientations.
                        val travel = obstacle.width / 2f + 0.7f
                        val start = Vector3(obstacle.x + travel,
                            EyeHeight.CAPSULE / 2f + 0.03f, obstacle.z)
                        val hits = scene.convexCast(playerCapsule, start, capsuleRotation,
                            Vector3(-1f, 0f, 0f), travel, CollisionCastHitMode.ALL,
                            CollisionGroup(WORLD_ONLY), root).results
                        val unrotatedHits = scene.convexCast(playerCapsule, start, Quat(),
                            Vector3(-1f, 0f, 0f), travel, CollisionCastHitMode.ALL,
                            CollisionGroup(WORLD_ONLY), root).results
                        val capsuleBlocked = hits.any { isWall(it) && (it.entity.getName() == obstacle.name ||
                            it.entity.getName() == obstacle.name.replace("Foot", "Post")) }
                        val footHit = cast(scene, Vector3(start.x, 0.12f, start.z),
                            Vector3(-1f, 0f, 0f), WORLD_ONLY, travel)
                        val footBlocked = footHit?.entity?.getName() == obstacle.name
                        val stopped = movePlayer(scene, Vector3(start.x, 1.55f, start.z), -travel, 0f)
                        val evidence = hits.map { "${it.entity.getName()}:${it.distance}:${it.normal}" }
                        Log.i(TAG, "V7_FOOTPRINT_SWEEP name=${obstacle.name} capsuleBlocked=$capsuleBlocked " +
                            "footRay=${footHit?.entity?.getName()} stopped=$stopped hits=$evidence " +
                            "unrotated=${unrotatedHits.map { "${it.entity.getName()}:${it.distance}" }}")
                        check(capsuleBlocked && footBlocked && !obstacle.contains(stopped.x, stopped.z, 0f)) {
                            "Footprint mismatch ${obstacle.name}: footHit=$footHit stopped=$stopped hits=$evidence"
                        }
                    }
                    impactVfx.impact(Vector3(0f, 0.1f, -2f), Vector3.UP, Vector3.BACK, eye())
                    impactVfx.explosion(Vector3(0f, 0.1f, -2f))
                    check(impactVfx.activeCount() == 2)
                    impactVfx.update(0f); check(impactVfx.activeCount() == 0)
                    impactVfx.headshot(Vector3(0f, 1.7f, -2f), Vector3.BACK, eye())
                    check(impactVfx.activeHeadshots() == 1)
                    impactVfx.update(0f); check(impactVfx.activeHeadshots() == 0)
                    combatVfx.muzzle(Vector3(0f, 1f, -2f), Quat())
                    combatVfx.shockwave(Vector3(0f, 0f, -2f), eye())
                    check(combatVfx.activeCount() == 2)
                    combatVfx.update(0f)
                    check(combatVfx.activeCount() == 0)
                    Log.i(TAG, "V7_VISUAL_DEVICE_PASS rings=3 banners=4 footprints=blocked impactPause=cleared")
                    verifyProjectileDamage(scene)
                    verifyArsenalNative(scene)
                }
                150 -> verifyArsenalHit(scene, GameRules.Weapon.ARC)
                151 -> verifyArsenalHit(scene, GameRules.Weapon.RAIL)
                152 -> verifyArsenalHit(scene, GameRules.Weapon.PLASMA)
                153 -> verifyPlasmaCover(scene)
                154 -> {
                    restart()
                    publish(state(false))
                    Log.i(TAG, "V3_SELFCHECK_PASS sdk=6.2.6 version=12")
                    RunOptions.selfCheck = false
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "V3_SELFCHECK_FAIL", error); restart(); RunOptions.selfCheck = false
        }
        selfCheckFrame++
    }
    override fun close() {
        if (closed) return
        closed = true
        provider.removeListener(poseListener); provider.removeControllerActionListener(actionListener); provider.stop()
        hmdProvider.removeListener(headListener); hmdProvider.stop()
        headSample = null; commands.clear()
        if (WastelandSystem.runtime === this) { WastelandSystem.runtime = null; EcsSystem.unregister(WastelandSystem::class.java) }
        actors.forEach { it.close() }; environment.close(); audio.close()
        navigation.close(); missionNavigation.close()
        if (root.valid) root.destroy(); if (camera.valid) camera.destroy()
        kit.close(); Log.i(TAG, "SCENE_DISPOSED version=3")
    }
    companion object {
        private const val TAG = "Wasteland"
        const val WORLD_ONLY = 512u
    }
}
class WastelandSystem : EcsSystem() {
    override fun update(context: SceneUpdateContext) { runtime?.update(context) }
    companion object { var runtime: WastelandScene? = null }
}
object RunOptions {
    var launchStartMs = SystemClock.elapsedRealtime()
    var selfCheck = false
    var stress = false
    var profileNoInk = false
    var profileFullShadows = true
    var missionStress = false
    var stressDurationMs = 120_000L
}
