#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Lumen2D CI helper — run a build command and make failures diagnosable.
#
# Runs the command with its output teed to `ci-logs/<label>.log` (uploaded as a
# workflow artifact) and, when a step fails on GitHub Actions, turns the most
# useful log lines into `::error::` annotations plus a step summary. Annotations
# are readable through the GitHub API, so a failing run can be understood even
# when the full job log cannot be downloaded.
#
# Usage: tools/ci-run.sh [--name LABEL] <command> [args...]
# -----------------------------------------------------------------------------
set -uo pipefail

LABEL="build"
if [ "${1:-}" = "--name" ]; then
  LABEL="${2:-build}"
  shift 2
fi
if [ "$#" -eq 0 ]; then
  echo "usage: tools/ci-run.sh [--name LABEL] <command> [args...]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/ci-logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/${LABEL}.log"

"$@" 2>&1 | tee "$LOG"
STATUS="${PIPESTATUS[0]}"

escape() { sed -e 's/%/%25/g' -e ':a;N;$!ba;s/\n/%0A/g'; }

if [ "${GITHUB_ACTIONS:-}" = "true" ] && [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### \`${LABEL}\` — exit code ${STATUS}"
    echo
    echo '```'
    tail -n 40 "$LOG"
    echo '```'
  } >> "$GITHUB_STEP_SUMMARY"
fi

if [ "$STATUS" -ne 0 ] && [ "${GITHUB_ACTIONS:-}" = "true" ]; then
  # GitHub keeps only the first few annotations of a step, so the whole diagnosis goes into
  # exactly two: the most relevant error lines, and the tail of the log.
  HITS="$(grep -nE '(^e: |FAILURE:|> Task .*FAILED|What went wrong|Caused by: |error:|AndroidRuntime|AssertionError|ScriptError|FAIL\])' "$LOG" | head -8 || true)"
  if [ -z "$HITS" ]; then
    HITS="$(tail -n 6 "$LOG" || true)"
  fi
  printf '::error title=%s::%s\n' "${LABEL}" "$(printf '%s' "$HITS" | escape | cut -c1-1500)"
  printf '::error title=%s (log tail)::%s\n' "${LABEL}" "$(tail -n 20 "$LOG" | escape | cut -c1-1500)"
fi

echo "--- ${LABEL}: exit code ${STATUS} (log: ci-logs/${LABEL}.log)"
exit "$STATUS"
