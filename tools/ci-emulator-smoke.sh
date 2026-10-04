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

# avdmanager and the emulator do not always agree on where AVDs live (the emulator only looks in
# $ANDROID_AVD_HOME, $ANDROID_SDK_HOME/avd and $HOME/.android/avd), so pin it for both.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"
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

# The emulator explains startup failures only in its own log; annotate the tail so a red run
# is diagnosable without the (undownloadable) artifact.
annotate_emulator_log() {
  [ "${GITHUB_ACTIONS:-}" = "true" ] || return 0
  [ -s "$ROOT/ci-logs/emulator.log" ] || return 0
  local escaped
  escaped="$(tail -n 12 "$ROOT/ci-logs/emulator.log" | sed -e 's/%/%25/g' -e ':a;N;$!ba;s/\n/%0A/g' | cut -c1-900)"
  printf '::error title=emulator log (tail)::%s\n' "$escaped"
}

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
  if [ "$code" -ne 0 ]; then
    annotate_emulator_log
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

# The emulator must be able to see what avdmanager just created, or it exits with
# "Unknown AVD name" — a check that saves five minutes of confusing silence.
AVDS="$("$SDK/emulator/emulator" -list-avds 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
if "$SDK/emulator/emulator" -list-avds 2>/dev/null | tr -d '\r' | grep -qx "$AVD_NAME"; then
  record "avd.visible" yes "the emulator lists $AVD_NAME (avd home: $ANDROID_AVD_HOME)"
else
  record "avd.visible" no "the emulator does not see $AVD_NAME; avd home: $ANDROID_AVD_HOME; avds it lists: ${AVDS:-none}"
  failures=$((failures + 1))
  finish 1
fi

echo "booting the emulator (headless)…"
"$SDK/emulator/emulator" \
  -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
  -gpu swiftshader_indirect -camera-back none -camera-front none \
  > "$ROOT/ci-logs/emulator.log" 2>&1 &
EMU_PID=$!

# Wait for the device, but notice quickly when the emulator process itself died — a failed
# start is by far the most common reason for "no device", and waiting five minutes for it
# wastes a runner.
booted=no
for _ in $(seq 1 60); do
  if ! kill -0 "$EMU_PID" 2>/dev/null; then
    record "emulator.start" no "the emulator process exited during startup"
    annotate_emulator_log
    failures=$((failures + 1))
    finish 1
  fi
  if adb devices 2>/dev/null | grep -qE "emulator-[0-9]+[[:space:]]+device"; then
    booted=starting
    break
  fi
  sleep 5
done
if [ "$booted" != starting ]; then
  record "emulator.start" no "no emulator device appeared within five minutes"
  annotate_emulator_log
  failures=$((failures + 1))
  finish 1
fi
echo "device visible to adb; waiting for the boot to complete"
booted=no
for _ in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    booted=yes
    break
  fi
  sleep 5
done
if [ "$booted" != yes ]; then
  record "emulator.boot" no "the emulator did not finish booting within ten minutes"
  failures=$((failures + 1))
  finish 1
fi
MODEL="$(adb shell getprop ro.product.model | tr -d '\r')"
VERSION="$(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
record "emulator.boot" yes "booted $MODEL · Android $VERSION"

# Wake the device and get past any keyguard: a headless AVD can boot to a locked screen where
# uiautomator would see nothing, and it likes to fall asleep again mid-run, so ask it to stay on.
adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
adb shell svc power stayon true > /dev/null 2>&1 || true
adb shell settings put system screen_off_timeout 1800000 > /dev/null 2>&1 || true

# A headless device gives no other signal that the hub is on screen, so re-dump until the
# sample titles show up instead of taking one dump and hoping it caught a laid-out window.
dump_screen() { # <local xml> <texts file>
  local local_xml="$1" texts="$2" remote dump_out=""
  adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
  adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
  for remote in /sdcard/window.xml /data/local/tmp/window.xml; do
    dump_out="$(adb shell uiautomator dump "$remote" 2>&1 | tr -d '\r' | tail -n 2)"
    : > "$local_xml"
    adb exec-out cat "$remote" > "$local_xml" 2>/dev/null || true
    [ -s "$local_xml" ] && break
  done
  # uiautomator's own words ("could not get idle state", …) explain an empty dump; keep them.
  printf '%s\n' "$dump_out" > "$WORK/hub-dump.log"
  python3 "$ROOT/tools/ui_dump.py" texts "$local_xml" > "$texts" 2>/dev/null || true
}

dump_hub() { dump_screen "$WORK/hub.xml" "$WORK/hub-texts.txt"; }

# Evidence for the annotations: a failing dump is useless unless it says what the device showed.
annotate_ui_evidence() {
  [ "${GITHUB_ACTIONS:-}" = "true" ] || return 0
  local focus texts
  focus="$(adb shell dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp' | tr -d '\r' | sed -e 's/%/%25/g' | cut -c1-200)"
  texts="$(head -n 20 "$WORK/hub-texts.txt" 2>/dev/null | tr '\n' '|' | sed -e 's/%/%25/g' | cut -c1-700)"
  printf '::error title=current focus::%s\n' "${focus:-unknown}"
  printf '::error title=hub dump (%s bytes)::%s\n' "$(wc -c < "$WORK/hub.xml" 2>/dev/null || echo 0)" "${texts:-no texts found}"
  printf '::error title=uiautomator said::%s\n' "$(tail -n 1 "$WORK/hub-dump.log" 2>/dev/null | sed -e 's/%/%25/g' | cut -c1-300)"
  return 0
}

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

MISSING="all three"
for _ in $(seq 1 12); do
  sleep 5
  dump_hub
  MISSING=""
  for title in "Hello Lumen2D" "Pixel Platformer" "Neon Shooter"; do
    grep -qF "$title" "$WORK/hub-texts.txt" || MISSING="$MISSING $title"
  done
  [ -z "$MISSING" ] && break
done

PLAYS="$(python3 "$ROOT/tools/ui_dump.py" count "$WORK/hub.xml" Play 2>/dev/null || echo 0)"
if [ -s "$WORK/hub.xml" ] && [ -z "$MISSING" ]; then
  record "hub.samples" yes "all three bundled sample games are listed by the hub"
elif [ -s "$WORK/hub.xml" ]; then
  record "hub.samples" no "the hub does not list:$MISSING — check SampleInstaller and assets/samples"
  failures=$((failures + 1))
else
  record "hub.samples" no "uiautomator produced no XML dump"
  failures=$((failures + 1))
fi

if [ "${PLAYS:-0}" -gt 0 ]; then
  record "hub.play-buttons" yes "$PLAYS Play control(s) on screen"
else
  record "hub.play-buttons" no "no Play control on the hub screen"
  failures=$((failures + 1))
  annotate_ui_evidence
fi

adb exec-out screencap -p > "$SHOTS/hub.png" 2>/dev/null || true
if [ -s "$SHOTS/hub.png" ] && head -c 8 "$SHOTS/hub.png" | grep -q "PNG"; then
  record "hub.screenshot" yes "$(wc -c < "$SHOTS/hub.png") bytes → ci-logs/emulator-shots/hub.png"
else
  record "hub.screenshot" no "screencap produced no PNG"
  failures=$((failures + 1))
fi

# ---------------------------------------------------- run a game for real
# Tap Play and wait for the studio. The tap may land on a stale layout (or on a title that
# merely contains "play"), so the coordinates are re-read from a fresh dump before retrying.
TAP=""
opened=no
for attempt in 1 2; do
  TAP="$(python3 "$ROOT/tools/ui_dump.py" find "$WORK/hub.xml" Play 2>/dev/null || true)"
  [ -n "$TAP" ] || break
  echo "tapping Play at $TAP (attempt $attempt)"
  adb shell input tap $TAP > /dev/null 2>&1 || true
  for _ in $(seq 1 8); do
    sleep 5
    dump_screen "$WORK/editor.xml" "$WORK/editor-texts.txt"
    if grep -qE "Inspector|Console|Assets|Scripts" "$WORK/editor-texts.txt"; then
      opened=yes
      break 2
    fi
  done
  dump_hub
done

if [ -z "$TAP" ]; then
  record "ui.play-control" no "no Play control was found in the hub dump"
  failures=$((failures + 1))
elif [ "$opened" = yes ]; then
  record "editor.open" yes "the studio opened the sample project (tapped Play at $TAP)"
else
  record "editor.open" no "the studio panes never appeared after tapping Play at $TAP"
  failures=$((failures + 1))
  annotate_ui_evidence
fi

if [ "$opened" = yes ]; then
  adb exec-out screencap -p > "$SHOTS/editor.png" 2>/dev/null || true
  [ -s "$SHOTS/editor.png" ] && record "editor.screenshot" yes "$(wc -c < "$SHOTS/editor.png") bytes → ci-logs/emulator-shots/editor.png"

  # Playing also has to reach the engine: its own log line proves a scene was loaded on device.
  sleep 5
  adb logcat -d -v brief > "$ROOT/ci-logs/logcat-after-play.txt" 2>/dev/null || true
  SCENE_LINE="$(grep -E "Scene '?[^']*'? loaded" "$ROOT/ci-logs/logcat-after-play.txt" | tail -1)"
  if [ -n "$SCENE_LINE" ]; then
    record "engine.scene" yes "${SCENE_LINE:0:150}"
  else
    record "engine.scene" no "no 'Scene … loaded' line from the engine in logcat"
    failures=$((failures + 1))
  fi
fi

echo "--------------------------------------------------------------"
echo "on-device checks: $(( $(wc -l < "$RESULTS") - failures )) passed, $failures failed"
echo "device screenshots: ci-logs/emulator-shots/"
[ "$failures" -eq 0 ] && record "result" yes "APK installed, launched and ran a sample game on $MODEL"
finish "$([ "$failures" -eq 0 ] && echo 0 || echo 1)"
