#!/usr/bin/env bash
# Disable every package in a list (default debloat.txt) for user 0, then point Private DNS at an
# ad-blocking resolver. Nothing is uninstalled, and tools/restore.sh undoes all of it. A backup is
# taken first. Safe to run again, for example after a factory reset.
#
# Usage: tools/debloat.sh [list]
#        PRIVATE_DNS= tools/debloat.sh        leaves the DNS setting alone
set -euo pipefail
. "$(dirname "$0")/lib.sh"
LIST="${1:-$ROOT/debloat.txt}"
PRIVATE_DNS="${PRIVATE_DNS-dns.adguard-dns.com}"
PKGS="$(read_list "$LIST")"
tv_connect
"$ROOT/tools/backup.sh"

# Print "yes" when the package is still in PKGS.
listed() {
  printf '%s\n' "$PKGS" | grep -xF "$1" >/dev/null && echo yes || true
}

# Drop a package from PKGS and say why.
skip() {
  echo "skipping $1: $2" >&2
  PKGS="$(printf '%s\n' "$PKGS" | grep -vxF "$1" || true)"
}

# A package that is not installed (a user app after a factory reset) has nothing to disable.
INSTALLED="$(tv pm list packages | sed 's/^package://')"
for p in $PKGS; do
  printf '%s\n' "$INSTALLED" | grep -xF "$p" >/dev/null || skip "$p" "not installed"
done

# Home guard: with the app that owns Home disabled, the setup wizard takes Home on Google TV and can
# crash-loop. Install and confirm a replacement launcher first.
HOME_PKG="$(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
  | tail -n 1 | cut -d/ -f1)"
[ -z "$(listed "$HOME_PKG")" ] || skip "$HOME_PKG" "it owns Home and nothing replaces it yet"

# A Wi-Fi network saved during first setup belongs to setupwraith, and Android deletes it the moment
# setupwraith is disabled: the TV drops off Wi-Fi and ADB. Forget and re-join the network first.
SETUP=com.google.android.tungsten.setupwraith
if [ -n "$(listed "$SETUP")" ]; then
  OWNERS="$(tv dumpsys wifi | grep -o 'cname=[^ ]*' || true)"
  # No answer counts as owned: losing the network is worse than a skipped package.
  if [ -z "$OWNERS" ] || printf '%s\n' "$OWNERS" | grep setupwraith >/dev/null; then
    skip "$SETUP" "it may own a saved Wi-Fi network; forget and re-join it in Settings first"
  fi
fi

# A package asking for TV_INPUT_HARDWARE usually drives HDMI or the tuner. Disabled, those inputs show
# "No Signal" for good, and only a full restore plus a cold boot brings the picture back.
NAMES="$(printf '%s ' $PKGS)"
HW="$(tv "for p in $NAMES; do dumpsys package \$p | sed -n -E '/requested permissions:/,/(install|runtime) permissions:|User [0-9]+:/p' | grep TV_INPUT_HARDWARE >/dev/null && echo \$p; done; true")"
for p in $HW; do
  skip "$p" "it requests TV_INPUT_HARDWARE, so it may drive the HDMI or tuner inputs"
done

# One pm call per package: a chained shell command can stop silently after the first failure.
for p in $PKGS; do
  tv pm disable-user --user 0 "$p" || echo "FAILED: $p"
done

if [ -n "$PRIVATE_DNS" ]; then
  tv settings put global private_dns_specifier "$PRIVATE_DNS"
  tv settings put global private_dns_mode hostname
  echo "Private DNS: $PRIVATE_DNS"
fi

# Check the TV's own answer, not pm's exit codes.
DISABLED="$(tv pm list packages -d | sed 's/^package://')"
MISSING="$(printf '%s\n' "$PKGS" | grep -vxF -f <(printf '%s\n' "$DISABLED") || true)"
echo "disabled on the TV: $(printf '%s\n' "$DISABLED" | grep -c .) packages; listed: $(printf '%s\n' "$PKGS" | grep -c .)"
if [ -n "$MISSING" ]; then
  echo "still enabled although listed:" >&2
  printf '  %s\n' $MISSING >&2
  exit 1
fi
