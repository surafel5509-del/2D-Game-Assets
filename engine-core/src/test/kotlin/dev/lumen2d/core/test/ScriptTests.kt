/**
 * LumenScript tests: the language itself plus the engine bridge (nodes, groups, signals).
 *
 * These tests run the real interpreter — no JVM compilation steps — so they double as living
 * documentation: every snippet below is valid script code.
 */
package dev.lumen2d.core.test

import dev.lumen2d.core.game.SAVE_STATE_GROUP
import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.scene.HealthNode
import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.Node2D
import dev.lumen2d.core.scene.Scene
import dev.lumen2d.core.scene.SceneTree
import dev.lumen2d.core.scene.ScriptNode
import dev.lumen2d.core.script.LumenScriptRuntime
import dev.lumen2d.core.script.ScriptError
import dev.lumen2d.core.script.ScriptStruct
import dev.lumen2d.core.script.compileScript
import dev.lumen2d.core.script.fromScriptValue
import dev.lumen2d.core.script.toScriptValue

fun scriptTests() {
    section("lumen script language")

    test("arithmetic, strings and comparisons") {
        val script = compileScript("""
            func compute() {
                var a = 2 + 3 * 4
                var b = (2 + 3) * 4
                var c = 10 % 3
                var text = "score: " + 42
                var flag = a == 14 and b == 20 and c == 1
                return flag and text == "score: 42"
            }
        """)
        eq(true, script.call("compute"), "operator precedence, string concat and boolean logic")
    }

    test("control flow, loops and collections") {
        val script = compileScript("""
            func sum_to(n) {
                var total = 0
                for i in range(1, n + 1) {
                    total += i
                }
                return total
            }

            func evens(limit) {
                var out = []
                var i = 0
                while i < limit {
                    i += 1
                    if i % 2 != 0 { continue }
                    if i > 8 { break }
                    out.push(i)
                }
                return out
            }

            func dictionary() {
                var stats = {"hp": 10, "mp": 4}
                stats["hp"] += 5
                return stats.hp + stats.size()
            }
        """)
        eq(15.0, script.call("sum_to", 5.0), "for + range")
        eq(listOf(2.0, 4.0, 6.0, 8.0), script.call("evens", 20.0), "while/continue/break")
        eq(17.0, script.call("dictionary"), "dictionaries, compound assignment and size()")
    }

    test("functions, closures and recursion") {
        val script = compileScript("""
            func factorial(n) {
                if n <= 1 { return 1 }
                return n * factorial(n - 1)
            }

            func make_counter(start) {
                var value = start
                return func() {
                    value += 1
                    return value
                }
            }

            func use_counter() {
                var counter = make_counter(10)
                counter()
                counter()
                return counter()
            }
        """)
        eq(120.0, script.call("factorial", 5.0), "recursion")
        eq(13.0, script.call("use_counter"), "closures capture their scope")
    }

    test("vectors, colours and built-in maths") {
        val script = compileScript("""
            func vectors() {
                var a = vec2(3, 4)
                var b = vec2(1, 1)
                var scaled = a * 2
                var moved = a + b
                return [a.length(), a.normalized().x, scaled.y, moved.x, a.distance_to(b), a.dot(b)]
            }

            func mathstuff() {
                return [clamp(15, 0, 10), lerp(0, 10, 0.25), abs(-3), floor(2.9), max(2, 7), sqrt(16)]
            }

            func colours() {
                var c = color("#FF8040")
                var half = c.lerp(color(0, 0, 0), 0.5)
                return [c.r, half.r, c.a]
            }
        """)
        val vectors = script.call("vectors") as List<*>
        near(5.0, vectors[0] as Double, 1e-6, "vec2 language builtin")
        near(0.6, vectors[1] as Double, 1e-6, "normalized()")
        near(8.0, vectors[2] as Double, 1e-6, "scalar multiply")
        near(4.0, vectors[3] as Double, 1e-6, "vector add")
        near(3.6055, vectors[4] as Double, 1e-3, "distance_to()")
        near(7.0, vectors[5] as Double, 1e-6, "dot()")

        eq(listOf(10.0, 2.5, 3.0, 2.0, 7.0, 4.0), script.call("mathstuff"), "math builtins")

        val colours = script.call("colours") as List<*>
        near(1.0, colours[0] as Double, 1e-6, "hex colour parsed")
        near(0.5, colours[1] as Double, 1e-6, "colour lerp")
        near(1.0, colours[2] as Double, 1e-6, "alpha preserved")
    }

    test("equality, truthiness and iteration over dictionaries") {
        val script = compileScript("""
            func run() {
                var same = vec2(1, 2) == vec2(1, 2)
                var listy = [1, 2] == [1, 2]
                var keys = []
                var scores = {"a": 1, "b": 2}
                for key in scores {
                    keys.push(key)
                }
                var empty_is_false = not ([] and {})
                var text_len = len("hello")
                return [same, listy, keys, empty_is_false, text_len]
            }
        """)
        val result = script.call("run") as List<*>
        eq(true, result[0], "struct equality")
        eq(true, result[1], "list equality")
        eq(listOf("a", "b"), result[2], "iterating a dictionary yields keys")
        eq(true, result[3], "empty collections are falsy")
        eq(5.0, result[4], "len() on strings")
    }

    test("syntax and runtime errors carry a line number") {
        val syntax = runCatching { compileScript("func broken( {\n  return 1\n}\n", "bad.lumen") }.exceptionOrNull()
        check(syntax is ScriptError, "syntax error raised, got $syntax")
        check((syntax as ScriptError).line > 0, "syntax error knows its line (${syntax.line})")

        val script = compileScript("""
            func divide(a, b) {
                return a / b
            }

            func missing() {
                return not_defined + 1
            }
        """)
        val division = runCatching { script.call("divide", 1.0, 0.0) }.exceptionOrNull()
        check(division is ScriptError && division.message!!.contains("zero"), "division by zero, got $division")
        val unknown = runCatching { script.call("missing") }.exceptionOrNull()
        check(unknown is ScriptError && unknown.message!!.contains("not_defined"), "unknown name, got $unknown")
        check(script.call("nope") == null, "missing function returns null")
    }

    section("script engine bridge")

    test("scripts read and write node properties") {
        val node = Node2D("Hero")
        val runtime = LumenScriptRuntime({ """
            export var speed = 120.0
            var coins = 0

            func ready() {
                position = vec2(32, 48)
                speed = 200
                coins = 3
                add_to_group("player")
            }

            func process(delta) {
                position.x += speed * delta
                position.y -= 10
                rotation = 0.5
            }
        """ })
        val instance = runtime.instantiate("hero.lumen", node, emptyMap()) as dev.lumen2d.core.script.ScriptInstance
        node.scriptBehavior = instance
        instance.onReady()

        eq(Vec2(32f, 48f), node.position, "script assigned the node position")
        eq(200.0, instance.scriptVar("speed"), "assignment updates exported variable")
        eq(3.0, instance.scriptVar("coins"), "plain variables work too")
        check(node.isInGroup("player"), "add_to_group()")

        instance.process(0.5f)
        eq(Vec2(132f, 38f), node.position, "member assignment writes back through the vec2")
        near(0.5f, node.rotation, 1e-6f, "scalar property")
        check(runtime.errors.isEmpty(), "no script errors: ${runtime.errors}")
    }

    test("scripts reach other nodes and use engine helpers") {
        val tree = SceneTree()
        val root = Node("root")
        val player = Node2D("Player")
        val hud = Node2D("Hud")
        root.addChild(player)
        root.addChild(hud)
        tree.setScene(Scene("main", root))

        val runtime = LumenScriptRuntime({ """
            func ready() {
                var target = get_node("../Hud")
                print("hud is " + target.name)
                target.set("visible", false)
                set_timer(0.1, func() {
                    print("timer")
                    mark()
                })
            }

            func mark() {
                self.set("modulate", color(1, 0, 0))
            }
        """ })
        val instance = runtime.instantiate("actor.lumen", player, emptyMap()) as dev.lumen2d.core.script.ScriptInstance
        player.scriptBehavior = instance
        instance.onReady()
        check(!hud.visible, "script hid the HUD through a node path lookup")

        // Timers run inside the tree's process step.
        tree.process(0.05f)
        tree.process(0.2f)
        eq(Color(1f, 0f, 0f), player.modulate, "timer callback mutated the node")
        check(runtime.errors.isEmpty(), "no script errors: ${runtime.errors}")
    }

    test("scripts connect to engine signals") {
        val tree = SceneTree()
        val root = Node("root")
        val holder = ScriptNode("Player")
        val health = HealthNode("Health")
        health.maxHealth = 5f
        health.currentHealth = 5f
        holder.addChild(health)
        root.addChild(holder)
        tree.setScene(Scene("main", root))

        val runtime = LumenScriptRuntime({ """
            var deaths = 0

            func ready() {
                connect_signal(self.get_node("Health"), "died", func() {
                    deaths += 1
                    print("died")
                })
            }
        """ })
        val instance = runtime.instantiate("listener.lumen", holder, emptyMap()) as dev.lumen2d.core.script.ScriptInstance
        holder.scriptBehavior = instance
        instance.onReady()
        health.damage(99f)
        eq(1.0, instance.scriptVar("deaths"), "signal callback ran once")
        check(!health.isAlive, "health node died")
    }

    test("save games capture script variables") {
        val tree = SceneTree()
        val root = Node("root")
        val node = Node2D("Player")
        root.addChild(node)
        tree.setScene(Scene("main", root))

        val runtime = LumenScriptRuntime({ """
            export var health = 100.0
            export var label = "hero"

            func ready() {
                add_to_group("save_state")
            }
        """ })
        val instance = runtime.instantiate("save.lumen", node, emptyMap()) as dev.lumen2d.core.script.ScriptInstance
        node.scriptBehavior = instance
        instance.onReady()

        instance.setScriptVar("health", 42.0)
        val state = tree.collectSaveState()
        check(state.isNotEmpty(), "script node captured: ${state.keys}")
        val captured = state.values.first() as Map<*, *>
        eq(42.0, captured["script.health"], "exported script value saved")

        instance.setScriptVar("health", 1.0)
        tree.applySaveState(state)
        eq(42.0, instance.scriptVar("health"), "exported script value restored")
        check(node.isInGroup(SAVE_STATE_GROUP), "node opted into save games from the script")
    }

    test("conversion between engine and script values round-trips") {
        near(3.0, (toScriptValue(Vec2(3f, 4f)) as ScriptStruct)["x"] as Double, 1e-6)
        eq(Vec2(3f, 4f), fromScriptValue(toScriptValue(Vec2(3f, 4f))), "vec2 round trip")
        eq(Color(1f, 0.5f, 0f), fromScriptValue(toScriptValue(Color(1f, 0.5f, 0f))), "colour round trip")
        eq(2.5, toScriptValue(2.5f), "floats become script numbers")
        eq(7.0, toScriptValue(7), "ints become script numbers")
        eq(7, fromScriptValue(7.0), "whole numbers convert back to int")
    }

    test("draw hook receives a renderer wrapper") {
        val node = Node2D("Overlay")
        val runtime = LumenScriptRuntime({ """
            var draws = 0

            func draw(renderer, alpha) {
                draws += 1
                renderer.screen_rect(rect(0, 0, 8, 8), color(1, 0, 0))
            }
        """ })
        val instance = runtime.instantiate("overlay.lumen", node, emptyMap()) as dev.lumen2d.core.script.ScriptInstance
        instance.draw(dev.lumen2d.core.render.SoftwareRenderer(16, 16), 1f)
        eq(1.0, instance.scriptVar("draws"), "draw() ran")
        check(runtime.errors.isEmpty(), "no script errors: ${runtime.errors}")
    }
}
