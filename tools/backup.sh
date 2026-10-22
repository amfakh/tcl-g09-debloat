#!/usr/bin/env bash
# Save the TV's current state to backup/<timestamp>/ before anything changes: package lists, getprop,
# every setting, dumpsys package, the Home component and memory. tools/debloat.sh runs this first, and
# tools/restore.sh reads the oldest folder as the state to go back to.
#
# Usage: tools/backup.sh
set -euo pipefail
. "$(dirname "$0")/lib.sh"
tv_connect

DIR="$ROOT/backup/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DIR"
tv pm list packages | sed 's/^package://' | sort > "$DIR/packages-all.txt"
tv pm list packages -s | sed 's/^package://' | sort > "$DIR/packages-system.txt"
tv pm list packages -3 | sed 's/^package://' | sort > "$DIR/packages-3rd.txt"
tv pm list packages -d | sed 's/^package://' | sort > "$DIR/packages-disabled.txt"
tv getprop > "$DIR/getprop.txt"
for ns in system secure global; do
  tv settings list "$ns" > "$DIR/settings-$ns.txt"
done
tv dumpsys package > "$DIR/dumpsys-package.txt"
tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
  | tail -n 1 > "$DIR/home.txt"
tv cat /proc/meminfo > "$DIR/meminfo.txt"
echo "backup: $DIR"
