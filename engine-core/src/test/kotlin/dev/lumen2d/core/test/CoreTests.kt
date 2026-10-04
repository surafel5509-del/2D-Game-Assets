/** Math, JSON, colour, RNG, clock and utility coverage. */
package dev.lumen2d.core.test

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.ColorUtil
import dev.lumen2d.core.math.Easing
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Rng
import dev.lumen2d.core.math.Transform2D
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.util.GameClock
import dev.lumen2d.core.util.Json
import dev.lumen2d.core.util.Profiler
import dev.lumen2d.core.util.deepCopyJson
import dev.lumen2d.core.util.flt
import dev.lumen2d.core.util.int
import dev.lumen2d.core.util.str

fun coreTests() {
    section("math")

    test("Vec2 basics behave") {
        val a = Vec2(3f, 4f)
        near(5f, a.length, 0.0001f, "3-4-5 triangle")
        near(25f, a.lengthSquared, 0.0001f)
        val n = a.normalized()
        near(1f, n.length, 0.0001f, "normalised length")
        eq(Vec2(4f, 6f), a + Vec2(1f, 2f))
        eq(Vec2(2f, 2f), a - Vec2(1f, 2f))
        eq(Vec2(6f, 8f), a * 2f)
        near(11f, a.dot(Vec2(1f, 2f)), 0.0001f)
        near(2f, a.cross(Vec2(1f, 2f)), 0.0001f, "cross product: 3*2 - 4*1")
        near(0f, Vec2(1f, 0f).distanceTo(Vec2(1f, 0f)), 0.0001f)
        near(1.5708f, Vec2(0f, 1f).angle, 0.001f, "vec angle in radians")
        near(90f, MathUtil.radToDeg(Vec2(0f, 1f).angle), 0.01f)
    }

    test("Rect helpers") {
        val r = Rect(10f, 10f, 20f, 20f)
        near(30f, r.right, 0.001f)
        near(30f, r.bottom, 0.001f)
        eq(Vec2(20f, 20f), r.center)
        check(r.contains(Vec2(15f, 15f)), "point inside rect")
        check(!r.contains(Vec2(5f, 15f)), "point outside rect")
        check(r.overlaps(Rect(25f, 25f, 10f, 10f)), "overlapping rects")
        check(!r.overlaps(Rect(40f, 40f, 5f, 5f)), "distant rects")
        eq(400f, r.area, "20x20")
        val grown = r.grown(5f)
        eq(Rect(5f, 5f, 30f, 30f), grown)
        eq(Vec2(15f, 15f), r.closestPoint(15f, 15f), "a point inside the rect is its own closest point")
        eq(Vec2(30f, 30f), r.closestPoint(40f, 40f), "closest point clamps to the corner")
    }

    test("Color conversion and blending") {
        val c = Color.fromHex("#FF8040")
        near(1f, c.r, 0.01f); near(0.502f, c.g, 0.01f); near(0.251f, c.b, 0.01f)
        eq("#ff8040ff", c.toHex(includeAlpha = true).lowercase())
        eq(Color.fromHex("#FF8040FF"), Color.fromHex(c.toHex()))
        val half = c.withAlpha(0.5f)
        near(0.5f, half.a, 0.001f)
        eq(Color.WHITE, Color.BLACK.lerp(Color.WHITE, 1f))
        eq(Color.BLACK, Color.WHITE.lerp(Color.BLACK, 1f))
        val dark = Color(0.5f, 0.5f, 0.5f).darker(0.5f)
        near(0.25f, dark.r, 0.001f)
        val hsv = c.toHsv()
        val roundTrip = ColorUtil.fromHsv(hsv[0], hsv[1], hsv[2], hsv[3])
        near(c.r, roundTrip.r, 0.01f, "hsv round trip red")
        near(c.g, roundTrip.g, 0.01f, "hsv round trip green")
        near(c.b, roundTrip.b, 0.01f, "hsv round trip blue")
    }

    test("Easing functions start and end correctly") {
        for (name in Easing.names) {
            val f = Easing.byName(name)
            near(0f, f(0f), 0.02f, "$name at t=0")
            near(1f, f(1f), 0.02f, "$name at t=1")
        }
        near(0.5f, Easing.byName("linear")(0.5f), 0.0001f)
        near(1f, Easing.byName("nope")(1f), 0.001f, "unknown easing falls back to linear")
    }

    test("MathUtil utility functions") {
        near(5f, MathUtil.clamp(7f, 0f, 5f), 0.001f)
        near(0f, MathUtil.clamp(-3f, 0f, 5f), 0.001f)
        near(0.5f, MathUtil.clamp01(0.5f), 0.001f)
        near(3f, MathUtil.lerp(1f, 5f, 0.5f), 0.001f)
        near(2f, MathUtil.moveTowards(0f, 3f, 2f), 0.001f)
        near(0f, MathUtil.wrap(4f, 0f, 2f), 0.001f, "wrap into range")
        near(90f, MathUtil.wrap(450f, 0f, 360f), 0.01f)
        near(0.0952f, MathUtil.damp(0f, 1f, 0.1f, 1f), 0.01f, "damp is framerate independent")
        var damped = 0f
        repeat(40) { damped = MathUtil.damp(damped, 1f, 10f, 1f / 60f) }
        near(1f, damped, 0.2f, "damp converges on the target")
        near(0.5f, MathUtil.smoothstep(0f, 1f, 0.5f), 0.001f)
        check(MathUtil.approx(1.0000001f, 1f), "approx")
        near(0.5f, MathUtil.invLerp(0f, 10f, 5f), 0.001f)
        near(0.5f, MathUtil.remap(5f, 0f, 10f, 0f, 1f), 0.001f)
    }

    test("Rng is deterministic for a seed") {
        val a = Rng(12345)
        val b = Rng(12345)
        repeat(50) {
            near(a.nextFloat(), b.nextFloat(), 0f, "same seed, same stream")
        }
        val rng = Rng(7)
        repeat(200) {
            val f = rng.nextFloat()
            check(f >= 0f && f < 1f, "float in range")
        }
        val ranged = Rng(9)
        repeat(100) {
            val i = ranged.nextInt(3, 7)
            check(i in 3..7, "int in 3..7 but was $i")
        }
        val inside = Rng(3).insideCircle(2f)
        check(inside.length <= 2.0001f, "insideCircle stays in radius")
    }

    test("Transform2D composition") {
        val t = Transform2D.trs(Vec2(10f, 0f), 0f, Vec2(2f, 2f))
        val p = t.transformPoint(Vec2(1f, 1f))
        near(12f, p.x, 0.001f)
        near(2f, p.y, 0.001f)
        val inv = t.inverse().transformPoint(p)
        near(1f, inv.x, 0.01f); near(1f, inv.y, 0.01f)
    }

    section("json")

    test("JSON round-trips nested structures") {
        val source = linkedMapOf<String, Any?>(
            "name" to "player", "hp" to 12.5, "alive" to true,
            "items" to listOf("sword", "shield"),
            "stats" to linkedMapOf("str" to 4, "dex" to 9),
            "nothing" to null,
            "unicode" to "héllo ✨",
        )
        val text = Json.stringify(source)
        val parsed = Json.parseObject(text)
        eq("player", parsed.str("name"))
        near(12.5f, parsed.flt("hp"), 0.0001f)
        eq(true, parsed["alive"])
        eq(listOf("sword", "shield"), parsed["items"])
        val statsMap = parsed["stats"] as Map<*, *>
        near(9f, (statsMap["dex"] as Number).toFloat(), 0.001f)
        eq("héllo ✨", parsed.str("unicode"))
        truthy(parsed.containsKey("nothing"), "null values survive a round trip")
    }

    test("JSON tolerates comments and trailing commas") {
        val text = """
            {
              // a comment
              "a": 1, /* block */
              "b": [1, 2, 3,],
            }
        """.trimIndent()
        val parsed = Json.parseObject(text)
        eq(1, parsed.int("a"))
        eq(3, (parsed["b"] as List<*>).size)
    }

    test("JSON rejects malformed input with a useful error") {
        fails("unterminated object") { Json.parseObject("{\"a\": ") }
        fails("unquoted key") { Json.parseObject("{a: 1}") }
    }

    test("deepCopyJson produces an independent tree") {
        val original = Json.parseObject("""{"a":{"b":[1,2]}}""")
        val copy = deepCopyJson(original) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val inner = (copy["a"] as MutableMap<String, Any?>)
        @Suppress("UNCHECKED_CAST")
        val list = inner["b"] as MutableList<Any?>
        list[0] = 99.0
        val originalInner = original["a"] as Map<*, *>
        val originalList = originalInner["b"] as List<*>
        eq(1.0, originalList[0], "copy must not alias the original")
    }

    test("JSON escaping survives round trip") {
        val tricky = "quote \" backslash \\ newline \n tab \t emoji 🎮"
        val text = Json.stringify(mapOf("s" to tricky))
        eq(tricky, Json.parseObject(text).str("s"))
    }

    section("profiler")

    test("Profiler records samples and counters") {
        Profiler.reset()
        Profiler.beginFrame()
        Profiler.measure("process") { Thread.sleep(1) }
        Profiler.count("drawCalls", 42f)
        Profiler.endFrame()
        check(Profiler.sample("process").lastMillis >= 0f, "sample recorded")
        near(42f, Profiler.counter("drawCalls"), 0.001f)
        check(Profiler.sample("process").historyOrdered.size == Profiler.HISTORY, "history allocated")
    }

    test("GameClock clamps large deltas") {
        val clock = GameClock()
        clock.maxDelta = 0.1f
        // No sleep: the first tick measures a tiny real delta, which must be clamped to maxDelta.
        val delta = clock.tick()
        check(delta <= 0.1f, "delta clamped to maxDelta")
        eq(1L, clock.frame)
    }

    section("filesystem")

    test("In-memory filesystem supports the editor's file operations") {
        val fs = dev.lumen2d.core.platform.InMemoryFileSystem()
        fs.writeText("a/b/c.txt", "hello")
        check(fs.exists("a/b/c.txt"), "file exists")
        check(fs.isDirectory("a/b"), "directory exists")
        eq("hello", fs.readText("a/b/c.txt"))
        eq(listOf("b"), fs.list("a"))
        eq(1, fs.walk("a").size, "walk finds one file")
        fs.rename("a/b/c.txt", "a/d.txt")
        check(fs.exists("a/d.txt") && !fs.exists("a/b/c.txt"), "rename moves the file")
        fs.delete("a", recursive = true)
        check(!fs.exists("a/d.txt"), "recursive delete")
    }
}
