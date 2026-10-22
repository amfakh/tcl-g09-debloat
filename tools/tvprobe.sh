#!/usr/bin/env bash
# Build the TV provider probe (tools/tvprobe), install it, grant READ_TV_LISTINGS, run it once, print
# what it saw, then uninstall it so nothing stays on the TV. Shows which apps' Continue watching and
# channel rows a third-party launcher can read.
#
# `live` instead tunes the antenna tuner inside the probe's own TvView for 12 seconds and logs whether
# video arrives: the test for showing live TV behind a launcher's home screen.
#
# Needs JDK 17 and the Android SDK (platforms;android-34, build-tools;34.0.0).
# Usage: tools/tvprobe.sh [live]
set -euo pipefail
. "$(dirname "$0")/lib.sh"
PKG=dev.tcl.tvprobe
APK="$(build_apk "$ROOT/tools/tvprobe" tvprobe)"

tv_connect
adb -s "$TV" install -r "$APK" >/dev/null
tv pm grant "$PKG" android.permission.READ_TV_LISTINGS
tv logcat -c
if [ "${1:-}" = live ]; then
  tv am start -W -n "$PKG/.LivePreviewActivity" >/dev/null
  sleep 14
else
  tv am start -W -n "$PKG/.ProbeActivity" >/dev/null
  sleep 2
fi
# This firmware prints the tag as "TvProbe :", with a space before the colon.
tv logcat -d -s TvProbe:I | sed -n 's/.*TvProbe *: //p'
adb -s "$TV" uninstall "$PKG" >/dev/null
