#!/usr/bin/env bash
# Undo tools/debloat.sh and tools/build-install.sh: re-enable every package in debloat.txt that was
# enabled in the baseline backup, give Home back to the launcher that held it, and put Private DNS and
# the accessibility services (TCL Home's redirect) back the way they were. TCL Home stays installed.
# Packages that were already disabled in the baseline stay disabled. Ends by comparing the TV's
# disabled packages with the baseline.
#
# Usage: tools/restore.sh [backup dir]     (default: the oldest folder in backup/, taken before the first change)
set -euo pipefail
. "$(dirname "$0")/lib.sh"
BASE="${1:-$(ls -d "$ROOT"/backup/*/ | head -n 1)}"
BASE="${BASE%/}"
PKGS="$(read_list "$ROOT/debloat.txt")"
tv_connect
echo "baseline: $BASE"

for p in $PKGS; do
  if grep -xF "$p" "$BASE/packages-disabled.txt" >/dev/null; then
    echo "Package $p stays disabled (it was before the first debloat)"
    continue
  fi
  tv pm enable --user 0 "$p" || echo "FAILED: $p"
done

tv cmd package set-home-activity "$(cat "$BASE/home.txt")"

# A key missing from the baseline settings was never set, so delete it rather than write a value.
for entry in global:private_dns_mode global:private_dns_specifier \
             secure:enabled_accessibility_services secure:accessibility_enabled; do
  ns="${entry%%:*}" key="${entry#*:}"
  value="$(sed -n "s/^$key=//p" "$BASE/settings-$ns.txt")"
  if [ -n "$value" ]; then
    tv settings put "$ns" "$key" "$value"
  else
    tv settings delete "$ns" "$key" >/dev/null
  fi
done
echo "Private DNS: $(tv settings get global private_dns_mode)"

if diff <(tv pm list packages -d | sed 's/^package://' | sort) "$BASE/packages-disabled.txt"; then
  echo "disabled packages match the baseline"
else
  echo "disabled packages differ from the baseline (< on the TV now, > in the baseline)" >&2
  exit 1
fi
