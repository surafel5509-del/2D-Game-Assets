/**
 * Lumen2D — desktop entry point.
 *
 * One binary covers the whole toolchain:
 *
 * ```
 * lumen2d                        # play the sample project (or --game <folder>)
 * lumen2d --game my-game         # run a project from a folder
 * lumen2d --demo all --out dir   # render documentation/CI screenshots of every demo
 * lumen2d --new "My Game" --dir .  # scaffold a new project
 * lumen2d --check my-game        # headless smoke test: boot, run frames, report health
 * lumen2d --export-assets dir    # rebuild the bundled asset library (packs + provenance index)
 * lumen2d --import-sources a.zip # vendor a third-party asset archive into assets-library/sources
 * ```
 *
 * The screenshot mode is what `tools/build-local.sh preview` and the CI workflow use: it runs the
 * real engine (software renderer, no GPU) over scripted scenes and writes PNGs to
 * `docs/preview/generated`, which keeps the documentation honest.
 */
package dev.lumen2d.desktop

import dev.lumen2d.core.game.Game
import dev.lumen2d.core.game.GameConfig
import dev.lumen2d.core.game.Project
import dev.lumen2d.core.platform.FileFileSystem
import dev.lumen2d.core.util.Log
import dev.lumen2d.core.util.LogLevel
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = DesktopOptions.parse(args)
    Log.minLevel = if (options.verbose) LogLevel.DEBUG else LogLevel.INFO

    when (options.mode) {
        DesktopMode.PREVIEW -> runPreviews(options)
        DesktopMode.NEW_PROJECT -> runNewProject(options)
        DesktopMode.CHECK -> runCheck(options.project, options.scene, options.shot)
        DesktopMode.EXPORT_ASSETS -> runExportAssets(options.out)
        DesktopMode.EXPORT_SAMPLES -> runExportSamples(options.out)
        DesktopMode.IMPORT_SOURCES -> runImportSources(options.project, options.dir)
        DesktopMode.HELP -> printUsage()
        DesktopMode.PLAY -> runGame(options)
    }
}

enum class DesktopMode {
    PLAY, PREVIEW, NEW_PROJECT, CHECK, EXPORT_ASSETS, EXPORT_SAMPLES, IMPORT_SOURCES, HELP,
}

/** Command line for the desktop host. */
class DesktopOptions(
    var mode: DesktopMode = DesktopMode.PLAY,
    var project: String = "",
    var demo: String = "all",
    var out: String = "docs/preview/generated",
    var name: String = "My Game",
    var dir: String = ".",
    var width: Int = 0,
    var height: Int = 0,
    var scale: Int = 3,
    var frames: Int = 24,
    var scene: String = "",
    var shot: String = "",
    var verbose: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): DesktopOptions {
            val options = DesktopOptions()
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                fun value(): String {
                    val next = args.getOrNull(index + 1)
                    if (next == null || next.startsWith("--")) return ""
                    index++
                    return next
                }
                when (arg) {
                    "--game", "-g" -> { options.mode = DesktopMode.PLAY; options.project = value() }
                    "--demo", "-d" -> { options.mode = DesktopMode.PREVIEW; options.demo = value().ifEmpty { "all" } }
                    "--out", "-o" -> options.out = value().ifEmpty { options.out }
                    "--new" -> { options.mode = DesktopMode.NEW_PROJECT; options.name = value().ifEmpty { options.name } }
                    "--dir" -> options.dir = value().ifEmpty { "." }
                    "--check" -> { options.mode = DesktopMode.CHECK; options.project = value() }
                    "--export-samples" -> {
                        options.mode = DesktopMode.EXPORT_SAMPLES
                        options.out = value().ifEmpty { "sample-games" }
                    }
                    "--export-assets" -> {
                        options.mode = DesktopMode.EXPORT_ASSETS
                        options.out = value().ifEmpty { "assets-library/packs" }
                    }
                    "--width" -> options.width = value().toIntOrNull() ?: options.width
                    "--height" -> options.height = value().toIntOrNull() ?: options.height
                    "--scale" -> options.scale = value().toIntOrNull()?.coerceIn(1, 8) ?: options.scale
                    "--frames" -> options.frames = value().toIntOrNull()?.coerceAtLeast(1) ?: options.frames
                    "--scene" -> options.scene = value()
                    "--shot" -> options.shot = value()
                    "--import-sources" -> {
                        options.mode = DesktopMode.IMPORT_SOURCES
                        options.project = value()
                    }
                    "--verbose", "-v" -> options.verbose = true
                    "--help", "-h" -> options.mode = DesktopMode.HELP
                }
                index++
            }
            return options
        }
    }
}

private fun printUsage() {
    println(
        """
        Lumen2D desktop host

          lumen2d [--game <folder>]            play a project
          lumen2d --demo <name|all> [--out d]  render preview screenshots
          lumen2d --new "<name>" [--dir d]     scaffold a new project
          lumen2d --check <folder>             headless smoke test
            [--scene file] [--shot out.png]
          lumen2d --export-assets <dir>        write the bundled asset library
          lumen2d --export-samples <dir>       write the sample games
          lumen2d --import-sources <zip>       vendor a third-party asset archive
            [--dir assets-library/sources]
          lumen2d --help

        Options: --width N --height N --scale N --frames N --verbose
        """.trimIndent(),
    )
}

// ---------------------------------------------------------------------------- playing

private fun runGame(options: DesktopOptions) {
    val directory = if (options.project.isEmpty()) findBundledProject() else File(options.project)
    if (directory == null || !directory.isDirectory) {
        System.err.println("No project found. Pass --game <folder> (or run --new to create one).")
        exitProcess(2)
    }
    val project = openProject(directory)
    if (project == null) {
        System.err.println("${directory.absolutePath} does not contain ${Project.MANIFEST}.")
        exitProcess(2)
    }
    if (options.width > 0) project.config.designWidth = options.width
    if (options.height > 0) project.config.designHeight = options.height

    val platform = DesktopPlatform()
    val game = Game(platform, project)
    game.attachRenderer(dev.lumen2d.core.render.SoftwareRenderer(project.config.designWidth, project.config.designHeight))
    installLumenScripts(game)

    if (options.verbose || !hasDisplay) {
        // No display: run headless for a fixed number of frames so CI still exercises the game.
        game.start()
        repeat(options.frames) { game.frame(1f / 60f) }
        println("Ran ${game.tree.frameCount} headless frame(s) — ${game.performanceSummary()}")
        return
    }
    LumenWindow(game, title = "${project.config.title} — Lumen2D", scale = options.scale).open()
}

/** Loads a project folder into a [Project] backed by real files. */
private fun openProject(directory: File): Project? {
    val vfs = FileFileSystem(directory)
    val project = Project.open(vfs, "", "project://") ?: Project.open(vfs, directory.name, "project://")
    return project
}

/** Looks for the sample games shipped next to the engine (repo checkout or installed copy). */
private fun findBundledProject(): File? {
    val candidates = listOf(
        File("sample-games/hello-lumen2d"),
        File("../sample-games/hello-lumen2d"),
        File(System.getProperty("user.home"), ".lumen2d/content/sample-games/hello-lumen2d"),
    )
    return candidates.firstOrNull { File(it, Project.MANIFEST).isFile }?.let { File(it.absolutePath) }
}

// ---------------------------------------------------------------------- new project

private fun runNewProject(options: DesktopOptions) {
    val target = File(options.dir, slug(options.name))
    if (target.exists() && target.list()?.isNotEmpty() == true) {
        System.err.println("${target.absolutePath} already exists and is not empty.")
        exitProcess(2)
    }
    target.mkdirs()
    val config = GameConfig().also {
        it.title = options.name
        it.packageId = "dev.lumen2d.${slug(options.name).replace('-', '_')}"
    }
    val project = Project.create(FileFileSystem(target), "", options.name, config)
    sampleContent(project)
    println("Created ${project.config.title} in ${target.absolutePath}")
    println("Run it with:  lumen2d --game ${target.absolutePath}")
}

private fun slug(name: String): String = name.lowercase()
    .map { if (it.isLetterOrDigit()) it else '-' }
    .joinToString("")
    .replace(Regex("-+"), "-")
    .trim('-')
    .ifEmpty { "lumen-game" }

/** Writes a small but complete starter: a scene, a script and a README. */
private fun sampleContent(project: Project) {
    val scene = dev.lumen2d.core.scene.Scene.empty("Main")
    val root = scene.root
    val gameplay = root.child("Gameplay") ?: root
    val player = dev.lumen2d.core.scene.Sprite2D("Player")
    player.textureId = "player.png"
    player.position = dev.lumen2d.core.math.Vec2(project.config.designWidth / 2f, project.config.designHeight / 2f)
    gameplay.addChild(player)
    project.saveScene(scene, "scenes/main.scene.json")
    project.vfs.mkdirs(project.path("scripts"))
    project.saveText(
        "scripts/player.lumen",
        """
        # Player controller for ${project.config.title}.
        export var speed = 90.0

        func ready() {
            print("player ready")
        }

        func process(delta) {
            var direction = input.axis("move")
            position.x += direction.x * speed * delta
            position.y += direction.y * speed * delta
        }
        """.trimIndent(),
    )
    project.saveText("README.md", "# ${project.config.title}\n\nCreated with Lumen2D.\n")
}

// -------------------------------------------------------------------- asset library

/** Writes the generated asset library (packs of art, fonts and sound) to [directory]. */
private fun runExportAssets(directory: String) {
    val target = File(directory)
    target.mkdirs()
    val files = dev.lumen2d.desktop.demo.AssetPackBuilder(target).buildAll()
    println("Wrote ${files.size} asset files to ${target.absolutePath}")
    for (file in files.take(8)) println("  $file")
    if (files.size > 8) println("  ... ${files.size - 8} more")
}

/**
 * Vendors a third-party asset archive into `assets-library/sources/<pack>/`.
 *
 * This is the first half of the library's provenance story: the archive's bytes are copied (never
 * linked), hashed and described by a `sources.json`. `--export-assets` then republishes them as a
 * normal pack, so the published pack can be rebuilt from the repository alone.
 */
private fun runImportSources(archive: String, directory: String) {
    if (archive.isEmpty()) {
        System.err.println("Usage: lumen2d --import-sources <archive.zip> [--dir assets-library/sources]")
        exitProcess(2)
    }
    val targetRoot = File(if (directory == ".") "assets-library/sources" else directory)
    val written = dev.lumen2d.desktop.demo.SourceImporter.importArchive(File(archive), targetRoot)
    println("Imported ${written.size} file(s) from ${File(archive).name} into ${targetRoot.absolutePath}")
    for (file in written) println("  $file")
    println("Now run:  lumen2d --export-assets assets-library/packs")
}

/** Writes the sample games (complete projects) to [directory]. */
private fun runExportSamples(directory: String) {
    val target = File(directory)
    target.mkdirs()
    val games = dev.lumen2d.desktop.demo.SampleGames(target).buildAll()
    println("Wrote ${games.size} sample game(s) to ${target.absolutePath}")
    for (game in games) println("  $game")
    println("Play one with:  lumen2d --game ${File(target, games.first()).absolutePath}")
}

// ------------------------------------------------------------------------ previews

private fun runPreviews(options: DesktopOptions) {
    val output = File(options.out)
    output.mkdirs()
    val generator = dev.lumen2d.desktop.demo.PreviewGenerator(output, scale = options.scale)
    val names = if (options.demo == "all") dev.lumen2d.desktop.demo.DemoScenes.names else listOf(options.demo)
    var written = 0
    for (name in names) {
        val result = generator.render(name, frames = options.frames)
        if (result == null) {
            System.err.println("  ! unknown demo '$name' (available: ${dev.lumen2d.desktop.demo.DemoScenes.names.joinToString(", ")})")
            continue
        }
        written++
        println("  rendered $name -> ${result.relativeTo(output.parentFile?.parentFile ?: output)}")
    }
    if (written == 0) {
        System.err.println("No previews rendered.")
        exitProcess(1)
    }
}

// --------------------------------------------------------------------------- check

/** Boots a project headlessly, runs frames and reports whether the engine stayed healthy. */
private fun runCheck(path: String, scene: String = "", shot: String = "") {
    val directory = if (path.isEmpty()) findBundledProject() else File(path)
    if (directory == null || !directory.isDirectory) {
        System.err.println("No project to check. Pass a folder: lumen2d --check <folder>")
        exitProcess(2)
    }
    val project = openProject(directory) ?: run {
        System.err.println("${directory.absolutePath} has no ${Project.MANIFEST}")
        exitProcess(2)
    }
    if (scene.isNotEmpty()) project.config.startScene = scene
    val game = Game(DesktopPlatform(), project)
    game.useSoftwareRenderer()
    installLumenScripts(game)
    game.start()
    repeat(120) { game.frame(1f / 60f) }
    val summary = game.performanceSummary()
    val frame = game.captureFrame()
    val drawn = frame?.let { buffer ->
        var distinct = 0
        val clear = buffer[0, 0]
        for (i in buffer.pixels.indices) if (buffer.pixels[i] != clear) distinct++
        distinct
    } ?: -1
    println("Checked '${project.config.title}' — frames=${game.frameCount} nodes=${game.tree.root.descendants().size}")
    println("  fps=${"%.1f".format(summary["fps"])} drawCalls=${summary["drawCalls"]?.toInt()} sprites=${summary["sprites"]?.toInt()} drawnPixels=$drawn")
    println("  assets=${summary["assets"]?.toInt()} textures=${summary["textures"]?.toInt()} bodies=${summary["bodies"]?.toInt()}")
    if (shot.isNotEmpty()) {
        val bytes = frame?.let { dev.lumen2d.core.render.PngCodec.encode(it) }
        if (bytes != null) {
            File(shot).absoluteFile.parentFile?.mkdirs()
            File(shot).writeBytes(bytes)
            println("  screenshot -> $shot")
        }
    }
    val errors = game.logLines.count { it.contains("ERROR") }
    if (errors > 0) {
        println("  $errors error line(s) in the log")
        exitProcess(1)
    }
    println("  ok")
}
