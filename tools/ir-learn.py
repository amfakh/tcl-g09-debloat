"""Teach the Smart IR hub the air conditioner's remote, one state at a time.

For each state in STATES the script asks for it, puts the hub in learning mode and waits for you to point
the AC's own remote at the hub and press the button that sends that state. An AC remote sends its whole
display on every press, so that press decides the state: the power button for Off, a temperature button
that lands on the wanted temperature for the others. Set the mode and fan the way you normally use them,
and aim the remote away from the hub until the last press. The learned codes are saved in ``ac_codes.json`` next
to devices.json. A state already in that file is skipped, so a run can be repeated and only learns what
is missing; delete a state from the file to learn it again. The file follows STATES: same order, and a
state no longer in STATES is dropped from it.

Usage: uv run --with tinytuya tools/ir-learn.py [path/to/devices.json]     (default: ~/tuya/devices.json)
"""

import json
import sys
from pathlib import Path

from tinytuya.Contrib import IRRemoteControlDevice

# Label shown on the TV, and what the remote should show once the press that is learned has been made.
STATES = [
    ("On", "on, from off (the press is the power button)"),
    ("Off", "off (the press is the power button)"),
    ("Cool 22 high", "cooling at 22 °C, fan high"),
    ("Cool 22 auto", "cooling at 22 °C, fan auto"),
    ("Dry", "dry mode, the way you normally use it"),
]
HUB_NAME = "Smart IR"
LEARN_TIMEOUT_S = 30

devices = Path(sys.argv[1] if len(sys.argv) > 1 else Path.home() / "tuya" / "devices.json")
codes_file = devices.with_name("ac_codes.json")
codes = json.loads(codes_file.read_text()) if codes_file.exists() else {}


def save() -> None:
    """Write the learned codes in the order of STATES, without any state STATES no longer lists."""
    ordered = {label: codes[label] for label, _ in STATES if label in codes}
    codes_file.write_text(json.dumps(ordered, ensure_ascii=False, indent=1))


hub = next(d for d in json.loads(devices.read_text()) if d["name"] == HUB_NAME)
ir = IRRemoteControlDevice(hub["id"], hub["ip"], hub["key"], version=float(hub["version"]), persist=False)

for label, how in STATES:
    if label in codes:
        continue
    input(f"\n[{label}] The remote should end up showing: {how}.\nPress Enter, then make that last press towards the hub: ")
    code = ir.receive_button(timeout=LEARN_TIMEOUT_S)
    if not isinstance(code, str):
        sys.exit(f"No code learned for {label} (got {code!r}). Run the script again to retry from here.")
    codes[label] = code
    save()
    print(f"learned {label} ({len(code)} characters)")

save()
print(f"\nAll {len(STATES)} states are in {codes_file}. Next: tools/smart-home.sh")
