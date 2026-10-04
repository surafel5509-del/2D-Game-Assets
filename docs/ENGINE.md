# Lumen2D — engine internals and API

This is the reference for people writing games against Lumen2D or extending the engine itself.
It describes what is actually in `engine-core`, `engine-desktop` and `engine-android`, module by
module. For the scripting language see [SCRIPTING.md](SCRIPTING.md), for content
[ASSETS.md](ASSETS.md), for the apps [EDITOR.md](EDITOR.md) and [ANDROID.md](ANDROID.md).

---

## 1. Module layout

| Module | Depends on | Contents |
| --- | --- | --- |
| `engine-core` | nothing (pure Kotlin/JVM) | every subsystem: math, JSON, VFS, scene graph, renderer, physics, tilemaps, particles, animation, audio, input, assets, saves, script interpreter |
| `engine-desktop` | `engine-core` + `java.desktop` | windowed player (AWT), headless rendering, CLI, asset-pack builder, sample-game generator, preview renderer |
| `engine-android` | `engine-core` + Android framework | asset/audio/input/storage/sharing backends and the game view |
| `app-android` | all of the above + AndroidX core | Lumen2D Studio (hub + editor) |

Nothing in `engine-core` touches a display, a file, a clock or a thread it did not receive through
`Platform`. That is what lets the same code run in a window, in a headless CI container, inside an
Android activity, and in the test suite.

```
Game ── owns ──► SceneTree ── owns ──► Scene (root Node) ──► nodes
  │                 │
  │                 ├─ PhysicsWorld        bodies, areas, queries
  │                 ├─ InputState          bindings, pointers, gestures
  │                 ├─ TweenManager        tweens + node timers
  │                 └─ ScriptRuntime       LumenScript instances per ScriptNode
  ├─ AssetDatabase ── mounts (project://, lib://, user://) ── sidecar metadata
  ├─ AudioMixer ── buses ── AudioOutput (platform)          effects, voices
  └─ Renderer (SoftwareRenderer | Android canvas | AWT window)
```

## 2. Frame loop and timing

`Game.frame(delta)` is the single entry point for a step:

1. `GameClock.step(delta)` clamps the delta (`GameConfig.maxDelta`, default `1/15 s`) and scales it
   by `timeScale`.
2. Input is polled from the platform's view/activity.
3. `SceneTree.update` runs `process` callbacks in tree order, respecting `ProcessMode`
   (`INHERIT`, `PAUSABLE`, `WHEN_PAUSED`, `ALWAYS`, `DISABLED`), then timers, tweens, particles and
   animation players.
4. Physics runs at a fixed rate (`GameConfig.physicsTicksPerSecond`, 60 by default) with an
   accumulator, so bodies behave identically at 30, 60 or 144 fps.
5. `SceneTree.render` clears, applies the active camera and draws the tree; UI nodes in
   `CanvasLayer`s draw last, in screen space.
6. Audio voices are mixed and pushed to the platform's `AudioOutput`.

`Profiler` records named timings (`render`, `physics`, `process`, `audio`) and counters
(`drawCalls`, `sprites`, `renderNodes`, `bodies`, `textures`, `assets`). `Game.performanceSummary()`
returns them as a map — that is what `lumen2d --check` prints and what the Studio console shows.

Determinism is a design rule, not an accident: the software renderer draws in a fixed order with
integer pixel coordinates at design resolution, `Rng` is a deterministic PCG, and physics uses a
fixed step. `tools/build-local.sh preview` twice in a row produces byte-identical PNGs (CI relies
on it).

## 3. Scene graph

**`Node`** — identity (`name`), hierarchy (`parent`, `children`, `addChild(child, index)`,
`removeChild(child, keepAlive)`, `descendants(includeSelf)`, `child(name)`), lifecycle
(`ready`, `process`, `physicsProcess`, `draw`, `exitTree`, `destroy`), `groups`, `visible`,
`processMode`, `internalProcess` and a **property system**:

* `propertyDefinitions(): List<PropertyDef>` describes every editable property
  (`name`, `type`, `category`, `min`, `max`, `hint`);
* `getProperty(name)` / `applyProperty(name, value)` read and write them by name;
* `PropertyType` covers `BOOL, INT, FLOAT, STRING, ENUM, COLOR, VECTOR2, RECT, TEXTURE, REGION,
  NODE_PATH, NODE_REF, TILE_MAP, ARRAY`.

The inspector, the scene serializer and the script bridge all use that one description, so a node
added by a game never needs editor-specific code.

**`Node2D`** adds `position`, `rotation`, `scale`, `zIndex`, `modulate`, `globalPosition`,
`globalRotation`, `globalScale`, `toGlobal`/`toLocal` and cached world transforms.

**`Scene`** is a named root node plus its source path: `Scene.empty(name)`,
`Scene.instantiate()` (deep copy for prefabs), `serialize()`/`Scene.fromJson()`.

**Signals** (`Signal<T>`) are the engine's event bus: nodes expose `ready`, `treeExited`, `freed`
and their own (e.g. `HealthNode.damaged/healed/died`, `TimerNode.timeout`, `ButtonControl.clicked`).
`connect` returns a handle with `disconnect()`.

**Node types** — 48 registered in `NodeRegistry`, each with a category, a description and a factory
(`NodeRegistry.creatableTypes()`, `byCategory()`, `create(name)`):

| Category | Types |
| --- | --- |
| Core (7) | `Node`, `Node2D`, `Timer`, `CanvasLayer`, `ScriptNode`, `PrefabInstance`, `Marker2D` |
| Visual (11) | `Sprite2D` (alias `Sprite`), `AnimatedSprite2D`, `TileMapLayer`, `Camera2D`, `Line2D`, `Polygon2D`, `NinePatchRect`, `Label2D`, `ParallaxLayer`, `BackgroundLayer` |
| Effects (3) | `CPUParticles2D`, `Light2D`, `TrailRenderer` |
| Physics (5) | `StaticBody2D`, `RigidBody2D`, `CharacterBody2D`, `Area2D`, `CollisionShape2D` |
| Gameplay (6) | `HealthNode`, `SpawnerNode`, `StateMachineNode`, `AnimationPlayer`, `Inventory`, `ScoreTracker` |
| Audio (2) | `AudioStreamPlayer`, `AudioStreamPlayer2D` |
| UI (14) | `Control`, `Panel`, `Label`, `Button`, `TextureRect`, `ProgressBar`, `Slider`, `VBoxContainer`, `HBoxContainer`, `GridContainer`, `ScrollContainer`, `VirtualJoystick`, `TouchButton`, `HudBar` |

Scenes are JSON (`lumen2d.scene`): a node is `{type, name, properties, children}`. `NodeRegistry`
resolves the type name back to a class, which is why scene files stay readable and diffable.

## 4. Rendering

`Renderer` is a narrow interface (`beginFrame`, `endFrame`, `drawSprite`, `drawNinePatch`,
`drawRect`, `drawRotatedRect`, `drawLine`, `drawCircle`, `drawEllipse`, `drawPolygon`, `drawArc`,
`drawText`, screen-space variants, `drawOverlay`, camera stack) — a backend only has to blit.

* **`SoftwareRenderer`** — the reference implementation: a `PixelBuffer` of ARGB pixels with
  nearest-neighbour sampling, integer clipping, and the same API the AWT window and Android view
  present. It runs anywhere (CI, tests, `--check`, `--demo`).
* **`RecordingRenderer`** — counts draw calls without touching pixels; used by tests to assert
  what a scene *would* draw.
* **`Camera2D`** — position, zoom, rotation, limits, smoothing, screen shake (`CameraNode` wraps it
  in the scene graph and can follow a node path or a group tag).
* **`StretchMode`** — how design pixels map to the device: `DISABLED`, `CANVAS_ITEMS`,
  `VIEWPORT`, `INTEGER`, `EXPAND`, `KEEP_ASPECT`. `StretchMath.viewport(config, w, h)` and
  `screenToDesign(...)` implement the mapping; the Android view uses them for touch coordinates.
* **Textures** — `PixelBuffer` (with `blendOver`, `ImageOps` for flips/tints/outlines),
  `PngCodec` (pure-Kotlin PNG encode/decode, no zlib dependency beyond `java.util.zip`),
  `Texture2D`, `TextureRegion`, `TextureAtlas`, `TextureFilter` (`NEAREST` by default for pixel
  art), `TextureManager` (`registerSource`, `load`, `add`, `getOrNull`, `sliceGrid`, `count`).
* **Fonts** — `BitmapFont` (atlas + glyph table, kerning, effects), `FontManager` with a built-in
  5×7 pixel font (`PixelFont5x7`, registered as `pixel6`) plus the generated 8 px `pixel8` atlas in
  the asset library. `resources.font(id)` resolves fonts like any other asset.

## 5. Physics

`PhysicsWorld` is a deterministic, fixed-step 2D solver with a spatial hash broadphase
(`SpatialHashGrid`):

* **Shapes** (`Shape2D`): `CircleShape`, `RectangleShape` (rotatable), `CapsuleShape`, `SegmentShape`;
* **Bodies** (`PhysicsBody`): `STATIC`, `KINEMATIC`, `RIGID` and trigger-only areas, with mass,
  friction, restitution, gravity scale, damping, layers/masks and one-way platforms;
* **Queries**: `raycast(from, to, mask, exclude)`, `queryRect`, `queryPoint`, `queryCircle`,
  `shapeCast`, `bodiesInArea`, `contacts()`;
* **Tilemap collision**: tiles flagged `collisionEnabled` become static shapes lazily around
  moving bodies (`TileCollision.FULL`, `PLATFORM` for one-way, `HAZARD`);
* **Scene wrappers**: `PhysicsBodyNode` and friends translate to script-friendly properties
  (`velocity`, `onFloor`, `onWall`, `move_and_slide(delta)`, `applyImpulse`, `jump`).

Collision callbacks are queued per step (`Contact`), so games never see a half-solved world.

## 6. Tilemaps

`TileMapData` is the serialisable model: width/height in tiles, tile size, a `TileSet` and a list of
`TileLayer`s (`data: IntArray` with Tiled-style flip/rotate flags in the high bits via `TileFlags`).

* `TileSet` — grid (`tileWidth`, `tileHeight`, `columns`, `margin`, `spacing`), per-index `TileMeta`
  (collision kind, tags, animation frames/fps), and named **auto-tile rules**
  (`{mask → index}`, mask bits 1 up, 2 right, 4 down, 8 left);
* `TileMapNode` — painting API (`setTile`, `getTile`, `clearLayer`, `paintLine`, `floodFill`,
  `autoTileLayer`, `rebuildCollision`, `worldBoundsOfMap`), `textureId` resolution and viewport
  culling;
* `TileMapImporter` — Tiled `.tmx`/`.tsx` import for teams that author maps outside the editor;
* level scenes in `sample-games/` are 60×18 tilemaps authored through this API and covered by tests.

## 7. Particles, animation, tweens

* **Particles** — `ParticleSystem` + `ParticleEmitterConfig` (emission shapes, curves for velocity,
  scale, colour and alpha over lifetime, gravity, drag, additive/multiply blends) with presets
  (`ParticlePresets`) for dust, sparks, explosions, rain and fire. `CPUParticles2D` wraps it.
* **Animation** — `Animation` holds typed tracks (`PropertyTrack`, `FrameTrack`, `CallTrack`,
  `AudioTrack`, `ParticlesTrack`) with `Keyframe`s and easing curves; `AnimationPlayerNode` plays
  them, `AnimatedSprite2D` handles frame animation from an atlas.
* **Tweens** — `TweenManager` drives `Tween`s built from `PropertyTweener`s, callbacks, waits and
  loops; `Node.tweenProperty(...)`, `tweenCallback(...)`, `node.setTimer(seconds, block)` and
  `callDeferred` are the script-facing sugar.

## 8. Audio

`AudioMixer` keeps buses (`Master`, `Music`, `SFX`, `UI` by default), voices and effect chains
(`GainEffect`, `LowPassEffect`, `HighPassEffect`, `EchoEffect`, `ReverbEffect`, `DistortionEffect`,
`BitCrushEffect`, `LimiterEffect`). Clips come from `WavCodec` (pure Kotlin, PCM 8/16-bit mono and
stereo) or the asset library. Playback is pushed to the platform: `JavaSoundOutput` (desktop,
`javax.sound.sampled`) or `AndroidAudioOutput` (AudioTrack, streaming writes on a worker thread).
Positional nodes (`AudioStreamPlayer2D`) attenuate and pan by x distance from the camera.

## 9. Input

`InputState` holds an `InputMap` of actions to bindings (`InputBinding(key`, `mouse button`,
`gamepad`, `virtual button`, `axis`) and the live pointer list. `DefaultInputMap.bindings()` defines
the standard set (`move_left/right/up/down`, `jump`, `fire`, `pause`, `ui_accept`, plus on-screen
buttons placed for 480×270) — projects save their own `input_map.json`, which the editor can edit.

Gestures (`tap`, `double tap`, `long press`, `drag`, `pinch`, `fling`) are recognised on top and
delivered as `GestureEvent`s. Virtual controls (`TouchButtonControl`, `JoystickControl`) inject
into the same action map, so gameplay code never branches on "is this a phone".

## 10. Assets and storage

`VirtualFileSystem` is the one storage abstraction; schemes are `project://`, `bundled://`,
`user://`, `lib://` and `device://`. Implementations: `FileFileSystem`, `InMemoryFileSystem`,
`PrefixedFileSystem`, `BundledFileSystem` (lookup-based), plus Android's `AssetFileSystem`.

`AssetDatabase` scans *declared roots* (the project folder plus `assetPacks`), keeps `AssetMeta`
records (id, path, type, tags, licence, author, import settings, hash, dependencies, nine-slice,
thumbnail, favourite, notes), and resolves references by id, virtual path, relative path or bare
file name. `AssetMount` binds a filesystem to a scheme and an id root, which is what keeps ids in
their documented `pack:path` form (`base:sprites/player.png`) no matter where the content sits.
[ASSETS.md](ASSETS.md) documents the formats; [`.meta.json` sidecars](ASSETS.md#3-sidecar-metadata)
carry per-file tags, licence and import settings.

Saves are JSON slots (`user://saves/slot_0.json`) written by `SaveStateCodec`, holding per-node
state plus the scene path and play time.

## 11. Scripting bridge

`engine-core/.../script/ScriptHosting.kt` provides `installLumenScripts(game)`, which registers the
project's `.lumen` sources with the runtime; `ScriptNode` attaches a behaviour
(`ScriptBehavior`) to any node. The bridge exposes nodes as script objects with the properties,
methods and signals listed in [SCRIPTING.md](SCRIPTING.md) — nothing in the interpreter knows about
a concrete node class beyond the `ScriptForeign` interface, and nothing in the scene graph knows a
script exists beyond `ScriptBehavior`.

## 12. Game, Project, configuration

```kotlin
val project = Project.open(FileFileSystem(directory), "", "project://")   // reads project.lumen
val game = Game(platform, project)                                        // mounts project + lib://
game.useSoftwareRenderer()                                                // or attachRenderer(...)
installLumenScripts(game)                                                 // wire scripts
game.start()                                                              // prepare + first scene
while (running) game.frame(delta)                                         // step
```

* `GameConfig` — title, version, package id, design resolution (480×270 default), stretch mode,
  gravity (0, 980), physics tick rate, max delta, time scale, start scene, autoloads, default font,
  audio buses, asset packs, physics layers, debug flags, save slots, editor panel widths.
  It serialises into `project.lumen`, so a project's settings are one readable JSON file.
* `Project` — `create`, `open`, `save`, `saveScene`/`loadScene`, `saveText`/`readText`,
  `saveInputMap`/`loadInputMap`, `index()` (`ProjectIndex` with scenes, scripts, prefabs, assets),
  `importInto(...)`.
* `Game` — `start`, `frame`, `stop`, `changeScene`, `registerScript`, `runHeadless(frames)`,
  `captureFrame()`, `screenshotTo(path)`, `performanceSummary()`, `assetScanRoots()`, save/load
  slots, and the log ring buffer the editor's console reads.

## 13. Platform contract

A backend implements `Platform`:

```kotlin
interface Platform {
    val name: String
    val isAndroid: Boolean
    val settingsStore: MutableMap<String, String>
    val userFileSystem: VirtualFileSystem
    val filePicker: PlatformFilePicker?
    val device: DeviceServices
    fun createAudioOutput(sampleRate: Int, channels: Int): AudioOutput?
    val bundledContentRoot: String
    val bundledFileSystem: VirtualFileSystem?      // mounted as lib://
    fun saveSettings(); fun logInfo(message: String); fun logError(message: String, t: Throwable?)
}
```

* `HeadlessPlatform` — logs only; the test suite and CI use it.
* `LocalPlatform` — JVM base class handling settings, user storage and bundled content; the Android
  platform and desktop platform derive from it (Android overrides the filesystem with
  `AssetFileSystem`).
* `DesktopPlatform` — real files, `JavaSoundOutput`, `AwtFilePicker`, device info.
* `AndroidPlatform` — APK assets as `lib://`, app-private storage as `user://`, SAF picker,
  AudioTrack output, vibration, clipboard, share intents.

If a platform provides no `bundledFileSystem`, the engine logs exactly why `lib://` references will
not resolve instead of failing silently.

## 14. Tests and determinism

`engine-core/src/test/kotlin/dev/lumen2d/core/test/` is a zero-dependency suite (its own tiny
harness, `TestHarness.kt`) run by `TestMain`: **125 tests / 1606 assertions** covering math, JSON,
the scene graph, physics, tilemaps, particles, animation, audio, assets, the shipped asset library
and the script interpreter. `tools/build-local.sh test` runs it offline; `gradle
:engine-core:engineTests` runs the same entry point on CI.

The library suite is the engine's contract with its own content: it verifies that every published
asset exists, has a sidecar and a provenance entry, that the imported pack can be sliced into a
working tileset, and that no sample game references a library file that does not exist.

## 15. Performance notes

* The software renderer costs about 0.5 ms per frame for the platformer level (124 draw calls,
  20 550 pixels) on a laptop-class CPU — headroom for phones, since the same work runs at 480×270.
* Asset scanning is limited to declared roots and caches file listings; the Android
  `AssetFileSystem` walks the APK asset tree once and keeps it.
* Textures decode on demand and are cached in `TextureManager`; `releaseTransient()` drops them on
  scene changes when memory matters.
* Physics uses a spatial hash with per-body AABB caching and only builds tile shapes near moving
  bodies.
* `Game.performanceSummary()` exposes counters, and the profiler can be sampled at runtime, so
  regressions are visible in the Studio console instead of being guessed at.
