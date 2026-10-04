/**
 * Lumen2D — demo scenes used for documentation previews and engine smoke tests.
 *
 * Every scene here is built from the public engine API only (no editor, no asset files), which
 * makes them a compact tour of the engine and a guard against API rot: `build()` runs in CI for
 * each demo and the result is written to `docs/preview/generated/<name>.png`.
 *
 * Available demos:
 *
 * | id             | shows                                                                    |
 * |----------------|--------------------------------------------------------------------------|
 * | `platformer`   | tilemap level, parallax backdrop, animated sprites, HUD, camera follow    |
 * | `tilemap`      | tile sets, layers, auto-tiling, collision from tiles                     |
 * | `physics`      | rigid bodies, character controller, raycasts, shapes                     |
 * | `particles`    | emitters, presets, trails, blend modes                                   |
 * | `ui`           | the whole widget toolkit in one screen                                   |
 * | `lighting`     | lights, parallax and blend modes                                         |
 * | `scripting`    | a LumenScript behaviour driving nodes at runtime                         |
 */
package dev.lumen2d.desktop.demo

import dev.lumen2d.core.game.Game
import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.scene.AnimatedSprite2D
import dev.lumen2d.core.scene.CameraNode
import dev.lumen2d.core.scene.CanvasLayer
import dev.lumen2d.core.scene.CharacterBody2DNode
import dev.lumen2d.core.scene.HudBarControl
import dev.lumen2d.core.scene.LabelControl
import dev.lumen2d.core.scene.LabelNode2D
import dev.lumen2d.core.scene.LineNode
import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.Node2D
import dev.lumen2d.core.scene.PanelNode
import dev.lumen2d.core.scene.ParallaxLayerNode
import dev.lumen2d.core.scene.ParticlesNode
import dev.lumen2d.core.scene.PolygonNode
import dev.lumen2d.core.scene.ProgressBarControl
import dev.lumen2d.core.scene.RigidBody2DNode
import dev.lumen2d.core.scene.Scene
import dev.lumen2d.core.scene.ScriptNode
import dev.lumen2d.core.scene.SliderControl
import dev.lumen2d.core.scene.Sprite2D
import dev.lumen2d.core.scene.TouchButtonControl
import dev.lumen2d.core.scene.VBoxContainer
import dev.lumen2d.core.tiles.TileCollision
import dev.lumen2d.core.tiles.TileMapData
import dev.lumen2d.core.tiles.TileMapNode
import dev.lumen2d.core.tiles.TileSet

/** The demo catalogue. Add a builder here and it shows up in `--demo all` automatically. */
object DemoScenes {

    val names: List<String> = listOf("platformer", "tilemap", "physics", "particles", "ui", "lighting", "scripting")

    /**
     * How many fixed 60 Hz frames each demo needs before its screenshot looks settled: physics
     * needs time for bodies to fall, particles need time to fill, the rest are quick.
     */
    fun settleFrames(id: String): Int = when (id) {
        "physics" -> 110
        "particles" -> 70
        "lighting" -> 60
        "platformer" -> 40
        else -> 24
    }

    fun describe(id: String): String = when (id) {
        "platformer" -> "Tilemap platformer level with parallax sky, coins, enemy and HUD"
        "tilemap" -> "Tile set editor output: layers, auto-tiling and tile collision"
        "physics" -> "Rigid bodies, character controller and physics queries"
        "particles" -> "Particle emitters, presets and blend modes"
        "ui" -> "Widget toolkit: containers, buttons, sliders, bars, HUD"
        "lighting" -> "Lights, parallax layers and blend modes"
        "scripting" -> "LumenScript behaviours driving scene nodes"
        else -> id
    }

    /** Builds demo [id] and makes it the active scene. Returns false for unknown ids. */
    fun build(id: String, game: Game): Boolean {
        val scene = when (id) {
            "platformer" -> platformer(game)
            "tilemap" -> tilemap(game)
            "physics" -> physics(game)
            "particles" -> particles(game)
            "ui" -> ui(game)
            "lighting" -> lighting(game)
            "scripting" -> scripting(game)
            else -> return false
        }
        game.changeScene(scene)
        return true
    }

    // ----------------------------------------------------------------- platformer

    private const val TILE_W = 16

    /** A hand-painted level: sky, hills, a tilemap platform course, pickups and a HUD. */
    private fun platformer(game: Game): Scene {
        val width = game.config.designWidth
        val height = game.config.designHeight
        val scene = Scene.empty("Platformer")
        val root = scene.root

        // Parallax sky + hills.
        val sky = Sprite2D("Sky")
        sky.textureId = "sky.png"
        sky.position = Vec2(width / 2f, height / 2f)
        sky.zIndex = -100
        root.addChild(sky)

        val hills = ParallaxLayerNode("Hills")
        hills.scrollFactor = Vec2(0.35f, 0f)
        hills.repeatWidth = width.toFloat()
        hills.zIndex = -90
        val hillsSprite = Sprite2D("HillsStrip")
        hillsSprite.textureId = DemoArt.BG_HILLS
        hills.addChild(hillsSprite)
        root.addChild(hills)

        // Level tiles: two layers, the decoration layer draws above gameplay.
        val map = TileMapData(tileWidth = TILE_W, tileHeight = TILE_W)
        map.tileSet.textureId = DemoArt.TILES
        map.tileSet.tileCount = 12
        map.tileSet.columns = 4
        val solid = map.ensureLayer("Solid", 30, 17)
        val decor = map.ensureLayer("Decor", 30, 17)
        decor.collisionEnabled = false
        map.tileSet.tileMeta(1).collision = TileCollision.FULL   // dirt
        map.tileSet.tileMeta(2).collision = TileCollision.FULL   // stone
        map.tileSet.tileMeta(5).collision = TileCollision.FULL   // dark stone
        map.tileSet.tileMeta(3).collision = TileCollision.PLATFORM // one-way platform
        map.tileSet.tileMeta(3).oneWay = true
        map.tileSet.tileMeta(6).hazard = 25f                     // spikes

        for (x in 0 until 30) { solid.set(x, 14, 0); solid.set(x, 15, 1); solid.set(x, 16, 1) }
        // two pits and a few platforms to hop between
        for (x in 11..13) for (y in 14..16) solid.set(x, y, -1)
        for (x in 21..23) for (y in 14..16) solid.set(x, y, -1)
        for (x in 5..8) solid.set(x, 11, 2)
        for (x in 16..19) solid.set(x, 10, 2)
        for (x in 25..28) solid.set(x, 12, 3)
        for (x in 9..10) solid.set(x, 13, 6)
        for (x in 0 until 30) if (x % 7 == 3) decor.set(x, 13, 11)

        val level = TileMapNode("Level")
        level.name = "Level"
        level.tileMap = map
        level.textureId = DemoArt.TILES
        root.addChild(level)

        val gameplay = root.child("Gameplay") ?: Node2D("Gameplay").also { root.addChild(it) }

        val player = AnimatedSprite2D("Player")
        player.textureId = DemoArt.PLAYER
        player.hframes = 4
        player.vframes = 2
        player.fps = 8f
        player.frames = (0 until 8).map { it.toString() }.toMutableList()
        player.position = Vec2(56f, 214f)
        player.zIndex = 5
        gameplay.addChild(player)

        for ((index, cell) in listOf(6 to 9, 17 to 8, 26 to 10, 14 to 12, 4 to 12).withIndex()) {
            val coin = AnimatedSprite2D("Coin${index + 1}")
            coin.textureId = DemoArt.COIN
            coin.hframes = 4
            coin.fps = 10f
            coin.position = Vec2(cell.first * TILE_W + 8f, cell.second * TILE_W + 8f)
            coin.zIndex = 4
            gameplay.addChild(coin)
        }

        val slime = AnimatedSprite2D("Slime")
        slime.textureId = DemoArt.ENEMY
        slime.hframes = 4
        slime.fps = 6f
        slime.position = Vec2(19 * TILE_W.toFloat(), 216f)
        gameplay.addChild(slime)

        val goal = Sprite2D("Goal")
        goal.textureId = DemoArt.CHECKPOINT
        goal.position = Vec2(29 * TILE_W - 6f, 214f)
        gameplay.addChild(goal)

        val dust = ParticlesNode("Dust")
        dust.setProperty("particleTextureId", DemoArt.PARTICLES)
        dust.setProperty("amount", 24)
        dust.setProperty("lifetime", 1.4f)
        dust.setProperty("speed", 40f)
        dust.setProperty("direction", -1.2f)
        dust.setProperty("spread", 1.6f)
        dust.setProperty("gravity", Vec2(0f, -10f))
        dust.setProperty("startSize", 4f)
        dust.setProperty("endSize", 0f)
        dust.setProperty("emitting", false)
        dust.position = Vec2(14f * TILE_W, 232f)
        gameplay.addChild(dust)

        val camera = CameraNode("Camera")
        camera.position = Vec2(width / 2f, height / 2f)
        camera.limitEnabled = true
        camera.limitRect = dev.lumen2d.core.math.Rect(0f, 0f, 30 * TILE_W.toFloat(), 17 * TILE_W.toFloat())
        camera.smoothingEnabled = true
        camera.smoothing = 0.25f
        root.addChild(camera)

        // HUD in a canvas layer so it ignores the camera.
        val uiRoot = root.child("UI") ?: Node2D("UI").also { root.addChild(it) }
        val canvas = CanvasLayer("HUD")
        canvas.layer = 10
        uiRoot.addChild(canvas)

        val coins = HudBarControl("CoinBar")
        coins.iconTexture = DemoArt.COIN_ICON
        coins.iconSize = 10f
        coins.setCount(3, 5)
        coins.labelText = "3/5"
        coins.anchorLeft = 0f; coins.anchorTop = 0f
        coins.offsetLeft = 8f; coins.offsetTop = 6f
        coins.minSize = Vec2(96f, 16f)
        canvas.addChild(coins)

        val health = ProgressBarControl("Health")
        health.value = 0.72f
        health.anchorLeft = 1f; health.anchorRight = 1f; health.anchorTop = 0f
        health.offsetLeft = -104f; health.offsetRight = -8f; health.offsetTop = 8f
        health.minSize = Vec2(96f, 8f)
        canvas.addChild(health)

        val title = LabelControl("Title")
        title.text = "LUMEN2D - PLATFORMER DEMO"
        title.align = dev.lumen2d.core.render.TextAlign.CENTER
        title.anchorLeft = 0f; title.anchorRight = 1f; title.anchorTop = 0f
        title.offsetTop = 24f
        title.minSize = Vec2(width.toFloat(), 12f)
        canvas.addChild(title)

        demoScript(
            game, scene,
            """
            # A little life in the preview: the slime patrols and the player bobs.
            var t = 0.0
            var direction = 1.0

            func ready() {
                print("platformer demo ready")
            }

            func process(delta) {
                t += delta
                var player = get_node("Gameplay/Player")
                player.position.y = 214.0 + sin(t * 3.0) * 1.5
                var slime = get_node("Gameplay/Slime")
                slime.position.x += direction * 24.0 * delta
                if slime.position.x > 340.0 { direction = -1.0 }
                if slime.position.x < 288.0 { direction = 1.0 }
            }
            """.trimIndent(),
        )
        return scene
    }

    // -------------------------------------------------------------------- tilemap

    /** Shows every tile in the set with layers, collision and auto-tiling. */
    private fun tilemap(game: Game): Scene {
        val scene = Scene.empty("Tilemap")
        val root = scene.root
        val backdrop = Sprite2D("Backdrop")
        backdrop.textureId = "sky.png"
        backdrop.position = Vec2(game.config.designWidth / 2f, game.config.designHeight / 2f)
        backdrop.zIndex = -100
        root.addChild(backdrop)

        val map = TileMapData(tileWidth = TILE_W, tileHeight = TILE_W)
        map.tileSet.textureId = DemoArt.TILES
        map.tileSet.tileCount = 12
        map.tileSet.columns = 4
        map.tileSet.tileMeta(1).collision = TileCollision.FULL

        // Terrain auto-tile rules: neighbour bitmask (1 up, 2 right, 4 down, 8 left) -> tile index.
        // 0 = grass cap, 1 = dirt fill, 2 = stone edge.
        map.tileSet.autoTileRules["ground"] = linkedMapOf(
            0 to 1, 1 to 0, 3 to 0, 5 to 0, 9 to 0, 13 to 0, 7 to 0, 11 to 0,
            2 to 2, 4 to 2, 8 to 2, 6 to 2, 10 to 2, 12 to 2, 14 to 2, 15 to 1,
        )

        val ground = map.ensureLayer("Ground", 30, 17)
        val props = map.ensureLayer("Props", 30, 17)
        props.collisionEnabled = false

        // A hill silhouette painted as filled dirt, then handed to the auto-tiler.
        for (x in 0 until 30) {
            val top = 9 + (4 * kotlin.math.sin(x * 0.21)).toInt().coerceAtLeast(0)
            for (y in top until 17) ground.set(x, y, 1)
        }
        map.autoTile("Ground", "ground", dev.lumen2d.core.math.Rect(0f, 0f, 30f, 17f))

        // Scattered props from the same sheet, placed on the surface the auto-tiler produced.
        fun surfaceAt(x: Int): Int {
            for (y in 0 until 17) if (ground.get(x, y) >= 0) return y
            return 17
        }
        for (x in 2 until 28 step 5) props.set(x, surfaceAt(x), 9)             // bush
        for (x in 5 until 28 step 9) props.set(x, surfaceAt(x) + 1, 11)        // gem
        for (x in 11 until 28 step 13) props.set(x, surfaceAt(x), 7)           // ladder

        val world = root.child("Gameplay") ?: Node2D("Gameplay").also { root.addChild(it) }
        val layer = TileMapNode("Ground")
        layer.tileMap = map
        layer.textureId = DemoArt.TILES
        world.addChild(layer)

        // Tile palette strip: all twelve tiles of the set, exactly what the tile set editor shows.
        val palette = TileMapData(tileWidth = TILE_W, tileHeight = TILE_W)
        palette.tileSet.textureId = DemoArt.TILES
        palette.tileSet.tileCount = 12
        palette.tileSet.columns = 4
        val strip = palette.ensureLayer("Palette", 12, 1)
        for (index in 0 until 12) strip.set(index, 0, index)
        val paletteNode = TileMapNode("Palette")
        paletteNode.tileMap = palette
        paletteNode.textureId = DemoArt.TILES
        paletteNode.generateCollision = false
        paletteNode.position = Vec2(216f, 250f)
        paletteNode.scale = Vec2(1.5f, 1.5f)
        paletteNode.zIndex = 200
        root.addChild(paletteNode)

        val marker = LabelNode2D("Legend")
        marker.text = "TILE SET | LAYERS | AUTO-TILE"
        marker.centered = false
        marker.position = Vec2(12f, 14f)
        root.addChild(marker)

        demoScript(
            game, scene,
            """
            # Drift the tilemap a few pixels so the preview shows the layer transform.
            func process(delta) {
                get_node("Gameplay").position.x = sin(time * 0.4) * 6.0
            }
            """.trimIndent(),
        )
        return scene
    }

    // -------------------------------------------------------------------- physics

    /** Rigid bodies falling onto a floor, a character body and a raycast. */
    private fun physics(game: Game): Scene {
        val scene = Scene.empty("Physics")
        val root = scene.root
        val backdrop = Sprite2D("Backdrop")
        backdrop.textureId = "sky.png"
        backdrop.position = Vec2(game.config.designWidth / 2f, game.config.designHeight / 2f)
        backdrop.zIndex = -100
        root.addChild(backdrop)
        val hills = ParallaxLayerNode("Hills")
        hills.scrollFactor = Vec2(0.4f, 0f)
        hills.zIndex = -90
        val hillStrip = Sprite2D("Strip")
        hillStrip.textureId = DemoArt.BG_HILLS
        hillStrip.position = Vec2(game.config.designWidth / 2f, 210f)
        hills.addChild(hillStrip)
        root.addChild(hills)

        val map = TileMapData(tileWidth = TILE_W, tileHeight = TILE_W)
        map.tileSet.textureId = DemoArt.TILES
        map.tileSet.tileCount = 12
        map.tileSet.columns = 4
        val ground = map.ensureLayer("Ground", 30, 18)
        for (x in 0 until 30) { ground.set(x, 13, 0); ground.set(x, 14, 1); ground.set(x, 15, 1); ground.set(x, 16, 1) }
        for (x in 4..6) ground.set(x, 10, 2)
        for (x in 20..23) ground.set(x, 11, 5)
        for (x in 13..14) ground.set(x, 8, 2)
        map.tileSet.tileMeta(1).collision = TileCollision.FULL
        map.tileSet.tileMeta(2).collision = TileCollision.FULL
        val level = TileMapNode("Level")
        level.tileMap = map
        level.textureId = DemoArt.TILES
        root.addChild(level)

        val gameplay = root.child("Gameplay") ?: Node2D("Gameplay").also { root.addChild(it) }

        // A stack of crates with different restitution / friction.
        var seed = 7
        fun rng(): Float {
            seed = seed * 1103515245 + 12345
            return ((seed ushr 16) % 1000) / 1000f
        }
        for (index in 0 until 7) {
            val crate = Sprite2D("Crate$index")
            crate.textureId = DemoArt.TILES
            crate.hframes = 4
            crate.vframes = 3
            crate.frame = 5
            crate.position = Vec2(120f + rng() * 180f, 20f + index * 26f)
            val body = RigidBody2DNode("CrateBody$index")
            body.bodyType = dev.lumen2d.core.physics.BodyType.RIGID
            body.restitution = 0.25f + rng() * 0.3f
            body.friction = 0.4f
            body.linearDamping = 0.05f
            body.addChild(crate)
            gameplay.addChild(body)
        }

        // Character body that will be seen standing on the floor after the demo runs.
        val walker = CharacterBody2DNode("Walker")
        walker.position = Vec2(70f, 180f)
        val walkerSprite = AnimatedSprite2D("WalkerSprite")
        walkerSprite.textureId = DemoArt.PLAYER
        walkerSprite.hframes = 4
        walkerSprite.vframes = 2
        walkerSprite.fps = 8f
        walkerSprite.frames = (4 until 8).map { it.toString() }.toMutableList()
        walker.addChild(walkerSprite)
        gameplay.addChild(walker)

        val camera = CameraNode("Camera")
        camera.position = Vec2(game.config.designWidth / 2f, game.config.designHeight / 2f)
        root.addChild(camera)

        demoScript(
            game, scene,
            """
            var direction = 1.0

            func physics_process(delta) {
                var walker = get_node("Gameplay/Walker")
                walker.velocity.x = direction * 60.0
                walker.move_and_slide(delta)
                if walker.on_wall { direction = -direction }
            }
            """.trimIndent(),
        )
        return scene
    }

    // ------------------------------------------------------------------ particles

    /** Four emitters showing presets, colours, one-shot bursts and trails. */
    private fun particles(game: Game): Scene {
        val scene = Scene.empty("Particles")
        val root = scene.root
        val backdrop = Sprite2D("Backdrop")
        backdrop.textureId = "sky.png"
        backdrop.position = Vec2(game.config.designWidth / 2f, game.config.designHeight / 2f)
        backdrop.modulate = Color(0.28f, 0.32f, 0.55f)
        backdrop.zIndex = -100
        root.addChild(backdrop)

        val specs = listOf(
            Triple(Vec2(84f, 130f), "fire", Color(1f, 0.65f, 0.25f, 0.95f)),
            Triple(Vec2(188f, 130f), "smoke", Color(0.65f, 0.7f, 0.85f, 0.75f)),
            Triple(Vec2(292f, 130f), "sparkle", Color(0.6f, 0.95f, 1f, 0.95f)),
            Triple(Vec2(396f, 130f), "explosion", Color(1f, 0.5f, 0.3f, 0.9f)),
        )
        val gameplay = root.child("Gameplay") ?: Node2D("Gameplay").also { root.addChild(it) }
        for ((index, spec) in specs.withIndex()) {
            val (position, preset, tint) = spec
            val emitter = ParticlesNode("Emitter$index")
            emitter.setProperty("presetName", preset)
            emitter.setProperty("particleTextureId", DemoArt.PARTICLES)
            emitter.setProperty("startColor", tint)
            emitter.setProperty("emitting", true)
            emitter.position = position
            gameplay.addChild(emitter)

            val label = LabelNode2D("Label$index")
            label.text = preset
            label.position = Vec2(position.x, 214f)
            gameplay.addChild(label)
        }

        val title = LabelNode2D("Title")
        title.text = "PARTICLES | PRESETS | BLEND MODES"
        title.position = Vec2(12f, 12f)
        root.addChild(title)
        return scene
    }

    // ------------------------------------------------------------------------- ui

    /** Every widget in the toolkit on one screen. */
    private fun ui(game: Game): Scene {
        val scene = Scene.empty("UI")
        val root = scene.root
        val width = game.config.designWidth
        val height = game.config.designHeight

        val backdrop = Sprite2D("Backdrop")
        backdrop.textureId = "sky.png"
        backdrop.position = Vec2(game.config.designWidth / 2f, game.config.designHeight / 2f)
        backdrop.zIndex = -100
        root.addChild(backdrop)

        val uiRoot = root.child("UI") ?: Node2D("UI").also { root.addChild(it) }
        val canvas = CanvasLayer("Canvas")
        canvas.layer = 5
        uiRoot.addChild(canvas)

        val frame = PanelNode("Frame")
        frame.backgroundColor = Color(0.08f, 0.10f, 0.18f, 0.82f)
        frame.anchorLeft = 0f; frame.anchorRight = 1f; frame.anchorTop = 0f; frame.anchorBottom = 1f
        frame.offsetLeft = 8f; frame.offsetRight = -8f; frame.offsetTop = 8f; frame.offsetBottom = -8f
        canvas.addChild(frame)

        val column = VBoxContainer("Column")
        column.anchorLeft = 0f; column.anchorRight = 1f; column.anchorTop = 0f
        column.offsetLeft = 20f; column.offsetRight = -width / 2f; column.offsetTop = 20f
        column.spacing = 6f
        frame.addChild(column)

        val header = LabelControl("Header")
        header.text = "WIDGET SHOWCASE"
        header.minSize = Vec2(width / 2f - 40f, 12f)
        column.addChild(header)

        val subtitle = LabelControl("Subtitle")
        subtitle.text = "controls, containers, bars and buttons"
        subtitle.minSize = Vec2(width / 2f - 40f, 10f)
        column.addChild(subtitle)

        val button = TouchButtonControl("TouchButton")
        button.label = "JUMP"
        button.minSize = Vec2(44f, 44f)
        button.anchorTop = 1f; button.anchorBottom = 1f
        button.offsetLeft = 28f; button.offsetRight = 72f
        button.offsetTop = -64f; button.offsetBottom = -20f
        canvas.addChild(button)

        val slider = SliderControl("Slider")
        slider.anchorLeft = 1f; slider.anchorRight = 1f
        slider.offsetLeft = -width / 2f + 20f; slider.offsetRight = -20f
        slider.offsetTop = 30f
        slider.minSize = Vec2(width / 2f - 40f, 12f)
        canvas.addChild(slider)

        val bar = ProgressBarControl("Progress")
        bar.value = 0.65f
        bar.anchorLeft = 1f; bar.anchorRight = 1f
        bar.offsetLeft = -width / 2f + 20f; bar.offsetRight = -20f
        bar.offsetTop = 52f
        bar.minSize = Vec2(width / 2f - 40f, 10f)
        canvas.addChild(bar)

        val hint = LabelControl("Hint")
        hint.text = "Lumen2D UI nodes use anchors + offsets, so one scene scales from phones to desktop."
        hint.wrap = true
        hint.anchorLeft = 1f; hint.anchorRight = 1f
        hint.offsetLeft = -width / 2f + 20f; hint.offsetRight = -20f
        hint.offsetTop = 76f
        hint.minSize = Vec2(width / 2f - 40f, 40f)
        canvas.addChild(hint)

        val footer = LabelControl("Footer")
        footer.text = "design ${'$'}{width}x${'$'}{height}"
        footer.format = { "design ${width} x ${height} | ${game.config.stretchMode.label}" }
        footer.align = dev.lumen2d.core.render.TextAlign.CENTER
        footer.anchorLeft = 0f; footer.anchorRight = 1f; footer.anchorTop = 1f; footer.anchorBottom = 1f
        footer.offsetTop = -16f; footer.offsetBottom = 0f
        footer.minSize = Vec2(width.toFloat(), 12f)
        canvas.addChild(footer)

        // A tween keeps the demo animated so screenshots never look frozen.
        game.tree.tweens.tweenProperty(slider, "value", 1f, 1.5f).setLoops(20, pingPong = true)
        return scene
    }

    // -------------------------------------------------------------------- lighting

    /** Lights, parallax and blend modes layered over a night scene. */
    private fun lighting(game: Game): Scene {
        val scene = Scene.empty("Lighting")
        val root = scene.root
        val width = game.config.designWidth

        val night = Sprite2D("Night")
        night.textureId = "sky.png"
        night.position = Vec2(game.config.designWidth / 2f, game.config.designHeight / 2f)
        night.modulate = Color(0.35f, 0.4f, 0.7f)
        night.zIndex = -100
        root.addChild(night)

        val hills = ParallaxLayerNode("Hills")
        hills.scrollFactor = Vec2(0.5f, 0f)
        hills.repeatWidth = width.toFloat()
        hills.zIndex = -90
        val strip = Sprite2D("Strip")
        strip.textureId = DemoArt.BG_HILLS
        strip.modulate = Color(0.5f, 0.55f, 0.8f)
        hills.addChild(strip)
        root.addChild(hills)

        val lightsRoot = root.child("Gameplay") ?: Node2D("Gameplay").also { root.addChild(it) }

        // A village skyline for the lanterns to light up — walls for the light to fall on, roofs for
        // silhouette, all built with the same tile texture the level uses.
        val houses = listOf(40f to 60f, 165f to 74f, 268f to 56f, 372f to 70f, 214f to 44f)
        for ((index, house) in houses.withIndex()) {
            val (x, houseWidth) = house
            val wall = Sprite2D("House$index")
            wall.textureId = DemoArt.TILES
            wall.hframes = 4
            wall.vframes = 3
            wall.frame = 1
            wall.drawSize = Vec2(houseWidth, 52f)
            wall.position = Vec2(x, 220f)
            wall.modulate = Color(0.7f, 0.74f, 0.88f)
            wall.zIndex = 20
            lightsRoot.addChild(wall)
            val roof = PolygonNode("Roof$index")
            roof.points = mutableListOf(
                Vec2(-houseWidth / 2f - 6f, 0f), Vec2(houseWidth / 2f + 6f, 0f), Vec2(0f, -22f))
            roof.color = if (index % 2 == 0) Color.fromHex("#8C3B2E") else Color.fromHex("#3E4C7A")
            roof.position = Vec2(x, 194f)
            roof.zIndex = 21
            lightsRoot.addChild(roof)
        }

        val lanterns = listOf(
            Triple(Vec2(70f, 150f), Color(1f, 0.82f, 0.45f, 0.7f), 78f),
            Triple(Vec2(190f, 120f), Color(1f, 0.6f, 0.3f, 0.65f), 96f),
            Triple(Vec2(300f, 155f), Color(0.55f, 0.85f, 1f, 0.6f), 84f),
            Triple(Vec2(400f, 120f), Color(0.75f, 0.6f, 1f, 0.55f), 90f),
            Triple(Vec2(240f, 200f), Color(1f, 0.9f, 0.6f, 0.5f), 70f),
        )
        for ((index, lantern) in lanterns.withIndex()) {
            val (position, tint, lightRadius) = lantern
            val light = dev.lumen2d.core.scene.LightNode("Light$index")
            light.setProperty("color", tint)
            light.setProperty("energy", 0.85f)
            light.setProperty("radius", lightRadius)
            light.position = position
            light.zIndex = 50
            lightsRoot.addChild(light)
        }

        val glow = ParticlesNode("Glow")
        glow.setProperty("particleTextureId", DemoArt.PARTICLES)
        glow.setProperty("amount", 32)
        glow.setProperty("lifetime", 2.5f)
        glow.setProperty("speed", 12f)
        glow.setProperty("gravity", Vec2(0f, -8f))
        glow.setProperty("startColor", Color(1f, 0.9f, 0.5f, 0.9f))
        glow.setProperty("endColor", Color(1f, 0.5f, 0.2f, 0f))
        glow.setProperty("blend", "ADD")
        glow.setProperty("emitting", true)
        glow.setProperty("amount", 48)
        glow.setProperty("lifetime", 3.2f)
        glow.setProperty("speed", 18f)
        glow.position = Vec2(width / 2f, 235f)
        glow.zIndex = 60
        root.addChild(glow)

        val title = LabelNode2D("Title")
        title.text = "LIGHTS | PARALLAX | ADDITIVE BLEND"
        title.position = Vec2(12f, 12f)
        title.zIndex = 100
        root.addChild(title)

        demoScript(
            game, scene,
            """
            # Slide the first lantern back and forth.
            var t = 0.0

            func process(delta) {
                t += delta
                get_node("Gameplay/Light0").position.x = 70.0 + sin(t) * 18.0
            }
            """.trimIndent(),
        )
        return scene
    }

    // ------------------------------------------------------------------- scripting

    /** Five behaviours written in LumenScript: movement, tweening, spawning, HUD text. */
    private fun scripting(game: Game): Scene {
        val scene = Scene.empty("Scripting")
        val root = scene.root
        val backdrop = Sprite2D("Backdrop")
        backdrop.textureId = "sky.png"
        backdrop.position = Vec2(game.config.designWidth / 2f, game.config.designHeight / 2f)
        backdrop.zIndex = -100
        root.addChild(backdrop)

        val gameplay = root.child("Gameplay") ?: Node2D("Gameplay").also { root.addChild(it) }
        val orbit = Sprite2D("Orbit")
        orbit.textureId = DemoArt.COIN
        orbit.hframes = 4
        orbit.position = Vec2(240f, 135f)
        gameplay.addChild(orbit)

        for (index in 0 until 3) {
            val ball = Sprite2D("Ball$index")
            ball.textureId = DemoArt.TILES
            ball.hframes = 4
            ball.vframes = 3
            ball.frame = 11
            ball.position = Vec2(80f + index * 160f, 60f)
            gameplay.addChild(ball)
        }

        val hero = AnimatedSprite2D("Hero")
        hero.textureId = DemoArt.PLAYER
        hero.hframes = 4
        hero.vframes = 2
        hero.fps = 8f
        hero.frames = (0 until 8).map { it.toString() }.toMutableList()
        hero.position = Vec2(60f, 214f)
        hero.zIndex = 5
        gameplay.addChild(hero)

        val uiRoot = root.child("UI") ?: Node2D("UI").also { root.addChild(it) }
        val canvas = CanvasLayer("HUD")
        canvas.layer = 10
        uiRoot.addChild(canvas)
        val status = LabelControl("Status")
        status.anchorLeft = 0f; status.anchorRight = 1f; status.anchorTop = 1f; status.anchorBottom = 1f
        status.offsetTop = -22f; status.offsetBottom = -6f
        status.align = dev.lumen2d.core.render.TextAlign.CENTER
        status.minSize = Vec2(game.config.designWidth.toFloat(), 12f)
        canvas.addChild(status)

        val script = ScriptNode("Behaviour")
        script.scriptPath = "scripts/demo.lumen"
        root.addChild(script)

        demoScript(game, scene, SCRIPTING_DEMO, path = "scripts/demo.lumen")
        return scene
    }

    /** Adds a behaviour node to [scene]; the script source is registered before the node readies. */
    private fun demoScript(game: Game, scene: Scene, source: String, path: String = "scripts/auto_behaviour.lumen") {
        game.registerScript(path, source)
        val node = ScriptNode("AutoBehaviour")
        node.scriptPath = path
        scene.root.addChild(node)
    }

    /** The script used by the `scripting` demo — kept here so it doubles as documentation. */
    val SCRIPTING_DEMO = """
        # LumenScript tour: variables, functions, vectors, loops, input and node access.
        export var speed = 90.0
        var time = 0.0
        var score = 0

        func ready() {
            print("scripting demo ready")
        }

        func process(delta) {
            time += delta

            # Orbit the coin around the screen centre.
            var orbit = get_node("Gameplay/Orbit")
            var radius = 90.0 + sin(time) * 18.0
            orbit.position = vec2(240.0 + cos(time * 2.0) * radius, 135.0 + sin(time * 2.0) * radius * 0.55)
            orbit.rotation = time * 2.0

            # Bounce the three balls, each with its own phase.
            var names = ["Ball0", "Ball1", "Ball2"]
            var index = 0
            for name in names {
                var ball = get_node("Gameplay/" + name)
                ball.position.y = 60.0 + abs(sin(time * 2.0 + index)) * 120.0
                index += 1
            }

            # Drive the hero from the input action map.
            var hero = get_node("Gameplay/Hero")
            var direction = input.axis("move")
            hero.position.x += direction.x * speed * delta
            hero.flipX = direction.x < 0.0
            if direction.x != 0.0 {
                hero.set_frame(5)
            } else {
                hero.set_frame(0)
            }
            if hero.position.x > 430.0 {
                hero.position.x = 40.0
                score += 1
            }

            var shown = round(time * 10.0) / 10.0
            get_node("UI/HUD/Status").text = "t " + str(shown) + "s   score " + str(score)
        }

        # Exported so editors show it in the inspector.
        func add_score(amount) {
            score += amount
            return score
        }
    """.trimIndent()
}
