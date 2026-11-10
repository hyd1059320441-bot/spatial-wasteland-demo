package com.pico.demo.wasteland.game

import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.math.*
import kotlin.math.sin

class OutpostEnvironment(private val kit: SceneKit) : AutoCloseable {
    private val root = kit.root
    private val polish = VisiblePolish(kit)
    lateinit var door: Entity; private set
    lateinit var doorHitbox: Entity; private set
    lateinit var generatorGlow: Entity; private set
    lateinit var chest: Entity; private set
    private val barrels = mutableListOf<Entity>()
    private val barrelHitboxes = mutableListOf<Entity>()
    val barrelTargets = mutableMapOf<Long, Int>()
    lateinit var upgrade: Entity; private set
    private var gateHeight = 0f
    private var chestLift = 0f
    private var clock = 0f
    private var pulseClock = 0f
    private var doorBase = Vector3.ZERO
    private var chestBase = Vector3.ZERO
    private val electricFx = ParticleFxPool(kit, "EnvironmentElectric", ParticleFxProfile.ELECTRIC, 5)
    private var chestOpened = false
    private data class Rotor(val entity: Entity, val speed: Float)
    private val rotors = mutableListOf<Rotor>()
    private val smokeFx = ParticleFxPool(kit, "FactorySmoke", ParticleFxProfile.SMOKE, 6)
    private val windFx = ParticleFxPool(kit, "DriftingDust", ParticleFxProfile.DUST, 4)
    private var atmosphereClock = 0f
    private var lastGateHeight = Float.NaN
    private var doorSolid = true
    private val barrelVisible = BooleanArray(V3Layout.barrelPositions.size) { true }
    private var upgradeShown = false
    private var lastChestLift = Float.NaN
    private val supplies = mutableMapOf<String, Entity>()
    private val supplyShown = mutableMapOf<String, Boolean>()
    private val worldFilter = com.pico.spatial.core.ecs.simulation.CollisionFilter(
        com.pico.spatial.core.ecs.simulation.CollisionGroup(SceneKit.GROUP or WastelandScene.WORLD_ONLY))
    private val disabledFilter = com.pico.spatial.core.ecs.simulation.CollisionFilter(
        com.pico.spatial.core.ecs.simulation.CollisionGroup(0u))

    suspend fun build() {
        val sky = kit.color(1f, 1f, 1f).apply {
            setBaseColorTexture(kit.texture("textures/wasteland-sky.png"))
            setCullingMode(MaterialCullingMode.FRONT)
        }
        kit.box(root, "GraphicNovelDustSky", Vector3(0f, 20f, -47f),
            Vector3(230f), sky, mesh = kit.sphere)
        kit.box(root, "WastelandGround", Vector3(0f, -0.15f, -47f),
            Vector3(78f, 0.3f, 100f), kit.ground, collider = true)
        kit.box(root, "QuarryRoad", Vector3(0f, 0.015f, -45f),
            Vector3(10f, 0.03f, 86f), kit.road)
        kit.box(root, "StorageRoad", Vector3(24f, 0.02f, -53f),
            Vector3(22f, 0.04f, 18f), kit.road)
        for (i in 0..3) {
            val x = if (i % 2 == 0) -48f else 49f
            val z = -20f - i * 21f
            kit.v3Asset("SandstoneRockComicV3", "DistantMesa$i", x, z,
                Vector3(18f, 12f + i * 2f, 20f), yaw = i * 43f)
            kit.box(root, "WindTower$i", Vector3(x, 16f, z), Vector3(0.55f, 14f, 0.55f), kit.deck)
            val rotor = Entity().apply {
                setName("WindRotor$i"); root.addChild(this)
                pose().setPosition(Vector3(x, 23f, z + 0.6f))
            }
            kit.box(rotor, "TurbineHub", Vector3.ZERO, Vector3(0.9f), kit.orange, mesh = kit.sphere)
            for (blade in 0..2) {
                val arm = Entity().apply {
                    rotor.addChild(this); pose().setEulerAngles(EulerAngles(0f, 0f, blade * 120f))
                }
                kit.box(arm, "WindBlade$blade", Vector3(0f, 2.5f, 0f),
                    Vector3(0.55f, 5f, 0.12f), kit.deck)
                kit.box(arm, "BladeTip$blade", Vector3(0f, 4.8f, 0f),
                    Vector3(0.55f, 0.45f, 0.15f), kit.orange)
            }
            rotors.add(Rotor(rotor, 17f + i * 3f))
        }
        kit.box(root, "ReactorDeck", Vector3(0f, 1f, -89f),
            Vector3(30f, 2f, 16f), kit.deck, collider = true)
        kit.box(root, "ReactorRamp", Vector3(0f, 1f, -77f),
            Vector3(10f, 0.35f, 8.25f), kit.deck, collider = true)
            .pose().setEulerAngles(EulerAngles(-14f, 0f, 0f))

        kit.v3Asset("RepairBayV4", "EntryRepairBay", 0f, V3Layout.REPAIR_Z,
            Vector3(V3Layout.REPAIR_WIDTH, V3Layout.REPAIR_HEIGHT, V3Layout.REPAIR_DEPTH),
            exactSize = false, directory = "v4").also { kit.staticCollision(it) }
        kit.v3Asset("MiningCraneComicV3", "CentralMiningCrane", 0f, -54f,
            Vector3(19f, 10f, 8f)).also { kit.staticCollision(it) }
        kit.v3Asset("WarehouseComicV3", "StorageWarehouse", 28f, -50f,
            Vector3(18f, 7f, 15f), yaw = -90f).also { kit.staticCollision(it) }
        kit.v3Asset("ReactorPlantComicV3", "ReactorPlant", 0f, -88f,
            Vector3(26f, 18f, 20f), groundY = 2f).also { kit.staticCollision(it) }
        for (wall in V3Layout.structures.take(10)) buildWall(wall)
        buildRouteMarkings()
        for (landmark in V5Layout.landmarks) {
            kit.v3Asset(landmark.model, landmark.id, landmark.x, landmark.z,
                Vector3(landmark.width, landmark.height, landmark.depth), landmark.yaw,
                exactSize = false, directory = "v5").also { kit.staticCollision(it) }
            val approach = V5Layout.supplies.first { supply ->
                kotlin.math.hypot(supply.x - landmark.x, supply.z - landmark.z) < 7f
            }
            kit.box(root, "${landmark.id}ApproachPad", Vector3(approach.x, 0.02f, approach.z),
                Vector3(2.1f, 0.035f, 1.7f), kit.road)
            for (side in listOf(-1f, 1f)) kit.box(root, "${landmark.id}SupplyStripe$side",
                Vector3(approach.x + side * 0.75f, 0.046f, approach.z),
                Vector3(0.08f, 0.018f, 1.25f), kit.cyan)
        }
        for (supply in V5Layout.supplies) {
            val cache = Entity().apply {
                setName("Supply_${supply.id}"); root.addChild(this)
                pose().setPosition(Vector3(supply.x, 0f, supply.z))
            }
            kit.box(cache, "SupplyCase", Vector3(0f, 0.22f, 0f),
                Vector3(0.65f, 0.44f, 0.45f), kit.wallCream)
            kit.box(cache, "SupplyCaseTrim", Vector3(0f, 0.4f, 0f),
                Vector3(0.68f, 0.065f, 0.48f), kit.paintedOrange)
            kit.box(cache, "SupplyCrossH", Vector3(0f, 0.23f, 0.231f),
                Vector3(0.22f, 0.055f, 0.012f), kit.cyan)
            kit.box(cache, "SupplyCrossV", Vector3(0f, 0.23f, 0.231f),
                Vector3(0.055f, 0.22f, 0.014f), kit.cyan)
            supplies[supply.id] = cache; supplyShown[supply.id] = true
        }
        polish.build()
        for (c in V8Layout.props) {
            val prop = kit.v3Asset(c.asset, c.name, c.x, c.z,
                Vector3(c.width, c.height, c.depth), exactSize = false, directory = "v8")
            val bounds = prop.getVisualBounds(root)
            check(bounds.size.x <= c.width + 0.02f && bounds.size.z <= c.depth + 0.02f &&
                bounds.size.y <= c.height + 0.02f) { "Generated prop exceeded navigation envelope: ${c.name}" }
            kit.staticCollision(prop)
        }

        for ((label, x) in listOf("West" to -35f, "East" to 35f)) {
            for (i in 0..8) {
                val z = -3f - i * 12f
                val stagger = if (label == "West") -1f else 1f
                kit.v3Asset("SandstoneRockComicV3", "Perimeter${label}Rock$i",
                    x + stagger * (i % 2) * 1.5f, z,
                    Vector3(8f + i % 3, 5f + i % 2, 7f + (i + 1) % 3),
                    yaw = i * 31f)
                val c = V3Layout.structures.first { it.name == "Perimeter${x}Rock$i" }
                kit.invisibleBox(root, "${c.name}Collision", Vector3(c.x, c.height / 2, c.z),
                    Vector3(c.width, c.height, c.depth))
            }
        }
        kit.invisibleBox(root, "WestBoundary", Vector3(-39.3f, 3f, -47f),
            Vector3(0.6f, 6f, 101f))
        kit.invisibleBox(root, "EastBoundary", Vector3(39.3f, 3f, -47f),
            Vector3(0.6f, 6f, 101f))
        kit.invisibleBox(root, "NorthBoundary", Vector3(0f, 3f, 3.3f),
            Vector3(79f, 6f, 0.6f))
        kit.invisibleBox(root, "SouthBoundary", Vector3(0f, 3f, -97.3f),
            Vector3(79f, 6f, 0.6f))

        for (c in V3Layout.covers) {
            val file = when (c.asset) {
                "MiningTruck" -> "MiningTruckComicV3"
                "SandstoneRock" -> "SandstoneRockComicV3"
                "Generator" -> "PowerRelayV4"
                "LootChest" -> "LootChestComicV3"
                else -> "ShippingContainerV8"
            }
            val y = V3Layout.heightAt(c.x, c.z)
            val prop = kit.v3Asset(file, c.name, c.x, c.z,
                Vector3(c.width, c.height, c.depth), yaw = if (c.depth > c.width) 90f else 0f,
                groundY = y,
                directory = when {
                    file.endsWith("V8") -> "v8"
                    file.endsWith("V4") -> "v4"
                    else -> "v3"
                })
            val bounds = prop.getVisualBounds(root)
            kit.invisibleBox(root, "${c.name}Collision",
                bounds.center, bounds.size)
            if (c.name in V3Layout.shippingContainers) check(
                kotlin.math.abs(bounds.size.x - c.width) < 0.03f &&
                kotlin.math.abs(bounds.size.z - c.depth) < 0.03f &&
                kotlin.math.abs(bounds.size.y - V3Layout.CONTAINER_HEIGHT) < 0.03f) {
                    "Industrial container/layout mismatch: ${c.name} $bounds"
                }
            if (c.name == "LootChest") { chest = prop; chestBase = prop.pose().position }
        }
        val entryNames = listOf("EntryRepairBay", "SalvageKiosk") +
            V8Layout.props.map { it.name } +
            V3Layout.covers.filter { it.z > -28f }.map { it.name }
        val entryBounds = entryNames.associateWith { name ->
            root.getChildren().first { it.getName() == name }.getVisualBounds(root)
        }
        for ((name, bounds) in entryBounds) for ((other, otherBounds) in entryBounds) {
            if (name >= other) continue
            val gapX = maxOf(bounds.min.x - otherBounds.max.x, otherBounds.min.x - bounds.max.x, 0f)
            val gapZ = maxOf(bounds.min.z - otherBounds.max.z, otherBounds.min.z - bounds.max.z, 0f)
            val gap = kotlin.math.hypot(gapX, gapZ)
            check(gap >= 1f) { "Entry props crowd each other: $name/$other gap=$gap" }
        }
        android.util.Log.i("Wasteland", "V9_ENTRY_SPACING_PASS objects=${entryBounds.size} minGap=1m owner=WastelandMineV3 visual=UNOBSERVED")

        door = kit.box(root, "ReactorGate", Vector3(0f, 2.1f, V3Layout.GATE_Z),
            Vector3(V3Layout.GATE_HALF_WIDTH * 2f, 4.2f, 0.25f), kit.deck)
        doorBase = door.pose().position
        doorHitbox = kit.invisibleBox(root, "LockedReactorGate",
            Vector3(0f, 2.1f, V3Layout.GATE_Z),
            Vector3(V3Layout.GATE_HALF_WIDTH * 2f, 4.2f, 1.1f))
        for (x in listOf(-21.75f, 21.75f)) {
            // Industrial wall panels must not masquerade as vertically stretched containers.
            buildWall(V3Layout.structures.first { it.name ==
                if (x < 0f) "GateWallWest" else "GateWallEast" })
            kit.invisibleBox(root, "GateWall${x}Collision", Vector3(x, 2.25f, V3Layout.GATE_Z),
                Vector3(34.5f, 4.5f, 2f))
        }

        generatorGlow = kit.v3Asset("EnergyBoltComicV3", "GeneratorStatus",
            V3Layout.GENERATOR_X, V3Layout.GENERATOR_Z - 0.8f,
            Vector3(0.45f, 0.16f, 0.16f), groundY = 1.4f)
        for (x in listOf(22f, 32f)) {
            val rotor = Entity().apply {
                setName("WarehouseVent$x"); root.addChild(this)
                pose().setPosition(Vector3(x, 5.5f, -42.2f))
            }
            kit.box(rotor, "FanHub", Vector3.ZERO, Vector3(0.18f), kit.orange, mesh = kit.sphere)
            for (i in 0..3) {
                val arm = Entity().apply {
                    rotor.addChild(this); pose().setEulerAngles(EulerAngles(0f, 0f, i * 90f))
                }
                kit.box(arm, "FanBlade$i", Vector3(0f, 0.35f, 0f),
                    Vector3(0.2f, 0.65f, 0.08f), kit.deck)
            }
            rotors.add(Rotor(rotor, 115f))
        }
        V3Layout.barrelPositions.forEachIndexed { i, (x, z) ->
            val y = V3Layout.heightAt(x, z)
            barrels.add(kit.v3Asset("FuelBarrelComicV3", "ExplosiveBarrel$i", x, z,
                Vector3(0.72f, 1.15f, 0.72f), groundY = y))
            val hitbox = kit.invisibleBox(root, "BarrelHitbox$i", Vector3(x, y + 0.6f, z),
                Vector3(0.7f, 1.2f, 0.7f))
            barrelHitboxes.add(hitbox); barrelTargets[hitbox.id] = i
        }
        upgrade = kit.v3Asset("EnergyBoltComicV3", "ElectricCore", 0f, -82f,
            Vector3(0.5f, 0.22f, 0.22f), groundY = 2.8f)
        upgrade.enabled = false
        for ((name, yaw, intensity, color) in listOf(
            Light("LowDustSun", -35f, 1800f, Color4(1f, 0.83f, 0.64f, 1f)),
            Light("ColdIndustrialFill", 145f, 650f, Color4(0.68f, 0.79f, 0.94f, 1f)))) {
            Entity().apply {
                setName(name); pose().setEulerAngles(EulerAngles(-45f, yaw, 0f))
                components.set(DirectionalLightComponent(color, intensity, name == "LowDustSun"))
                root.addChild(this)
            }
        }
        electricFx.initialize(); smokeFx.initialize(); windFx.initialize()
    }
    /** Physical strips stay authoritative; panel seams give each wall a readable industrial rhythm. */
    private fun buildWall(wall: V3Layout.Obstacle) {
        val floor = V3Layout.heightAt(wall.x, wall.z)
        val alongZ = wall.depth > wall.width
        val length = if (alongZ) wall.depth else wall.width
        val thickness = if (alongZ) wall.width else wall.depth
        val repair = wall.name.startsWith("Repair")
        val crane = wall.name.startsWith("Crane")
        kit.box(root, wall.name, Vector3(wall.x, floor + wall.height / 2f, wall.z),
            Vector3(wall.width, wall.height, wall.depth),
            if (crane) kit.paintedOrange else kit.deck, collider = true)
        if (crane) return
        val count = if (repair) 4 else 5
        val step = length / count
        for (i in 0 until count) {
            val along = -length / 2f + step * (i + 0.5f)
            // One envelope covers both faces; the substrate shows only in narrow seams.
            kit.box(root, "${wall.name}Panel$i",
                Vector3(wall.x + if (alongZ) 0f else along,
                    floor + wall.height / 2f, wall.z + if (alongZ) along else 0f),
                if (alongZ) Vector3(thickness + 0.025f, wall.height - 0.18f, step - 0.12f)
                else Vector3(step - 0.12f, wall.height - 0.18f, thickness + 0.025f),
                if (i % 3 == 0) kit.wallCream else kit.wallSand)
        }
        kit.box(root, "${wall.name}SafetyCap",
            Vector3(wall.x, floor + wall.height - 0.08f, wall.z),
            if (alongZ) Vector3(thickness + 0.06f, 0.16f, length)
            else Vector3(length, 0.16f, thickness + 0.06f), kit.paintedOrange)
        if (repair) {
            for (end in listOf(-1f, 1f)) {
                kit.box(root, "${wall.name}EndFrame$end",
                    Vector3(wall.x, floor + (V3Layout.REPAIR_HEIGHT + wall.height) / 2f,
                        wall.z + end * (length / 2f - 0.18f)),
                    Vector3(thickness, V3Layout.REPAIR_HEIGHT - wall.height, 0.12f),
                    kit.paintedOrange, collider = true)
            }
            kit.box(root, "${wall.name}RoofBeam", Vector3(wall.x, floor + V3Layout.REPAIR_HEIGHT - 0.25f, wall.z),
                Vector3(thickness, 0.24f, length), kit.deck)
        }
    }

    private fun buildRouteMarkings() {
        for (x in listOf(-4.35f, 4.35f)) {
            kit.box(root, "RepairBayFloorEdge$x", Vector3(x, 0.056f, -10f),
                Vector3(0.14f, 0.015f, 10f), kit.white)
        }
        for (i in 0..9) {
            kit.box(root, "QuarryRouteDash$i", Vector3(0f, 0.039f, -19f - i * 4.4f),
                Vector3(0.12f, 0.012f, 1.6f), kit.white)
        }
        for (x in listOf(-4.7f, 4.7f)) {
            kit.box(root, "GateWarningStripe$x", Vector3(x, 0.06f, V3Layout.GATE_Z + 1.2f),
                Vector3(0.35f, 0.014f, 1.5f), kit.orange)
        }
        for (z in listOf(-82f, -88f, -94f)) {
            kit.box(root, "ReactorAisleDash$z", Vector3(0f, 2.026f, z),
                Vector3(0.16f, 0.015f, 2f), kit.white)
        }
        for (x in listOf(25.2f, 26.8f)) {
            kit.box(root, "RelayStatusStrip$x", Vector3(x, 1.35f, -58.985f),
                Vector3(0.045f, 0.42f, 0.025f), kit.cyan)
        }
    }
    private data class Light(val name: String, val yaw: Float, val intensity: Float, val color: Color4)
    fun update(rules: GameRules, dt: Float, animate: Boolean = true) {
        clock += dt
        polish.update(dt)
        supplies.forEach { (id, entity) ->
            val available = rules.supplyAvailable(id)
            if (available != supplyShown[id]) {
                entity.enabled = available; supplyShown[id] = available
            }
        }
        if (dt > 0f)
            rotors.forEach { it.entity.pose().setEulerAngles(EulerAngles(0f, 0f, clock * it.speed)) }
        atmosphereClock = (atmosphereClock - dt).coerceAtLeast(0f)
        if (animate && atmosphereClock == 0f) {
            smokeFx.burst(Vector3(-4f, 19.5f, -89f))
            smokeFx.burst(Vector3(4f, 19.5f, -89f))
            windFx.burst(Vector3(-22f + sin(clock * 0.17f) * 4f, 0.4f, -31f))
            windFx.burst(Vector3(21f, 0.6f, -61f))
            atmosphereClock = 1.2f
        }
        smokeFx.update(dt); windFx.update(dt)
        val desiredHeight = if (rules.gateOpen) 4.2f else 0f
        gateHeight += (desiredHeight - gateHeight).coerceIn(-dt * 2.3f, dt * 2.3f)
        if (gateHeight != lastGateHeight) {
            door.pose().setPosition(doorBase + Vector3(0f, gateHeight, 0f))
            doorHitbox.pose().setPosition(Vector3(0f, 2.1f + gateHeight, V3Layout.GATE_Z))
            lastGateHeight = gateHeight
        }
        val solid = gateHeight < 3f
        if (solid != doorSolid) {
            doorHitbox.components[CollisionComponent::class.java]?.collisionFilter =
                if (solid) worldFilter else disabledFilter
            doorSolid = solid
        }
        if (dt > 0f) generatorGlow.pose().setEulerAngles(EulerAngles(0f, clock * 90f, 0f))
        barrels.forEachIndexed { i, e ->
            val intact = rules.barrels[i].intact
            if (intact != barrelVisible[i]) {
                e.enabled = intact
                barrelHitboxes[i].components[CollisionComponent::class.java]?.collisionFilter =
                    if (intact) worldFilter else disabledFilter
                barrelVisible[i] = intact
            }
        }
        if (rules.complete != chestOpened) {
            chestOpened = rules.complete
        }
        chestLift = if (rules.complete) (chestLift + dt).coerceAtMost(1f) else 0f
        if (chestLift != lastChestLift) {
            chest.pose().setPosition(chestBase + Vector3(0f, chestLift * 0.35f, 0f))
                .setEulerAngles(EulerAngles(0f, sin(chestLift * 3.14159f) * 8f, 0f))
            lastChestLift = chestLift
        }
        pulseClock = (pulseClock - dt).coerceAtLeast(0f)
        if (animate && pulseClock == 0f && rules.complete) {
            electricFx.burst(Vector3(V3Layout.CHEST_X, 3.2f, V3Layout.CHEST_Z))
            pulseClock = 0.45f
        }
        electricFx.update(dt)
    }
    fun reset() {
        gateHeight = 0f; chestLift = 0f; clock = 0f; pulseClock = 0f; chestOpened = false
        atmosphereClock = 0f; electricFx.reset(); smokeFx.reset(); windFx.reset()
        polish.reset()
    }
    fun verifyVisiblePolish() = polish.verify()
    override fun close() = Unit
}
