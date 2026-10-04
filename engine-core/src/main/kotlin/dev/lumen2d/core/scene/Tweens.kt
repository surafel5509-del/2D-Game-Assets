/**
 * Lumen2D — tweening.
 *
 * Tweens are the scripting-friendly cousin of [dev.lumen2d.core.anim.Animation]: create one,
 * chain property steps and callbacks, and let [TweenManager] drive it from the scene tree's
 * `process` loop. Everything is allocation-light and headless-testable.
 *
 * ```kotlin
 * node.createTween()
 *     .property("position", Vec2(120f, 40f), 0.6f, "out_back")
 *     .property("modulate", Color.WHITE, 0.2f)
 *     .callback { audio.playSfx("land") }
 * ```
 */
package dev.lumen2d.core.scene

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Easing
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.util.Event

/** Anything a tween can write to: nodes, custom runtime objects, or plain property bags. */
interface TweenTarget {
    fun tweenGet(property: String): Any?
    fun tweenSet(property: String, value: Any?): Boolean
}

/** Wraps a getter/setter pair so plain objects can be tweened without implementing [TweenTarget]. */
class LambdaTweenTarget(
    private val getter: (String) -> Any?,
    private val setter: (String, Any?) -> Boolean,
) : TweenTarget {
    override fun tweenGet(property: String): Any? = getter(property)
    override fun tweenSet(property: String, value: Any?): Boolean = setter(property, value)
}

/** A tween over a single property. */
class PropertyTweener(
    val target: TweenTarget,
    val property: String,
    var to: Any?,
    var duration: Float,
    var delay: Float = 0f,
    var easingName: String = "linear",
    var fromOverride: Any? = null,
    var asRelative: Boolean = false,
) {
    /** Set before the step starts. */
    var from: Any? = null
    private var elapsed: Float = 0f
    private var started = false

    fun from(value: Any?): PropertyTweener { fromOverride = value; return this }

    fun setDelay(seconds: Float): PropertyTweener { delay = seconds; return this }

    fun setEasing(name: String): PropertyTweener { easingName = name; return this }

    /** Adds the delta of [to] to the value the tween starts from. */
    fun asRelative(): PropertyTweener { asRelative = true; return this }

    fun seek(timeSeconds: Float) {
        if (!started) begin()
        val raw = if (duration <= 0f) 1f else MathUtil.clamp01((timeSeconds - delay) / duration)
        applyValue(raw)
        if (raw >= 1f) elapsed = delay + duration else elapsed = timeSeconds
    }

    private fun begin() {
        started = true
        elapsed = 0f
        from = fromOverride ?: target.tweenGet(property)
        if (asRelative) to = addValues(from, to)
    }

    private fun applyValue(raw: Float) {
        val eased = Easing.byName(easingName)(MathUtil.clamp01(raw))
        target.tweenSet(property, interpolate(from, to, eased))
    }

    /** Returns true when finished. */
    fun advance(delta: Float): Boolean {
        if (!started) begin()
        elapsed += delta
        if (elapsed < delay) return false
        val raw = if (duration <= 0f) 1f else MathUtil.clamp01((elapsed - delay) / duration)
        applyValue(raw)
        if (raw >= 1f) { applyValue(1f); return true }
        return false
    }

    fun reset() { started = false; elapsed = 0f }

    val isStarted: Boolean get() = started
    val progress: Float get() =
        if (duration <= 0f) 1f else MathUtil.clamp01((elapsed - delay) / duration)

    companion object {
        /** Interpolates any of the engine's common value types. */
        fun interpolate(from: Any?, to: Any?, t: Float): Any? = when {
            from is Vec2 && to is Vec2 -> from.lerp(to, t)
            from is Color && to is Color -> from.lerp(to, t)
            from is Rect && to is Rect -> Rect(
                MathUtil.lerp(from.x, to.x, t), MathUtil.lerp(from.y, to.y, t),
                MathUtil.lerp(from.w, to.w, t), MathUtil.lerp(from.h, to.h, t))
            from is Number && to is Number -> MathUtil.lerp(from.toFloat(), to.toFloat(), t)
            from is FloatArray && to is FloatArray && from.size == to.size ->
                FloatArray(from.size) { i -> MathUtil.lerp(from[i], to[i], t) }
            t < 1f -> from
            else -> to
        }

        fun addValues(a: Any?, b: Any?): Any? = when {
            a is Vec2 && b is Vec2 -> a + b
            a is Number && b is Number -> a.toFloat() + b.toFloat()
            a is Color && b is Color -> Color(a.r + b.r, a.g + b.g, a.b + b.b, a.a + b.a)
            else -> b
        }
    }
}

/** A step in a [Tween] timeline. */
sealed class TweenStep {
    abstract fun advance(delta: Float): Boolean
    open fun begin() {}
    open fun end() {}
    open fun reset() {}
    open val isFinished: Boolean get() = false

    class Property(val tweener: PropertyTweener) : TweenStep() {
        override fun begin() = tweener.reset()
        override fun advance(delta: Float): Boolean = tweener.advance(delta)
    }

    class Callback(val delay: Float, val action: () -> Unit) : TweenStep() {
        private var elapsed = 0f
        private var fired = false
        override fun reset() { elapsed = 0f; fired = false }
        override fun advance(delta: Float): Boolean {
            elapsed += delta
            if (elapsed >= delay && !fired) { fired = true; action() }
            return elapsed >= delay
        }
    }

    class Wait(val duration: Float) : TweenStep() {
        private var elapsed = 0f
        override fun reset() { elapsed = 0f }
        override fun advance(delta: Float): Boolean { elapsed += delta; return elapsed >= duration }
    }
}

enum class TweenPlayback { PENDING, RUNNING, PAUSED, FINISHED }

/**
 * A timeline of [TweenStep]s. Steps run in sequence; with `parallel = true` every step runs
 * at once (useful for "fade + move + scale" simultaneously).
 */
class Tween internal constructor(internal var manager: TweenManager?) {
    /** Timeline steps in order; the editor's tween inspector reads and edits this list. */
    val steps = ArrayList<TweenStep>()
    var parallel: Boolean = false
    /** -1 = forever. Ping-pong reverses on every second pass. */
    var loops: Int = 1
    var pingPong: Boolean = false
    var playback: TweenPlayback = TweenPlayback.PENDING
        private set

    val finished = Event()
    val loopFinished = Event()

    private var stepIndex = 0
    private var completedLoops = 0
    private var pingDirection = 1f
    /** When a group is skipped (e.g. node freed) the tween stops silently. */
    var killWhenTargetMissing: Boolean = true

    val isRunning: Boolean get() = playback == TweenPlayback.RUNNING
    val isFinished: Boolean get() = playback == TweenPlayback.FINISHED
    val stepCount: Int get() = steps.size

    /** Total length in seconds (assumes sequential steps; parallel groups overlap). */
    val length: Float get() = steps.maxOfOrNull { stepLength(it) } ?: 0f

    private fun stepLength(step: TweenStep): Float = when (step) {
        is TweenStep.Property -> step.tweener.delay + step.tweener.duration
        is TweenStep.Callback -> step.delay
        is TweenStep.Wait -> step.duration
    }

    // ------------------------------------------------------------------ building

    fun property(target: TweenTarget, property: String, to: Any?, duration: Float, easing: String = "linear"): Tween {
        steps.add(TweenStep.Property(PropertyTweener(target, property, to, duration, easingName = easing)))
        return this
    }

    fun property(target: Node, property: String, to: Any?, duration: Float, easing: String = "linear"): Tween =
        property(NodeTweenTarget(target), property, to, duration, easing)

    fun callback(delay: Float = 0f, action: () -> Unit): Tween {
        steps.add(TweenStep.Callback(delay, action)); return this
    }

    fun wait(duration: Float): Tween { steps.add(TweenStep.Wait(duration)); return this }

    fun setParallel(value: Boolean = true): Tween { parallel = value; return this }

    fun setLoops(count: Int, pingPong: Boolean = false): Tween {
        loops = count; this.pingPong = pingPong; return this
    }

    /** Convenience: fade a node's modulate alpha out then free it. */
    fun fadeOutAndFree(node: Node, duration: Float = 0.25f): Tween =
        property(node, "modulate", Color(1f, 1f, 1f, 0f), duration, "out_quad")
            .callback { node.queueFree() }

    // ------------------------------------------------------------------ control

    fun start(): Tween = begin()

    /** Begins playback (called by [start] and by the manager's auto-start). */
    internal fun begin(): Tween {
        if (steps.isEmpty()) { playback = TweenPlayback.PENDING; return this }
        stepIndex = 0
        completedLoops = 0
        pingDirection = 1f
        steps.forEach { it.reset() }
        playback = TweenPlayback.RUNNING
        manager?.register(this)
        steps.firstOrNull()?.begin()
        return this
    }

    fun pause() { if (playback == TweenPlayback.RUNNING) playback = TweenPlayback.PAUSED }

    fun resume() { if (playback == TweenPlayback.PAUSED) playback = TweenPlayback.RUNNING }

    fun kill() {
        playback = TweenPlayback.FINISHED
        manager?.unregister(this)
    }

    fun complete() {
        while (playback == TweenPlayback.RUNNING) advance(1e9f)
    }

    /** Jumps to [timeSeconds] within the timeline. */
    fun seek(timeSeconds: Float, applyToTargets: Boolean = true) {
        if (!applyToTargets) return
        for (step in steps) if (step is TweenStep.Property) step.tweener.seek(timeSeconds)
    }

    internal fun isActive(): Boolean =
        playback == TweenPlayback.RUNNING || playback == TweenPlayback.PAUSED

    internal fun advance(delta: Float) {
        if (playback != TweenPlayback.RUNNING) return
        val scaled = delta * pingDirection
        if (parallel) {
            var allDone = true
            for (step in steps) {
                val done = step.advance(scaled)
                if (!done) allDone = false
                if (scaled < 0f && done) step.reset()
            }
            if (!allDone) return
            finishPass()
            return
        }
        // Sequential playback.
        while (stepIndex < steps.size) {
            val step = steps[stepIndex]
            val done = step.advance(scaled)
            if (!done) return
            step.end()
            stepIndex += if (scaled >= 0f) 1 else -1
        }
        finishPass()
    }

    private fun finishPass() {
        loopFinished.emit()
        completedLoops++
        if (loops < 0 || completedLoops < loops) {
            if (pingPong) {
                pingDirection = -pingDirection
                if (pingDirection < 0f) {
                    stepIndex = steps.size - 1
                    steps[stepIndex].begin()
                } else {
                    stepIndex = 0
                    steps.forEach { it.reset() }
                    steps.firstOrNull()?.begin()
                }
            } else {
                stepIndex = 0
                steps.forEach { it.reset() }
                steps.firstOrNull()?.begin()
            }
            return
        }
        playback = TweenPlayback.FINISHED
        manager?.unregister(this)
        finished.emit()
    }
}

/** Adapts a [Node] to [TweenTarget] using its inspector property system. */
class NodeTweenTarget(val node: Node) : TweenTarget {
    override fun tweenGet(property: String): Any? = node.getProperty(property)
    override fun tweenSet(property: String, value: Any?): Boolean = node.applyProperty(property, value)
}

/** Owns and advances the active tweens of a scene tree. */
class TweenManager {
    private val active = ArrayList<Tween>()
    private val pending = ArrayList<Tween>()
    var autoStart: Boolean = true
    var timeScale: Float = 1f
    private var nextId = 1

    val runningCount: Int get() = active.size
    val isEmpty: Boolean get() = active.isEmpty()

    /** Creates a tween that starts immediately (or on the next update when called mid-frame). */
    fun create(parallel: Boolean = false): Tween {
        val tween = Tween(this)
        tween.parallel = parallel
        // Registered right away so the builder style (`createTween().property(...)`) starts
        // automatically on the next update once it actually has steps.
        pending.add(tween)
        return tween
    }

    fun tweenProperty(target: Node, property: String, to: Any?, duration: Float, easing: String = "linear"): Tween =
        create().property(target, property, to, duration, easing).start()

    fun tweenCallback(delay: Float = 0f, action: () -> Unit): Tween = create().callback(delay, action).start()

    internal fun register(tween: Tween) {
        if (tween !in active && tween !in pending) pending.add(tween)
    }

    internal fun unregister(tween: Tween) {
        pending.remove(tween)
        active.remove(tween)
    }

    fun killAll() {
        pending.toList().forEach { it.kill() }
        active.toList().forEach { it.kill() }
        pending.clear(); active.clear()
    }

    fun killTweensOf(target: Any) {
        for (tween in (active + pending).toList()) {
            val touches = tween.steps.any {
                it is TweenStep.Property && (it.tweener.target === target ||
                    (it.tweener.target is NodeTweenTarget && (it.tweener.target as NodeTweenTarget).node === target))
            }
            if (touches) tween.kill()
        }
    }

    fun pauseAll() { active.forEach { it.pause() } }
    fun resumeAll() { active.forEach { it.resume() } }

    fun update(delta: Float) {
        val dt = delta * timeScale
        if (pending.isNotEmpty()) {
            // Tweens are built step by step (`create().property(...)`), so they only start once
            // they actually have steps — autoStart keeps script code tidy.
            for (tween in pending.toList()) {
                if (tween.playback == TweenPlayback.PENDING && tween.steps.isNotEmpty() && autoStart) {
                    tween.begin()
                }
            }
            active.addAll(pending.filter { it.playback == TweenPlayback.RUNNING || it.playback == TweenPlayback.PAUSED })
            pending.removeAll { it.playback == TweenPlayback.RUNNING || it.playback == TweenPlayback.PAUSED }
        }
        if (active.isEmpty()) return
        val snapshot = active.toList()
        for (tween in snapshot) {
            if (tween.playback != TweenPlayback.RUNNING) continue
            if (tween.killWhenTargetMissing && tweenTargetsFreed(tween)) { tween.kill(); continue }
            tween.advance(dt)
        }
        active.removeAll { it.playback == TweenPlayback.FINISHED }
    }

    private fun tweenTargetsFreed(tween: Tween): Boolean = tween.steps.any {
        it is TweenStep.Property && it.tweener.target is NodeTweenTarget &&
            (it.tweener.target as NodeTweenTarget).node.isQueuedForDeletion
    }

    /** Serialisable snapshot for the editor's "active tweens" debug panel. */
    fun describe(): List<String> = active.map { tween ->
        val name = tween.steps.filterIsInstance<TweenStep.Property>().firstOrNull()?.let {
            val target = it.tweener.target
            val label = if (target is NodeTweenTarget) target.node.name else "object"
            "$label.${it.tweener.property}"
        } ?: "timeline"
        "$name (${(tween.steps.filterIsInstance<TweenStep.Property>().firstOrNull()?.tweener?.progress ?: 0f * 100f).toInt()}%)"
    }
}

/** Creates a tween driven by this node's scene tree (falls back to an unregistered tween). */
fun Node.createTween(parallel: Boolean = false): Tween {
    val manager = tree?.tweens
    return if (manager != null) manager.create(parallel) else Tween(null).also { it.begin() }
}

/** Tweens a property on this node and returns the tween for chaining. */
fun Node.tweenProperty(property: String, to: Any?, duration: Float, easing: String = "linear"): Tween =
    createTween().property(this, property, to, duration, easing).start()

/** Fades this node in from transparent and returns the tween. */
fun Node.tweenFadeIn(duration: Float = 0.3f, delay: Float = 0f): Tween {
    applyProperty("modulate", Color(1f, 1f, 1f, 0f))
    val tween = createTween()
    if (delay > 0f) tween.wait(delay)
    return tween.property(this, "modulate", Color.WHITE, duration, "out_quad").start()
}

/** Frees this node after a fade-out. */
fun Node.tweenFadeOutAndFree(duration: Float = 0.25f): Tween =
    createTween().fadeOutAndFree(this, duration)

/**
 * A node that owns tweens and can be authored from the scene inspector — handy for
 * "open a door when the player enters" style intros without writing a script.
 */
open class TweenNode(name: String = "Tween") : Node(name) {
    /** Playback triggers: manual, on ready, or when a named signal arrives. */
    var trigger: String = "on_ready"
    var playOnSignal: String = ""
    var loops: Int = 1
    var pingPong: Boolean = false

    /** Property steps authored in the inspector: (nodePath, property, value, duration, easing). */
    val authored = ArrayList<AuthoredTween>()

    private var tween: Tween? = null

    override fun onReady() {
        if (trigger == "on_ready") play()
    }

    fun play() {
        val manager = tree?.tweens ?: return
        tween?.kill()
        val t = manager.create().setLoops(loops, pingPong)
        for (step in authored) {
            val target = if (step.nodePath.isEmpty() || step.nodePath == ".") this
            else parent?.nodePath(step.nodePath) ?: this
            t.property(target, step.property, step.parseValue(), step.duration, step.easing)
        }
        tween = t.start()
    }

    fun stop() { tween?.kill(); tween = null }

    val isPlaying: Boolean get() = tween?.isRunning == true

    override fun getProperty(property: String): Any? = when (property) {
        "trigger" -> trigger
        "playOnSignal" -> playOnSignal
        "loops" -> loops
        "pingPong" -> pingPong
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "trigger" -> { trigger = value?.toString() ?: trigger; return true }
            "playOnSignal" -> { playOnSignal = value?.toString() ?: ""; return true }
            "loops" -> { loops = (value as? Number)?.toInt() ?: loops; return true }
            "pingPong" -> { pingPong = value as? Boolean ?: pingPong; return true }
        }
        return super.setProperty(property, value)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("trigger", PropertyType.ENUM, "Trigger", "on_ready", category = "Tween",
            enumValues = listOf("on_ready", "signal", "manual")),
        PropertyDef("playOnSignal", PropertyType.STRING, "Signal name", "", category = "Tween"),
        PropertyDef("loops", PropertyType.INT, "Loops", 1, min = -1f, max = 100f, step = 1f, category = "Tween",
            tooltip = "-1 loops forever"),
        PropertyDef("pingPong", PropertyType.BOOL, "Ping-pong", false, category = "Tween"),
    ) + super.propertyDefinitions()

    /** One authored property step; values are stored as text so scenes stay hand-editable. */
    class AuthoredTween(
        var nodePath: String = ".",
        var property: String = "position",
        var value: String = "0,0",
        var duration: Float = 0.5f,
        var easing: String = "linear",
    ) {
        fun parseValue(): Any? {
            val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return when {
                value.equals("true", true) -> true
                value.equals("false", true) -> false
                property == "modulate" || property == "selfModulate" || property.endsWith("Color") ->
                    Color.fromHex(parts.firstOrNull() ?: "#ffffffff")
                property == "rotation" || parts.size == 1 -> parts.firstOrNull()?.toFloatOrNull() ?: 0f
                parts.size >= 2 -> Vec2(parts[0].toFloatOrNull() ?: 0f, parts[1].toFloatOrNull() ?: 0f)
                else -> value
            }
        }
    }
}
