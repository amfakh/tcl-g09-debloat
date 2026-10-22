#!/usr/bin/env bash
# Build the launcher (launcher/), install it on the TV, grant what it needs and switch on its Home
# redirect, then press Home and check that TCL Home is what shows. If anything else shows, the redirect
# is switched off again and the script fails, so the TV is never left without a working Home.
#
# Google TV keeps Home: its HOME priority beats any third-party launcher, and on this build neither
# `cmd package set-home-activity` nor the Home role changes that. Instead an accessibility service
# opens TCL Home whenever Google TV's home screen appears (window events only, no key filtering).
# tools/restore.sh switches it off again.
#
# Usage: tools/build-install.sh
set -euo pipefail
. "$(dirname "$0")/lib.sh"
PKG=dev.tcl.home
SERVICE="$PKG/$PKG.HomeRedirectService"

APK="$(build_apk "$ROOT/launcher" tcl-home)"
echo "built: $APK ($(wc -c < "$APK" | tr -d ' ') bytes)"

tv_connect
adb -s "$TV" install -r "$APK" >/dev/null
tv pm grant "$PKG" android.permission.READ_TV_LISTINGS

# Keep every accessibility service already on (tvQuickActions); a reinstall can drop ours from the list.
ENABLED="$(tv settings get secure enabled_accessibility_services)"
[ "$ENABLED" != null ] || ENABLED=""
case ":$ENABLED:" in
  *":$SERVICE:"*) ;;
  *) tv settings put secure enabled_accessibility_services "${ENABLED:+$ENABLED:}$SERVICE" ;;
esac
tv settings put secure accessibility_enabled 1
sleep 2

# Trust only what is on screen after a Home press. Leave Home first: with Google TV's home already on
# screen a Home press changes no window, so the redirect has nothing to react to.
tv am start -a android.settings.SETTINGS >/dev/null
sleep 2
tv input keyevent KEYCODE_HOME
sleep 3
ON_SCREEN="$(tv dumpsys activity activities | grep -m 1 -E 'topResumedActivity|mResumedActivity' || true)"
if printf '%s\n' "$ON_SCREEN" | grep -F "$PKG/" >/dev/null; then
  echo "Home: $PKG"
else
  echo "Home did not land on $PKG:$ON_SCREEN" >&2
  echo "Switching the redirect off; Google TV stays Home." >&2
  REST="$(tv settings get secure enabled_accessibility_services | tr ':' '\n' | grep -vxF "$SERVICE" | paste -sd: - || true)"
  if [ -n "$REST" ]; then
    tv settings put secure enabled_accessibility_services "$REST"
  else
    tv settings delete secure enabled_accessibility_services >/dev/null
  fi
  tv input keyevent KEYCODE_HOME
  exit 1
fi
