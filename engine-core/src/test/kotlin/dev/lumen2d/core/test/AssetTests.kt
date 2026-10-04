/** Asset pipeline: scanning, metadata, loading, atlases and the project/game layer. */
package dev.lumen2d.core.test

import dev.lumen2d.core.assets.AssetCategory
import dev.lumen2d.core.assets.AssetDatabase
import dev.lumen2d.core.assets.AssetType
import dev.lumen2d.core.audio.AudioClip
import dev.lumen2d.core.audio.WavCodec
import dev.lumen2d.core.game.Game
import dev.lumen2d.core.game.GameConfig
import dev.lumen2d.core.game.Project
import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.platform.HeadlessPlatform
import dev.lumen2d.core.platform.InMemoryFileSystem
import dev.lumen2d.core.render.PixelBuffer
import dev.lumen2d.core.render.PngCodec
import dev.lumen2d.core.render.TextureFilter
import dev.lumen2d.core.util.Json

/** Builds an in-memory asset pack so the whole pipeline can be exercised without a disk. */
private fun assetPackVfs(): InMemoryFileSystem {
    val vfs = InMemoryFileSystem()

    // --- catalogue + sidecar metadata
    vfs.writeText("base/catalog.json", """
        {
          "pack": "base",
          "name": "Lumen2D Base Pack",
          "license": "CC0-1.0",
          "author": "Lumen2D",
          "assets": [
            {"id": "sprites/hero", "path": "sprites/hero.png", "type": "texture", "tags": ["character","hero"], "license": "CC0-1.0", "author": "Lumen2D"},
            {"id": "sprites/atlas", "path": "sprites/atlas.png", "type": "atlas", "tags": ["atlas"], "license": "CC0-1.0"}
          ]
        }
    """.trimIndent())

    val png = PixelBuffer(16, 16)
    for (y in 0 until 16) for (x in 0 until 16) png[x, y] = if ((x / 8 + y / 8) % 2 == 0) 0xFFFF00FF.toInt() else 0xFF00FFFF.toInt()
    vfs.writeBytes("base/sprites/hero.png", PngCodec.encode(png))
    vfs.writeBytes("base/sprites/atlas.png", PngCodec.encode(png))
    vfs.writeText("base/sprites/hero.png.meta.json", """
        {"name":"Hero Sprite","tags":["pixel","hero","16x16"],"license":"CC0-1.0","author":"Lumen2D",
         "source":"generated","import":{"filter":"nearest","pivot":"center"}}
    """.trimIndent())

    vfs.writeText("base/sprites/atlas.json", """
        {"texture":"atlas.png","regions":[
            {"name":"coin_0","x":0,"y":0,"width":8,"height":8},
            {"name":"coin_1","x":8,"y":0,"width":8,"height":8}],
         "animations":{"coin":["coin_0","coin_1"]}}
    """.trimIndent())

    // --- audio
    val samples = ShortArray(4000) { (Math.sin(it / 12.0) * 14000).toInt().toShort() }
    vfs.writeBytes("base/audio/jump.wav", WavCodec.encode(AudioClip("jump", 22050, 1, samples)))
    vfs.writeText("base/audio/jump.wav.meta.json", """{"tags":["sfx","jump"],"license":"CC0-1.0"}""")

    // --- data + scene
    vfs.writeText("base/data/levels.json", """{"levels":[{"name":"Level 1","enemies":3}]}""")
    vfs.writeText("base/data/notes.txt", "not an engine asset")
    vfs.writeText("base/.thumbnails/hero.png", "thumbnail cache must be skipped")
    return vfs
}

fun assetTests() {
    section("asset database")

    test("scanning discovers assets and merges sidecar metadata") {
        val vfs = assetPackVfs()
        val db = AssetDatabase(listOf("lib://" to vfs))
        val result = db.scan(extractMetadata = true)
        check(result.added.any { it.id.contains("hero") }, "hero asset discovered, got ${result.added.map { it.id }}")
        val hero = db.all().first { it.path.contains("hero") }
        eq("Hero Sprite", hero.displayName, "sidecar metadata overrides the default display name")
        check(hero.hasTag("pixel"), "tags from the sidecar")
        eq("CC0-1.0", hero.license)
        check(db.all().any { it.id.endsWith("sprites/hero.png") }, "pack-relative id built, got ${db.all().map { it.id }}")
        check(db.all().none { it.path.contains(".thumbnails") }, "thumbnail cache skipped")
        check(db.all().none { it.path.endsWith(".txt") }, "text files are not engine assets")
        check(db.byPath("base/sprites/hero.png") != null, "assets are indexed by path")
        check(db.all().any { it.type == AssetType.AUDIO }, "audio asset classified")
        check(db.all().any { it.type == AssetType.DATA }, "json data asset classified")
    }

    test("search filters by type, tag and text") {
        val db = AssetDatabase(listOf("lib://" to assetPackVfs()))
        db.scan()
        check(db.search("hero").isNotEmpty(), "text search finds hero")
        check(db.search(tags = listOf("pixel")).isNotEmpty(), "tag search")
        check(db.search("", type = AssetType.TEXTURE).isNotEmpty(), "type search")
        val categories = db.byCategory(AssetCategory.IMAGES)
        check(categories.isNotEmpty(), "sprites category populated")
        check(db.allTags().contains("pixel"), "tag index built")
    }

    test("textures, atlases, audio, fonts and scenes load through the database") {
        val vfs = assetPackVfs()
        val db = AssetDatabase(listOf("lib://" to vfs))
        db.scan()

        val hero = db.all().first { it.path.endsWith("hero.png") }
        val texture = db.loadTexture(hero.id)!!
        eq(16, texture.width)
        eq(16, texture.height)
        eq(TextureFilter.NEAREST, texture.filter, "import settings applied")

        val atlas = db.all().first { it.path.endsWith("atlas.json") }
        val loaded = db.loadTexture(atlas.id)!!
        check(loaded.regions.containsKey("coin_0"), "atlas regions registered, got ${loaded.regions.keys}")
        eq(2, loaded.animations["coin"]?.size, "atlas animation frames")

        val jump = db.all().first { it.path.endsWith("jump.wav") }
        val clip = db.loadAudio(jump.id)!!
        check(clip.frameCount > 1000, "wav decoded")
        eq(22050, clip.sampleRate)

        val data = db.all().first { it.path.endsWith("levels.json") }
        val json = db.loadJson(data.id)!!
        check(json.toString().contains("Level 1"), "json asset loaded")

        val slot = db.loadAudio(jump.id)!!
        check(slot === clip || slot.frameCount == clip.frameCount, "audio cached between loads")
    }

    test("asset hashes, sizes and dependency graph") {
        val db = AssetDatabase(listOf("lib://" to assetPackVfs()))
        db.scan()
        val hero = db.all().first { it.path.endsWith("hero.png") }
        val atlas = db.all().first { it.path.endsWith("atlas.json") }
        db.reload(hero.id)
        val reloaded = db.get(hero.id)!!
        eq(16, reloaded.hash.length, "16-hex-character hash")
        eq(16, db.computeHash(hero.id).length, "recomputed hash is stable")
        check(reloaded.sizeBytes > 0, "size computed")
        db.registerDependency(hero.id, atlas.id)
        check(db.dependents(atlas.id).any { it.id == hero.id }, "dependency tracked")
        check(db.transitiveDependencies(hero.id).isNotEmpty(), "transitive dependencies")
        check(db.unusedAssets().isNotEmpty(), "unused asset report")
    }

    test("external images can be imported into a project") {
        val db = AssetDatabase(listOf("project://" to InMemoryFileSystem()))
        val buffer = PixelBuffer(4, 4)
        for (y in 0 until 4) for (x in 0 until 4) buffer[x, y] = 0xFF123456.toInt()
        val decoded = db.decodeExternalPng(PngCodec.encode(buffer))!!
        eq(4, decoded.width)
        eq(0xFF123456.toInt(), decoded[1, 1])
    }

    test("empty databases behave predictably") {
        val db = AssetDatabase(emptyList())
        eq(0, db.size)
        check(db.search("anything").isEmpty(), "search on empty database is empty")
        check(db.loadAudio("missing") == null, "missing audio returns null")
        check(db.get("missing") == null, "missing asset lookup returns null")
    }

    section("projects")

    test("creating a project scaffolds folders, manifest and input map") {
        val vfs = InMemoryFileSystem()
        val project = Project.create(vfs, "my-game", "My Game")
        check(vfs.exists("my-game/project.lumen"), "manifest written")
        check(vfs.exists("my-game/scenes/main.scene.json"), "start scene written")
        check(vfs.exists("my-game/input_map.json"), "input map written")
        for (folder in Project.STANDARD_FOLDERS) {
            check(vfs.isDirectory("my-game/$folder"), "folder $folder created")
        }

        val reopened = Project.open(vfs, "my-game")
        truthy(reopened, "project reopens")
        eq("My Game", reopened!!.name)
        eq("My Game", reopened.config.title)
        val index = reopened.index()
        check(index.scenes.contains("scenes/main.scene.json"), "index finds the scene")
        check(reopened.loadScene("scenes/main.scene.json") != null, "scene loads")
        check(reopened.loadInputMap().isNotEmpty(), "input bindings load")
        check(reopened.summary().contains("scenes"), "summary string")
    }

    test("opening a missing project returns null") {
        check(Project.open(InMemoryFileSystem(), "nope") == null, "no manifest, no project")
    }

    test("project config round-trips through the manifest") {
        val vfs = InMemoryFileSystem()
        val project = Project.create(vfs, "tuned", "Tuned")
        project.config.designWidth = 320
        project.config.designHeight = 180
        project.config.gravity = Vec2(0f, 500f)
        project.config.tags.add("secret")
        project.save()

        val reopened = Project.open(vfs, "tuned")!!
        eq(320, reopened.config.designWidth)
        eq(180, reopened.config.designHeight)
        near(500f, reopened.config.gravity.y, 0.001f)
        check(reopened.config.tags.contains("secret"), "tags persisted")
    }

    section("game runtime")

    test("headless game boots, runs frames and renders") {
        val vfs = InMemoryFileSystem()
        val project = Project.create(vfs, "demo", "Demo")
        val game = Game(HeadlessPlatform, project)
        game.attachRenderer(game.useSoftwareRenderer())
        game.start()
        check(game.isReady, "game reports ready")
        game.runHeadless(20)
        eq(20L, game.frameCount)
        check(game.captureFrame() != null, "software renderer captures frames")
        game.stop()
    }

    test("game switches scenes and finds nodes") {
        val vfs = InMemoryFileSystem()
        val project = Project.create(vfs, "switch", "Switch")
        val root = dev.lumen2d.core.scene.Node("Level")
        val player = dev.lumen2d.core.scene.Node2D("Player")
        player.position = Vec2(32f, 16f)
        root.addChild(player)
        project.saveScene(dev.lumen2d.core.scene.Scene("level_2", root), "scenes/level_2.scene.json")

        val game = Game(HeadlessPlatform, project)
        game.attachRenderer(game.useSoftwareRenderer())
        game.start()
        check(game.currentScene != null, "start scene loaded")
        check(game.changeScene("scenes/level_2.scene.json"), "scene switch succeeded")
        game.runHeadless(3)
        val found = game.currentScene!!.root.descendants().firstOrNull { it.name == "Player" }
        truthy(found, "node from the loaded scene is in the tree")
    }

    test("save games capture and restore node state") {
        val vfs = InMemoryFileSystem()
        val project = Project.create(vfs, "saves", "Saves")
        val root = dev.lumen2d.core.scene.Node("Main")
        val probe = Probe("Probe")
        probe.addToGroup(dev.lumen2d.core.game.SAVE_STATE_GROUP)
        root.addChild(probe)
        project.saveScene(dev.lumen2d.core.scene.Scene("main", root), "scenes/main.scene.json")

        val game = Game(HeadlessPlatform, project)
        game.attachRenderer(game.useSoftwareRenderer())
        game.start()
        // start() instantiates the scene, so the save/load cycle runs against the live node.
        val live = game.tree.root.descendants().first { it is Probe } as Probe
        live.addToGroup(dev.lumen2d.core.game.SAVE_STATE_GROUP)
        live.value = 123f
        check(game.saveGame(0), "save succeeded")
        live.value = 0f
        check(game.loadGame(0), "load succeeded")
        near(123f, live.value, 0.001f, "property restored from the slot")
        eq(1, game.saveSlotsUsed().count { it }, "one slot in use")
        eq("scenes/main.scene.json", project.config.startScene)
    }

    test("stretch maths and settings persistence") {
        val game = Game(HeadlessPlatform, Project.create(InMemoryFileSystem(), "cfg", "Cfg"))
        game.config.designWidth = 320
        game.config.designHeight = 180
        game.config.stretchMode = dev.lumen2d.core.game.StretchMode.FIT
        val view = game.viewportRect(640f, 360f)
        near(640f, view.w, 0.01f)
        near(360f, view.h, 0.01f)
        game.saveSettings()
        eq("Cfg", HeadlessPlatform.settingsStore["game.title"])
    }

    test("performance summary exposes the profiler counters") {
        val game = Game(HeadlessPlatform, Project.create(InMemoryFileSystem(), "perf", "Perf"))
        game.attachRenderer(game.useSoftwareRenderer())
        game.start()
        game.runHeadless(5)
        val summary = game.performanceSummary()
        check(summary.containsKey("fps"), "fps present")
        check(summary.containsKey("drawCalls"), "draw calls present")
        check(summary["assets"]!! >= 0f, "asset count present")
    }

    test("game config defaults are sane") {
        val config = GameConfig()
        check(config.designWidth > 0 && config.designHeight > 0, "design resolution set")
        check(config.startScene.endsWith(".scene.json"), "start scene path")
        eq(Color(0.05f, 0.06f, 0.10f), config.clearColor)
        check(config.physicsLayers.isNotEmpty(), "physics layers listed")
        val json = Json.stringify(config.serialize())
        check(json.contains("designWidth"), "config serialises")
    }
}
