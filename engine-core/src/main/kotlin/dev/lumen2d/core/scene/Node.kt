/**
 * Lumen2D — the scene graph.
 *
 * [Node] is the base of everything in a Lumen2D project: sprites, bodies, UI, tilemaps and
 * user scripts all derive from it. The design mirrors the ergonomics of Godot — a tree with
 * named nodes, lifecycle callbacks, signals, groups and a property system that the editor's
 * inspector drives generically.
 */
package dev.lumen2d.core.scene

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Transform2D
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.util.Event
import dev.lumen2d.core.util.Log
import dev.lumen2d.core.util.Signal

/** How a node behaves when the tree is paused. */
enum class ProcessMode { INHERIT, PAUSABLE, WHEN_PAUSED, ALWAYS }

/** Value kinds understood by the property system (and therefore by the inspector UI). */
enum class PropertyType {
    FLOAT, INT, BOOL, STRING, VECTOR2, RECT, COLOR, ENUM, FLAGS, TEXTURE, REGION, NODE_PATH,
    RESOURCE, CURVE, ARRAY_FLOAT, ARRAY_STRING, TILESET, FONT, AUDIO, SCENE, SCRIPT, MULTILINE_STRING,
    SHAPE, TILEMAP_DATA, CALLBACK
}

/**
 * A single inspector-editable property of a node.
 * The editor renders editors automatically from these definitions, which is why every node
 * type declares its data this way instead of with ad-hoc fields.
 */
class PropertyDef(
    val name: String,
    val type: PropertyType,
    val label: String = name,
    val defaultValue: Any? = null,
    val min: Float = -Float.MAX_VALUE,
    val max: Float = Float.MAX_VALUE,
    val step: Float = 0.01f,
    val enumValues: List<String> = emptyList(),
    val category: String = "General",
    val tooltip: String = "",
    val animateable: Boolean = true,
    /** For NODE_PATH/RESOURCE pickers: restricts what the editor offers. */
    val hint: String = "",
    /** Save games skip properties flagged false (runtime-only counters, caches, ...). */
    val persist: Boolean = true,
)

/**
 * Base node type.
 *
 * Lifecycle: `onEnterTree` → `onReady` → (`onProcess`/`onPhysicsProcess`/`onDraw` each frame)
 * → `onExitTree` → `onFree`.
 */
open class Node(name: String = "") {

    var name: String = name
    /** Stable identity for this runtime instance (editor selection, networking, saves). */
    var instanceId: Long = nextInstanceId()

    var parent: Node? = null
        private set
    val children: MutableList<Node> = ArrayList()

    /** The tree this node currently lives in, or null when detached. */
    var tree: SceneTree? = null
        internal set

    var visible: Boolean = true
    var processMode: ProcessMode = ProcessMode.INHERIT
    /** Disables all processing (editor "pause node" / pooling). */
    var processingEnabled: Boolean = true
    var physicsProcessingEnabled: Boolean = true

    /** Groups for fast queries: `"enemies"`, `"pickups"`, `"hud"`. */
    val groups: MutableSet<String> = LinkedHashSet()
    /** Editor-only tags used by the asset/level tools. */
    val tags: MutableSet<String> = LinkedHashSet()

    var isQueuedForDeletion: Boolean = false
        private set
    var isReady: Boolean = false
        private set

    /** Script instance attached to this node, if any (see dev.lumen2d.core.script). */
    var scriptBehavior: ScriptBehavior? = null

    /** Signals */
    val ready = Event()
    val treeEntered = Event()
    val treeExited = Event()
    val treeChanged = Event()
    val childAdded = Signal<Node>()
    val childRemoved = Signal<Node>()
    val propertyChanged = Signal<String>()
    val freed = Event()

    val typeName: String get() = NodeRegistry.typeNameOf(this)

    private companion object {
        private var instanceCounter = 1L
        private fun nextInstanceId(): Long = instanceCounter++
    }

    // ------------------------------------------------------------------- tree edits

    fun addChild(child: Node, index: Int = -1) {
        require(child !== this) { "A node cannot be its own child" }
        require(!child.isAncestorOf(this)) { "Adding '${child.name}' would create a cycle" }
        child.parent?.removeChild(child)
        child.parent = this
        if (index < 0 || index > children.size) children.add(child) else children.add(index, child)
        child.assignTree(tree)
        if (isInsideTree()) {
            child.propagateEnterTree()
            if (tree?.isStarted == true) child.propagateReady()
        }
        childAdded.emit(child)
        treeChanged.emit()
        tree?.notifyNodeAdded(child)
        tree?.notifyHierarchyChanged()
    }

    fun removeChild(child: Node, keepAlive: Boolean = true) {
        if (!children.remove(child)) return
        if (isInsideTree()) child.propagateExitTree()
        child.parent = null
        child.assignTree(null)
        childRemoved.emit(child)
        treeChanged.emit()
        tree?.notifyHierarchyChanged()
    }

    /** Removes the node at the end of the current frame (safe inside process loops). */
    fun queueFree() {
        if (isQueuedForDeletion) return
        isQueuedForDeletion = true
        tree?.queueDeletion(this)
    }

    fun free() {
        isQueuedForDeletion = true
        propagateExit()
        scriptBehavior?.onDestroy()
        scriptBehavior = null
        freed.emit()
    }

    /** Propagates this node's tree reference into the whole subtree (used by add/removeChild). */
    internal fun assignTree(newTree: SceneTree?) {
        tree = newTree
        for (c in children) c.assignTree(newTree)
    }

    fun isInsideTree(): Boolean = tree != null

    fun isAncestorOf(other: Node?): Boolean {
        var n = other?.parent
        while (n != null) { if (n === this) return true; n = n.parent }
        return false
    }

    /** Depth-first children flattened (the node itself excluded). */
    fun descendants(includeSelf: Boolean = false): List<Node> {
        val out = ArrayList<Node>()
        if (includeSelf) out.add(this)
        fun visit(n: Node) { for (c in n.children) { out.add(c); visit(c) } }
        visit(this)
        return out
    }

    fun ancestors(): List<Node> {
        val out = ArrayList<Node>()
        var n = parent
        while (n != null) { out.add(n); n = n.parent }
        return out
    }

    fun moveToFront() { parent?.let { it.children.remove(this); it.children.add(this) } }
    fun moveToBack() { parent?.let { it.children.remove(this); it.children.add(0, this) } }

    // --------------------------------------------------------------- tree traversal

    /** Resolves "Player/Sprite2D", "../Hud", "/root/level/enemies". */
    fun nodePath(path: String): Node? {
        if (path.isEmpty() || path == ".") return this
        val absolute = path.startsWith("/")
        var current: Node? = if (absolute) (tree?.root ?: root()) else this
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        for ((index, part) in parts.withIndex()) {
            // "/root/Level" addresses the tree root itself as the first segment.
            if (absolute && index == 0 && part == current?.name) continue
            current = when (part) {
                "." -> current
                ".." -> current?.parent
                else -> current?.child(part)
            } ?: return null
        }
        return current
    }

    /** Absolute slash-separated path from the tree root ("Level/Enemies/Goblin"). */
    fun nodePath(): String {
        val parts = ArrayList<String>(8)
        var n: Node? = this
        while (n != null && n.parent != null) { parts.add(n.name); n = n.parent }
        parts.reverse()
        return parts.joinToString("/")
    }

    fun root(): Node {
        var n: Node = this
        while (n.parent != null) n = n.parent!!
        return n
    }

    fun child(childName: String): Node? = children.firstOrNull { it.name == childName }

    fun childAt(index: Int): Node? = children.getOrNull(index)

    val childCount: Int get() = children.size
    val indexInParent: Int get() = parent?.children?.indexOf(this) ?: -1

    /** Finds the first descendant of a given engine type. */
    inline fun <reified T : Node> findFirst(): T? {
        for (n in descendants()) if (n is T) return n
        return null
    }

    fun findByTypeName(type: String): Node? = descendants().firstOrNull { it.typeName == type }

    fun findByNameOrNull(nodeName: String): Node? = descendants().firstOrNull { it.name == nodeName }

    // ---------------------------------------------------------------------- groups

    fun addToGroup(group: String) { groups.add(group); tree?.indexGroup(this, group) }
    fun removeFromGroup(group: String) { groups.remove(group); tree?.unindexGroup(this, group) }
    fun isInGroup(group: String): Boolean = groups.contains(group)

    // ------------------------------------------------------------------ lifecycle

    /** Called before the node is added to a ready tree. */
    open fun onEnterTree() {}
    /** Called once the node and all children are inside the tree (before first process). */
    open fun onReady() {}
    /** Per-frame gameplay update. */
    open fun onProcess(delta: Float) {}
    /** Fixed-step update (physics, timers, movement). */
    open fun onPhysicsProcess(delta: Float) {}
    /** Rendering callback for drawable nodes (Node2D subclasses). */
    open fun onDraw(renderer: dev.lumen2d.core.render.Renderer, alpha: Float) {}
    open fun onExitTree() {}
    open fun onFree() {}
    /** Called when the inspector edits a property so nodes can react (e.g. rebuild caches). */
    open fun onPropertyApplied(property: String, value: Any?) {}
    /** Called after the whole tree finished loading a scene (used by tilemaps, tile res). */
    open fun onSceneLoaded() {}

    internal fun propagateEnterTree() {
        treeEntered.emit()
        onEnterTree()
        // Index-based: handlers (tilemap collision, spawners, ...) may add children while running.
        for (i in 0 until children.size) children[i].propagateEnterTree()
    }

    internal fun propagateReady() {
        if (isReady) return
        isReady = true
        ready.emit()
        onReady()
        scriptBehavior?.onReady()
        for (i in 0 until children.size) children[i].propagateReady()
    }

    internal fun propagateExitTree() {
        for (i in children.size - 1 downTo 0) children[i].propagateExitTree()
        scriptBehavior?.onTreeExit()
        onExitTree()
        treeExited.emit()
        isReady = false
    }

    internal fun propagateExit() {
        for (i in children.size - 1 downTo 0) children[i].propagateExit()
        if (tree != null) propagateExitTree()
        onFree()
    }

    internal fun propagateProcess(delta: Float, paused: Boolean) {
        if (!processingEnabled || isQueuedForDeletion) return
        if (isPausedFor(paused)) return
        onProcess(delta)
        scriptBehavior?.process(delta)
        for (i in 0 until children.size) children[i].propagateProcess(delta, paused)
    }

    internal fun propagatePhysicsProcess(delta: Float, paused: Boolean) {
        if (!physicsProcessingEnabled || isQueuedForDeletion) return
        if (isPausedFor(paused)) return
        onPhysicsProcess(delta)
        scriptBehavior?.physicsProcess(delta)
        for (c in children) c.propagatePhysicsProcess(delta, paused)
    }

    internal fun propagateDraw(renderer: dev.lumen2d.core.render.Renderer, alpha: Float) {
        if (!visible || isQueuedForDeletion) return
        onDraw(renderer, alpha)
        scriptBehavior?.draw(renderer, alpha)
        // Draw children in z-index order (falls back to insertion order), like Godot's Node2D.
        if (children.size > 1) {
            val tree = tree
            val ordered = tree?.drawOrderOf(this) ?: children
            for (c in ordered) c.propagateDraw(renderer, alpha)
        } else if (children.size == 1) children[0].propagateDraw(renderer, alpha)
    }

    private fun isPausedFor(treePaused: Boolean): Boolean = when (processMode) {
        ProcessMode.INHERIT -> treePaused
        ProcessMode.PAUSABLE -> treePaused
        ProcessMode.WHEN_PAUSED -> !treePaused
        ProcessMode.ALWAYS -> false
    }

    /** Runs [action] once, at the end of the current frame (deferred call). */
    fun callDeferred(tag: String, action: () -> Unit) {
        val t = tree
        if (t == null) { action(); return }
        t.deferCall(tag, this, action)
    }

    fun setTimer(seconds: Float, repeat: Boolean = false, onTimeout: () -> Unit): NodeTimer? {
        val t = tree ?: return null
        return t.createTimer(seconds, repeat, onTimeout).also { it.attachTo(this) }
    }

    // ------------------------------------------------------------------ properties

    /** Inspector-visible properties of this node type. */
    open fun propertyDefinitions(): List<PropertyDef> = emptyList()

    open fun getProperty(property: String): Any? = when (property) {
        "name" -> name
        "visible" -> visible
        "processMode" -> processMode.name
        "processingEnabled" -> processingEnabled
        "physicsProcessingEnabled" -> physicsProcessingEnabled
        else -> null
    }

    open fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "name" -> { name = value?.toString() ?: name; return true }
            "visible" -> { visible = asBool(value, visible); return true }
            "processMode" -> { processMode = runCatching { ProcessMode.valueOf(value.toString()) }.getOrDefault(processMode); return true }
            "processingEnabled" -> { processingEnabled = asBool(value, processingEnabled); return true }
            "physicsProcessingEnabled" -> { physicsProcessingEnabled = asBool(value, physicsProcessingEnabled); return true }
        }
        return false
    }

    /** Applies a property and notifies listeners (used by the editor's undoable commands). */
    fun applyProperty(property: String, value: Any?): Boolean {
        val ok = setProperty(property, value)
        if (ok) { onPropertyApplied(property, value); propertyChanged.emit(property) }
        return ok
    }

    protected fun asBool(value: Any?, fallback: Boolean): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.equals("true", true)
        else -> fallback
    }

    protected fun asFloat(value: Any?, fallback: Float): Float = when (value) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull() ?: fallback
        is Boolean -> if (value) 1f else 0f
        else -> fallback
    }

    protected fun asInt(value: Any?, fallback: Int): Int = asFloat(value, fallback.toFloat()).toInt()

    protected fun asVec2(value: Any?, fallback: Vec2): Vec2 = when (value) {
        is Vec2 -> value
        is Map<*, *> -> Vec2(asFloat(value["x"], fallback.x), asFloat(value["y"], fallback.y))
        is List<*> -> Vec2(asFloat(value.getOrNull(0), fallback.x), asFloat(value.getOrNull(1), fallback.y))
        is String -> parseVec2String(value, fallback)
        else -> fallback
    }

    private fun parseVec2String(text: String, fallback: Vec2): Vec2 {
        val parts = text.trim('(', ')', ' ').split(',')
        if (parts.size < 2) return fallback
        return Vec2(parts[0].trim().toFloatOrNull() ?: fallback.x, parts[1].trim().toFloatOrNull() ?: fallback.y)
    }

    protected fun asColor(value: Any?, fallback: Color): Color = when (value) {
        is Color -> value
        is Number -> Color.fromArgb32(value.toInt())
        is String -> runCatching { Color.fromHex(value) }.getOrDefault(fallback)
        is Map<*, *> -> Color(
            asFloat(value["r"], fallback.r), asFloat(value["g"], fallback.g),
            asFloat(value["b"], fallback.b), asFloat(value["a"], fallback.a))
        else -> fallback
    }

    // ---------------------------------------------------------------- serialization

    /** Serializes this node (and, if [withChildren], its subtree) to JSON-friendly data. */
    open fun serialize(withChildren: Boolean = true): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["type"] = typeName
        out["name"] = name
        val props = LinkedHashMap<String, Any?>()
        for (def in propertyDefinitions()) {
            val value = getProperty(def.name) ?: continue
            if (valuesEqual(value, def.defaultValue)) continue
            props[def.name] = encodeValue(value, def.type)
        }
        // Non-default base properties are always written.
        if (!visible) props["visible"] = false
        if (processMode != ProcessMode.INHERIT) props["processMode"] = processMode.name
        if (!processingEnabled) props["processingEnabled"] = false
        if (!physicsProcessingEnabled) props["physicsProcessingEnabled"] = false
        if (groups.isNotEmpty()) props["groups"] = groups.toList()
        if (tags.isNotEmpty()) props["tags"] = tags.toList()
        if (props.isNotEmpty()) out["properties"] = props
        if (scriptBehavior != null) out["script"] = scriptBehavior!!.sourcePath
        if (withChildren && children.isNotEmpty()) out["children"] = children.map { it.serialize(true) }
        return out
    }

    /** Applies serialized data produced by [serialize]. */
    open fun deserialize(data: Map<String, Any?>, registry: NodeRegistry = NodeRegistry) {
        (data["name"] as? String)?.let { name = it }
        (data["groups"] as? List<*>)?.forEach { g -> g?.toString()?.let { addToGroup(it) } }
        (data["tags"] as? List<*>)?.forEach { t -> t?.toString()?.let { tags.add(it) } }
        val props = data["properties"] as? Map<*, *>
        if (props != null) {
            for ((key, raw) in props) {
                val propertyName = key.toString()
                val def = propertyDefinitions().firstOrNull { it.name == propertyName }
                setProperty(propertyName, decodeValue(raw, def?.type ?: PropertyType.STRING, def))
            }
        }
        if (data["visible"] == false && props?.containsKey("visible") != true) visible = false
        (data["processMode"] as? String)?.let { mode -> runCatching { processMode = ProcessMode.valueOf(mode) } }
        (data["processingEnabled"] as? Boolean)?.let { processingEnabled = it }
        (data["physicsProcessingEnabled"] as? Boolean)?.let { physicsProcessingEnabled = it }
        (data["children"] as? List<*>)?.forEach { childData ->
            (childData as? Map<*, *>)?.let { map ->
                @Suppress("UNCHECKED_CAST")
                val typed = map as Map<String, Any?>
                val type = typed["type"]?.toString()
                if (type != null) {
                    val child = registry.create(type)
                    if (child != null) {
                        addChild(child)
                        child.deserialize(typed, registry)
                    } else Log.w("Node", "Unknown node type '$type' while loading scene")
                }
            }
        }
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a is Vec2 && b is Vec2) return a.x == b.x && a.y == b.y
        if (a is Color && b is Color) return a.toArgb32() == b.toArgb32()
        if (a is Number && b is Number) return a.toFloat() == b.toFloat()
        return a == b
    }

    protected open fun encodeValue(value: Any?, type: PropertyType): Any? = when (value) {
        is Vec2 -> listOf(value.x, value.y)
        is Color -> value.toHex(includeAlpha = value.a < 1f)
        is Rect -> listOf(value.x, value.y, value.w, value.h)
        is Enum<*> -> value.name
        else -> value
    }

    protected open fun decodeValue(raw: Any?, type: PropertyType, def: PropertyDef?): Any? = when (type) {
        PropertyType.VECTOR2 -> asVec2(raw, def?.defaultValue as? Vec2 ?: Vec2.ZERO)
        PropertyType.COLOR -> asColor(raw, def?.defaultValue as? Color ?: Color.WHITE)
        PropertyType.FLOAT -> asFloat(raw, (def?.defaultValue as? Number)?.toFloat() ?: 0f)
        PropertyType.INT -> asInt(raw, (def?.defaultValue as? Number)?.toInt() ?: 0)
        PropertyType.BOOL -> asBool(raw, def?.defaultValue as? Boolean ?: false)
        PropertyType.ARRAY_FLOAT -> (raw as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() } ?: emptyList<Float>()
        PropertyType.ARRAY_STRING -> (raw as? List<*>)?.map { it.toString() } ?: emptyList<String>()
        // Idempotent: node setters also run values through decodeValue, so a Rect must pass through
        // unchanged instead of collapsing to Rect.ZERO.
        PropertyType.RECT -> when (raw) {
            is Rect -> raw
            is List<*> -> Rect(asFloat(raw.getOrNull(0), 0f), asFloat(raw.getOrNull(1), 0f),
                asFloat(raw.getOrNull(2), 0f), asFloat(raw.getOrNull(3), 0f))
            else -> Rect.ZERO
        }
        else -> raw
    }

    override fun toString(): String = "$typeName('$name')"

    /** Copy of this node without children (used by the editor's duplicate command). */
    open fun duplicateNode(): Node {
        val copy = NodeRegistry.create(typeName) ?: Node(name)
        copy.name = name
        copy.visible = visible
        copy.processMode = processMode
        copy.processingEnabled = processingEnabled
        copy.physicsProcessingEnabled = physicsProcessingEnabled
        copy.groups.addAll(groups)
        copy.tags.addAll(tags)
        copy.deserialize(serialize(withChildren = false), NodeRegistry)
        // Keep the original name (deserialize may have reset it).
        copy.name = name
        return copy
    }
}

/**
 * Movable 2D node: position, rotation, scale, pivot, z-index and tint. Children are
 * transformed by their parent, exactly like Godot's `Node2D`.
 */
open class Node2D(name: String = "") : Node(name) {

    var position: Vec2 = Vec2.ZERO
        set(value) { field = value; markTransformDirty() }
    var rotation: Float = 0f
        set(value) { field = value; markTransformDirty() }
    var scale: Vec2 = Vec2.ONE
        set(value) { field = value; markTransformDirty() }
    /** Rotation/scale pivot in local pixels. */
    var pivot: Vec2 = Vec2.ZERO
        set(value) { field = value; markTransformDirty() }
    var zIndex: Int = 0
        set(value) { field = value; tree?.markDrawOrderDirty() }
    /** Draw order within the same z-index: smaller first. */
    var zAsRelative: Boolean = true
    var modulate: Color = Color.WHITE
    var selfModulate: Color? = null
    var skew: Float = 0f

    private var localTransform: Transform2D = Transform2D.IDENTITY
    private var transformDirty = true
    protected var globalTransform: Transform2D = Transform2D.IDENTITY
    private var globalDirty = true

    private fun markTransformDirty() { transformDirty = true; globalDirty = true; for (c in children) (c as? Node2D)?.invalidateGlobalTransform() }

    internal fun invalidateGlobalTransform() {
        globalDirty = true
        for (c in children) (c as? Node2D)?.invalidateGlobalTransform()
    }

    /** Local transform (parent -> this node). */
    fun localTransform(): Transform2D {
        if (transformDirty) {
            localTransform = Transform2D.trs(position, rotation, scale, pivot, skew)
            transformDirty = false
        }
        return localTransform
    }

    /** Accumulated transform from the scene root to this node. */
    fun globalTransform(): Transform2D {
        if (globalDirty) {
            val parent2d = parent as? Node2D
            globalTransform = if (parent2d == null) localTransform() else parent2d.globalTransform() * localTransform()
            globalDirty = false
        }
        return globalTransform
    }

    val globalPosition: Vec2 get() = globalTransform().translation
    val globalRotation: Float get() = globalTransform().rotation
    val globalScale: Vec2 get() = Vec2(globalTransform().scaleX, globalTransform().scaleY)

    fun setGlobalPosition(p: Vec2) {
        val parentTransform = (parent as? Node2D)?.globalTransform() ?: Transform2D.IDENTITY
        position = parentTransform.inverse().transformPoint(p)
    }

    /** Moves the node by a delta in local space. */
    fun translate(dx: Float, dy: Float) { position = Vec2(position.x + dx, position.y + dy) }

    fun translateLocal(dx: Float, dy: Float) {
        val rotated = Vec2(dx, dy).rotated(rotation)
        position = Vec2(position.x + rotated.x, position.y + rotated.y)
    }

    fun distanceTo(other: Node2D): Float = globalPosition.distanceTo(other.globalPosition)

    /** Local rectangle this node occupies (overridden by drawable nodes for culling). */
    open fun localBounds(): Rect = Rect.ZERO

    /** World-space AABB used for view culling and editor selection. */
    open fun worldBounds(): Rect {
        val local = localBounds()
        if (local.isEmpty) {
            val p = globalPosition
            return Rect(p.x - 4f, p.y - 4f, 8f, 8f)
        }
        val t = globalTransform()
        val corners = floatArrayOf(
            local.left, local.top, local.right, local.top,
            local.right, local.bottom, local.left, local.bottom,
        )
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val p = t.transformPoint(corners[i * 2], corners[i * 2 + 1])
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        return Rect.fromLTRB(minX, minY, maxX, maxY)
    }

    /** Effective tint including parent modulate chain. */
    fun effectiveModulate(): Color {
        var color = selfModulate ?: modulate
        var p = parent as? Node2D
        while (p != null) {
            color *= (p.selfModulate ?: p.modulate)
            p = p.parent as? Node2D
        }
        return color
    }

    override fun onDraw(renderer: dev.lumen2d.core.render.Renderer, alpha: Float) {
        // Drawable subclasses: push our transform, then draw in local space.
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("position", PropertyType.VECTOR2, "Position", Vec2.ZERO, category = "Transform"),
        PropertyDef("rotation", PropertyType.FLOAT, "Rotation (rad)", 0f, min = -100f, max = 100f, step = 0.01f, category = "Transform"),
        PropertyDef("scale", PropertyType.VECTOR2, "Scale", Vec2.ONE, min = -100f, max = 100f, step = 0.05f, category = "Transform"),
        PropertyDef("pivot", PropertyType.VECTOR2, "Pivot", Vec2.ZERO, category = "Transform"),
        PropertyDef("zIndex", PropertyType.INT, "Z index", 0, min = -4096f, max = 4096f, step = 1f, category = "Draw"),
        PropertyDef("modulate", PropertyType.COLOR, "Modulate", Color.WHITE, category = "Draw"),
        PropertyDef("visible", PropertyType.BOOL, "Visible", true, category = "Draw"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "position" -> position
        "rotation" -> rotation
        "scale" -> scale
        "pivot" -> pivot
        "zIndex" -> zIndex
        "modulate" -> modulate
        "skew" -> skew
        "globalPosition" -> globalPosition
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "position" -> { position = asVec2(value, position); return true }
            "rotation" -> { rotation = asFloat(value, rotation); return true }
            "scale" -> { scale = asVec2(value, scale); return true }
            "pivot" -> { pivot = asVec2(value, pivot); return true }
            "zIndex" -> { zIndex = asInt(value, zIndex); return true }
            "modulate" -> { modulate = asColor(value, modulate); return true }
            "skew" -> { skew = asFloat(value, skew); markTransformDirty(); return true }
            "globalPosition" -> { setGlobalPosition(asVec2(value, globalPosition)); return true }
        }
        return super.setProperty(property, value)
    }

    override fun encodeValue(value: Any?, type: PropertyType): Any? = super.encodeValue(value, type)

    override fun duplicateNode(): Node {
        val copy = super.duplicateNode()
        (copy as? Node2D)?.let { c -> c.position = position; c.rotation = rotation; c.scale = scale; c.pivot = pivot }
        return copy
    }
}
