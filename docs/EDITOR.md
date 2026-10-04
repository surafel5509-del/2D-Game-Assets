# The editor — Lumen2D Studio (Android) and the desktop tools

Lumen2D ships an editor, not a level format you have to hand-edit. *Lumen2D Studio* is an Android
app that runs the real engine in a live viewport and exposes it through five panels; the same
repository also gives you a desktop CLI for playing, checking, screenshotting and generating
content. Both use the same engine code, and both edit the same JSON projects.

---

## 1. The hub (`MainActivity`)

The app opens on a list of every project in app storage. On first launch the three sample games are
seeded from the APK (`SampleInstaller.install`), so a fresh install already has playable games.

Each project card shows its title, scene/script/asset counts and when it was last modified, with:

| Action | What it does |
| --- | --- |
| **New project** | `Project.create` + the standard input map, in `files/lumen/projects/<slug>` |
| **Import .lumenzip** | SAF picker (`AndroidFilePicker`) → `ProjectArchive.import` |
| **Edit** | opens the editor on that project |
| **Play** | opens the editor with the game already running |
| **Export** | writes `<slug>.lumenzip` and offers the system share sheet (Drive, mail, another phone) |
| **Export all** | zips every project in one go |
| **Rename / Duplicate / Delete** | file operations on the project folder |

Projects are ordinary folders (`project.lumen`, `scenes/`, `scripts/`, `assets/`), so anything you
export is the same thing the desktop engine opens.

## 2. The editor (`StudioActivity`)

```
┌──────────────────────────────────────────────────────────────┐
│ ◀ Hub  Play  Pause  Save  Undo  Redo  Export                 │  toolbar
├───────────────────────────────┬──────────────────────────────┤
│                               │  Scene | Inspector | Assets  │
│   live engine viewport        │  Scripts | Console           │
│   (480×270 design pixels,     │ ────────────────────────────  │
│    drag to move nodes,        │  panel for the active tab     │
│    tap to select,             │  (tree, properties, browser,  │
│    editor overlay on top)     │   code, log)                  │
├───────────────────────────────┴──────────────────────────────┤
│ status: scene · unsaved count · selection                     │
└──────────────────────────────────────────────────────────────┘
```

* **Viewport** — `LumenGameView` renders the running engine and reports touches in *design*
  coordinates (`onDesignTouch(design, phase)`, `worldToView`, `designToWorld`, plus
  `nodeSize`/`viewport`). Tap selects the node under the finger (`pickNodeAt`); dragging moves the
  selected node and the whole move becomes **one undo entry** (the drag is rewritten as a
  `Set-property` action before being applied). Play/Pause switch between playing and editing without
  leaving the screen.
* **Scene** — `SceneTreeView`: a custom-drawn, scrollable tree with fold markers, type-coloured
  rows and a long-press menu (add child, duplicate, rename, delete). `+ Node` opens the type chooser
  built from `NodeRegistry.creatableTypes()`; `Open…` lists `project.index().scenes`.
* **Inspector** — `InspectorView` builds its rows from `node.propertyDefinitions()`: booleans,
  float sliders (respecting `min`/`max`), integers, colours with a swatch, textures and regions
  (with a picker fed by the asset database), node paths, enums, strings, vectors and rects. Edits are
  emitted as `onEdit(node, property, value, final)` — the "final" flag lets a slider commit one undo
  entry on release instead of hundreds while dragging.
* **Assets** — `AssetBrowserView`: a thumbnail grid over the live asset database (project + library),
  with the free-text filter, type filters, a **Rescan** button, and a header summarising the bundled
  library (`Library: 2 packs — Lumen2D Base (CC0-1.0, 17) · Kenney Pixel Platformer (CC0-1.0, 2)`).
  Each card shows the asset's id, type, size, licence, origin and recipe; imported assets get a
  badge. Tapping selects (or pastes into the inspector), long-press copies the id together with its
  licence and provenance.
* **Scripts** — `ScriptEditorPanel`: one tab per `project.index().scripts` entry, a monospaced
  editor, and **live reload** — every keystroke re-registers the source with the running game
  (`game.registerScript(path, text)`), so a script edit is visible on the next frame. `Save` writes
  it back into the project, `Reload` reverts to the file.
* **Console** — `ConsolePanel`: the engine log ring buffer through `Log.addListener` (script `print`
  output, warnings, errors) plus the performance counters from `game.performanceSummary()`.
* **Save** writes the current scene back to its JSON file; the status bar tracks unsaved changes.
  **Export** writes a `.lumenzip` and shares it.

Undo/redo is a real command stack (`StudioSession`): `AddNodeAction`, `RemoveNodeAction`,
`RenameNodeAction`, `SetPropertyAction` (and moves), each with `apply()`/`revert()`. The stack is
bounded, cleared on scene load, and every action carries a human label that the toolbar shows.

## 3. Desktop tools

The CLI covers everything the editor does not need a screen for:

```bash
LUMEN="out/classes/core:out/classes/desktop:$HOME/.cache/tools/kotlin/lib/kotlin-stdlib.jar"
CLI="java -cp $LUMEN dev.lumen2d.desktop.LumenDesktopKt"

$CLI --game sample-games/pixel-platformer          # play in a window (AWT)
$CLI --demo all --out docs/preview/generated       # render the 7 demo screenshots
$CLI --new "My Game" --dir ~/games                 # scaffold a project
$CLI --check sample-games/neon-shooter --scene scenes/space.scene.json --shot /tmp/shot.png
$CLI --export-assets assets-library/packs          # rebuild the library (+ source index)
$CLI --export-samples sample-games                 # regenerate the sample projects
$CLI --import-sources kit.zip --dir assets-library/sources
$CLI --help
```

* **`--check`** boots a project headlessly, runs 120 fixed frames and reports frames, node count,
  fps, draw calls, sprites, drawn pixels, assets, textures and bodies; it writes a screenshot with
  `--shot` and exits non-zero if any `ERROR` line appeared in the log. This is what CI runs against
  every sample game.
* **`--demo`** renders the demo scenes (`platformer`, `tilemap`, `physics`, `particles`, `ui`,
  `lighting`, `scripting`) with the software renderer at 3× scale. The output is deterministic
  (re-rendering gives byte-identical files), so the screenshots in the README cannot silently drift.
* **`--new`** writes a starter project: a scene with a player sprite, a `scripts/player.lumen` with
  a working controller, the standard input map and a README.
* **`LumenWindow`** is the desktop player: an AWT window with integer scaling, keyboard/mouse input,
  audio through `JavaSoundOutput`, and the performance overlay. In a headless container the CLI
  falls back to running frames instead of opening a window.

`tools/build-local.sh play` runs the platformer through the window path; `preview` runs the demo
renderer.

## 4. Editing workflow (desktop ⇄ phone)

1. Generate or open a project on the desktop (`--new`, or a sample from `sample-games/`).
2. Work on it on the phone: import the folder as a `.lumenzip` (Export on the desktop writes one),
   edit scenes, scripts and assets in Studio, then export it back.
3. Run it anywhere with the same engine: `--check` on CI, `--game` in a desktop window, **Play** on
   the phone.

Because projects are plain folders and packs are plain files, a Git checkout *is* the project
store: `sample-games/` in this repository is edited by exactly the same code paths as a project on
a phone.
