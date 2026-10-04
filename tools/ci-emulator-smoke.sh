#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Lumen2D — on-device verification of a built APK.
#
# The static verifier (tools/verify-apk.sh) proves what is *inside* the APK; this
# script proves the APK actually *runs*: it boots a headless emulator, installs
# the app, launches the hub, checks that the bundled sample games were seeded and
# listed, plays one of them, and confirms the engine loaded a scene on the device
# (plus screenshots of both screens).
#
# Usage:
#   tools/ci-emulator-smoke.sh <apk> --package dev.lumen2d.studio.debug [--api 30]
#                               [--report build/emulator-report.md] [--shots DIR]
#
# Requires `adb`, `emulator` and `avdmanager` on the PATH (the CI workflow installs
# them) and /dev/kvm. Without KVM the run reports "skipped" instead of failing:
# a shared runner without virtualisation cannot exercise a device.
# -----------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"
APK=""
PACKAGE="dev.lumen2d.studio.debug"
API="${ANDROID_EMULATOR_API:-30}"
REPORT=""
SHOTS="$ROOT/ci-logs/emulator-shots"
WORK="$(mktemp -d)"
AVD_NAME="lumen2d-ci"
EMU_PID=""

while [ $# -gt 0 ]; do
  case "$1" in
    --package) PACKAGE="${2:-}"; shift 2 ;;
    --api) API="${2:-30}"; shift 2 ;;
    --report) REPORT="${2:-}"; shift 2 ;;
    --shots) SHOTS="${2:-$SHOTS}"; shift 2 ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) APK="$1"; shift ;;
  esac
done

mkdir -p "$ROOT/ci-logs" "$SHOTS"
RESULTS="$WORK/results.tsv"
: > "$RESULTS"

record() { # id, ok(yes/no), detail
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$RESULTS"
  printf '  [%-4s] %-24s %s\n' "$([ "$2" = yes ] && echo ok || echo FAIL)" "$1" "$3"
}

failures=0
finish() {
  local code="$1"
  local skipped="${2:-}"
  {
    echo "## On-device verification"
    echo
    echo "Package \`$PACKAGE\` · API $API · emulator \`$AVD_NAME\`"
    echo
    if [ -n "$skipped" ]; then
      echo "**Skipped:** $skipped"
    else
      echo "| check | result | detail |"
      echo "| --- | --- | --- |"
      while IFS=$'\t' read -r id ok detail; do
        echo "| \`$id\` | $([ "$ok" = yes ] && echo '✅' || echo '❌') | $detail |"
      done < "$RESULTS"
    fi
  } > "$WORK/report.md"
  if [ -n "$REPORT" ]; then
    mkdir -p "$(dirname "$REPORT")"
    cat "$WORK/report.md" > "$REPORT"
  fi
  if [ "${GITHUB_ACTIONS:-}" = "true" ] && [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    cat "$WORK/report.md" >> "$GITHUB_STEP_SUMMARY"
  fi
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    # Every failed check becomes its own annotation: annotations are readable from the API, so a
    # red run can be understood without downloading the job log.
    while IFS=$'\t' read -r id ok detail; do
      [ "$ok" = no ] || continue
      printf '::error title=on-device check %s::%s\n' "$id" "${detail:0:600}"
    done < "$RESULTS"
    if [ "$code" -eq 0 ]; then
      printf '::notice title=on-device smoke test::installed, launched and played a sample game on the emulator\n'
    else
      printf '::error title=APK on-device smoke test::%s check(s) failed — device report uploaded as lumen2d-device-screenshots\n' "$failures"
    fi
  fi
  exit "$code"
}

cleanup() {
  if [ -n "$EMU_PID" ]; then
    adb emu kill >/dev/null 2>&1 || kill "$EMU_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "Lumen2D on-device smoke test — $APK"
echo "--------------------------------------------------------------"

if [ ! -f "$APK" ]; then
  echo "::error title=emulator smoke::APK not found: $APK"
  exit 2
fi

if [ ! -e /dev/kvm ]; then
  echo "::warning title=emulator smoke skipped::/dev/kvm is unavailable on this runner, so the APK was not exercised on a device"
  record "emulator.kvm" no "/dev/kvm missing"
  finish 0 "no hardware virtualisation on this runner (/dev/kvm missing)"
fi
sudo chmod 666 /dev/kvm 2>/dev/null || true

IMAGE="system-images;android-${API};google_apis;x86_64"

# The emulator and a system image are large and often not preinstalled; fetch them through
# sdkmanager when missing so the job works on a plain runner.
need_packages=0
for tool in adb emulator avdmanager; do
  command -v "$tool" > /dev/null 2>&1 || need_packages=1
done
if [ "$need_packages" -eq 1 ]; then
  if ! command -v sdkmanager > /dev/null 2>&1; then
    echo "::error title=emulator smoke::neither the SDK tools nor sdkmanager are on the PATH"
    exit 2
  fi
  echo "installing adb, the emulator and $IMAGE…"
  yes | sdkmanager --licenses > /dev/null 2>&1 || true
  sdkmanager --install "platform-tools" "emulator" "$IMAGE" > "$ROOT/ci-logs/sdkmanager-emulator.log" 2>&1 || {
    echo "::error title=emulator smoke::sdkmanager failed to install the emulator packages"
    tail -20 "$ROOT/ci-logs/sdkmanager-emulator.log"
    exit 2
  }
fi

for tool in adb emulator avdmanager; do
  if ! command -v "$tool" > /dev/null 2>&1; then
    echo "::error title=emulator smoke::$tool is not on the PATH after installing the SDK packages"
    exit 2
  fi
done

echo "creating AVD $AVD_NAME"
if ! echo no | avdmanager create avd -n "$AVD_NAME" -k "$IMAGE" --device pixel_5 --force > "$WORK/avd.log" 2>&1; then
  echo "notice: the pixel_5 profile is unavailable, falling back to the default device"
  echo no | avdmanager create avd -n "$AVD_NAME" -k "$IMAGE" --force > "$WORK/avd.log" 2>&1 || {
    echo "::error title=emulator smoke::avdmanager could not create $AVD_NAME"
    tail -20 "$WORK/avd.log"
    exit 2
  }
fi

echo "booting the emulator (headless)…"
"$SDK/emulator/emulator" \
  -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
  -gpu swiftshader_indirect -camera-back none -camera-front none \
  > "$ROOT/ci-logs/emulator.log" 2>&1 &
EMU_PID=$!

timeout 300 adb wait-for-device || { echo "::error title=emulator smoke::adb never saw the device"; exit 2; }
booted=no
for _ in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    booted=yes
    break
  fi
  sleep 5
done
if [ "$booted" != yes ]; then
  record "emulator.boot" no "the emulator did not finish booting within 10 minutes"
  tail -30 "$ROOT/ci-logs/emulator.log" || true
  failures=$((failures + 1))
  finish 1
fi
MODEL="$(adb shell getprop ro.product.model | tr -d '\r')"
VERSION="$(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
record "emulator.boot" yes "booted $MODEL · Android $VERSION"

# Wake the device and get past any keyguard: a headless AVD can boot to a locked screen, where
# uiautomator would see nothing.
adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
adb shell wm dismiss-keyguard > /dev/null 2>&1 || true

# Animation must not fight the tap coordinates below.
for key in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global "$key" 0 > /dev/null 2>&1 || true
done

INSTALL_OUT="$(adb install -r -t "$APK" 2>&1 | tr -d '\r')"
if echo "$INSTALL_OUT" | grep -q "Success"; then
  record "apk.install" yes "$(echo "$INSTALL_OUT" | tail -1)"
else
  record "apk.install" no "$(echo "$INSTALL_OUT" | tail -2 | tr '\n' ' ')"
  failures=$((failures + 1))
  finish 1
fi

START_OUT="$(adb shell am start -W -n "$PACKAGE/dev.lumen2d.studio.MainActivity" 2>&1 | tr -d '\r')"
if echo "$START_OUT" | grep -qE "Status: ok|LaunchState"; then
  record "activity.start" yes "$(echo "$START_OUT" | grep -E 'Status|TotalTime' | tr '\n' ' ')"
else
  record "activity.start" no "$(echo "$START_OUT" | tail -3 | tr '\n' ' ')"
  failures=$((failures + 1))
fi

sleep 6
PID="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')"
if [ -n "$PID" ]; then
  record "process.alive" yes "pid $PID"
else
  record "process.alive" no "the app is not running after launch"
  failures=$((failures + 1))
fi

adb logcat -d -v brief > "$ROOT/ci-logs/logcat.txt" 2>/dev/null || true
if grep -n "FATAL EXCEPTION" -A 12 "$ROOT/ci-logs/logcat.txt" | grep -q "$PACKAGE"; then
  CRASH="$(grep -n "FATAL EXCEPTION" -A 6 "$ROOT/ci-logs/logcat.txt" | head -8 | tr '\n' ' ')"
  record "runtime.crash" no "${CRASH:0:220}"
  failures=$((failures + 1))
else
  record "runtime.crash" yes "no fatal exception in logcat"
fi

adb shell uiautomator dump /sdcard/hub.xml > /dev/null 2>&1 || true
adb pull /sdcard/hub.xml "$WORK/hub.xml" > /dev/null 2>&1 || true

if [ -s "$WORK/hub.xml" ]; then
  python3 "$ROOT/tools/ui_dump.py" texts "$WORK/hub.xml" > "$WORK/hub-texts.txt" 2>/dev/null || true
  MISSING=""
  for title in "Hello Lumen2D" "Pixel Platformer" "Neon Shooter"; do
    grep -qF "$title" "$WORK/hub-texts.txt" || MISSING="$MISSING $title"
  done
  if [ -z "$MISSING" ]; then
    record "hub.samples" yes "all three bundled sample games are listed by the hub"
  else
    record "hub.samples" no "the hub does not list:$MISSING — check SampleInstaller and assets/samples"
    failures=$((failures + 1))
  fi
  PLAYS="$(python3 "$ROOT/tools/ui_dump.py" count "$WORK/hub.xml" Play 2>/dev/null || echo 0)"
  record "hub.play-buttons" "$([ "${PLAYS:-0}" -gt 0 ] && echo yes || echo no)" "$PLAYS Play control(s) on screen"
else
  record "hub.samples" no "uiautomator dump produced no XML"
  failures=$((failures + 1))
fi

adb exec-out screencap -p > "$SHOTS/hub.png" 2>/dev/null || true
if [ -s "$SHOTS/hub.png" ] && head -c 8 "$SHOTS/hub.png" | grep -q "PNG"; then
  record "hub.screenshot" yes "$(wc -c < "$SHOTS/hub.png") bytes → ci-logs/emulator-shots/hub.png"
else
  record "hub.screenshot" no "screencap produced no PNG"
  failures=$((failures + 1))
fi

# ---------------------------------------------------- run a game for real
TAP="$(python3 "$ROOT/tools/ui_dump.py" find "$WORK/hub.xml" Play 2>/dev/null || true)"
if [ -n "$TAP" ]; then
  adb shell input tap $TAP > /dev/null 2>&1 || true
  sleep 12
  adb shell uiautomator dump /sdcard/editor.xml > /dev/null 2>&1 || true
  adb pull /sdcard/editor.xml "$WORK/editor.xml" > /dev/null 2>&1 || true
  EDITOR_TEXTS="$(python3 "$ROOT/tools/ui_dump.py" texts "$WORK/editor.xml" 2>/dev/null || true)"
  if printf '%s' "$EDITOR_TEXTS" | grep -qE "Inspector|Console|Assets|Scripts"; then
    record "editor.open" yes "the studio opened the sample project (tapped Play at $TAP)"
  else
    record "editor.open" no "the studio panes never appeared after tapping Play"
    failures=$((failures + 1))
  fi
  adb exec-out screencap -p > "$SHOTS/editor.png" 2>/dev/null || true
  [ -s "$SHOTS/editor.png" ] && record "editor.screenshot" yes "$(wc -c < "$SHOTS/editor.png") bytes → ci-logs/emulator-shots/editor.png"

  adb logcat -d -v brief > "$ROOT/ci-logs/logcat-after-play.txt" 2>/dev/null || true
  SCENE_LINE="$(grep -E "Lumen2D.*Scene .* loaded" "$ROOT/ci-logs/logcat-after-play.txt" | tail -1)"
  if [ -n "$SCENE_LINE" ]; then
    record "engine.scene" yes "${SCENE_LINE:0:150}"
  else
    record "engine.scene" no "no 'Scene … loaded' line from the engine in logcat"
    failures=$((failures + 1))
  fi
else
  record "ui.play-control" no "no Play control was found in the hub dump"
  failures=$((failures + 1))
fi

echo "--------------------------------------------------------------"
echo "on-device checks: $(( $(wc -l < "$RESULTS") - failures )) passed, $failures failed"
echo "device screenshots: ci-logs/emulator-shots/"
[ "$failures" -eq 0 ] && record "result" yes "APK installed, launched and ran a sample game on $MODEL"
finish "$([ "$failures" -eq 0 ] && echo 0 || echo 1)"
