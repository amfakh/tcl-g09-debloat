"""Check what a launcher could read from the owner's Plex server through the Plex API.

Reads the token from ``plex.local`` in the repo root (one line, git-ignored), finds the servers on the
account through plex.tv, tries each connection, then counts On Deck, Continue Watching, Recently added
and the movie libraries, and times one small poster. Prints counts and connection kinds only: never
the token, server names, addresses or titles.

Usage: python3 -I tools/plex-check.py
"""

import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HEADERS = {
    "Accept": "application/json",
    "X-Plex-Client-Identifier": "tcl-tv-plex-check",
    "X-Plex-Product": "TCL TV plex-check",
}


def get(url: str, token: str, timeout: float = 8) -> tuple[int, bytes, float]:
    """Fetch a URL with the Plex headers.

    Args:
        url: Absolute URL to fetch.
        token: The X-Plex-Token to send.
        timeout: Seconds before giving up.

    Returns:
        The HTTP status, the body and the elapsed milliseconds.
    """
    req = urllib.request.Request(url, headers={**HEADERS, "X-Plex-Token": token})
    start = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read()
        return resp.status, body, (time.monotonic() - start) * 1000


def container(base: str, path: str, token: str) -> dict:
    """Return the MediaContainer of a server endpoint.

    Args:
        base: Server base URI.
        path: Endpoint path with its query string.
        token: The X-Plex-Token to send.

    Returns:
        The decoded MediaContainer object.
    """
    return json.loads(get(base + path, token)[1])["MediaContainer"]


def kind(conn: dict) -> str:
    """Describe a connection without revealing its address.

    Args:
        conn: One entry of a resource's ``connections`` list.

    Returns:
        A label such as ``remote https`` or ``relay``.
    """
    if conn.get("relay"):
        return "relay"
    return f"{'local' if conn.get('local') else 'remote'} {conn.get('protocol')}"


def main() -> int:
    """Run the check and print the findings.

    Returns:
        The process exit code.
    """
    token_file = ROOT / "plex.local"
    token = token_file.read_text().strip() if token_file.exists() else ""
    if not token:
        print(f"Put the X-Plex-Token on one line in {token_file.name} (git-ignored), then run again.")
        return 1

    # On Deck is per Plex user: report whose token this is among the Plex Home users, by position only.
    me = json.loads(get("https://plex.tv/api/v2/user", token)[1])
    try:
        home = json.loads(get("https://plex.tv/api/v2/home/users", token)[1])
        users = home.get("users", home) if isinstance(home, dict) else home
    except Exception as e:  # noqa: BLE001 - accounts without Plex Home answer with an error
        print(f"Plex Home users: unavailable ({type(e).__name__})")
        users = []
    print(f"Plex Home users: {len(users)}")
    for n, u in enumerate(users, 1):
        mine = " <- this token" if u.get("id") == me.get("id") else ""
        print(f"  user #{n}: admin={u.get('admin')} restricted={u.get('restricted')} protected={u.get('protected')}{mine}")

    # Switching to a Plex Home user without a PIN returns that user's own token, which is how a launcher
    # shows their On Deck. The tokens stay in memory and are never printed.
    for n, u in enumerate(users, 1):
        if u.get("id") == me.get("id") or u.get("protected"):
            continue
        stage = "switch"
        try:
            req = urllib.request.Request(
                f"https://plex.tv/api/v2/home/users/{u['uuid']}/switch",
                data=b"",
                method="POST",
                headers={**HEADERS, "X-Plex-Token": token},
            )
            with urllib.request.urlopen(req, timeout=8) as resp:
                user_token = json.loads(resp.read())["authToken"]
            stage = "resources"
            _, rbody, _ = get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", user_token)
            stage = "server"
            decks = []
            for res in (r for r in json.loads(rbody) if "server" in r.get("provides", "")):
                # Same order as below: direct remote first, local next, the relay last.
                conns = sorted(res.get("connections", []), key=lambda c: (bool(c.get("relay")), bool(c.get("local"))))
                deck = "unreachable"
                for conn in conns:
                    try:
                        size = container(conn["uri"], "/library/onDeck", res.get("accessToken") or user_token).get("size", 0)
                        deck = f"{size} via {kind(conn)}"
                        break
                    except Exception:  # noqa: BLE001, S112 - try the next route
                        continue
                decks.append(deck)
            print(f"  user #{n} switch: ok, On Deck per server: {', '.join(decks) or 'no reachable server'}")
        except Exception as e:  # noqa: BLE001 - report and keep checking the other users
            reason = getattr(e, "reason", None) or getattr(e, "code", None)
            print(f"  user #{n} switch: failed at {stage} ({type(e).__name__}: {type(reason).__name__ if isinstance(reason, Exception) else reason})")

    _, body, _ = get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", token)
    servers = [r for r in json.loads(body) if "server" in r.get("provides", "")]
    print(f"servers on the account: {len(servers)}")

    for n, server in enumerate(servers, 1):
        print(f"server #{n}: owned={server.get('owned')} connections={len(server.get('connections', []))}")
        # Direct connections first, the Plex relay last.
        conns = sorted(server.get("connections", []), key=lambda c: (bool(c.get("relay")), bool(c.get("local"))))
        base = None
        for conn in conns:
            try:
                status, _, ms = get(conn["uri"] + "/identity", server.get("accessToken") or token, timeout=5)
                print(f"  {kind(conn)}: HTTP {status} in {ms:.0f} ms")
                if status == 200 and base is None:
                    base = conn["uri"]
            except Exception as e:  # noqa: BLE001 - any failure just means this route is unusable
                print(f"  {kind(conn)}: unreachable ({type(e).__name__})")
        if base is None:
            continue

        stoken = server.get("accessToken") or token
        on_deck = container(base, "/library/onDeck", stoken)
        print(f"  On Deck: {on_deck.get('size', 0)} items")
        try:
            hub = container(base, "/hubs/continueWatching", stoken)
            print(f"  Continue Watching hub: {sum(h.get('size', 0) for h in hub.get('Hub', []))} items")
        except Exception as e:  # noqa: BLE001 - older servers lack this hub
            print(f"  Continue Watching hub: unavailable ({type(e).__name__})")
        recent = container(base, "/library/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=50", stoken)
        print(f"  Recently added: {recent.get('size', 0)} items (first 50)")
        for section in container(base, "/library/sections", stoken).get("Directory", []):
            total = container(
                base, f"/library/sections/{section['key']}/all?X-Plex-Container-Start=0&X-Plex-Container-Size=0", stoken
            ).get("totalSize", "?")
            print(f"  library type={section.get('type')}: {total} items")

        thumb = next((i.get("thumb") for i in on_deck.get("Metadata", []) + recent.get("Metadata", []) if i.get("thumb")), None)
        if thumb:
            query = urllib.parse.urlencode({"width": 320, "height": 180, "minSize": 1, "upscale": 1, "url": thumb})
            status, data, ms = get(f"{base}/photo/:/transcode?{query}", stoken)
            print(f"  poster 320x180: HTTP {status}, {len(data) // 1024} KB in {ms:.0f} ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
