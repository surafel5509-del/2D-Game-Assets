/**
 * Lumen2D — scripting runtime.
 *
 * Bridges the [Interpreter] to the engine: it compiles scripts from the asset database, creates
 * one [ScriptInstance] per node, exposes the engine API to scripts and routes lifecycle calls
 * (`ready`, `process`, `physics_process`, `draw`, signals) back into script functions.
 *
 * ```kotlin
 * val runtime = LumenScriptRuntime { path -> database.loadScriptSource(path) }
 * game.scriptRuntime = runtime
 * ```
 *
 * Engine objects appear in scripts through [ScriptForeign] adapters — [NodeForeign] covers every
 * node property and method, [InputForeign] the action map — so the scripting package has no
 * platform or renderer dependency of its own.
 */
package dev.lumen2d.core.script

import dev.lumen2d.core.audio.AudioClip
import dev.lumen2d.core.input.InputState
import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.render.Renderer
import dev.lumen2d.core.scene.AudioPlayerNode
import dev.lumen2d.core.scene.ButtonControl
import dev.lumen2d.core.scene.HealthNode
import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.Node2D
import dev.lumen2d.core.scene.SceneTree
import dev.lumen2d.core.scene.ScriptBehavior
import dev.lumen2d.core.scene.ScoreNode
import dev.lumen2d.core.scene.Sprite2D
import dev.lumen2d.core.scene.AnimatedSprite2D
import dev.lumen2d.core.scene.tweenProperty
import dev.lumen2d.core.scene.TimerNode
import dev.lumen2d.core.util.Log
import kotlin.math.abs
import kotlin.math.floor

/** Wraps a Kotlin value so scripts can read/write/call it. */
class BoxedForeign(private val value: Any, override val scriptTypeName: String) : ScriptForeign {
    override fun scriptGet(name: String): Any? = BoxedValue.get(value, name)
    override fun scriptSet(name: String, value: Any?): Boolean = BoxedValue.set(this.value, name, value)
    override fun scriptCall(name: String, args: List<Any?>): Any? = BoxedValue.call(this.value, name, args)
}

/** Best-effort accessor for values that have no dedicated adapter yet (keeps scripts explorable). */
internal object BoxedValue {
    fun get(value: Any, name: String): Any? = when (value) {
        is AudioClip -> when (name) {
            "name" -> value.name
            "sample_rate" -> value.sampleRate.toDouble()
            "channels" -> value.channels.toDouble()
            "duration" -> value.durationSeconds.toDouble()
            "frames", "frame_count" -> value.frameCount.toDouble()
            else -> throw RuntimeException("${value::class.simpleName} has no property '$name'")
        }
        else -> throw RuntimeException("${value::class.simpleName ?: "value"} has no property '$name'")
    }

    fun set(value: Any, name: String, newValue: Any?): Boolean = when (value) {
        is AudioClip -> false
        else -> false
    }

    fun call(value: Any, name: String, args: List<Any?>): Any? = when (value) {
        else -> throw RuntimeException("${value::class.simpleName ?: "value"} has no method '$name'")
    }
}

/**
 * Adapter for [Node] (and every subclass). Property lookups go through the node's own
 * `getProperty`/`setProperty` pair, so the inspector, save games and scripts always agree, and
 * script variables declared by the node's own script shadow engine properties like in Godot.
 */
class NodeForeign(val node: Node, private val instance: ScriptInstance?) : ScriptForeign {

    override val scriptTypeName: String get() = node.typeName

    override fun scriptHas(name: String): Boolean = when (name) {
        // Always-available node conveniences that are not backed by a PropertyDef.
        "name", "type", "type_name", "children", "child_count", "parent", "node_path", "is_ready",
        "visible", "groups", "active", "is_inside_tree", "delta", "process_delta", "time", "frame",
        "queue_free", "free" -> true
        else -> node.getProperty(name) != null || instance?.hasVariable(name) == true
    }

    override fun scriptGet(name: String): Any? {
        instance?.variableOrNull(name)?.let { return it }
        when (name) {
            "name" -> return node.name
            "type", "type_name" -> return node.typeName
            "children" -> return node.children.map { toScriptValue(it, instance?.runtime) }.toMutableList()
            "child_count" -> return node.childCount.toDouble()
            "parent" -> return node.parent?.let { toScriptValue(it, instance?.runtime) }
            "node_path" -> return node.nodePath()
            "is_ready" -> return node.isReady
            "visible" -> return node.visible
            "groups" -> return node.groups.toMutableList()
            "active", "is_inside_tree" -> return node.tree != null
            "queue_free", "free" -> return NativeFunction(name, 0) { node.queueFree(); null }
        }
        // 2D transform shortcuts that scripts use constantly (equivalent to Vec2 fields).
        if (node is dev.lumen2d.core.scene.PhysicsBodyNode) {
            when (name) {
                "velocity" -> return toScriptValue(node.body?.velocity ?: Vec2.ZERO, instance?.runtime)
                "on_floor" -> return node.onFloor
                "on_wall" -> return (node as? dev.lumen2d.core.scene.CharacterBody2DNode)?.onWall == true
                "on_ceiling" -> return (node as? dev.lumen2d.core.scene.CharacterBody2DNode)?.onCeiling == true
                "mass" -> return (node.body?.mass ?: 1f).toDouble()
                "gravity_scale" -> return (node.body?.gravityScale ?: 1f).toDouble()
            }
        }
        if (node is Node2D) {
            when (name) {
                "position" -> return toScriptValue(node.position, instance?.runtime)
                "global_position" -> return toScriptValue(node.globalPosition, instance?.runtime)
                "rotation" -> return node.rotation.toDouble()
                "global_rotation" -> return node.globalRotation.toDouble()
                "scale" -> return toScriptValue(node.scale, instance?.runtime)
                "global_scale" -> return toScriptValue(node.globalScale, instance?.runtime)
                "z_index" -> return node.zIndex.toDouble()
                "modulate" -> return toScriptValue(node.modulate, instance?.runtime)
                "opacity", "alpha" -> return node.modulate.a.toDouble()
                "pointing_right" -> return node.scale.x >= 0f
            }
        }
        if (node is Node2D) {
            when (name) {
                "modulate" -> return toScriptValue(node.modulate, instance?.runtime)
                "z_index" -> return node.zIndex.toDouble()
            }
        }
        when (name) {
            "process_delta", "delta" -> return (node.tree?.clock?.delta ?: 0f).toDouble()
            "time" -> return (node.tree?.clock?.elapsedSeconds ?: 0f).toDouble()
            "frame" -> return (node.tree?.clock?.frame ?: 0L).toDouble()
        }
        node.getProperty(name)?.let { return toScriptValue(it, instance?.runtime) }
        throw ScriptError("${node.typeName} '${node.name}' has no property '$name'")
    }

    override fun scriptSet(name: String, value: Any?): Boolean {
        val converted = fromScriptValue(value)
        when (name) {
            "name" -> { node.name = converted?.toString() ?: node.name; return true }
            "visible" -> { node.visible = converted as? Boolean ?: true; return true }
            "position" -> if (node is Node2D) { node.position = converted as? Vec2 ?: Vec2.ZERO; return true }
            "rotation" -> if (node is Node2D) { node.rotation = (converted as? Number)?.toFloat() ?: 0f; return true }
            "velocity" -> if (node is dev.lumen2d.core.scene.PhysicsBodyNode) {
                node.body?.velocity = converted as? Vec2 ?: Vec2.ZERO; return true
            }
            "mass" -> if (node is dev.lumen2d.core.scene.PhysicsBodyNode) {
                node.body?.mass = (converted as? Number)?.toFloat() ?: 1f; return true
            }
            "scale" -> if (node is Node2D) { node.scale = converted as? Vec2 ?: node.scale; return true }
            "z_index" -> if (node is Node2D) { node.zIndex = (converted as? Number)?.toInt() ?: 0; return true }
            "modulate" -> if (node is Node2D) { node.modulate = converted as? Color ?: node.modulate; return true }
            "opacity", "alpha" -> if (node is Node2D) {
                val alpha = (converted as? Number)?.toFloat() ?: 1f
                node.modulate = node.modulate.withAlpha(alpha); return true
            }
        }
        if (instance?.hasVariable(name) == true) {
            instance.setVariable(name, value); return true
        }
        if (node.setProperty(name, converted)) return true
        // Unknown names become script variables (typo-friendly but predictable).
        if (instance != null) { instance.setVariable(name, value); return true }
        return false
    }

    override fun scriptCall(name: String, args: List<Any?>): Any? {
        when (name) {
            "get_node", "find_node" -> {
                val path = args.getOrNull(0)?.toString() ?: return null
                val target = resolveNodePath(node, path)
                return target?.let { toScriptValue(it, instance?.runtime) }
            }
            "get_parent" -> return node.parent?.let { toScriptValue(it, instance?.runtime) }
            "get_child" -> {
                val index = (args.getOrNull(0) as? Double)?.toInt() ?: 0
                return node.childAt(index)?.let { toScriptValue(it, instance?.runtime) }
            }
            "find_children" -> {
                // find_children(pattern, type, recursive) — "*" matches everything, like Godot.
                val pattern = args.getOrNull(0)?.toString() ?: "*"
                val typeName = args.getOrNull(1)?.toString() ?: ""
                val recursive = (args.getOrNull(2) as? Boolean) ?: true
                val candidates = if (recursive) node.descendants() else node.children.toList()
                val found = candidates.filter { candidate ->
                    (typeName.isEmpty() || typeName == "*" || candidate.typeName == typeName) &&
                        (pattern == "*" || nameMatches(candidate.name, pattern))
                }
                return found.map { toScriptValue(it, instance?.runtime) }.toMutableList()
            }
            "add_child" -> {
                val child = (args.getOrNull(0) as? ScriptForeign)?.let { foreignNode(it) }
                if (child != null) node.addChild(child)
                return null
            }
            "remove_child" -> {
                val child = (args.getOrNull(0) as? ScriptForeign)?.let { foreignNode(it) }
                if (child != null) node.removeChild(child)
                return null
            }
            "add_to_group" -> { node.addToGroup(args.getOrNull(0)?.toString() ?: ""); return null }
            "remove_from_group" -> { node.removeFromGroup(args.getOrNull(0)?.toString() ?: ""); return null }
            "is_in_group" -> return node.isInGroup(args.getOrNull(0)?.toString() ?: "")
            "queue_free" -> { node.queueFree(); return null }
            "set_timer", "timer" -> {
                val seconds = (args.getOrNull(0) as? Double)?.toFloat() ?: 0f
                val callback = args.getOrNull(1) ?: return null
                val repeat = args.getOrNull(2) as? Boolean ?: false
                return node.setTimer(seconds, repeat) { instance?.invokeCallback(callback, emptyList()) }
            }
            "emit" -> { instance?.emitSignal(name0(args, 0), args.drop(1)); return null }
            "connect" -> { instance?.connectSignal(this, name0(args, 0), args.getOrNull(1)); return null }
            "disconnect" -> { instance?.disconnectSignal(this, name0(args, 0)); return null }
            "get", "get_property" -> return scriptGet(args.getOrNull(0)?.toString() ?: "")
            "set", "set_property" -> {
                scriptSet(args.getOrNull(0)?.toString() ?: "", args.getOrNull(1))
                return null
            }
            "has", "has_property" -> return scriptHas(args.getOrNull(0)?.toString() ?: "")
            "call_deferred" -> {
                val callback = args.getOrNull(0) ?: return null
                node.callDeferred("script:${node.instanceId}") { instance?.invokeCallback(callback, emptyList()) }
                return null
            }
            "call_method" -> {
                val method = args.getOrNull(0)?.toString() ?: return null
                return node.scriptBehavior?.callMethod(method, args.drop(1))
            }
            "has_method" -> return node.scriptBehavior?.hasMethod(name0(args, 0)) == true
            "play", "play_sound" -> {
                val player = node as? AudioPlayerNode ?: node.descendants().filterIsInstance<AudioPlayerNode>().firstOrNull()
                player?.let {
                    val clipId = args.getOrNull(0)?.toString()
                    if (!clipId.isNullOrEmpty()) it.clipId = clipId
                    it.play()
                }
                return null
            }
            "damage" -> { (node as? HealthNode)?.damage((args.getOrNull(0) as? Double)?.toFloat() ?: 0f); return null }
            "heal" -> { (node as? HealthNode)?.heal((args.getOrNull(0) as? Double)?.toFloat() ?: 0f); return null }
            "add_score" -> { (node as? ScoreNode)?.add((args.getOrNull(0) as? Double)?.toInt() ?: 0); return null }
            "press" -> { (node as? ButtonControl)?.let { it.setProperty("pressed", true) }; return null }
            "release" -> { (node as? ButtonControl)?.let { it.setProperty("pressed", false) }; return null }
            "is_pressed" -> return (node.getProperty("pressed") as? Boolean)
                ?: (node.getProperty("isPressed") as? Boolean) ?: false
            // HUD helpers: `bar.set_count(3, 5)`, `progress.set_value(0.4)`.
            "set_count" -> {
                val bar = node as? dev.lumen2d.core.scene.HudBarControl ?: return null
                val count = (args.getOrNull(0) as? Double)?.toInt() ?: 0
                val maximum = (args.getOrNull(1) as? Double)?.toInt() ?: bar.maxCount
                bar.setCount(count, maximum)
                return null
            }
            "set_value" -> {
                val value = (args.getOrNull(0) as? Double)?.toFloat() ?: 0f
                when (val target = node) {
                    is dev.lumen2d.core.scene.ProgressBarControl -> target.value = value * target.maxValue
                    is dev.lumen2d.core.scene.SliderControl -> target.value = value
                }
                return null
            }
            "start" -> { (node as? TimerNode)?.start(); return null }
            "move_and_slide" -> {
                val body = node as? dev.lumen2d.core.scene.CharacterBody2DNode ?: return null
                val delta = (args.getOrNull(0) as? Double)?.toFloat()
                    ?: (node.tree?.clock?.delta ?: 1f / 60f)
                return body.moveAndSlide(delta)
            }
            "move_and_collide" -> {
                val body = node as? dev.lumen2d.core.scene.CharacterBody2DNode ?: return false
                val motion = fromScriptValue(args.getOrNull(0)) as? Vec2 ?: Vec2.ZERO
                val delta = (args.getOrNull(1) as? Double)?.toFloat() ?: (node.tree?.clock?.delta ?: 1f / 60f)
                body.motionVelocity = motion
                body.moveAndSlide(delta)
                return body.lastCollision != null
            }
            "apply_impulse" -> {
                (node as? dev.lumen2d.core.scene.PhysicsBodyNode)?.applyImpulse(fromScriptValue(args.getOrNull(0)) as? Vec2 ?: Vec2.ZERO)
                return null
            }
            "jump" -> {
                val body = node as? dev.lumen2d.core.scene.PhysicsBodyNode ?: return null
                val force = (args.getOrNull(0) as? Double)?.toFloat() ?: 320f
                val current = body.body?.velocity ?: return null
                body.body?.velocity = Vec2(current.x, -force)
                return null
            }
            "is_on_floor" -> return (node as? dev.lumen2d.core.scene.PhysicsBodyNode)?.onFloor == true
            "flash" -> {
                val color = fromScriptValue(args.getOrNull(0)) as? Color ?: Color.WHITE
                val duration = (args.getOrNull(1) as? Double)?.toFloat() ?: 0.12f
                if (node is Node2D) {
                    val original = node.modulate
                    node.modulate = color
                    node.setTimer(duration) { if (node.isInsideTree()) node.modulate = original }
                }
                return null
            }
            "shake_camera" -> {
                val amplitude = (args.getOrNull(0) as? Double)?.toFloat() ?: 6f
                val duration = (args.getOrNull(1) as? Double)?.toFloat() ?: 0.25f
                (node.tree?.activeCamera?.camera)?.shake(amplitude, duration)
                return null
            }
            "spawn" -> {
                val rt = instance?.runtime
                val scene = args.getOrNull(0)?.toString()?.let { rt?.sceneProvider?.invoke(it) }
                val where = fromScriptValue(args.getOrNull(1)) as? Vec2
                val instance = scene?.instantiate()
                if (instance != null) {
                    if (instance is Node2D && where != null) instance.position = where
                    node.parent?.addChild(instance) ?: node.addChild(instance)
                }
                return instance?.let { toScriptValue(it, rt) }
            }
            "stop" -> { (node as? TimerNode)?.stop(); return null }
            "play_animation" -> {
                val sprite = node as? AnimatedSprite2D ?: return null
                sprite.animationName = args.getOrNull(0)?.toString() ?: sprite.animationName
                sprite.playing = true
                return null
            }
            "pause_animation" -> { (node as? AnimatedSprite2D)?.playing = false; return null }
            "resume_animation" -> { (node as? AnimatedSprite2D)?.playing = true; return null }
            "set_frame" -> {
                val index = (args.getOrNull(0) as? Double)?.toInt() ?: 0
                when (val target = node) {
                    is dev.lumen2d.core.scene.AnimatedSprite2D -> target.setFrame(index)
                    is Sprite2D -> target.setProperty("frame", index)
                }
                return null
            }
        }
        // A script method on the node itself.
        instance?.let { if (it.hasMethod(name)) return it.callMethod(name, args) }
        node.scriptBehavior?.let { if (it.hasMethod(name)) return it.callMethod(name, args) }
        throw ScriptError("${node.typeName} '${node.name}' has no method '$name'")
    }

    private fun name0(args: List<Any?>, index: Int): String = args.getOrNull(index)?.toString() ?: ""

    fun foreignNode(foreign: ScriptForeign): Node? = (foreign as? NodeForeign)?.node
}

/** Adapter for the engine's [InputState]: `input.pressed("jump")`, `input.axis("move").x`. */
/**
 * A script object whose real foreign is resolved on demand — used for globals that only exist once
 * the node is inside the tree (`input`, `scene`, `engine`).
 */
internal class DeferredForeign(
    private val typeName: String,
    private val getter: () -> ScriptForeign,
) : ScriptForeign {
    override val scriptTypeName: String get() = typeName
    private fun real(): ScriptForeign = getter()
    override fun scriptGet(name: String): Any? = real().scriptGet(name)
    override fun scriptSet(name: String, value: Any?): Boolean = real().scriptSet(name, value)
    override fun scriptCall(name: String, args: List<Any?>): Any? = real().scriptCall(name, args)
    override fun scriptHas(name: String): Boolean = real().scriptHas(name)
}

/** Glob match used by `find_children` (`*`, `?` and literal segments). */
internal fun nameMatches(name: String, pattern: String): Boolean {
    val regex = buildString {
        append('^')
        for (char in pattern) {
            when (char) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(char.toString()))
            }
        }
        append('$')
    }
    return Regex(regex).matches(name)
}

/**
 * Resolves a path passed to `get_node()` / `find_node()`.
 *
 * Candidate bases, first match wins: `""`/`"."` (the node itself), absolute paths from the tree
 * root, explicit `./` and `../` prefixes relative to the node, then the node, the scene root and
 * finally the tree root. Within a path, a bare segment also matches the first descendant with that
 * name (`"Sprite"`, `"UI/HUD/Score"`), so a behaviour script can sit next to the nodes it drives.
 */
internal fun resolveNodePath(from: dev.lumen2d.core.scene.Node, path: String): dev.lumen2d.core.scene.Node? {
    val trimmed = path.trim()
    if (trimmed.isEmpty() || trimmed == ".") return from
    if (trimmed.startsWith("/")) return walkNodePath(from.tree?.root ?: from.root(), trimmed, strict = true)

    val explicitRelative = trimmed.startsWith("./") || trimmed.startsWith("../")
    walkNodePath(from, trimmed, strict = true)?.let { return it }
    if (explicitRelative) return null

    val sceneRoot = from.tree?.currentScene?.root
    if (sceneRoot != null) walkNodePath(sceneRoot, trimmed, strict = false)?.let { return it }
    return walkNodePath(from.root(), trimmed, strict = false)
}

/**
 * Walks `/`-separated segments from [base]. When [strict] is false, a segment that is not a direct
 * child also matches the first descendant with that name (or a `*` pattern).
 */
private fun walkNodePath(base: dev.lumen2d.core.scene.Node, path: String, strict: Boolean): dev.lumen2d.core.scene.Node? {
    var current: dev.lumen2d.core.scene.Node = base
    for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
        current = when (part) {
            "." -> current
            ".." -> current.parent ?: return null
            else -> current.child(part)
                ?: (if (strict) null else descendantByName(current, part))
                ?: return null
        }
    }
    return current
}

/** Depth-first search for a node with [name], optionally a `*`/`?` pattern (`Coin*`). */
private fun descendantByName(root: dev.lumen2d.core.scene.Node, name: String): dev.lumen2d.core.scene.Node? {
    val wildcard = name.contains('*') || name.contains('?')
    return root.descendants().firstOrNull { if (wildcard) nameMatches(it.name, name) else it.name == name }
}

/** Test/tooling entry point for the node-path resolver (see the desktop `--check` command). */
fun resolveNodePathForTest(from: dev.lumen2d.core.scene.Node, path: String) = resolveNodePath(from, path)

class InputForeign(private val input: InputState) : ScriptForeign {
    override val scriptTypeName: String = "input"

    override fun scriptGet(name: String): Any? = when (name) {
        "mouse_position" -> ScriptStruct.vec2(input.mousePosition.x.toDouble(), input.mousePosition.y.toDouble())
        "screen_width" -> input.screenWidth.toDouble()
        "screen_height" -> input.screenHeight.toDouble()
        "pointer_count" -> input.pointerCount.toDouble()
        else -> throw ScriptError("input has no property '$name'")
    }

    override fun scriptSet(name: String, value: Any?): Boolean = false

    override fun scriptCall(name: String, args: List<Any?>): Any? {
        val action = args.getOrNull(0)?.toString() ?: ""
        return when (name) {
            "pressed", "is_pressed", "is_action_pressed" -> input.isActionPressed(action)
            "just_pressed", "is_action_just_pressed" -> input.isActionJustPressed(action)
            "just_released", "is_action_just_released" -> input.isActionJustReleased(action)
            "strength", "action_strength" -> input.actionStrength(action).toDouble()
            "axis" -> input.axis(action).let { ScriptStruct.vec2(it.x.toDouble(), it.y.toDouble()) }
            "any_pressed" -> input.isActionPressed(action)
            "tap", "touch_just_pressed" -> input.activePointers().isNotEmpty()
            "consume", "consume_pointer" -> { input.consumePointer((args.getOrNull(1) as? Double)?.toInt() ?: 0); null }
            else -> throw ScriptError("input has no method '$name'")
        }
    }
}

/** Adapter for a [Renderer], used by scripts that draw custom overlays in `draw()`. */
class RendererForeign(private val renderer: Renderer, private val tree: () -> SceneTree?) : ScriptForeign {
    override val scriptTypeName: String = "renderer"

    override fun scriptGet(name: String): Any? = when (name) {
        "width" -> renderer.width.toDouble()
        "height" -> renderer.height.toDouble()
        "draw_calls" -> renderer.stats.drawCalls.toDouble()
        else -> null
    }

    override fun scriptSet(name: String, value: Any?): Boolean = false

    override fun scriptCall(name: String, args: List<Any?>): Any? {
        when (name) {
            "text" -> {
                val font = tree()?.resources?.fonts?.default
                if (font != null) {
                    val text = args.getOrNull(0)?.toString() ?: ""
                    renderer.drawTextScreen(
                        font, text,
                        (args.getOrNull(1) as? Double)?.toFloat() ?: 0f,
                        (args.getOrNull(2) as? Double)?.toFloat() ?: 0f,
                        (args.getOrNull(3) as? Double)?.toFloat() ?: 1f,
                        (args.getOrNull(4) as? ScriptStruct)?.let { fromScriptValue(it) as? Color } ?: Color.WHITE,
                    )
                }
                return null
            }
            "rect" -> {
                val r = (args.getOrNull(0) as? ScriptStruct)?.let { fromScriptValue(it) }
                val color = (args.getOrNull(1) as? ScriptStruct)?.let { fromScriptValue(it) } as? Color ?: Color.WHITE
                if (r is dev.lumen2d.core.math.Rect) renderer.drawRect(r, color)
                return null
            }
            "screen_rect" -> {
                val r = (args.getOrNull(0) as? ScriptStruct)?.let { fromScriptValue(it) }
                val color = (args.getOrNull(1) as? ScriptStruct)?.let { fromScriptValue(it) } as? Color ?: Color.WHITE
                if (r is dev.lumen2d.core.math.Rect) renderer.drawRectScreen(r, color)
                return null
            }
            else -> throw ScriptError("renderer has no method '$name'")
        }
    }
}

/**
 * One running script attached to a node. Implements [ScriptBehavior] so the scene tree drives it
 * exactly like engine-side behaviours.
 */
class ScriptInstance internal constructor(
    val program: ScriptProgram,
    val node: Node,
    internal val runtime: LumenScriptRuntime,
) : ScriptBehavior {

    override val sourcePath: String get() = program.path

    private val interpreter = runtime.interpreter
    private val env: Env
    private val exportedValues = LinkedHashMap<String, Any?>()
    private val connections = ArrayList<Connection>()
    private var suspended = false

    private class Connection(val owner: Node, val signalName: String, val callback: Any?, val handler: (Any?) -> Unit)

    init {
        val builtins = Env(runtime.globals)
        val self = NodeForeign(node, this)
        env = interpreter.createInstance(program, self, builtinsFor(builtins, self))
        program.exports.forEach { name -> env.get(name)?.let { value -> exportedValues[name] = value } }
    }

    private fun builtinsFor(parent: Env, self: NodeForeign): Env {
        val env = Env(parent)
        fun native(name: String, arity: Int = -1, body: (List<Any?>) -> Any?) {
            env.declare(name, NativeFunction(name, arity, body), isConst = true)
        }
        native("print") { args -> runtime.log(args.joinToString(" ") { Interpreter.stringify(it) }); null }
        native("get_node", 1) { self.scriptCall("get_node", it) }
        native("find_node", 1) { self.scriptCall("get_node", it) }
        native("add_to_group", 1) { node.addToGroup(it[0].toString()); null }
        native("remove_from_group", 1) { node.removeFromGroup(it[0].toString()); null }
        native("is_in_group", 1) { node.isInGroup(it[0].toString()) }
        native("queue_free", 0) { node.queueFree(); null }
        native("set_timer") { args ->
            val seconds = (args.getOrNull(0) as? Double)?.toFloat() ?: 0f
            val callback = args.getOrNull(1) ?: return@native null
            node.setTimer(seconds) { invokeCallback(callback, emptyList()) }
        }
        native("call_deferred") { args ->
            val callback = args.getOrNull(0) ?: return@native null
            node.callDeferred("script:${node.instanceId}") { invokeCallback(callback, emptyList()) }
            null
        }
        native("tween_property") { args ->
            val target = (args.getOrNull(0) as? ScriptForeign)?.let { (it as? NodeForeign)?.node } ?: node
            val property = args.getOrNull(1)?.toString() ?: return@native null
            val value = fromScriptValue(args.getOrNull(2))
            val duration = (args.getOrNull(3) as? Double)?.toFloat() ?: 0.3f
            val easing = args.getOrNull(4)?.toString() ?: "linear"
            toScriptValue(TweenHandle(target.tweenProperty(property, value, duration, easing), this), runtime)
        }
        native("tween_callback") { args ->
            val delay = (args.getOrNull(0) as? Double)?.toFloat() ?: 0f
            val callback = args.getOrNull(1) ?: return@native null
            val manager = node.tree?.tweens
            if (manager == null) { invokeCallback(callback, emptyList()); null }
            else toScriptValue(TweenHandle(manager.create().callback(delay) { invokeCallback(callback, emptyList()) }.start(), this), runtime)
        }
        native("load_scene") { args ->
            val path = args.getOrNull(0)?.toString() ?: return@native null
            runtime.sceneProvider?.invoke(path)?.let { toScriptValue(it, runtime) }
        }
        native("change_scene") { args ->
            runtime.changeScene?.invoke(args.getOrNull(0)?.toString() ?: "") ?: false
        }
        native("instantiate") { args ->
            val source = args.getOrNull(0)
            val path = when (source) {
                is String -> source
                is NodeForeign -> source.node.name
                else -> null
            }
            val parent = (args.getOrNull(1) as? ScriptForeign)?.let { (it as? NodeForeign)?.node } ?: node
            val scene = path?.let { runtime.sceneProvider?.invoke(it) }
            val instance = scene?.instantiate()
            if (instance != null) {
                instance.name = scene.name.substringAfterLast('/').removeSuffix(".scene.json").ifEmpty { instance.name }
                parent.addChild(instance)
            }
            instance?.let { toScriptValue(it, runtime) }
        }
        native("randf") { kotlin.random.Random.nextDouble() }
        native("randi") { args ->
            val from = (args.getOrNull(0) as? Double)?.toInt() ?: 0
            val to = (args.getOrNull(1) as? Double)?.toInt() ?: from
            if (to <= from) from.toDouble() else kotlin.random.Random.nextInt(from, to + 1).toDouble()
        }
        native("randomize") { null }
        native("now") { System.currentTimeMillis().toDouble() / 1000.0 }
        native("emit_signal") { args ->
            emitSignal(args.getOrNull(0)?.toString() ?: "", args.drop(1)); null
        }
        native("connect_signal") { args ->
            val target = (args.getOrNull(0) as? ScriptForeign)?.let { it as? NodeForeign }
            if (target != null) connectSignal(target, args.getOrNull(1)?.toString() ?: "", args.getOrNull(2))
            null
        }
        native("play_sound") { args ->
            runtime.playSound?.invoke(args.getOrNull(0)?.toString() ?: "", (args.getOrNull(1) as? Double)?.toFloat() ?: 1f)
            null
        }
        native("assert") { args ->
            if (!interpreter.truthyValue(args.getOrNull(0))) {
                throw ScriptError("assertion failed: ${args.getOrNull(1) ?: "condition is false"}", 0, sourcePath)
            }
            null
        }
        native("find_children") { args -> self.scriptCall("find_children", args) }
        native("spawn") { args -> self.scriptCall("spawn", args) }
        native("get_parent_node") { args -> self.scriptCall("get_parent", args) }
        native("queue_free") { args ->
            ((args.getOrNull(0) as? ScriptForeign) as? NodeForeign)?.node?.queueFree()
            null
        }
        native("nodes_in_group") { args ->
            val group = args.getOrNull(0)?.toString() ?: ""
            val found = node.tree?.nodesInGroup(group) ?: emptyList()
            found.map { toScriptValue(it, runtime) }.toMutableList()
        }
        native("sign") { args ->
            dev.lumen2d.core.math.MathUtil.sign(((args.getOrNull(0) as? Double) ?: 0.0).toFloat()).toDouble()
        }
        native("sqrt") { args -> kotlin.math.sqrt(((args.getOrNull(0) as? Double) ?: 0.0)) }
        native("vec2") { args ->
            val x = (args.getOrNull(0) as? Double) ?: 0.0
            val y = (args.getOrNull(1) as? Double) ?: x
            ScriptStruct.vec2(x, y)
        }
        // Runtime globals: input action map, the live scene root and simple engine info.
        env.declare("input", DeferredForeign("input") { InputForeign(node.tree?.input ?: InputState()) }, isConst = true)
        env.declare("scene", DeferredForeign("scene") {
            node.tree?.currentScene?.root?.let { NodeForeign(it, null) } ?: NodeForeign(node.root(), null)
        }, isConst = true)
        env.declare("engine", BoxedForeign(
            linkedMapOf<String, Any?>(
                "name" to dev.lumen2d.core.util.EngineInfo.NAME,
                "version" to dev.lumen2d.core.util.EngineInfo.VERSION,
                "api_version" to dev.lumen2d.core.util.EngineInfo.API_VERSION.toDouble(),
                "platform" to (node.tree?.platformName ?: "unknown"),
            ), "engine"), isConst = true
        )
        native("pause_tree") { node.tree?.paused = true; null }
        native("resume_tree") { node.tree?.paused = false; null }
        return env
    }

    // ------------------------------------------------------------- ScriptBehavior

    override fun onReady() { call("ready") }

    override fun process(delta: Float) {
        call("process", delta.toDouble())
        call("update", delta.toDouble())
    }

    override fun physicsProcess(delta: Float) { call("physics_process", delta.toDouble()) }

    override fun draw(renderer: Renderer, alpha: Float) {
        if (!hasMethod("draw")) return
        runtime.activeRenderer = RendererForeign(renderer, { node.tree })
        try {
            // `draw(renderer, alpha)` — the renderer argument is passed by the tree.
            interpreter.invoke(env.get("draw"), listOf(runtime.activeRenderer, alpha.toDouble()), 0)
        } catch (t: ScriptError) { report(t) }
    }

    override fun onTreeExit() {
        call("exit_tree")
        call("on_tree_exit")
    }

    override fun onDestroy() {
        connections.forEach { runCatching { it.handler } }
        connections.clear()
        call("destroy")
    }

    override fun callMethod(name: String, args: List<Any?>): Any? {
        val function = env.get(name)
        if (function === Env.UNDEFINED || function == null) return null
        return runCatching { interpreter.invoke(function, args, 0) }
            .onFailure { if (it is ScriptError) report(it) }
            .getOrNull()
    }

    override fun hasMethod(name: String): Boolean = env.get(name) is ScriptFunction

    override val isSuspended: Boolean get() = suspended

    override val exportedProperties: List<Pair<String, Any?>>
        get() = program.exports.mapNotNull { name -> variableOrNull(name)?.let { name to it } }

    override fun setExported(name: String, value: Any?) {
        if (env.has(name)) env.assign(name, value)
        exportedValues[name] = value
    }

    override fun resumeWith(signalName: String, args: List<Any?>) {
        suspended = false
        call("on_signal", signalName, args)
    }

    // ----------------------------------------------------------------- internals

    /** Runs a script function by name if it exists (lifecycle hooks). */
    private fun call(name: String, vararg args: Any?) {
        val function = env.get(name)
        if (function === Env.UNDEFINED || function !is ScriptFunction && function !is NativeFunction) return
        try {
            interpreter.invoke(function, args.toList(), 0)
        } catch (t: ScriptError) {
            report(t)
        } catch (t: Throwable) {
            Log.e("Script", "${sourcePath}: ${name}() failed: ${t.message}")
        }
    }

    internal fun invokeCallback(callback: Any?, args: List<Any?>) {
        try {
            interpreter.invoke(callback, args, 0)
        } catch (t: ScriptError) { report(t) }
    }

    internal fun hasVariable(name: String): Boolean = env.has(name) && env.get(name) !is ScriptFunction

    internal fun variableOrNull(name: String): Any? {
        val value = env.get(name)
        return if (value === Env.UNDEFINED) null else value
    }

    internal fun setVariable(name: String, value: Any?) {
        if (!env.assign(name, value)) env.declare(name, value)
    }

    /** Reads an exported value (used by the inspector and by save games). */
    fun exportValue(name: String): Any? = exportedValues[name]

    /** Reads any script variable (constants, locals promoted to globals, exports). */
    fun scriptVar(name: String): Any? = variableOrNull(name)

    /** Writes any script variable, declaring it when needed. */
    fun setScriptVar(name: String, value: Any?) = setVariable(name, value)

    internal fun emitSignal(name: String, args: List<Any?>) {
        val signal = LumenScriptRuntime.signalFor(node, name) ?: return
        signal.emit(args)
    }

    internal fun connectSignal(target: NodeForeign, signalName: String, callback: Any?) {
        val function = callback as? ScriptFunction ?: return
        val signal = LumenScriptRuntime.signalFor(target.node, signalName) ?: run {
            Log.w("Script", "$sourcePath: signal '$signalName' not found on ${target.node.typeName}")
            return
        }
        signal.connect { args -> invokeCallback(function, args) }
    }

    internal fun disconnectSignal(target: NodeForeign, signalName: String) {
        LumenScriptRuntime.signalFor(target.node, signalName)?.clear()
    }

    private fun report(error: ScriptError) {
        Log.e("Script", error.prettyMessage)
        runtime.errors.add(error.prettyMessage)
    }
}

/** Wrapper for engine [dev.lumen2d.core.scene.Tween] values returned by `tween_property`. */
class TweenHandle(private val tween: dev.lumen2d.core.scene.Tween, private val instance: ScriptInstance) : ScriptForeign {

    override val scriptTypeName: String = "tween"

    override fun scriptGet(name: String): Any? = when (name) {
        "is_running" -> tween.isRunning
        "is_finished" -> tween.isFinished
        else -> throw ScriptError("tween has no property '$name'")
    }

    override fun scriptSet(name: String, value: Any?): Boolean = false

    override fun scriptCall(name: String, args: List<Any?>): Any? {
        when (name) {
            "connect_finished", "finished" -> tween.finished.connect { instance.invokeCallback(args.getOrNull(0), emptyList()) }
            "kill", "stop" -> tween.kill()
            "pause" -> tween.pause()
            "resume" -> tween.resume()
            "set_loops" -> tween.setLoops((args.getOrNull(0) as? Double)?.toInt() ?: 1, args.getOrNull(1) as? Boolean ?: false)
            "wait" -> { tween.wait((args.getOrNull(0) as? Double)?.toFloat() ?: 0f); return this }
            "property" -> {
                val target = (args.getOrNull(0) as? ScriptForeign)?.let { (it as? NodeForeign)?.node } ?: instance.node
                tween.property(target, args.getOrNull(1)?.toString() ?: "", fromScriptValue(args.getOrNull(2)),
                    (args.getOrNull(3) as? Double)?.toFloat() ?: 0.3f, args.getOrNull(4)?.toString() ?: "linear")
                return this
            }
            "callback" -> {
                tween.callback((args.getOrNull(0) as? Double)?.toFloat() ?: 0f) {
                    instance.invokeCallback(args.getOrNull(1), emptyList())
                }
                return this
            }
            else -> throw ScriptError("tween has no method '$name'")
        }
        return null
    }
}

/**
 * Compiles scripts, caches the AST and creates [ScriptInstance]s. Install it on the game with
 * `game.scriptRuntime = LumenScriptRuntime { path -> database.loadScriptSource(path) }`.
 */
class LumenScriptRuntime(
    /** Reads script text for a virtual path (`scripts/player.lumen`). */
    private val sourceProvider: (String) -> String?,
    /** Loads a scene by path for `load_scene()` / `instantiate()`. */
    var sceneProvider: ((String) -> dev.lumen2d.core.scene.Scene?)? = null,
    /** Switches the active scene for `change_scene()`. */
    var changeScene: ((String) -> Boolean)? = null,
    /** Plays a clip by asset id for `play_sound()`. */
    var playSound: ((String, Float) -> Unit)? = null,
) : SceneTree.ScriptRuntimeHost, ScriptRuntimeContext {

    internal val interpreter = Interpreter(this)
    internal val globals: Env = ScriptMath.buildEnvs(Env())

    /** Compile errors, surfaced in the editor's console. */
    val errors = ArrayList<String>()

    /** Renderer wrapper handed to `draw(renderer, alpha)` (recreated per frame). */
    internal var activeRenderer: RendererForeign? = null

    private val cache = HashMap<String, ScriptProgram>()

    /** True when [path] has already been compiled (used by the editor's hot reload). */
    fun isCompiled(path: String): Boolean = cache.containsKey(path)

    /** Drops the compiled form of [path] (or everything) so the next instantiation re-reads it. */
    fun invalidate(path: String? = null) {
        if (path == null) cache.clear() else cache.remove(path)
    }

    /** Compiles without instantiating — the editor uses this for syntax checking. */
    fun compile(path: String, source: String): ScriptProgram {
        val tokens = Lexer(source, path).tokenize()
        return Parser(tokens, path).parseProgram().also { cache[path] = it }
    }

    override fun instantiate(scriptPath: String, node: Node, exported: Map<String, Any?>): ScriptBehavior? {
        val program = cache[scriptPath] ?: run {
            val source = sourceProvider(scriptPath)
            if (source == null) {
                val message = "script '$scriptPath' not found"
                errors.add(message)
                Log.e("Script", message)
                return null
            }
            try {
                compile(scriptPath, source)
            } catch (t: ScriptError) {
                // A syntax error keeps the node alive without a behaviour, so the rest of the scene
                // still runs and the editor can show the error next to the script.
                errors.add(t.prettyMessage)
                Log.e("Script", t.prettyMessage)
                return null
            }
        }
        return try {
            ScriptInstance(program, node, this).also { instance ->
                // Scene-authored export values win over the script defaults.
                exported.forEach { (key, value) -> instance.setExported(key, toScriptValue(value, this)) }
            }
        } catch (t: ScriptError) {
            errors.add(t.prettyMessage)
            Log.e("Script", t.prettyMessage)
            null
        }
    }

    override fun foreignFor(value: Any): Any? = when (value) {
        is Node -> NodeForeign(value, null)
        is InputState -> InputForeign(value)
        is AudioClip -> BoxedForeign(value, "audio_clip")
        else -> null
    }

    override fun log(message: String) {
        Log.i("Script", message)
    }

    companion object {
        /** Engine signals addressable from scripts by name, per node type. */
        fun signalFor(node: Node, name: String): ScriptSignal? = when (node) {
            is HealthNode -> when (name) {
                "damage_taken", "damageTaken" -> ScriptSignal { handler -> node.damageTaken.connect { v -> handler(listOf(v.toDouble())) } }
                "healed" -> ScriptSignal { handler -> node.healed.connect { v -> handler(listOf(v.toDouble())) } }
                "died" -> ScriptSignal { handler -> node.died.connect { handler(emptyList()) } }
                "health_changed", "healthChanged" -> ScriptSignal { handler -> node.healthChanged.connect { v -> handler(listOf(v.toDouble())) } }
                else -> null
            }
            is ScoreNode -> when (name) {
                "score_changed", "scoreChanged" -> ScriptSignal { handler -> node.scoreChanged.connect { v -> handler(listOf(v.toDouble())) } }
                "combo_changed" -> ScriptSignal { handler -> node.comboChanged.connect { v -> handler(listOf(v.toDouble())) } }
                "new_best" -> ScriptSignal { handler -> node.newBest.connect { handler(emptyList()) } }
                else -> null
            }
            is AudioPlayerNode -> when (name) {
                "finished" -> ScriptSignal { handler -> node.finished.connect { handler(emptyList()) } }
                else -> null
            }
            is AnimatedSprite2D -> when (name) {
                "frame_changed", "animation_finished" -> ScriptSignal { handler -> node.frameChanged.connect { handler(emptyList()) } }
                else -> null
            }
            is ButtonControl -> when (name) {
                "clicked", "pressed" -> ScriptSignal { handler -> node.clicked.connect { handler(emptyList()) } }
                "released" -> ScriptSignal { handler -> node.released.connect { handler(emptyList()) } }
                else -> null
            }
            is TimerNode -> when (name) {
                "timeout" -> ScriptSignal { handler -> node.timeout.connect { handler(emptyList()) } }
                else -> null
            }
            else -> when (name) {
                "ready" -> ScriptSignal { handler -> node.ready.connect { handler(emptyList()) } }
                "tree_exited" -> ScriptSignal { handler -> node.treeExited.connect { handler(emptyList()) } }
                "freed" -> ScriptSignal { handler -> node.freed.connect { handler(emptyList()) } }
                else -> null
            }
        }
    }
}

/** Type-erased signal bridge so scripts can connect by name. */
class ScriptSignal(private val connectBlock: ((List<Any?>) -> Unit) -> Unit) {
    fun connect(handler: (List<Any?>) -> Unit) = connectBlock(handler)
    fun emit(args: List<Any?>) = Unit // engine signals are emitted by their owners
    fun clear() = Unit
}

/** Convenience: formats a number the way scripts expect (no trailing ".0"). */
internal fun formatNumber(value: Double): String =
    if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()
