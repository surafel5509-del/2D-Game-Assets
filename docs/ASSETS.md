# Assets, packs and asset sources

Lumen2D has no opaque asset database: a pack is a folder of files, a scene is JSON, and every
published asset carries a **sidecar** (what it is) plus an entry in a **source index** (where it came
from, under which licence). That makes the library diffable in Git, auditable for licensing, and
rebuildable from the repository alone.

```
assets-library/
  packs/                        ← published content, mounted as `lib://packs` (read-only)
    base/
      pack.json                 manifest: id, name, version, licence, tags, files
      sprites/player.png        17 assets + a *.meta.json sidecar for each
    kenney/                     an imported third-party pack (CC0), publication-ready as-is
  sources/                      ← provenance, mounted as `lib://sources` (ships in the APK too)
    base/index.json             one entry per published file: recipe, origin, licence, settings
    base/README.md              the same information as a readable table
    kenney-pixel-platformer/    the vendored upstream archive (unmodified) + sources.json
```

## 1. Mounts and ids

| Scheme | What it is | Writable |
| --- | --- | --- |
| `project://` | the open project folder | yes |
| `lib://` | the engine content root (this repository's `assets-library`, or the APK's assets) | no |
| `user://` | per-user storage: saves, settings, exported builds | yes |
| `bundled://` | a jar/APK-embedded fallback mount | no |
| `device://` | native file picker results (imports) | per result |

A project declares the packs it uses in `project.lumen`:

```json
"config": { "assetPacks": ["lib://packs/base"] }
```

`Game.assetScanRoots()` turns that into the scan list (the project folder plus each declared pack),
so only relevant content is indexed and a file in the library is never shadowed by a same-named file
in another pack.

**Asset ids** are `pack:relative/path.ext` — `base:sprites/player.png`, `kenney:tiles/tileset.png` —
and stay stable when packs move inside the content root (`AssetMount.idRoot` strips the folder
layout). **References** may be written four ways, resolved in this order:

| Reference | Example | Use |
| --- | --- | --- |
| asset id | `base:sprites/player.png` | scripts, tools, clipboard from the browser |
| full virtual path | `lib://packs/base/sprites/player.png` | scenes (what the editor writes) |
| project-relative path | `scenes/level_1.scene.json`, `sprites/player.png` | project files |
| bare file name | `player.png` | last resort, only for bare names (never paths) |

## 2. `pack.json`

```json
{
  "id": "base",
  "name": "Lumen2D Base",
  "description": "Starter pixel-art kit: characters, terrain, UI, particles, font and sound.",
  "version": "1.0.0",
  "license": "CC0-1.0",
  "author": "Lumen2D contributors",
  "engine": "1.0.0",
  "tags": ["pixel-art", "16x16", "starter"],
  "files": ["audio/blip.wav", "fonts/pixel8.png", "sprites/player.png", "tiles/tileset.png", "..."]
}
```

`files` lists **assets**, pack-relative, sorted; sidecars and documentation (`.md`, `.json`
sidecars) are not listed. `AssetPackManifest.discover(vfs, "packs")` finds every folder with a
`pack.json`, and `AssetLibrary.load(vfs, "packs", "sources")` joins the manifests with their
provenance.

## 3. Sidecar metadata

Every asset may carry `<file>.meta.json` next to it, read by the scanner (`extractMetadata`):

```json
{
  "displayName": "Player",
  "tags": ["character", "player"],
  "license": "CC0-1.0",
  "author": "Lumen2D contributors",
  "source": "Lumen2D generated asset library",
  "sourceUrl": "https://github.com/",
  "importSettings": { "frameWidth": 16, "frameHeight": 20, "columns": 4 },
  "nineSlice": [6, 6, 6, 6],
  "favourite": false,
  "notes": "4-frame walk cycle"
}
```

* `importSettings` is free-form and type-specific: `frameWidth/Height`, `columns`, `rows`,
  `tileWidth/Height`, `sampleRate`, `channels`, `size` (fonts), `descriptor`, `source` (where inside
  a source archive the file came from).
* `nineSlice` stores `[x, y, w, h]` insets for UI panels so `NinePatchRect` and the browser agree.
* Tags drive the browser's filters; licence/author/sourceUrl are shown on every card.

## 4. Source index (`sources/<pack>/index.json`)

Written by the library build (`SourceBook`) while the pack is being written, so it can never drift:

```json
{
  "format": "lumen2d.asset-sources",
  "version": 1,
  "engine": "1.0.0",
  "pack": "base",
  "name": "Lumen2D Base",
  "license": "CC0-1.0",
  "author": "Lumen2D contributors",
  "fileCount": 17,
  "files": [
    {
      "path": "sprites/player.png",
      "origin": "generated",
      "recipe": "DemoArt.playerSheet()",
      "source": "engine",
      "license": "CC0-1.0",
      "author": "Lumen2D contributors",
      "importSettings": { "frameWidth": 16, "frameHeight": 20, "columns": 4 },
      "notes": "4-frame walk cycle, 16x20"
    }
  ]
}
```

`origin` is `generated` (an engine recipe can rebuild the file), `imported` (copied out of a source
archive) or `unknown` (no index). The engine reads this offline: in the Studio's asset browser every
card shows its licence, origin and recipe, and a long-press copies the id together with them.

`AssetSourceIndex` is the reader:

```kotlin
val index = AssetSourceIndex.load(platform.bundledFileSystem, "sources")
index.provenance("base:sprites/player.png")   // -> AssetSource(recipe = "DemoArt.playerSheet()", …)
index.archiveFor("kenney")?.tileCount         // -> 180
```

## 5. Imported sources (`sources/<archive>/sources.json`)

Third-party content is vendored — never linked — and described by a descriptor:

```json
{
  "format": "lumen2d.asset-source-archive",
  "version": 1,
  "id": "kenney",
  "packFolder": "kenney-pixel-platformer",
  "name": "Kenney Pixel Platformer",
  "upstream": "https://kenney.nl/assets/pixel-platformer",
  "license": "CC0-1.0",
  "author": "Kenney (www.kenney.nl)",
  "archive": "kenney_pixel-platformer.zip",
  "archiveSha256": "d01a196dbe3cc964e00d83ba3b987df6",
  "tileWidth": 18, "tileHeight": 18, "spacing": 1,
  "sheetColumns": 20, "sheetRows": 9,
  "files": [ { "name": "tilemap_packed.png", "bytes": 5913, "sha256": "a3bbff36594baa36…" } ],
  "licenseText": "…the upstream licence, verbatim…"
}
```

The extracted archive files sit beside it, so the published pack can be rebuilt with
`--export-assets` on a machine that never sees the original download. Import one with:

```bash
java -cp "out/classes/core:out/classes/desktop:$KOTLIN_LIB/kotlin-stdlib.jar" \
     dev.lumen2d.desktop.LumenDesktopKt --import-sources my_kit.zip --dir assets-library/sources
```

`SourceImporter` takes the files a pixel-art tileset kit needs (`Tilemap/*.png`, the `Tilesheet`
descriptors and `License.txt`), hashes them, reads the sheet metrics from the PNG header, and writes
`sources.json`. `KenneyPackBuilder` then publishes that source as the `kenney` pack: the packed
sheet becomes `tiles/tileset.png` (18×18 tiles, 20×9 = 180 tiles), the character sheet becomes
`sprites/characters.png`, both get sidecars with the metrics the browser needs, and the pack gets
`ATTRIBUTION.md` plus a `sources/kenney/index.json` marking every file as `imported`.

## 6. Asset types and categories

`AssetType` (18 values, with the extensions that map to them): `TEXTURE`, `ATLAS`, `TILESET`,
`TILEMAP`, `ANIMATION`, `FONT`, `AUDIO`, `MUSIC`, `SCENE`, `PREFAB`, `SCRIPT`, `SHADER`, `DATA`,
`THEME`, `PARTICLE_PRESET`, `MATERIAL`, `AUDIO_BUS`, `UNKNOWN`. The scanner also *sniffs* JSON files
(an atlas has `regions` + `texture`, a scene has `root`), so a `.json` lands in the right bucket.

`AssetCategory` groups them for the browser: Images, Tiles, Animation, Audio, Fonts, Scenes,
Scripts, Data.

## 7. The published packs

| Pack | Contents | Origin |
| --- | --- | --- |
| `base` | 17 assets: 4 character/pickup sprites, a 12-tile terrain set, 3 UI pieces, a particle, a parallax backdrop, a bitmap font (atlas + descriptor) and 5 sound effects | generated by `AssetPackBuilder` (PNG/WAV writers in the engine) |
| `kenney` | 2 assets: an 18×18 terrain tileset (180 tiles) and a character sheet | imported from Kenney's *Pixel Platformer* (CC0-1.0) via `SourceImporter` + `KenneyPackBuilder` |

Both are CC0-1.0. The base pack's sounds are synthesised by `Tone` (square/triangle/noise with
envelopes), its art by `DemoArt` (procedural pixel routines), and its font by
`FontManager.buildPixelFont()` — the recipes recorded in the source index are the actual call sites.

## 8. Adding your own pack

```bash
mkdir -p assets-library/packs/mypack/tiles
cp my_tiles.png assets-library/packs/mypack/tiles/
cat > assets-library/packs/mypack/pack.json <<'JSON'
{ "id": "mypack", "name": "My Kit", "version": "1.0.0", "license": "CC0-1.0",
  "author": "me", "tags": ["tileset"], "files": ["tiles/my_tiles.png"] }
JSON
cat > assets-library/packs/mypack/tiles/my_tiles.png.meta.json <<'JSON'
{ "displayName": "My tiles", "tags": ["tileset"], "license": "CC0-1.0", "author": "me",
  "importSettings": { "tileWidth": 16, "tileHeight": 16, "columns": 8 } }
JSON
```

Then add `"lib://packs/mypack"` to a project's `assetPacks` and reload. The tests
(`engine-core`: "the shipped library matches its index") will tell you immediately if a manifest
lists a file that does not exist or a file that has no sidecar — that is deliberate: the library is
content, and content is verified like code.

Generated packs should go through `AssetPackBuilder` + `SourceBook` instead of being written by
hand: that is what keeps `pack.json`, the sidecars and the source index consistent after a rebuild.

## 9. Android packaging

`app-android/build.gradle.kts` stages the whole content root into the APK:

```
assets/packs/**     → lib://packs/**      (base + kenney)
assets/sources/**   → lib://sources/**    (provenance shown in the editor)
assets/samples/**   → bundledSamples      (seeded into app storage on first launch)
```

Everything is regenerated first (`:engine-desktop:exportContent`), so a release APK can only contain
content that the current engine produces. See [ANDROID.md](ANDROID.md).
