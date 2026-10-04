/** Scene graph, lifecycle, serialisation, signals, tweens and save-state coverage. */
package dev.lumen2d.core.test

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.Node2D
import dev.lumen2d.core.scene.NodeRegistry
import dev.lumen2d.core.scene.PropertyDef
import dev.lumen2d.core.scene.PropertyType
import dev.lumen2d.core.scene.Scene
import dev.lumen2d.core.scene.SceneTree
import dev.lumen2d.core.scene.createTween
import dev.lumen2d.core.scene.tweenProperty
import dev.lumen2d.core.util.Json

/** Test node that records lifecycle calls and owns a couple of animatable properties. */
class Probe(name: String = "Probe") : Node2D(name) {
    val calls = ArrayList<String>()
    var value: Float = 0f
    var ticks: Int = 0

    override fun onEnterTree() { calls.add("enter") }
    override fun onReady() { calls.add("ready") }
    override fun onProcess(delta: Float) { ticks++ }
    override fun onExitTree() { calls.add("exit") }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("value", PropertyType.FLOAT, "Value", 0f, category = "Test"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "value" -> value
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        if (property == "value") { this.value = (value as? Number)?.toFloat() ?: 0f; return true }
        return super.setProperty(property, value)
    }
}

fun sceneTests() {
    section("scene graph")

    test("children, lookup and traversal") {
        val root = Node("root")
        val level = Node("Level")
        val player = Node("Player")
        val sprite = Node("Sprite")
        root.addChild(level)
        level.addChild(player)
        player.addChild(sprite)

        eq(1, root.children.size)
        eq(sprite, level.nodePath("Player/Sprite"))
        eq(player, sprite.nodePath("../."))
        eq(root, sprite.nodePath("/root"))
        eq(4, root.descendants(includeSelf = true).size)
        eq(listOf("Player", "Level", "root"), sprite.ancestors().map { it.name }, "ancestors walk up to the root")
        eq("Level/Player/Sprite", sprite.nodePath())
        truthy(level.child("Player") === player)
        truthy(root.child("Nope") == null)
    }

    test("reparenting moves the node between parents") {
        val a = Node("A")
        val b = Node("B")
        val child = Node("Child")
        a.addChild(child)
        b.addChild(child)
        eq(0, a.children.size)
        eq(1, b.children.size)
        truthy(child.parent === b)
    }

    test("removing a child detaches it") {
        val parent = Node("P")
        val child = Node("C")
        parent.addChild(child)
        parent.removeChild(child, keepAlive = true)
        truthy(child.parent == null)
        eq(0, parent.children.size)
    }

    test("groups index and un-index nodes") {
        val tree = SceneTree()
        val scene = Scene("s", Node("root"))
        val enemy = Node("Enemy")
        scene.root.addChild(enemy)
        tree.setScene(scene)
        enemy.addToGroup("enemies")
        eq(1, tree.nodesInGroup("enemies").size)
        truthy(tree.firstNodeInGroup("enemies") === enemy)
        enemy.removeFromGroup("enemies")
        eq(0, tree.nodesInGroup("enemies").size)
    }

    section("lifecycle")

    test("ready fires once, after the node enters the tree") {
        val tree = SceneTree()
        val probe = Probe("Probe")
        val scene = Scene("s", Node("root"))
        scene.root.addChild(probe)
        tree.setScene(scene)
        eq("enter", probe.calls.first(), "enter fires on setScene")
        tree.process(1f / 60f)
        check(probe.calls.contains("ready"), "ready fired when the node entered the tree")
        eq(1, probe.calls.count { it == "ready" })
        tree.process(1f / 60f)
        eq(1, probe.calls.count { it == "ready" }, "ready must not fire twice")
        check(probe.ticks >= 2, "process ran on both frames")
    }

    test("queueFree removes the node at the end of the frame") {
        val tree = SceneTree()
        val scene = Scene("s", Node("root"))
        val victim = Node("Victim")
        scene.root.addChild(victim)
        tree.setScene(scene)
        tree.process(0.016f)
        victim.queueFree()
        tree.process(0.016f)
        eq(0, scene.root.children.size, "node removed after processing")
    }

    test("timers fire once or repeatedly") {
        val tree = SceneTree()
        tree.setScene(Scene("s", Node("root")))
        var once = 0
        var repeating = 0
        tree.createTimer(0.1f, repeat = false) { once++ }
        tree.createTimer(0.05f, repeat = true) { repeating++ }
        tree.process(0.04f)
        eq(0, once)
        tree.process(0.08f)
        eq(1, once, "one-shot timer fired")
        check(repeating >= 2, "repeating timer fired multiple times, was $repeating")
    }

    test("deferred calls run once and can be cancelled") {
        val tree = SceneTree()
        tree.setScene(Scene("s", Node("root")))
        var ran = 0
        var cancelled = 0
        tree.deferCall("boot", null) { ran++ }
        tree.deferCall("nope", null) { cancelled++ }
        tree.cancelDeferred("nope")
        tree.process(0.016f)
        tree.process(0.016f)
        eq(1, ran, "deferred call ran exactly once")
        eq(0, cancelled, "cancelled call never ran")
    }

    section("serialisation")

    test("scenes round-trip through JSON") {
        NodeRegistry.ensureBuiltinsRegistered()
        registerProbeType()
        val root = Node("Main")
        val level = Node2D("Level")
        level.position = Vec2(12f, -4f)
        val probe = Probe("Probe")
        probe.value = 7f
        level.addChild(probe)
        root.addChild(level)

        val scene = Scene("level_1", root)
        val text = Json.stringify(scene.serialize())
        val restored = Scene.fromJson(Json.parseObject(text), "scenes/level_1.scene.json")

        eq("level_1", restored.name)
        val restoredLevel = restored.root.child("Level")
        truthy(restoredLevel != null, "child node restored")
        truthy(restoredLevel is Node2D)
        near(12f, (restoredLevel as Node2D).position.x, 0.001f)
        near(-4f, restoredLevel.position.y, 0.001f)
        val restoredProbe = restoredLevel.child("Probe") as Probe
        near(7f, restoredProbe.value, 0.001f, "custom node type restored with its properties")
        eq(Probe::class.java, restoredProbe.javaClass)
    }

    test("instantiate deep-copies a scene without sharing nodes") {
        val root = Node("Main")
        val child = Node2D("Child")
        child.position = Vec2(5f, 5f)
        root.addChild(child)
        val scene = Scene("s", root)
        val copy = scene.instantiate()
        val copiedChild = copy.child("Child") as Node2D
        copiedChild.position = Vec2(99f, 99f)
        near(5f, child.position.x, 0.001f, "original untouched by the copy")
        truthy(copiedChild !== child)
    }

    test("node registry exposes editor metadata") {
        NodeRegistry.ensureBuiltinsRegistered()
        check(NodeRegistry.exists("Sprite2D"), "Sprite2D registered")
        check(NodeRegistry.exists("RigidBody2D"), "RigidBody2D registered")
        check(NodeRegistry.exists("Button"), "Button registered")
        val categories = NodeRegistry.byCategory()
        check(categories.isNotEmpty(), "palette categories exist")
        val sprite = NodeRegistry.create("Sprite2D")
        truthy(sprite != null, "registry creates nodes")
        eq("Sprite2D", NodeRegistry.typeNameOf(sprite!!))
        check(NodeRegistry.creatableTypes().size > 20, "plenty of creatable types")
    }

    test("registered node types all instantiate and serialise") {
        NodeRegistry.ensureBuiltinsRegistered()
        for (info in NodeRegistry.creatableTypes()) {
            val node = info.factory()
            val data = node.serialize(true)
            val typeName = data["type"]?.toString() ?: ""
            check(typeName.isNotEmpty(), "${info.name} serialises a type name")
            val clone = NodeRegistry.create(typeName)
            truthy(clone != null, "${info.name} can be recreated from its serialised type")
        }
    }

    section("signals")

    test("signals connect, emit and disconnect") {
        val tree = SceneTree()
        val node = Node("N")
        tree.setScene(Scene("s", Node("root")).also { it.root.addChild(node) })
        var hits = 0
        val handler: (String) -> Unit = { hits++ }
        node.propertyChanged.connect(handler)
        node.applyProperty("name", "N2")
        node.propertyChanged.disconnect(handler)
        node.applyProperty("name", "N3")
        eq(1, hits, "listener only called while connected")
    }

    test("tree signals fire for node add/remove") {
        val tree = SceneTree()
        var added = 0
        var removed = 0
        tree.nodeAdded.connect { added++ }
        tree.nodeRemoved.connect { removed++ }
        val root = Node("root")
        tree.setScene(Scene("s", root))
        val child = Node("Child")
        root.addChild(child)
        tree.process(0.016f)
        check(added >= 1, "nodeAdded fired")
        child.queueFree()
        tree.process(0.016f)
        check(removed >= 1, "nodeRemoved fired")
    }

    section("tweens")

    test("property tweens interpolate and finish") {
        val tree = SceneTree()
        val node = Probe("T")
        tree.setScene(Scene("s", Node("root")).also { it.root.addChild(node) })
        tree.process(1f / 60f)
        var finished = false
        node.createTween().property(node, "value", 10f, 1f, "linear").also {
            it.finished.connect { finished = true }
        }
        node.applyProperty("value", 0f)
        repeat(30) { tree.process(1f / 60f) }
        near(5f, node.value, 0.35f, "half-way through a linear tween")
        repeat(35) { tree.process(1f / 60f) }
        near(10f, node.value, 0.001f, "tween reached its target")
        check(finished, "finished signal fired")
    }

    test("chained and looping tweens run in order") {
        val tree = SceneTree()
        val a = Probe("A")
        val b = Probe("B")
        tree.setScene(Scene("s", Node("root")).also { it.root.addChild(a); it.root.addChild(b) })
        tree.process(1f / 60f)
        var callbackTime = -1f
        val tween = a.createTween()
            .property(a, "value", 1f, 0.2f)
            .callback { callbackTime = a.value }
            .property(b, "value", 5f, 0.2f)
        tween.start()
        repeat(60) { tree.process(1f / 60f) }
        near(1f, callbackTime, 0.05f, "callback ran after the first property step")
        near(5f, b.value, 0.001f, "second step applied to the other node")
    }

    test("tween delays hold the value until the delay elapses") {
        val tree = SceneTree()
        val node = Probe("D")
        tree.setScene(Scene("s", Node("root")).also { it.root.addChild(node) })
        tree.process(1f / 60f)
        val tween = node.createTween().property(node, "value", 4f, 0.2f)
        (tween.steps.first() as dev.lumen2d.core.scene.TweenStep.Property).tweener.setDelay(0.3f)
        repeat(12) { tree.process(1f / 60f) }
        near(0f, node.value, 0.001f, "still inside the delay window")
        repeat(40) { tree.process(1f / 60f) }
        near(4f, node.value, 0.001f, "value applied after the delay")
    }

    test("relative tweens add to the current value") {
        val tree = SceneTree()
        val node = Probe("R")
        tree.setScene(Scene("s", Node("root")).also { it.root.addChild(node) })
        tree.process(1f / 60f)
        node.applyProperty("value", 3f)
        val tween = node.createTween().property(node, "value", 2f, 0.1f)
        (tween.steps.first() as dev.lumen2d.core.scene.TweenStep.Property).tweener.asRelative()
        repeat(12) { tree.process(1f / 60f) }
        near(5f, node.value, 0.001f, "3 + 2")
    }

    test("killing a tween stops it") {
        val tree = SceneTree()
        val node = Probe("K")
        tree.setScene(Scene("s", Node("root")).also { it.root.addChild(node) })
        tree.process(1f / 60f)
        node.tweenProperty("value", 100f, 1f)
        repeat(10) { tree.process(1f / 60f) }
        tree.tweens.killAll()
        val mid = node.value
        repeat(20) { tree.process(1f / 60f) }
        near(mid, node.value, 0.001f, "value frozen after killAll")
    }

    section("save state")

    test("nodes in the save_state group are captured and restored") {
        val tree = SceneTree()
        val root = Node("root")
        val probe = Probe("Probe")
        probe.value = 1f
        root.addChild(probe)
        tree.setScene(Scene("s", root))
        tree.process(1f / 60f)
        probe.addToGroup("save_state")
        probe.value = 42f
        val state = tree.collectSaveState()
        check(state.isNotEmpty(), "node captured, got ${state.keys}")
        check(state.keys.first().endsWith("Probe"), "captured path points at the probe: ${state.keys}")

        probe.value = 0f
        tree.applySaveState(state)
        near(42f, probe.value, 0.001f, "value restored from the save state")
    }

    test("save slots report their metadata") {
        val slot = dev.lumen2d.core.game.SaveSlot.parse(2, """
            {"format":"lumen2d.save","title":"Neon Runner","scene":"scenes/level_3.scene.json",
             "playTime":3725.5,"savedAt":1000,"extra":{"coins":17}}
        """.trimIndent())
        check(slot.exists)
        eq("Neon Runner", slot.title)
        eq("1:02:05", slot.playTimeText)
        eq(17.0, (slot.extra["coins"] as Number).toDouble())
    }

    section("stretch maths")

    test("viewport letterboxes to preserve the aspect ratio") {
        val config = dev.lumen2d.core.game.GameConfig()
        config.designWidth = 480
        config.designHeight = 270
        config.stretchMode = dev.lumen2d.core.game.StretchMode.FIT
        val wide = dev.lumen2d.core.game.StretchMath.viewport(config, 1600, 900)
        near(1600f, wide.w, 0.01f)
        near(900f, wide.h, 0.01f)
        val tall = dev.lumen2d.core.game.StretchMath.viewport(config, 800, 1600)
        near(800f, tall.w, 0.01f)
        near(450f, tall.h, 0.01f)
        near(575f, tall.y, 0.01f, "centred vertically")

        config.stretchMode = dev.lumen2d.core.game.StretchMode.INTEGER
        val integer = dev.lumen2d.core.game.StretchMath.viewport(config, 1000, 600)
        near(2f, integer.w / 480f, 0.001f, "integer scaling uses whole multiples")

        val design = dev.lumen2d.core.game.StretchMath.screenToDesign(config, 1000, 600, Vec2(240f, 135f))
        check(design.x > 0f && design.y > 0f, "screen to design mapping")
    }

    test("game config survives a save/load cycle") {
        val config = dev.lumen2d.core.game.GameConfig()
        config.title = "Neon Runner"
        config.designWidth = 320
        config.designHeight = 180
        config.clearColor = Color(0.1f, 0.2f, 0.3f, 1f)
        config.gravity = Vec2(0f, 1200f)
        config.tags.add("boss")
        val restored = dev.lumen2d.core.game.GameConfig.fromJson(
            Json.parseObject(Json.stringify(config.serialize())))
        eq("Neon Runner", restored.title)
        eq(320, restored.designWidth)
        eq(180, restored.designHeight)
        near(0.1f, restored.clearColor.r, 0.01f, "8-bit colour quantisation is acceptable")
        near(1200f, restored.gravity.y, 0.001f)
        check(restored.tags.contains("boss"), "tags restored")
    }

    test("rect properties survive deserialisation") {
        // Node setters run values through decodeValue a second time, so a Rect must pass through
        // unchanged — this caught the bug where every camera limit collapsed to Rect.ZERO.
        val camera = NodeRegistry.create("Camera2D") as dev.lumen2d.core.scene.CameraNode
        camera.deserialize(
            mapOf(
                "name" to "Camera",
                "properties" to linkedMapOf<String, Any?>(
                    "limitEnabled" to true,
                    "limitRect" to listOf(0.0, 0.0, 960.0, 288.0),
                    "followNodePath" to "Gameplay/Player",
                ),
            ),
        )
        eq(960f, camera.limitRect.w, "camera limit width kept")
        eq(288f, camera.limitRect.h, "camera limit height kept")
        eq(true, camera.limitEnabled)

        val panel = NodeRegistry.create("NinePatchRect") as dev.lumen2d.core.scene.NinePatchRectNode
        panel.setProperty("insets", listOf(2.0, 4.0, 6.0, 8.0))
        eq(6f, panel.insets.w, "nine-patch insets kept")

        // And the whole rect survives a serialize → deserialize round trip.
        val reloaded = NodeRegistry.create("Camera2D") as dev.lumen2d.core.scene.CameraNode
        reloaded.deserialize(camera.serialize(true))
        eq(960f, reloaded.limitRect.w, "camera limit kept through a round trip")
    }
}

/** Registers the [Probe] test node type so scenes can round-trip it through the registry. */
private var probeRegistered = false

fun registerProbeType() {
    if (probeRegistered) return
    probeRegistered = true
    NodeRegistry.ensureBuiltinsRegistered()
    NodeRegistry.register(
        name = "Probe", category = "Test", baseType = "Node2D",
        description = "Test node that records lifecycle calls.", icon = "node",
    ) { Probe() }
}
