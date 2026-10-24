#!/usr/bin/env bash
# Put the smart devices (Tuya: BARDI, Smart Life) on the TV so Home can show and switch them. Reads what
# tools/tuya-login.py saved in tuya-session.json (device ids, local keys, the cloud session) and sends the air
# purifier, the water fountain, the IR hub (for its temperature and humidity) and the air conditioner to the
# launcher's files folder as smart_home.json. The
# local keys and the session stay out of the repo and the APK.
# The purifier and the fountain are reached by IP and protocol version, which come from the devices.json that
# `tinytuya wizard` wrote in the same folder; give them a fixed address in the router.
#
# Usage: tools/smart-home.sh [folder]      (default: ~/tuya, with tuya-session.json and devices.json in it)
set -euo pipefail
. "$(dirname "$0")/lib.sh"
FOLDER="${1:-$HOME/tuya}"
DEST=/sdcard/Android/data/dev.tcl.home/files
tv_connect

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
python3 -I - "$FOLDER" > "$TMP" <<'PY'
import json
import os
import sys

folder = sys.argv[1]
login = json.load(open(os.path.join(folder, "tuya-session.json")))
local = {d["id"]: d for d in json.load(open(os.path.join(folder, "devices.json")))}
KINDS = {"kj": "purifier", "cwysj": "fountain", "wnykq": "sensor"}
devices = []
for d in login["devices"]:
    if d["category"] == "infrared_ac":
        devices.append({"kind": "ac", "id": d["id"], **d.get("ac", {})})
    elif d["category"] in KINDS and local.get(d["id"], {}).get("ip"):
        devices.append({"kind": KINDS[d["category"]], "id": d["id"], "key": d["key"],
                        "ip": local[d["id"]]["ip"], "version": local[d["id"]]["version"]})
print(json.dumps({"cloud": login["session"], "devices": devices}))
PY
tv mkdir -p "$DEST"
adb -s "$TV" push "$TMP" "$DEST/smart_home.json" >/dev/null
echo "sent $(python3 -I -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["devices"]))' "$TMP") devices to the TV"
