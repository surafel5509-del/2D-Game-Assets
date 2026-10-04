#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Lumen2D offline build.
#
# Compiles engine-core (and optionally engine-desktop) using a plain JDK +
# Kotlin compiler, with no Gradle, Maven or network access required. This is how
# the engine core is validated in environments where only the sources are
# available; CI uses Gradle + the Android SDK to produce the APK.
#
# Usage:
#   tools/build-local.sh compile      # compile engine-core -> out/engine-core.jar
#   tools/build-local.sh test         # compile + run the engine-core test suite
#   tools/build-local.sh desktop      # compile engine-desktop too
#   tools/build-local.sh preview      # render sample-game screenshots/GIF frames
#   tools/build-local.sh all
# -----------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${LUMEN_TOOLCHAIN:-$HOME/.cache/tools}"
OUT="$ROOT/out"

export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk}"
export PATH="$JAVA_HOME/bin:$TOOLCHAIN/kotlin/bin:$PATH"

# The Kotlin runtime must be on the classpath when running compiled code.
find_stdlib() {
  find "$TOOLCHAIN/kotlin/lib" -maxdepth 1 -name 'kotlin-stdlib.jar' | head -1
}
STDLIB="$(find_stdlib)"
if [ -z "$STDLIB" ]; then
  echo "kotlin-stdlib.jar not found under $TOOLCHAIN/kotlin/lib" >&2
  exit 1
fi

if ! command -v kotlinc >/dev/null 2>&1; then
  echo "kotlinc not found. Set LUMEN_TOOLCHAIN to a folder containing jdk/ and kotlin/." >&2
  exit 1
fi

mkdir -p "$OUT/classes"
JVM_TARGET="${LUMEN_JVM_TARGET:-17}"
COMMON_FLAGS=(-jvm-target "$JVM_TARGET" -nowarn)

compile_core() {
  echo "==> compiling engine-core"
  rm -rf "$OUT/classes/core"; mkdir -p "$OUT/classes/core"
  kotlinc "${COMMON_FLAGS[@]}" \
    -d "$OUT/classes/core" \
    $(find "$ROOT/engine-core/src/main/kotlin" -name '*.kt' | sort)
  echo "    ok -> $OUT/classes/core"
}

compile_desktop() {
  echo "==> compiling engine-desktop"
  rm -rf "$OUT/classes/desktop"; mkdir -p "$OUT/classes/desktop"
  kotlinc "${COMMON_FLAGS[@]}" \
    -classpath "$OUT/classes/core" \
    -d "$OUT/classes/desktop" \
    $(find "$ROOT/engine-desktop/src/main/kotlin" -name '*.kt' | sort)
  echo "    ok -> $OUT/classes/desktop"
}

run_tests() {
  echo "==> compiling + running engine-core tests"
  rm -rf "$OUT/classes/test"; mkdir -p "$OUT/classes/test"
  kotlinc "${COMMON_FLAGS[@]}" \
    -classpath "$OUT/classes/core" \
    -d "$OUT/classes/test" \
    $(find "$ROOT/engine-core/src/test/kotlin" -name '*.kt' | sort)
  java -cp "$OUT/classes/core:$OUT/classes/test:$STDLIB" dev.lumen2d.core.test.TestMainKt
}

build_desktop_jar() {
  # `jar` may be missing from minimal JDK distributions, so the archive is written with Python's
  # zipfile (sorted entries + fixed timestamps keep the jar byte-for-byte reproducible).
  local main='dev.lumen2d.desktop.LumenDesktopKt'
  local dest="$OUT/lumen2d-desktop.jar"
  echo "==> packaging desktop jar"
  python3 - "$dest" "$main" "$OUT/classes/core" "$OUT/classes/desktop" "$STDLIB" <<'PYEOF'
import os, sys, zipfile
dest, main, core, desktop, stdlib = sys.argv[1:6]
entries = []
for root_dir in (core, desktop):
    for base, _dirs, files in os.walk(root_dir):
        for name in files:
            if name.endswith('.class'):
                full = os.path.join(base, name)
                entries.append((os.path.relpath(full, root_dir).replace(os.sep, '/'), full))
with zipfile.ZipFile(stdlib) as lib:
    for info in lib.infolist():
        if info.filename.endswith('.class') and not info.filename.startswith('META-INF/versions/'):
            entries.append((info.filename, None, lib.read(info)))
with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as out:
    def write(name, data):
        info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        out.writestr(info, data)
    write('META-INF/MANIFEST.MF', ('Manifest-Version: 1.0\r\nMain-Class: %s\r\n\r\n' % main).encode())
    seen = set()
    for entry in sorted(entries, key=lambda e: e[0]):
        name = entry[0]
        if name in seen:
            continue
        seen.add(name)
        data = entry[2] if len(entry) > 2 else open(entry[1], 'rb').read()
        write(name, data)
print('    ok -> %s (%d entries, kotlin-stdlib bundled)' % (dest, len(seen)))
PYEOF
}

case "${1:-all}" in
  compile) compile_core ;;
  test)    compile_core; run_tests ;;
  desktop) compile_core; compile_desktop ;;
  preview) compile_core; compile_desktop
           java -cp "$OUT/classes/core:$OUT/classes/desktop:$STDLIB" dev.lumen2d.desktop.LumenDesktopKt \
             --demo all --out "$ROOT/docs/preview/generated" ;;
  play)    compile_core; compile_desktop
           java -cp "$OUT/classes/core:$OUT/classes/desktop:$STDLIB" dev.lumen2d.desktop.LumenDesktopKt ;;
  all)     compile_core; compile_desktop; run_tests; build_desktop_jar ;;
  *) echo "unknown target: $1" >&2; exit 2 ;;
esac
