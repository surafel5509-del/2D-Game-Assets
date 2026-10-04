/**
 * Lumen2D — scene tree, scenes, timers and the script behaviour contract.
 *
 * [SceneTree] owns the runtime: it drives the fixed-step physics loop, variable-step
 * gameplay loop, rendering order, timers, deferred calls, node groups and scene switching.
 * The editor, the runtime player and the automated tests all drive the engine through it.
 */
package dev.lumen2d.core.scene

import dev.lumen2d.core.audio.AudioMixer
import dev.lumen2d.core.input.InputState
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.physics.PhysicsWorld
import dev.lumen2d.core.render.Renderer
import dev.lumen2d.core.util.Event
import dev.lumen2d.core.util.GameClock
import dev.lumen2d.core.util.Log
import dev.lumen2d.core.util.Profiler
import dev.lumen2d.core.util.Signal

/**
 * Contract implemented by the scripting runtime (`dev.lumen2d.core.script.ScriptInstance`).
 * Keeping it here lets nodes hold scripts without the scene package depending on the VM.
 */
interface ScriptBehavior {
    /** Virtual path of the script resource. */
    val sourcePath: String
    /** Called when the script starts on its node. */
    fun onReady() {}
    fun process(delta: Float) {}
    fun physicsProcess(delta: Float) {}
    fun draw(renderer: Renderer, alpha: Float) {}
    fun onTreeExit() {}
    fun onDestroy() {}
    /** Invoked when a signal the script awaited fires. */
    fun resumeWith(signalName: String, args: List<Any?>) {}
    /** Calls a script function by name; returns the value (null when absent). */
    fun callMethod(name: String, args: List<Any?>): Any? = null
    fun hasMethod(name: String): Boolean = false
    /** True while the script is suspended on an `await`. */
    val isSuspended: Boolean get() = false
    /** Values exposed to the inspector as extra properties. */
    val exportedProperties: List<Pair<String, Any?>> get() = emptyList()
    fun setExported(name: String, value: Any?) {}
}

/** A timer owned by a node; created through `Node.setTimer` or the `Timer` node. */
class NodeTimer(
    var duration: Float,
    var repeat: Boolean = false,
    var onTimeout: () -> Unit,
) {
    var timeLeft: Float = duration
    var isActive: Boolean = false
        private set
    /** When true the timer counts down even while the tree is paused. */
    var processWhilePaused: Boolean = false
    var timeScale: Float = 1f
    var owner: Node? = null
        private set
    /** Emitted every time the timer fires. */
    val timeout = Event()

    fun attachTo(node: Node): NodeTimer { owner = node; return this }

    fun start(seconds: Float = duration) {
        duration = seconds
        timeLeft = seconds
        isActive = true
        (owner?.tree)?.registerTimer(this)
    }

    fun restart() = start(duration)

    fun stop() { isActive = false; (owner?.tree)?.unregisterTimer(this) }

    /** Advances the timer; returns true when it fired this update. */
    internal fun tick(delta: Float): Boolean {
        if (!isActive) return false
        timeLeft -= delta * timeScale
        var fired = false
        // A long frame can cover several intervals: fire once per elapsed period.
        while (isActive && timeLeft <= 0f) {
            val callback = onTimeout
            timeout.emit()
            if (!isActive) return true
            callback()
            fired = true
            if (!repeat) { stop(); break }
            if (duration <= 0f) break
            timeLeft += duration
        }
        return fired
    }

    val progress: Float get() = if (duration <= 0f) 1f else (1f - timeLeft / duration).coerceIn(0f, 1f)
}

/**
 * A saved scene: a node tree plus editor metadata. Scenes are the unit the editor opens,
 * the runtime switches between, and prefabs are instanced from.
 */
class Scene(
    var name: String,
    var root: Node,
    var path: String = "",
    /** Camera position/zoom saved by the editor so reopening a level looks familiar. */
    var editorState: MutableMap<String, Any?> = LinkedHashMap(),
    /** Assets the scene references (kept in sync by the editor for dependency tracking). */
    var dependencies: MutableSet<String> = LinkedHashSet(),
) {
    /** Deep copy including a fresh node tree (prefab instancing). */
    fun instantiate(): Node {
        val data = root.serialize(true)
        val copy = NodeRegistry.create(data["type"]?.toString() ?: "Node") ?: Node(root.name)
        copy.deserialize(data, NodeRegistry)
        copy.name = root.name
        return copy
    }

    fun serialize(withEditorState: Boolean = true): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["format"] = "lumen2d.scene"
        out["version"] = 1
        out["name"] = name
        out["root"] = root.serialize(true)
        if (withEditorState && editorState.isNotEmpty()) out["editor"] = editorState
        if (dependencies.isNotEmpty()) out["dependencies"] = dependencies.toList()
        return out
    }

    companion object {
        const val EXTENSION = ".scene.json"

        fun fromJson(data: Map<String, Any?>, path: String = ""): Scene {
            NodeRegistry.ensureBuiltinsRegistered()
            val rootData = (data["root"] as? Map<*, *>)?.let { map ->
                @Suppress("UNCHECKED_CAST") map as Map<String, Any?>
            } ?: throw IllegalArgumentException("Scene file has no 'root' node")
            val rootType = rootData["type"]?.toString() ?: "Node"
            val root = NodeRegistry.create(rootType) ?: Node(rootData["name"]?.toString() ?: "root")
            root.deserialize(rootData, NodeRegistry)
            root.onSceneLoaded()
            val scene = Scene(data["name"]?.toString() ?: path.substringAfterLast('/').removeSuffix(EXTENSION), root, path)
            (data["editor"] as? Map<*, *>)?.let { editor ->
                editor.forEach { (k, v) -> scene.editorState[k.toString()] = v }
            }
            (data["dependencies"] as? List<*>)?.forEach { it?.toString()?.let { dep -> scene.dependencies.add(dep) } }
            return scene
        }

        /** Builds an empty scene with the standard root structure the editor expects. */
        fun empty(name: String = "Level"): Scene {
            val root = Node2D(name)
            root.addChild(Node2D("Environment"))
            root.addChild(Node2D("Gameplay"))
            root.addChild(Node2D("UI"))
            return Scene(name, root)
        }
    }
}

/**
 * The runtime scene tree.
 *
 * Typical host loop (implemented by the Android player, the desktop player and CI):
 * ```
 * tree.start()
 * while (running) { tree.process(delta); tree.render(renderer) }
 * ```
 */
class SceneTree(
    var renderer: Renderer? = null,
    var audio: AudioMixer? = null,
    var assets: Any? = null,
    val input: InputState = InputState(),
    val physics: PhysicsWorld = PhysicsWorld(),
) {
    val root: Node = Node("root")
    val clock = GameClock()

    /** Tween timeline manager driven from [process]. */
    val tweens = TweenManager()

    /** Host contract implemented by the scripting runtime. */
    interface ScriptRuntimeHost {
        fun instantiate(scriptPath: String, node: Node, exported: Map<String, Any?>): ScriptBehavior?
    }

    /** Fired after a scene finished loading. */
    val sceneChanged = Signal<Scene>()
    val nodeAdded = Signal<Node>()
    val nodeRemoved = Signal<Node>()
    /** Editor hook: fired when the hierarchy changed (tree view refresh). */
    val hierarchyChanged = Event()
    /** Backend name reported to scripts and the editor ("desktop", "android", "headless"). */
    var platformName: String = "unknown"

    var currentScene: Scene? = null
        private set
    var isStarted: Boolean = false
        private set
    var paused: Boolean = false
    var timeScale: Float
        get() = clock.timeScale
        set(value) { clock.timeScale = value; physics.enabled = !paused }

    /** Continuous collision-safe fixed timestep accumulator. */
    private var physicsAccumulator = 0f
    var physicsTicks: Long = 0
        private set
    /** Frame counter (also readable by scripts as `Engine.frame`). */
    val frameCount: Long get() = clock.frame

    private val groups = HashMap<String, MutableSet<Node>>()
    private val timers = LinkedHashSet<NodeTimer>()
    private val deferredCalls = ArrayList<DeferredCall>()
    private val deletionQueue = ArrayList<Node>()
    private val drawOrderCache = HashMap<Node, List<Node>>()

    /**
     * Script runtime (dev.lumen2d.core.script.ScriptRuntime). Null until the host installs one;
     * ScriptNode and prefab scripts are skipped gracefully while it is absent.
     */
    var scriptRuntime: ScriptRuntimeHost? = null

    /** Textures, fonts, audio and asset database shared by every node in this tree. */
    var resources: SceneResources? = null

    /** The camera used for rendering the world (set by a [CameraNode] with isCurrent). */
    var activeCamera: CameraNode? = null

    /** Screen/viewport size, mirrored from the renderer each frame. */
    var viewportWidth: Float = 480f
    var viewportHeight: Float = 270f

    /** Global pause that ignores per-node process modes (used by the editor). */
    var hardPaused: Boolean = false

    private class DeferredCall(val tag: String, val owner: Node?, val action: () -> Unit)

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (isStarted) return
        NodeRegistry.ensureBuiltinsRegistered()
        root.tree = this
        root.propagateEnterTree()
        root.propagateReady()
        isStarted = true
        clock.reset()
        Log.i("SceneTree", "Scene tree started (${root.descendants().size} nodes)")
    }

    fun stop() {
        isStarted = false
        root.propagateExitTree()
        root.children.toList().forEach { root.removeChild(it) }
        currentScene = null
        timers.clear(); groups.clear(); deferredCalls.clear(); deletionQueue.clear()
        Log.i("SceneTree", "Scene tree stopped")
    }

    /** Loads [scene] as the active scene, freeing the previous one. */
    fun setScene(scene: Scene) {
        if (!isStarted) start()
        root.children.toList().forEach { root.removeChild(it) }
        groups.clear()
        root.addChild(scene.root)
        currentScene = scene
        scene.root.onSceneLoaded()
        sceneChanged.emit(scene)
        Log.i("SceneTree", "Scene '${scene.name}' loaded (${scene.root.descendants().size} nodes)")
    }

    /** Adds a node directly under the root (persistent managers, HUDs, debug panels). */
    fun addPersistent(node: Node, name: String = node.name) {
        if (!isStarted) start()
        node.name = name
        root.addChild(node)
    }

    // -------------------------------------------------------------------- grouping

    /** Called when a node is added anywhere in the tree: indexes groups and fires [nodeAdded]. */
    fun notifyNodeAdded(node: Node) {
        for (group in node.groups) indexGroup(node, group)
        for (child in node.descendants()) {
            for (group in child.groups) indexGroup(child, group)
        }
        nodeAdded.emit(node)
    }

    fun indexGroup(node: Node, group: String) {
        groups.getOrPut(group) { LinkedHashSet() }.add(node)
    }

    fun unindexGroup(node: Node, group: String) {
        groups[group]?.remove(node)
        if (groups[group]?.isEmpty() == true) groups.remove(group)
    }

    /** All nodes in a group (first matching group wins; empty when unknown). */
    fun nodesInGroup(group: String): List<Node> = groups[group]?.filter { !it.isQueuedForDeletion } ?: emptyList()

    fun firstNodeInGroup(group: String): Node? = nodesInGroup(group).firstOrNull()

    fun groupNames(): Set<String> = groups.keys.toSet()

    fun groupCount(group: String): Int = groups[group]?.size ?: 0

    // --------------------------------------------------------------------- timers

    fun createTimer(seconds: Float, repeat: Boolean, onTimeout: () -> Unit): NodeTimer {
        val timer = NodeTimer(seconds, repeat, onTimeout)
        registerTimer(timer)
        timer.start(seconds)
        return timer
    }

    internal fun registerTimer(timer: NodeTimer) { timers.add(timer) }
    internal fun unregisterTimer(timer: NodeTimer) { timers.remove(timer) }

    private fun updateTimers(delta: Float) {
        if (timers.isEmpty()) return
        for (timer in timers.toList()) {
            if (paused && !timer.processWhilePaused) continue
            timer.tick(delta)
        }
    }

    // ------------------------------------------------------------------- save state

    /**
     * Captures every node that joined the [dev.lumen2d.core.game.SAVE_STATE_GROUP] group.
     * Nodes opt in by calling `addToGroup("save_state")`; the built-in `save`/`load` API in
     * [dev.lumen2d.core.game.Game] writes the returned map into a save slot.
     */
    fun collectSaveState(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (root.children.isEmpty()) return out
        for (node in root.descendants(includeSelf = true)) {
            if (dev.lumen2d.core.game.SAVE_STATE_GROUP !in node.groups) continue
            val props = LinkedHashMap<String, Any?>()
            for (def in node.propertyDefinitions()) {
                if (!def.persist) continue
                node.getProperty(def.name)?.let { props[def.name] = dev.lumen2d.core.game.SaveStateCodec.encode(it) }
            }
            node.scriptBehavior?.exportedProperties?.forEach { (key, value) ->
                props["script.$key"] = dev.lumen2d.core.game.SaveStateCodec.encode(value)
            }
            if (props.isNotEmpty()) out[node.nodePath()] = props
        }
        return out
    }

    /**
     * Finds a node by its slash-separated path. Paths come from [Node.nodePath], which does not
     * include the internal tree root, so the lookup cannot simply use relative resolution.
     */
    private fun nodeByPath(path: String): Node? {
        val target = path.trim('/')
        if (target.isEmpty()) return root
        for (node in root.descendants(includeSelf = true)) if (node.nodePath() == target) return node
        return root.nodePath(path) ?: root.nodePath("/$target")
    }

    /** Restores a map produced by [collectSaveState]; unknown nodes are skipped. */
    fun applySaveState(data: Map<String, Any?>) {
        var applied = 0
        for ((path, raw) in data) {
            val props = raw as? Map<*, *> ?: continue
            val node = nodeByPath(path) ?: continue
            for ((key, value) in props) {
                val name = key.toString()
                if (name.startsWith("script.")) {
                    node.scriptBehavior?.setExported(name.removePrefix("script."), value)
                } else {
                    node.setProperty(name, dev.lumen2d.core.game.SaveStateCodec.decode(value))
                }
            }
            applied++
        }
        if (applied > 0) Log.i("SaveState", "Restored $applied node(s)")
    }

    // --------------------------------------------------------- deferred + deletion

    fun deferCall(tag: String, owner: Node?, action: () -> Unit) {
        deferredCalls.add(DeferredCall(tag, owner, action))
    }

    /** Cancels pending deferred calls (used when an editor tool is rolled back). */
    fun cancelDeferred(tag: String, owner: Node? = null) {
        deferredCalls.removeAll { it.tag == tag && (owner == null || it.owner === owner) }
    }

    fun queueDeletion(node: Node) { if (node !in deletionQueue) deletionQueue.add(node) }

    private fun processDeletions() {
        if (deletionQueue.isEmpty()) return
        val snapshot = deletionQueue.toList()
        deletionQueue.clear()
        for (node in snapshot) {
            node.parent?.removeChild(node) ?: run {
                if (node !== root) root.removeChild(node)
            }
            undoGroupIndexing(node)
            node.free()
            nodeRemoved.emit(node)
        }
        notifyHierarchyChanged()
    }

    private fun undoGroupIndexing(node: Node) {
        for (group in node.groups) unindexGroup(node, group)
        for (child in node.descendants()) for (group in child.groups) unindexGroup(child, group)
    }

    // ------------------------------------------------------------------ housekeeping

    fun notifyHierarchyChanged() {
        drawOrderCache.clear()
        hierarchyChanged.emit()
    }

    fun markDrawOrderDirty() { drawOrderCache.clear() }

    /**
     * Sorted children for drawing: ascending `z_index`, ties broken by tree order (the sort is
     * stable, so siblings keep the order the editor shows). Lower indices draw first — behind.
     */
    fun drawOrderOf(parent: Node): List<Node> {
        val cached = drawOrderCache[parent]
        if (cached != null) return cached
        val sorted = parent.children.sortedBy { (it as? Node2D)?.zIndex ?: 0 }
        drawOrderCache[parent] = sorted
        return sorted
    }

    // --------------------------------------------------------------- main iteration

    /** Runs one frame: input → physics → gameplay → timers → deferred → deletions. */
    fun process(deltaOverride: Float = -1f) {
        Profiler.beginFrame()
        if (!isStarted) start()
        val delta = if (deltaOverride >= 0f) deltaOverride else clock.tick()
        val rawDelta = if (deltaOverride >= 0f) deltaOverride else clock.unscaledDelta

        if (hardPaused) { Profiler.endFrame(); return }

        renderer?.let {
            viewportWidth = it.width.toFloat()
            viewportHeight = it.height.toFloat()
        }
        input.onViewportChanged(viewportWidth, viewportHeight)
        input.beginFrame(delta)

        // Fixed-step physics.
        val step = physics.fixedDelta
        physicsAccumulator += if (paused) 0f else delta
        var iterations = 0
        while (physicsAccumulator >= step && iterations < 5) {
            Profiler.measure("physics") {
                if (!paused) root.propagatePhysicsProcess(step, false)
                physics.step(step)
            }
            physicsAccumulator -= step
            physicsTicks++
            iterations++
        }
        if (iterations >= 5) physicsAccumulator = 0f   // avoid spiral of death after a hitch

        Profiler.measure("process") { root.propagateProcess(delta, paused) }
        dispatchUiInput()
        updateTimers(delta)
        if (!paused) tweens.update(delta)
        audio?.update(delta)
        input.endFrame()

        // Deferred calls run after everything else this frame.
        if (deferredCalls.isNotEmpty()) {
            val snapshot = deferredCalls.toList()
            deferredCalls.clear()
            for (call in snapshot) runCatching { call.action() }
                .onFailure { Log.e("SceneTree", "Deferred call '${call.tag}' failed", it) }
        }
        processDeletions()
        Profiler.endFrame()
    }

    /**
     * Routes pointer events to UI controls (topmost first) and lets each consume taps so
     * gameplay is not triggered by UI presses. Called automatically by [process].
     */
    private fun dispatchUiInput() {
        val controls = groups["ui"]?.toList() ?: return
        if (controls.isEmpty()) return
        val pointers = input.activePointers()
        if (pointers.isEmpty()) {
            for (control in controls) (control as? ControlNode)?.pointerLeft()
            return
        }
        // Reverse order approximates z-order: the last UI node in the tree is on top.
        for (control in controls.asReversed()) {
            val ui = control as? ControlNode ?: continue
            if (!ui.visibleUi || !ui.enabled) continue
            for (pointer in pointers) {
                if (pointer.consumed && pointer.isDown) continue
                val handled = ui.handlePointer(pointer, pointer.isDown)
                if (handled) input.consumePointer(pointer.id)
            }
        }
    }

    /** Renders the current scene into [renderer]. */
    fun render(clearColor: dev.lumen2d.core.math.Color = dev.lumen2d.core.math.Color(0.07f, 0.08f, 0.12f)) {
        val target = renderer ?: return
        Profiler.measure("render") {
            target.beginFrame(clearColor)
            val scene = currentScene ?: return@measure
            target.debugDraw = debugDrawEnabled
            activeCamera?.camera?.applyTo(target)
            scene.root.propagateDraw(target, 1f)
            if (debugDrawEnabled) drawDebugOverlay(target)
            target.endFrame()
        }
        val stats = target.stats
        Profiler.count("drawCalls", stats.drawCalls.toFloat())
        Profiler.count("sprites", stats.sprites.toFloat())
        Profiler.count("nodes", stats.renderedNodes.toFloat())
        Profiler.count("bodies", physics.bodyCount.toFloat())
    }

    /** Physics-shape and node debug overlay (editor "visible collision shapes" toggle). */
    var debugDrawEnabled: Boolean = false

    private fun drawDebugOverlay(renderer: Renderer) {
        val color = dev.lumen2d.core.math.Color(0.3f, 1f, 0.5f, 0.85f)
        for (body in physics.allBodies()) {
            val bounds = body.worldBounds()
            renderer.drawRect(bounds, color, filled = false, lineWidth = 1f)
            val velocityLength = body.velocity.length
            if (velocityLength > 1f) {
                val start = body.position
                val end = start + body.velocity.normalized() * (velocityLength * 0.1f)
                renderer.drawLine(start.x, start.y, end.x, end.y, dev.lumen2d.core.math.Color.RED, 1f)
            }
        }
    }

    // ------------------------------------------------------------------- utilities

    /** Finds a node by absolute path from the tree root ("root/Level/Player"). */
    fun nodeAt(path: String): Node? {
        val trimmed = path.removePrefix("/")
        val parts = trimmed.split('/').filter { it.isNotEmpty() }
        var current: Node? = if (parts.firstOrNull() == root.name) root else root
        for (part in parts.dropWhile { it == root.name }) {
            current = current?.child(part) ?: return null
        }
        return current
    }

    /** Recursively finds nodes of a given type name. */
    fun nodesOfType(typeName: String): List<Node> = root.descendants().filter { it.typeName == typeName }

    inline fun <reified T : Node> nodesOf(): List<T> = root.descendants().filterIsInstance<T>()

    /** Time-scaled seconds since start (scripts read this as `Engine.time`). */
    val time: Float get() = clock.elapsedSeconds

    val nodeCount: Int get() = root.descendants().size

    fun stats(): Map<String, Any> = mapOf(
        "nodes" to nodeCount,
        "groups" to groups.size,
        "bodies" to physics.bodyCount,
        "timers" to timers.size,
        "frame" to clock.frame,
        "time" to clock.elapsedSeconds,
    )

    /** Deep snapshot used by saves and by the editor's "scene as text" view. */
    fun snapshotScene(): Map<String, Any?>? = currentScene?.serialize()
}
