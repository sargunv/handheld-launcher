#!/usr/bin/env bash
# Runs a command while holding the Android dev device's lock, so the agents and
# people sharing a device take turns installing and driving it.
#
# The lock is a directory, /tmp/handheld-device-$ANDROID_SERIAL.lock, since
# mkdir either creates it or fails, in one step. This waits while another
# holder has it and removes it when the command ends, even when it fails or is
# interrupted. A holder that was killed outright leaves it behind; remove it
# once nobody is using the device.
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
