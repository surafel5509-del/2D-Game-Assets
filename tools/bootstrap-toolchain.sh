#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Lumen2D — offline toolchain bootstrap.
#
# Installs the two things `tools/build-local.sh` needs in a sandbox that has no
# Android SDK, no Maven/Gradle access and no JDK package manager:
#
#   * a JDK, delivered through the `jdk4py` PyPI wheel
#   * the Kotlin compiler, delivered through the `kotlin-compiler` npm package
#
# Both channels are plain HTTPS downloads, so they work anywhere pip/npm work.
# The Android side of the engine is built by CI (Gradle + Android SDK); this
# toolchain is for compiling and testing `engine-core` / `engine-desktop` and for
# rendering the preview screenshots.
#
# Usage:  tools/bootstrap-toolchain.sh [target-dir]      (default: $HOME/.cache/tools)
# Then:   LUMEN_TOOLCHAIN=<target-dir> tools/build-local.sh all
# -----------------------------------------------------------------------------
set -euo pipefail

TOOLCHAIN="${1:-${HOME}/.cache/tools}"
TMP="$(mktemp -d)"
mkdir -p "$TOOLCHAIN"

echo "==> toolchain -> $TOOLCHAIN"

# --- JDK ---------------------------------------------------------------------
if [ -x "$TOOLCHAIN/jdk/bin/java" ]; then
  echo "    jdk already present"
else
  echo "==> downloading JDK (jdk4py wheel)"
  pip download jdk4py --no-deps -d "$TMP/jdk" >/dev/null
  WHEEL="$(find "$TMP/jdk" -name '*.whl' | head -1)"
  python3 - "$WHEEL" "$TOOLCHAIN/jdk" <<'PY'
import sys, zipfile, os, shutil
wheel, dest = sys.argv[1], sys.argv[2]
os.makedirs(dest, exist_ok=True)
with zipfile.ZipFile(wheel) as z:
    names = [n for n in z.namelist() if "/java-runtime/" in n]
    if not names:
        raise SystemExit("jdk4py wheel has no java-runtime payload")
    prefix = names[0].split("/java-runtime/")[0] + "/java-runtime/"
    for name in names:
        rel = name[len(prefix):]
        if not rel or name.endswith("/"):
            continue
        target = os.path.join(dest, rel)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with z.open(name) as src, open(target, "wb") as out:
            shutil.copyfileobj(src, out)
        if rel.startswith("bin/"):
            os.chmod(target, 0o755)
PY
  echo "    jdk -> $TOOLCHAIN/jdk ($("$TOOLCHAIN/jdk/bin/java" -version 2>&1 | head -1))"
fi

# --- Kotlin compiler ---------------------------------------------------------
if [ -x "$TOOLCHAIN/kotlin/bin/kotlinc" ]; then
  echo "    kotlin compiler already present"
else
  echo "==> downloading Kotlin compiler (npm package)"
  npm install --silent --no-fund --no-audit --prefix "$TMP/kc" kotlin-compiler >/dev/null
  mkdir -p "$TOOLCHAIN/kotlin"
  cp -r "$TMP/kc/node_modules/kotlin-compiler/." "$TOOLCHAIN/kotlin/"
  chmod +x "$TOOLCHAIN/kotlin/bin/"* 2>/dev/null || true
  echo "    kotlin -> $TOOLCHAIN/kotlin"
fi

# --- optional: aapt2 (only useful when CI assets need repacking) -------------
if [ "${LUMEN_WITH_AAPT2:-0}" = "1" ]; then
  npm install --silent --no-fund --no-audit --prefix "$TMP/aapt" aaptjs3 >/dev/null
  cp -r "$TMP/aapt/node_modules/aaptjs3" "$TOOLCHAIN/aapt2" 2>/dev/null || true
  echo "    aapt2 -> $TOOLCHAIN/aapt2"
fi

rm -rf "$TMP"
echo "==> done. Try:  LUMEN_TOOLCHAIN=\"$TOOLCHAIN\" tools/build-local.sh test"
