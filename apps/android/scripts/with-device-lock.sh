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

# Agents from before this lock took /tmp/handheld-thor.lock around their work on
# the Thor, so this takes that one too until none do.
locks=("/tmp/handheld-device-${ANDROID_SERIAL:-default}.lock" /tmp/handheld-thor.lock)
held=()

release() {
  # Bash 3.2, macOS's, calls an empty array unbound.
  for lock in ${held[@]+"${held[@]}"}; do rmdir "$lock" 2>/dev/null || true; done
}
trap release EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

# Always in the same order, so two holders can't each wait on the other.
for lock in "${locks[@]}"; do
  if ! mkdir "$lock" 2>/dev/null; then
    echo "Waiting for $lock, which another session holds" >&2
    until mkdir "$lock" 2>/dev/null; do sleep 2; done
  fi
  held+=("$lock")
done

"$@"
