"""Log in to Smart Life with a QR code and save the session and the devices, local keys included.

This replaces `tinytuya wizard`: no project on iot.tuya.com and no Access ID or Secret. It is the login the
official Tuya integration of Home Assistant uses (tuya_sharing), so it borrows that integration's client id;
use it only with your own account, on your own devices.

You need the User Code of your Smart Life account (Me > Settings > Account and Security > User Code). The
script prints a QR code; scan it from Smart Life (the scan button on the home screen) and confirm. It writes
``tuya-session.json`` next to devices.json (default folder ~/tuya): the session tokens, which let the launcher
call the Tuya cloud for the air conditioner, and every device with its id, local key and category.
That file is as sensitive as a password: keep it out of the repo.

Usage: uv run --with cryptography --with qrcode tools/tuya-login.py [folder]
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CLIENT_ID = "HA_3y9q4ak7g4ephrvke"
SCHEMA = "haauthorize"
LOGIN_HOST = "https://apigw.iotbing.com"
NONCE_ALPHABET = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
SIGNED_HEADERS = ("X-appKey", "X-requestId", "X-sid", "X-time", "X-token")
IR_AC_CATEGORY = "infrared_ac"
QR_WAIT_S = 180


def hash_key(request_id: str, refresh_token: str) -> str:
    """Key that signs one request: md5 of the request id and the refresh token."""
    return hashlib.md5((request_id + refresh_token).encode()).hexdigest()


def derive_secret(request_id: str, hash_key_hex: str) -> str:
    """The 16 character AES key of one request."""
    return hmac.new(request_id.encode(), hash_key_hex.encode(), hashlib.sha256).hexdigest()[:16]


def restful_sign(hash_key_hex: str, query_enc: str, body_enc: str, headers: dict[str, str]) -> str:
    """HMAC-SHA256 over the signed headers, then the encrypted query and body."""
    parts = [f"{h}={headers[h]}" for h in SIGNED_HEADERS if headers.get(h)]
    return hmac.new(hash_key_hex.encode(), ("||".join(parts) + query_enc + body_enc).encode(), hashlib.sha256).hexdigest()


def encrypt_payload(text: str, secret: str, nonce: str | None = None) -> str:
    """AES-GCM with a 12 character nonce; the result is base64(nonce) followed by base64(ciphertext + tag)."""
    nonce = nonce or "".join(secrets.choice(NONCE_ALPHABET) for _ in range(12))
    sealed = AESGCM(secret.encode()).encrypt(nonce.encode(), text.encode(), None)
    return base64.b64encode(nonce.encode()).decode() + base64.b64encode(sealed).decode()


def decrypt_payload(b64: str, secret: str) -> str:
    """The server answers with base64(nonce(12) + ciphertext + tag)."""
    raw = base64.b64decode(b64)
    return AESGCM(secret.encode()).decrypt(raw[:12], raw[12:], None).decode()


class Cloud:
    """The signed requests of the Smart Life sharing API, with the session of one login."""

    def __init__(self, session: dict) -> None:
        self.session = session

    def request(self, method: str, path: str, params: dict | None = None, body: dict | None = None) -> dict:
        """One signed, encrypted request; returns the decrypted `result`."""
        request_id = str(uuid.uuid4())
        key = hash_key(request_id, self.session["refreshToken"])
        secret = derive_secret(request_id, key)
        url = self.session["endpoint"] + path
        query_enc = body_enc = ""
        if params:
            query_enc = encrypt_payload(json.dumps(params), secret)
            url += "?encdata=" + urllib.parse.quote(query_enc, safe="")
        if body:
            body_enc = encrypt_payload(json.dumps(body), secret)
        headers = {"X-appKey": CLIENT_ID, "X-requestId": request_id, "X-sid": "", "X-time": str(int(time.time() * 1000)),
                   "X-token": self.session["accessToken"]}
        headers["X-sign"] = restful_sign(key, query_enc, body_enc, headers)
        if body_enc:
            headers["Content-Type"] = "application/json"
        data = json.dumps({"encdata": body_enc}).encode() if body_enc else None
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=20) as r:
            answer = json.load(r)
        if not answer.get("success"):
            raise SystemExit(f"Tuya {answer.get('code')}: {answer.get('msg')} ({path})")
        result = answer.get("result")
        return json.loads(decrypt_payload(result, secret)) if isinstance(result, str) and result else result


def login(user_code: str) -> dict:
    """Shows a QR code and waits until Smart Life confirms it; returns the session."""
    import qrcode

    query = urllib.parse.urlencode({"clientid": CLIENT_ID, "usercode": user_code, "schema": SCHEMA})
    with urllib.request.urlopen(urllib.request.Request(f"{LOGIN_HOST}/v1.0/m/life/home-assistant/qrcode/tokens?{query}", method="POST"), timeout=20) as r:
        answer = json.load(r)
    if not answer.get("success"):
        raise SystemExit(f"Could not get a QR code: {answer.get('msg')} (is the User Code right?)")
    token = answer["result"]["qrcode"]
    code = qrcode.QRCode(border=1)
    code.add_data(f"tuyaSmart--qrLogin?token={token}")
    code.print_ascii(invert=True)
    print("Scan this with Smart Life (the scan button on its home screen) and confirm.")
    poll = urllib.parse.urlencode({"clientid": CLIENT_ID, "usercode": user_code})
    deadline = time.time() + QR_WAIT_S
    while time.time() < deadline:
        time.sleep(2)
        try:
            with urllib.request.urlopen(f"{LOGIN_HOST}/v1.0/m/life/home-assistant/qrcode/tokens/{token}?{poll}", timeout=20) as r:
                answer = json.load(r)
        except OSError:
            continue
        if answer.get("success"):
            r = answer["result"]
            return {"uid": r["uid"], "username": r.get("username"), "accessToken": r["access_token"],
                    "refreshToken": r["refresh_token"], "expiresAt": int(answer["t"]) + int(r["expire_time"]) * 1000,
                    "endpoint": r["endpoint"], "terminalId": r["terminal_id"], "userCode": user_code}
    raise SystemExit("The QR code expired without a confirmation. Run the script again.")


def devices(cloud: Cloud) -> list[dict]:
    """Every device in every home, with its local key; infrared ACs also get their limits."""
    found = []
    for home in cloud.request("GET", "/v1.0/m/life/users/homes") or []:
        for d in cloud.request("GET", "/v1.0/m/life/ha/home/devices", {"homeId": str(home["ownerId"])}) or []:
            entry = {"id": d["id"], "name": d.get("name"), "key": d.get("local_key", ""), "category": d.get("category"),
                     "product": d.get("product_name"), "sub": bool(d.get("sub")), "online": bool(d.get("online")), "home": home.get("name")}
            if d.get("category") == IR_AC_CATEGORY:
                spec = cloud.request("GET", f"/v1.1/m/life/{d['id']}/specifications") or {}
                entry["ac"] = {f["code"]: json.loads(f["values"]) if isinstance(f.get("values"), str) else f.get("values")
                               for f in spec.get("functions", []) if f.get("code") in ("T", "M", "F")}
            found.append(entry)
    return found


def main() -> None:
    """Asks for the User Code, logs in, lists the devices and saves both."""
    folder = Path(sys.argv[1] if len(sys.argv) > 1 else Path.home() / "tuya")
    folder.mkdir(parents=True, exist_ok=True)
    user_code = input("Smart Life User Code: ").strip()
    session = login(user_code)
    found = devices(Cloud(session))
    out = folder / "tuya-session.json"
    out.write_text(json.dumps({"session": session, "devices": found}, ensure_ascii=False, indent=1))
    os.chmod(out, 0o600)
    print(f"\nSaved {len(found)} devices and the session to {out}")
    for d in found:
        print(f"  {d['name']:<42} {d['category']:<12} {'online' if d['online'] else 'offline'}")


if __name__ == "__main__":
    main()
