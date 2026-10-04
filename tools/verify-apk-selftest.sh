#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Lumen2D — self-test for tools/verify-apk.sh.
#
# The APK verifier is what CI trusts to decide whether a built artifact really
# ships the engine's content, so it is tested itself: this script builds two
# synthetic APKs out of the repository's own content (one healthy, one with
# injected defects) and asserts that the verifier passes the first and fails the
# second with the expected checks. Needs no Android SDK and no Java.
#
# Usage: tools/verify-apk-selftest.sh
# -----------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail=0
step() { printf '  %-46s %s\n' "$1" "$2"; }

echo "Lumen2D APK verifier self-test"
echo "--------------------------------------------------------------"

python3 - "$ROOT" "$WORK" <<'PY'
import os, sys, zipfile

root, work = sys.argv[1], sys.argv[2]
CLASSES = (b"dev/lumen2d/core/game/Game dev/lumen2d/studio/MainActivity "
           b"dev/lumen2d/android/AndroidPlatform")
MAPPING = [("assets-library/packs", "assets/packs"),
           ("assets-library/sources", "assets/sources"),
           ("sample-games", "assets/samples")]
STORE_EXT = (".png", ".wav", ".json", ".lumen", ".fnt", ".tmx", ".tsx")


def write_apk(path, drop=(), deflate=()):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00manifest")
        zf.writestr("resources.arsc", b"\x02\x00\x0c\x00arsc" + b"\0" * 4096)
        zf.writestr("classes.dex", b"dex\n035\0" + CLASSES.ljust(300_000, b"\0"))
        for src, dst in MAPPING:
            base = os.path.join(root, src)
            for dirpath, _dirs, files in os.walk(base):
                for name in files:
                    rel = os.path.relpath(os.path.join(dirpath, name), base).replace(os.sep, "/")
                    entry = f"{dst}/{rel}"
                    if entry in drop:
                        continue
                    data = open(os.path.join(dirpath, name), "rb").read()
                    stored = name.lower().endswith(STORE_EXT) and entry not in deflate
                    if stored:
                        zf.writestr(zipfile.ZipInfo(entry), data, compress_type=zipfile.ZIP_STORED)
                    else:
                        zf.writestr(entry, data)


write_apk(os.path.join(work, "healthy-debug.apk"))
write_apk(os.path.join(work, "broken-release.apk"),
          drop=("assets/packs/base/sprites/player.png.meta.json",
                "assets/samples/neon-shooter/input_map.json"),
          deflate=("assets/packs/base/tiles/tileset.png",))
PY

echo "1. healthy APK is accepted"
if "$ROOT/tools/verify-apk.sh" "$WORK/healthy-debug.apk" --expect debug --no-tools > "$WORK/healthy.txt" 2>&1; then
  step "verify-apk.sh healthy-debug.apk" "ok (exit 0)"
else
  step "verify-apk.sh healthy-debug.apk" "FAILED — expected exit 0"
  tail -20 "$WORK/healthy.txt"
  fail=1
fi
grep -q "0 failed" "$WORK/healthy.txt" || { step "healthy run reported 0 failures" "FAILED"; fail=1; }

echo "2. injected defects are detected"
if "$ROOT/tools/verify-apk.sh" "$WORK/broken-release.apk" --expect release --no-tools > "$WORK/broken.txt" 2>&1; then
  step "verify-apk.sh broken-release.apk" "FAILED — expected non-zero exit"
  fail=1
else
  step "verify-apk.sh broken-release.apk" "ok (non-zero exit)"
fi

for check in pack.base.sidecars sample.neon-shooter assets.uncompressed content.packs.exact; do
  if grep -q "FAIL\] $check" "$WORK/broken.txt"; then
    step "detects $check" "ok"
  else
    step "detects $check" "FAILED — check did not report a failure"
    fail=1
  fi
done

echo "3. real SDK tool output is parsed (both aapt2 spellings, apksigner)"
BADGING="$WORK/badging.txt"
SIGNER="$WORK/signer.txt"
cat > "$BADGING" <<'EOF'
package: name='dev.lumen2d.studio.debug' versionCode='1' versionName='1.0.0' compileSdkVersion='36'
minSdkVersion:'24'
targetSdkVersion:'36'
uses-permission: name='android.permission.VIBRATE'
uses-feature-not-required: name='android.hardware.touchscreen'
application-label:'Lumen2D Studio'
launchable-activity: name='dev.lumen2d.studio.MainActivity'  label='' icon=''
EOF
cat > "$SIGNER" <<'EOF'
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Number of signers: 1
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
EOF
for spelling in minSdkVersion sdkVersion; do
  sed "s/^minSdkVersion:/$spelling:/" "$BADGING" > "$WORK/badging-$spelling.txt"
  if python3 "$ROOT/tools/verify-apk.py" --apk "$WORK/healthy-debug.apk" --expect debug \
      --content-root "$ROOT" --badging "$WORK/badging-$spelling.txt" --signer "$SIGNER" \
      --json "$WORK/parsed.json" > "$WORK/parsed.txt" 2>&1; then
    step "parses aapt2 '$spelling' + apksigner output" "ok"
  else
    step "parses aapt2 '$spelling' + apksigner output" "FAILED"
    grep -E "FAIL\]" "$WORK/parsed.txt" | head -5
    fail=1
  fi
done
grep -q "minSdk=24" "$WORK/parsed.txt" && grep -q "DN=CN=Android Debug" "$WORK/parsed.txt" \
  && step "reports the parsed minSdk and certificate" "ok" \
  || { step "reports the parsed minSdk and certificate" "FAILED"; fail=1; }

echo "4. a missing file is reported, not silently ignored"
if "$ROOT/tools/verify-apk.sh" "$WORK/does-not-exist.apk" --no-tools > "$WORK/missing.txt" 2>&1; then
  step "missing APK rejected" "FAILED — expected non-zero exit"
  fail=1
else
  step "missing APK rejected" "ok"
fi

echo "--------------------------------------------------------------"
if [ "$fail" -eq 0 ]; then
  echo "APK verifier self-test: PASS"
else
  echo "APK verifier self-test: FAIL"
  [ "${GITHUB_ACTIONS:-}" = "true" ] && echo "::error title=APK verifier self-test::the verifier did not behave as expected"
fi
exit "$fail"
