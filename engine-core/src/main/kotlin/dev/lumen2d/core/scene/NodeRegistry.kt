/**
 * Lumen2D — node type registry.
 *
 * The editor's "Create node" dialog, scene loading and the script VM's `new()` built-in all
 * resolve node types through here, so adding a node type to the engine automatically makes it
 * available everywhere (including in saved scenes and prefabs).
 */
package dev.lumen2d.core.scene

/** Metadata describing a registered node type. */
class NodeTypeInfo(
    val name: String,
    /** Editor palette category: "Node2D", "Physics", "UI", "Audio", "Effects", "Gameplay"... */
    val category: String,
    val baseType: String,
    val description: String,
    val icon: String,
    val factory: () -> Node,
    /** False for nodes that only make sense at runtime (e.g. debug probes). */
    val creatableInEditor: Boolean = true,
    /** True for nodes the runtime hides from the editor palette entirely. */
    val internal: Boolean = false,
)

object NodeRegistry {

    private val types = LinkedHashMap<String, NodeTypeInfo>()
    private val classToType = HashMap<Class<*>, String>()
    /** Kotlin class simple name -> type name, for resolving scenes saved with older names. */
    private val simpleNameToType = HashMap<String, String>()
    private var builtinsRegistered = false

    fun register(info: NodeTypeInfo) {
        types[info.name] = info
        // First registration wins so aliases (Sprite vs Sprite2D) do not rename the primary type.
        runCatching {
            val cls = info.factory().javaClass
            classToType.putIfAbsent(cls, info.name)
            simpleNameToType.putIfAbsent(cls.simpleName, info.name)
        }
    }

    fun register(
        name: String,
        category: String,
        baseType: String,
        description: String = "",
        icon: String = "node",
        creatableInEditor: Boolean = true,
        internal: Boolean = false,
        factory: () -> Node,
    ) = register(NodeTypeInfo(name, category, baseType, description, icon, factory, creatableInEditor, internal))

    /**
     * Creates a node instance by type name, or null when the type is unknown.
     * Accepts both the editor/palette name (`CharacterBody2D`) and the Kotlin class name
     * (`CharacterBody2DNode`) so scenes saved before the registry was populated still load.
     */
    fun create(typeName: String): Node? {
        ensureBuiltinsRegistered()
        types[typeName]?.let { return it.factory() }
        types[typeName.removeSuffix("Node")]?.let { return it.factory() }
        // Scenes saved before the registry was populated carry Kotlin class names
        // ("CameraNode", "TileMapNode", "LabelControl") — map them onto the registered type.
        simpleNameToType[typeName]?.let { name -> types[name]?.let { return it.factory() } }
        simpleNameToType[typeName + "Node"]?.let { name -> types[name]?.let { return it.factory() } }
        return null
    }

    fun info(typeName: String): NodeTypeInfo? {
        ensureBuiltinsRegistered()
        return types[typeName] ?: types[typeName.removeSuffix("Node")]
    }

    fun exists(typeName: String): Boolean {
        ensureBuiltinsRegistered()
        return types.containsKey(typeName) || types.containsKey(typeName.removeSuffix("Node"))
    }

    fun all(): List<NodeTypeInfo> = types.values.toList()

    fun creatableTypes(): List<NodeTypeInfo> = types.values.filter { it.creatableInEditor && !it.internal }

    /** Types grouped for the editor palette. */
    fun byCategory(): Map<String, List<NodeTypeInfo>> =
        creatableTypes().groupBy { it.category }.toSortedMap()

    fun typeNameOf(node: Node): String {
        ensureBuiltinsRegistered()
        classToType[node.javaClass]?.let { return it }
        // Fall back to walking the class hierarchy and reverse-matching factories.
        var cls: Class<*>? = node.javaClass
        while (cls != null) {
            types.values.firstOrNull { it.factory().javaClass.isAssignableFrom(cls) }?.let { return it.name }
            cls = cls.superclass
        }
        return node.javaClass.simpleName
    }

    fun ensureBuiltinsRegistered() {
        if (builtinsRegistered) return
        builtinsRegistered = true
        BuiltinNodes.registerAll()
    }

    /** Registers a script-defined or plugin-defined node type at runtime. */
    fun registerCustom(name: String, category: String, baseType: String, description: String, factory: () -> Node) {
        register(NodeTypeInfo(name, category, baseType, description, "script", factory))
    }
}

/**
 * Declares every built-in node type together with its palette metadata.
 * Kept in one place so the editor's palette, docs and scripting bindings stay in sync.
 */
object BuiltinNodes {
    fun registerAll() {
        val r = NodeRegistry

        // ---------------------------------------------------------------- core
        r.register("Node", "Core", "Node", "Bare container node with lifecycle callbacks.", "node") { Node() }
        r.register("Node2D", "Core", "Node", "2D transform node: position, rotation, scale.", "node2d") { Node2D() }
        r.register("Timer", "Core", "Node", "Emits a signal after a delay; optionally repeating.", "timer") { dev.lumen2d.core.scene.TimerNode() }
        r.register("CanvasLayer", "Core", "Node", "Draws its children in screen space with an independent order.", "layer") { dev.lumen2d.core.scene.CanvasLayer() }
        r.register("ScriptNode", "Core", "Node2D", "Node driven by a LumenScript file.", "script") { dev.lumen2d.core.scene.ScriptNode() }
        r.register("PrefabInstance", "Core", "Node2D", "Instance of a reusable prefab scene.", "prefab") { dev.lumen2d.core.scene.PrefabInstance() }
        r.register("Marker2D", "Core", "Node2D", "Editor-only position marker (spawn points, waypoints).", "marker") { dev.lumen2d.core.scene.Marker2D() }

        // ------------------------------------------------------------- visuals
        r.register("Sprite2D", "Visual", "Node2D", "Draws a texture region or whole texture.", "sprite") { dev.lumen2d.core.scene.Sprite2D() }
        r.register("Sprite", "Visual", "Node2D", "Alias of Sprite2D (Godot-compatible name).", "sprite") { dev.lumen2d.core.scene.Sprite2D() }
        r.register("AnimatedSprite2D", "Visual", "Node2D", "Plays frame animations from an atlas.", "anim_sprite") { dev.lumen2d.core.scene.AnimatedSprite2D() }
        r.register("TileMapLayer", "Visual", "Node2D", "Paints tiles from a tile set with optional collision.", "tilemap") { dev.lumen2d.core.tiles.TileMapNode() }
        r.register("Camera2D", "Visual", "Node2D", "Viewport camera with limits, smoothing and screen shake.", "camera") { dev.lumen2d.core.scene.CameraNode() }
        r.register("Line2D", "Visual", "Node2D", "Polyline with width, colour and rounded caps.", "line") { dev.lumen2d.core.scene.LineNode() }
        r.register("Polygon2D", "Visual", "Node2D", "Filled convex polygon.", "polygon") { dev.lumen2d.core.scene.PolygonNode() }
        r.register("NinePatchRect", "Visual", "Node2D", "Scalable nine-patch panel.", "ninepatch") { dev.lumen2d.core.scene.NinePatchRectNode() }
        r.register("Label2D", "Visual", "Node2D", "Bitmap-font text in world space.", "label") { dev.lumen2d.core.scene.LabelNode2D() }
        r.register("CPUParticles2D", "Effects", "Node2D", "Particle emitter (sparks, dust, explosions, rain).", "particles") { dev.lumen2d.core.scene.ParticlesNode() }
        r.register("Light2D", "Effects", "Node2D", "Additive radial light for torches and glow.", "light") { dev.lumen2d.core.scene.LightNode() }
        r.register("ParallaxLayer", "Visual", "Node2D", "Scrolls children slower than the camera.", "parallax") { dev.lumen2d.core.scene.ParallaxLayerNode() }
        r.register("BackgroundLayer", "Visual", "Node2D", "Solid or gradient full-screen background.", "background") { dev.lumen2d.core.scene.BackgroundLayerNode() }
        r.register("TrailRenderer", "Effects", "Node2D", "Draws a fading trail behind moving objects.", "trail") { dev.lumen2d.core.scene.TrailNode() }

        // ------------------------------------------------------------- physics
        r.register("StaticBody2D", "Physics", "Node2D", "Immovable collider (ground, walls).", "body_static") { dev.lumen2d.core.scene.StaticBody2DNode() }
        r.register("RigidBody2D", "Physics", "Node2D", "Physics-driven body with gravity and bounce.", "body_rigid") { dev.lumen2d.core.scene.RigidBody2DNode() }
        r.register("CharacterBody2D", "Physics", "Node2D", "Kinematic body moved by script (players, enemies).", "body_character") { dev.lumen2d.core.scene.CharacterBody2DNode() }
        r.register("Area2D", "Physics", "Node2D", "Trigger volume emitting enter/exit events.", "area") { dev.lumen2d.core.scene.Area2DNode() }
        r.register("CollisionShape2D", "Physics", "Node2D", "Collision shape attached to a body or area.", "collision") { dev.lumen2d.core.scene.CollisionShape2DNode() }

        // ------------------------------------------------------------- gameplay
        r.register("HealthNode", "Gameplay", "Node", "Hit points with damage/heal/death signals.", "health") { dev.lumen2d.core.scene.HealthNode() }
        r.register("SpawnerNode", "Gameplay", "Node2D", "Spawns prefabs on a timer or on demand.", "spawner") { dev.lumen2d.core.scene.SpawnerNode() }
        r.register("StateMachineNode", "Gameplay", "Node", "Finite state machine for AI and animation.", "fsm") { dev.lumen2d.core.scene.StateMachineNode() }
        r.register("AnimationPlayer", "Gameplay", "Node", "Plays property/frame/call animations.", "anim_player") { dev.lumen2d.core.anim.AnimationPlayerNode() }
        r.register("Inventory", "Gameplay", "Node", "Item slots with add/remove/use signals.", "inventory") { dev.lumen2d.core.scene.InventoryNode() }
        r.register("ScoreTracker", "Gameplay", "Node", "Score/combo tracker with best-score saving.", "score") { dev.lumen2d.core.scene.ScoreNode() }

        // ------------------------------------------------------------- audio
        r.register("AudioStreamPlayer", "Audio", "Node", "Non-positional sound and music player.", "audio") { dev.lumen2d.core.scene.AudioPlayerNode() }
        r.register("AudioStreamPlayer2D", "Audio", "Node2D", "Positional player: volume and pan follow X.", "audio2d") { dev.lumen2d.core.scene.AudioPlayer2DNode() }

        // ------------------------------------------------------------------ UI
        r.register("Control", "UI", "Node2D", "Base UI node with anchors and layout.", "ui") { dev.lumen2d.core.scene.ControlNode() }
        r.register("Panel", "UI", "Control", "Coloured or nine-patch panel.", "ui_panel") { dev.lumen2d.core.scene.PanelNode() }
        r.register("Label", "UI", "Control", "Text label with alignment and wrapping.", "ui_label") { dev.lumen2d.core.scene.LabelControl() }
        r.register("Button", "UI", "Control", "Touch/mouse button with pressed/hover states.", "ui_button") { dev.lumen2d.core.scene.ButtonControl() }
        r.register("TextureRect", "UI", "Control", "Draws a texture, optionally stretched or tiled.", "ui_image") { dev.lumen2d.core.scene.TextureRectControl() }
        r.register("ProgressBar", "UI", "Control", "Value bar (health, XP, loading).", "ui_progress") { dev.lumen2d.core.scene.ProgressBarControl() }
        r.register("Slider", "UI", "Control", "Draggable value slider.", "ui_slider") { dev.lumen2d.core.scene.SliderControl() }
        r.register("VBoxContainer", "UI", "Control", "Stacks children vertically.", "ui_vbox") { dev.lumen2d.core.scene.VBoxContainer() }
        r.register("HBoxContainer", "UI", "Control", "Lays children out horizontally.", "ui_hbox") { dev.lumen2d.core.scene.HBoxContainer() }
        r.register("GridContainer", "UI", "Control", "Grid layout with fixed columns.", "ui_grid") { dev.lumen2d.core.scene.GridContainer() }
        r.register("ScrollContainer", "UI", "Control", "Scrollable viewport for its child.", "ui_scroll") { dev.lumen2d.core.scene.ScrollContainer() }
        r.register("VirtualJoystick", "UI", "Control", "On-screen analogue stick for mobile.", "ui_joystick") { dev.lumen2d.core.scene.JoystickControl() }
        r.register("TouchButton", "UI", "Control", "Large on-screen action button bound to an input action.", "ui_touch") { dev.lumen2d.core.scene.TouchButtonControl() }
        r.register("HudBar", "UI", "Control", "Ready-made hearts/icon bar used by sample games.", "ui_hud") { dev.lumen2d.core.scene.HudBarControl() }
    }
}
