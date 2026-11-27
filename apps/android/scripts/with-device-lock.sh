#!/usr/bin/env bash
# Runs a command while holding the Android dev device's lock (see AGENTS.md).
#
#   apps/android/scripts/with-device-lock.sh adb shell input -d 0 keyevent ENTER
set -euo pipefail

if [ $# -eq 0 ]; then
  echo "usage: $0 <command> [args...]" >&2
  exit 2
fi

lock="/tmp/handheld-device-${ANDROID_SERIAL:-default}.lock"
trap 'exit 130' INT
trap 'exit 143' TERM HUP

if ! mkdir "$lock" 2>/dev/null; then
  echo "Waiting for $lock, which another session holds" >&2
  until mkdir "$lock" 2>/dev/null; do sleep 2; done
fi
trap 'rmdir "$lock"' EXIT

"$@"
