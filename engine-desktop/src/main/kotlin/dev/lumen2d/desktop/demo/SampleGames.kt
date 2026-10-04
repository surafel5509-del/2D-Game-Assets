/**
 * Lumen2D — sample game generator.
 *
 * Writes the games that ship with the engine as ordinary projects on disk:
 *
 * ```
 * sample-games/pixel-platformer/
 *   project.lumen                  game config + asset packs
 *   input_map.json                 keyboard + touch bindings
 *   scenes/menu.scene.json         title screen
 *   scenes/level_1.scene.json      the level, built from the base asset pack
 *   scripts/game.lumen             all the gameplay code, in LumenScript
 * ```
 *
 * They are generated instead of hand-written so they cannot drift from the engine's node and
 * script APIs — a breaking change makes `lumen2d --check <game>` fail — and they reference
 * `lib://packs/base`, the asset library the engine also generates, rather than embedding art.
 *
 * Regenerate with `lumen2d --export-samples sample-games`.
 */
package dev.lumen2d.desktop.demo

import dev.lumen2d.core.game.DefaultInputMap
import dev.lumen2d.core.game.GameConfig
import dev.lumen2d.core.game.Project
import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.platform.FileFileSystem
import dev.lumen2d.core.scene.AnimatedSprite2D
import dev.lumen2d.core.scene.CameraNode
import dev.lumen2d.core.scene.CanvasLayer
import dev.lumen2d.core.scene.CharacterBody2DNode
import dev.lumen2d.core.scene.CollisionShape2DNode
import dev.lumen2d.core.scene.HudBarControl
import dev.lumen2d.core.scene.LabelControl
import dev.lumen2d.core.scene.Node2D
import dev.lumen2d.core.scene.ParticlesNode
import dev.lumen2d.core.scene.ProgressBarControl
import dev.lumen2d.core.scene.Scene
import dev.lumen2d.core.scene.ScriptNode
import dev.lumen2d.core.scene.Sprite2D
import dev.lumen2d.core.scene.TouchButtonControl
import dev.lumen2d.core.tiles.TileCollision
import dev.lumen2d.core.tiles.TileMapData
import dev.lumen2d.core.tiles.TileMapNode
import java.io.File

/** Builds every sample game into [root]; each entry in [written] is a project folder name. */
class SampleGames(private val root: File) {
    companion object {
        /**
         * Fixed write timestamp for the shipped projects: regenerating `sample-games/` twice
         * produces identical bytes, so CI can assert the committed content matches the engine
         * with a plain `git diff` (the property that keeps the APK's samples honest).
         */
        const val SAMPLE_SAVED_AT: Long = 1_600_000_000_000L
    }


    val written = ArrayList<String>()

    /** Asset ids the sample games use, so a missing pack fails the `--check` run loudly. */
    private object Pack {
        const val PLAYER = "lib://packs/base/sprites/player.png"
        const val SLIME = "lib://packs/base/sprites/slime.png"
        const val COIN = "lib://packs/base/sprites/coin.png"
        const val FLAG = "lib://packs/base/sprites/flag.png"
        const val COIN_ICON = "lib://packs/base/ui/coin_icon.png"
        const val TILES = "lib://packs/base/tiles/tileset.png"
        const val SPARK = "lib://packs/base/fx/spark.png"
        const val HILLS = "lib://packs/base/fx/hills.png"
    }

    fun buildAll(): List<String> {
        helloLumen()
        pixelPlatformer()
        neonShooter()
        return written
    }

    private fun create(id: String, title: String, description: String, block: (Project) -> Unit) {
        val directory = File(root, id)
        directory.mkdirs()
        val project = Project.create(FileFileSystem(directory), "", title, GameConfig().also {
            it.title = title
            it.description = description
            it.packageId = "dev.lumen2d.samples.${id.replace('-', '_')}"
            it.author = "Lumen2D samples"
            it.assetPacks = arrayListOf("lib://packs/base")
            it.tags = arrayListOf("player", "enemy", "pickup", "goal", "bullet")
        })
        project.vfs.mkdirs(project.path("scripts"))
        project.vfs.mkdirs(project.path("scenes"))
        block(project)
        // Project.create() seeds a template main scene; drop it when the sample ships its own
        // entry scene so the project folder only contains files that are actually used.
        val templateScene = project.path("scenes/main.scene.json")
        if (project.config.startScene != "scenes/main.scene.json" && project.vfs.exists(templateScene)) {
            project.vfs.delete(templateScene)
        }
        project.saveInputMap(DefaultInputMap.bindings())
        project.save(SAMPLE_SAVED_AT)
        written += id
    }

    // ------------------------------------------------------------------- hello-lumen2d

    /** The "first project" from the quick-start guide: one sprite, one script, one hint label. */
    private fun helloLumen() = create(
        "hello-lumen2d", "Hello Lumen2D",
        "The smallest complete Lumen2D game: a tilemap floor, a walking character and a camera.",
    ) { project ->
        val scene = Scene.empty("Main")
        val world = scene.root.child("Gameplay")!!

        val player = CharacterBody2DNode("Player")
        player.position = Vec2(240f, 180f)
        player.addChild(CollisionShape2DNode("Body").also { it.size = Vec2(10f, 18f) })
        val sprite = AnimatedSprite2D("Sprite")
        sprite.textureId = Pack.PLAYER
        sprite.hframes = 4
        sprite.vframes = 2
        sprite.fps = 8f
        sprite.frames = (0 until 8).map { it.toString() }.toMutableList()
        player.addChild(sprite)
        world.addChild(player)

        val map = TileMapData(tileWidth = 16, tileHeight = 16)
        map.tileSet.textureId = Pack.TILES
        map.tileSet.tileCount = 12
        map.tileSet.columns = 4
        map.tileSet.tileMeta(0).collision = TileCollision.FULL
        map.tileSet.tileMeta(1).collision = TileCollision.FULL
        val layer = map.ensureLayer("Ground", 30, 17)
        for (x in 0 until 30) { layer.set(x, 14, 0); layer.set(x, 15, 1); layer.set(x, 16, 1) }
        val tiles = TileMapNode("Level")
        tiles.tileMap = map
        tiles.textureId = Pack.TILES
        world.addChild(tiles)

        val camera = CameraNode("Camera")
        camera.position = Vec2(240f, 135f)
        camera.followNodePath = "Gameplay/Player"
        scene.root.addChild(camera)

        val canvas = CanvasLayer("HUD")
        val hint = LabelControl("Hint")
        hint.text = "ARROWS / A-D TO MOVE  -  SPACE TO JUMP"
        hint.align = dev.lumen2d.core.render.TextAlign.CENTER
        hint.anchorLeft = 0f; hint.anchorRight = 1f; hint.anchorTop = 0f
        hint.offsetTop = 10f
        hint.minSize = Vec2(480f, 12f)
        canvas.addChild(hint)
        scene.root.child("UI")!!.addChild(canvas)

        val behaviour = ScriptNode("Behaviour")
        behaviour.scriptPath = "scripts/player.lumen"
        world.addChild(behaviour)

        project.saveScene(scene, "scenes/main.scene.json")
        project.saveText("scripts/player.lumen", HELLO_SCRIPT)
        project.saveText("README.md", helloReadme())
    }

    // --------------------------------------------------------------- pixel-platformer

    /** A complete platformer: level, coins, a patrolling slime, a goal, HUD, sound and a menu. */
    private fun pixelPlatformer() = create(
        "pixel-platformer", "Pixel Platformer",
        "A complete 2D platformer: tilemap level, coins, an enemy, a goal flag and a HUD.",
    ) { project ->
        val level = Scene.empty("Level1")
        val world = level.root.child("Gameplay")!!

        // ---- level geometry -------------------------------------------------
        val map = TileMapData(tileWidth = 16, tileHeight = 16)
        map.tileSet.textureId = Pack.TILES
        map.tileSet.tileCount = 12
        map.tileSet.columns = 4
        map.tileSet.tileMeta(0).collision = TileCollision.FULL
        map.tileSet.tileMeta(1).collision = TileCollision.FULL
        map.tileSet.tileMeta(2).collision = TileCollision.FULL
        map.tileSet.tileMeta(3).collision = TileCollision.PLATFORM
        map.tileSet.tileMeta(3).oneWay = true
        map.tileSet.tileMeta(6).hazard = 25f

        val solid = map.ensureLayer("Solid", 60, 18)
        val decor = map.ensureLayer("Decor", 60, 18)
        decor.collisionEnabled = false
        for (x in 0 until 60) { solid.set(x, 15, 0); solid.set(x, 16, 1); solid.set(x, 17, 1) }
        for (x in 14..16) for (y in 15..17) solid.set(x, y, -1)      // pit
        for (x in 30..32) for (y in 15..17) solid.set(x, y, -1)      // second pit
        for (x in 6..9) solid.set(x, 12, 3)
        for (x in 19..23) solid.set(x, 11, 3)
        for (x in 35..39) solid.set(x, 12, 2)
        for (x in 44..47) solid.set(x, 10, 3)
        for (x in 24..26) solid.set(x, 14, 6)                        // spikes
        for (x in 0 until 60) if (x % 9 == 5) decor.set(x, 14, 11)

        val tiles = TileMapNode("Level")
        tiles.tileMap = map
        tiles.textureId = Pack.TILES
        world.addChild(tiles)

        // ---- player ---------------------------------------------------------
        val player = CharacterBody2DNode("Player")
        player.position = Vec2(48f, 200f)
        player.addToGroup("player")
        player.addChild(CollisionShape2DNode("Body").also { it.size = Vec2(10f, 18f) })
        val playerSprite = AnimatedSprite2D("Sprite")
        playerSprite.textureId = Pack.PLAYER
        playerSprite.hframes = 4
        playerSprite.vframes = 2
        playerSprite.fps = 10f
        playerSprite.frames = (0 until 8).map { it.toString() }.toMutableList()
        player.addChild(playerSprite)
        world.addChild(player)

        // ---- pickups, enemy, goal -------------------------------------------
        val coinCells = listOf(6 to 11, 8 to 11, 20 to 10, 22 to 10, 27 to 13, 36 to 11, 38 to 11, 45 to 9, 47 to 9)
        for ((index, cell) in coinCells.withIndex()) {
            val coin = AnimatedSprite2D("Coin${index + 1}")
            coin.textureId = Pack.COIN
            coin.hframes = 4
            coin.fps = 10f
            coin.position = Vec2(cell.first * 16f + 8f, cell.second * 16f + 8f)
            coin.addToGroup("coin")
            world.addChild(coin)
        }

        val slime = AnimatedSprite2D("Slime")
        slime.textureId = Pack.SLIME
        slime.hframes = 4
        slime.fps = 6f
        slime.position = Vec2(400f, 224f)
        slime.addToGroup("enemy")
        world.addChild(slime)

        val goal = Sprite2D("Goal")
        goal.textureId = Pack.FLAG
        goal.position = Vec2(900f, 200f)
        goal.addToGroup("goal")
        world.addChild(goal)

        val dust = ParticlesNode("Dust")
        dust.setProperty("particleTextureId", Pack.SPARK)
        dust.setProperty("amount", 24)
        dust.setProperty("lifetime", 0.6f)
        dust.setProperty("speed", 60f)
        dust.setProperty("spread", 1.5f)
        dust.setProperty("direction", -1.5f)
        dust.setProperty("gravity", Vec2(0f, 160f))
        dust.setProperty("startSize", 5f)
        dust.setProperty("endSize", 0f)
        dust.setProperty("emitting", false)
        dust.position = Vec2(48f, 232f)
        world.addChild(dust)

        val camera = CameraNode("Camera")
        camera.position = Vec2(240f, 135f)
        camera.followNodePath = "Gameplay/Player"
        camera.limitEnabled = true
        camera.limitRect = Rect(0f, 0f, 60 * 16f, 18 * 16f)
        camera.smoothingEnabled = true
        camera.smoothing = 0.25f
        level.root.addChild(camera)

        // ---- HUD ------------------------------------------------------------
        val canvas = CanvasLayer("HUD")
        val coinBar = HudBarControl("CoinBar")
        coinBar.iconTexture = Pack.COIN_ICON
        coinBar.iconSize = 9f
        coinBar.setCount(0, coinCells.size)
        coinBar.anchorLeft = 0f; coinBar.anchorTop = 0f
        coinBar.offsetLeft = 8f; coinBar.offsetTop = 6f
        coinBar.minSize = Vec2(120f, 12f)
        canvas.addChild(coinBar)

        val hearts = ProgressBarControl("Hearts")
        hearts.value = 1f
        hearts.fillColor = Color.fromHex("#FF6B6B")
        hearts.anchorLeft = 1f; hearts.anchorRight = 1f; hearts.anchorTop = 0f
        hearts.offsetLeft = -104f; hearts.offsetRight = -8f; hearts.offsetTop = 8f
        hearts.minSize = Vec2(96f, 8f)
        canvas.addChild(hearts)

        val banner = LabelControl("Banner")
        banner.text = ""
        banner.align = dev.lumen2d.core.render.TextAlign.CENTER
        banner.anchorLeft = 0f; banner.anchorRight = 1f; banner.anchorTop = 0f
        banner.offsetTop = 96f
        banner.minSize = Vec2(480f, 20f)
        canvas.addChild(banner)

        canvas.addChild(touchButton("JumpButton", "jump", "JUMP", 404f, -64f, 52f))
        canvas.addChild(touchButton("LeftButton", "move_left", "<", 16f, -64f, 44f))
        canvas.addChild(touchButton("RightButton", "move_right", ">", 70f, -64f, 44f))
        level.root.child("UI")!!.addChild(canvas)

        val behaviour = ScriptNode("Behaviour")
        behaviour.scriptPath = "scripts/game.lumen"
        world.addChild(behaviour)

        project.saveScene(level, "scenes/level_1.scene.json")
        project.saveScene(menuScene("Main Menu", "PIXEL PLATFORMER"), "scenes/menu.scene.json")
        project.saveText("scripts/game.lumen", PLATFORMER_SCRIPT)
        project.saveText("scripts/menu.lumen", MENU_SCRIPT)
        project.saveText("README.md", platformerReadme())
        project.config.startScene = "scenes/menu.scene.json"
    }

    private fun touchButton(name: String, action: String, label: String, left: Float, top: Float, size: Float): TouchButtonControl {
        val button = TouchButtonControl(name)
        button.action = action
        button.label = label
        button.anchorTop = 1f; button.anchorBottom = 1f
        button.offsetLeft = left; button.offsetRight = left + size
        button.offsetTop = top; button.offsetBottom = top + size
        return button
    }

    // ------------------------------------------------------------------ neon-shooter

    /** A vertical shooter: instanced prefabs, waves, particles, score and restart. */
    private fun neonShooter() = create(
        "neon-shooter", "Neon Shooter",
        "A vertical shooter showing prefab instancing, waves, particles and a score HUD.",
    ) { project ->
        val scene = Scene.empty("Space")
        val world = scene.root.child("Gameplay")!!

        val stars = Sprite2D("Stars")
        stars.textureId = Pack.HILLS
        stars.modulate = Color(0.22f, 0.26f, 0.5f)
        stars.position = Vec2(240f, 160f)
        world.addChild(stars)

        val ship = CharacterBody2DNode("Ship")
        ship.position = Vec2(240f, 210f)
        ship.addChild(CollisionShape2DNode("Body").also { it.size = Vec2(14f, 14f) })
        val shipSprite = Sprite2D("Sprite")
        shipSprite.textureId = Pack.PLAYER
        shipSprite.hframes = 4
        shipSprite.vframes = 2
        shipSprite.frame = 1
        ship.addChild(shipSprite)
        world.addChild(ship)

        val muzzle = ParticlesNode("Muzzle")
        muzzle.setProperty("particleTextureId", Pack.SPARK)
        muzzle.setProperty("amount", 12)
        muzzle.setProperty("lifetime", 0.3f)
        muzzle.setProperty("speed", 90f)
        muzzle.setProperty("startColor", Color.fromHex("#8CE9FF"))
        muzzle.setProperty("endColor", Color(0.4f, 0.7f, 1f, 0f))
        muzzle.setProperty("blend", "ADD")
        muzzle.setProperty("emitting", false)
        muzzle.position = Vec2(240f, 198f)
        world.addChild(muzzle)

        val camera = CameraNode("Camera")
        camera.position = Vec2(240f, 135f)
        scene.root.addChild(camera)

        val canvas = CanvasLayer("HUD")
        val score = LabelControl("Score")
        score.text = "SCORE 0"
        score.anchorLeft = 0f; score.anchorTop = 0f
        score.offsetLeft = 8f; score.offsetTop = 8f
        score.minSize = Vec2(180f, 12f)
        canvas.addChild(score)
        val lives = HudBarControl("Lives")
        lives.iconTexture = Pack.COIN_ICON
        lives.iconSize = 9f
        lives.setCount(3, 3)
        lives.anchorLeft = 1f; lives.anchorRight = 1f; lives.anchorTop = 0f
        lives.offsetLeft = -100f; lives.offsetRight = -8f; lives.offsetTop = 6f
        lives.minSize = Vec2(92f, 12f)
        canvas.addChild(lives)
        scene.root.child("UI")!!.addChild(canvas)

        // Prefabs: the body is the scene root, so `spawn()` hands the script a physics body.
        val bullet = CharacterBody2DNode("Bullet")
        bullet.addToGroup("bullet")
        bullet.addChild(CollisionShape2DNode("Body").also { it.size = Vec2(4f, 10f) })
        val bulletSprite = Sprite2D("Sprite")
        bulletSprite.textureId = Pack.COIN_ICON
        bulletSprite.drawSize = Vec2(4f, 10f)
        bulletSprite.modulate = Color.fromHex("#8CE9FF")
        bullet.addChild(bulletSprite)
        project.saveScene(Scene("Bullet", bullet), "scenes/prefabs/bullet.scene.json")

        val enemy = AnimatedSprite2D("Enemy")
        enemy.textureId = Pack.SLIME
        enemy.hframes = 4
        enemy.fps = 7f
        enemy.addToGroup("enemy")
        project.saveScene(Scene("Enemy", enemy), "scenes/prefabs/enemy.scene.json")

        val behaviour = ScriptNode("Behaviour")
        behaviour.scriptPath = "scripts/game.lumen"
        world.addChild(behaviour)

        project.saveScene(scene, "scenes/space.scene.json")
        project.saveText("scripts/game.lumen", SHOOTER_SCRIPT)
        project.saveText("README.md", shooterReadme())
        project.config.startScene = "scenes/space.scene.json"
    }

    // ------------------------------------------------------------------------- extras

    /** A title screen with a start button, used by the platformer sample. */
    private fun menuScene(name: String, title: String): Scene {
        val scene = Scene.empty(name)
        val backdrop = Sprite2D("Backdrop")
        backdrop.textureId = Pack.HILLS
        backdrop.position = Vec2(240f, 190f)
        backdrop.modulate = Color(0.55f, 0.6f, 0.9f)
        scene.root.child("Environment")!!.addChild(backdrop)

        val canvas = CanvasLayer("Menu")
        val heading = LabelControl("Title")
        heading.text = title
        heading.align = dev.lumen2d.core.render.TextAlign.CENTER
        heading.anchorLeft = 0f; heading.anchorRight = 1f; heading.anchorTop = 0f
        heading.offsetTop = 70f
        heading.minSize = Vec2(480f, 20f)
        canvas.addChild(heading)

        val hint = LabelControl("Hint")
        hint.text = "start a level, collect the coins, reach the flag"
        hint.align = dev.lumen2d.core.render.TextAlign.CENTER
        hint.anchorLeft = 0f; hint.anchorRight = 1f; hint.anchorTop = 0f
        hint.offsetTop = 96f
        hint.minSize = Vec2(480f, 12f)
        canvas.addChild(hint)

        val start = TouchButtonControl("Start")
        start.action = "ui_accept"
        start.label = "START"
        start.round = false
        start.anchorLeft = 0.5f; start.anchorRight = 0.5f; start.anchorTop = 0f
        start.offsetLeft = -48f; start.offsetRight = 48f
        start.offsetTop = 140f; start.offsetBottom = 164f
        canvas.addChild(start)
        scene.root.child("UI")!!.addChild(canvas)
        return scene
    }

    private fun helloReadme() = """
        # Hello Lumen2D

        The smallest complete Lumen2D game: a tilemap floor, a character with an animated sprite, a
        camera that follows it and a script that reads the input map.

        ```sh
        lumen2d --game sample-games/hello-lumen2d      # play it
        lumen2d --check sample-games/hello-lumen2d     # headless smoke test
        lumen2d --new "My Game"                        # start your own
        ```

        | file | what it is |
        |------|------------|
        | `scripts/player.lumen` | movement, gravity and jumping |
        | `scenes/main.scene.json` | the level — open it in Lumen Studio |
        | `project.lumen` | resolution, gravity, audio buses, asset packs |
        | `input_map.json` | keyboard and touch bindings |
    """.trimIndent() + "\n"

    private fun platformerReadme() = """
        # Pixel Platformer

        A complete platformer: a tilemap level with pits, one-way platforms and spikes, coins to
        collect, a patrolling slime, a goal flag, a HUD, a title screen and sound effects.

        ```sh
        lumen2d --game sample-games/pixel-platformer
        ```

        Controls: **A/D** or **←/→** move, **Space/Z** jump, **Esc** pauses.
        The same project shows touch buttons on phones — nothing in the scene is desktop-specific.

        Try editing `scripts/game.lumen` while the game runs in Lumen Studio: the script reloads
        and the level keeps its state.

        | file | what it is |
        |------|------------|
        | `scenes/menu.scene.json` | title screen (the start scene) |
        | `scenes/level_1.scene.json` | the level: tilemap, coins, slime, spikes, goal flag |
        | `scripts/menu.lumen` | any accept/fire press starts the level |
        | `scripts/game.lumen` | movement, coyote time, coin pickups, HUD, game over |
        | `project.lumen` | resolution, gravity, audio buses, asset packs |
        | `input_map.json` | keyboard and touch bindings |
    """.trimIndent() + "\n"

    private fun shooterReadme() = """
        # Neon Shooter

        A vertical shooter demonstrating prefab instancing (`spawn`), wave spawning with
        `set_timer`, particle feedback, a score HUD and a restart flow.

        ```sh
        lumen2d --game sample-games/neon-shooter
        ```

        Controls: **←/→** or **A/D** steer, **Space/X** fires, **Space** restarts after a game over.

        | file | what it is |
        |------|------------|
        | `scenes/space.scene.json` | the playable scene (the start scene) |
        | `scenes/prefabs/bullet.scene.json` | bullet prefab instanced by `spawn` |
        | `scenes/prefabs/enemy.scene.json` | enemy prefab, picked per wave |
        | `scripts/game.lumen` | firing, wave spawning, collisions, score, restart |
        | `project.lumen` | resolution, gravity, audio buses, asset packs |
        | `input_map.json` | keyboard and touch bindings |
    """.trimIndent() + "\n"

    // ------------------------------------------------------------------------ scripts

    private val HELLO_SCRIPT = """
        # Hello Lumen2D — the player controller from the quick-start guide.
        export var speed = 110.0
        export var jump_force = 300.0
        var coyote = 0.0

        func ready() {
            print("Hello from LumenScript!")
        }

        func physics_process(delta) {
            var body = get_node("Player")
            var axis = input.axis("move")

            body.velocity.x = axis.x * speed
            body.velocity.y += 900.0 * delta                      # gravity

            if body.on_floor {
                coyote = 0.1                                      # coyote time, in seconds
            } else {
                coyote = max(0.0, coyote - delta)
            }

            if input.just_pressed("jump") and coyote > 0.0 {
                body.velocity.y = -jump_force
                coyote = 0.0
                play_sound("lib://packs/base/audio/jump.wav")
            }

            body.move_and_slide(delta)
        }
    """.trimIndent()

    private val MENU_SCRIPT = """
        # Title screen: any accept/fire press starts the level.
        func process(delta) {
            if input.just_pressed("ui_accept") or input.just_pressed("fire") or input.just_pressed("jump") {
                change_scene("scenes/level_1.scene.json")
            }
        }
    """.trimIndent()

    private val PLATFORMER_SCRIPT = """
        # Pixel Platformer — the whole game loop in one script.
        export var speed = 105.0
        export var jump_force = 320.0
        var total_coins = 9
        var coins_found = 0
        var health = 1.0
        var facing = 1.0
        var coyote = 0.0
        var jump_buffer = 0.0
        var invulnerable = 0.0
        var slime_direction = -1.0
        var won = false

        func ready() {
            print("Collect the coins and reach the flag.")
        }

        func physics_process(delta) {
            var player = get_node("Player")
            var sprite = get_node("Player/Sprite")

            if won {
                player.velocity.x = 0.0
                player.move_and_slide(delta)
                return
            }

            # --- horizontal movement
            var axis = input.axis("move")
            if abs(axis.x) > 0.15 {
                facing = sign(axis.x)
                sprite.flipX = facing < 0.0
            }
            player.velocity.x = axis.x * speed

            # --- jump with coyote time and input buffering
            if player.on_floor {
                coyote = 0.1
                if abs(axis.x) > 0.15 {
                    sprite.set_frame(6.0)
                } else {
                    sprite.set_frame(0.0)
                }
            } else {
                coyote = max(0.0, coyote - delta)
                sprite.set_frame(4.0)
            }
            if input.just_pressed("jump") {
                jump_buffer = 0.12
            } else {
                jump_buffer = max(0.0, jump_buffer - delta)
            }
            if jump_buffer > 0.0 and coyote > 0.0 {
                player.velocity.y = -jump_force
                jump_buffer = 0.0
                coyote = 0.0
                play_sound("lib://packs/base/audio/jump.wav")
                var dust = get_node("Dust")
                dust.position = player.position
                dust.set("emitting", true)
                set_timer(0.25, "stop_dust")
            }

            # --- gravity, then move the body through the world
            if not player.on_floor {
                player.velocity.y = min(player.velocity.y + 900.0 * delta, 480.0)
            }
            player.move_and_slide(delta)

            if player.position.y > 300.0 {
                hurt(0.34)
                player.position = vec2(48.0, 200.0)
                player.velocity = vec2(0.0, 0.0)
            }

            update_coins()
            update_slime(delta)
            update_goal()
        }

        func process(delta) {
            invulnerable = max(0.0, invulnerable - delta)
        }

        func stop_dust() {
            get_node("Dust").set("emitting", false)
        }

        func update_coins() {
            var player = get_node("Player")
            for coin in find_children("Coin*", "*", true) {
                if coin.is_in_group("coin") and coin.visible {
                    if coin.global_position.distance_to(player.global_position) < 14.0 {
                        coin.visible = false
                        coins_found += 1
                        play_sound("lib://packs/base/audio/coin.wav")
                        get_node("UI/HUD/CoinBar").set_count(coins_found, total_coins)
                        if coins_found >= total_coins {
                            show_banner("ALL COINS FOUND!")
                        }
                    }
                }
            }
        }

        func update_slime(delta) {
            var slime = get_node("Slime")
            var player = get_node("Player")
            slime.position.x += slime_direction * 30.0 * delta
            if slime.position.x < 352.0 or slime.position.x > 460.0 {
                slime_direction = -slime_direction
            }
            if abs(slime.position.x - player.position.x) < 14.0 and abs(slime.position.y - player.position.y) < 20.0 {
                hurt(0.34)
                player.velocity.y = -170.0
            }
        }

        func hurt(amount) {
            if invulnerable > 0.0 {
                return
            }
            health = max(0.0, health - amount)
            invulnerable = 1.0
            get_node("UI/HUD/Hearts").set_value(health)
            play_sound("lib://packs/base/audio/hurt.wav")
            flash(get_node("Player"), 0.25)
            if health <= 0.0 {
                health = 1.0
                get_node("UI/HUD/Hearts").set_value(1.0)
                get_node("Player").position = vec2(48.0, 200.0)
                show_banner("OUCH! BACK TO THE START")
            }
        }

        func update_goal() {
            if won {
                return
            }
            var player = get_node("Player")
            var goal = get_node("Goal")
            if abs(player.position.x - goal.position.x) < 18.0 {
                won = true
                show_banner("LEVEL CLEAR!  coins " + str(coins_found) + "/" + str(total_coins))
                play_sound("lib://packs/base/audio/pickup.wav")
                shake_camera(3.0, 0.4)
            }
        }

        func show_banner(text) {
            get_node("UI/HUD/Banner").text = text
            set_timer(3.0, "clear_banner")
        }

        func clear_banner() {
            get_node("UI/HUD/Banner").text = ""
        }
    """.trimIndent()

    private val SHOOTER_SCRIPT = """
        # Neon Shooter — waves, bullets, particles and a score.
        export var speed = 150.0
        export var fire_delay = 0.22
        var score = 0
        var lives = 3
        var fire_timer = 0.0
        var spawn_timer = 0.8
        var elapsed = 0.0
        var game_over = false

        func ready() {
            print("Neon Shooter ready — hold SPACE to fire.")
        }

        func process(delta) {
            if game_over {
                if input.just_pressed("fire") or input.just_pressed("jump") {
                    restart()
                }
                return
            }
            elapsed += delta

            # --- steering
            var ship = get_node("Ship")
            var axis = input.axis("move")
            ship.position.x = clamp(ship.position.x + axis.x * speed * delta, 16.0, 464.0)
            ship.position.y = clamp(ship.position.y - axis.y * speed * 0.6 * delta, 120.0, 240.0)

            # --- shooting
            fire_timer -= delta
            if input.pressed("fire") and fire_timer <= 0.0 {
                fire_timer = fire_delay
                shoot(ship.position)
            }

            move_bullets(delta)
            move_enemies(delta)

            # --- waves get denser over time
            spawn_timer -= delta
            if spawn_timer <= 0.0 {
                spawn_timer = max(0.3, 1.1 - elapsed * 0.02)
                spawn_enemy()
            }
        }

        func shoot(from) {
            var bullet = spawn("scenes/prefabs/bullet.scene.json", vec2(from.x, from.y - 12.0))
            if bullet != null {
                bullet.velocity = vec2(0.0, -260.0)
            }
            var muzzle = get_node("Muzzle")
            muzzle.position = vec2(from.x, from.y - 14.0)
            muzzle.set("emitting", true)
            set_timer(0.08, "stop_muzzle")
            play_sound("lib://packs/base/audio/blip.wav", 0.4)
        }

        func stop_muzzle() {
            get_node("Muzzle").set("emitting", false)
        }

        func spawn_enemy() {
            var enemy = spawn("scenes/prefabs/enemy.scene.json", vec2(16.0 + randf() * 448.0, -10.0))
            if enemy != null {
                enemy.fps = 3.0 + elapsed * 0.05
            }
        }

        func move_enemies(delta) {
            for enemy in find_children("Enemy", "*", true) {
                if not enemy.is_in_group("enemy") {
                    continue
                }
                enemy.position.y += (55.0 + elapsed * 3.0) * delta
                if enemy.position.y > 288.0 {
                    enemy.queue_free()
                    hurt()
                }
            }
        }

        func move_bullets(delta) {
            for bullet in find_children("Bullet", "*", true) {
                bullet.move_and_slide(delta)
                if bullet.position.y < -10.0 {
                    bullet.queue_free()
                    continue
                }
                if hit_enemy(bullet) {
                    bullet.queue_free()
                    score += 10
                    get_node("UI/HUD/Score").text = "SCORE " + str(score)
                    play_sound("lib://packs/base/audio/pickup.wav", 0.6)
                }
            }
        }

        func hit_enemy(bullet) {
            for enemy in find_children("Enemy", "*", true) {
                if enemy.visible and enemy.position.distance_to(bullet.position) < 14.0 {
                    enemy.queue_free()
                    return true
                }
            }
            return false
        }

        func hurt() {
            lives -= 1
            get_node("UI/HUD/Lives").set_count(lives, 3)
            play_sound("lib://packs/base/audio/hurt.wav")
            shake_camera(3.0, 0.25)
            if lives <= 0 {
                game_over = true
                get_node("UI/HUD/Score").text = "GAME OVER - SCORE " + str(score) + " - PRESS SPACE"
            }
        }

        func restart() {
            score = 0
            lives = 3
            elapsed = 0.0
            game_over = false
            get_node("UI/HUD/Score").text = "SCORE 0"
            get_node("UI/HUD/Lives").set_count(3, 3)
            for enemy in find_children("Enemy", "*", true) {
                enemy.queue_free()
            }
        }
    """.trimIndent()
}
