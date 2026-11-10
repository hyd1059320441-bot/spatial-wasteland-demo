package com.pico.demo.wasteland.game

import android.util.Log
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.ecs.simulation.*
import com.pico.spatial.core.math.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Scene-owned resource pool. Assets are loaded/cloned once, never during frame updates. */
class SceneKit(val root: Entity) : AutoCloseable {
    private val resources = mutableListOf<Resource>()
    val cube = own(MeshResource.createBox(Vector3(1f, 1f, 1f)))
    val sphere = own(MeshResource.createSphere(0.5f))
    private val shape = own(ShapeResource.createBox(Vector3(1f, 1f, 1f)))
    private val physics = own(PhysicsMaterialResource())
    val ink = color(0.04f, 0.05f, 0.07f)
    val orange = color(1f, 0.35f, 0.04f)
    val cyan = color(0.06f, 0.88f, 1f)
    val white = color(0.82f, 0.75f, 0.57f)
    val red = color(1f, 0.08f, 0.04f)
    val violet = color(0.65f, 0.12f, 1f)
    val gold = color(1f, 0.74f, 0.06f)
    val magenta = color(1f, 0.08f, 0.65f)
    val lime = color(0.5f, 1f, 0.06f)
    val outline = color(0.012f, 0.008f, 0.006f).apply {
        setCullingMode(MaterialCullingMode.FRONT)
    }
    val ground = pbr(0.51f, 0.37f, 0.23f, roughness = 0.96f)
    val road = pbr(0.43f, 0.38f, 0.31f, roughness = 0.94f)
    val deck = pbr(0.34f, 0.44f, 0.47f, roughness = 0.9f, metallic = 0.12f)
    val wallSand = pbr(0.62f, 0.56f, 0.44f, roughness = 0.94f)
    val wallCream = pbr(0.69f, 0.63f, 0.49f, roughness = 0.94f)
    val paintedOrange = pbr(0.82f, 0.31f, 0.095f, roughness = 0.84f, metallic = 0.08f)
    val seamSteel = pbr(0.24f, 0.37f, 0.41f, roughness = 0.85f, metallic = 0.12f)
    private val templates = mutableMapOf<String, Entity>()
    private val bundles = mutableListOf<AssetBundle>()
    private val staticShapes = mutableMapOf<MeshResource, ShapeResource>()
    private data class ShadowState(val component: DynamicShadowComponent, val authoredCasting: Boolean,
        val authoredReceiving: Boolean)
    private data class VisualGroup(val models: List<ModelComponent>, val center: Vector3,
        val radius: Float, val range: Float, val shadows: List<ShadowState>,
        val shadowRadius: Float, val shadowRange: Float, var visible: Boolean = true,
        var casting: Boolean = true)
    private val visualGroups = mutableListOf<VisualGroup>()
    private val loadingModels = mutableListOf<ModelComponent>()
    private val worldColliders = linkedSetOf<Entity>()
    private val worldBoxes = linkedSetOf<Entity>()
    private var loading = true
    private var closed = false
    private var visibilityClock = 0f
    private val cache = Entity().apply { setName("AssetTemplates"); root.addChild(this); enabled = false }
    fun <T : Resource> own(r: T): T = r.also {
        it.toGlobal()
        resources.add(it)
    }
    fun color(r: Float, g: Float, b: Float) = own(UnlitMaterial.create()).apply {
        setBaseColor(Color4(r, g, b, 1f))
    }
    fun texture(path: String) = own(TextureResource.load(path))
    fun pbr(r: Float, g: Float, b: Float, roughness: Float, metallic: Float = 0f) =
        own(PhysicallyBasedMaterial.create()).apply {
            setBaseColor(Color4(r, g, b, 1f))
            setRoughness(roughness)
            setMetallic(metallic)
        }
    fun box(parent: Entity, name: String, pos: Vector3, size: Vector3,
        material: Material = ink, collider: Boolean = false, mesh: MeshResource = cube): Entity =
        Entity().apply {
            setName(name); pose().setPosition(pos).setQuaternion(Quat()).setScaleVector(size)
            components.set(ModelComponent(mesh, material).also(::guardLoading))
            if (material is UnlitMaterial)
                components.set(DynamicShadowComponent(castsShadowEnabled = false,
                    receivesShadowEnabled = false))
            if (collider) collision(this)
            parent.addChild(this)
        }
    suspend fun batchBoxes(parent: Entity, name: String, boxes: List<VisualGeometry.Box>,
        material: Material): Entity {
        var created: MeshResource? = null
        val mesh = try {
            withContext(Dispatchers.IO) {
                MeshResource.createWithMeshModel(VisualGeometry.boxes(boxes), name = name)
                    .also { created = it }
            }
        } catch (cancelled: CancellationException) {
            created?.close(); throw cancelled
        }
        if (closed) { mesh.close(); throw CancellationException("Scene disposed during detail import") }
        own(mesh)
        return box(parent, name, Vector3.ZERO, Vector3(1f), material, mesh = mesh)
    }
    fun collision(e: Entity, group: UInt = GROUP or WastelandScene.WORLD_ONLY): CollisionComponent =
        CollisionComponent(listOf(shape), physics,
            collisionFilter = CollisionFilter(CollisionGroup(group))).also {
                e.components.set(it)
                if (group and WastelandScene.WORLD_ONLY != 0u) {
                    worldColliders.add(e); worldBoxes.add(e)
                }
            }
    fun invisibleBox(parent: Entity, name: String, pos: Vector3, size: Vector3,
        group: UInt = GROUP or WastelandScene.WORLD_ONLY): Entity =
        Entity().apply {
            setName(name); pose().setPosition(pos).setQuaternion(Quat()).setScaleVector(size)
            collision(this, group); parent.addChild(this)
        }
    suspend fun model(path: String, parent: Entity, name: String, independent: Boolean = false): Entity {
        val instance = if (independent) Entity.loadSuspend("asset://$path") else {
        val original = templates[path] ?: Entity.loadSuspend("asset://$path").also {
            if (closed) { it.destroy(); throw CancellationException("Scene disposed during import") }
            cache.addChild(it); templates[path] = it
        }
        checkNotNull(original.clone(Entity.CloneOptions(recursive = true,
            shouldShareMaterialInstance = true))) { "模型复制失败：$path" }
        }
        if (closed) { instance.destroy(); throw CancellationException("Scene disposed during model import") }
        return instance.apply {
            setName(name); enabled = true
            fun guard(node: Entity) {
                node.components[ModelComponent::class.java]?.let(::guardLoading)
                node.getChildren().forEach(::guard)
            }
            guard(this); parent.addChild(this)
            removeInteraction(this)
            disableUnlitShadows(this)
            pose().setPosition(Vector3.ZERO).setQuaternion(Quat()).setScaleVector(Vector3(1f, 1f, 1f))
        }
    }
    private fun guardLoading(model: ModelComponent) {
        if (loading) { model.isVisible = false; loadingModels.add(model) }
    }
    suspend fun bundleModel(path: String, sceneName: String, parent: Entity, name: String): Entity {
        var created: AssetBundle? = null
        val bundle = try {
            withContext(Dispatchers.IO) { AssetBundle.load("asset://$path").also { created = it } }
        } catch (cancelled: CancellationException) {
            created?.close(); throw cancelled
        }
        if (closed) { bundle.close(); throw CancellationException("Scene disposed during bundle import") }
        bundles.add(bundle)
        val entity = bundle.loadModelSuspend(sceneName)
        if (closed) { entity.destroy(); throw CancellationException("Scene disposed during bundle model import") }
        entity.setName(name)
        fun guard(node: Entity) {
            node.components[ModelComponent::class.java]?.let(::guardLoading)
            node.getChildren().forEach(::guard)
        }
        guard(entity); parent.addChild(entity)
        removeInteraction(entity); disableUnlitShadows(entity)
        return entity
    }
    /** Parallel async imports; parenting and registry writes resume on the caller's main context. */
    suspend fun preload(paths: List<String>, onProgress: (Int, Int) -> Unit) = coroutineScope {
        var done = 0
        paths.distinct().map { path -> async {
            if (templates[path] == null) {
                val original = Entity.loadSuspend("asset://$path")
                if (closed) { original.destroy(); throw CancellationException("Scene disposed during preload") }
                cache.addChild(original); templates[path] = original
            }
            onProgress(++done, paths.distinct().size)
        } }.forEach { it.await() }
    }
    fun reveal() {
        loading = false
        loadingModels.forEach { it.isVisible = true }
        loadingModels.clear()
    }
    fun collisionDescription(): String = worldColliders.joinToString("\n") { entity ->
        val chain = generateSequence(entity) { it.getParent() }.takeWhile { it != root }.toList()
        chain.joinToString("/") { "${it.getName()}:${it.pose().position}:${it.pose().quaternion}:${it.pose().scaleVector}" }
    }
    /** Ray casts omit a solid that contains their origin. These are the actual unit-box
     * colliders with their complete authored hierarchy, not visual bounding boxes. */
    fun insideWorldBox(point: Vector3): Boolean = worldBoxes.any { entity ->
        val enabled = generateSequence(entity) { it.getParent() }.takeWhile { it != root }
            .all { it.enabled }
        if (!enabled) false else {
            val local = entity.convertPositionFrom(point, root)
            abs(local.x) < 0.5001f && abs(local.y) < 0.5001f && abs(local.z) < 0.5001f
        }
    }
    private fun disableUnlitShadows(entity: Entity) {
        entity.components[ModelComponent::class.java]?.let { model ->
            if (model.materials.all { it is UnlitMaterial })
                entity.components.set(DynamicShadowComponent(castsShadowEnabled = false,
                    receivesShadowEnabled = false))
        }
        entity.getChildren().forEach(::disableUnlitShadows)
    }
    fun removeInteraction(entity: Entity): Int {
        var count = 0
        if (entity.components[InteractableComponent::class.java] != null) {
            entity.components.remove(InteractableComponent::class.java); count++
        }
        return count + entity.getChildren().sumOf { removeInteraction(it) }
    }
    /** Exact authored surfaces, including open doors; only static environment models use triangles. */
    fun staticCollision(entity: Entity) {
        if (entity.getName().endsWith("Ink")) return
        entity.components[ModelComponent::class.java]?.mesh?.takeIf { it.valid }?.let { mesh ->
            val shape = staticShapes.getOrPut(mesh) { own(ShapeResource.createStaticMesh(mesh)) }
            entity.components.set(CollisionComponent(listOf(shape), physics,
                collisionFilter = CollisionFilter(CollisionGroup(GROUP or WastelandScene.WORLD_ONLY))))
            worldColliders.add(entity)
        }
        entity.getChildren().forEach { staticCollision(it) }
    }
    private fun replaceMaterials(entity: Entity, material: Material) {
        entity.components[ModelComponent::class.java]?.let { model ->
            for (index in 0 until model.materials.size) model.materials[index] = material
            // The front-culled ink shell only outlines the visible surface. It is not another object.
            entity.components.set(DynamicShadowComponent(castsShadowEnabled = false,
                receivesShadowEnabled = false))
        }
        entity.getChildren().forEach { replaceMaterials(it, material) }
    }
    /** Generated assets retain their authored PBR surfaces and gain a shared ink silhouette pass. */
    suspend fun outlinedModel(path: String, parent: Entity, name: String,
        inkScale: Float = 1.025f): Entity =
        Entity().apply {
            setName(name); parent.addChild(this)
            val visual = model(path, this, "${name}Surface")
            if (inkScale > 1f) {
                val inkPass = model(path, this, "${name}Ink")
                replaceMaterials(inkPass, outline)
                inkPass.pose().setScaleVector(Vector3(inkScale))
                inkPass.enabled = !RunOptions.profileNoInk
            }
            visual.pose().setPosition(Vector3.ZERO)
        }

    /** Place and ground a V3 asset using its measured runtime bounds. */
    suspend fun v3Asset(file: String, name: String, x: Float, z: Float, size: Vector3,
        yaw: Float = 0f, groundY: Float = 0f, exactSize: Boolean = true,
        directory: String = "v3"): Entity {
        val placement = Entity().apply { setName(name); root.addChild(this) }
        // Architectural black shells expand into large dark planes; authored V4 ink needs no clone.
        val inkScale = 1f
        val asset = outlinedModel("models/$directory/$file.usdz", placement, "${name}Model", inkScale)
        asset.pose().setEulerAngles(EulerAngles(0f, yaw, 0f))
        val raw = asset.getVisualBounds(placement)
        check(!raw.isEmpty() && raw.size.x > 0f && raw.size.y > 0f && raw.size.z > 0f) {
            "V3 model bounds invalid: $file"
        }
        val sx = size.x / raw.size.x
        val sy = size.y / raw.size.y
        val sz = size.z / raw.size.z
        placement.pose().setScaleVector(if (exactSize) Vector3(sx, sy, sz) else {
            val uniform = minOf(sx, sy, sz)
            Vector3(uniform, uniform, uniform)
        })
        val fitted = placement.getVisualBounds(root)
        placement.pose().setPosition(Vector3(x - fitted.center.x, groundY - fitted.min.y,
            z - fitted.center.z))
        val finalBounds = placement.getVisualBounds(root)
        check(abs(finalBounds.min.y - groundY) < 0.02f) {
            "V3 model grounding failed: $name bottom=${finalBounds.min.y} expected=$groundY"
        }
        if (exactSize) check(abs(finalBounds.size.x - size.x) < 0.03f &&
            abs(finalBounds.size.y - size.y) < 0.03f && abs(finalBounds.size.z - size.z) < 0.03f) {
            "Model fit failed: $name actual=${finalBounds.size} expected=$size"
        }
        Log.i("Wasteland", "V3_ASSET $name file=$file bottom=${finalBounds.min.y} size=${finalBounds.size}")
        if (name == "EntryRepairBay" || name in V3Layout.shippingContainers || directory == "v8")
            Log.i("Wasteland", "V8_PLACEMENT id=$name owner=WastelandMineV3 min=${finalBounds.min} max=${finalBounds.max} scale=${placement.pose().scaleVector} position=${placement.pose().position} numeric=PASS epsilon=0.03 visual=UNOBSERVED")
        val models = mutableListOf<ModelComponent>()
        fun collect(node: Entity) {
            node.components[ModelComponent::class.java]?.let { models.add(it) }
            node.getChildren().forEach(::collect)
        }
        collect(placement)
        val shadows = mutableListOf<ShadowState>()
        fun collectShadows(node: Entity) {
            node.components[ModelComponent::class.java]?.let { model ->
                if (model.materials.any { it !is UnlitMaterial }) {
                    val component = node.components[DynamicShadowComponent::class.java]
                        ?: DynamicShadowComponent().also { node.components.set(it) }
                    shadows.add(ShadowState(component, component.castsShadowEnabled,
                        component.receivesShadowEnabled))
                }
            }
            node.getChildren().forEach(::collectShadows)
        }
        // Moving mission items retain their authored casting; static X/Z bounds drive the budget.
        if (name !in setOf("ReactorGate", "LootChest", "ElectricCore", "GeneratorStatus"))
            collectShadows(placement)
        visualGroups.add(VisualGroup(models, finalBounds.center, finalBounds.size.length() * 0.5f,
            if (size.y >= 8f || directory == "v5") 130f else if (size.y >= 4f) 85f else 55f,
            shadows, kotlin.math.hypot(finalBounds.size.x, finalBounds.size.z) * 0.5f,
            if (size.y >= 4f || directory == "v5") 32f else 22f))
        return placement
    }
    /** Rendering-only culling retains static collision, navigation and interaction surfaces. */
    fun updateVisibility(eye: Vector3, forward: Vector3, dt: Float) {
        visibilityClock -= dt
        if (visibilityClock > 0f) return
        visibilityClock = 0.1f
        var visibleCount = 0
        visualGroups.forEach {
            val d = it.center - eye
            val visible = VisibilityBudget.visible(d.x, d.y, d.z, it.radius, it.range,
                forward.x, forward.y, forward.z)
            if (visible != it.visible) {
                it.models.forEach { model -> model.isVisible = visible }
                it.visible = visible
            }
            // Off-screen nearby casters still matter; this is independent of gaze culling.
            val casting = RunOptions.profileFullShadows ||
                VisibilityBudget.castsShadow(d.x, d.z, it.shadowRadius, it.shadowRange, it.casting)
            if (casting != it.casting) {
                it.shadows.forEach { shadow ->
                    shadow.component.castsShadowEnabled = casting && shadow.authoredCasting
                }
                it.casting = casting
            }
            if (visible) visibleCount++
        }
        if (RunOptions.selfCheck) Log.i("Wasteland",
            "V5_VISIBILITY groups=$visibleCount/${visualGroups.size} collisions=retained")
    }
    fun verifyShadowBudget(): Int {
        var checked = 0
        visualGroups.forEach { group ->
            group.shadows.forEach { shadow ->
                check(shadow.component.castsShadowEnabled == (group.casting && shadow.authoredCasting))
                check(shadow.component.receivesShadowEnabled == shadow.authoredReceiving)
                checked++
            }
        }
        check(checked > 0)
        if (!RunOptions.profileFullShadows)
            check(visualGroups.any { !it.casting && it.shadows.any { shadow -> shadow.authoredCasting } })
        return checked
    }
    fun verifyShadowOverrides(): Int {
        var checked = 0
        fun visit(entity: Entity, inkShell: Boolean) {
            val ink = inkShell || entity.getName().endsWith("Ink")
            entity.components[ModelComponent::class.java]?.let { model ->
                if (ink || model.materials.all { it is UnlitMaterial }) {
                    val shadow = entity.components[DynamicShadowComponent::class.java]
                    check(shadow != null && !shadow.castsShadowEnabled && !shadow.receivesShadowEnabled) {
                        "Unlit/ink model casts dynamic shadow: ${entity.getName()}"
                    }
                    checked++
                }
            }
            entity.getChildren().forEach { visit(it, ink) }
        }
        // The disabled source templates keep their authored components; instances own overrides.
        root.getChildren().filter { it != cache }.forEach { visit(it, false) }
        return checked
    }
    override fun close() {
        closed = true
        bundles.forEach { it.close() }; bundles.clear()
        resources.forEach { it.close() }; resources.clear()
    }
    companion object { const val GROUP = 256u }
}

internal fun Entity.pose(): TransformComponent =
    components[TransformComponent::class.java] ?: TransformComponent().also { components.set(it) }
