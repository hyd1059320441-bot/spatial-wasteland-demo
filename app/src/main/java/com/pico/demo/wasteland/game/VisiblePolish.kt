package com.pico.demo.wasteland.game

import android.util.Log
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.*
import kotlin.math.*

/** Near/middle-distance composition that is already present before the first encounter starts. */
class VisiblePolish(private val kit: SceneKit) {
    private data class Ring(val owner: Entity, val node: Entity, val radius: Float,
        val tilt: Float, val speed: Float)
    private data class Banner(val joints: List<Entity>, val phase: Float)
    private val rings = mutableListOf<Ring>()
    private val banners = mutableListOf<Banner>()
    private lateinit var housing: Entity
    private lateinit var plinth: Entity
    private lateinit var entryLight: SpotLightComponent
    private var clock = 0f
    private var animationDebt = 0f

    suspend fun build() {
        plinth = kit.box(kit.root, "InductionPlinth",
            Vector3(V7Layout.POWER_X, 0.12f, V7Layout.POWER_Z),
            Vector3(V7Layout.POWER_WIDTH, 0.24f, V7Layout.POWER_WIDTH), kit.deck, collider = true)
        housing = Entity().apply { setName("InductionHousing"); kit.root.addChild(this) }
        kit.bundleModel("scene-packages/WastelandV7Visuals.bundle", "WastelandV7Visuals",
            housing, "InductionHousingSurface")
        val raw = housing.getVisualBounds(kit.root)
        check(!raw.isEmpty() && raw.size.x > 0f && raw.size.y > 0f && raw.size.z > 0f)
        val uniform = minOf(3f / raw.size.x, 1.45f / raw.size.y, 3f / raw.size.z)
        housing.pose().setScaleVector(Vector3(uniform))
        val fitted = housing.getVisualBounds(kit.root)
        housing.pose().setPosition(Vector3(V7Layout.POWER_X - fitted.center.x,
            0.24f - fitted.min.y, V7Layout.POWER_Z - fitted.center.z))
        kit.staticCollision(housing)
        val bounds = housing.getVisualBounds(kit.root)
        check(bounds.min.y in 0.22f..0.26f && bounds.size.x <= 3.03f && bounds.size.z <= 3.03f)
        val ringMesh = kit.own(MeshResource.createTorus(1f, 0.94f))
        listOf(1.15f to 23f, 0.91f to -38f, 0.66f to 61f).forEachIndexed { i, (radius, speed) ->
            val owner = Entity().apply {
                setName("InductionRingOwner$i"); kit.root.addChild(this)
                pose().setPosition(Vector3(V7Layout.POWER_X, 3.1f, V7Layout.POWER_Z))
            }
            kit.box(owner, "InductionRing$i", Vector3.ZERO, Vector3(radius),
                if (i == 1) kit.white else kit.cyan, mesh = ringMesh)
            val node = kit.box(owner, "CirculatingEnergy$i", Vector3.ZERO,
                Vector3(0.12f), if (i == 1) kit.orange else kit.white, mesh = kit.sphere)
            rings.add(Ring(owner, node, radius * 0.97f, 22f + i * 31f, speed))
        }
        for ((index, z) in V7Layout.gantryZ.withIndex()) {
            for (side in listOf(-1f, 1f)) {
                val x = side * V7Layout.POST_X
                kit.box(kit.root, "EntryLampFoot${index}_$x", Vector3(x, 0.15f, z),
                    Vector3(0.45f, 0.3f, 0.45f), kit.paintedOrange, collider = true)
                kit.box(kit.root, "EntryLampPost${index}_$x", Vector3(x, 1.9f, z),
                    Vector3(0.18f, 3.8f, 0.18f), kit.seamSteel, collider = true)
                buildBanner(index * 2 + if (side > 0f) 1 else 0, Vector3(side * 4.65f, 3.5f, z))
            }
            kit.box(kit.root, "EntryLampBeam$index", Vector3(0f, 3.76f, z),
                Vector3(10.6f, 0.24f, 0.35f), kit.seamSteel)
            kit.box(kit.root, "EntryLampStrip$index", Vector3(0f, 3.616f, z),
                Vector3(8.2f, 0.028f, 0.16f), if (index == 0) kit.white else kit.cyan)
        }
        Entity().apply {
            setName("EntryWorkLight"); kit.root.addChild(this)
            pose().setPosition(Vector3(0f, 3.54f, -0.1f))
                .setEulerAngles(EulerAngles(-65f, 0f, 0f))
            entryLight = SpotLightComponent(Color4(1f, 0.82f, 0.58f, 1f),
                1800f, 7f, 34f, 55f, false)
            components.set(entryLight)
        }
        buildRoad()
        animate()
        Log.i("Wasteland", "V8_VISUAL_READY rings=${rings.size} banners=${banners.size} housingMin=${bounds.min} housingMax=${bounds.max} light=1800lm/7m staticDetailBatches=4")
    }

    private fun buildBanner(index: Int, anchor: Vector3) {
        val owner = Entity().apply {
            setName("WindBanner$index"); kit.root.addChild(this); pose().setPosition(anchor)
        }
        val joints = mutableListOf<Entity>()
        var parent = owner
        repeat(7) { segment ->
            val joint = Entity().apply {
                setName("BannerJoint${index}_$segment"); parent.addChild(this)
                pose().setPosition(Vector3(0f, if (segment == 0) 0f else -0.16f, 0f))
            }
            kit.box(joint, "BannerCloth${index}_$segment", Vector3(0f, -0.08f, 0f),
                Vector3(0.64f, 0.165f, 0.018f),
                if (index % 2 == 0) kit.paintedOrange else kit.seamSteel)
            if (segment == 1 || segment == 5)
                kit.box(joint, "BannerStripe${index}_$segment", Vector3(0f, -0.08f, 0f),
                    Vector3(0.65f, 0.04f, 0.021f), kit.wallCream)
            joints.add(joint); parent = joint
        }
        banners.add(Banner(joints, index * 1.6f))
    }

    private suspend fun buildRoad() {
        val wear = mutableListOf<VisualGeometry.Box>()
        val patches = mutableListOf<VisualGeometry.Box>()
        val steel = mutableListOf<VisualGeometry.Box>()
        val stones = mutableListOf<VisualGeometry.Box>()
        for (i in 0 until 26) {
            val z = -3.5f - i * 2.35f
            for (side in listOf(-1f, 1f)) {
                wear.add(VisualGeometry.Box(Vector3(side * (1.65f + sin(i * 0.5f) * 0.12f), 0.036f, z),
                    Vector3(0.21f, 0.008f, 1.6f + i % 3 * 0.18f), sin(i * 0.7f) * 3f))
                if (i % 3 == 0) {
                    val x = side * (5.5f + i % 4 * 0.4f)
                    // Shoulder chips are below ankle height; do not clutter the walking strip.
                    stones.add(VisualGeometry.Box(Vector3(x, 0.06f, z - 0.4f),
                        Vector3(0.22f + i % 3 * 0.09f, 0.12f, 0.35f), i * 39f))
                    stones.add(VisualGeometry.Box(Vector3(x + side * 0.45f, 0.035f, z + 0.2f),
                        Vector3(0.16f, 0.07f, 0.22f), i * 61f))
                }
            }
            if (i % 5 == 0) patches.add(VisualGeometry.Box(Vector3(sin(i.toFloat()) * 2.8f, 0.032f, z),
                Vector3(0.65f, 0.007f, 0.8f), i * 19f))
        }
        for (z in listOf(-9f, -26f, -47f)) {
            for (i in 0..16) steel.add(VisualGeometry.Box(Vector3(-3.2f + i * 0.4f, 0.04f, z),
                Vector3(0.09f, 0.013f, 0.52f)))
        }
        // A flush conduit links the early power station with the left shoulder.
        steel.add(VisualGeometry.Box(Vector3(-5.65f, 0.07f, -13.8f), Vector3(0.09f, 0.14f, 5f)))
        val wornRubber = kit.pbr(0.22f, 0.205f, 0.18f, 0.99f)
        kit.batchBoxes(kit.root, "RoadTireWear", wear, wornRubber)
        kit.batchBoxes(kit.root, "RoadRepairPatches", patches, kit.ground)
        kit.batchBoxes(kit.root, "RoadDrainageAndConduit", steel, kit.seamSteel)
        kit.batchBoxes(kit.root, "RoadShoulderChips", stones, kit.wallSand)
    }

    fun update(dt: Float) {
        if (dt <= 0f) return
        clock += dt
        animationDebt += dt
        if (animationDebt < 1f / 30f) return
        animationDebt = 0f
        animate()
    }
    private fun animate() {
        rings.forEachIndexed { i, ring ->
            ring.owner.pose().setEulerAngles(EulerAngles(ring.tilt + sin(clock * 0.7f + i) * 12f,
                clock * ring.speed, i * 19f))
            val angle = clock * (1.2f + i * 0.4f)
            ring.node.pose().setPosition(Vector3(cos(angle) * ring.radius, 0f, sin(angle) * ring.radius))
        }
        banners.forEach { banner ->
            banner.joints.forEachIndexed { segment, joint ->
                joint.pose().setEulerAngles(EulerAngles(
                    5f + sin(clock * 2.2f - segment * 0.65f + banner.phase) * (3f + segment * 0.5f),
                    0f, sin(clock * 1.3f + banner.phase) * 0.7f))
            }
        }
    }
    fun verify(): Boolean {
        val bounds = housing.getVisualBounds(kit.root)
        return rings.size == 3 && banners.size == 4 && bounds.min.y in 0.22f..0.26f &&
            plinth.components[CollisionComponent::class.java] != null &&
            !entryLight.castsShadowEnabled && entryLight.attenuationRadius == 7f
    }
    fun reset() { clock = 0f; animationDebt = 0f; if (rings.isNotEmpty()) animate() }
}
