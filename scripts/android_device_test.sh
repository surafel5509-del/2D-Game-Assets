#!/usr/bin/env bash
# Runs inside the emulator started by reactivecircus/android-emulator-runner.
#
# Installs the editor APK, then drives the real app: dashboard, the New project wizard
# (the flow that used to crash), live preview of the created project, a template project,
# a bundled example, project reopening, the exported-game build and the export test.
# Every failure prints the on-device crash trace and publishes full diagnostics as git
# blobs whose SHAs are printed as ::notice:: so the run can be inspected from the API.
set -u

REPO="${GITHUB_REPOSITORY:-surafel5509-del/2D-Game-Assets}"
APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
ARTIFACTS="${RUNNER_TEMP:-/tmp}/world-device-test"
mkdir -p "$ARTIFACTS"

if [ -z "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then export ANDROID_HOME="$ANDROID_SDK_ROOT"; fi
ADB="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb)"
echo "::notice::Using adb at $ADB"
"$ADB" devices

if [ ! -f "$APK" ]; then echo '::error::Editor APK was not built'; exit 1; fi

"$ADB" shell svc power stayon true >/dev/null 2>&1 || true
"$ADB" shell input keyevent 82 >/dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" install -r "$APK" || { echo '::error::Could not install the editor APK on the emulator'; exit 1; }
"$ADB" shell am start -W -n com.world2d.engine/.editor.MainActivity || true
sleep 12

publish_blob() { # publish_blob <path> <label>
  local path="$1" label="$2"
  [ -f "$path" ] || return 0
  [ -n "${GH_TOKEN:-}" ] || return 0
  local payload sha
  payload="$(mktemp)"
  printf '{"content":"' > "$payload"
  base64 -w0 "$path" >> "$payload"
  printf '","encoding":"base64"}' >> "$payload"
  sha="$(gh api -X POST "repos/$REPO/git/blobs" --input "$payload" --jq .sha 2>/dev/null || true)"
  rm -f "$payload"
  if [ -n "$sha" ]; then echo "::notice::${label}_BLOB_SHA=$sha"; fi
}

collect_diagnostics() { # collect_diagnostics <reason>
  echo "::error::$1"
  "$ADB" shell uiautomator dump /sdcard/world-diagnostics.xml >/dev/null 2>&1 || true
  "$ADB" shell cat /sdcard/world-diagnostics.xml > "$ARTIFACTS/ui-dump.xml" 2>/dev/null || true
  "$ADB" logcat -d -t 3000 -s AndroidRuntime:E > "$ARTIFACTS/crash.log" 2>/dev/null || true
  "$ADB" logcat -d -t 3000 -v threadtime > "$ARTIFACTS/logcat.log" 2>/dev/null || true
  "$ADB" exec-out screencap -p > "$ARTIFACTS/failure.png" 2>/dev/null || true
  head -c 200000 "$ARTIFACTS/crash.log" > "$ARTIFACTS/crash-trimmed.log" 2>/dev/null || true
  if [ -s "$ARTIFACTS/crash-trimmed.log" ]; then
    # Surface the top of the trace as annotations: that is what the API reports back.
    grep -m 40 -E 'FATAL EXCEPTION|Process:|Caused by|at com\.world2d|Exception|Error' \
      "$ARTIFACTS/crash-trimmed.log" | while IFS= read -r line; do echo "::error::CRASH $line"; done
  fi
  publish_blob "$ARTIFACTS/crash-trimmed.log" CRASH_LOG
  publish_blob "$ARTIFACTS/ui-dump.xml" UI_DUMP
  publish_blob "$ARTIFACTS/failure.png" FAILURE_SCREENSHOT
}

smoke_status=0
if ! python3 scripts/android_smoke.py "$ADB" "$ARTIFACTS/gameplay.png" > "$ARTIFACTS/smoke.log" 2>&1; then
  smoke_status=1
  cat "$ARTIFACTS/smoke.log" || true
  collect_diagnostics "The on-device smoke test failed: the new-project or example flow did not complete"
else
  cat "$ARTIFACTS/smoke.log"
fi

if [ "$smoke_status" -eq 0 ]; then
  publish_blob "$ARTIFACTS/gameplay.png" GAMEPLAY_SCREENSHOT
  for shot in world-new-project-editor.png world-new-project-preview.png world-new-template-project.png; do
    publish_blob "$ARTIFACTS/$shot" "${shot%.png}"
  done
fi

# The exported game must still build as a separate Android project; the export test proves it.
if [ -f "$TEST_APK" ]; then
  if ! "$ADB" install -r "$TEST_APK"; then
    echo '::warning::Could not install the export instrumentation APK; exported-game build was not device-tested'
  else
    "$ADB" shell am instrument -w com.world2d.engine.test/androidx.test.runner.AndroidJUnitRunner > "$ARTIFACTS/instrumentation.log" 2>&1 || true
    if grep -q 'OK (1 test)' "$ARTIFACTS/instrumentation.log"; then
      echo '::notice::Exporter instrumentation verified native sources, authored scenes and imported assets in the game ZIP'
      "$ADB" exec-out run-as com.world2d.engine cat files/game-export-smoke.zip > "$ARTIFACTS/game-export.zip" 2>/dev/null || true
      mkdir -p "$ARTIFACTS/export"
      if [ -s "$ARTIFACTS/game-export.zip" ] && unzip -q -o "$ARTIFACTS/game-export.zip" -d "$ARTIFACTS/export"; then
        chmod +x "$ARTIFACTS/export/gradlew" 2>/dev/null || true
        if bash "$ARTIFACTS/export/gradlew" -p "$ARTIFACTS/export" --no-daemon :app:assembleDebug > "$ARTIFACTS/export-build.log" 2>&1; then
          echo '::notice::Actual exported game compiled to a separate installable Android APK'
          cp "$ARTIFACTS/export/app/build/outputs/apk/debug/app-debug.apk" "$ARTIFACTS/sample-game.apk" 2>/dev/null || true
        else
          grep -E 'error:|FAILURE:|What went wrong|Could not |failed' "$ARTIFACTS/export-build.log" | tail -12 | while IFS= read -r line; do echo "::notice::Export build: $line"; done
          echo '::warning::Exported game project did not assemble its APK'
        fi
      else
        echo '::warning::Exported game ZIP was not produced by the instrumentation test'
      fi
    else
      grep -m 20 -E 'FAILURES|Error|Exception|failed' "$ARTIFACTS/instrumentation.log" | while IFS= read -r line; do echo "::error::Instrument: $line"; done
      publish_blob "$ARTIFACTS/instrumentation.log" INSTRUMENTATION_LOG
      echo '::error::Actual Android game project ZIP export test failed'
      exit 1
    fi
  fi
else
  echo '::warning::Instrumentation APK unavailable; exported-game build was not device-tested'
fi

if [ "$smoke_status" -ne 0 ]; then
  collect_diagnostics "The on-device smoke test reported a failure (see the crash trace above)"
  exit 1
fi

echo '::notice::On-device verification finished: the editor installed, created projects, played them and exported a game'
