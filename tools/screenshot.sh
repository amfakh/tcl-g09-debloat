#!/usr/bin/env bash
# Press the given remote keys on the TV, then save what is on screen to shots/<name>.png.
# Keys are Android KEYCODE_ names without the prefix: HOME, BACK, DPAD_DOWN, DPAD_CENTER, TV_INPUT ...
#
# Usage: tools/screenshot.sh <name> [KEY ...]     e.g. tools/screenshot.sh 01-home HOME
set -euo pipefail
. "$(dirname "$0")/lib.sh"
NAME="${1:?usage: tools/screenshot.sh <name> [KEY ...]}"
shift
tv_connect

for key in "$@"; do
  tv input keyevent "KEYCODE_$key"
  sleep 1
done
# Give the last key's screen time to finish its animation.
[ $# -eq 0 ] || sleep 2

mkdir -p "$ROOT/shots"
OUT="$ROOT/shots/$NAME.png"
adb -s "$TV" exec-out screencap -p > "$OUT"
if [ ! -s "$OUT" ]; then
  # Live TV, HDMI and DRM apps such as Netflix are protected surfaces: screencap returns 0 bytes.
  rm -f "$OUT"
  echo "empty screenshot (protected content). On screen now:" >&2
  tv dumpsys activity activities | grep -m 1 -E 'topResumedActivity|mResumedActivity' >&2 || true
  exit 1
fi
echo "$OUT"
