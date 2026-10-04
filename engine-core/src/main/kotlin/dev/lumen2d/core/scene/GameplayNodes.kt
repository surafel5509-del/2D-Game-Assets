/**
 * Lumen2D — gameplay nodes.
 *
 * Building blocks the editor palette exposes for common game logic: timers, screen-space
 * layers, script nodes, prefab instances, health, spawners, state machines, inventories and
 * score/combo tracking. Sample games are built almost entirely from these plus scripts.
 */
package dev.lumen2d.core.scene

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.render.Renderer
import dev.lumen2d.core.util.Event
import dev.lumen2d.core.util.Signal

/** Timer node: fires `timeout` after a delay, optionally repeating. */
open class TimerNode(name: String = "Timer") : Node(name) {
    var waitTime: Float = 1f
    var repeat: Boolean = false
    var autostart: Boolean = false
    var processWhilePaused: Boolean = false

    val timeout = Event()
    val timer = NodeTimer(waitTime, repeat) { timeout.emit() }

    val isRunning: Boolean get() = timer.isActive
    val timeLeft: Float get() = timer.timeLeft

    fun start(seconds: Float = waitTime) {
        timer.duration = seconds
        timer.repeat = repeat
        timer.processWhilePaused = processWhilePaused
        timer.attachTo(this)
        timer.start(seconds)
    }

    fun stop() = timer.stop()
    fun restart() = start(waitTime)

    override fun onReady() { if (autostart) start() }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("waitTime", PropertyType.FLOAT, "Wait time", 1f, min = 0.01f, max = 600f, step = 0.05f, category = "Timer"),
        PropertyDef("repeat", PropertyType.BOOL, "Repeat", false, category = "Timer"),
        PropertyDef("autostart", PropertyType.BOOL, "Autostart", false, category = "Timer"),
        PropertyDef("processWhilePaused", PropertyType.BOOL, "Run while paused", false, category = "Timer"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "waitTime" -> waitTime
        "repeat" -> repeat
        "autostart" -> autostart
        "processWhilePaused" -> processWhilePaused
        "isRunning" -> isRunning
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "waitTime" -> { waitTime = asFloat(value, 1f); return true }
            "repeat" -> { repeat = asBool(value, false); return true }
            "autostart" -> { autostart = asBool(value, false); return true }
            "processWhilePaused" -> { processWhilePaused = asBool(value, false); return true }
        }
        return super.setProperty(property, value)
    }
}

/**
 * Draws children in screen space with its own order — HUDs, pause overlays, floating text.
 * Layer 0 renders with the world, higher layers render on top regardless of world position.
 */
open class CanvasLayer(name: String = "CanvasLayer") : Node(name) {
    var layer: Int = 1
    var followCamera: Boolean = false
    var opacity: Float = 1f
    var offset: Vec2 = Vec2.ZERO

    // Children are drawn by the normal traversal. UI widgets draw themselves in screen space,
    // so a CanvasLayer only controls ordering (keep it as the last child of the scene root)
    // and optional opacity, which is applied through the children's modulate chain.

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("layer", PropertyType.INT, "Layer", 1, min = -16f, max = 64f, step = 1f, category = "Canvas"),
        PropertyDef("offset", PropertyType.VECTOR2, "Offset", Vec2.ZERO, category = "Canvas"),
        PropertyDef("followCamera", PropertyType.BOOL, "Follow camera", false, category = "Canvas"),
        PropertyDef("opacity", PropertyType.FLOAT, "Opacity", 1f, min = 0f, max = 1f, step = 0.05f, category = "Canvas"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "layer" -> layer
        "offset" -> offset
        "followCamera" -> followCamera
        "opacity" -> opacity
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "layer" -> { layer = asInt(value, 1); return true }
            "offset" -> { offset = asVec2(value, Vec2.ZERO); return true }
            "followCamera" -> { followCamera = asBool(value, false); return true }
            "opacity" -> { opacity = asFloat(value, 1f); return true }
        }
        return super.setProperty(property, value)
    }
}

/**
 * A node whose behaviour comes from a LumenScript file (`scriptPath`).
 * The runtime attaches a [ScriptBehavior] instance when the node enters the tree.
 */
open class ScriptNode(name: String = "") : Node2D(name) {
    var scriptPath: String = ""
    /** Values written by the script's `export` declarations (shown in the inspector). */
    val exportedValues: MutableMap<String, Any?> = LinkedHashMap()
    private var attachedPath: String = ""

    override fun onReady() { attachScript() }

    private fun attachScript() {
        if (scriptPath.isEmpty() || scriptPath == attachedPath) return
        val tree = tree ?: return
        val runtime = tree.scriptRuntime
        if (runtime == null) {
            dev.lumen2d.core.util.Log.w("ScriptNode", "No script runtime available; '$scriptPath' not attached")
            return
        }
        scriptBehavior?.onDestroy()
        val behavior = runtime.instantiate(scriptPath, this, exportedValues)
        scriptBehavior = behavior
        attachedPath = scriptPath
        behavior?.onReady()
    }

    fun reload() { attachedPath = ""; scriptBehavior?.onDestroy(); scriptBehavior = null; attachScript() }

    /** Calls a function defined in the script. */
    fun call(functionName: String, vararg args: Any?): Any? =
        scriptBehavior?.callMethod(functionName, args.toList())

    override fun onExitTree() { scriptBehavior?.onTreeExit() }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("scriptPath", PropertyType.SCRIPT, "Script", "", category = "Script",
            hint = "asset:script"),
    ) + super.propertyDefinitions() +
        exportedValues.keys.map { key ->
            PropertyDef(key, guessPropertyType(exportedValues[key]), "Script: $key", exportedValues[key], category = "Exported")
        }

    override fun getProperty(property: String): Any? {
        exportedValues[property]?.let { return it }
        return when (property) {
            "scriptPath" -> scriptPath
            else -> super.getProperty(property)
        }
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        if (exportedValues.containsKey(property)) {
            exportedValues[property] = value
            scriptBehavior?.setExported(property, value)
            return true
        }
        if (property == "scriptPath") {
            scriptPath = value?.toString() ?: ""
            (scriptBehavior as? ScriptBehavior)?.let { }
            return true
        }
        return super.setProperty(property, value)
    }

    private fun guessPropertyType(value: Any?): PropertyType = when (value) {
        is Float, is Double -> PropertyType.FLOAT
        is Int, is Long -> PropertyType.INT
        is Boolean -> PropertyType.BOOL
        is Vec2 -> PropertyType.VECTOR2
        is Color -> PropertyType.COLOR
        is List<*> -> PropertyType.ARRAY_FLOAT
        else -> PropertyType.STRING
    }
}

/**
 * Instance of a prefab scene, keeping a link back to the source file so the editor can show
 * "modified from prefab" state and revert individual properties.
 */
open class PrefabInstance(name: String = "") : Node2D(name) {
    var prefabPath: String = ""
    var loaded: Boolean = false
    /** Properties that override the prefab (editor keeps these to support revert). */
    val overrides: MutableMap<String, Any?> = LinkedHashMap()

    fun loadPrefab(force: Boolean = false) {
        if (loaded && !force) return
        val res = tree?.resources ?: return
        val scene = res.assets?.loadScene(prefabPath) ?: return
        children.toList().forEach { removeChild(it) }
        for (child in scene.root.children.toList()) {
            scene.root.removeChild(child, keepAlive = true)
            addChild(child)
        }
        (scene.root as? Node2D)?.let { root2d ->
            position = root2d.position
            scale = root2d.scale
            rotation = root2d.rotation
        }
        loaded = true
        for ((key, value) in overrides) applyProperty(key, value)
    }

    override fun onReady() {
        loadPrefab()
        onSceneLoaded()
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("prefabPath", PropertyType.SCENE, "Prefab", "", category = "Prefab", hint = "asset:prefab"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "prefabPath" -> prefabPath
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        if (property == "prefabPath") {
            prefabPath = value?.toString() ?: ""
            loaded = false
            if (isReady) loadPrefab(force = true)
            return true
        }
        return super.setProperty(property, value)
    }
}

/** Hit points with damage/heal/death signals — used by players and enemies alike. */
open class HealthNode(name: String = "Health") : Node(name) {
    var maxHealth: Float = 100f
    var currentHealth: Float = 100f
    var invulnerableSeconds: Float = 0f
    var destroyOnDeath: Boolean = false
    /** When true the node resets to full health when the scene loads. */
    var resetOnReady: Boolean = false
    var armour: Float = 0f

    private var invulnerableTimer = 0f

    val damageTaken = Signal<Float>()
    val healed = Signal<Float>()
    val died = Event()
    val healthChanged = Signal<Float>()
    val invulnerabilityEnded = Event()

    val isAlive: Boolean get() = currentHealth > 0f
    val ratio: Float get() = if (maxHealth <= 0f) 0f else (currentHealth / maxHealth).coerceIn(0f, 1f)
    val isInvulnerable: Boolean get() = invulnerableTimer > 0f

    override fun onReady() { if (resetOnReady) reset() }

    fun reset() {
        currentHealth = maxHealth
        invulnerableTimer = 0f
        healthChanged.emit(currentHealth)
    }

    /** Applies damage; returns the damage actually applied (0 while invulnerable). */
    fun damage(amount: Float, sourceGroup: String = "", knockback: Vec2 = Vec2.ZERO): Float {
        if (!isAlive || isInvulnerable) return 0f
        val applied = (amount - armour).coerceAtLeast(0f)
        currentHealth = (currentHealth - applied).coerceAtLeast(0f)
        damageTaken.emit(applied)
        healthChanged.emit(currentHealth)
        if (invulnerableSeconds > 0f) invulnerableTimer = invulnerableSeconds
        if (currentHealth <= 0f) {
            died.emit()
            if (destroyOnDeath) queueFree()
        }
        return applied
    }

    fun heal(amount: Float): Float {
        if (!isAlive) return 0f
        val before = currentHealth
        currentHealth = (currentHealth + amount).coerceAtMost(maxHealth)
        val healedAmount = currentHealth - before
        if (healedAmount > 0f) { healed.emit(healedAmount); healthChanged.emit(currentHealth) }
        return healedAmount
    }

    fun kill() { currentHealth = 0f; died.emit(); healthChanged.emit(0f); if (destroyOnDeath) queueFree() }

    fun setMax(value: Float, refill: Boolean = true) {
        maxHealth = value
        if (refill || currentHealth > maxHealth) currentHealth = maxHealth
        healthChanged.emit(currentHealth)
    }

    override fun onPhysicsProcess(delta: Float) {
        if (invulnerableTimer > 0f) {
            invulnerableTimer -= delta
            if (invulnerableTimer <= 0f) { invulnerableTimer = 0f; invulnerabilityEnded.emit() }
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("maxHealth", PropertyType.FLOAT, "Max health", 100f, min = 1f, max = 100000f, step = 1f, category = "Health"),
        PropertyDef("currentHealth", PropertyType.FLOAT, "Health", 100f, min = 0f, max = 100000f, step = 1f, category = "Health"),
        PropertyDef("armour", PropertyType.FLOAT, "Armour", 0f, min = 0f, max = 10000f, step = 1f, category = "Health"),
        PropertyDef("invulnerableSeconds", PropertyType.FLOAT, "Invulnerability", 0f, min = 0f, max = 10f, step = 0.05f, category = "Health"),
        PropertyDef("destroyOnDeath", PropertyType.BOOL, "Destroy on death", false, category = "Health"),
        PropertyDef("resetOnReady", PropertyType.BOOL, "Reset on ready", false, category = "Health"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "maxHealth" -> maxHealth
        "currentHealth" -> currentHealth
        "armour" -> armour
        "invulnerableSeconds" -> invulnerableSeconds
        "destroyOnDeath" -> destroyOnDeath
        "resetOnReady" -> resetOnReady
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "maxHealth" -> { setMax(asFloat(value, 100f)); return true }
            "currentHealth" -> { currentHealth = asFloat(value, 100f).coerceIn(0f, maxHealth); healthChanged.emit(currentHealth); return true }
            "armour" -> { armour = asFloat(value, 0f); return true }
            "invulnerableSeconds" -> { invulnerableSeconds = asFloat(value, 0f); return true }
            "destroyOnDeath" -> { destroyOnDeath = asBool(value, false); return true }
            "resetOnReady" -> { resetOnReady = asBool(value, true); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Spawns prefab instances on a timer and/or on demand (waves, powerups, enemies). */
open class SpawnerNode(name: String = "Spawner") : Node2D(name) {
    var prefabPath: String = ""
    var interval: Float = 2f
    var maxAlive: Int = 12
    var spawnOnReady: Boolean = false
    var autostart: Boolean = false
    var spawnRadius: Float = 0f
    var randomizeRotation: Boolean = false
    var group: String = "spawned"

    private var timer = 0f
    private val alive = ArrayList<Node>()

    val spawned = Signal<Node>()

    override fun onReady() { if (spawnOnReady) spawn() }

    fun start() { autostart = true }
    fun stop() { autostart = false; timer = 0f }

    fun spawn(): Node? {
        val res = tree?.resources ?: return null
        val scene = res.assets?.loadScene(prefabPath) ?: return null
        alive.removeAll { it.isQueuedForDeletion || it.parent == null }
        if (alive.size >= maxAlive) return null
        val node = scene.instantiate()
        if (node is Node2D) {
            val angle = if (spawnRadius > 0f) Math.random().toFloat() * dev.lumen2d.core.math.TAU else 0f
            node.position = position + Vec2(kotlin.math.cos(angle) * spawnRadius, kotlin.math.sin(angle) * spawnRadius)
            if (randomizeRotation) node.rotation = Math.random().toFloat() * dev.lumen2d.core.math.TAU
        }
        if (group.isNotEmpty()) node.addToGroup(group)
        parent?.addChild(node)
        alive.add(node)
        spawned.emit(node)
        return node
    }

    fun spawnMany(count: Int): List<Node> = (0 until count).mapNotNull { spawn() }

    fun aliveCount(): Int = alive.count { !it.isQueuedForDeletion && it.parent != null }

    override fun onProcess(delta: Float) {
        if (!autostart) return
        timer -= delta
        if (timer <= 0f) { timer = interval; spawn() }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("prefabPath", PropertyType.SCENE, "Prefab", "", category = "Spawner", hint = "asset:prefab"),
        PropertyDef("interval", PropertyType.FLOAT, "Interval", 2f, min = 0.05f, max = 60f, step = 0.05f, category = "Spawner"),
        PropertyDef("maxAlive", PropertyType.INT, "Max alive", 12, min = 1f, max = 512f, step = 1f, category = "Spawner"),
        PropertyDef("autostart", PropertyType.BOOL, "Autostart", false, category = "Spawner"),
        PropertyDef("spawnOnReady", PropertyType.BOOL, "Spawn on ready", false, category = "Spawner"),
        PropertyDef("spawnRadius", PropertyType.FLOAT, "Radius", 0f, min = 0f, max = 512f, category = "Spawner"),
        PropertyDef("group", PropertyType.STRING, "Group", "spawned", category = "Spawner"),
        PropertyDef("alive", PropertyType.INT, "Alive", 0, category = "Debug", animateable = false),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "prefabPath" -> prefabPath
        "interval" -> interval
        "maxAlive" -> maxAlive
        "autostart" -> autostart
        "spawnOnReady" -> spawnOnReady
        "spawnRadius" -> spawnRadius
        "group" -> group
        "alive" -> aliveCount()
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "prefabPath" -> { prefabPath = value?.toString() ?: ""; return true }
            "interval" -> { interval = asFloat(value, 2f); return true }
            "maxAlive" -> { maxAlive = asInt(value, 12); return true }
            "autostart" -> { autostart = asBool(value, false); return true }
            "spawnOnReady" -> { spawnOnReady = asBool(value, false); return true }
            "spawnRadius" -> { spawnRadius = asFloat(value, 0f); return true }
            "group" -> { group = value?.toString() ?: "spawned"; return true }
        }
        return super.setProperty(property, value)
    }
}

/**
 * Finite state machine: states are child nodes; the machine calls `enter`/`exit`/`update`
 * functions on the active state's script and emits `state_changed`.
 */
open class StateMachineNode(name: String = "StateMachine") : Node(name) {
    var initialState: String = ""
    var currentState: String = ""
        private set
    var autoSwitchByGroup: Boolean = true

    val stateChanged = Signal<String>()
    private var elapsedInState: Float = 0f

    val stateTime: Float get() = elapsedInState

    override fun onReady() {
        if (autoSwitchByGroup) {
            for (child in children) child.addToGroup("state")
        }
        if (initialState.isNotEmpty()) transitionTo(initialState)
        else currentState = children.firstOrNull()?.name ?: ""
    }

    fun transitionTo(stateName: String, force: Boolean = false) {
        if (stateName == currentState && !force) return
        val previous = currentStateNode()
        previous?.let { node ->
            node.scriptBehavior?.callMethod("exit", emptyList())
            node.visible = false
        }
        currentState = stateName
        elapsedInState = 0f
        val next = currentStateNode()
        next?.let { node ->
            node.visible = true
            node.scriptBehavior?.callMethod("enter", emptyList())
        }
        stateChanged.emit(stateName)
    }

    fun currentStateNode(): Node? = children.firstOrNull { it.name == currentState }

    override fun onProcess(delta: Float) {
        elapsedInState += delta
        currentStateNode()?.scriptBehavior?.callMethod("update", listOf(delta))
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("initialState", PropertyType.STRING, "Initial state", "", category = "State machine"),
        PropertyDef("currentState", PropertyType.STRING, "State", "", category = "State machine"),
        PropertyDef("autoSwitchByGroup", PropertyType.BOOL, "Tag children as states", true, category = "State machine"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "initialState" -> initialState
        "currentState" -> currentState
        "autoSwitchByGroup" -> autoSwitchByGroup
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "initialState" -> { initialState = value?.toString() ?: ""; return true }
            "currentState" -> { transitionTo(value?.toString() ?: "", force = true); return true }
            "autoSwitchByGroup" -> { autoSwitchByGroup = asBool(value, true); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Item slot for [InventoryNode]. */
data class ItemSlot(val itemId: String, var count: Int)

/** Simple inventory with capacity, stacking and signals — RPG-style sample games use it. */
open class InventoryNode(name: String = "Inventory") : Node(name) {
    var capacity: Int = 16
    var maxStack: Int = 99
    val slots: MutableList<ItemSlot?> = ArrayList()

    val itemAdded = Signal<ItemSlot>()
    val itemRemoved = Signal<ItemSlot>()
    val changed = Event()
    val full = Event()

    init { repeat(capacity) { slots.add(null) } }

    fun addItem(itemId: String, count: Int = 1): Int {
        var remaining = count
        // Fill existing stacks first.
        for (i in slots.indices) {
            val slot = slots[i] ?: continue
            if (slot.itemId == itemId && slot.count < maxStack) {
                val space = maxStack - slot.count
                val moved = minOf(space, remaining)
                slot.count += moved
                remaining -= moved
                itemAdded.emit(slot)
                if (remaining == 0) { changed.emit(); return count }
            }
        }
        // Then use empty slots.
        for (i in slots.indices) {
            if (slots[i] == null && remaining > 0) {
                val moved = minOf(maxStack, remaining)
                val slot = ItemSlot(itemId, moved)
                slots[i] = slot
                remaining -= moved
                itemAdded.emit(slot)
            }
        }
        changed.emit()
        if (remaining > 0) full.emit()
        return count - remaining
    }

    fun removeItem(itemId: String, count: Int = 1): Boolean {
        if (countOf(itemId) < count) return false
        var remaining = count
        for (i in slots.indices.reversed()) {
            val slot = slots[i] ?: continue
            if (slot.itemId != itemId) continue
            val removed = minOf(slot.count, remaining)
            slot.count -= removed
            remaining -= removed
            itemRemoved.emit(slot)
            if (slot.count <= 0) slots[i] = null
            if (remaining == 0) break
        }
        changed.emit()
        return true
    }

    fun countOf(itemId: String): Int = slots.filterNotNull().filter { it.itemId == itemId }.sumOf { it.count }
    fun has(itemId: String, count: Int = 1): Boolean = countOf(itemId) >= count
    fun usedSlots(): Int = slots.count { it != null }
    val isFull: Boolean get() = usedSlots() >= capacity

    fun clearAll() { for (i in slots.indices) slots[i] = null; changed.emit() }

    /** Serialization for save games. */
    fun toJson(): List<Map<String, Any?>> = slots.mapNotNull { slot ->
        slot?.let { mapOf<String, Any?>("item" to it.itemId, "count" to it.count) }
    }

    fun fromJson(data: List<Map<String, Any?>>) {
        clearAll()
        for (entry in data) {
            val id = entry["item"]?.toString() ?: continue
            addItem(id, (entry["count"] as? Number)?.toInt() ?: 1)
        }
    }
}

/** Score + combo tracker with best-score persistence through the save system. */
open class ScoreNode(name: String = "Score") : Node(name) {
    var score: Int = 0
    var bestScore: Int = 0
    var comboTimeout: Float = 2f
    var comboMultiplierStep: Float = 0.25f

    var combo: Int = 0
        private set
    private var comboTimer = 0f

    val scoreChanged = Signal<Int>()
    val comboChanged = Signal<Int>()
    val newBest = Event()

    val multiplier: Float get() = 1f + combo * comboMultiplierStep

    fun add(points: Int): Int {
        val gained = (points * multiplier).toInt()
        score += gained
        combo++
        comboTimer = comboTimeout
        scoreChanged.emit(score)
        comboChanged.emit(combo)
        if (score > bestScore) { bestScore = score; newBest.emit() }
        return gained
    }

    fun reset() { score = 0; combo = 0; comboTimer = 0f; scoreChanged.emit(0); comboChanged.emit(0) }

    override fun onProcess(delta: Float) {
        if (comboTimer > 0f) {
            comboTimer -= delta
            if (comboTimer <= 0f) { comboTimer = 0f; combo = 0; comboChanged.emit(0) }
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("score", PropertyType.INT, "Score", 0, min = 0f, max = 9999999f, step = 1f, category = "Score"),
        PropertyDef("bestScore", PropertyType.INT, "Best", 0, min = 0f, max = 9999999f, step = 1f, category = "Score"),
        PropertyDef("comboTimeout", PropertyType.FLOAT, "Combo timeout", 2f, min = 0.1f, max = 30f, step = 0.1f, category = "Score"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "score" -> score
        "bestScore" -> bestScore
        "comboTimeout" -> comboTimeout
        "combo" -> combo
        "multiplier" -> multiplier
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "score" -> { score = asInt(value, 0); scoreChanged.emit(score); return true }
            "bestScore" -> { bestScore = asInt(value, 0); return true }
            "comboTimeout" -> { comboTimeout = asFloat(value, 2f); return true }
        }
        return super.setProperty(property, value)
    }
}
