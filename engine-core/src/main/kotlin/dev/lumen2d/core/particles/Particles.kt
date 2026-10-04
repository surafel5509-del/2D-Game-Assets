/**
 * Lumen2D — CPU particle system.
 *
 * Designed for mobile: a flat, pooled particle array with no per-particle allocation, and
 * emitters that can be configured by the editor's particle panel and serialized into scenes.
 * Supports spray/burst emission, gravity, drag, size/colour/alpha/rotation curves, additive
 * or normal blending, sub-emitter style child bursts (explosions producing sparks) and
 * collision with the physics world for ground dust.
 */
package dev.lumen2d.core.particles

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Rng
import dev.lumen2d.core.math.TAU
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.render.BlendMode
import dev.lumen2d.core.render.Renderer
import dev.lumen2d.core.render.TextureRegion
import kotlin.math.cos
import kotlin.math.sin

/** A single particle. Plain data so the pool can be a flat array. */
class Particle {
    var active: Boolean = false
    var position: Vec2 = Vec2.ZERO
    var velocity: Vec2 = Vec2.ZERO
    var acceleration: Vec2 = Vec2.ZERO
    var life: Float = 0f
    var maxLife: Float = 1f
    var size: Float = 8f
    var rotation: Float = 0f
    var angularVelocity: Float = 0f
    var startColor: Color = Color.WHITE
    var endColor: Color = Color(1f, 1f, 1f, 0f)
    var frameIndex: Int = 0
    val age01: Float get() = if (maxLife <= 0f) 1f else MathUtil.clamp01(1f - life / maxLife)
}

/** Curve used by emitters to animate a value over particle lifetime. */
class ParticleCurve(private val points: FloatArray) {
    init { require(points.size >= 2) { "A curve needs at least one value pair" } }
    val size: Int get() = points.size

    /** Samples the curve at t in 0..1. */
    fun at(t: Float): Float {
        val clamped = MathUtil.clamp01(t)
        val segments = points.size - 1
        val scaled = clamped * segments
        val i = scaled.toInt().coerceIn(0, segments - 1)
        val frac = scaled - i
        return MathUtil.lerp(points[i], points[i + 1], frac)
    }

    fun toList(): List<Float> = points.toList()

    companion object {
        fun constant(v: Float) = ParticleCurve(floatArrayOf(v, v))
        fun ramp(from: Float, to: Float) = ParticleCurve(floatArrayOf(from, to))
        fun ease(from: Float, to: Float, mid: Float) = ParticleCurve(floatArrayOf(from, mid, to))
        fun fromList(list: List<Float>) = ParticleCurve(if (list.size >= 2) list.toFloatArray() else floatArrayOf(0f, 0f))
    }
}

enum class EmissionShape { POINT, CIRCLE, RECT, CONE }

/** Emitter configuration — everything the editor's particle inspector edits. */
class ParticleEmitterConfig {
    var emitting: Boolean = true
    var amount: Int = 16                    // particles per emission
    var lifetime: Float = 0.8f
    var lifetimeVariance: Float = 0.2f
    var speed: Float = 120f
    var speedVariance: Float = 30f
    var direction: Float = -TAU / 4f        // straight up by default
    var spread: Float = TAU / 8f            // cone width
    var gravity: Vec2 = Vec2(0f, 300f)
    var drag: Float = 0.4f
    var angularVelocity: Float = 0f
    var angularVelocityVariance: Float = 0f
    var startSize: Float = 8f
    var endSize: Float = 0f
    var sizeCurve: ParticleCurve = ParticleCurve.ramp(1f, 0f)
    var startColor: Color = Color(1f, 0.9f, 0.4f, 1f)
    var endColor: Color = Color(1f, 0.3f, 0.1f, 0f)
    var blend: BlendMode = BlendMode.ADD
    var shape: EmissionShape = EmissionShape.POINT
    var shapeRadius: Float = 8f
    var shapeSize: Vec2 = Vec2(24f, 8f)
    var oneShot: Boolean = false
    var burstCount: Int = 0                 // >0 emits bursts of this size
    var burstInterval: Float = 0.5f
    var loopBurst: Boolean = true
    var localSpace: Boolean = true          // particles follow the emitter node
    var collide: Boolean = false
    var bounce: Float = 0.4f
    var maxParticles: Int = 256
    var seed: Long = 12345L

    fun copy(): ParticleEmitterConfig { val c = ParticleEmitterConfig(); c.assignFrom(this); return c }

    fun assignFrom(other: ParticleEmitterConfig) {
        emitting = other.emitting; amount = other.amount; lifetime = other.lifetime
        lifetimeVariance = other.lifetimeVariance; speed = other.speed; speedVariance = other.speedVariance
        direction = other.direction; spread = other.spread; gravity = other.gravity; drag = other.drag
        angularVelocity = other.angularVelocity; angularVelocityVariance = other.angularVelocityVariance
        startSize = other.startSize; endSize = other.endSize; sizeCurve = other.sizeCurve
        startColor = other.startColor; endColor = other.endColor; blend = other.blend
        shape = other.shape; shapeRadius = other.shapeRadius; shapeSize = other.shapeSize
        oneShot = other.oneShot; burstCount = other.burstCount; burstInterval = other.burstInterval
        loopBurst = other.loopBurst; localSpace = other.localSpace; collide = other.collide
        bounce = other.bounce; maxParticles = other.maxParticles; seed = other.seed
    }

    fun serialize(): MutableMap<String, Any?> = linkedMapOf(
        "emitting" to emitting, "amount" to amount, "lifetime" to lifetime.toDouble(),
        "lifetimeVariance" to lifetimeVariance.toDouble(), "speed" to speed.toDouble(),
        "speedVariance" to speedVariance.toDouble(), "direction" to direction.toDouble(),
        "spread" to spread.toDouble(), "gravity" to listOf(gravity.x, gravity.y),
        "drag" to drag.toDouble(), "angularVelocity" to angularVelocity.toDouble(),
        "startSize" to startSize.toDouble(), "endSize" to endSize.toDouble(),
        "sizeCurve" to sizeCurve.toList().map { it.toDouble() },
        "startColor" to startColor.toHex(true), "endColor" to endColor.toHex(true),
        "blend" to blend.id, "shape" to shape.name, "shapeRadius" to shapeRadius.toDouble(),
        "shapeSize" to listOf(shapeSize.x, shapeSize.y), "oneShot" to oneShot,
        "burstCount" to burstCount, "burstInterval" to burstInterval.toDouble(), "loopBurst" to loopBurst,
        "localSpace" to localSpace, "collide" to collide, "bounce" to bounce.toDouble(),
        "maxParticles" to maxParticles, "seed" to seed,
    )

    companion object {
        fun fromJson(data: Map<String, Any?>): ParticleEmitterConfig {
            val config = ParticleEmitterConfig()
            config.emitting = (data["emitting"] as? Boolean) ?: true
            (data["amount"] as? Number)?.let { config.amount = it.toInt() }
            (data["lifetime"] as? Number)?.let { config.lifetime = it.toFloat() }
            (data["lifetimeVariance"] as? Number)?.let { config.lifetimeVariance = it.toFloat() }
            (data["speed"] as? Number)?.let { config.speed = it.toFloat() }
            (data["speedVariance"] as? Number)?.let { config.speedVariance = it.toFloat() }
            (data["direction"] as? Number)?.let { config.direction = it.toFloat() }
            (data["spread"] as? Number)?.let { config.spread = it.toFloat() }
            (data["gravity"] as? List<*>)?.let {
                config.gravity = Vec2((it.getOrNull(0) as? Number)?.toFloat() ?: 0f, (it.getOrNull(1) as? Number)?.toFloat() ?: 0f)
            }
            (data["drag"] as? Number)?.let { config.drag = it.toFloat() }
            (data["angularVelocity"] as? Number)?.let { config.angularVelocity = it.toFloat() }
            (data["startSize"] as? Number)?.let { config.startSize = it.toFloat() }
            (data["endSize"] as? Number)?.let { config.endSize = it.toFloat() }
            (data["sizeCurve"] as? List<*>)?.let { list ->
                val values = list.mapNotNull { (it as? Number)?.toFloat() }
                if (values.size >= 2) config.sizeCurve = ParticleCurve(values.toFloatArray())
            }
            (data["startColor"] as? String)?.let { config.startColor = Color.fromHex(it) }
            (data["endColor"] as? String)?.let { config.endColor = Color.fromHex(it) }
            (data["blend"] as? Number)?.let { config.blend = BlendMode.byId(it.toInt()) }
            (data["shape"] as? String)?.let { name ->
                config.shape = runCatching { EmissionShape.valueOf(name) }.getOrDefault(EmissionShape.POINT)
            }
            (data["shapeRadius"] as? Number)?.let { config.shapeRadius = it.toFloat() }
            (data["shapeSize"] as? List<*>)?.let {
                config.shapeSize = Vec2((it.getOrNull(0) as? Number)?.toFloat() ?: 24f, (it.getOrNull(1) as? Number)?.toFloat() ?: 8f)
            }
            config.oneShot = (data["oneShot"] as? Boolean) ?: false
            (data["burstCount"] as? Number)?.let { config.burstCount = it.toInt() }
            (data["burstInterval"] as? Number)?.let { config.burstInterval = it.toFloat() }
            config.loopBurst = (data["loopBurst"] as? Boolean) ?: true
            config.localSpace = (data["localSpace"] as? Boolean) ?: true
            config.collide = (data["collide"] as? Boolean) ?: false
            (data["bounce"] as? Number)?.let { config.bounce = it.toFloat() }
            (data["maxParticles"] as? Number)?.let { config.maxParticles = it.toInt().coerceIn(1, 4096) }
            (data["seed"] as? Number)?.let { config.seed = it.toLong() }
            return config
        }
    }
}

/**
 * The particle simulation + renderer. One instance per particle node.
 */
class ParticleSystem(var config: ParticleEmitterConfig = ParticleEmitterConfig()) {

    private var particles = arrayOfNulls<Particle>(config.maxParticles)
    private var rng = Rng(config.seed)
    private var emissionAccumulator = 0f
    private var burstTimer = 0f
    private var oneShotDone = false

    /** Sum of active particles (profiler + editor readout). */
    var activeCount: Int = 0
        private set
    /** Hook for physics collision (set by the particle node when the tree has a world). */
    var collisionQuery: ((Vec2) -> Boolean)? = null
    val finished: Boolean get() = activeCount == 0 && (config.oneShot && oneShotDone)

    private fun ensureCapacity() {
        if (particles.size != config.maxParticles) {
            val resized = arrayOfNulls<Particle>(config.maxParticles)
            for (i in 0 until minOf(particles.size, resized.size)) resized[i] = particles[i]
            particles = resized
        }
    }

    fun restart() {
        rng = Rng(config.seed)
        oneShotDone = false
        burstTimer = 0f
        for (p in particles) p?.active = false
        activeCount = 0
    }

    fun clear() {
        for (p in particles) p?.active = false
        activeCount = 0
    }

    /** Emits [count] particles immediately (muzzle flashes, explosions, impacts). */
    fun burst(count: Int = config.amount, origin: Vec2 = Vec2.ZERO, direction: Float? = null, emitterRotation: Float = 0f) {
        ensureCapacity()
        for (i in 0 until count) emitOne(origin, direction, emitterRotation)
    }

    private fun emitOne(origin: Vec2, directionOverride: Float?, emitterRotation: Float) {
        val slot = particles.indexOfFirst { it == null || !it.active }
        val particle = if (slot >= 0) {
            (particles[slot] ?: Particle().also { particles[slot] = it })
        } else {
            // Recycle the oldest particle when the budget is exceeded.
            var oldestIndex = 0
            var lowestLife = Float.MAX_VALUE
            for (i in particles.indices) {
                val p = particles[i] ?: continue
                if (p.life < lowestLife) { lowestLife = p.life; oldestIndex = i }
            }
            particles[oldestIndex] ?: Particle().also { particles[oldestIndex] = it }
        }

        val spawnOffset = when (config.shape) {
            EmissionShape.POINT -> Vec2.ZERO
            EmissionShape.CIRCLE -> rng.insideCircle(config.shapeRadius)
            EmissionShape.RECT -> Vec2(rng.nextFloat(-config.shapeSize.x / 2f, config.shapeSize.x / 2f),
                rng.nextFloat(-config.shapeSize.y / 2f, config.shapeSize.y / 2f))
            EmissionShape.CONE -> Vec2.fromAngle(config.direction + emitterRotation, rng.nextFloat(0f, config.shapeRadius))
        }

        val baseDirection = (directionOverride ?: config.direction) + emitterRotation
        val angle = baseDirection + rng.nextFloat(-config.spread / 2f, config.spread / 2f)
        val speed = (config.speed + rng.nextFloat(-config.speedVariance, config.speedVariance)).coerceAtLeast(0f)

        particle.active = true
        particle.position = origin + spawnOffset
        particle.velocity = Vec2(cos(angle) * speed, sin(angle) * speed)
        particle.acceleration = config.gravity
        particle.maxLife = (config.lifetime + rng.nextFloat(-config.lifetimeVariance, config.lifetimeVariance)).coerceAtLeast(0.05f)
        particle.life = particle.maxLife
        particle.size = config.startSize
        particle.rotation = if (config.angularVelocity != 0f) rng.nextFloat(0f, TAU) else 0f
        particle.angularVelocity = config.angularVelocity + rng.nextFloat(-config.angularVelocityVariance, config.angularVelocityVariance)
        particle.startColor = config.startColor
        particle.endColor = config.endColor
        particle.frameIndex = if (config.maxParticles > 0) rng.nextInt(1) else 0
        activeCount++
    }

    /** Advances the simulation. [emitterPosition] is the node's world position. */
    /** Live particles — used by debug draw, the particle editor preview and tests. */
    fun liveParticles(): List<Particle> = particles.filterNotNull().filter { it.active }

    /** Live particle positions (cheap snapshot for gizmos/tests). */
    fun debugPositions(): List<Vec2> = liveParticles().map { it.position }

    fun update(delta: Float, emitterPosition: Vec2 = Vec2.ZERO, emitterRotation: Float = 0f, emitterVisible: Boolean = true) {
        ensureCapacity()
        if (config.emitting && emitterVisible && !(config.oneShot && oneShotDone)) {
            if (config.burstCount > 0) {
                burstTimer -= delta
                if (burstTimer <= 0f) {
                    burst(config.burstCount, Vec2.ZERO, null, emitterRotation)
                    burstTimer = config.burstInterval
                    if (config.oneShot) oneShotDone = true
                }
            } else {
                emissionAccumulator += delta * config.amount
                val toEmit = emissionAccumulator.toInt()
                if (toEmit > 0) {
                    emissionAccumulator -= toEmit
                    burst(toEmit, Vec2.ZERO, null, emitterRotation)
                }
                // A one-shot emitter without burst mode emits its `amount` once and stops.
                if (config.oneShot) { oneShotDone = true; emissionAccumulator = 0f }
            }
        }

        var count = 0
        for (p in particles) {
            if (p == null || !p.active) continue
            p.life -= delta
            if (p.life <= 0f) { p.active = false; continue }
            p.velocity += p.acceleration * delta
            p.velocity *= (1f - MathUtil.clamp(config.drag * delta, 0f, 0.95f))
            p.position += p.velocity * delta
            p.rotation += p.angularVelocity * delta
            if (config.collide && collisionQuery != null && collisionQuery!!.invoke(p.position)) {
                p.velocity = Vec2(p.velocity.x * config.bounce, -p.velocity.y * config.bounce)
                p.position += p.velocity * delta
            }
            count++
        }
        activeCount = count
    }

    /** Draws all active particles at [origin] (the emitter's world position). */
    fun draw(renderer: Renderer, origin: Vec2, region: TextureRegion?, fallbackSize: Float = 3f) {
        for (p in particles) {
            if (p == null || !p.active) continue
            val t = p.age01
            val size = (config.sizeCurve.at(t) * config.startSize + (1f - config.sizeCurve.at(t)) * config.endSize)
                .coerceAtLeast(0.5f)
            val color = p.startColor.lerp(p.endColor, t)
            if (color.a <= 0.004f) continue
            val worldPos = if (config.localSpace) origin + p.position else p.position
            if (region != null) {
                renderer.drawSprite(region, worldPos.x, worldPos.y, size, size, p.rotation, size / 2f, size / 2f, color,
                    blend = config.blend)
            } else {
                renderer.drawCircle(worldPos.x, worldPos.y, size * 0.5f, color, filled = true)
            }
        }
    }

    /** Bounding box of active particles — used for culling. */
    fun bounds(origin: Vec2): Rect {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in particles) {
            if (p == null || !p.active) continue
            val pos = if (config.localSpace) origin + p.position else p.position
            val r = p.size * 0.5f
            if (pos.x - r < minX) minX = pos.x - r
            if (pos.x + r > maxX) maxX = pos.x + r
            if (pos.y - r < minY) minY = pos.y - r
            if (pos.y + r > maxY) maxY = pos.y + r
        }
        if (maxX < minX) return Rect(origin.x - 8f, origin.y - 8f, 16f, 16f)
        return Rect.fromLTRB(minX, minY, maxX, maxY)
    }

    /** Snapshot for the editor's particle preview list. */
    fun debugInfo(): Map<String, Any> = mapOf(
        "active" to activeCount,
        "capacity" to config.maxParticles,
        "emitting" to config.emitting,
        "blend" to config.blend.label,
        "shape" to config.shape.name,
    )
}

/**
 * Ready-made emitter presets — the sample games and the editor's "add particles" menu use
 * these, and they double as documentation for the particle parameters.
 */
object ParticlePresets {
    fun explosion(): ParticleEmitterConfig = ParticleEmitterConfig().apply {
        amount = 48; lifetime = 0.65f; lifetimeVariance = 0.25f
        speed = 210f; speedVariance = 90f; spread = TAU; direction = 0f
        gravity = Vec2(0f, 120f); drag = 1.4f
        startSize = 12f; endSize = 0f
        sizeCurve = ParticleCurve.ease(0.4f, 1f, 0f)
        startColor = Color.fromHex("#FFF3B0"); endColor = Color.fromHex("#EF476F00")
        blend = BlendMode.ADD; oneShot = true; burstCount = 48; loopBurst = false
        maxParticles = 160
    }

    fun dust(): ParticleEmitterConfig = ParticleEmitterConfig().apply {
        amount = 6; lifetime = 0.5f; speed = 40f; speedVariance = 20f
        spread = TAU / 3f; direction = -TAU / 4f; gravity = Vec2(0f, -20f); drag = 2f
        startSize = 5f; endSize = 1f
        startColor = Color.fromHex("#E9ECEFAA"); endColor = Color.fromHex("#ADB5BD00")
        blend = BlendMode.NORMAL; shape = EmissionShape.CIRCLE; shapeRadius = 6f; maxParticles = 64
    }

    fun sparks(): ParticleEmitterConfig = ParticleEmitterConfig().apply {
        amount = 18; lifetime = 0.35f; speed = 260f; speedVariance = 60f
        spread = TAU / 5f; gravity = Vec2(0f, 700f); drag = 0.6f
        startSize = 4f; endSize = 0f
        startColor = Color.fromHex("#FFE066"); endColor = Color.fromHex("#FF6B3500")
        blend = BlendMode.ADD; collide = true; bounce = 0.35f; maxParticles = 128
    }

    fun magic(): ParticleEmitterConfig = ParticleEmitterConfig().apply {
        amount = 12; lifetime = 1.2f; speed = 60f; speedVariance = 25f
        spread = TAU; gravity = Vec2(0f, -40f); drag = 1.6f
        startSize = 6f; endSize = 0f
        startColor = Color.fromHex("#9B5DE5"); endColor = Color.fromHex("#00F5D400")
        blend = BlendMode.ADD; shape = EmissionShape.CIRCLE; shapeRadius = 14f; maxParticles = 192
    }

    fun rain(): ParticleEmitterConfig = ParticleEmitterConfig().apply {
        amount = 24; lifetime = 1.4f; speed = 320f; speedVariance = 40f
        spread = 0.1f; direction = TAU / 4f; gravity = Vec2(0f, 0f); drag = 0f
        startSize = 3f; endSize = 3f
        startColor = Color.fromHex("#8ECAE688"); endColor = Color.fromHex("#8ECAE633")
        blend = BlendMode.NORMAL; shape = EmissionShape.RECT; shapeSize = Vec2(520f, 4f); maxParticles = 256
    }

    fun trail(): ParticleEmitterConfig = ParticleEmitterConfig().apply {
        amount = 30; lifetime = 0.45f; lifetimeVariance = 0.1f
        speed = 12f; speedVariance = 8f; spread = TAU
        gravity = Vec2.ZERO; drag = 3f
        startSize = 7f; endSize = 0f
        startColor = Color.fromHex("#FFD166CC"); endColor = Color.fromHex("#FF5D8F00")
        blend = BlendMode.ADD; maxParticles = 96
    }

    fun heal(): ParticleEmitterConfig = ParticleEmitterConfig().apply {
        amount = 10; lifetime = 0.9f; speed = 45f; speedVariance = 15f
        spread = TAU / 6f; direction = -TAU / 4f; gravity = Vec2(0f, -60f); drag = 1f
        startSize = 6f; endSize = 1f
        startColor = Color.fromHex("#70E000"); endColor = Color.fromHex("#38B00000")
        blend = BlendMode.ADD; maxParticles = 96
    }

    val named: Map<String, () -> ParticleEmitterConfig> = linkedMapOf(
        "Explosion" to ::explosion, "Dust" to ::dust, "Sparks" to ::sparks, "Magic" to ::magic,
        "Rain" to ::rain, "Trail" to ::trail, "Heal" to ::heal,
    )
}
