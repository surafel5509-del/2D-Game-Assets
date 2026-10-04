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

`.github/workflows/android.yml` (see it for the exact steps) runs on every push, pull request, tag and
manual dispatch, and is built around one rule: **an artifact is not finished until it has been
verified.**

| Job | What it proves |
| --- | --- |
| **Engine core tests (JVM)** | the engine suite passes under Gradle 8.14.3 + JDK 17 (`:engine-core:engineTests`) |
| **Asset library, samples and previews** | regenerating `assets-library/`/`sample-games/` changes nothing (committed content == engine output), every sample runs headlessly, the seven previews render, and the APK verifier's own self-test passes |
| **Build and verify the Android APK** | SDK 36 assembles debug + release; both APKs pass the static verification below |
| **Install and run the APK on an emulator** | a headless API 30 emulator installs the debug APK, the hub launches and lists all three bundled samples, playing one makes the engine log a scene load, and creating a project from **+ New project** reaches the studio without a fatal exception |
| **Install and run the release APK on an emulator** | the same run for the R8-minified, resource-shrunk release APK — minification is whole-program, so the release build has to prove itself on a device too |
| **Install and run the release APK on a current Android** | the release APK once more on a recent API level (`ANDROID_EMULATOR_MODERN_API`), because the base device runs use an old image and platform rules changed a lot since |
| **Re-verify the artifacts and publish the release** | the *uploaded* APKs are downloaded and verified again independently, then published as the rolling `latest-build` release with checksums, both verification reports and the device report in the notes, and the emulator screenshots as release assets |

Every build step runs through `tools/ci-run.sh`, which tees its output to `ci-logs/` (uploaded as an
artifact) and turns a failure into GitHub annotations plus a step summary — a red run can be
understood from the run page alone, without downloading logs.

### Verifying an APK by hand

```bash
tools/verify-apk.sh app-android/build/outputs/apk/debug/app-android-debug.apk --expect debug
```

`tools/verify-apk.sh` drives the SDK's own tools (`aapt2 dump badging`, `aapt2 dump files`,
`apksigner verify`) and then `tools/verify-apk.py` for the content, which is where the engine-specific
mistakes live:

* the APK is a valid, CRC-clean zip with `AndroidManifest.xml`, `classes.dex` (magic-checked) and
  `resources.arsc`, and a real signature (v2 or v3) with a certificate;
* package/version/minSdk 24/targetSdk 36/launcher/label/permissions match what the project declares;
* every pack's `pack.json` lists exactly the assets that are packaged, every asset carries its
  `*.meta.json` sidecar, and each sidecar parses and names a licence;
* the provenance index (`assets/sources/<pack>/index.json`) describes exactly the assets the pack
  publishes, and every record resolves to a packaged file;
* all three sample projects are complete inside the APK — `project.lumen`, an `input_map.json` that
  parses, at least one scene, at least one script, and the start scene the manifest points at;
* media and JSON assets are stored uncompressed (the `noCompress` list really applied);
* the packaged asset tree is *identical* to the repository's (`--content-root`), so the APK cannot
  ship stale or hand-edited content;
* on a debug build, the engine classes (`Game`, `MainActivity`, `AndroidPlatform`) are present in the
  dex — on a release build the shrunk dex size is checked instead.

Everything is reported as a markdown table, written to the step summary and, on failure, to
annotations. `tools/verify-apk-selftest.sh` builds a healthy APK and a deliberately broken one out of
the repository's own content and asserts the verifier accepts one and rejects the other, so the
verifier cannot rot silently; it needs no SDK and runs in CI on every push.

### Running the on-device smoke test

```bash
tools/ci-emulator-smoke.sh app-android/build/outputs/apk/debug/app-android-debug.apk \
  --package dev.lumen2d.studio.debug --api 30 \
  --report build/emulator-report.md --shots ci-logs/emulator-shots
```

It needs `adb`, `emulator` and `avdmanager` plus `/dev/kvm`; it puts the SDK's own directories on
`PATH` itself and installs the emulator packages when they are missing. Without virtualisation it
records a *skipped* report and exits 0 — a shared runner cannot be blamed for a missing device. The
checks are listed in the report table and the screenshots land in `--shots`.

CI runs it three times in parallel — debug on API 30, release on API 30, release on a current API
level — and `--shot-prefix` keeps each run's files from overwriting another run's (they share the
workspace and the release).

The test drives the real UI, so it locates controls with `dumpsys window` (a focused activity),
`uiautomator dump` and `tools/ui_dump.py find <dump.xml> <label>` (which prefers an exact, clickable,
on-screen match); `tools/ui_dump.py findclass <dump.xml> <class>` handles widgets that have no label
to match, such as the new-project dialog's text field.

## 7. Repository conventions

* **Generated content is committed and reproducible.** `assets-library/` and `sample-games/` are
  outputs of the engine, checked in so the repository (and the APK) works without a generation step.
  Regenerating them is byte-for-byte deterministic — `Project.save(savedAtMillis)` lets the sample
  generator write a fixed timestamp instead of "now" — so CI regenerates the content on every push
  and fails the build if `git diff` is not empty. After changing the engine, run
  `--export-assets` / `--export-samples` (or `gradle :engine-desktop:exportContent`) and commit the
  result.
* **Build output never is.** `out/`, `build/`, `.gradle/`, `ci-logs/` (what `tools/ci-run.sh`
  captures locally) and `docs/preview/generated/frames/` are ignored.
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
