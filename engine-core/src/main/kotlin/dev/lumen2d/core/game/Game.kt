/**
 * Lumen2D — projects, game configuration and the runtime entry point.
 *
 * A [Project] is a folder on any [VirtualFileSystem] with a `project.lumen` manifest:
 *
 * ```
 * my-game/
 *   project.lumen          manifest + game config
 *   input_map.json         action bindings
 *   scenes/name.scene.json scenes and prefabs
 *   scripts/name.lumen     gameplay scripts
 *   assets/                imported art, audio, fonts (with .meta.json sidecars)
 *   packs/                 asset library packs the project depends on
 * ```
 *
 * [Game] wires a project to a backend [Platform] + [Renderer] and owns the update/render loop.
 * It is deliberately headless-capable: pass [SoftwareRenderer] and [HeadlessPlatform] and the
 * same code runs in CI to produce screenshots and regression frames.
 */
package dev.lumen2d.core.game

import dev.lumen2d.core.assets.AssetDatabase
import dev.lumen2d.core.assets.AssetMeta
import dev.lumen2d.core.assets.AssetType
import dev.lumen2d.core.audio.AudioMixer
import dev.lumen2d.core.audio.AudioPump
import dev.lumen2d.core.input.InputBinding
import dev.lumen2d.core.input.InputSource
import dev.lumen2d.core.input.InputState
import dev.lumen2d.core.input.KeyCode
import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.platform.Platform
import dev.lumen2d.core.platform.VirtualFileSystem
import dev.lumen2d.core.render.Renderer
import dev.lumen2d.core.render.SoftwareRenderer
import dev.lumen2d.core.render.TextureManager
import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.Scene
import dev.lumen2d.core.scene.SceneResources
import dev.lumen2d.core.scene.SceneTree
import dev.lumen2d.core.util.Json
import dev.lumen2d.core.util.Log
import dev.lumen2d.core.util.LogRecord
import dev.lumen2d.core.util.Profiler
import dev.lumen2d.core.util.Signal
import dev.lumen2d.core.util.bool
import dev.lumen2d.core.util.int
import dev.lumen2d.core.util.obj
import dev.lumen2d.core.util.str
import dev.lumen2d.core.util.strList

/** How the design resolution maps onto the device screen. */
enum class StretchMode(val id: String, val label: String) {
    NONE("none", "None (1:1 pixels)"),
    FIT("fit", "Fit (letterbox)"),
    WIDTH("width", "Fit width"),
    HEIGHT("height", "Fit height"),
    EXPAND("expand", "Expand (show more)"),
    INTEGER("integer", "Integer scale (pixel perfect)");

    companion object {
        fun byId(id: String?): StretchMode = entries.firstOrNull { it.id == id } ?: FIT
    }
}

/** Everything the engine needs to boot a project; persisted in `project.lumen`. */
class GameConfig {
    var title: String = "Untitled Game"
    var version: String = "1.0.0"
    var author: String = ""
    var description: String = ""
    /** Friendly package id used when exporting an Android build. */
    var packageId: String = "dev.lumen2d.game"

    // ---- display ----------------------------------------------------------
    var designWidth: Int = 480
    var designHeight: Int = 270
    var stretchMode: StretchMode = StretchMode.FIT
    var pixelArt: Boolean = true
    var clearColor: Color = Color(0.05f, 0.06f, 0.10f)
    var orientation: String = "landscape"      // landscape | portrait | sensor
    var fullscreen: Boolean = true
    var targetFps: Int = 60
    var vsync: Boolean = true

    // ---- gameplay ---------------------------------------------------------
    var gravity: Vec2 = Vec2(0f, 980f)
    var physicsTicksPerSecond: Int = 60
    var maxDelta: Float = 1f / 15f
    var timeScale: Float = 1f
    var startScene: String = "scenes/main.scene.json"
    var autoloads: MutableList<String> = ArrayList()
    var defaultFont: String = "pixel8"
    /** Mixer buses with initial volume/mute state. */
    var audioBuses: MutableMap<String, Float> = linkedMapOf("Master" to 1f, "Music" to 0.7f, "SFX" to 0.9f, "UI" to 0.8f)
    var audioBusMutes: MutableMap<String, Boolean> = linkedMapOf()

    // ---- project ----------------------------------------------------------
    var assetPacks: MutableList<String> = ArrayList(listOf("lib://packs/base"))
    var physicsLayers: MutableList<String> = ArrayList(
        listOf("player", "enemy", "world", "pickup", "hazard", "projectile", "trigger", "ui"))
    var tags: MutableList<String> = ArrayList(listOf("player", "enemy", "pickup", "checkpoint", "destructible"))
    var debugDraw: Boolean = false
    var showPerformance: Boolean = false
    var saveSlots: Int = 3
    var editorWidths: MutableList<Float> = ArrayList()

    fun serialize(): MutableMap<String, Any?> = linkedMapOf(
        "title" to title, "version" to version, "author" to author, "description" to description,
        "packageId" to packageId,
        "display" to linkedMapOf(
            "designWidth" to designWidth, "designHeight" to designHeight,
            "stretchMode" to stretchMode.id, "pixelArt" to pixelArt, "clearColor" to clearColor.toHex(),
            "orientation" to orientation, "fullscreen" to fullscreen,
            "targetFps" to targetFps, "vsync" to vsync,
        ),
        "gameplay" to linkedMapOf(
            "gravity" to listOf(gravity.x, gravity.y),
            "physicsTicksPerSecond" to physicsTicksPerSecond,
            "maxDelta" to maxDelta, "timeScale" to timeScale,
            "startScene" to startScene, "autoloads" to autoloads.toList(),
            "defaultFont" to defaultFont,
            "audioBuses" to LinkedHashMap<String, Any?>(audioBuses),
            "audioBusMutes" to LinkedHashMap<String, Any?>(audioBusMutes),
        ),
        "project" to linkedMapOf(
            "assetPacks" to assetPacks.toList(),
            "physicsLayers" to physicsLayers.toList(),
            "tags" to tags.toList(),
            "debugDraw" to debugDraw, "showPerformance" to showPerformance, "saveSlots" to saveSlots,
            "editorWidths" to editorWidths.toList(),
        ),
    )

    fun load(data: Map<String, Any?>) {
        title = data.str("title", title)
        version = data.str("version", version)
        author = data.str("author", author)
        description = data.str("description", description)
        packageId = data.str("packageId", packageId)
        data.obj("display")?.let { d ->
            designWidth = d.int("designWidth", designWidth)
            designHeight = d.int("designHeight", designHeight)
            stretchMode = StretchMode.byId(d.str("stretchMode"))
            pixelArt = d.bool("pixelArt", pixelArt)
            d.str("clearColor").takeIf { it.isNotEmpty() }?.let { clearColor = Color.fromHex(it) }
            orientation = d.str("orientation", orientation)
            fullscreen = d.bool("fullscreen", fullscreen)
            targetFps = d.int("targetFps", targetFps)
            vsync = d.bool("vsync", vsync)
        }
        data.obj("gameplay")?.let { g ->
            (g["gravity"] as? List<*>)?.takeIf { it.size >= 2 }?.let {
                gravity = Vec2((it[0] as? Number)?.toFloat() ?: 0f, (it[1] as? Number)?.toFloat() ?: 980f)
            }
            physicsTicksPerSecond = g.int("physicsTicksPerSecond", physicsTicksPerSecond)
            maxDelta = runCatching { g["maxDelta"].let { (it as? Number)?.toFloat() ?: maxDelta } }.getOrDefault(maxDelta)
            timeScale = runCatching { (g["timeScale"] as? Number)?.toFloat() ?: timeScale }.getOrDefault(timeScale)
            startScene = g.str("startScene", startScene)
            autoloads = g.strList("autoloads").toMutableList()
            defaultFont = g.str("defaultFont", defaultFont)
            g.obj("audioBuses")?.forEach { (k, v) -> audioBuses[k] = (v as? Number)?.toFloat() ?: 1f }
            g.obj("audioBusMutes")?.forEach { (k, v) -> audioBusMutes[k] = v == true }
        }
        data.obj("project")?.let { p ->
            assetPacks = p.strList("assetPacks").ifEmpty { assetPacks }.toMutableList()
            physicsLayers = p.strList("physicsLayers").ifEmpty { physicsLayers }.toMutableList()
            tags = p.strList("tags").ifEmpty { tags }.toMutableList()
            debugDraw = p.bool("debugDraw", debugDraw)
            showPerformance = p.bool("showPerformance", showPerformance)
            saveSlots = p.int("saveSlots", saveSlots)
            editorWidths = p["editorWidths"].let { list ->
                (list as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() } ?: emptyList()
            }.toMutableList()
        }
    }

    companion object {
        fun fromJson(data: Map<String, Any?>): GameConfig = GameConfig().also { it.load(data) }
    }
}

/** Discovered contents of a project folder — used by the project manager and asset browser. */
class ProjectIndex(
    val scenes: List<String> = emptyList(),
    val prefabs: List<String> = emptyList(),
    val scripts: List<String> = emptyList(),
    val assets: List<String> = emptyList(),
    val packs: List<String> = emptyList(),
) {
    val sceneCount: Int get() = scenes.size
    val scriptCount: Int get() = scripts.size
    val assetCount: Int get() = assets.size
    fun isEmpty(): Boolean = scenes.isEmpty() && scripts.isEmpty() && assets.isEmpty()
}

/**
 * A game project: manifest, folder layout and scene/asset access.
 * Projects are plain folders, so they can live in app storage, on a desktop disk or in SAF.
 */
class Project(
    var name: String,
    /** Root path inside [fileSystems] ("my-game" or ".", for the mount root itself). */
    var root: String,
    var config: GameConfig = GameConfig(),
    /** Mounts searched for project files, first match wins: e.g. ("project://", vfs). */
    var fileSystems: List<Pair<String, VirtualFileSystem>> = emptyList(),
) {
    val created = System.currentTimeMillis()

    /** The writable filesystem holding the project. */
    val vfs: VirtualFileSystem get() = fileSystems.firstOrNull()?.second
        ?: throw IllegalStateException("Project has no filesystem mounted")

    companion object {
        const val MANIFEST = "project.lumen"
        const val FORMAT = "lumen2d.project"
        const val VERSION = 1

        /** Folder skeleton created for new projects (kept in sync with the docs). */
        val STANDARD_FOLDERS = listOf("scenes", "prefabs", "scripts", "assets", "packs", "ui", "audio", "shaders")

        /** Creates a project folder with a manifest, input map and an empty main scene. */
        fun create(
            vfs: VirtualFileSystem,
            root: String,
            name: String,
            config: GameConfig = GameConfig().also { it.title = name },
        ): Project {
            val project = Project(name, root, config, listOf("project://" to vfs))
            STANDARD_FOLDERS.forEach { vfs.mkdirs(join(root, it)) }
            val boot = Scene("main", Node("Main"))
            val node = Node("World")
            boot.root.addChild(node)
            project.saveScene(boot, "scenes/main.scene.json")
            project.saveInputMap(DefaultInputMap.bindings())
            project.save()
            return project
        }

        /** Opens a project folder; returns null when there is no manifest. */
        fun open(vfs: VirtualFileSystem, root: String, mount: String = "project://"): Project? {
            val manifestPath = join(root, MANIFEST)
            if (!vfs.exists(manifestPath)) return null
            val data = runCatching { Json.parseObject(vfs.readText(manifestPath)) }.getOrNull() ?: return null
            val config = GameConfig.fromJson(data.obj("config") ?: emptyMap())
            val project = Project(
                name = data.str("name", root.substringAfterLast('/').ifEmpty { "project" }),
                root = root, config = config, fileSystems = listOf(mount to vfs),
            )
            return project
        }

        fun join(root: String, path: String): String {
            val clean = path.removePrefix("/")
            return if (root.isEmpty() || root == ".") clean else "$root/$clean"
        }
    }

    // ------------------------------------------------------------------ paths

    fun path(relative: String): String = join(root, relative)
    fun projectPath(relative: String): String = "project://${join(root, relative)}"

    // ------------------------------------------------------------- persistence

    fun save() {
        val manifest = linkedMapOf<String, Any?>(
            "format" to FORMAT, "version" to VERSION, "name" to name,
            "engine" to "Lumen2D 1.0",
            "savedAt" to System.currentTimeMillis(),
            "config" to config.serialize(),
        )
        vfs.mkdirs(root)
        vfs.writeText(path(MANIFEST), Json.stringify(manifest))
    }

    fun saveScene(scene: Scene, relativePath: String) {
        val target = join(root, if (relativePath.endsWith(Scene.EXTENSION)) relativePath else "$relativePath${Scene.EXTENSION}")
        vfs.mkdirs(target.substringBeforeLast('/', root))
        vfs.writeText(target, Json.stringify(scene.serialize()))
    }

    fun loadScene(relativePath: String): Scene? {
        val candidates = listOf(
            join(root, relativePath),
            join(root, "$relativePath${Scene.EXTENSION}"),
            join(root, "scenes/$relativePath"),
            join(root, "scenes/$relativePath${Scene.EXTENSION}"),
            join(root, "prefabs/$relativePath${Scene.EXTENSION}"),
        )
        for (candidate in candidates) {
            if (!vfs.exists(candidate)) continue
            val data = runCatching { Json.parseObject(vfs.readText(candidate)) }.getOrNull() ?: continue
            return Scene.fromJson(data, projectPath(relativePath))
        }
        return null
    }

    fun deleteFile(relativePath: String) {
        val target = path(relativePath)
        if (vfs.exists(target)) vfs.delete(target, recursive = true)
    }

    fun saveText(relativePath: String, text: String) {
        val target = path(relativePath)
        vfs.mkdirs(target.substringBeforeLast('/', root))
        vfs.writeText(target, text)
    }

    fun readText(relativePath: String): String? =
        path(relativePath).let { if (vfs.exists(it)) runCatching { vfs.readText(it) }.getOrNull() else null }

    // ------------------------------------------------------------- input map

    fun saveInputMap(bindings: List<InputBinding>) {
        saveText("input_map.json", Json.stringify(linkedMapOf(
            "format" to "lumen2d.input_map", "version" to 1,
            "bindings" to bindings.map { DefaultInputMap.bindingToJson(it) },
        )))
    }

    fun loadInputMap(): List<InputBinding> {
        val text = readText("input_map.json") ?: return DefaultInputMap.bindings()
        val data = runCatching { Json.parseObject(text) }.getOrNull() ?: return DefaultInputMap.bindings()
        val list = (data["bindings"] as? List<*>) ?: return DefaultInputMap.bindings()
        val bindings = list.mapNotNull { DefaultInputMap.bindingFromJson(it) }
        return bindings.ifEmpty { DefaultInputMap.bindings() }
    }

    // -------------------------------------------------------------- discovery

    /** Walks the project folder and classifies every file. */
    fun index(): ProjectIndex {
        val files = runCatching { vfs.walk(root) }.getOrDefault(emptyList())
            .map { it.removePrefix(if (root == ".") "" else "$root/") }
            .filter { it.isNotEmpty() && !it.startsWith(".") && !it.contains("/.thumbnails/") }
        return ProjectIndex(
            scenes = files.filter { it.startsWith("scenes/") && it.endsWith(Scene.EXTENSION) }.sorted(),
            prefabs = files.filter { it.startsWith("prefabs/") && it.endsWith(Scene.EXTENSION) }.sorted(),
            scripts = files.filter { it.endsWith(".lumen") || it.endsWith(".l2d") }.sorted(),
            assets = files.filter {
                AssetType.fromPath(it) != AssetType.UNKNOWN && !it.endsWith(Scene.EXTENSION)
            }.sorted(),
            packs = files.filter { it.contains("catalog.json") }.sorted(),
        )
    }

    fun sceneFiles(): List<String> = index().scenes

    /** Reads every asset in the project into [database] (packs can be mounted separately). */
    fun importInto(database: AssetDatabase, extractMetadata: Boolean = true): Int =
        database.scan(listOf("project://" to root), extractMetadata).added.size

    fun assetMeta(path: String): AssetMeta? = AssetMeta.fromJson(
        linkedMapOf("id" to path, "path" to path, "type" to AssetType.fromPath(path).id))

    /** Human-readable summary used by the project manager cards. */
    fun summary(): String {
        val i = index()
        return "${i.sceneCount} scenes · ${i.scriptCount} scripts · ${i.assetCount} assets"
    }
}

/**
 * The runtime: owns the tree, renderer, audio, input and asset database, and runs the frame loop.
 *
 * ```kotlin
 * val game = Game(platform, project).apply { start() }
 * while (game.running) { game.frame() }
 * ```
 */
class Game(
    val platform: Platform,
    var project: Project? = null,
    var config: GameConfig = project?.config ?: GameConfig(),
) {
    // ------------------------------------------------------------- subsystems
    val textures = TextureManager()
    val database: AssetDatabase
    val mixer: AudioMixer = AudioMixer()
    val audioOutput: dev.lumen2d.core.platform.AudioOutput? by lazy {
        platform.createAudioOutput(mixer.sampleRate, mixer.channels)
    }
    private var pump: AudioPump? = null
    val resources: SceneResources
    val tree: SceneTree
    var renderer: Renderer? = null
        private set

    /** Script runtime injected by the platform module (optional; the desktop/CI builds skip it). */
    var scriptRuntime: SceneTree.ScriptRuntimeHost?
        get() = tree.scriptRuntime
        set(value) { tree.scriptRuntime = value }

    /**
     * In-memory script registry: path -> source. Tools, demos and tests register scripts here so the
     * runtime can run gameplay code without touching the project folder.
     */
    val scriptSources = LinkedHashMap<String, String>()

    /** Registers (or replaces) a script source under [path]. */
    fun registerScript(path: String, source: String) { scriptSources[path] = source }

    /** Log lines produced since boot, for the editor console. */
    val logLines = ArrayList<String>()
    val sceneLoaded = Signal<Scene>()
    val ready = Signal<Unit>()

    var running: Boolean = false
        private set
    var paused: Boolean = false
    var timeScale: Float
        get() = tree.timeScale
        set(value) { tree.timeScale = value; mixer.speed = value }

    private var framesWithoutFocus = 0
    var frameCount: Long = 0
        private set
    /** Set by the host when the game should shut down (window closed, activity finished). */
    var quitRequested: Boolean = false

    init {
        val mounts = ArrayList<Pair<String, VirtualFileSystem>>()
        project?.fileSystems?.forEach { mounts.add(it) }
        // The shared asset library ships with the engine and is mounted read-only as `lib://`.
        val library = platform.bundledFileSystem
        if (library != null) mounts.add("lib://" to library)
        else Log.w(
            "Assets",
            "No bundled asset library at '${platform.bundledContentRoot}': `lib://` references " +
                "will not resolve (start the app from the repository root, or set LUMEN2D_CONTENT).",
        )
        mounts.add("user://" to platform.userFileSystem)
        database = AssetDatabase(mounts, textures)
        resources = SceneResources(
            textures = textures, assets = database, audio = mixer, platform = platform,
        )
        tree = SceneTree(renderer = null, audio = mixer, assets = database, input = InputState(), physics = dev.lumen2d.core.physics.PhysicsWorld())
        tree.resources = resources
        tree.scriptRuntime = null
        Log.addListener { record: LogRecord ->
            if (logLines.size > 4000) logLines.subList(0, 1000).clear()
            logLines.add("${record.timeText} ${record.level.label}/${record.tag}: ${record.message}")
        }
    }

    // ------------------------------------------------------------------ setup

    /** Attaches a renderer (created by the backend) and configures resolution/filters. */
    fun attachRenderer(renderer: Renderer) {
        this.renderer = renderer
        tree.renderer = renderer
        renderer.debugDraw = config.debugDraw
    }

    /** Creates a headless software renderer at the design resolution (tests, CI screenshots). */
    fun useSoftwareRenderer(scale: Float = 1f): SoftwareRenderer =
        SoftwareRenderer(config.designWidth, config.designHeight, scale).also { attachRenderer(it) }

    /** Scans project assets and loads the input map plus audio bus configuration. */
    fun prepare() {
        val index = project?.index()
        database.scan(extractMetadata = true)
        Log.i("Game", "Assets: ${database.size} (${index?.assetCount ?: 0} in project)")
        val bindings = project?.loadInputMap() ?: DefaultInputMap.bindings()
        tree.input.setBindings(bindings)
        config.audioBuses.forEach { (bus, volume) ->
            if (bus == "Master") mixer.masterVolume = volume else mixer.bus(bus).volume = volume
        }
        config.audioBusMutes.forEach { (bus, muted) ->
            if (bus == "Master") mixer.muted = muted else mixer.bus(bus).muted = muted
        }
        tree.platformName = platform.name
        tree.physics.gravity = config.gravity
        tree.physics.fixedDelta = 1f / config.physicsTicksPerSecond.coerceAtLeast(1)
        resources.fonts.defaultFontName = config.defaultFont
        // Guarantees text is renderable even when a project ships no font asset.
        resources.fonts.ensureFallback()
    }

    /** Boots the engine: assets, autoloads, start scene, audio. */
    fun start() {
        if (running) return
        prepare()
        tree.start()
        running = true
        ready.emit(Unit)
        log("Lumen2D started — ${config.title} (${platform.name})")
        startAudio()
        changeScene(config.startScene)
        for (autoload in config.autoloads) {
            project?.loadScene(autoload)?.let { scene ->
                tree.addPersistent(scene.root, scene.name)
                log("Autoloaded ${scene.name}")
            }
        }
    }

    private fun startAudio() {
        val output = audioOutput ?: return
        pump = AudioPump(mixer, output).also { it.start() }
    }

    fun stop() {
        if (!running) return
        pump?.stop()
        tree.stop()
        running = false
    }

    // ------------------------------------------------------------- scene flow

    /** Loads and switches to a project scene; missing scenes log an error instead of crashing. */
    fun changeScene(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false
        val scene = project?.loadScene(relativePath)
        if (scene == null) {
            log("Scene not found: $relativePath")
            return false
        }
        return changeScene(scene)
    }

    fun changeScene(scene: Scene): Boolean {
        tree.setScene(scene)
        sceneLoaded.emit(scene)
        log("Scene '${scene.name}' loaded (${scene.root.descendants().size + 1} nodes)")
        return true
    }

    fun reloadCurrentScene() {
        val scene = tree.currentScene ?: return
        tree.setScene(scene)
    }

    val currentScene: Scene? get() = tree.currentScene

    // -------------------------------------------------------------- frame loop

    /** One frame using the real clock. */
    fun frame() = frame(-1f)

    /** One frame with an explicit delta (tests, replays, CI screenshots). */
    fun frame(deltaOverride: Float = -1f) {
        if (!running) start()
        if (quitRequested) return
        tree.process(deltaOverride)
        if (renderer != null) tree.render(config.clearColor)
        pump?.let { if (mixer.playing) it.pump() }
        frameCount++
    }

    /** Runs [frames] fixed-delta frames — deterministic headless execution. */
    fun runHeadless(frames: Int, delta: Float = 1f / 60f) {
        repeat(frames) { frame(delta) }
    }

    /** Processes input that arrived before the loop started (touch down on the first frame). */
    fun pumpInput() { tree.input.endFrame() }

    fun requestQuit() {
        quitRequested = true
        saveSettings()
        platform.saveSettings()
    }

    // ----------------------------------------------------------- screenshots

    /** Current frame as a pixel buffer (software renderer only). */
    fun captureFrame(): dev.lumen2d.core.render.PixelBuffer? =
        (renderer as? SoftwareRenderer)?.captureFrame()

    /** Saves a PNG screenshot next to the user's save data. */
    fun screenshotTo(path: String): Boolean {
        val buffer = captureFrame() ?: return false
        val bytes = dev.lumen2d.core.render.PngCodec.encode(buffer)
        platform.userFileSystem.mkdirs(path.substringBeforeLast('/', ""))
        platform.userFileSystem.writeBytes(path, bytes)
        return true
    }

    // ------------------------------------------------------------ save games

    fun savePath(slot: Int = 0): String = "user://saves/slot_$slot.json"

    fun saveGame(slot: Int = 0, extra: Map<String, Any?> = emptyMap()): Boolean {
        val data = linkedMapOf<String, Any?>(
            "format" to "lumen2d.save", "version" to 1,
            "slot" to slot, "title" to config.title, "versionId" to config.version,
            "savedAt" to System.currentTimeMillis(),
            "scene" to (currentScene?.path ?: ""),
            "playTime" to tree.clock.elapsedSeconds,
            "nodes" to tree.collectSaveState(),
            "extra" to extra,
        )
        return runCatching {
            val path = savePath(slot)
            platform.userFileSystem.mkdirs(path.substringBeforeLast('/', ""))
            platform.userFileSystem.writeText(path, Json.stringify(data))
            log("Saved slot $slot (${data["scene"]})")
            true
        }.getOrElse { log("Save failed: ${it.message}"); false }
    }

    fun loadGame(slot: Int = 0): Boolean {
        val path = savePath(slot)
        if (!platform.userFileSystem.exists(path)) { log("Save slot $slot is empty"); return false }
        val data = runCatching { Json.parseObject(platform.userFileSystem.readText(path)) }.getOrNull() ?: return false
        val scenePath = data.str("scene")
        if (scenePath.isNotEmpty() && currentScene?.path != scenePath) changeScene(scenePath)
        tree.applySaveState((data["nodes"] as? Map<*, *>)?.let {
            @Suppress("UNCHECKED_CAST") it as Map<String, Any?>
        } ?: emptyMap())
        log("Loaded slot $slot")
        return true
    }

    fun deleteSave(slot: Int) {
        val path = savePath(slot)
        if (platform.userFileSystem.exists(path)) platform.userFileSystem.delete(path)
    }

    fun hasSave(slot: Int): Boolean = platform.userFileSystem.exists(savePath(slot))

    fun saveSlotsUsed(): List<Boolean> = (0 until config.saveSlots.coerceAtLeast(1)).map { hasSave(it) }

    // ------------------------------------------------------------ savegame ui

    data class SaveSlotInfo(val slot: Int, val used: Boolean, val scene: String, val playTime: Float, val savedAt: Long)

    fun inspectSaveSlots(): List<SaveSlotInfo> = (0 until config.saveSlots.coerceAtLeast(1)).map { slot ->
        val path = savePath(slot)
        if (!platform.userFileSystem.exists(path)) SaveSlotInfo(slot, false, "", 0f, 0L)
        else {
            val data = runCatching { Json.parseObject(platform.userFileSystem.readText(path)) }.getOrNull()
            SaveSlotInfo(
                slot = slot, used = true,
                scene = data?.str("scene") ?: "",
                playTime = (data?.get("playTime") as? Number)?.toFloat() ?: 0f,
                savedAt = (data?.get("savedAt") as? Number)?.toLong() ?: 0L,
            )
        }
    }

    // -------------------------------------------------------------- settings

    fun saveSettings() {
        val store = platform.settingsStore
        store["game.title"] = config.title
        store["game.version"] = config.version
        store["game.lastPlayed"] = System.currentTimeMillis().toString()
        store["audio.master"] = mixer.masterVolume.toString()
        platform.saveSettings()
    }

    fun log(message: String) { Log.i("Game", message) }

    val fps: Float get() = Profiler.fps
    val frameMillis: Float get() = Profiler.lastFrameMillis

    /** Snapshot for the editor's profiler panel and the desktop overlay. */
    fun performanceSummary(): Map<String, Float> = linkedMapOf(
        "fps" to Profiler.fps,
        "frameMs" to Profiler.lastFrameMillis,
        "processMs" to Profiler.sample("process").lastMillis,
        "renderMs" to Profiler.sample("render").lastMillis,
        "physicsMs" to Profiler.sample("physics").lastMillis,
        "drawCalls" to Profiler.counter("drawCalls"),
        "sprites" to Profiler.counter("sprites"),
        "renderNodes" to Profiler.counter("nodes"),
        "bodies" to Profiler.counter("bodies"),
        "textures" to textures.count.toFloat(),
        "assets" to database.size.toFloat(),
    )

    /** Screen rectangle in design pixels, useful for overlays and the letterboxed editor view. */
    fun viewportRect(screenWidth: Float, screenHeight: Float): Rect =
        StretchMath.viewport(config, screenWidth.toInt(), screenHeight.toInt())

    val isReady: Boolean get() = running
}

/** Default action bindings shared by new projects, the editor and CI tests. */
object DefaultInputMap {

    fun bindings(): List<InputBinding> = listOf(
        InputBinding("move_left", InputSource.KEYBOARD, KeyCode.LEFT),
        InputBinding("move_left", InputSource.KEYBOARD, KeyCode.KEY_A),
        InputBinding("move_right", InputSource.KEYBOARD, KeyCode.RIGHT),
        InputBinding("move_right", InputSource.KEYBOARD, KeyCode.KEY_D),
        InputBinding("move_up", InputSource.KEYBOARD, KeyCode.UP),
        InputBinding("move_up", InputSource.KEYBOARD, KeyCode.KEY_W),
        InputBinding("move_down", InputSource.KEYBOARD, KeyCode.DOWN),
        InputBinding("move_down", InputSource.KEYBOARD, KeyCode.KEY_S),
        InputBinding("jump", InputSource.KEYBOARD, KeyCode.SPACE),
        InputBinding("jump", InputSource.KEYBOARD, KeyCode.KEY_Z),
        InputBinding("fire", InputSource.KEYBOARD, KeyCode.KEY_X),
        InputBinding("fire", InputSource.KEYBOARD, KeyCode.KEY_J),
        InputBinding("pause", InputSource.KEYBOARD, KeyCode.ESCAPE),
        InputBinding("ui_accept", InputSource.KEYBOARD, KeyCode.ENTER),
        // Touch defaults: virtual buttons laid out for a 480x270 landscape screen.
        InputBinding("move_left", InputSource.VIRTUAL, buttonRect = Rect(8f, 200f, 40f, 40f)),
        InputBinding("move_right", InputSource.VIRTUAL, buttonRect = Rect(56f, 200f, 40f, 40f)),
        InputBinding("jump", InputSource.VIRTUAL, buttonRect = Rect(400f, 200f, 44f, 44f)),
        InputBinding("fire", InputSource.VIRTUAL, buttonRect = Rect(352f, 206f, 40f, 40f)),
    )

    /** Named axes used by touch joysticks; the editor exposes these in the input map panel. */
    fun axes(): List<String> = listOf("move", "aim")

    fun bindingToJson(binding: InputBinding): Map<String, Any?> = linkedMapOf(
        "action" to binding.action,
        "source" to binding.source.name.lowercase(),
        "key" to binding.key.name,
        "deadZone" to binding.deadZone,
        "rect" to binding.buttonRect?.let { listOf(it.x, it.y, it.w, it.h) },
    )

    fun bindingFromJson(raw: Any?): InputBinding? {
        val map = raw as? Map<*, *> ?: return null
        val action = map["action"]?.toString() ?: return null
        val source = runCatching {
            InputSource.valueOf((map["source"]?.toString() ?: "keyboard").uppercase())
        }.getOrDefault(InputSource.KEYBOARD)
        val key = runCatching { KeyCode.valueOf(map["key"]?.toString() ?: "NONE") }.getOrDefault(KeyCode.NONE)
        val rect = (map["rect"] as? List<*>)?.takeIf { it.size >= 4 }?.let {
            Rect((it[0] as? Number)?.toFloat() ?: 0f, (it[1] as? Number)?.toFloat() ?: 0f,
                (it[2] as? Number)?.toFloat() ?: 0f, (it[3] as? Number)?.toFloat() ?: 0f)
        }
        return InputBinding(action, source, key, rect, (map["deadZone"] as? Number)?.toFloat() ?: 0.2f)
    }
}

/** Letterboxing / scaling maths shared by the editor preview, desktop and Android backends. */
object StretchMath {
    /** Returns the design-space rectangle (in screen pixels) the game should be drawn into. */
    fun viewport(config: GameConfig, screenWidth: Int, screenHeight: Int): Rect {
        val dw = config.designWidth.toFloat()
        val dh = config.designHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return Rect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat())
        return when (config.stretchMode) {
            StretchMode.NONE -> Rect(0f, 0f, dw, dh)
            StretchMode.WIDTH -> {
                val scale = screenWidth / dw
                val h = dh * scale
                Rect(0f, (screenHeight - h) / 2f, screenWidth.toFloat(), h)
            }
            StretchMode.HEIGHT -> {
                val scale = screenHeight / dh
                val w = dw * scale
                Rect((screenWidth - w) / 2f, 0f, w, screenHeight.toFloat())
            }
            StretchMode.EXPAND -> Rect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat())
            StretchMode.INTEGER -> {
                val scale = (minOf(screenWidth / dw, screenHeight / dh)).toInt().coerceAtLeast(1)
                val w = dw * scale
                val h = dh * scale
                Rect((screenWidth - w) / 2f, (screenHeight - h) / 2f, w, h)
            }
            StretchMode.FIT -> {
                val scale = minOf(screenWidth / dw, screenHeight / dh)
                val w = dw * scale
                val h = dh * scale
                Rect((screenWidth - w) / 2f, (screenHeight - h) / 2f, w, h)
            }
        }
    }

    /** Converts a touch point in screen pixels into design-space coordinates. */
    fun screenToDesign(config: GameConfig, screenWidth: Int, screenHeight: Int, point: Vec2): Vec2 {
        val view = viewport(config, screenWidth, screenHeight)
        if (view.w <= 0f || view.h <= 0f) return point
        return Vec2(
            (point.x - view.x) / view.w * config.designWidth,
            (point.y - view.y) / view.h * config.designHeight,
        )
    }
}
