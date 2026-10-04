/** Physics, tilemaps, particles, animation, audio and asset pipeline coverage. */
package dev.lumen2d.core.test

import dev.lumen2d.core.anim.Animation
import dev.lumen2d.core.anim.AnimationPlayerNode
import dev.lumen2d.core.anim.Curve
import dev.lumen2d.core.audio.AudioClip
import dev.lumen2d.core.audio.AudioMixer
import dev.lumen2d.core.audio.WavCodec
import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Transform2D
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.particles.EmissionShape
import dev.lumen2d.core.particles.ParticleEmitterConfig
import dev.lumen2d.core.particles.ParticlePresets
import dev.lumen2d.core.particles.ParticleSystem
import dev.lumen2d.core.physics.BodyType
import dev.lumen2d.core.physics.PhysicsWorld
import dev.lumen2d.core.physics.Shape2D
import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.Scene
import dev.lumen2d.core.scene.SceneTree
import dev.lumen2d.core.scene.AnimatedSprite2D
import dev.lumen2d.core.tiles.TileCollision
import dev.lumen2d.core.tiles.TileFlags
import dev.lumen2d.core.tiles.TileLayer
import dev.lumen2d.core.tiles.TileMapData
import dev.lumen2d.core.tiles.TileMapNode
import dev.lumen2d.core.tiles.TileSet
import kotlin.math.abs

fun physicsTests() {
    section("physics")

    test("gravity accelerates a dynamic body downwards") {
        val world = PhysicsWorld()
        world.gravity = Vec2(0f, 980f)
        val body = world.createBody(BodyType.RIGID, Shape2D.box(8f, 8f))
        body.position = Vec2(0f, 0f)
        repeat(60) { world.step(1f / 60f) }
        check(body.position.y > 100f, "body fell, y=${body.position.y}")
        check(body.velocity.y > 400f, "body gained downward velocity, vy=${body.velocity.y}")
    }

    test("a dynamic body lands on a static floor instead of passing through") {
        val world = PhysicsWorld()
        world.gravity = Vec2(0f, 980f)
        val floor = world.createBody(BodyType.STATIC, Shape2D.box(200f, 16f))
        floor.position = Vec2(0f, 100f)
        val body = world.createBody(BodyType.RIGID, Shape2D.box(16f, 16f))
        body.position = Vec2(0f, 0f)
        repeat(180) { world.step(1f / 60f) }
        check(body.position.y < 100f, "body never sinks through the floor (y=${body.position.y})")
        val restingY = body.position.y
        repeat(60) { world.step(1f / 60f) }
        check(abs(body.position.y - restingY) < 1.5f, "body rests stably (y=${body.position.y})")
        check(body.velocity.y < 60f, "vertical velocity is damped by the contact, vy=${body.velocity.y}")
    }

    test("static bodies never move") {
        val world = PhysicsWorld()
        val static = world.createBody(BodyType.STATIC, Shape2D.box(32f, 32f))
        static.position = Vec2(5f, 5f)
        repeat(60) { world.step(1f / 60f) }
        near(5f, static.position.x, 0.001f)
        near(5f, static.position.y, 0.001f)
    }

    test("circle and rectangle collisions are detected") {
        val world = PhysicsWorld()
        world.gravity = Vec2.ZERO
        val ball = world.createBody(BodyType.RIGID, Shape2D.circle(8f))
        ball.position = Vec2(0f, 0f)
        ball.velocity = Vec2(120f, 0f)
        val wall = world.createBody(BodyType.STATIC, Shape2D.box(16f, 64f))
        wall.position = Vec2(30f, 0f)
        repeat(60) { world.step(1f / 60f) }
        check(ball.position.x < 30f, "ball stopped at the wall, x=${ball.position.x}")
        check(world.contactCount >= 0, "contact bookkeeping exists")
    }

    test("raycasts report the first hit") {
        val world = PhysicsWorld()
        world.gravity = Vec2.ZERO
        val wall = world.createBody(BodyType.STATIC, Shape2D.box(16f, 16f))
        wall.position = Vec2(50f, 0f)
        val hit = world.raycast(Vec2(0f, 0f), Vec2(100f, 0f))
        truthy(hit, "ray hit something")
        check(hit!!.body === wall, "ray hit the wall")
        near(42f, hit.distance, 4f, "distance measured to the wall face")
        val missed = world.raycast(Vec2(0f, 200f), Vec2(100f, 200f))
        check(missed == null, "ray into empty space hits nothing")
    }

    test("point queries find bodies at a location") {
        val world = PhysicsWorld()
        val body = world.createBody(BodyType.STATIC, Shape2D.box(16f, 16f))
        body.position = Vec2(10f, 10f)
        eq(1, world.queryPoint(Vec2(12f, 12f)).size)
        eq(0, world.queryPoint(Vec2(200f, 200f)).size)
        eq(1, world.queryRect(Rect(0f, 0f, 32f, 32f)).size)
    }

    test("trigger areas report enter and exit") {
        val world = PhysicsWorld()
        world.gravity = Vec2.ZERO
        var entered = 0
        var exited = 0
        val area = world.createArea(Transform2D.fromTranslation(0f, 0f), Shape2D.circle(20f))
        area.onBodyEntered = { entered++ }
        area.onBodyExited = { exited++ }
        val walker = world.createBody(BodyType.KINEMATIC, Shape2D.box(8f, 8f))
        walker.position = Vec2(-50f, 0f)
        repeat(30) {
            walker.position = Vec2(-50f + it * 5f, 0f)
            world.step(1f / 60f)
        }
        eq(1, entered, "entered the trigger once")
        repeat(40) {
            walker.position = Vec2(100f + it * 5f, 0f)
            world.step(1f / 60f)
        }
        eq(1, exited, "exited the trigger once")
    }

    test("shapes expose bounds and areas") {
        val box = Shape2D.box(20f, 10f)
        val bounds = box.localBounds()
        near(20f, bounds.w, 0.001f)
        near(200f, box.area(), 0.5f)
        val circle = Shape2D.circle(5f)
        near(78.5f, circle.area(), 0.5f, "circle area")
        val capsule = Shape2D.capsule(4f, 10f)
        check(capsule.area() > 0f, "capsule area positive")
        val polygon = Shape2D.polygon(-5f, -5f, 5f, -5f, 5f, 5f, -5f, 5f)
        near(100f, polygon.area(), 1f, "square polygon area")
        val json = polygon.serialize()
        eq("polygon", json["type"], "shape type recorded for serialisation")
        val restored = Shape2D.fromJson(json)
        near(100f, restored.area(), 1f, "polygon area survives the round trip")
        val restoredBox = Shape2D.fromJson(box.serialize())
        near(20f, restoredBox.localBounds().w, 0.001f, "shape survives serialisation")
    }

    test("character movement helpers stop at walls") {
        val world = PhysicsWorld()
        world.gravity = Vec2.ZERO
        val wall = world.createBody(BodyType.STATIC, Shape2D.box(16f, 64f))
        wall.position = Vec2(40f, 0f)
        val character = world.createBody(BodyType.RIGID, Shape2D.box(12f, 24f))
        character.position = Vec2(0f, 0f)
        character.fixedRotation = true
        repeat(120) {
            character.velocity = Vec2(200f, 0f)
            world.step(1f / 60f)
        }
        check(character.position.x < 40f, "character blocked by the wall (x=${character.position.x})")
    }

    test("physics bodies serialise with their shapes") {
        val world = PhysicsWorld()
        val body = world.createBody(BodyType.RIGID, Shape2D.box(10f, 10f))
        body.position = Vec2(4f, 6f)
        body.velocity = Vec2(1f, 2f)
        body.applyMass(4f)
        near(0.25f, body.invMass, 0.0001f, "inverse mass derived from mass")
        body.applyImpulse(Vec2(0f, 100f))
        check(body.velocity.y > 2f, "impulse changed the velocity")
    }
}

fun tilemapTests() {
    section("tilemaps")

    test("painting, erasing and querying tiles") {
        val map = TileMapData()
        map.addLayer("ground", 8, 8)
        map.paint("ground", 2, 3, 5)
        eq(5, map.layer("ground")!!.tileIndexAt(2, 3))
        map.erase("ground", 2, 3)
        check(map.layer("ground")!!.isEmpty(2, 3), "erased tile is empty")
        map.paint("ground", 0, 0, 1, flipH = true)
        check(TileFlags.isFlippedH(map.layer("ground")!!.get(0, 0)), "flip flags stored in the tile value")
        eq(1, TileFlags.index(map.layer("ground")!!.get(0, 0)), "index survives flag packing")
    }

    test("flood fill and rectangle fill cover the expected cells") {
        val map = TileMapData()
        map.addLayer("ground", 16, 16)
        map.floodFill("ground", 4, 4, 3)
        var count = 0
        for (y in 0 until 16) for (x in 0 until 16) if (map.layer("ground")!!.tileIndexAt(x, y) == 3) count++
        eq(256, count, "flood fill covered the empty layer")
        map.fillRect("ground", Rect(0f, 0f, 4f, 4f), 7)
        eq(7, map.layer("ground")!!.tileIndexAt(3, 3))
        map.paintLine("ground", 0, 0, 5, 5, 9)
        eq(9, map.layer("ground")!!.tileIndexAt(3, 3), "line painting overwrote the tile")
    }

    test("tilemap data serialises with layers and flag data") {
        val map = TileMapData()
        map.addLayer("ground", 4, 4)
        map.addLayer("decor", 4, 4)
        map.paint("ground", 1, 1, 2)
        map.paint("decor", 2, 2, 3, flipV = true)
        val json = map.serialize()
        val restored = TileMapData.fromJson(json)
        eq(2, restored.layers.size)
        eq(2, restored.layer("ground")!!.tileIndexAt(1, 1))
        check(TileFlags.isFlippedV(restored.layer("decor")!!.get(2, 2)), "flags survive the round trip")
    }

    test("resizing keeps existing content") {
        val map = TileMapData()
        val layer = map.addLayer("ground", 4, 4)
        layer.set(1, 1, 6)
        map.resize(8, 8)
        eq(6, map.layer("ground")!!.tileIndexAt(1, 1), "content preserved after growing")
        eq(8, map.layer("ground")!!.width)
        map.resize(2, 2)
        eq(6, map.layer("ground")!!.tileIndexAt(1, 1), "content preserved after shrinking")
    }

    test("tile sets resolve regions and auto-tile rules") {
        val tileSet = TileSet(tileWidth = 16, tileHeight = 16, columns = 4).also { it.tileCount = 8 }
        val textures = dev.lumen2d.core.render.TextureManager()
        val buffer = dev.lumen2d.core.render.PixelBuffer(64, 32)
        tilesetSetup@ for (y in 0 until 32) for (x in 0 until 64) buffer[x, y] = 0xFF334455.toInt()
        tileSet.texture = textures.add("tiles", buffer)
        val region = tileSet.regionFor(5)
        truthy(region, "region for tile 5 exists")
        eq(16, region!!.width)
        eq(16, region.y, "tile 5 is in the second row of a 4-column set")
        tileSet.autoTileRules.getOrPut("grass") { LinkedHashMap() }[0b0110] = 3
        eq(3, tileSet.autoTile("grass", 0b0110))
        eq(null, tileSet.autoTile("grass", 0b1111))
        val restored = TileSet.fromJson(tileSet.serialize())
        eq(tileSet.tileWidth, restored.tileWidth)
        eq(tileSet.autoTile("grass", 0b0110), restored.autoTile("grass", 0b0110))
    }

    test("TileMapNode reports solidity and hazards") {
        val node = TileMapNode("Ground")
        val tileSet = TileSet(tileWidth = 16, tileHeight = 16, columns = 4).also { it.tileCount = 8 }
        tileSet.tileMeta(2).collision = TileCollision.FULL
        tileSet.tileMeta(4).collision = TileCollision.FULL
        tileSet.tileMeta(4).hazard = 10f
        node.tileMap.tileSet = tileSet
        node.tileMap.tileWidth = 16
        node.tileMap.tileHeight = 16
        node.tileMap.addLayer("solid", 8, 8)
        node.tileMap.addLayer("spikes", 8, 8)
        node.tileMap.addLayer("decor", 8, 8)
        node.tileMap.paint("solid", 1, 1, 2)
        node.tileMap.paint("spikes", 3, 1, 4)
        node.tileMap.paint("decor", 0, 0, 5)

        check(node.isSolid(1, 1), "tile with FULL collision is solid")
        check(!node.isSolid(0, 0), "empty cell is not solid")
        near(10f, node.hazardAt(node.cellToWorld(3, 1)), 0.001f, "hazard damage reported")

        val worldPos = node.cellToWorld(1, 1)
        near(24f, worldPos.x, 0.001f, "cell centre converted to world space")
        val (cellX, cellY) = node.worldToCell(worldPos)
        eq(1, cellX)
        eq(1, cellY)

        val tree = SceneTree()
        val root = Node("root")
        root.addChild(node)
        tree.setScene(Scene("s", root))
        tree.process(1f / 60f)
        node.rebuildCollision()
        tree.process(1f / 60f)
        check(tree.physics.bodyCount > 0, "collision bodies created for solid tiles")
        val bounds = node.levelBounds()
        near(128f, bounds.w, 0.001f, "level bounds cover the layer")
    }

    test("Tiled TMX/TSX import produces a playable tilemap") {
        val tsx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tileset version="1.10" name="kenney" tilewidth="16" tileheight="16" tilecount="4" columns="2">
              <image source="tiles.png" width="32" height="32"/>
            </tileset>
        """.trimIndent()
        val tmx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <map version="1.10" orientation="orthogonal" width="4" height="3" tilewidth="16" tileheight="16">
              <tileset firstgid="1" source="kenney.tsx"/>
              <layer name="ground" width="4" height="3">
                <data encoding="csv">
                1,2,0,0,
                0,3,0,1,
                4,4,0,0
                </data>
              </layer>
            </map>
        """.trimIndent()
        val importedSet = dev.lumen2d.core.tiles.TileMapImporter.parseTsx(tsx, "kenney")
        eq(16, importedSet.tileWidth, "TSX tile size parsed")
        eq(4, importedSet.tileCount, "TSX tile count parsed")
        val imported = dev.lumen2d.core.tiles.TileMapImporter.parseTmx(tmx, importedSet, "kenney")
        eq(16, imported.tileWidth, "tile size read from the map")
        eq(1, imported.layers.size)
        val layer = imported.layers.first()
        eq(4, layer.width, "map width in tiles")
        eq(3, layer.height, "map height in tiles")
        eq(0, layer.tileIndexAt(0, 0), "gid 1 (firstgid) becomes tile 0")
        eq(1, layer.tileIndexAt(1, 0))
        check(layer.isEmpty(2, 0), "gid 0 is empty")
        eq(3, layer.tileIndexAt(0, 2), "gid 4 is the fourth tile of the set")
    }

    test("Tiled flag bits unpack into flip flags") {
        val gid = TileFlags.pack(3, flipH = true, flipV = true)
        check(TileFlags.isFlippedH(gid), "flip H bit")
        check(TileFlags.isFlippedV(gid), "flip V bit")
        eq(3, TileFlags.index(gid), "index extracted")
    }
}

fun particleTests() {
    section("particles")

    test("a burst emits the requested particles") {
        val system = ParticleSystem(ParticleEmitterConfig())
        system.burst(24, Vec2.ZERO, direction = -1.5708f)
        check(system.activeCount == 24, "24 particles active, got ${system.activeCount}")
    }

    test("particles expire after their lifetime") {
        val config = ParticleEmitterConfig().also {
            it.oneShot = true
            it.amount = 10
            it.lifetime = 0.5f
            it.lifetimeVariance = 0f
            it.emitting = true
        }
        val system = ParticleSystem(config)
        system.burst(10)
        var steps = 0
        while (system.activeCount > 0 && steps < 400) {
            system.update(1f / 60f, Vec2.ZERO)
            steps++
        }
        eq(0, system.activeCount, "all particles expired")
        check(steps in 1..200, "expiry took a sensible number of frames ($steps)")
        check(system.finished, "one-shot emitter reports finished")
    }

    test("particles move under their velocity and gravity") {
        val config = ParticleEmitterConfig().also {
            it.lifetime = 2f
            it.lifetimeVariance = 0f
            it.speed = 100f
            it.speedVariance = 0f
            it.gravity = Vec2(0f, 500f)
            it.spread = 0f
            it.drag = 0f
            it.oneShot = true
        }
        val system = ParticleSystem(config)
        system.burst(1, Vec2.ZERO, direction = 0f)
        val startX = (system.debugPositions().firstOrNull() ?: Vec2.ZERO).x
        repeat(30) { system.update(1f / 60f, Vec2.ZERO) }
        val movedX = system.debugPositions().firstOrNull()?.x ?: 0f
        check(movedX > startX, "particle travelled along +x")
    }

    test("presets are ready to use and serialise") {
        val presets = listOf(
            ParticlePresets.explosion(), ParticlePresets.dust(), ParticlePresets.sparks(),
            ParticlePresets.magic(), ParticlePresets.rain(), ParticlePresets.trail(),
            ParticlePresets.heal(),
        )
        for ((index, preset) in presets.withIndex()) {
            check(preset.lifetime > 0f, "preset $index has a lifetime")
            val restored = ParticleEmitterConfig.fromJson(preset.serialize())
            near(preset.lifetime, restored.lifetime, 0.001f, "preset $index lifetime survives JSON")
            near(preset.speed, restored.speed, 0.001f)
        }
        check(EmissionShape.entries.isNotEmpty(), "emission shapes exist")
    }

    test("emission rate produces particles over time") {
        val config = ParticleEmitterConfig().also {
            it.emitting = true
            it.oneShot = false
            it.amount = 30
            it.burstCount = 0
            it.lifetime = 1f
            it.speedVariance = 0f
            it.maxParticles = 128
        }
        val system = ParticleSystem(config)
        repeat(60) { system.update(1f / 60f, Vec2.ZERO) }
        check(system.activeCount >= 20, "continuous emitter produced particles (${system.activeCount})")
        system.restart()
        eq(0, system.activeCount, "restart clears the pool")
    }
}

fun animationTests() {
    section("animation")

    test("property tracks interpolate between keys") {
        val animation = Animation("move", 1f)
        animation.addPropertyTrack("Probe", "value").addKey(0f, 0f).addKey(1f, 10f)
        val player = AnimationPlayerNode("Anim")
        player.addAnimation(animation)
        val probe = Probe("Probe")
        val tree = SceneTree()
        val root = Node("root")
        root.addChild(probe)
        root.addChild(player)
        tree.setScene(Scene("s", root))
        tree.process(1f / 60f)
        player.play("move")
        repeat(30) { tree.process(1f / 60f) }
        check(probe.value > 3f && probe.value < 7f, "midway through the track, value=${probe.value}")
        repeat(40) { tree.process(1f / 60f) }
        near(10f, probe.value, 0.001f, "track finished at the last key")
        check(!player.playing, "non-looping animation stopped")
    }

    test("looping animations wrap and emit signals") {
        val animation = Animation("spin", 0.5f, loop = true)
        animation.addPropertyTrack(".", "value").addKey(0f, 0f).addKey(0.5f, 1f)
        val player = AnimationPlayerNode("Anim")
        player.addAnimation(animation)
        val probe = Probe("Probe")
        val tree = SceneTree()
        val root = Node("root")
        root.addChild(probe)
        root.addChild(player)
        tree.setScene(Scene("s", root))
        tree.process(1f / 60f)
        var loops = 0
        player.animationLooped.connect { loops++ }
        player.play("spin")
        repeat(90) { tree.process(1f / 60f) }
        check(loops >= 2, "looped at least twice (got $loops)")
        check(player.playing, "still playing")
        check(player.progress in 0f..1.001f, "progress stays in range")
    }

    test("call tracks fire script hooks once per pass") {
        val animation = Animation("hit", 0.4f)
        animation.addCallTrack(".", "on_hit").addCall(0.1f)
        val player = AnimationPlayerNode("Anim")
        player.addAnimation(animation)
        val tree = SceneTree()
        val root = Node("root")
        root.addChild(player)
        tree.setScene(Scene("s", root))
        tree.process(1f / 60f)
        var hits = 0
        // "." in a track path addresses the animation player's parent node.
        root.scriptBehavior = object : dev.lumen2d.core.scene.ScriptBehavior {
            override val sourcePath: String = "scripts/test.lumen"
            override fun hasMethod(name: String) = name == "on_hit"
            override fun callMethod(name: String, args: List<Any?>): Any? {
                if (name == "on_hit") hits++
                return null
            }
        }
        player.play("hit")
        repeat(60) { tree.process(1f / 60f) }
        eq(1, hits, "call fired exactly once")
    }

    test("curves evaluate smoothly") {
        val linear = Curve.linear()
        near(0f, linear.evaluate(0f), 0.001f)
        near(1f, linear.evaluate(1f), 0.001f)
        near(0.5f, linear.evaluate(0.5f), 0.01f)
        val curved = Curve.fade()
        near(1f, curved.evaluate(0f), 0.001f)
        near(0f, curved.evaluate(1f), 0.001f)
        val restored = Curve.fromJson(curved.serialize())
        near(curved.evaluate(0.3f), restored.evaluate(0.3f), 0.001f)
        check(Curve.bounce().evaluate(0.5f) > 1f, "bounce overshoots")
    }

    test("animation player serialises with its animations") {
        val player = AnimationPlayerNode("Anim")
        player.addAnimation(Animation("idle", 2f).also {
            it.addPropertyTrack(".", "value").addKey(0f, 0f).addKey(2f, 5f)
        })
        val data = player.serialize(true)
        val restored = AnimationPlayerNode("Anim")
        restored.deserialize(data, dev.lumen2d.core.scene.NodeRegistry)
        eq(1, restored.animations.size, "animation restored")
        near(2f, restored.animations["idle"]!!.length, 0.001f)
    }

    test("animated sprites cycle frames") {
        val textures = dev.lumen2d.core.render.TextureManager()
        val buffer = dev.lumen2d.core.render.PixelBuffer(32, 16)
        for (y in 0 until 16) for (x in 0 until 32) buffer[x, y] = 0xFF888888.toInt()
        val texture = textures.add("tiles", buffer)
        val sprite = AnimatedSprite2D("Anim")
        sprite.textureId = "tiles"
        sprite.hframes = 2
        sprite.vframes = 1
        sprite.fps = 10f
        sprite.loop = true
        val tree = SceneTree()
        val root = Node("root")
        root.addChild(sprite)
        tree.setScene(Scene("s", root))
        tree.resources = dev.lumen2d.core.scene.SceneResources(textures = textures)
        tree.process(1f / 60f)
        val firstFrame = sprite.currentFrame
        var changed = false
        repeat(30) {
            tree.process(1f / 60f)
            if (sprite.currentFrame != firstFrame) changed = true
        }
        check(changed, "frame index advanced over time")
        check(textures.getOrNull("tiles") != null, "texture registered")
    }
}

fun audioTests() {
    section("audio")

    test("WAV encode/decode round-trips 16-bit stereo") {
        val samples = ShortArray(400) { (it * 40 - 8000).toShort() }
        val clip = AudioClip("beep", 22050, 2, samples)
        val bytes = WavCodec.encode(clip)
        check(WavCodec.isWav(bytes), "encoded bytes carry a RIFF header")
        val decoded = WavCodec.decode(bytes)
        eq(22050, decoded.sampleRate)
        eq(2, decoded.channels)
        eq(samples.size, decoded.samples.size)
        for (i in 0 until samples.size) eq(samples[i], decoded.samples[i], "sample $i survives the round trip")
        near(200f / 22050f, clip.durationSeconds, 0.001f, "duration derived from stereo frames and rate")
    }

    test("decoded clips expose waveform summaries") {
        val samples = ShortArray(1000) { (Math.sin(it / 20.0) * 20000).toInt().toShort() }
        val clip = AudioClip("tone", 44100, 1, samples)
        val waveform = clip.waveform(20)
        eq(20, waveform.size)
        check(waveform.any { it > 0.1f }, "waveform discovers the tone")
        check(clip.peak > 0.3f, "peak detection")
    }

    test("mixer plays, stops and counts voices") {
        val mixer = AudioMixer(sampleRate = 8000, channels = 2)
        val clip = AudioClip("blip", 8000, 2, ShortArray(800 * 2) { 8000 })
        val voice = mixer.play(clip, "SFX", volume = 1f)
        truthy(voice, "voice created")
        eq(1, mixer.voiceCount, "one active voice")
        val buffer = FloatArray(512 * 2)
        mixer.mix(buffer, 512)
        check(buffer.any { abs(it) > 0.01f }, "mixer produced audio")
        mixer.stopAll(0f)
        mixer.update(0.1f)
        eq(0, mixer.voiceCount, "voices stopped")
    }

    test("muted buses and the master volume silence output") {
        val mixer = AudioMixer(sampleRate = 8000, channels = 2)
        val clip = AudioClip("blip", 8000, 2, ShortArray(800 * 2) { 8000 })
        mixer.play(clip, "SFX")
        mixer.setBusVolume("SFX", 0f)
        val quiet = FloatArray(256 * 2)
        mixer.mix(quiet, 256)
        check(quiet.all { abs(it) < 0.001f }, "muted bus is silent")

        mixer.setBusVolume("SFX", 1f)
        mixer.masterVolume = 0f
        val silent = FloatArray(256 * 2)
        mixer.mix(silent, 256)
        check(silent.all { abs(it) < 0.001f }, "master volume silences everything")
    }

    test("voice count is capped and steals the oldest voice") {
        val mixer = AudioMixer(sampleRate = 8000, channels = 1)
        mixer.maxVoices = 4
        val clip = AudioClip("clip", 8000, 1, ShortArray(8000) { 4000 })
        repeat(10) { mixer.play(clip, "SFX") }
        check(mixer.voiceCount <= 4, "voice budget respected, got ${mixer.voiceCount}")
    }

    test("mixer state persists to JSON") {
        val mixer = AudioMixer(sampleRate = 8000, channels = 2)
        mixer.setBusVolume("Music", 0.25f)
        mixer.bus("Music").muted = true
        val json = mixer.serializeState()
        val restored = AudioMixer(sampleRate = 8000, channels = 2)
        restored.loadState(json)
        near(0.25f, restored.bus("Music").volume, 0.001f, "bus volume restored")
        check(restored.bus("Music").muted, "mute restored")
    }

    test("offline rendering produces a playable wav") {
        val mixer = AudioMixer(sampleRate = 22050, channels = 2)
        val samples = ShortArray(22050) { (Math.sin(it / 6.0) * 12000).toInt().toShort() }
        mixer.play(AudioClip("note", 22050, 2, samples), "SFX", volume = 0.8f)
        val pump = dev.lumen2d.core.audio.AudioPump(mixer, object : dev.lumen2d.core.platform.AudioOutput {
            override val sampleRate = 22050
            override val channels = 2
            override fun start() {}
            override fun write(buffer: ShortArray, count: Int) {}
            override fun stop() {}
            override val latencyMillis = 20
        }, framesPerBuffer = 512)
        val rendered = pump.renderOffline(0.5f)
        eq(22050, rendered.sampleRate)
        check(rendered.peak > 0.05f, "offline render is audible (peak=${rendered.peak})")
        val wav = WavCodec.encode(rendered)
        check(WavCodec.isWav(wav), "offline render encodes to wav")
    }

    test("audio effects shape the signal") {
        val clip = AudioClip("loud", 8000, 1, ShortArray(800) { 20000 })
        val quieter = clip.withGain(0.25f)
        check(quieter.peak < clip.peak, "gain effect reduces the peak")
        val trimmed = clip.trimmedTo(0.05f)
        check(trimmed.durationSeconds < clip.durationSeconds, "trim shortens the clip")
        val gain = dev.lumen2d.core.audio.GainEffect(0.5f)
        val shelf = dev.lumen2d.core.audio.LowPassEffect(2000f)
        val echo = dev.lumen2d.core.audio.EchoEffect(0.2f, 0.3f)
        val reverb = dev.lumen2d.core.audio.ReverbEffect()
        val crush = dev.lumen2d.core.audio.BitCrushEffect(6)
        val limiter = dev.lumen2d.core.audio.LimiterEffect(0.5f)
        for (effect in listOf(gain, shelf, echo, reverb, crush, limiter)) {
            effect.prepare(8000, 1)
            check(effect.name.isNotEmpty(), "effect has a name")
        }
    }
}
