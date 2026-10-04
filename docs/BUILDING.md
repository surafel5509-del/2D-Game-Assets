# Building, testing and releasing

Lumen2D builds two ways on purpose:

* **Gradle** — the normal path; produces the desktop jar and the Android APK, and runs the same test
  suite CI runs.
* **`tools/build-local.sh`** — a dependency-free path (`kotlinc` + a JDK) that compiles and tests the
  engine anywhere, including air-gapped machines and CI containers without Gradle. This is what the
  repository was developed with, and it is kept working as a first-class build.

---

## 1. Toolchain

| Piece | Version |
| --- | --- |
| Kotlin | 2.4.20 |
| JVM target | 17 (Gradle toolchain and `kotlinc -jvm-target 17`) |
| Android Gradle Plugin | 8.13.0 |
| Gradle | 8.14.3 (CI) |
| Android SDK | compileSdk/targetSdk 36, minSdk 24, build-tools 36.0.0 |
| AndroidX (app only) | `core-ktx` — used solely for `FileProvider` |

`gradle/libs.versions.toml` is the single source of truth for versions; the engine itself has no
runtime dependencies beyond the Kotlin stdlib.

## 2. Offline build (no Gradle, no SDK)

```bash
tools/bootstrap-toolchain.sh          # JDK (jdk4py wheel) + Kotlin (kotlin-compiler npm) -> ~/.cache/tools
tools/build-local.sh all              # compile core + desktop, run tests, package the CLI jar
```

`bootstrap-toolchain.sh` exists because the development sandbox had no JDK, no Android SDK and no
Maven access; it installs a JDK and the Kotlin compiler from PyPI/npm into `$LUMEN_TOOLCHAIN`
(default `~/.cache/tools`). Point `LUMEN_TOOLCHAIN` elsewhere, or set `LUMEN_KOTLINC`/`LUMEN_KOTLIN_LIB`,
to use your own copies.

`build-local.sh` targets:

| Target | What it does |
| --- | --- |
| `compile` | compiles `engine-core` → `out/classes/core` |
| `test` | compiles and runs the engine-core suite (125 tests) |
| `desktop` | also compiles `engine-desktop` → `out/classes/desktop` |
| `preview` | renders the seven demo screenshots into `docs/preview/generated/` |
| `play` | opens the platformer in an AWT window |
| `all` (default) | compile + desktop + test + `out/lumen2d-desktop.jar` |

Run the CLI:

```bash
CP="out/classes/core:out/classes/desktop:$HOME/.cache/tools/kotlin/lib/kotlin-stdlib.jar"
java -cp "$CP" dev.lumen2d.desktop.LumenDesktopKt --help
```

`out/lumen2d-desktop.jar` bundles the Kotlin stdlib, so it runs with a plain `java -jar`.

## 3. Android type check without an SDK

```bash
tools/typecheck-android.sh all        # stubs + engine-android + app-android
```

Hand-written framework declarations live in `tools/android-stubs/src/` (framework + the one AndroidX
class used) and are compiled with `kotlinc` before the real modules, so the Android code is
type-checked exactly as if `android.jar` were present. The `R` class for the app is generated into
`out/` by `tools/generate-r-stub.py`. Nothing here is shipped; the APK is built by Gradle + the SDK
(see [ANDROID.md](ANDROID.md#5-building)).

## 4. Gradle build

```bash
gradle :engine-core:engineTests        # the engine suite
gradle :engine-desktop:run --args="--demo all --out build/preview"
gradle :engine-desktop:exportContent   # regenerate assets-library/ + sample-games/
gradle :engine-desktop:checkSamples    # headless smoke test of every sample game
gradle :app-android:assembleDebug      # the studio APK (also stages engine content)
gradle :app-android:assembleRelease
```

The desktop `application` runs headless by default (`java.awt.headless=true`), so `:engine-desktop:run`
is safe in CI containers.

## 5. Tests

The suite is a zero-dependency runner (`dev.lumen2d.core.test.TestMain`) with its own tiny harness,
so it runs identically under Gradle, `build-local.sh` and CI:

```bash
tools/build-local.sh test              # the whole suite
gradle :engine-core:engineTests -Pfilter=physics   # one section, e.g. during development
```

The output ends with the exact totals — `Lumen2D engine tests — 125 tests, 1606 assertions` /
`all green` — and a failure prints the test name, its section and the failing assertion message.

Coverage: math/geometry, JSON round-trips, the scene graph and serialisation, physics and body
queries, tilemaps and autotiling, particles, animation and tweens, the audio mixer and WAV codec,
the asset pipeline and the shipped asset library (including its provenance index), and the script
interpreter.

The suite deliberately treats generated content as code, because that is where the project actually
broke during development:

* **The library is verified like source** — every published file must exist, carry a sidecar and have
  a provenance entry; every sample scene reference must resolve to a real library file, and the
  tilemap textures must come from the pack that owns the tileset (a cross-pack fallback bug that
  rendered blank tiles has its own regression test).
* **Rendering is reproducible** — the software renderer draws into a fixed-size buffer with the
  engine's seeded `Rng` and a fixed frame count, so `preview` produces the same PNG on any machine.
  CI uploads those previews as an artifact, which turns a rendering regression into a visible diff
  rather than "looks fine to me".

## 6. CI

`.github/workflows/android.yml` (see it for the exact steps) has three jobs:

1. **Engine core tests** — JDK 17 + Gradle 8.14.3 + `:engine-core:engineTests`.
2. **Build Android APK** — SDK 36, regenerate content, `checkSamples`, `assembleDebug/Release`,
   upload `lumen2d-studio-apk`, and publish/refresh the rolling `latest-build` release on `main`,
   `arena/*` and tags.
3. **Desktop renderer smoke test** — regenerate content, check every sample, render the previews,
   upload them as an artifact.

## 7. Repository conventions

* **Generated content is committed.** `assets-library/` and `sample-games/` are outputs of the
  engine, and they are checked in so the repository (and the APK) works without a generation step.
  Regenerate them with `--export-assets` / `--export-samples` after changing the engine, and commit
  the diff — the tests will tell you if they no longer agree.
* **Build output never is.** `out/`, `build/`, `.gradle/` and `docs/preview/generated/frames/` are
  ignored.
* **Provenance is part of a pack.** If you add an asset, add its sidecar and (for generated content)
  the recipe that makes it; the library tests enforce it.
* **The Android modules stay framework-only.** No new AndroidX dependency in `engine-android`, no
  Compose in `app-android` — the editor is built from framework views on purpose.

## 8. Troubleshooting

| Symptom | Fix |
| --- | --- |
| `kotlinc not found` | run `tools/bootstrap-toolchain.sh`, or set `LUMEN_TOOLCHAIN` |
| `lib://` references do not resolve | start the CLI from the repository root, inside it, or set `LUMEN2D_CONTENT` to the content root; the engine logs the exact folder it looked for |
| `No project found` | pass `--game <folder>` or run from a checkout that has `sample-games/` |
| Sample check shows `nodes=0` | pass `--scene scenes/level_1.scene.json`; the default scene for a game may be its menu |
| A scene shows no tiles | the project's `assetPacks` must list `lib://packs/base` (or whichever pack owns the tileset) |
| Android type check complains about `androidx` | the stubs only cover what the repository uses (`FileProvider`); add a declaration in `tools/android-stubs/src/` if you add an API |
