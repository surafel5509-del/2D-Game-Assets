/**
 * Lumen2D — animation system.
 *
 * [Animation] resources hold tracks (property keyframes, sprite frames, method calls, audio
 * cues, particle bursts). [AnimationPlayerNode] plays them with blending, speed scaling,
 * looping and signals. The editor's animation timeline edits these structures directly, and
 * the particle/curve editor reuses [Curve].
 */
package dev.lumen2d.core.anim

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Easing
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.scene.AnimatedSprite2D
import dev.lumen2d.core.particles.ParticleEmitterConfig
import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.Node2D
import dev.lumen2d.core.scene.ParticlesNode
import dev.lumen2d.core.scene.Sprite2D
import dev.lumen2d.core.util.Event
import dev.lumen2d.core.util.Signal

/** A single keyframe on a track. */
class Keyframe(
    var time: Float,
    var value: Any?,
    /** Easing applied from this key to the next. */
    var easing: String = "linear",
) {
    fun serialize(): Map<String, Any?> = mapOf("time" to time.toDouble(), "value" to encodeValue(value), "easing" to easing)

    companion object {
        fun fromJson(data: Map<String, Any?>): Keyframe = Keyframe(
            time = (data["time"] as? Number)?.toFloat() ?: 0f,
            value = data["value"],
            easing = data["easing"] as? String ?: "linear",
        )

        fun encodeValue(value: Any?): Any? = when (value) {
            is Vec2 -> listOf(value.x, value.y)
            is Color -> value.toHex(true)
            else -> value
        }
    }
}

enum class TrackType { PROPERTY, SPRITE_FRAME, CALL, AUDIO, PARTICLES, VISIBILITY }

/** Base animation track. */
abstract class AnimationTrack(
    var nodePath: String,
    var type: TrackType,
    var enabled: Boolean = true,
) {
    abstract fun evaluate(animation: Animation, time: Float, player: AnimationPlayerNode)

    abstract fun serialize(): Map<String, Any?>
}

/** Animates any inspector property of a node (position, rotation, modulate, scale, ...). */
class PropertyTrack(
    nodePath: String,
    /** Property name on the target node. */
    var property: String,
    var keyframes: MutableList<Keyframe> = ArrayList(),
) : AnimationTrack(nodePath, TrackType.PROPERTY) {

    fun addKey(time: Float, value: Any?, easing: String = "linear"): PropertyTrack {
        keyframes.add(Keyframe(time, value, easing))
        keyframes.sortBy { it.time }
        return this
    }

    override fun evaluate(animation: Animation, time: Float, player: AnimationPlayerNode) {
        if (keyframes.isEmpty()) return
        val target = player.resolveTarget(nodePath) ?: return
        if (time <= keyframes.first().time) { target.applyProperty(property, keyframes.first().value); return }
        if (time >= keyframes.last().time) { target.applyProperty(property, keyframes.last().value); return }
        for (i in 0 until keyframes.size - 1) {
            val a = keyframes[i]; val b = keyframes[i + 1]
            if (time in a.time..b.time) {
                val span = (b.time - a.time).coerceAtLeast(1e-5f)
                val raw = (time - a.time) / span
                val t = Easing.byName(a.easing)(raw)
                target.applyProperty(property, interpolate(a.value, b.value, t))
                return
            }
        }
    }

    private fun interpolate(a: Any?, b: Any?, t: Float): Any? = when {
        a is Number && b is Number -> a.toFloat() + (b.toFloat() - a.toFloat()) * t
        a is Vec2 && b is Vec2 -> a.lerp(b, t)
        a is Color && b is Color -> a.lerp(b, t)
        a is Boolean -> if (t < 0.5f) a else b
        else -> if (t < 1f) a else b
    }

    override fun serialize(): Map<String, Any?> = linkedMapOf(
        "type" to "property", "nodePath" to nodePath, "property" to property,
        "keyframes" to keyframes.map { it.serialize() },
    )

    companion object {
        fun fromJson(data: Map<String, Any?>): PropertyTrack {
            val track = PropertyTrack(
                nodePath = data["nodePath"] as? String ?: "",
                property = data["property"] as? String ?: "position",
            )
            ((data["keyframes"] as? List<*>) ?: emptyList<Any?>()).forEach { raw ->
                (raw as? Map<*, *>)?.let {
                    @Suppress("UNCHECKED_CAST")
                    track.keyframes.add(Keyframe.fromJson(it as Map<String, Any?>))
                }
            }
            track.keyframes.sortBy { it.time }
            return track
        }
    }
}

/** Drives an [AnimatedSprite2D] or [Sprite2D] frame over time. */
class FrameTrack(
    nodePath: String,
    var frames: MutableList<Pair<Float, Int>> = ArrayList(),
) : AnimationTrack(nodePath, TrackType.SPRITE_FRAME) {

    fun addFrame(time: Float, frame: Int): FrameTrack {
        frames.add(time to frame); frames.sortBy { it.first }; return this
    }

    override fun evaluate(animation: Animation, time: Float, player: AnimationPlayerNode) {
        if (frames.isEmpty()) return
        val target = player.resolveTarget(nodePath) ?: return
        var chosen = frames.first().second
        for ((frameTime, frame) in frames) if (time >= frameTime) chosen = frame
        when (target) {
            is AnimatedSprite2D -> target.setFrame(chosen)
            is Sprite2D -> target.frame = chosen
        }
    }

    override fun serialize(): Map<String, Any?> = mapOf(
        "type" to "frames", "nodePath" to nodePath,
        "frames" to frames.map { mapOf("time" to it.first.toDouble(), "frame" to it.second) })

    companion object {
        fun fromJson(data: Map<String, Any?>): FrameTrack {
            val track = FrameTrack(data["nodePath"] as? String ?: "")
            ((data["frames"] as? List<*>) ?: emptyList<Any?>()).forEach { raw ->
                (raw as? Map<*, *>)?.let { map ->
                    val time = (map["time"] as? Number)?.toFloat() ?: 0f
                    val frame = (map["frame"] as? Number)?.toInt() ?: 0
                    track.frames.add(time to frame)
                }
            }
            track.frames.sortBy { it.first }
            return track
        }
    }
}

/** Calls a script method at a point on the timeline (footstep sounds, damage windows). */
class CallTrack(
    nodePath: String,
    var method: String,
    var calls: MutableList<Pair<Float, List<Any?>>> = ArrayList(),
) : AnimationTrack(nodePath, TrackType.CALL) {

    fun addCall(time: Float, vararg args: Any?): CallTrack {
        calls.add(time to args.toList()); calls.sortBy { it.first }; return this
    }

    override fun evaluate(animation: Animation, time: Float, player: AnimationPlayerNode) {
        // Call tracks are fired by the player at the moment the playhead crosses them.
    }

    override fun serialize(): Map<String, Any?> = mapOf(
        "type" to "call", "nodePath" to nodePath, "method" to method,
        "calls" to calls.map { mapOf("time" to it.first.toDouble(), "args" to it.second) })

    companion object {
        fun fromJson(data: Map<String, Any?>): CallTrack {
            val track = CallTrack(data["nodePath"] as? String ?: "", data["method"] as? String ?: "")
            ((data["calls"] as? List<*>) ?: emptyList<Any?>()).forEach { raw ->
                (raw as? Map<*, *>)?.let { map ->
                    val time = (map["time"] as? Number)?.toFloat() ?: 0f
                    val args = (map["args"] as? List<*>) ?: emptyList<Any?>()
                    track.calls.add(time to args)
                }
            }
            return track
        }
    }
}

/** Plays a sound at a timeline point (via the node's SceneResources audio). */
class AudioTrack(
    nodePath: String,
    var clipId: String,
    var cues: MutableList<Float> = ArrayList(),
    var volume: Float = 1f,
) : AnimationTrack(nodePath, TrackType.AUDIO) {

    fun addCue(time: Float): AudioTrack { cues.add(time); cues.sort(); return this }

    override fun evaluate(animation: Animation, time: Float, player: AnimationPlayerNode) {}

    override fun serialize(): Map<String, Any?> = mapOf(
        "type" to "audio", "nodePath" to nodePath, "clip" to clipId, "volume" to volume.toDouble(),
        "cues" to cues.map { it.toDouble() })

    companion object {
        fun fromJson(data: Map<String, Any?>): AudioTrack = AudioTrack(
            data["nodePath"] as? String ?: "",
            data["clip"] as? String ?: "",
            ((data["cues"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { (it as? Number)?.toFloat() }
                .toMutableList(),
            (data["volume"] as? Number)?.toFloat() ?: 1f,
        )
    }
}

/** Triggers particle bursts at timeline points (impacts, dust, magic). */
class ParticlesTrack(
    nodePath: String,
    var bursts: MutableList<Triple<Float, Int, Boolean>> = ArrayList(),   // time, count, restart
) : AnimationTrack(nodePath, TrackType.PARTICLES) {

    override fun evaluate(animation: Animation, time: Float, player: AnimationPlayerNode) {}

    override fun serialize(): Map<String, Any?> = mapOf(
        "type" to "particles", "nodePath" to nodePath,
        "bursts" to bursts.map { mapOf("time" to it.first.toDouble(), "count" to it.second, "restart" to it.third) })

    companion object {
        fun fromJson(data: Map<String, Any?>): ParticlesTrack {
            val track = ParticlesTrack(data["nodePath"] as? String ?: "")
            ((data["bursts"] as? List<*>) ?: emptyList<Any?>()).forEach { raw ->
                (raw as? Map<*, *>)?.let { map ->
                    track.bursts.add(Triple(
                        (map["time"] as? Number)?.toFloat() ?: 0f,
                        (map["count"] as? Number)?.toInt() ?: 12,
                        (map["restart"] as? Boolean) ?: false))
                }
            }
            return track
        }
    }
}

/** An animation clip: length, looping behaviour and tracks. */
class Animation(
    var name: String,
    var length: Float = 1f,
    var loop: Boolean = false,
    var speed: Float = 1f,
) {
    val tracks = ArrayList<AnimationTrack>()

    val trackCount: Int get() = tracks.size

    fun addPropertyTrack(nodePath: String, property: String): PropertyTrack =
        PropertyTrack(nodePath, property).also { tracks.add(it) }

    fun addFrameTrack(nodePath: String): FrameTrack = FrameTrack(nodePath).also { tracks.add(it) }

    fun addCallTrack(nodePath: String, method: String): CallTrack =
        CallTrack(nodePath, method).also { tracks.add(it) }

    fun addAudioTrack(nodePath: String, clipId: String): AudioTrack =
        AudioTrack(nodePath, clipId).also { tracks.add(it) }

    fun addParticlesTrack(nodePath: String): ParticlesTrack = ParticlesTrack(nodePath).also { tracks.add(it) }

    fun removeTrack(track: AnimationTrack) { tracks.remove(track) }

    fun serialize(): Map<String, Any?> = linkedMapOf(
        "name" to name, "length" to length.toDouble(), "loop" to loop, "speed" to speed.toDouble(),
        "tracks" to tracks.map { it.serialize() },
    )

    companion object {
        fun fromJson(data: Map<String, Any?>): Animation {
            val animation = Animation(
                name = data["name"] as? String ?: "Animation",
                length = (data["length"] as? Number)?.toFloat() ?: 1f,
                loop = data["loop"] as? Boolean ?: false,
                speed = (data["speed"] as? Number)?.toFloat() ?: 1f,
            )
            ((data["tracks"] as? List<*>) ?: emptyList<Any?>()).forEach { raw ->
                val map = raw as? Map<*, *> ?: return@forEach
                @Suppress("UNCHECKED_CAST")
                val typed = map as Map<String, Any?>
                val track = when (typed["type"] as? String) {
                    "property" -> PropertyTrack.fromJson(typed)
                    "frames" -> FrameTrack.fromJson(typed)
                    "call" -> CallTrack.fromJson(typed)
                    "audio" -> AudioTrack.fromJson(typed)
                    "particles" -> ParticlesTrack.fromJson(typed)
                    else -> null
                }
                if (track != null) animation.tracks.add(track)
            }
            return animation
        }
    }
}

/**
 * Plays [Animation] resources on the subtree it belongs to.
 * Supports queueing, blending between clips, per-animation speed and one-shot callbacks.
 */
open class AnimationPlayerNode(name: String = "AnimationPlayer") : Node(name) {

    val animations = LinkedHashMap<String, Animation>()
    var autoplay: String = ""
    var defaultSpeed: Float = 1f
    var playing: Boolean = false
        private set
    var currentAnimation: String = ""
        private set
    var position: Float = 0f
        private set

    val animationStarted = Signal<String>()
    val animationFinished = Signal<String>()
    val animationLooped = Signal<String>()

    private var queued: String? = null
    private val firedCalls = HashSet<String>()
    private val firedAudio = HashSet<String>()

    val current: Animation? get() = animations[currentAnimation]

    val progress: Float get() = current?.let { if (it.length <= 0f) 0f else position / it.length } ?: 0f

    override fun onReady() { if (autoplay.isNotEmpty()) play(autoplay) }

    fun addAnimation(animation: Animation): AnimationPlayerNode {
        animations[animation.name] = animation
        return this
    }

    fun play(animationName: String, speed: Float = 1f, queue: String? = null, startAt: Float = 0f) {
        val animation = animations[animationName] ?: return
        currentAnimation = animationName
        this.speed = speed
        position = startAt
        playing = true
        queued = queue
        firedCalls.clear(); firedAudio.clear()
        animationStarted.emit(animationName)
        // Apply the first frame immediately so one-frame clips look right on the very first frame.
        for (track in animation.tracks) track.evaluate(animation, position, this)
        for (track in animation.tracks) {
            if (track is FrameTrack && track.frames.isNotEmpty()) track.evaluate(animation, position, this)
        }
    }

    fun queue(animationName: String) { queued = animationName }

    fun stop() { playing = false; position = 0f }

    fun pause() { playing = false }

    fun resume() { playing = true }

    fun seek(seconds: Float) {
        val animation = current ?: return
        position = seconds.coerceIn(0f, animation.length)
        for (track in animation.tracks) track.evaluate(animation, position, this)
    }

    var speed: Float = 1f

    override fun onProcess(delta: Float) {
        val animation = current ?: return
        if (!playing) return
        val previous = position
        position += delta * speed * defaultSpeed * animation.speed

        // Fire call / audio / particle cues crossed since the previous frame.
        fireCues(animation, previous, position)

        if (position >= animation.length) {
            if (animation.loop) {
                position = MathUtil.wrap(position, 0f, animation.length)
                animationLooped.emit(animation.name)
                firedCalls.clear(); firedAudio.clear()
            } else {
                position = animation.length
                for (track in animation.tracks) track.evaluate(animation, position, this)
                playing = false
                animationFinished.emit(animation.name)
                queued?.let { next -> queued = null; play(next) }
                return
            }
        }
        for (track in animation.tracks) track.evaluate(animation, position, this)
    }

    private fun fireCues(animation: Animation, from: Float, to: Float) {
        for ((index, track) in animation.tracks.withIndex()) {
            when (track) {
                is CallTrack -> for ((time, args) in track.calls) {
                    val key = "$index:$time"
                    if (time > from && time <= to && firedCalls.add(key)) {
                        resolveTarget(track.nodePath)?.scriptBehavior?.callMethod(track.method, args)
                    }
                }
                is AudioTrack -> for (time in track.cues) {
                    val key = "$index:$time"
                    if (time > from && time <= to && firedAudio.add(key)) {
                        val clip = tree?.resources?.audioClip(track.clipId) ?: continue
                        tree?.resources?.audio?.play(clip, "SFX", track.volume)
                    }
                }
                is ParticlesTrack -> for ((time, count, restart) in track.bursts) {
                    if (time > from && time <= to) {
                        val node = resolveTarget(track.nodePath)
                        if (node is ParticlesNode) { if (restart) node.restart(); node.emit(count) }
                    }
                }
                else -> {}
            }
        }
    }

    /** Resolves a track's node path relative to the animation player. */
    fun resolveTarget(path: String): Node? {
        if (path.isEmpty() || path == ".") return parent ?: this
        return (parent ?: this).nodePath(path)
    }

    override fun propertyDefinitions(): List<dev.lumen2d.core.scene.PropertyDef> = listOf(
        dev.lumen2d.core.scene.PropertyDef("autoplay", dev.lumen2d.core.scene.PropertyType.STRING, "Autoplay", "", category = "Animation",
            enumValues = listOf("") + animations.keys.toList()),
        dev.lumen2d.core.scene.PropertyDef("defaultSpeed", dev.lumen2d.core.scene.PropertyType.FLOAT, "Speed", 1f, min = 0f, max = 8f, step = 0.05f, category = "Animation"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "autoplay" -> autoplay
        "defaultSpeed" -> defaultSpeed
        "currentAnimation" -> currentAnimation
        "isPlaying" -> playing
        "animationCount" -> animations.size
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "autoplay" -> { autoplay = value?.toString() ?: ""; return true }
            "defaultSpeed" -> { defaultSpeed = (value as? Number)?.toFloat() ?: 1f; return true }
        }
        return super.setProperty(property, value)
    }

    override fun serialize(withChildren: Boolean): MutableMap<String, Any?> {
        val out = super.serialize(withChildren)
        val props = (out["properties"] as? MutableMap<String, Any?>) ?: LinkedHashMap()
        props["_animations"] = animations.values.map { it.serialize() }
        out["properties"] = props
        return out
    }

    override fun deserialize(data: Map<String, Any?>, registry: dev.lumen2d.core.scene.NodeRegistry) {
        super.deserialize(data, registry)
        val props = data["properties"] as? Map<*, *>
        ((props?.get("_animations") as? List<*>) ?: emptyList<Any?>()).forEach { raw ->
            (raw as? Map<*, *>)?.let {
                @Suppress("UNCHECKED_CAST")
                addAnimation(Animation.fromJson(it as Map<String, Any?>))
            }
        }
    }
}

/** Editable easing curve with arbitrary control points (curve editor + particle size curves). */
class Curve(points: List<Vec2> = listOf(Vec2(0f, 0f), Vec2(1f, 1f))) {
    val points: MutableList<Vec2> = points.toMutableList()

    fun evaluate(t: Float): Float {
        if (points.isEmpty()) return 0f
        if (points.size == 1) return points[0].y
        val clamped = MathUtil.clamp01(t)
        if (clamped <= points.first().x) return points.first().y
        if (clamped >= points.last().x) return points.last().y
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            if (clamped in a.x..b.x) {
                val span = (b.x - a.x).coerceAtLeast(1e-5f)
                val local = (clamped - a.x) / span
                val smooth = local * local * (3f - 2f * local)   // smoothstep between points
                return MathUtil.lerp(a.y, b.y, smooth)
            }
        }
        return points.last().y
    }

    fun addPoint(x: Float, y: Float): Curve {
        points.add(Vec2(x, y)); points.sortBy { it.x }; return this
    }

    fun removePoint(index: Int): Curve {
        if (index in points.indices && points.size > 2) points.removeAt(index)
        return this
    }

    fun toFloatArray(): FloatArray = points.flatMap { listOf(it.x, it.y) }.toFloatArray()

    fun serialize(): List<List<Float>> = points.map { listOf(it.x, it.y) }

    companion object {
        fun fromJson(data: List<*>): Curve {
            val points = data.mapNotNull { raw ->
                (raw as? List<*>)?.takeIf { it.size >= 2 }?.let {
                    Vec2((it[0] as? Number)?.toFloat() ?: 0f, (it[1] as? Number)?.toFloat() ?: 0f)
                }
            }
            return Curve(if (points.size >= 2) points else listOf(Vec2(0f, 0f), Vec2(1f, 1f)))
        }

        fun linear() = Curve(listOf(Vec2(0f, 0f), Vec2(1f, 1f)))
        fun easeInOut() = Curve(listOf(Vec2(0f, 0f), Vec2(0.5f, 0.5f), Vec2(1f, 1f)))
        fun easeOut() = Curve(listOf(Vec2(0f, 0f), Vec2(0.25f, 0.7f), Vec2(1f, 1f)))
        fun fade() = Curve(listOf(Vec2(0f, 1f), Vec2(1f, 0f)))
        fun bounce() = Curve(listOf(Vec2(0f, 0f), Vec2(0.5f, 1.1f), Vec2(0.75f, 0.95f), Vec2(1f, 1f)))
    }
}

/** Ready-made animation helpers used by sample games and by the editor's "add animation". */
object Animations {
    fun fadeIn(nodePath: String, duration: Float = 0.35f): Animation =
        Animation("fade_in", duration).also { animation ->
            animation.addPropertyTrack(nodePath, "modulate")
                .addKey(0f, Color(1f, 1f, 1f, 0f))
                .addKey(duration, Color.WHITE, "out_cubic")
        }

    fun fadeOut(nodePath: String, duration: Float = 0.35f): Animation =
        Animation("fade_out", duration).also { animation ->
            animation.addPropertyTrack(nodePath, "modulate")
                .addKey(0f, Color.WHITE)
                .addKey(duration, Color(1f, 1f, 1f, 0f), "out_cubic")
        }

    fun pop(nodePath: String, from: Float = 0f, to: Float = 1f, duration: Float = 0.25f): Animation =
        Animation("pop", duration).also { animation ->
            animation.addPropertyTrack(nodePath, "scale")
                .addKey(0f, Vec2(from, from))
                .addKey(duration, Vec2(to, to), "out_back")
        }

    fun shake(nodePath: String, amplitude: Float = 3f, duration: Float = 0.3f): Animation =
        Animation("shake", duration).also { animation ->
            val track = animation.addPropertyTrack(nodePath, "position")
            val steps = 6
            for (i in 0..steps) {
                val t = i.toFloat() / steps * duration
                val decay = 1f - i.toFloat() / steps
                track.addKey(t, Vec2((if (i % 2 == 0) amplitude else -amplitude) * decay, 0f))
            }
            track.addKey(duration, Vec2.ZERO)
        }

    fun float(nodePath: String, height: Float = 4f, duration: Float = 1.4f): Animation =
        Animation("float", duration, loop = true).also { animation ->
            animation.addPropertyTrack(nodePath, "positionY")
                .addKey(0f, 0f)
                .addKey(duration / 2f, height, "in_out_sine")
                .addKey(duration, 0f, "in_out_sine")
        }

    fun damageFlash(nodePath: String, duration: Float = 0.25f): Animation =
        Animation("damage_flash", duration).also { animation ->
            animation.addPropertyTrack(nodePath, "modulate")
                .addKey(0f, Color(1f, 0.35f, 0.35f))
                .addKey(duration, Color.WHITE)
        }

    fun particleBurst(nodePath: String, time: Float = 0f, count: Int = 24): Animation =
        Animation("burst", maxOf(time + 0.01f, 0.2f)).also { animation ->
            val track = animation.addParticlesTrack(nodePath)
            track.bursts.add(Triple(time, count, true))
        }

    /** Default particle configuration used when the editor creates a new emitter. */
    fun defaultParticleConfig(): ParticleEmitterConfig = ParticleEmitterConfig()
}
