#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Lumen2D — verify a built Studio APK.
#
# Runs the SDK's own tooling over the artifact (aapt2 for the manifest, apksigner
# for the signature) and then tools/verify-apk.py for the content checks: the
# packaged asset library, its metadata sidecars, the provenance index and the
# three sample games. Works without an SDK too — the missing checks are reported
# as skipped instead of passing silently.
#
# Usage:
#   tools/verify-apk.sh <apk> [--expect debug|release|any] [--content-root DIR]
#                           [--no-tools] [--report FILE]
# -----------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK=""
EXPECT="any"
CONTENT_ROOT="$ROOT"
NO_TOOLS=0
REPORT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --expect) EXPECT="${2:-any}"; shift 2 ;;
    --content-root) CONTENT_ROOT="${2:-$ROOT}"; shift 2 ;;
    --report) REPORT="${2:-}"; shift 2 ;;
    --no-tools) NO_TOOLS=1; shift ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) APK="$1"; shift ;;
  esac
done

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "usage: tools/verify-apk.sh <apk> [--expect debug|release|any] [--content-root DIR] [--no-tools] [--report FILE]" >&2
  echo "error: APK not found: '${APK:-<none>}'" >&2
  exit 2
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- locate the SDK build-tools (aapt2/apksigner) -----------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -d /usr/local/lib/android/sdk ]; then
  SDK=/usr/local/lib/android/sdk
fi

AAPT2=""
APKSIGNER=""
if [ "$NO_TOOLS" -eq 0 ] && [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
  BUILD_TOOLS="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
  [ -x "${BUILD_TOOLS}aapt2" ] && AAPT2="${BUILD_TOOLS}aapt2"
  [ -x "${BUILD_TOOLS}apksigner" ] && APKSIGNER="${BUILD_TOOLS}apksigner"
fi

if [ -n "$AAPT2" ]; then
  "$AAPT2" dump badging "$APK" > "$TMP/badging.txt" 2> "$TMP/badging.err" || {
    echo "warning: aapt2 dump badging failed:"; cat "$TMP/badging.err"; }
  "$AAPT2" dump files "$APK" > "$TMP/files.txt" 2>/dev/null || true
else
  echo "note: aapt2 not found (set ANDROID_HOME or pass --no-tools) — manifest checks will be skipped"
fi

if [ -n "$APKSIGNER" ]; then
  "$APKSIGNER" verify --verbose --print-certs "$APK" > "$TMP/signer.txt" 2>&1 || true
else
  echo "note: apksigner not found — signature checks will be skipped"
fi

if [ -n "$AAPT2" ] && [ ! -s "$TMP/badging.txt" ]; then
  echo "warning: no badging output produced" >&2
fi

# --- structural + content verification ---------------------------------------
ARGS=(--apk "$APK" --expect "$EXPECT")
[ -d "$CONTENT_ROOT/assets-library/packs" ] && ARGS+=(--content-root "$CONTENT_ROOT")
[ -s "$TMP/badging.txt" ] && ARGS+=(--badging "$TMP/badging.txt")
[ -s "$TMP/files.txt" ] && ARGS+=(--files "$TMP/files.txt")
[ -s "$TMP/signer.txt" ] && ARGS+=(--signer "$TMP/signer.txt")
[ -n "$REPORT" ] && ARGS+=(--report "$REPORT")
ARGS+=(--json "$TMP/result.json")

python3 "$ROOT/tools/verify-apk.py" "${ARGS[@]}"
STATUS=$?

if [ "$STATUS" -ne 0 ] && [ -f "$TMP/result.json" ]; then
  echo
  echo "failed checks:"
  python3 - "$TMP/result.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
for check in data.get("failures", []):
    print(f"  - {check['id']}: {check['detail']}")
PY
fi
exit "$STATUS"
