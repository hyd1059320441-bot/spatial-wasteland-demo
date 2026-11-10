package com.pico.demo.wasteland.game

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ParticleComponent
import com.pico.spatial.core.ecs.particle.*
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3

enum class ParticleFxProfile(
    val colorA: Color4,
    val colorB: Color4,
    val particles: IntRange,
    val size: ClosedFloatingPointRange<Float>,
    val speed: ClosedFloatingPointRange<Float>,
    val lifetime: ClosedFloatingPointRange<Float>,
    val acceleration: Vector3,
    val blending: ParticleBlendingMode,
) {
    MUZZLE(
        Color4(1f, 0.63f, 0.19f, 0.8f), Color4(0.9f, 0.16f, 0.01f, 0.65f),
        7..11, 0.025f..0.075f, 0.3f..0.85f, 0.04f..0.09f,
        Vector3.ZERO, ParticleBlendingMode.ADDITIVE,
    ),
    SPARK(
        Color4(1f, 0.72f, 0.28f, 0.9f), Color4(1f, 0.18f, 0.01f, 0.75f),
        10..18, 0.018f..0.055f, 1.4f..4.2f, 0.12f..0.42f,
        Vector3(0f, -5.5f, 0f), ParticleBlendingMode.ADDITIVE,
    ),
    DUST(
        Color4(0.62f, 0.33f, 0.14f, 0.75f), Color4(0.22f, 0.12f, 0.08f, 0.35f),
        12..22, 0.06f..0.18f, 0.35f..1.25f, 0.38f..0.82f,
        Vector3(0f, 0.25f, 0f), ParticleBlendingMode.ALPHA,
    ),
    ELECTRIC(
        Color4(0.1f, 0.75f, 1f, 0.85f), Color4(0.01f, 0.35f, 0.85f, 0.75f),
        12..24, 0.014f..0.048f, 0.8f..2.5f, 0.08f..0.28f,
        Vector3.ZERO, ParticleBlendingMode.ADDITIVE,
    ),
    CORE_BREAK(
        Color4(0.12f, 0.8f, 1f, 0.85f), Color4(0.02f, 0.5f, 1f, 0f),
        6..10, 0.014f..0.03f, 0.4f..0.9f, 0.08f..0.18f,
        Vector3(0f, -2.5f, 0f), ParticleBlendingMode.ADDITIVE,
    ),
    EXPLOSION(
        Color4(1f, 0.7f, 0.1f, 1f), Color4(0.75f, 0.06f, 0.01f, 0f),
        20..32, 0.09f..0.3f, 1.5f..4f, 0.15f..0.4f,
        Vector3(0f, -1f, 0f), ParticleBlendingMode.ADDITIVE,
    ),
    BLAST_SMOKE(
        Color4(0.5f, 0.36f, 0.22f, 0.38f), Color4(0.2f, 0.15f, 0.1f, 0f),
        6..12, 0.16f..0.38f, 0.3f..0.8f, 0.45f..0.8f,
        Vector3(0.15f, 0.5f, 0f), ParticleBlendingMode.ALPHA,
    ),
    SMOKE(
        Color4(0.33f, 0.22f, 0.16f, 0.5f), Color4(0.12f, 0.09f, 0.07f, 0f),
        18..28, 0.5f..1.5f, 0.3f..1.1f, 1.2f..2.4f,
        Vector3(0.3f, 0.5f, 0f), ParticleBlendingMode.ALPHA,
    ),
}

/** Native billboard particles; a looping emitter with gated birth rate avoids one-shot time resets. */
class ParticleFxPool(
    private val kit: SceneKit,
    name: String,
    private val profile: ParticleFxProfile,
    slots: Int = 8,
) {
    private data class Emitter(val entity: Entity, val component: ParticleComponent,
        var emitting: Float = 0f, var life: Float = 0f, var fresh: Boolean = false)
    private val zeroRate = ParticleFloatVaryingProperty(value = 0f)
    private val rate = ParticleFloatVaryingProperty(value = profile.particles.last / 0.055f)
    private val texture by lazy { kit.texture(if (profile.blending == ParticleBlendingMode.ALPHA)
        "textures/particle-smoke.png" else "textures/particle-flare.png") }
    private val emitters by lazy { List(slots) { index ->
        val entity = Entity().apply { setName("$name$index"); kit.root.addChild(this); enabled = false }
        val component = ParticleComponent().apply {
            isEmitting = true; isLoop = true
            emissionDuration = ParticleFloatProperty(value = 3600f)
            idleDuration = ParticleFloatProperty(value = 0f)
            birthRateFull = zeroRate
            mainBurstCount = ParticleIntVaryingProperty(value = 0)
            isMainSimulationEnabled = true; isSubSimulationEnabled = false
            emitterShape = ParticleEmitterShape(ParticleEmitterShapeType.POINT, Vector3(0.015f))
            mainSimulation.apply {
                maxParticleCount = profile.particles.last * 2
                particleType = ParticleType.BILLBOARD
                facingMode = ParticleFacingMode.CAMERA_POSITION
                simulationSpace = ParticleSpace.LOCAL
                descriptionSpace = ParticleSpace.LOCAL
                startColor = ParticleColorVaryingProperty(ParticleVaryingPropertyType.RANDOM,
                    profile.colorA, profile.colorB)
                startSize = ParticleFloatVaryingProperty(ParticleVaryingPropertyType.RANDOM,
                    profile.size.start, profile.size.endInclusive)
                startSpeed = ParticleFloatVaryingProperty(ParticleVaryingPropertyType.RANDOM,
                    profile.speed.start, profile.speed.endInclusive)
                lifetime = ParticleFloatVaryingProperty(ParticleVaryingPropertyType.RANDOM,
                    profile.lifetime.start, profile.lifetime.endInclusive)
                emitSpreadingAngle = if (profile == ParticleFxProfile.MUZZLE) 30f else 180f
                isMotionEnabled = true
                acceleration = profile.acceleration
                isColorGradientEnabled = true
                colorGradient = ParticleColorGradient(profile.colorA,
                    if (profile == ParticleFxProfile.CORE_BREAK || profile == ParticleFxProfile.ELECTRIC)
                        Color4(0.01f, 0.25f, 1f, 0f) else Color4(0.12f, 0.08f, 0.03f, 0f))
                material.materialType = ParticleMaterialType.DEFAULT_UNLIT
                material.blendingMode = profile.blending
                material.colorMode = ParticleColorMode.MULTIPLY
                material.baseColorMap = texture
            }
        }
        entity.components.set(component)
        Emitter(entity, component)
    } }
    private var cursor = 0
    private var suspended = false
    var bursts = 0; private set

    fun initialize() { emitters }
    fun burst(position: Vector3, rotation: com.pico.spatial.core.math.Quat = com.pico.spatial.core.math.Quat()) {
        val slot = emitters[cursor++ % emitters.size]
        slot.entity.enabled = true
        slot.entity.pose().setPosition(position).setQuaternion(rotation)
        slot.component.birthRateFull = rate
        slot.emitting = 0.055f
        slot.life = 0.055f + profile.lifetime.endInclusive
        slot.fresh = true
        bursts++
    }

    fun update(dt: Float) {
        if (dt <= 0f) {
            // Discard transient effects on pause, so a partly emitted shot cannot replay on resume.
            if (!suspended) reset()
            suspended = true
            return
        }
        suspended = false
        emitters.forEach { slot ->
            if (slot.life <= 0f) return@forEach
            if (slot.fresh) { slot.fresh = false; return@forEach }
            slot.life = (slot.life - dt).coerceAtLeast(0f)
            if (slot.emitting > 0f) {
                slot.emitting = (slot.emitting - dt).coerceAtLeast(0f)
                if (slot.emitting == 0f) slot.component.birthRateFull = zeroRate
            }
            if (slot.life == 0f) slot.entity.enabled = false
        }
    }
    fun reset() {
        suspended = false
        emitters.forEach {
            if (it.life > 0f) {
                it.component.birthRateFull = zeroRate
                it.entity.enabled = false
            }
            it.life = 0f; it.emitting = 0f; it.fresh = false
        }
    }
    fun activeCount(): Int = emitters.count { it.life > 0f }
    fun verifyIdle(): Boolean = emitters.filter { it.life <= 0f }.all {
        !it.entity.enabled && it.component.birthRateFull.value == 0f
    }
    fun verifyEmission(): Boolean = emitters.filter { it.emitting > 0f }.all {
        it.component.isEmitting && it.component.birthRateFull.value > 0f &&
            it.component.mainSimulation.maxParticleCount == profile.particles.last * 2
    }
}
